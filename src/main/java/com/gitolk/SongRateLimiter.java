package com.gitolk;

import java.util.*;

public class SongRateLimiter {

    // 用户点歌记录：userId -> 点歌时间列表
    private final Map<String, Deque<Long>> userSongTimestamps = new HashMap<>();

    private final int maxSongs = 2;         // 时间窗口内最大点歌数
    private final long timeWindowMillis = 30 * 1000; // 时间窗口：30秒

    public synchronized boolean allow(String username) {
        //获取当前时间
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = userSongTimestamps.computeIfAbsent(username, k -> new ArrayDeque<>());

        // 清理过期时间戳
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > timeWindowMillis) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxSongs) {
            return false; // 超出限制
        }

        timestamps.addLast(now); // 记录本次请求时间
        return true;
    }
}

