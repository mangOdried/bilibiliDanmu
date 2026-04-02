package com.gitolk;

import com.alibaba.fastjson2.JSONObject;
import pojo.Song;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;

/**
 * 点歌播放列表（单例），管理待播队列、当前曲目和切歌历史。
 * <p>
 * 提供线程安全的添加 / 切歌 / 快照等操作，并通过监听器机制
 * 在歌单变化时通知外部订阅者（如 SSE 推送服务）。
 * </p>
 * <p>
 * 使用 {@link #ensureInstance(int)} 初始化唯一实例，
 * 通过 {@link #getInstance()} 在其它模块中获取。
 * </p>
 */
public class PlayList {

    /** 歌单最大容量（当前曲目 + 待播队列总数上限） */
    private final int size;

    /** 待播队列（FIFO） */
    private final LinkedBlockingDeque<Song> playlist = new LinkedBlockingDeque<>();

    /** 已入队谱面 ID 的哈希索引，用于 O(1) 判断重复 */
    private final Set<Integer> queuedBeatmapIds = new HashSet<>();

    /** 切歌历史栈，用于"上一首"功能 */
    private final Deque<Song> history = new ArrayDeque<>();

    /** 当前正在播放的曲目 */
    private Song currentSong;

    /** 用户点歌频率限制器 */
    private final SongRateLimiter limiter = new SongRateLimiter();

    /** 全局唯一实例 */
    private static volatile PlayList instance = null;

    /** 歌单变化监听器列表（线程安全） */
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * 创建播放列表并将自身设置为全局实例。
     *
     * @param size 歌单最大容量
     */
    public PlayList(int size) {
        this.size = size;
        instance = this;
    }

    /**
     * 添加歌曲到待播队列。
     * <p>
     * 在添加前会检查容量、重复和频率限制。
     * 若当前无曲目，则自动将新添加的歌曲提升为当前曲目。
     * 添加成功后通知所有监听器。
     * </p>
     *
     * @param song 要添加的歌曲
     * @return 包含成功/失败状态的结果对象
     */
    public synchronized SongRequestResult addSong(Song song) {
        SongRequestStatus status = getRequestStatus(song);

        switch (status) {
            case PLAYLIST_FULL:
                return new SongRequestResult(false, SongRequestStatus.PLAYLIST_FULL);
            case ALREADY_PLAYED:
                return new SongRequestResult(false, SongRequestStatus.ALREADY_PLAYED);
            case TOO_FREQUENT:
                return new SongRequestResult(false, SongRequestStatus.TOO_FREQUENT);
            case SUCCESS:
                playlist.add(song);
                queuedBeatmapIds.add(song.getBeatMapId());
                // 无当前曲目时，自动提升队首为当前
                if (currentSong == null) {
                    currentSong = playlist.pollFirst();
                }
                notifyListeners();
                return new SongRequestResult(true, SongRequestStatus.SUCCESS);
            default:
                throw new IllegalStateException("未知状态错误: " + status);
        }
    }

