package com.gitolk;

import java.util.*;

/**
 * 用户点歌频率限制器（滑动窗口算法）。
 * <p>
 * 同一用户在 {@link #timeWindowMillis} 毫秒内最多允许 {@link #maxSongs} 次点歌请求，
 * 超出限制时返回 false，由调用方决定后续处理（一般拒绝入队并提示用户）。
 * </p>
 * <p>
 * 线程安全：所有操作通过 synchronized 保护。
 * </p>
 */
public class SongRateLimiter {

    /** 用户点歌时间戳记录：用户名 → 点歌时间队列（由旧到新） */
    private final Map<String, Deque<Long>> userSongTimestamps = new HashMap<>();

    /** 时间窗口内允许的最大点歌数 */
    private final int maxSongs = 2;

    /** 滑动窗口长度（毫秒），默认 30 秒 */
    private final long timeWindowMillis = 30 * 1000;

    /**
     * 检查指定用户是否允许点歌。
     * <p>
     * 内部会先清除过期的时间戳，然后判断窗口内的请求数是否超限。
     * 若允许，则记录本次请求时间。
     * </p>
     *
     * @param username 弹幕用户名
     * @return true — 允许点歌；false — 超出频率限制
     */
    public synchronized boolean allow(String username) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = userSongTimestamps.computeIfAbsent(username, k -> new ArrayDeque<>());

        // 移除窗口之外的过期记录
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > timeWindowMillis) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxSongs) {
            return false;
        }

        timestamps.addLast(now);
        return true;
    }
}
