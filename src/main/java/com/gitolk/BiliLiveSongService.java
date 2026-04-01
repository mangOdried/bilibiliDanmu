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
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BiliLiveSongService {
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+");
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    private final PlayList playList;
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "beatmap-download");
        t.setDaemon(true);
        return t;
    });

    private static final class ResolvedBeatmap {
        final int sid;
        final String artist;
        final String title;

        ResolvedBeatmap(int sid, String artist, String title) {
            this.sid = sid;
            this.artist = artist != null ? artist : "";
            this.title = title;
        }
    }

    public BiliLiveSongService() {
        this.playList = PlayList.ensureInstance(20);
    }

    private static String normalizeDanmuText(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        t = t.replace('\uFEFF', ' ')
                .replace('\u200B', ' ')
                .replace('\u200C', ' ')
                .replace('\u200D', ' ')
                .replace('\u2060', ' ');
        return t.strip();
    }

    public Boolean userAddSong(String content, String username) {
        String raw = normalizeDanmuText(content);
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

        ResolvedBeatmap resolved;
        if (isNumeric(arg)) {
            resolved = resolveById(arg);
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

        Song song = new Song(username, resolved.sid);
        song.setDownloadStatus(BeatmapDownloadStatus.PENDING);
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
        SongRequestResult result = playList.addSong(song);
        if (!result.isSuccess()) {
            throw new RuntimeException(result.getMessage());
        }
        scheduleDownload(song, resolved);
        return true;
    }

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

    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return NUMERIC_PATTERN.matcher(str).matches();
    }

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

    private String sanitizeFileName(String name) {
        return UNSAFE_FILENAME_CHARS.matcher(name).replaceAll("_");
    }

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
