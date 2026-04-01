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

public class PlayList {
    private final int size;
    private final LinkedBlockingDeque<Song> playlist = new LinkedBlockingDeque<>();
    private final Set<Integer> queuedBeatmapIds = new HashSet<>();
    private final Deque<Song> history = new ArrayDeque<>();
    private Song currentSong;
    private final SongRateLimiter limiter = new SongRateLimiter();

    private static volatile PlayList instance = null;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PlayList(int size) {
        this.size = size;
        instance = this;
    }

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
     * 从 Sayobot 获取谱面标题并设置到 song 上
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

    public synchronized int getSize() {
        return size;
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
            queuedBeatmapIds.remove(currentSong.getBeatMapId());
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
            queuedBeatmapIds.add(currentSong.getBeatMapId());
        }
        currentSong = history.pop();
        queuedBeatmapIds.add(currentSong.getBeatMapId());
        notifyListeners();
        return true;
    }

    private synchronized int getTotalCount() {
        return playlist.size() + (currentSong == null ? 0 : 1);
    }

    public static PlayList getInstance() {
        return instance;
    }

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

    private static void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    public static void notifyPlaylistChanged() {
        notifyListeners();
    }
}
