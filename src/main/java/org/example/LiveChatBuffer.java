package org.example;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 环形弹幕缓冲区（单例），线程安全，支持增量监听器。
 * <p>
 * 保存最近 {@link #CAPACITY} 条弹幕消息；超出容量时自动淘汰最旧的消息。
 * 每次有新消息追加时，会同步通知所有已注册的监听器（用于 SSE 实时推送）。
 * </p>
 * <p>
 * 使用 {@link #getInstance()} 获取全局唯一实例。
 * </p>
 */
public final class LiveChatBuffer {

    /** 全局唯一实例（饿汉模式） */
    private static final LiveChatBuffer INSTANCE = new LiveChatBuffer();

    /** 缓冲区最大容量 */
    private static final int CAPACITY = 100;

    /** 弹幕消息双端队列（由旧到新） */
    private final ArrayDeque<Entry> deque = new ArrayDeque<>();

    /** 增量消息监听器列表（线程安全），收到新弹幕时逐个回调 */
    private final CopyOnWriteArrayList<Consumer<JSONObject>> listeners = new CopyOnWriteArrayList<>();

    private LiveChatBuffer() {
    }

    /** 获取全局唯一实例 */
    public static LiveChatBuffer getInstance() {
        return INSTANCE;
    }

    /**
     * 注册增量消息监听器。
     * <p>
     * 每当 {@link #append} 收到新弹幕时，会将其 JSON 表示传递给所有监听器。
     * 典型用途：SSE 推送增量聊天消息到前端。
     * </p>
     *
     * @param listener 消息回调，参数为包含 user/text/ts 的 JSONObject
     */
    public void addListener(Consumer<JSONObject> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * 追加一条弹幕消息。
     * <p>
     * 线程安全：在 synchronized 块内操作队列，释放锁后再通知监听器。
     * 若缓冲区已满，先淘汰最旧的消息。
     * </p>
     *
     * @param user 弹幕发送者用户名
     * @param text 弹幕文本内容
     */
    public void append(String user, String text) {
        String u = user == null ? "" : user;
        String t = text == null ? "" : text;
        JSONObject line;
        synchronized (this) {
            while (deque.size() >= CAPACITY) {
                deque.pollFirst();
            }
            Entry e = new Entry(u, t, System.currentTimeMillis());
            deque.addLast(e);
            line = e.toJson();
        }
        // 在锁外通知监听器，避免持锁时间过长
        for (Consumer<JSONObject> c : listeners) {
            try {
                c.accept(line);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取缓冲区中所有消息的快照，按时间从旧到新排列。
     * <p>
     * 适合在前端首次加载或 SSE 重连时拉取全量聊天记录。
     * </p>
     *
     * @return 消息 JSON 列表
     */
    public synchronized List<JSONObject> snapshotOldestFirst() {
        List<JSONObject> out = new ArrayList<>(deque.size());
        for (Entry e : deque) {
            out.add(e.toJson());
        }
        return out;
    }

    /**
     * 内部弹幕条目，不可变。
     */
    private static final class Entry {
        /** 用户名 */
        final String user;
        /** 弹幕文本 */
        final String text;
        /** 时间戳（毫秒） */
        final long ts;

        Entry(String user, String text, long ts) {
            this.user = user;
            this.text = text;
            this.ts = ts;
        }

        /** 转换为前端可消费的 JSON 格式 */
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("user", user);
            o.put("text", text);
            o.put("ts", ts);
            return o;
        }
    }
}
