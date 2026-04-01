package com.gitolk;

import com.alibaba.fastjson2.JSONObject;
import pojo.Song;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Collectors;

public class PlayList {
    private final int size;  //歌单容量
    private final LinkedBlockingDeque<Song> playlist = new LinkedBlockingDeque<>(); //待播队列
    private final Deque<Song> history = new ArrayDeque<>();      //切歌历史（栈）
    private Song currentSong;                                     //当前曲目
    private final SongRateLimiter limiter = new SongRateLimiter();
    // 单例实例，方便外部（HTTP服务器）访问当前歌单
    private static volatile PlayList instance = null;

    // 监听器：歌单变化时会触发
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PlayList(int size) {
        this.size = size;
        instance = this;
    }

    /**
     * 添加谱面到播放列表
     * @param song
     * @return
     */
    public synchronized SongRequestResult addSong(Song song) {
        //获取铺面加入队列的状态
        SongRequestStatus status = getRequestStatus(song);

        switch (status) {
            case PLAYLIST_FULL:
                return new SongRequestResult(false, SongRequestStatus.PLAYLIST_FULL);
            case ALREADY_PLAYED:
                return new SongRequestResult(false, SongRequestStatus.ALREADY_PLAYED);
            case TOO_FREQUENT:
                return new SongRequestResult(false, SongRequestStatus.TOO_FREQUENT);
            case SUCCESS:
                //加入队列
                playlist.add(song);
                // 无当前曲目时，自动提升队首为当前
                if (currentSong == null) {
                    currentSong = playlist.pollFirst();
                }
                // 通知监听器
                notifyListeners();
                return new SongRequestResult(true, SongRequestStatus.SUCCESS);
            default:
                throw new IllegalStateException("未知状态错误: " + status);
        }
    }

    /**
     * 获取铺面信息
     * @param song
     * @return
     */
    public void GetSongMessage(Song song) throws IOException {
        long t0 = System.nanoTime();
        try {
            HttpURLConnection con = (HttpURLConnection)
                    new URL("https://api.sayobot.cn/v2/beatmapinfo?0=" + song.getBeatMapId())
                            .openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Content-Type", "application/json");

            // 获取响应结果
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String respons = bufferedReader.lines().collect(Collectors.joining("\n"));
                JSONObject result = JSONObject.parseObject(respons);
                if (result == null) {
                    return;
                }
                JSONObject data = result.getJSONObject("data");
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
     * 下载谱面
     * @param song
     * @return
     */
    public SongRequestResult downloadSong(Song song) {
        return new SongRequestResult(true, SongRequestStatus.SUCCESS);
    }

    private SongRequestStatus getRequestStatus(Song song) {
        if (getTotalCount() >= this.size) {
            return SongRequestStatus.PLAYLIST_FULL;
        }
        // 仅拒绝「已在当前或候播队列」的重复；不再用 playedSongs 终身禁止同一 sid（否则测试/重复点歌会一直失败，且会先下载再入队失败）
        if (currentSong != null && currentSong.equals(song)) {
            return SongRequestStatus.ALREADY_PLAYED;
        }
        if (playlist.contains(song)) {
            return SongRequestStatus.ALREADY_PLAYED;
        }
        if (!limiter.allow(song.getSongRequester())) {
            return SongRequestStatus.TOO_FREQUENT;
        }
        return SongRequestStatus.SUCCESS;
    }
    public synchronized int getSize() {
        return size;
    }

    /**
     * 返回歌单快照（线程安全）
     */
    public synchronized List<Song> getSnapshot() {
        return new ArrayList<>(playlist);
    }

    public synchronized Song getCurrentSong() {
        return currentSong;
    }

    public synchronized List<Song> getQueueSnapshot() {
        return new ArrayList<>(playlist);
    }

    public synchronized int getHistoryCount() {
        return history.size();
    }

    public synchronized boolean nextSong() {
        if (currentSong == null && playlist.isEmpty()) {
            return false;
        }
        if (currentSong != null) {
            history.push(currentSong);
        }
        currentSong = playlist.pollFirst();
        notifyListeners();
        return true;
    }

    public synchronized boolean prevSong() {
        if (history.isEmpty()) {
            return false;
        }
        if (currentSong != null) {
            playlist.offerFirst(currentSong);
        }
        currentSong = history.pop();
        notifyListeners();
        return true;
    }

    private synchronized int getTotalCount() {
        return playlist.size() + (currentSong == null ? 0 : 1);
    }

    public static PlayList getInstance() {
        return instance;
    }

    /** 若尚未创建歌单（例如先起了 HTTP 服务），则创建默认实例，避免 getInstance 为空。 */
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

    public static void addChangeListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public static void removeChangeListener(Runnable r) {
        if (r != null) listeners.remove(r);
    }

    private static void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    /** 异步下载等场景更新 Song 状态后刷新前端（SSE） */
    public static void notifyPlaylistChanged() {
        notifyListeners();
    }


}
