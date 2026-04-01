package org.example;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gitolk.PlayList;
import pojo.BeatmapDownloadStatus;
import pojo.Song;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.awt.Desktop;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlaylistHttpServer {
    private final HttpServer server;
    private final CopyOnWriteArrayList<PrintWriter> sseClients = new CopyOnWriteArrayList<>();

    public PlaylistHttpServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/playlist", new ApiHandler());
        server.createContext("/api/chat", new ChatApiHandler());
        server.createContext("/api/playlist/next", new NextHandler());
        server.createContext("/api/playlist/prev", new PrevHandler());
        server.createContext("/api/open-download", new OpenDownloadHandler());
        server.createContext("/sse", new SseHandler());
        server.createContext("/playlist.html", new StaticHandler());
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        PlayList.ensureInstance(20);
        PlayList.addChangeListener(this::broadcastPlaylist);
        LiveChatBuffer.getInstance().addListener(this::broadcastChat);
    }

    /**
     * SSE 行必须使用 \\n。Windows 下 PrintWriter.println 会输出 \\r\\n，导致 data 行末尾带 \\r，
     * 浏览器里 e.data 解析 JSON 失败，歌单/弹幕无法更新。
     */
    private static void writeSseData(PrintWriter pw, String jsonOneLine) {
        pw.print("data: ");
        pw.print(jsonOneLine);
        pw.print("\n\n");
        pw.flush();
    }

    private static void writeSseComment(PrintWriter pw) {
        pw.print(": ping\n\n");
        pw.flush();
    }

    /** 默认 message 事件，前端仅用 onmessage 解析，兼容 OBS/旧内核对命名事件支持差的情况 */
    private String playlistEnvelopeJson() {
        JSONObject w = new JSONObject();
        w.put("type", "playlist");
        w.put("payload", buildPlaylistObject());
        return w.toJSONString();
    }

    private static String chatEnvelopeJson(JSONObject chatPayload) {
        JSONObject w = new JSONObject();
        w.put("type", "chat");
        w.put("payload", chatPayload);
        return w.toJSONString();
    }

    private void broadcastPlaylist() {
        try {
            String json = playlistEnvelopeJson();
            for (PrintWriter pw : sseClients) {
                try {
                    writeSseData(pw, json);
                } catch (Exception ignore) {
                    try { pw.close(); } catch (Exception e) {}
                    sseClients.remove(pw);
                }
            }
        } catch (Exception ignored) {}
    }

    private void broadcastChat(JSONObject line) {
        if (line == null) {
            return;
        }
        try {
            String json = chatEnvelopeJson(line);
            for (PrintWriter pw : sseClients) {
                try {
                    writeSseData(pw, json);
                } catch (Exception ignore) {
                    try { pw.close(); } catch (Exception e) {}
                    sseClients.remove(pw);
                }
            }
        } catch (Exception ignored) {}
    }

    private JSONObject songToJson(Song s) {
        JSONObject o = new JSONObject();
        o.put("requester", s.getSongRequester());
        o.put("id", s.getBeatMapId());
        o.put("title", s.getSongTitle());
        o.put("downloadStatus", s.getDownloadStatus().name());
        String p = s.getDownloadLocalPath();
        boolean hasLocal = s.getDownloadStatus() == BeatmapDownloadStatus.DONE
                && p != null && !p.isEmpty();
        o.put("hasLocalFile", hasLocal);
        return o;
    }

    private static Song findSongByBeatmapId(PlayList pl, int beatmapId) {
        Song current = pl.getCurrentSong();
        if (current != null && current.getBeatMapId() == beatmapId) {
            return current;
        }
        for (Song s : pl.getQueueSnapshot()) {
            if (s.getBeatMapId() == beatmapId) {
                return s;
            }
        }
        return null;
    }

    private JSONObject buildPlaylistObject() {
        PlayList pl = PlayList.getInstance();
        JSONObject root = new JSONObject();
        root.put("current", null);
        root.put("queue", new JSONArray());
        root.put("historyCount", 0);
        root.put("canPrev", false);
        root.put("canNext", false);
        if (pl != null) {
            Song current = pl.getCurrentSong();
            if (current != null) {
                root.put("current", songToJson(current));
            }
            List<Song> queue = pl.getQueueSnapshot();
            JSONArray arr = new JSONArray();
            for (Song s : queue) arr.add(songToJson(s));
            root.put("queue", arr);
            root.put("historyCount", pl.getHistoryCount());
            root.put("canPrev", pl.getHistoryCount() > 0);
            root.put("canNext", current != null || !queue.isEmpty());
        }
        return root;
    }

    private void sendJson(HttpExchange exchange, int code, JSONObject body) throws IOException {
        byte[] resp = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    private void handleSwitchSong(HttpExchange exchange, boolean isNext) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        PlayList pl = PlayList.getInstance();
        if (pl == null) {
            JSONObject body = new JSONObject();
            body.put("ok", false);
            body.put("reason", "playlist_not_ready");
            sendJson(exchange, 503, body);
            return;
        }
        boolean ok = isNext ? pl.nextSong() : pl.prevSong();
        JSONObject body = new JSONObject();
        body.put("ok", ok);
        body.put("action", isNext ? "next" : "prev");
        if (!ok) {
            body.put("reason", isNext ? "no_next_song" : "no_previous_song");
            sendJson(exchange, 409, body);
            return;
        }
        body.put("snapshot", buildPlaylistObject());
        sendJson(exchange, 200, body);
    }

    class NextHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleSwitchSong(exchange, true);
        }
    }

    class PrevHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleSwitchSong(exchange, false);
        }
    }

    class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body = buildPlaylistObject().toJSONString();
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        }
    }

    class ChatApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            JSONObject root = new JSONObject();
            JSONArray messages = new JSONArray();
            for (JSONObject m : LiveChatBuffer.getInstance().snapshotOldestFirst()) {
                messages.add(m);
            }
            root.put("messages", messages);
            root.put("active", sseClients.size());
            sendJson(exchange, 200, root);
        }
    }

    class OpenDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            PlayList pl = PlayList.getInstance();
            if (pl == null) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "playlist_not_ready");
                sendJson(exchange, 503, body);
                return;
            }
            String raw;
            try (InputStream is = exchange.getRequestBody()) {
                raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JSONObject req = JSONObject.parseObject(raw);
            if (req == null || !req.containsKey("id")) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "missing_id");
                sendJson(exchange, 400, body);
                return;
            }
            int id = req.getIntValue("id");
            Song song = findSongByBeatmapId(pl, id);
            if (song == null) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "not_found");
                sendJson(exchange, 404, body);
                return;
            }
            if (song.getDownloadStatus() != BeatmapDownloadStatus.DONE) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "not_downloaded");
                sendJson(exchange, 409, body);
                return;
            }
            String path = song.getDownloadLocalPath();
            if (path == null || path.isEmpty()) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "no_local_path");
                sendJson(exchange, 409, body);
                return;
            }
            File f = new File(path);
            if (!f.isFile()) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "file_missing");
                sendJson(exchange, 409, body);
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "desktop_not_supported");
                sendJson(exchange, 503, body);
                return;
            }
            try {
                Desktop.getDesktop().open(f);
            } catch (Exception e) {
                JSONObject body = new JSONObject();
                body.put("ok", false);
                body.put("reason", "open_failed: " + e.getMessage());
                sendJson(exchange, 503, body);
                return;
            }
            JSONObject ok = new JSONObject();
            ok.put("ok", true);
            sendJson(exchange, 200, ok);
        }
    }

    class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);
            sseClients.add(pw);
            writeSseData(pw, playlistEnvelopeJson());
            long heartbeatAt = System.currentTimeMillis();
            try {
                while (!pw.checkError()) {
                    if (System.currentTimeMillis() - heartbeatAt >= 15000) {
                        writeSseComment(pw);
                        heartbeatAt = System.currentTimeMillis();
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {}
            try { pw.close(); } catch (Exception ignored) {}
            sseClients.remove(pw);
        }
    }

    class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("<!doctype html>\n");
            sb.append("<html><head><meta charset=\"utf-8\">\n");
            sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n");
            sb.append("<title>SonicGhost</title>\n");
            sb.append("<style>");
            sb.append(":root{--bg:#050a0f;--accent:#00d2ff;--card:#141a22;--muted:#7a93a8;--text:#e8f4ff}");
            sb.append("*{box-sizing:border-box}body{margin:0;min-height:100vh;background:var(--bg);font-family:Inter,Segoe UI,Helvetica,Arial,sans-serif;color:var(--text);display:flex;align-items:center;justify-content:center;padding:20px}");
            sb.append(".panel{width:380px;max-width:96vw;background:linear-gradient(180deg,rgba(20,26,34,0.98),rgba(10,14,20,0.99));border:1px solid rgba(0,210,255,0.22);border-radius:16px;box-shadow:0 24px 64px rgba(0,0,0,0.55),0 0 0 1px rgba(0,210,255,0.06) inset;overflow:hidden}");
            sb.append(".top{padding:16px 16px 10px;display:flex;align-items:center;justify-content:space-between}");
            sb.append(".brand{font-size:22px;font-weight:800;letter-spacing:-0.02em;color:var(--accent);text-shadow:0 0 24px rgba(0,210,255,0.35)}");
            sb.append(".icon-btn{width:36px;height:36px;border-radius:10px;border:1px solid rgba(0,210,255,0.25);background:rgba(8,14,22,0.9);color:var(--muted);display:inline-flex;align-items:center;justify-content:center;cursor:default}");
            sb.append(".icon-row{display:flex;gap:8px}");
            sb.append(".now{margin:0 14px 12px;padding:14px;border-radius:14px;background:var(--card);border:1px solid rgba(0,210,255,0.28);box-shadow:0 8px 28px rgba(0,0,0,0.35)}");
            sb.append(".live-badge{display:inline-block;font-size:10px;font-weight:700;letter-spacing:.12em;padding:4px 10px;border-radius:999px;background:var(--accent);color:#061018;margin-bottom:8px}");
            sb.append(".row{display:flex;gap:12px;align-items:flex-start}.cover{width:72px;height:72px;border-radius:12px;background:linear-gradient(145deg,#e8e4d8,#9aa396);flex:none;box-shadow:0 4px 16px rgba(0,0,0,0.35)}");
            sb.append(".now-text{min-width:0;flex:1}.song-title{font-size:19px;font-weight:700;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;line-height:1.2}");
            sb.append(".song-sub{margin-top:4px;font-size:12px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
            sb.append(".req-line{margin-top:6px;font-size:12px;color:var(--accent);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
            sb.append(".dl-status{margin-top:6px;font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.dl-status.is-wait{color:var(--muted)}.dl-status.is-busy{color:var(--accent)}.dl-status.is-done{color:#5dffa2}.dl-status.is-fail{color:#ff6b6b}");
            sb.append(".open-file-wrap{margin-top:6px}.btn-open-file{font-size:11px;padding:4px 10px;border-radius:8px;border:1px solid rgba(0,210,255,0.45);background:rgba(0,210,255,0.12);color:var(--accent);cursor:pointer}.btn-open-file:hover{background:rgba(0,210,255,0.22)}");
            sb.append(".time-row{display:flex;justify-content:space-between;margin-top:10px;font-size:11px;color:var(--muted)}");
            sb.append(".line{height:3px;border-radius:999px;background:rgba(0,210,255,0.12);margin-top:6px;overflow:hidden}.line-in{height:100%;width:42%;background:linear-gradient(90deg,var(--accent),#5af)}");
            sb.append(".control{margin-top:12px;display:flex;align-items:center;justify-content:center;gap:20px}.btn{width:40px;height:40px;border-radius:50%;border:1px solid rgba(0,210,255,0.45);background:rgba(6,12,20,0.95);color:var(--accent);cursor:pointer;font-size:16px}");
            sb.append(".btn:hover{background:rgba(0,210,255,0.12)}.btn:disabled{opacity:0.35;cursor:not-allowed}");
            sb.append(".section-head{padding:0 14px 8px;display:flex;align-items:center;justify-content:space-between;color:var(--muted);font-size:11px;font-weight:700;letter-spacing:.14em}");
            sb.append(".list{padding:0 12px 10px;display:flex;flex-direction:column;gap:8px;max-height:240px;overflow:auto}");
            sb.append(".item{display:flex;align-items:center;gap:10px;padding:10px 10px;border-radius:12px;background:rgba(10,16,24,0.95);border:1px solid rgba(0,210,255,0.12)}");
            sb.append(".item-next{border-left:3px solid var(--accent);box-shadow:-2px 0 12px rgba(0,210,255,0.2)}");
            sb.append(".thumb{width:36px;height:36px;border-radius:8px;background:linear-gradient(135deg,#0c2a3d,#0a5066);flex:none}");
            sb.append(".index{width:30px;height:30px;border-radius:8px;background:rgba(0,210,255,0.1);display:flex;align-items:center;justify-content:center;color:var(--accent);font-weight:700;font-size:12px;flex:none;border:1px solid rgba(0,210,255,0.25)}");
            sb.append(".it-info{min-width:0;flex:1}.it-title{font-size:14px;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.it-meta{margin-top:2px;font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
            sb.append(".it-side{text-align:right;flex:none;min-width:88px}.it-req{font-size:11px;color:var(--accent);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:120px}.it-dur{font-size:11px;color:var(--muted);margin-top:2px}.it-dur.is-wait{color:var(--muted)}.it-dur.is-busy{color:var(--accent)}.it-dur.is-done{color:#5dffa2}.it-dur.is-fail{color:#ff6b6b}");
            sb.append(".chat-list{padding:0 12px 14px;display:flex;flex-direction:column;gap:10px;max-height:220px;overflow-y:auto}");
            sb.append(".chat-bubble{border:1px solid var(--accent);border-radius:20px;padding:10px 16px;background:rgba(0,210,255,0.05);line-height:1.45}");
            sb.append(".chat-user{color:var(--accent);font-weight:700;margin-right:8px}.chat-text{color:#f0f6ff;word-break:break-word}");
            sb.append(".empty{padding:22px 12px;text-align:center;color:var(--muted);font-size:13px}");
            sb.append(".state{padding:0 14px 14px;color:var(--muted);font-size:12px;min-height:18px}");
            sb.append(".list,.chat-list{scrollbar-width:thin;scrollbar-color:rgba(0,210,255,0.5) rgba(6,12,20,0.85)}");
            sb.append(".list::-webkit-scrollbar,.chat-list::-webkit-scrollbar{width:8px;height:8px}");
            sb.append(".list::-webkit-scrollbar-track,.chat-list::-webkit-scrollbar-track{background:rgba(6,12,20,0.85);border-radius:999px;margin:6px 0}");
            sb.append(".list::-webkit-scrollbar-thumb,.chat-list::-webkit-scrollbar-thumb{background:linear-gradient(180deg,rgba(0,210,255,0.35),rgba(0,180,220,0.55));border-radius:999px;border:2px solid rgba(6,12,20,0.95)}");
            sb.append(".list::-webkit-scrollbar-thumb:hover,.chat-list::-webkit-scrollbar-thumb:hover{background:rgba(0,210,255,0.65)}");
            sb.append(".list::-webkit-scrollbar-corner,.chat-list::-webkit-scrollbar-corner{background:transparent}");
            sb.append("</style></head><body>");
            sb.append("<div class=\"panel\">");
            sb.append("<div class=\"top\"><div class=\"brand\">SonicGhost</div><div class=\"icon-row\">");
            sb.append("<span class=\"icon-btn\" title=\"History\" aria-hidden=\"true\"><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M3 12a9 9 0 1 0 3-6.7\"/><path d=\"M3 4v5h5\"/></svg></span>");
            sb.append("<span class=\"icon-btn\" title=\"Settings\" aria-hidden=\"true\"><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><circle cx=\"12\" cy=\"12\" r=\"3\"/><path d=\"M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2\"/></svg></span>");
            sb.append("</div></div>");
            sb.append("<div class=\"now\">");
            sb.append("<div class=\"row\"><div class=\"cover\"></div><div class=\"now-text\">");
            sb.append("<div class=\"live-badge\">LIVE NOW</div>");
            sb.append("<div id=\"current-title\" class=\"song-title\">加载中...</div>");
            sb.append("<div id=\"current-sub\" class=\"song-sub\">—</div>");
            sb.append("<div id=\"current-meta\" class=\"req-line\">正在连接服务...</div>");
            sb.append("<div id=\"current-download\" class=\"dl-status\"></div>");
            sb.append("<div id=\"current-open-wrap\" class=\"open-file-wrap\"></div></div></div>");
            sb.append("<div class=\"time-row\"><span>0:00</span><span>--:--</span></div>");
            sb.append("<div class=\"line\"><div class=\"line-in\"></div></div>");
            sb.append("<div class=\"control\"><button type=\"button\" id=\"btn-prev\" class=\"btn\" aria-label=\"上一首\">&#9664;</button><button type=\"button\" id=\"btn-next\" class=\"btn\" aria-label=\"下一首\">&#9654;</button></div>");
            sb.append("</div>");
            sb.append("<div class=\"section-head\"><span>NEXT UP</span><span id=\"queue-count\">0 Songs in Queue</span></div>");
            sb.append("<div id=\"list\" class=\"list\"><div class=\"empty\">加载中...</div></div>");
            sb.append("<div class=\"section-head\"><span>LIVE CHAT</span><span id=\"chat-active\">0 active</span></div>");
            sb.append("<div id=\"chat-list\" class=\"chat-list\"><div class=\"empty\" id=\"chat-empty\">等待弹幕...</div></div>");
            sb.append("<div id=\"status\" class=\"state\"></div>");
            sb.append("</div>");
            sb.append("<script>");
            sb.append("var state={current:null,queue:[],historyCount:0,canPrev:false,canNext:false};");
            sb.append("var busy=false;var es=null;var reconnectTimer=null;var pollTimer=null;");
            sb.append("function text(v,d){return (v===null||v===undefined||v==='')?d:v}");
            sb.append("function dlText(st){var M={PENDING:'等待下载',DOWNLOADING:'下载中…',DONE:'已下载',FAILED:'下载失败'};return M[st]||st||'—';}");
            sb.append("function dlClass(st){if(st==='DONE')return'is-done';if(st==='FAILED')return'is-fail';if(st==='DOWNLOADING')return'is-busy';return'is-wait';}");
            sb.append("function openBeatmapFile(id){fetch('/api/open-download',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:id})}).then(function(r){return r.json().then(function(b){return {status:r.status,body:b};});}).then(function(res){if(res.body&&res.body.ok){setStatus('已请求打开文件');setTimeout(function(){setStatus('');},1800);}else{var msg=res.body&&res.body.reason?res.body.reason:('HTTP '+res.status);setStatus('打开失败: '+msg);}}).catch(function(){setStatus('打开失败: 网络异常');});}");
            sb.append("function appendChatBubble(obj){var wrap=document.getElementById('chat-list');var empty=document.getElementById('chat-empty');if(empty){empty.remove();}var div=document.createElement('div');div.className='chat-bubble';var u=document.createElement('span');u.className='chat-user';u.textContent='@'+text(obj.user,'匿名');var t=document.createElement('span');t.className='chat-text';t.textContent=text(obj.text,'');div.appendChild(u);div.appendChild(t);wrap.appendChild(div);wrap.scrollTop=wrap.scrollHeight;}");
            sb.append("function renderChatFromApi(data){var wrap=document.getElementById('chat-list');wrap.innerHTML='';var n=document.getElementById('chat-active');if(n&&data&&typeof data.active==='number'){n.textContent=data.active+' active';}var msgs=(data&&data.messages)?data.messages:[];if(msgs.length===0){var e=document.createElement('div');e.className='empty';e.id='chat-empty';e.textContent='等待弹幕...';wrap.appendChild(e);return;}msgs.forEach(appendChatBubble);wrap.scrollTop=wrap.scrollHeight;}");
            sb.append("function fetchChat(){return fetch('/api/chat').then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}).then(function(d){renderChatFromApi(d);return d;});}");
            sb.append("function render(s){state=s||state;var ct=document.getElementById('current-title');var cs=document.getElementById('current-sub');var cm=document.getElementById('current-meta');var list=document.getElementById('list');var count=document.getElementById('queue-count');var bp=document.getElementById('btn-prev');var bn=document.getElementById('btn-next');");
            sb.append("if(state.current){ct.textContent=text(state.current.title,state.current.id);cs.textContent='Beatmap ID: '+text(state.current.id,'-');cm.textContent='Req by @'+text(state.current.requester,'匿名');var cds=document.getElementById('current-download');var ds=state.current.downloadStatus||'PENDING';if(cds){cds.textContent='谱面文件：'+dlText(ds);cds.className='dl-status '+dlClass(ds);}var cow=document.getElementById('current-open-wrap');if(cow){cow.innerHTML='';if(state.current.downloadStatus==='DONE'&&state.current.hasLocalFile){var ob=document.createElement('button');ob.type='button';ob.className='btn-open-file';ob.textContent='打开文件';ob.onclick=function(){openBeatmapFile(state.current.id);};cow.appendChild(ob);}}}else{ct.textContent='暂无当前曲目';cs.textContent='—';cm.textContent='等待点歌进入队列';var cds0=document.getElementById('current-download');if(cds0){cds0.textContent='';cds0.className='dl-status';}var cow0=document.getElementById('current-open-wrap');if(cow0){cow0.innerHTML='';}}");
            sb.append("bp.disabled=busy||!state.canPrev;bn.disabled=busy||!state.canNext;");
            sb.append("var q=state.queue||[];var hasCur=!!state.current;count.textContent=(hasCur?1:0)+' playing · '+q.length+' waiting';list.innerHTML='';if(q.length===0){if(!hasCur){list.innerHTML='<div class=\"empty\">队列为空，等待新的点歌</div>';return;}list.innerHTML='<div class=\"empty\">暂无候播，下一首将出现在此</div>';return;}q.forEach(function(it,i){var item=document.createElement('div');item.className='item'+(i===0?' item-next':'');var idx=document.createElement('div');idx.className='thumb';var info=document.createElement('div');info.className='it-info';var t=document.createElement('div');t.className='it-title';t.textContent=text(it.title,it.id);var m=document.createElement('div');m.className='it-meta';m.textContent='osu!';var side=document.createElement('div');side.className='it-side';var r=document.createElement('div');r.className='it-req';r.textContent='@'+text(it.requester,'匿名');var d=document.createElement('div');var dst=it.downloadStatus||'PENDING';d.className='it-dur '+dlClass(dst);d.textContent=dlText(dst);info.appendChild(t);info.appendChild(m);side.appendChild(r);side.appendChild(d);if(it.downloadStatus==='DONE'&&it.hasLocalFile){var obq=document.createElement('button');obq.type='button';obq.className='btn-open-file';obq.textContent='打开';obq.style.marginTop='4px';obq.onclick=function(){openBeatmapFile(it.id);};side.appendChild(obq);}item.appendChild(idx);item.appendChild(info);item.appendChild(side);list.appendChild(item);});}");
            sb.append("function setStatus(msg){document.getElementById('status').textContent=msg||'';}");
            sb.append("function fetchSnapshot(){return fetch('/api/playlist').then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}).then(function(data){render(data);setStatus('');return data;});}");
            sb.append("function callSwitch(url){if(busy)return;busy=true;render(state);setStatus('正在切换...');fetch(url,{method:'POST'}).then(function(r){return r.json().then(function(b){return {status:r.status,body:b};});}).then(function(res){if(res.status>=200&&res.status<300){if(res.body&&res.body.snapshot){render(res.body.snapshot);}setStatus('切换成功');setTimeout(function(){setStatus('');},1200);}else{setStatus(res.body&&res.body.reason?('切换失败: '+res.body.reason):'切换失败');}}).catch(function(){setStatus('切换失败: 网络异常');}).finally(function(){busy=false;render(state);});}");
            sb.append("function setupActions(){document.getElementById('btn-prev').addEventListener('click',function(){callSwitch('/api/playlist/prev');});document.getElementById('btn-next').addEventListener('click',function(){callSwitch('/api/playlist/next');});}");
            sb.append("function setupSse(){if(es){es.close();es=null;}es=new EventSource('/sse');es.onmessage=function(e){try{var m=JSON.parse(e.data);if(m.type==='playlist'){render(m.payload);setStatus('');}else if(m.type==='chat'){appendChatBubble(m.payload);}}catch(err){console.error(err);}};es.onerror=function(){setStatus('实时连接中断，正在重连...');if(!reconnectTimer){reconnectTimer=setTimeout(function(){reconnectTimer=null;setupSse();},2000);}if(!pollTimer){pollTimer=setInterval(function(){fetchSnapshot().catch(function(){});fetchChat().catch(function(){});},8000);}};es.onopen=function(){setStatus('');if(pollTimer){clearInterval(pollTimer);pollTimer=null;}fetchChat().catch(function(){});}};");
            sb.append("setupActions();fetchSnapshot().catch(function(){setStatus('初始加载失败，等待自动恢复');});fetchChat().catch(function(){});setupSse();");
            sb.append("</script></body></html>");
            byte[] resp = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        }
    }

    public void stop() {
        server.stop(0);
    }
}
