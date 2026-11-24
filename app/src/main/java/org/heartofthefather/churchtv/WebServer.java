package org.heartofthefather.churchtv;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP server (port 8080) that serves:
 *   /           the control page you open from any computer or phone on the network
 *   /player     the page the TV itself shows full-screen
 *   /api/...    status + commands
 *   /media/...  the uploaded announcement files
 */
public final class WebServer {

    public interface Host {
        String controlUrl();
        boolean autoStartReady();
        void checkLiveNow();
        void reloadPlayer();
        /** e.g. "4032x3024", or null if unknown. */
        String mediaDims(java.io.File f);
        /** Human-readable description of the TV's screen. */
        String displayInfo();
        void showTestPattern();
        String diagnostics();
        String versionName();
    }

    private static final String TAG = "ChurchTV.Web";
    public static final int PORT = 8080;

    private final Context ctx;
    private final Store store;
    private final Host host;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running;

    public WebServer(Context ctx, Store store, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        this.host = host;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    ServerSocket s = new ServerSocket();
                    s.setReuseAddress(true);
                    s.bind(new java.net.InetSocketAddress(PORT));
                    server = s;
                    while (running) {
                        final Socket client = s.accept();
                        pool.execute(() -> handle(client));
                    }
                } catch (IOException e) {
                    if (!running) break;
                    Log.w(TAG, "server error, restarting in 3s", e);
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { break; }
                }
            }
        }, "web-server");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(60000);
            InputStream in = sock.getInputStream();
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 65536);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { sock.close(); return; }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { sock.close(); return; }
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
            }

            String path = target;
            Map<String, String> q = new HashMap<>();
            int qm = target.indexOf('?');
            if (qm >= 0) {
                path = target.substring(0, qm);
                for (String kv : target.substring(qm + 1).split("&")) {
                    if (kv.isEmpty()) continue;
                    int eq = kv.indexOf('=');
                    String k = dec(eq >= 0 ? kv.substring(0, eq) : kv);
                    String v = eq >= 0 ? dec(kv.substring(eq + 1)) : "";
                    q.put(k, v);
                }
            }
            path = dec(path);

            route(method, path, q, headers, in, out, sock);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        } finally {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    // ---------- PIN / sign-in ----------

    private static final String COOKIE = "churchtv_auth";
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 5 * 60 * 1000;
    private int fails = 0;
    private long lockedUntil = 0;

    private synchronized long lockSecondsLeft() {
        long left = lockedUntil - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    private synchronized boolean registerFailure() {
        if (++fails >= MAX_FAILS) { fails = 0; lockedUntil = System.currentTimeMillis() + LOCK_MS; return true; }
        return false;
    }

    private synchronized void registerSuccess() { fails = 0; }

    private static String cookie(Map<String, String> headers, String name) {
        String c = headers.get("cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    private static String setCookie(String token) {
        return "Set-Cookie: " + COOKIE + "=" + token + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict\r\n";
    }

    /** Returns true if the request was a sign-in endpoint and has been answered. */
    private boolean authRoutes(String method, String path, Map<String, String> q, Map<String, String> headers,
                               InputStream in, OutputStream out, boolean authed) throws IOException {
        if (path.equals("/api/auth")) {
            drain(headers, in);
            json(out, "{\"pinSet\":" + store.hasPin() + ",\"authed\":" + authed
                    + ",\"lockedSeconds\":" + lockSecondsLeft() + "}");
            return true;
        }
        if (!"POST".equals(method)) return false;
        switch (path) {
            case "/api/create-pin": {
                drain(headers, in);
                String pin = q.get("pin");
                if (store.hasPin()) { text(out, 409, "A PIN has already been set."); return true; }
                if (!Store.validPinFormat(pin)) { text(out, 400, "The PIN must be 4 to 8 digits."); return true; }
                store.setPin(pin);
                send(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), -1, setCookie(store.newToken()));
                return true;
            }
            case "/api/login": {
                drain(headers, in);
                long locked = lockSecondsLeft();
                if (locked > 0) { text(out, 429, "Too many wrong PINs. Try again in " + ((locked + 59) / 60) + " minute(s)."); return true; }
                if (store.checkPin(q.get("pin"))) {
                    registerSuccess();
                    send(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), -1, setCookie(store.newToken()));
                } else {
                    boolean nowLocked = registerFailure();
                    text(out, 403, nowLocked ? "Too many wrong PINs. Locked for 5 minutes." : "Wrong PIN.");
                }
                return true;
            }
            case "/api/logout": {
                drain(headers, in);
                store.revokeToken(cookie(headers, COOKIE));
                send(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), -1,
                        "Set-Cookie: " + COOKIE + "=; Path=/; Max-Age=0\r\n");
                return true;
            }
            case "/api/change-pin": {
                drain(headers, in);
                if (!authed) { text(out, 401, "Please sign in."); return true; }
                if (lockSecondsLeft() > 0) { text(out, 429, "Too many wrong PINs. Try again later."); return true; }
                if (!store.checkPin(q.get("current"))) { registerFailure(); text(out, 403, "The current PIN is wrong."); return true; }
                if (!Store.validPinFormat(q.get("pin"))) { text(out, 400, "The new PIN must be 4 to 8 digits."); return true; }
                registerSuccess();
                store.setPin(q.get("pin"));   // signs out every other device
                send(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), -1, setCookie(store.newToken()));
                return true;
            }
        }
        return false;
    }

    private void route(String method, String path, Map<String, String> q, Map<String, String> headers,
                       InputStream in, OutputStream out, Socket sock) throws IOException {
        // The TV itself (its own player page) never needs the PIN; everyone else does.
        boolean local = sock.getInetAddress() != null && sock.getInetAddress().isLoopbackAddress();
        boolean authed = local || store.validToken(cookie(headers, COOKIE));
        boolean publicPage = ("GET".equals(method) || "HEAD".equals(method)) && (path.equals("/") || path.equals("/index.html"));
        if (authRoutes(method, path, q, headers, in, out, authed)) return;
        if (!authed && !publicPage) {
            drain(headers, in);
            send(out, 401, "application/json", "{\"auth\":\"required\"}".getBytes(StandardCharsets.UTF_8), -1, "");
            return;
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            boolean head = "HEAD".equals(method);
            if (path.equals("/") || path.equals("/index.html")) { asset(out, "control.html", head); return; }
            if (path.equals("/player")) { asset(out, "player.html", head); return; }
            if (path.equals("/api/state")) {
                org.json.JSONObject st = store.stateJson(host.controlUrl(), host.autoStartReady());
                try {
                    st.put("display", host.displayInfo());
                    org.json.JSONArray items = st.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        org.json.JSONObject it = items.getJSONObject(i);
                        String d = host.mediaDims(new File(store.mediaDir(), it.getString("name")));
                        if (d != null) it.put("dims", d);
                    }
                } catch (org.json.JSONException ignored) { }
                json(out, st.toString());
                return;
            }
            if (path.equals("/api/diag")) {
                text(out, 200, "Church TV " + host.versionName() + "\n" + host.displayInfo() + "\n\n" + host.diagnostics());
                return;
            }
            if (path.startsWith("/media/")) { media(out, path.substring(7), headers.get("range"), head); return; }
            text(out, 404, "Not found");
            return;
        }
        if (!"POST".equals(method)) { text(out, 405, "Method not allowed"); return; }

        switch (path) {
            case "/api/upload": upload(q.get("name"), headers, in, out); return;
            case "/api/delete":
                drain(headers, in);
                store.delete(q.get("name"));
                ok(out);
                return;
            case "/api/move":
                drain(headers, in);
                store.move(q.get("name"), "up".equals(q.get("dir")) ? -1 : 1);
                ok(out);
                return;
            case "/api/settings": {
                drain(headers, in);
                Integer secs = null;
                try { if (q.containsKey("imageSeconds")) secs = Integer.parseInt(q.get("imageSeconds")); } catch (NumberFormatException ignored) { }
                String oldChannel = store.channel();
                Integer tms = null;
                try { if (q.containsKey("transitionMs")) tms = Integer.parseInt(q.get("transitionMs")); } catch (NumberFormatException ignored) { }
                store.updateSettings(q.get("channel"), secs, q.get("playback"), q.get("transition"), tms);
                if (!oldChannel.equals(store.channel())) host.checkLiveNow();
                ok(out);
                return;
            }
            case "/api/mode": {
                drain(headers, in);
                String m = q.get("m");
                store.setOverride("auto".equals(m) ? null : m);
                if ("live".equals(m)) host.checkLiveNow();
                ok(out);
                return;
            }
            case "/api/check": drain(headers, in); host.checkLiveNow(); ok(out); return;
            case "/api/test-pattern": drain(headers, in); host.showTestPattern(); ok(out); return;
            case "/api/reload": drain(headers, in); host.reloadPlayer(); ok(out); return;
            default: drain(headers, in); text(out, 404, "Not found");
        }
    }

    // ---------- uploads ----------

    private void upload(String rawName, Map<String, String> headers, InputStream in, OutputStream out) throws IOException {
        String name = Store.sanitize(rawName);
        long len = parseLong(headers.get("content-length"), -1);
        if (name == null) { drain(headers, in); text(out, 400, "Unsupported file type. Use MP4/MOV videos or JPG/PNG/HEIC images."); return; }
        if (len <= 0) { text(out, 411, "Missing Content-Length"); return; }
        File dir = store.mediaDir();
        if (dir.getUsableSpace() < len + 200L * 1024 * 1024) {
            drain(headers, in);
            text(out, 507, "Not enough space on the TV for this file.");
            return;
        }
        // Don't overwrite a different file with the same name.
        File dest = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = name.substring(0, dot), ext = name.substring(dot);
        for (int i = 2; dest.exists(); i++) dest = new File(dir, base + " (" + i + ")" + ext);

        File part = new File(dir, dest.getName() + ".part");
        long remaining = len;
        try (FileOutputStream fo = new FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                fo.write(buf, 0, n);
                remaining -= n;
            }
        } catch (IOException e) {
            // Connection dropped mid-upload: don't leave a half-written file on the TV.
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (remaining > 0 || !part.renameTo(dest)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            text(out, 500, "Upload was interrupted. Please try again.");
            return;
        }
        store.added(dest.getName());
        ok(out);
    }

    // ---------- media with Range support (needed for video playback) ----------

    private void media(OutputStream out, String rawName, String range, boolean head) throws IOException {
        String name = Store.sanitize(rawName);
        File f = name == null ? null : new File(store.mediaDir(), name);
        if (f == null || !f.isFile()) { text(out, 404, "Not found"); return; }
        long size = f.length();
        long start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String r = range.substring(6).split(",")[0].trim();
            int dash = r.indexOf('-');
            if (dash >= 0) {
                String a = r.substring(0, dash), b = r.substring(dash + 1);
                if (a.isEmpty()) { start = Math.max(0, size - parseLong(b, size)); }
                else { start = parseLong(a, 0); if (!b.isEmpty()) end = Math.min(size - 1, parseLong(b, size - 1)); }
                partial = true;
            }
        }
        if (start > end || start >= size) {
            String h = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + size + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.US_ASCII));
            return;
        }
        long count = end - start + 1;
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(mime(name)).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Cache-Control: no-cache\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n");
        h.append("Content-Length: ").append(count).append("\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (head) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            byte[] buf = new byte[65536];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".heif")) return "image/heif";
        return "image/jpeg";
    }

    // ---------- helpers ----------

    private void asset(OutputStream out, String file, boolean head) throws IOException {
        byte[] body;
        try (InputStream a = ctx.getAssets().open(file)) { body = readAll(a); }
        send(out, 200, "text/html; charset=utf-8", head ? new byte[0] : body, head ? body.length : -1);
    }

    private static void ok(OutputStream out) throws IOException { json(out, "{\"ok\":true}"); }

    private static void json(OutputStream out, String s) throws IOException {
        send(out, 200, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8), -1);
    }

    private static void text(OutputStream out, int code, String s) throws IOException {
        send(out, code, "text/plain; charset=utf-8", s.getBytes(StandardCharsets.UTF_8), -1);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, long lengthOverride) throws IOException {
        send(out, code, type, body, lengthOverride, "");
    }

    private static void send(OutputStream out, int code, String type, byte[] body, long lengthOverride,
                             String extraHeaders) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 404: status = "Not Found"; break;
            case 401: status = "Unauthorized"; break;
            case 403: status = "Forbidden"; break;
            case 405: status = "Method Not Allowed"; break;
            case 409: status = "Conflict"; break;
            case 429: status = "Too Many Requests"; break;
            case 411: status = "Length Required"; break;
            case 507: status = "Insufficient Storage"; break;
            default: status = "Error";
        }
        String h = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + (lengthOverride >= 0 ? lengthOverride : body.length) + "\r\n"
                + "Cache-Control: no-store\r\n"
                + extraHeaders
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
    }

    private static void drain(Map<String, String> headers, InputStream in) throws IOException {
        long len = parseLong(headers.get("content-length"), 0);
        byte[] buf = new byte[8192];
        while (len > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, len));
            if (n < 0) break;
            len -= n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    /** First non-loopback IPv4 address (Wi-Fi or Ethernet). */
    public static String localIp() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
