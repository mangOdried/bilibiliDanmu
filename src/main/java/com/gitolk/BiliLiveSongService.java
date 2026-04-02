package com.gitolk;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import pojo.BeatmapDownloadStatus;
import pojo.Song;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * B站直播点歌核心服务：解析弹幕中的点歌指令，查询谱面信息，入队并异步下载。
 * <p>
 * 工作流程：
 * <ol>
 *   <li>从弹幕内容中提取"点歌"关键词及其后的参数</li>
 *   <li>判断参数是数字 ID 还是关键词，分别调用 Sayobot API 解析</li>
 *   <li>将解析结果构造为 {@link Song} 对象加入 {@link PlayList}</li>
 *   <li>在后台线程池中异步下载 .osz 谱面文件</li>
 * </ol>
 * </p>
 */
public class BiliLiveSongService {

    /** 预编译：纯数字判断 */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");

    /** 预编译：文件名中不安全字符 */
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    /** 关联的播放列表实例 */
    private final PlayList playList;

    /** 歌单容量，同时也决定下载队列的上限（不可能有比歌单更多的下载任务） */
    private static final int PLAYLIST_CAPACITY = 20;

    /**
     * 谱面下载线程池。
     * <p>
     * 核心 2 线程 + 最大 4 线程 + 有界队列（= 歌单容量）。
     * <ul>
     *   <li>队列容量与歌单容量对齐：歌单最多 {@value #PLAYLIST_CAPACITY} 首，
     *       下载任务数不可能超过该值</li>
     *   <li>CallerRunsPolicy：万一队列和线程全满，由弹幕处理线程同步执行，天然背压</li>
     *   <li>空闲线程 60s 回收；守护线程，主程序退出时自动终止</li>
     * </ul>
     * </p>
     */
    private static final ExecutorService downloadExecutor;