    /**
     * 从 Sayobot API 查询谱面标题，并设置到给定的 Song 对象上。
     * <p>
     * 调用 <code>https://api.sayobot.cn/v2/beatmapinfo</code> 接口，
     * 仅在 resolve 阶段未取得标题时才会调用此方法作为兜底。
     * </p>
     *
     * @param song 目标歌曲（通过 beatMapId 查询）
     * @throws IOException 网络请求失败时抛出
     */
    public void fetchSongTitle(Song song) throws IOException {
        long t0 = System.nanoTime();
        try {
            HttpURLConnection con = (HttpURLConnection)
                    URI.create("https://api.sayobot.cn/v2/beatmapinfo?0=" + song.getBeatMapId()).toURL()
                            .openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setRequestProperty("Accept", "application/json");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String response = reader.lines().collect(Collectors.joining());
                JSONObject result = JSONObject.parseObject(response);
                if (result == null) {
                    return;
                }
                JSONObject data = result.getJSONObject("data");
                // 兼容 Sayobot 返回 data 字段为 JSON 字符串的情况
                if (data == null && result.get("data") instanceof String) {
                    data = JSONObject.parseObject(result.getString("data"));
                }
                if (data != null) {
                    String title = data.getString("title");
                    if (title != null && !title.isEmpty()) {
                        song.setSongTitle(title);
                    }
                }
            }
        } finally {
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("[Sayobot beatmapinfo] 耗时 " + elapsedMs + "ms, beatmapId=" + song.getBeatMapId());
        }
    }

    /**
     * 检查歌曲能否加入队列。
     * <p>
     * 依次检查：容量 → 是否与当前曲目重复 → 是否已在队列中 → 用户频率限制。
     * </p>
     *
     * @param song 待检查的歌曲
     * @return 对应的状态枚举
     */
    private SongRequestStatus getRequestStatus(Song song) {
        if (getTotalCount() >= this.size) {
            return SongRequestStatus.PLAYLIST_FULL;
        }
        if (currentSong != null && currentSong.getBeatMapId() == song.getBeatMapId()) {
            return SongRequestStatus.ALREADY_PLAYED;
        }
        if (queuedBeatmapIds.contains(song.getBeatMapId())) {
            return SongRequestStatus.ALREADY_PLAYED;
        }
        if (!limiter.allow(song.getSongRequester())) {
            return SongRequestStatus.TOO_FREQUENT;
        }
        return SongRequestStatus.SUCCESS;
    }

    /** 获取歌单容量上限 */
    public synchronized int getSize() {
        return size;
    }

    /** 获取当前正在播放的歌曲（可能为 null） */
    public synchronized Song getCurrentSong() {
        return currentSong;
    }

    /**
     * 获取待播队列的快照副本（线程安全）。
     *
     * @return 当前待播队列的不可变副本
     */
    public synchronized List<Song> getQueueSnapshot() {
        return new ArrayList<>(playlist);
    }

    /** 获取切歌历史条数 */
    public synchronized int getHistoryCount() {
        return history.size();
    }

    /**
     * 切到下一首：当前曲目压入历史栈，队首提升为当前曲目。
     *
     * @return true — 切换成功；false — 无可切换的歌曲
     */
    public synchronized boolean nextSong() {
        if (currentSong == null && playlist.isEmpty()) {
            return false;
        }
        if (currentSong != null) {
            queuedBeatmapIds.remove(currentSong.getBeatMapId());
            history.push(currentSong);
        }
        currentSong = playlist.pollFirst();
        notifyListeners();
        return true;
    }

    /**
     * 切到上一首：当前曲目退回队首，从历史栈弹出作为新的当前曲目。
     *
     * @return true — 切换成功；false — 历史栈为空
     */
    public synchronized boolean prevSong() {
        if (history.isEmpty()) {
            return false;
        }
        if (currentSong != null) {
            playlist.offerFirst(currentSong);
            queuedBeatmapIds.add(currentSong.getBeatMapId());
        }
        currentSong = history.pop();
        queuedBeatmapIds.add(currentSong.getBeatMapId());
        notifyListeners();
        return true;
    }

    /**
     * 计算歌单中的总曲目数（当前曲目 + 待播队列）。
     */
    private synchronized int getTotalCount() {
        return playlist.size() + (currentSong == null ? 0 : 1);
    }

    /** 获取全局唯一实例（可能为 null，表示尚未初始化） */
    public static PlayList getInstance() {
        return instance;
    }

    /**
     * 确保全局实例已初始化；若尚未创建则以给定容量新建。
     * 使用双重检查锁定（DCL）保证线程安全。
     *
     * @param size 歌单容量
     * @return 全局唯一的 PlayList 实例
     */
    public static PlayList ensureInstance(int size) {
        if (instance == null) {
            synchronized (PlayList.class) {
                if (instance == null) {
                    return new PlayList(size);
                }
            }
        }
        return instance;
    }

    /**
     * 注册歌单变化监听器。歌曲添加、切换等操作完成后会逐个回调。
     *
     * @param r 监听回调
     */
    public static void addChangeListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    /**
     * 逐个触发所有监听器，单个监听器异常不影响后续执行。
     */
    private static void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    /**
     * 公开的通知入口，供异步下载线程在更新歌曲状态后刷新前端。
     */
    public static void notifyPlaylistChanged() {
        notifyListeners();
    }
}
