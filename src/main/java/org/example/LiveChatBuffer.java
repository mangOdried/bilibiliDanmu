package org.example;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 环形弹幕缓冲：线程安全，支持监听器（用于 SSE 推送增量）。
 */
public final class LiveChatBuffer {
    private static final LiveChatBuffer INSTANCE = new LiveChatBuffer();
    private static final int CAPACITY = 100;

    private final ArrayDeque<Entry> deque = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Consumer<JSONObject>> listeners = new CopyOnWriteArrayList<>();

    private LiveChatBuffer() {
    }

    public static LiveChatBuffer getInstance() {
        return INSTANCE;
    }

    public void addListener(Consumer<JSONObject> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Consumer<JSONObject> listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

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
        for (Consumer<JSONObject> c : listeners) {
            try {
                c.accept(line);
            } catch (Exception ignored) {
            }
        }
    }

    /** 从旧到新，适合按时间顺序渲染 */
    public synchronized List<JSONObject> snapshotOldestFirst() {
        List<JSONObject> out = new ArrayList<>(deque.size());
        for (Entry e : deque) {
            out.add(e.toJson());
        }
        return out;
    }

    private static final class Entry {
        final String user;
        final String text;
        final long ts;

        Entry(String user, String text, long ts) {
            this.user = user;
            this.text = text;
            this.ts = ts;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("user", user);
            o.put("text", text);
            o.put("ts", ts);
            return o;
        }
    }
}