    static {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "beatmap-download-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        downloadExecutor = new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(PLAYLIST_CAPACITY),
                tf,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Sayobot API 返回的谱面解析结果。
     */
    private static final class ResolvedBeatmap {
        /** 谱面集 ID */
        final int sid;
        /** 艺术家名 */
        final String artist;
        /** 谱面标题 */
        final String title;

        ResolvedBeatmap(int sid, String artist, String title) {
            this.sid = sid;
            this.artist = artist != null ? artist : "";
            this.title = title;
        }
    }

    /**
     * 构造服务实例，同时确保 PlayList 单例已初始化。
     */
    public BiliLiveSongService() {
        this.playList = PlayList.ensureInstance(PLAYLIST_CAPACITY);
    }

    /**
     * 规范化弹幕文本：去除 BOM、零宽字符等不可见干扰字符。
     *
     * @param s 原始弹幕文本
     * @return 清洗后的文本
     */
    private static String normalizeDanmuText(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        t = t.replace('\uFEFF', ' ')   // BOM
                .replace('\u200B', ' ') // 零宽空格
                .replace('\u200C', ' ') // 零宽非连接符
                .replace('\u200D', ' ') // 零宽连接符
                .replace('\u2060', ' ');// 单词连接符
        return t.strip();
    }

    /**
     * 处理用户弹幕中的点歌请求。
     * <p>
     * 识别"点歌 xxx"格式的弹幕，查询 Sayobot 获取谱面信息，
     * 入队并触发异步下载。若弹幕不符合点歌格式则返回 false。
     * </p>
     *
     * @param content  弹幕内容
     * @param username 发送弹幕的用户名
     * @return true — 作为点歌处理了；false — 不是点歌格式
     * @throws RuntimeException 找不到歌曲或入队失败时抛出
     */
    public Boolean userAddSong(String content, String username) {
        String raw = normalizeDanmuText(content);

        // 提取"点歌"关键词后面的参数
        String arg;
        if (raw.startsWith("点歌")) {
            arg = raw.substring("点歌".length()).strip();
        } else {
            int idx = raw.indexOf("点歌");
            if (idx < 0) {
                return false;
            }
            arg = raw.substring(idx + "点歌".length()).strip();
        }
        if (arg.isEmpty()) {
            return false;
        }

        // 根据参数类型选择查询方式：纯数字用 ID 查询，否则用关键词搜索
        ResolvedBeatmap resolved;
        if (isNumeric(arg)) {
            resolved = resolveById(arg);
            // Sayobot 查不到时，将纯数字直接视为 sid 兜底入队
            if (resolved == null) {
                try {
                    int parsed = Integer.parseInt(arg);
                    if (parsed > 0) {
                        resolved = new ResolvedBeatmap(parsed, "", null);
                        System.err.println("Sayobot 查 ID 失败，使用弹幕数字作为谱面 sid 入队: " + parsed);
                    }
                } catch (NumberFormatException ignored) {
                    resolved = null;
                }
            }
        } else {
            resolved = resolveByKeyword(arg);
        }

        if (resolved == null || resolved.sid <= 0) {
            throw new RuntimeException("未找到歌曲: " + arg);
        }

        // 构造 Song 对象并设置标题
        Song song = new Song(username, resolved.sid);
        song.setDownloadStatus(BeatmapDownloadStatus.PENDING);

        // 优先使用 resolve 阶段已获取的标题，避免重复 HTTP 请求
        if (resolved.title != null && !resolved.title.trim().isEmpty()) {
            song.setSongTitle(resolved.title);
        } else {
            try {
                playList.fetchSongTitle(song);
            } catch (Exception e) {
                System.err.println("获取歌曲标题失败，使用ID兜底: " + e.getMessage());
            }
        }
        if (song.getSongTitle() == null || song.getSongTitle().trim().isEmpty()) {
            song.setSongTitle(String.valueOf(resolved.sid));
        }

        // 入队
        SongRequestResult result = playList.addSong(song);
        if (!result.isSuccess()) {
            throw new RuntimeException(result.getMessage());
        }

        // 提交异步下载任务
        scheduleDownload(song, resolved);
        return true;
    }

    /**
     * 在后台线程池中异步下载谱面 .osz 文件。
     * <p>
     * 下载开始和结束时均会更新 Song 的状态并通知前端刷新。
     * </p>
     *
     * @param song 对应的歌曲对象
     * @param meta 解析出的谱面元数据
     */
    private void scheduleDownload(Song song, ResolvedBeatmap meta) {
        downloadExecutor.execute(() -> {
            song.setDownloadLocalPath(null);
            song.setDownloadStatus(BeatmapDownloadStatus.DOWNLOADING);
            PlayList.notifyPlaylistChanged();
            try {
                String title = pickDownloadTitle(meta, song);
                String path = performDownloadToDisk(song.getBeatMapId(), meta != null ? meta.artist : "", title);
                song.setDownloadLocalPath(path);
                song.setDownloadStatus(BeatmapDownloadStatus.DONE);
            } catch (IOException ex) {
                System.err.println("下载失败: " + ex.getMessage());
                song.setDownloadLocalPath(null);
                song.setDownloadStatus(BeatmapDownloadStatus.FAILED);
            }
            PlayList.notifyPlaylistChanged();
        });
    }

    /**
     * 选择下载文件名所用的标题：优先取解析元数据中的标题，其次取 Song 标题，最后用 sid。
     */
    private static String pickDownloadTitle(ResolvedBeatmap meta, Song song) {
        if (meta != null && meta.title != null && !meta.title.isEmpty()) {
            return meta.title;
        }
        String t = song.getSongTitle();
        if (t != null && !t.trim().isEmpty()) {
            return t.trim();
        }
        return String.valueOf(song.getBeatMapId());
    }

    /**
     * 使用预编译正则判断字符串是否为纯数字。
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(str).matches();
    }

    /**
     * 通过谱面 ID 查询 Sayobot beatmapinfo 接口。
     *
     * @param idStr 谱面 ID 字符串
     * @return 解析结果；失败返回 null
     */
    private ResolvedBeatmap resolveById(String idStr) {
        try {
            String url = "https://api.sayobot.cn/v2/beatmapinfo?0=" + idStr;
            HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setRequestProperty("Accept", "application/json");
            con.setConnectTimeout(12000);
            con.setReadTimeout(12000);

            int code = con.getResponseCode();
            InputStream stream = (code >= 200 && code < 400) ? con.getInputStream() : con.getErrorStream();
            if (stream == null) {
                System.err.println("beatmapinfo HTTP " + code + " 且无响应体");
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String response = reader.lines().collect(Collectors.joining("\n"));
                JSONObject result = JSONObject.parseObject(response);
                if (result == null) {
                    return null;
                }
                int status = result.getIntValue("status");
                if (status == 0) {
                    JSONObject data = result.getJSONObject("data");
                    if (data != null && data.containsKey("sid")) {
                        int sid = data.getIntValue("sid");
                        return new ResolvedBeatmap(sid, data.getString("artist"), data.getString("title"));
                    }
                } else {
                    System.err.println("beatmapinfo status=" + status + " body=" + response);
                }
            }
        } catch (IOException e) {
            System.err.println("通过 id 获取谱面失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从关键词搜索结果中选出与用户输入最匹配的谱面。
     * <p>
     * 评分规则：
     * <ul>
     *   <li>标题完全匹配 +1000</li>
     *   <li>标题包含关键词 +500</li>
     *   <li>艺术家包含关键词 +200</li>
     *   <li>标题长度与关键词越接近得分越高</li>
     *   <li>Sayobot order 权重也纳入评分</li>
     * </ul>
     * </p>
     *
     * @param data    Sayobot 搜索结果数组
     * @param keyword 用户输入的关键词
     * @return 最佳匹配的 JSONObject；若全部为空则返回首个
     */
    private JSONObject selectBestKeywordMatch(JSONArray data, String keyword) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (item == null || !item.containsKey("sid")) {
                continue;
            }

            String title = item.getString("title");
            String titleU = item.getString("titleU");
            String artist = item.getString("artist");

            String titleLower = title == null ? "" : title.toLowerCase(Locale.ROOT);
            String titleULower = titleU == null ? "" : titleU.toLowerCase(Locale.ROOT);
            String artistLower = artist == null ? "" : artist.toLowerCase(Locale.ROOT);

            int score = 0;
            if (!normalizedKeyword.isEmpty()) {
                if (titleLower.equals(normalizedKeyword) || titleULower.equals(normalizedKeyword)) {
                    score += 1000;
                }
                if (titleLower.contains(normalizedKeyword) || titleULower.contains(normalizedKeyword)) {
                    score += 500;
                }
                if (artistLower.contains(normalizedKeyword)) {
                    score += 200;
                }
                score -= Math.abs(titleLower.length() - normalizedKeyword.length());
            }

            score += Math.round(item.getFloatValue("order") * 10);

            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }

        return best != null ? best : data.getJSONObject(0);
    }

    /**
     * 通过关键词调用 Sayobot 搜索接口查找谱面。
     *
     * @param keyword 搜索关键词
     * @return 最佳匹配的解析结果；失败返回 null
     */
    private ResolvedBeatmap resolveByKeyword(String keyword) {
        try {
            String url = "https://api.sayobot.cn/?post";
            HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            JSONObject body = new JSONObject();
            body.put("cmd", "beatmaplist");
            body.put("limit", 25);
            body.put("offset", 0);
            body.put("type", "search");
            body.put("keyword", keyword);

            String jsonBody = body.toJSONString();
            con.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String response = reader.lines().collect(Collectors.joining("\n"));
                JSONObject result = JSONObject.parseObject(response);
                int status = result.getIntValue("status");
                if (status == 0) {
                    JSONArray data = result.getJSONArray("data");
                    JSONObject matched = selectBestKeywordMatch(data, keyword);
                    if (matched != null && matched.containsKey("sid")) {
                        int sid = matched.getIntValue("sid");
                        return new ResolvedBeatmap(sid, matched.getString("artist"), matched.getString("title"));
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("通过歌名搜索谱面失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 将文件名中不安全的字符替换为下划线。
     */
    private String sanitizeFileName(String name) {
        return UNSAFE_FILENAME_CHARS.matcher(name).replaceAll("_");
    }

    /**
     * 将远程文件下载到本地目录。
     *
     * @param urlStr      下载 URL
     * @param dirName     保存目录名
     * @param displayName 文件显示名（会经过安全化处理）
     * @return 保存后的绝对路径
     * @throws IOException 下载失败时抛出
     */
    private String downloadToFile(String urlStr, String dirName, String displayName) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Accept", "*/*");
        con.setDoInput(true);

        File dir = new File(dirName);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String safeName = sanitizeFileName(displayName);
        File outFile = new File(dir, safeName);
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            out.flush();
        }
        return outFile.getAbsolutePath();
    }

    /**
     * 执行谱面 .osz 文件下载。
     * <p>
     * 文件从 Sayobot CDN 下载，保存到 downloads 目录下。
     * URL 格式：{@code https://cu2.sayobot.cn:25225/beatmaps/{dir1}/{dir2}/full?filename=xxx}
     * </p>
     *
     * @param sid    谱面集 ID
     * @param artist 艺术家名
     * @param title  谱面标题
     * @return 下载后的本地文件绝对路径
     * @throws IOException 下载失败时抛出
     */
    private String performDownloadToDisk(int sid, String artist, String title) throws IOException {
        if (artist == null) {
            artist = "";
        }
        if (title == null || title.isEmpty()) {
            title = String.valueOf(sid);
        }

        String filenameNoExt = sid + " " + artist + " - " + title;
        String filename = filenameNoExt + ".osz";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        // Sayobot CDN 路径按 10000 分片
        int dir1 = sid / 10000;
        String dir2 = String.format("%04d", sid % 10000);
        String downloadUrl = String.format(
                "https://cu2.sayobot.cn:25225/beatmaps/%d/%s/full?filename=%s",
                dir1,
                dir2,
                encodedFilename
        );

        System.out.println("下载链接: " + downloadUrl);
        String saved = downloadToFile(downloadUrl, "downloads", filename);
        System.out.println("已下载到: " + saved);
        return saved;
    }
}
