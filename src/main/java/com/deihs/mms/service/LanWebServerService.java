package com.deihs.mms.service;

import com.deihs.mms.dao.LookupDao;
import com.deihs.mms.dao.RequestDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.*;
import com.deihs.mms.service.RequestService;
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
    private final LookupDao lookupDao = new LookupDao();
    private final RequestDao requestDao = new RequestDao();
    private final RequestService requestService = new RequestService();
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private int port = getConfiguredPort();

    private record SessionInfo(int userId, String username, String displayName, String csrf, long createdAt) {}

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
        s.createContext("/requests", this::handleRequests);
        s.createContext("/requests/new", this::handleNewRequest);
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
        sendHtml(ex, 200, page(s, null, true));
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
            sendHtml(ex, 200, page(null, "Enter your username and password.", true));
            return;
        }
        try {
            Optional<UserCredentials> found = userDao.findByUsername(username);
            if (found.isEmpty() || !found.get().user().active() || !BCrypt.checkpw(password, found.get().passwordHash())) {
                sendHtml(ex, 200, page(null, "Invalid username or password.", true));
                return;
            }
            User u = found.get().user();
            String sid = UUID.randomUUID().toString();
            String csrf = UUID.randomUUID().toString().replace("-", "");
            sessions.put(sid, new SessionInfo(u.id(), u.username(), u.fullName(), csrf, System.currentTimeMillis()));
            ex.getResponseHeaders().add("Set-Cookie", "DEIHS_SID=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
            ex.getResponseHeaders().add("Location", "/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        } catch (SQLException e) {
            sendHtml(ex, 500, page(null, "Database error. Make sure the DEIHS MMS database is running.", true));
        }
    }

    private void handleRequests(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "Method Not Allowed"); return; }
        SessionInfo s = session(ex);
        if (s == null) { redirect(ex, "/"); return; }
        try {
            Optional<User> uo = userDao.findById(s.userId());
            if (uo.isEmpty() || !uo.get().active()) { clearAndRedirect(ex); return; }
            User user = uo.get();
            String q = queryParam(ex, "q");
            String status = queryParam(ex, "status");
            String priority = queryParam(ex, "priority");
            Integer assignedTo = user.role() == Role.PERSONNEL ? user.id() : null;
            Integer requesterId = (!Permissions.canViewAllRequests(user) && user.role() == Role.STAFF) ? user.id() : null;
            // STAFF may only see their own; PERSONNEL assigned; admins/heads all.
            if (user.role() == Role.STAFF) { requesterId = user.id(); assignedTo = null; }
            if (user.role() == Role.PERSONNEL) { assignedTo = user.id(); requesterId = null; }
            var rows = requestDao.search(blankToNull(q), blankToNull(status), blankToNull(priority), assignedTo, requesterId, false);
            sendHtml(ex, 200, requestsPage(user, s.csrf(), rows, q, status, priority, null));
        } catch (Exception e) {
            sendHtml(ex, 500, errorPage("Could not load requests", e.getMessage()));
        }
    }

    private void handleNewRequest(HttpExchange ex) throws IOException {
        SessionInfo s = session(ex);
        if (s == null) { redirect(ex, "/"); return; }
        try {
            Optional<User> uo = userDao.findById(s.userId());
            if (uo.isEmpty() || !uo.get().active()) { clearAndRedirect(ex); return; }
            User user = uo.get();
            if (!Permissions.canCreateRequest(user)) {
                sendHtml(ex, 403, errorPage("Access denied", "Your account does not have permission to submit maintenance requests from the web portal."));
                return;
            }
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendHtml(ex, 200, newRequestPage(user, s.csrf(), Collections.emptyMap(), null, null));
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "Method Not Allowed"); return; }
            Map<String, String> form = parseForm(readBody(ex));
            if (!s.csrf().equals(form.getOrDefault("_csrf", ""))) {
                sendHtml(ex, 403, errorPage("Session check failed", "Please reload the form and try again."));
                return;
            }
            RequestDraft draft = draftFromForm(form);
            try {
                CreatedRequest created = requestService.submit(draft, user);
                sendHtml(ex, 200, submitSuccessPage(user, created));
            } catch (ValidationException ve) {
                sendHtml(ex, 400, newRequestPage(user, s.csrf(), form, ve.getMessage(), null));
            } catch (Exception e) {
                sendHtml(ex, 500, newRequestPage(user, s.csrf(), form, "Could not submit request: " + e.getMessage(), null));
            }
        } catch (Exception e) {
            sendHtml(ex, 500, errorPage("Could not load request form", e.getMessage()));
        }
    }

    private RequestDraft draftFromForm(Map<String, String> form) {
        return new RequestDraft(
                form.getOrDefault("title", ""),
                parseIntObj(form.get("categoryId")),
                parseIntObj(form.get("facilityId")),
                parseIntObj(form.get("equipmentId")),
                form.getOrDefault("priority", ""),
                form.getOrDefault("description", ""),
                parseDate(form.get("targetDate")),
                null
        );
    }

    private static Integer parseIntObj(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return null; }
    }
    private static java.time.LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) return null;
        try { return java.time.LocalDate.parse(v.trim()); } catch (Exception e) { return null; }
    }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
    private void clearAndRedirect(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "DEIHS_SID");
        if (sid != null) sessions.remove(sid);
        ex.getResponseHeaders().add("Set-Cookie", "DEIHS_SID=; Path=/; Max-Age=0; HttpOnly");
        redirect(ex, "/");
    }
    private String queryParam(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, StandardCharsets.UTF_8);
                v = URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            if (name.equals(k)) return v;
        }
        return null;
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

    private String page(SessionInfo s, String error, boolean narrow) {
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
                    + "<p class='sub'>You are signed in to the DEIHS MMS mobile web companion.</p>"
                    + "<div class='card'>"
                    + "<p><strong>Username:</strong> " + esc(s.username()) + "</p>"
                    + "<p><strong>Status:</strong> Connected to DEIHS MMS LAN web portal.</p>"
                    + "<div class='actions'>"
                    + "<a class='btnlink' href='/requests'>View Requests</a>"
                    + "<a class='btnlink secondary' href='/requests/new'>Submit Request</a>"
                    + "</div>"
                    + "<p class='hint'>Use this mobile companion for quick request status checks and intake on the school LAN. For full maintenance workflows, use the desktop app.</p>"
                    + "<a class='btnlink danger' href='/logout'>Sign out</a>"
                    + "</div>";
        }
        return layout("DEIHS MMS", narrow, body, errForLayout(error));
    }

    private String errForLayout(String error) { return null; }

    private String layout(String title, boolean narrow, String body, String topMessage) {
        String msg = topMessage == null ? "" : topMessage;
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + esc(title) + "</title><style>"
                + "body{margin:0;font-family:Arial,sans-serif;background:linear-gradient(160deg,#0c8f57,#0f6a3f);min-height:100vh;color:#123;display:flex;align-items:center;justify-content:center;padding:24px;}"
                + ".wrap{width:100%;max-width:920px;background:#fff;border-radius:16px;box-shadow:0 18px 40px rgba(0,0,0,.18);padding:28px;}"
                + ".wrap.narrow{max-width:520px;}"
                + ".brand{display:flex;gap:12px;align-items:center;margin-bottom:18px}.seal{width:52px;height:52px;border-radius:50%;background:#e9f7f0;color:#0f6a3f;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:20px;}"
                + ".kicker{font-size:12px;color:#0f6a3f;font-weight:700;letter-spacing:.04em;text-transform:uppercase}.school{font-size:13px;color:#4d5b57}"
                + "h1{margin:0 0 8px;font-size:28px;color:#153126}.sub{margin:0 0 18px;color:#4d5b57;line-height:1.45}"
                + ".card{background:#f7faf8;border:1px solid #dce9e1;border-radius:12px;padding:18px;display:flex;flex-direction:column;gap:10px}"
                + "label{font-size:13px;font-weight:700;color:#234}input,select,textarea{padding:11px 12px;border-radius:8px;border:1px solid #c9d7cf;font-size:15px;width:100%;box-sizing:border-box;font-family:inherit}"
                + "textarea{min-height:110px;resize:vertical}"
                + "button,.btnlink{margin-top:6px;background:#16794c;color:#fff;border:none;border-radius:8px;padding:11px 14px;font-size:15px;font-weight:700;cursor:pointer;text-decoration:none;display:inline-block;text-align:center}"
                + "button:hover,.btnlink:hover{background:#115f3c}.btnlink.secondary{background:#e9f3ed;color:#18543a}.btnlink.secondary:hover{background:#d7e8de}.btnlink.danger{background:#fff1f0;color:#9b1c1c;border:1px solid #f0c9c6}"
                + ".actions{display:flex;gap:8px;flex-wrap:wrap}.actions .btnlink,.actions button{margin-top:0;flex:1 1 140px}"
                + ".err{background:#fff3f2;color:#9b1c1c;border:1px solid #f1c4c0;padding:10px 12px;border-radius:8px;margin-bottom:12px;font-size:14px}"
                + ".ok{background:#edf8f1;color:#14532d;border:1px solid #cde5d6;padding:10px 12px;border-radius:8px;margin-bottom:12px;font-size:14px}"
                + ".hint{font-size:12px;color:#5b6a64;line-height:1.45;margin:14px 0 0}"
                + ".topbar{display:flex;justify-content:space-between;gap:10px;align-items:center;margin-bottom:14px;flex-wrap:wrap}"
                + ".nav{display:flex;gap:8px;flex-wrap:wrap}"
                + ".pill{display:inline-block;padding:4px 8px;border-radius:999px;font-size:11px;font-weight:700;background:#e8f3ec;color:#1b5d3b}"
                + ".pill.PENDING{background:#fff4d6;color:#7a5b00}.pill.ASSIGNED{background:#e8f1ff;color:#1e4ea1}.pill.IN_PROGRESS{background:#dff5ea;color:#166534}.pill.ON_HOLD{background:#f1f1f1;color:#555}.pill.COMPLETED{background:#e8f7ee;color:#14532d}.pill.CLOSED{background:#eceff1;color:#455a64}.pill.CANCELLED{background:#feeceb;color:#991b1b}"
                + ".prio{font-size:11px;font-weight:700}.prio.CRITICAL{color:#b91c1c}.prio.HIGH{color:#b45309}.prio.MEDIUM{color:#1d4ed8}.prio.LOW{color:#4b5563}"
                + "table{width:100%;border-collapse:collapse}th,td{padding:10px 8px;border-bottom:1px solid #e3ece6;text-align:left;font-size:13px;vertical-align:top}th{font-size:12px;color:#486055;text-transform:uppercase;letter-spacing:.03em}"
                + ".muted{color:#60726a;font-size:12px}.grid2{display:grid;grid-template-columns:1fr 1fr;gap:10px}.full{grid-column:1/-1}"
                + "@media(max-width:720px){.wrap{padding:18px}.grid2{grid-template-columns:1fr}.full{grid-column:auto}table,thead,tbody,th,td,tr{display:block}thead{display:none}tr{border:1px solid #e3ece6;border-radius:10px;padding:8px;margin-bottom:10px;background:#fff}td{border:none;padding:4px 0}td::before{content:attr(data-label);display:block;font-size:11px;color:#64796f;font-weight:700;text-transform:uppercase}}"
                + "</style></head><body><div class='wrap" + (narrow ? " narrow" : "") + "'>"
                + "<div class='brand'><div class='seal'>D</div><div><div class='kicker'>DEIHS MMS</div><div class='school'>Dasmariñas East Integrated High School</div></div></div>"
                + msg
                + body
                + "</div></body></html>";
    }

    private String requestsPage(User user, String csrf, java.util.List<RequestRow> rows, String q, String status, String priority, String message) {
        StringBuilder b = new StringBuilder();
        b.append("<div class='topbar'><div><h1>Requests</h1><p class='sub'>Mobile companion request list and status view.</p></div>");
        b.append("<div class='nav'><a class='btnlink secondary' href='/'>Home</a>");
        if (Permissions.canCreateRequest(user)) b.append("<a class='btnlink' href='/requests/new'>New Request</a>");
        b.append("<a class='btnlink danger' href='/logout'>Sign out</a></div></div>");
        if (message != null && !message.isBlank()) b.append("<div class='ok'>").append(esc(message)).append("</div>");
        b.append("<form method='get' action='/requests' class='card' style='margin-bottom:14px'><div class='grid2'>");
        b.append("<div><label>Search</label><input name='q' value='").append(esc(q == null ? "" : q)).append("' placeholder='Ticket, title, asset, requester'></div>");
        b.append("<div><label>Status</label><select name='status'>").append(option("", "All statuses", status))
                .append(option("PENDING","Pending",status)).append(option("ASSIGNED","Assigned",status))
                .append(option("IN_PROGRESS","In Progress",status)).append(option("ON_HOLD","On Hold",status))
                .append(option("COMPLETED","Completed",status)).append(option("CLOSED","Closed",status))
                .append(option("CANCELLED","Cancelled",status)).append(option("OVERDUE","Overdue",status))
                .append("</select></div>");
        b.append("<div><label>Priority</label><select name='priority'>").append(option("", "All priorities", priority))
                .append(option("CRITICAL","Critical",priority)).append(option("HIGH","High",priority))
                .append(option("MEDIUM","Medium",priority)).append(option("LOW","Low",priority))
                .append("</select></div>");
        b.append("<div style='display:flex;align-items:end'><button type='submit'>Filter</button></div>");
        b.append("</div></form>");
        b.append("<div class='card'><div class='muted'>Showing ").append(rows.size()).append(" request(s).</div>");
        if (rows.isEmpty()) {
            b.append("<p class='hint'>No requests found for the current filters.</p>");
        } else {
            b.append("<table><thead><tr><th>Ticket</th><th>Title / Asset</th><th>Status</th><th>Priority</th><th>Assignee</th><th>Reported</th></tr></thead><tbody>");
            java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");
            for (RequestRow r : rows) {
                String st = r.status() == null ? "" : r.status();
                String pr = r.priority() == null ? "" : r.priority();
                b.append("<tr>");
                b.append("<td data-label='Ticket'><strong>").append(esc(r.ticketNo())).append("</strong>");
                if (r.overdue()) b.append(" <span class='pill CANCELLED'>OVERDUE</span>");
                if (r.slaBreached()) b.append(" <span class='pill CANCELLED'>SLA</span>");
                b.append("</td>");
                b.append("<td data-label='Title'><div><strong>").append(esc(r.title())).append("</strong></div><div class='muted'>")
                        .append(esc(nvl(r.asset()))).append(" · ").append(esc(nvl(r.requester()))).append("</div></td>");
                b.append("<td data-label='Status'><span class='pill ").append(esc(st)).append("'>").append(esc(st.replace('_', ' '))).append("</span></td>");
                b.append("<td data-label='Priority'><span class='prio ").append(esc(pr)).append("'>").append(esc(pr)).append("</span></td>");
                b.append("<td data-label='Assignee'>").append(esc(nvl(r.assignee()))).append("</td>");
                b.append("<td data-label='Reported'>").append(r.reportedAt() == null ? "" : esc(r.reportedAt().format(dtf))).append("</td>");
                b.append("</tr>");
            }
            b.append("</tbody></table>");
        }
        b.append("</div>");
        return layout("DEIHS MMS - Requests", false, b.toString(), null);
    }

    private String newRequestPage(User user, String csrf, Map<String, String> form, String error, String ok) {
        StringBuilder b = new StringBuilder();
        b.append("<div class='topbar'><div><h1>Submit Request</h1><p class='sub'>Mobile request intake for the school LAN.</p></div>");
        b.append("<div class='nav'><a class='btnlink secondary' href='/requests'>Requests</a><a class='btnlink secondary' href='/'>Home</a></div></div>");
        if (error != null && !error.isBlank()) b.append("<div class='err'>").append(esc(error)).append("</div>");
        if (ok != null && !ok.isBlank()) b.append("<div class='ok'>").append(esc(ok)).append("</div>");
        try {
            var categories = lookupDao.categories();
            var facilities = lookupDao.facilities();
            var equipment = lookupDao.equipment(null);
            b.append("<form method='post' action='/requests/new' class='card'><input type='hidden' name='_csrf' value='").append(esc(csrf)).append("'>");
            b.append("<div class='grid2'>");
            b.append(fieldText("title", "Title", form.getOrDefault("title", ""), true, "full"));
            b.append(fieldSelect("categoryId", "Category", categories, form.get("categoryId"), true, ""));
            b.append(fieldSelect("facilityId", "Facility", facilities, form.get("facilityId"), false, ""));
            b.append(fieldSelect("equipmentId", "Equipment", equipment, form.get("equipmentId"), false, ""));
            b.append("<div><label>Priority</label><select name='priority' required>")
                    .append(option("", "Select priority", form.get("priority")))
                    .append(option("LOW","Low",form.get("priority"))).append(option("MEDIUM","Medium",form.get("priority")))
                    .append(option("HIGH","High",form.get("priority"))).append(option("CRITICAL","Critical",form.get("priority")))
                    .append("</select></div>");
            b.append("<div><label>Target date (optional)</label><input type='date' name='targetDate' value='").append(esc(form.getOrDefault("targetDate",""))).append("'></div>");
            b.append("<div class='full'><label>Description</label><textarea name='description' required>").append(esc(form.getOrDefault("description",""))).append("</textarea></div>");
            b.append("</div><div class='actions'><button type='submit'>Submit Request</button><a class='btnlink secondary' href='/requests'>Cancel</a></div>");
            b.append("<p class='hint'>Photo upload is available in the desktop app. This mobile form submits the request details directly to DEIHS MMS.</p></form>");
        } catch (Exception e) {
            b.append("<div class='err'>Could not load form options: ").append(esc(e.getMessage())).append("</div>");
        }
        return layout("DEIHS MMS - New Request", false, b.toString(), null);
    }

    private String submitSuccessPage(User user, CreatedRequest created) {
        String body = "<div class='topbar'><div><h1>Request Submitted</h1><p class='sub'>Your maintenance request was recorded.</p></div></div>"
                + "<div class='card'><div class='ok'>Request <strong>" + esc(created.ticketNo()) + "</strong> was submitted successfully.</div>"
                + "<div class='actions'><a class='btnlink' href='/requests'>View Requests</a><a class='btnlink secondary' href='/requests/new'>Submit Another</a><a class='btnlink secondary' href='/'>Home</a></div></div>";
        return layout("DEIHS MMS - Submitted", true, body, null);
    }

    private String errorPage(String title, String detail) {
        String body = "<h1>" + esc(title) + "</h1><div class='err'>" + esc(detail == null ? "Unexpected error" : detail) + "</div><div class='actions'><a class='btnlink secondary' href='/'>Home</a></div>";
        return layout("DEIHS MMS - Error", true, body, null);
    }

    private static String option(String value, String label, String selected) {
        boolean sel = value.equalsIgnoreCase(selected == null ? "" : selected.trim());
        return "<option value='" + esc(value) + "'" + (sel ? " selected" : "") + ">" + esc(label) + "</option>";
    }

    private static String fieldText(String name, String label, String value, boolean required, String extraClass) {
        return "<div class='" + esc(extraClass) + "'><label>" + esc(label) + "</label><input name='" + esc(name) + "' value='" + esc(value == null ? "" : value) + "'" + (required ? " required" : "") + "></div>";
    }

    private String fieldSelect(String name, String label, java.util.List<LookupOption> options, String selected, boolean required, String extraClass) {
        StringBuilder b = new StringBuilder("<div class='").append(esc(extraClass)).append("'><label>").append(esc(label)).append("</label><select name='").append(esc(name)).append("'").append(required ? " required" : "").append(">");
        b.append("<option value=''>").append(required ? "Select..." : "None").append("</option>");
        for (LookupOption o : options) {
            String v = String.valueOf(o.id());
            boolean sel = v.equals(selected);
            b.append("<option value='").append(v).append("'").append(sel ? " selected" : "").append(">").append(esc(o.label())).append("</option>");
        }
        b.append("</select></div>");
        return b.toString();
    }

    private static String nvl(String s) { return s == null || s.isBlank() ? "—" : s; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
    }
}
