package com.deihs.mms.service;

import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.User;
import com.deihs.mms.model.UserCredentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/**
 * Small embedded LAN web server so a QR scan opens a DEIHS MMS login/landing page.
 * This is intentionally lightweight (JDK HttpServer, no extra dependencies).
 */
public final class LanWebServerService {

    public static final int DEFAULT_PORT = 8090;
    private static final Preferences PREFS = Preferences.userNodeForPackage(LanWebServerService.class);
    private static final String KEY_ENABLED = "lanWeb.enabled";
    private static final String KEY_PORT = "lanWeb.port";

    private static volatile LanWebServerService INSTANCE;

    private final UserDao userDao = new UserDao();
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private int port = getConfiguredPort();

    private record SessionInfo(int userId, String username, String displayName, long createdAt) {}

    public static LanWebServerService get() {
        if (INSTANCE == null) {
            synchronized (LanWebServerService.class) {
                if (INSTANCE == null) INSTANCE = new LanWebServerService();
            }
        }
        return INSTANCE;
    }

    private LanWebServerService() {}

    public static int getConfiguredPort() {
        return PREFS.getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setConfiguredPort(int p) {
        PREFS.putInt(KEY_PORT, p);
    }

    public static boolean isConfiguredEnabled() {
        return PREFS.getBoolean(KEY_ENABLED, false);
    }

    public static void setConfiguredEnabled(boolean enabled) {
        PREFS.putBoolean(KEY_ENABLED, enabled);
    }

    public synchronized void start() throws IOException {
        if (server != null) return;
        port = getConfiguredPort();
        HttpServer s = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        s.createContext("/", this::handleRoot);
        s.createContext("/login", this::handleLogin);
        s.createContext("/logout", this::handleLogout);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "deihs-lan-web");
            t.setDaemon(true);
            return t;
        }));
        s.start();
        server = s;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        sessions.clear();
    }

    public synchronized boolean isRunning() { return server != null; }
    public int getPort() { return port; }

    public String getLanUrl() {
        String ip = detectLanIp();
        return "http://" + ip + ":" + getPort() + "/";
    }

    public static String detectLanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "Method Not Allowed"); return; }
        SessionInfo s = session(ex);
        sendHtml(ex, 200, page(s, null));
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "DEIHS_SID");
        if (sid != null) sessions.remove(sid);
        ex.getResponseHeaders().add("Set-Cookie", "DEIHS_SID=; Path=/; Max-Age=0; HttpOnly");
        ex.getResponseHeaders().add("Location", "/");
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "Method Not Allowed"); return; }
        String body = readBody(ex);
        Map<String, String> form = parseForm(body);
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) {
            sendHtml(ex, 200, page(null, "Enter your username and password."));
            return;
        }
        try {
            Optional<UserCredentials> found = userDao.findByUsername(username);
            if (found.isEmpty() || !found.get().user().active() || !BCrypt.checkpw(password, found.get().passwordHash())) {
                sendHtml(ex, 200, page(null, "Invalid username or password."));
                return;
            }
            User u = found.get().user();
            String sid = UUID.randomUUID().toString();
            sessions.put(sid, new SessionInfo(u.id(), u.username(), u.fullName(), System.currentTimeMillis()));
            ex.getResponseHeaders().add("Set-Cookie", "DEIHS_SID=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
            ex.getResponseHeaders().add("Location", "/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } catch (SQLException e) {
            sendHtml(ex, 500, page(null, "Database error. Make sure the DEIHS MMS database is running."));
        }
    }

    private SessionInfo session(HttpExchange ex) {
        String sid = cookie(ex, "DEIHS_SID");
        if (sid == null) return null;
        SessionInfo s = sessions.get(sid);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.createdAt() > 8L * 60 * 60 * 1000) { sessions.remove(sid); return null; }
        return s;
    }

    private static String cookie(HttpExchange ex, String name) {
        for (String h : ex.getRequestHeaders().getOrDefault("Cookie", Collections.emptyList())) {
            String[] parts = h.split(";");
            for (String p : parts) {
                int eq = p.indexOf('=');
                if (eq > 0) {
                    String k = p.substring(0, eq).trim();
                    String v = p.substring(eq + 1).trim();
                    if (name.equals(k)) return v;
                }
            }
        }
        return null;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) return map;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, StandardCharsets.UTF_8);
                v = URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            map.put(k, v);
        }
        return map;
    }

    private void send(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private String page(SessionInfo s, String error) {
        String err = error == null ? "" : "<div class='err'>" + esc(error) + "</div>";
        String body;
        if (s == null) {
            body = ""
                    + "<h1>Sign in to DEIHS MMS</h1>"
                    + "<p class='sub'>Scan-to-open LAN portal for Dasmariñas East Integrated High School Maintenance Management System.</p>"
                    + err
                    + "<form method='post' action='/login' class='card'>"
                    + "<label>Username</label><input name='username' autocomplete='username' required>"
                    + "<label>Password</label><input name='password' type='password' autocomplete='current-password' required>"
                    + "<button type='submit'>Sign In</button>"
                    + "</form>"
                    + "<p class='hint'>This page is hosted on your school LAN by the DEIHS MMS desktop app. For full maintenance workflows, use the desktop app on the school computer.</p>";
        } else {
            body = ""
                    + "<h1>Welcome, " + esc(s.displayName()) + "</h1>"
                    + "<p class='sub'>You are signed in to the DEIHS MMS LAN portal.</p>"
                    + "<div class='card'>"
                    + "<p><strong>Username:</strong> " + esc(s.username()) + "</p>"
                    + "<p><strong>Status:</strong> Connected to DEIHS MMS LAN web portal.</p>"
                    + "<p class='hint'>Mobile request intake and asset scan workflows can be added here. For now, use the desktop app for full request, inventory, and admin actions.</p>"
                    + "<a class='btnlink' href='/logout'>Sign out</a>"
                    + "</div>";
        }
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>DEIHS MMS</title><style>"
                + "body{margin:0;font-family:Arial,sans-serif;background:linear-gradient(160deg,#0c8f57,#0f6a3f);min-height:100vh;color:#123;display:flex;align-items:center;justify-content:center;padding:24px;}"
                + ".wrap{width:100%;max-width:520px;background:#fff;border-radius:16px;box-shadow:0 18px 40px rgba(0,0,0,.18);padding:28px;}"
                + ".brand{display:flex;gap:12px;align-items:center;margin-bottom:18px}.seal{width:52px;height:52px;border-radius:50%;background:#e9f7f0;color:#0f6a3f;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:20px;}"
                + ".kicker{font-size:12px;color:#0f6a3f;font-weight:700;letter-spacing:.04em;text-transform:uppercase}.school{font-size:13px;color:#4d5b57}"
                + "h1{margin:0 0 8px;font-size:28px;color:#153126}.sub{margin:0 0 18px;color:#4d5b57;line-height:1.45}"
                + ".card{background:#f7faf8;border:1px solid #dce9e1;border-radius:12px;padding:18px;display:flex;flex-direction:column;gap:10px}"
                + "label{font-size:13px;font-weight:700;color:#234}input{padding:11px 12px;border-radius:8px;border:1px solid #c9d7cf;font-size:15px}"
                + "button,.btnlink{margin-top:6px;background:#16794c;color:#fff;border:none;border-radius:8px;padding:11px 14px;font-size:15px;font-weight:700;cursor:pointer;text-decoration:none;display:inline-block;text-align:center}"
                + "button:hover,.btnlink:hover{background:#115f3c}.err{background:#fff3f2;color:#9b1c1c;border:1px solid #f1c4c0;padding:10px 12px;border-radius:8px;margin-bottom:12px;font-size:14px}"
                + ".hint{font-size:12px;color:#5b6a64;line-height:1.45;margin:14px 0 0}"
                + "</style></head><body><div class='wrap'>"
                + "<div class='brand'><div class='seal'>D</div><div><div class='kicker'>DEIHS MMS</div><div class='school'>Dasmariñas East Integrated High School</div></div></div>"
                + body
                + "</div></body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
}
