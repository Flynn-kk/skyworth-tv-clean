package com.ccos.wallpaper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP 服务：给电视上一个上传壁纸的网页用。
 * 路由：GET /（网页）、GET /ping（连通性/状态）、POST /upload（收图）。
 *
 * 手机浏览器（尤其是 iOS Safari 和国产 App 内置浏览器）有几个坑，这里都处理了：
 *  - Expect: 100-continue：不回 100 客户端会一直等到超时再放弃
 *  - Transfer-Encoding: chunked：没有 Content-Length 时按块读
 *  - 大图慢网：读超时放到 60 秒
 */
public class UploadServer {

    public interface Callback {
        void onStatus(String text);
        /** 返回写入的字节数；失败返回 -1。 */
        int onImage(byte[] data);
        void onApplied(int bytes);
        /** 已安装 App 的 JSON：[{"p":包名,"n":名字}] */
        String appsJson();
        /** 当前 dock 的 JSON：["包名", ...] */
        String dockJson();
        /** 保存 dock，csv 是逗号分隔的包名。 */
        boolean setDock(String csv);
    }

    private static final int MAX_BODY = 40 * 1024 * 1024;

    private final Callback cb;
    private volatile boolean running;
    private ServerSocket server;
    private int actualPort;

    public UploadServer(Callback cb) {
        this.cb = cb;
    }

    public int getPort() {
        return actualPort;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "upload-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
    }

    private void loop() {
        for (int p = 8090; p < 8100 && running; p++) {
            try {
                server = new ServerSocket(p);
                actualPort = p;
                break;
            } catch (Exception e) {
                server = null;
            }
        }
        if (server == null) {
            cb.onStatus("端口都被占了，换个网络试试");
            return;
        }
        cb.onStatus("等待手机上传…");
        while (running) {
            try {
                final Socket socket = server.accept();
                Thread h = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                }, "upload-conn");
                h.setDaemon(true);
                h.start();
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(60000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // 逐字节读到空行，避免把已经读进缓存区的 body 字节吃掉
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) return;
                headBuf.write(b);
                if ((state == 0 || state == 2) && b == '\r') state++;
                else if ((state == 1 || state == 3) && b == '\n') state++;
                else state = (b == '\r') ? 1 : 0;
                if (headBuf.size() > 32768) return;
            }
            String head = new String(headBuf.toByteArray(), "ISO-8859-1");
            String[] lines = head.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            String method = requestLine.length > 0 ? requestLine[0] : "";
            String path = requestLine.length > 1 ? requestLine[1] : "/";

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) {
                    headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US),
                            lines[i].substring(c + 1).trim());
                }
            }

            // 客户端在等 100 Continue 时，不回它就会挂到超时
            String expect = headers.get("expect");
            if (expect != null && expect.toLowerCase(Locale.US).contains("100-continue")) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
            }

            if ("GET".equals(method)) {
                if (path.startsWith("/favicon")) {
                    respond(out, 204, "text/plain", new byte[0]);
                } else if (path.startsWith("/apps")) {
                    respond(out, 200, "application/json; charset=utf-8", cb.appsJson().getBytes("UTF-8"));
                } else if (path.startsWith("/dock")) {
                    respond(out, 200, "application/json; charset=utf-8", cb.dockJson().getBytes("UTF-8"));
                } else if (path.startsWith("/ping")) {
                    respond(out, 200, "text/plain; charset=utf-8",
                            ("pong " + android.os.Build.MODEL).getBytes("UTF-8"));
                } else {
                    respond(out, 200, "text/html; charset=utf-8", page().getBytes("UTF-8"));
                }
                return;
            }

            if ("POST".equals(method) && path.startsWith("/dock")) {
                byte[] body = readBody(in, headers);
                if (body == null) {
                    respond(out, 200, "text/plain; charset=utf-8", "failed 请求体为空".getBytes("UTF-8"));
                    return;
                }
                String csv = formValue(new String(body, "UTF-8"), "dock");
                if (csv == null) {
                    respond(out, 200, "text/plain; charset=utf-8", "failed 没读到 dock 参数".getBytes("UTF-8"));
                    return;
                }
                boolean ok = cb.setDock(csv);
                respond(out, 200, "text/plain; charset=utf-8",
                        (ok ? "ok" : "failed 写入电视失败").getBytes("UTF-8"));
                return;
            }

            if ("POST".equals(method) && path.startsWith("/upload")) {
                byte[] body;
                String te = headers.get("transfer-encoding");
                if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
                    body = readChunked(in);
                } else {
                    int len = 0;
                    try {
                        len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                    } catch (Exception ignored) {
                    }
                    if (len <= 0 || len > MAX_BODY) {
                        respond(out, 200, "text/plain; charset=utf-8",
                                ("failed 图片太大或为空（上限 " + (MAX_BODY / 1024 / 1024) + " MB）").getBytes("UTF-8"));
                        return;
                    }
                    body = new byte[len];
                    int off = 0;
                    while (off < len) {
                        int n = in.read(body, off, len - off);
                        if (n < 0) break;
                        off += n;
                    }
                    if (off < len) {
                        respond(out, 200, "text/plain; charset=utf-8",
                                ("failed 只收到 " + off + " / " + len + " 字节，网络断了").getBytes("UTF-8"));
                        return;
                    }
                }

                byte[] file = multipartFile(body, headers.get("content-type"));
                if (file == null) {
                    respond(out, 200, "text/plain; charset=utf-8",
                            "failed 没从请求里读到图片".getBytes("UTF-8"));
                    return;
                }
                cb.onStatus("收到 " + (file.length / 1024) + " KB，处理中…");
                int written = cb.onImage(file);
                if (written > 0) {
                    respond(out, 200, "text/plain; charset=utf-8",
                            ("ok " + written).getBytes("UTF-8"));
                    cb.onApplied(written);
                } else {
                    respond(out, 200, "text/plain; charset=utf-8",
                            "failed 写入电视失败（权限或磁盘）".getBytes("UTF-8"));
                }
                return;
            }

            respond(out, 404, "text/plain", "not found".getBytes("UTF-8"));
        } catch (Exception e) {
            cb.onStatus("出错：" + e.getClass().getSimpleName());
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) {
        try {
            String te = headers.get("transfer-encoding");
            if (te != null && te.toLowerCase(Locale.US).contains("chunked")) return readChunked(in);
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (Exception ignored) {
            }
            if (len <= 0 || len > MAX_BODY) return null;
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }
            return off == len ? body : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String formValue(String body, String key) {
        try {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && key.equals(pair.substring(0, eq))) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] readChunked(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            if (sb.length() == 0) continue;
            int size;
            try {
                size = Integer.parseInt(sb.toString().trim(), 16);
            } catch (Exception e) {
                return null;
            }
            if (size == 0) {
                // 吃掉尾部
                while ((c = in.read()) >= 0) {
                    if (c == '\n') break;
                }
                return out.toByteArray();
            }
            if (out.size() + size > MAX_BODY) return null;
            byte[] buf = new byte[size];
            int off = 0;
            while (off < size) {
                int n = in.read(buf, off, size - off);
                if (n < 0) return out.toByteArray();
                off += n;
            }
            out.write(buf, 0, size);
            in.read();
            in.read();
        }
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    /** 从 multipart 体里取出第一个带 filename 的部分。 */
    private static byte[] multipartFile(byte[] body, String contentType) {
        if (contentType == null) return null;
        int bi = contentType.toLowerCase(Locale.US).indexOf("boundary=");
        if (bi < 0) return null;
        String boundary = contentType.substring(bi + 9).trim();
        if (boundary.startsWith("\"")) {
            int end = boundary.indexOf('"', 1);
            if (end > 0) boundary = boundary.substring(1, end);
        }
        byte[] delim;
        try {
            delim = ("--" + boundary).getBytes("ISO-8859-1");
        } catch (Exception e) {
            return null;
        }
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int afterDelim = pos + delim.length;
            if (afterDelim + 1 < body.length && body[afterDelim] == '-' && body[afterDelim + 1] == '-') {
                return null;
            }
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(), afterDelim);
            if (headerEnd < 0) return null;
            int dataStart = headerEnd + 4;
            byte[] marker = ("\r\n--" + boundary).getBytes();
            int dataEnd = indexOf(body, marker, dataStart);
            if (dataEnd < 0) return null;
            String partHead = new String(body, afterDelim, headerEnd - afterDelim,
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            if (partHead.contains("filename=")) {
                byte[] result = new byte[dataEnd - dataStart];
                System.arraycopy(body, dataStart, result, 0, result.length);
                return result;
            }
            pos = indexOf(body, delim, dataEnd);
        }
        return null;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String page() {
        return "<!doctype html><html lang=zh><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>电视壁纸</title><style>"
                + "*{box-sizing:border-box}body{margin:0;padding:22px;font:16px/1.5 -apple-system,"
                + "'PingFang SC','Microsoft YaHei',sans-serif;background:#0b0e14;color:#eef;}"
                + "h1{font-size:20px;margin:0 0 4px}p.sub{color:#8b96ad;margin:0 0 22px;font-size:14px}"
                + "label.file{display:block;border:2px dashed #2a3550;border-radius:16px;padding:38px 16px;"
                + "text-align:center;color:#9fb0d0;margin-bottom:16px}"
                + "label.file input{display:none}"
                + "#prev{width:100%;border-radius:12px;margin-bottom:16px;display:none}"
                + "button{width:100%;padding:16px;border:0;border-radius:14px;font-size:17px;"
                + "font-weight:600;background:#3a6df0;color:#fff;opacity:.45}"
                + "button.on{opacity:1}"
                + "#msg{min-height:24px;margin-top:14px;font-size:14px;text-align:center;color:#8b96ad}"
                + "#bar{height:4px;border-radius:2px;background:#22304d;margin-top:14px;display:none}"
                + "#bar>i{display:block;height:100%;width:0;background:#3a6df0;border-radius:2px}"
                + "</style></head><body>"
                + "<h1>给电视换壁纸</h1><p class=sub>选一张图，点下面的按钮。会自动裁成 16:9 并缩放。</p>"
                + "<label class=file>点这里选图片<input id=f type=file accept='image/*'></label>"
                + "<img id=prev><button id=b disabled>上传并设为壁纸</button>"
                + "<div id=bar><i></i></div><div id=msg></div>"
                + "<script>"
                + "var f=document.getElementById('f'),p=document.getElementById('prev'),"
                + "b=document.getElementById('b'),m=document.getElementById('msg'),bar=document.getElementById('bar'),"
                + "bi=bar.querySelector('i');"
                + "f.onchange=function(){var x=f.files[0];if(!x)return;"
                + "p.src=URL.createObjectURL(x);p.style.display='block';b.disabled=false;b.className='on';"
                + "m.textContent='已选好（'+(x.size/1024/1024).toFixed(1)+' MB），点上传';};"
                + "b.onclick=function(){if(!f.files[0])return;b.disabled=true;b.className='';"
                + "m.textContent='上传中，别把电视上的这个页面切走…';bar.style.display='block';"
                + "var d=new FormData();d.append('file',f.files[0]);"
                + "var xhr=new XMLHttpRequest();xhr.open('POST','/upload');"
                + "xhr.upload.onprogress=function(e){if(e.lengthComputable)bi.style.width=(e.loaded/e.total*100)+'%';};"
                + "xhr.onload=function(){var t=(xhr.responseText||'').trim();"
                + "if(t.indexOf('ok')===0){m.textContent='✅ 已换好（'+t.split(' ')[1]+' 字节），抬头看电视';}"
                + "else{m.textContent='❌ '+t;b.disabled=false;b.className='on';}};"
                + "xhr.onerror=function(){m.textContent='❌ 连不上电视：电视上「TV 壁纸」是不是被切走了？重新打开它再试';"
                + "b.disabled=false;b.className='on';};"
                + "xhr.send(d);};"
                + "</script>"
                + dockSection()
                + "</body></html>";
    }

    /** 手机上的「桌面图标」区块：4 个下拉框 + 保存。 */
    private static String dockSection() {
        return "<style>.slot{display:flex;align-items:center;gap:10px;margin-bottom:10px}"
                + ".slot span{width:44px;color:#8b96ad;font-size:15px}"
                + ".slot select{flex:1;padding:14px;border-radius:12px;background:#141a26;"
                + "color:#eef;border:1px solid #2a3550;font-size:16px}</style>"
                + "<h1 style='margin-top:36px'>桌面图标</h1>"
                + "<p class=sub>选 4 个 App 放到电视上那排玻璃条里，保存后桌面自动刷新</p>"
                + "<div id=slots></div>"
                + "<button id=save disabled>保存到电视</button><div id=dmsg></div>"
                + "<script>"
                + "var APPS=[],CUR=[];"
                + "function mk(t,c,x){var e=document.createElement(t);if(c)e.className=c;"
                + "if(x!=null)e.textContent=x;return e;}"
                + "Promise.all([fetch('/apps').then(function(r){return r.json();}),"
                + "fetch('/dock').then(function(r){return r.json();})])"
                + ".then(function(res){APPS=res[0]||[];CUR=res[1]||[];render();})"
                + ".catch(function(e){document.getElementById('dmsg').textContent='读不到 App 列表：'+e;});"
                + "function render(){var box=document.getElementById('slots');box.innerHTML='';"
                + "for(var i=0;i<4;i++){var row=mk('div','slot');row.appendChild(mk('span',null,(i+1)));"
                + "var sel=document.createElement('select');"
                + "APPS.forEach(function(a){var o=document.createElement('option');o.value=a.p;"
                + "o.textContent=a.n;if(CUR[i]===a.p)o.selected=true;sel.appendChild(o);});"
                + "row.appendChild(sel);box.appendChild(row);}"
                + "var b=document.getElementById('save');b.disabled=false;b.className='on';}"
                + "document.getElementById('save').onclick=function(){"
                + "var s=document.querySelectorAll('#slots select'),d=[];"
                + "for(var i=0;i<s.length;i++)d.push(s[i].value);"
                + "var m=document.getElementById('dmsg');m.textContent='保存中…';"
                + "fetch('/dock',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                + "body:'dock='+encodeURIComponent(d.join(','))})"
                + ".then(function(r){return r.text();})"
                + ".then(function(t){m.textContent=(t.indexOf('ok')===0)?'✅ 已保存，抬头看电视':'❌ '+t;})"
                + ".catch(function(e){m.textContent='❌ '+e;});};"
                + "</script>";
    }
}
