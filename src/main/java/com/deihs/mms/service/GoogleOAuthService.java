package com.deihs.mms.service;

import com.deihs.mms.config.AppConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Desktop Google OAuth 2.0 (loopback redirect + system browser + PKCE).
 * Config keys: google.clientId, google.clientSecret, google.redirectPort.
 */
public class GoogleOAuthService {

    public record GoogleProfile(String sub, String email, boolean emailVerified,
                                String givenName, String familyName) {}

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isConfigured() {
        String id = AppConfig.get("google.clientId", "");
        return id != null && !id.isBlank();
    }

    public GoogleProfile signIn() throws Exception {
        String clientId = required("google.clientId");
        String clientSecret = AppConfig.get("google.clientSecret", "");
        int port = AppConfig.getInt("google.redirectPort", 8765);
        String redirectUri = "http://127.0.0.1:" + port + "/callback";

        String state = randomUrlSafe(24);
        String verifier = randomUrlSafe(48);
        String challenge = pkceChallenge(verifier);

        CompletableFuture<Map<String, String>> callbackParams = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/callback", ex -> handleCallback(ex, callbackParams));
        server.setExecutor(null);
        server.start();
        try {
            String authUrl = AUTH_ENDPOINT
                    + "?client_id=" + enc(clientId)
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&response_type=code"
                    + "&scope=" + enc("openid email profile")
                    + "&state=" + enc(state)
                    + "&code_challenge=" + enc(challenge)
                    + "&code_challenge_method=S256"
                    + "&access_type=online"
                    + "&prompt=select_account";

            openBrowser(authUrl);

            Map<String, String> params;
            try {
                params = callbackParams.get(3, TimeUnit.MINUTES);
            } catch (TimeoutException te) {
                throw new AuthException("Google sign-in timed out. Please try again.");
            }

            if (params.containsKey("error")) {
                String err = params.get("error");
                String desc = params.getOrDefault("error_description", err);
                throw new AuthException("Google sign-in was cancelled or failed: " + desc);
            }
            if (!state.equals(params.get("state"))) {
                throw new AuthException("Google sign-in failed (invalid state). Please try again.");
            }
            String code = params.get("code");
            if (code == null || code.isBlank()) {
                throw new AuthException("Google sign-in failed: no authorization code received.");
            }

            String accessToken = exchangeCode(code, clientId, clientSecret, redirectUri, verifier);
            return fetchProfile(accessToken);
        } finally {
            server.stop(0);
        }
    }

    private void handleCallback(HttpExchange ex, CompletableFuture<Map<String, String>> future) throws IOException {
        Map<String, String> params = parseQuery(ex.getRequestURI().getRawQuery());
        String body = "<html><body style='font-family:sans-serif;padding:40px'>"
                + "<h3>Sign-in complete</h3><p>You can close this window and return to the application.</p>"
                + "</body></html>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        future.complete(params);
    }

    private String exchangeCode(String code, String clientId, String clientSecret,
                                String redirectUri, String verifier) throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code");
        body.append("&code=").append(enc(code));
        body.append("&client_id=").append(enc(clientId));
        if (clientSecret != null && !clientSecret.isBlank()) {
            body.append("&client_secret=").append(enc(clientSecret));
        }
        body.append("&redirect_uri=").append(enc(redirectUri));
        body.append("&code_verifier=").append(enc(verifier));

        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (resp.statusCode() >= 400 || json.has("error")) {
            String msg = json.has("error_description") ? json.get("error_description").getAsString()
                    : json.has("error") ? json.get("error").getAsString() : resp.body();
            throw new AuthException("Google token exchange failed: " + msg);
        }
        if (!json.has("access_token")) {
            throw new AuthException("Google sign-in failed: no access token returned.");
        }
        return json.get("access_token").getAsString();
    }

    private GoogleProfile fetchProfile(String accessToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(USERINFO_ENDPOINT))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new AuthException("Could not read Google profile (" + resp.statusCode() + ").");
        }
        JsonObject j = JsonParser.parseString(resp.body()).getAsJsonObject();
        String email = opt(j, "email");
        boolean verified = j.has("email_verified") && j.get("email_verified").getAsBoolean();
        return new GoogleProfile(
                opt(j, "sub"),
                email,
                verified,
                opt(j, "given_name"),
                opt(j, "family_name")
        );
    }

    private static String opt(JsonObject j, String key) {
        return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsString() : null;
    }

    private static void openBrowser(String url) throws Exception {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url));
            return;
        }
        // Linux fallback
        try {
            new ProcessBuilder("xdg-open", url).start();
        } catch (IOException e) {
            throw new AuthException("Could not open a browser for Google sign-in. Open this URL manually:\n" + url);
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            String k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.put(k, v);
        }
        return map;
    }

    private static String pkceChallenge(String verifier) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String required(String key) throws AuthException {
        String v = AppConfig.get(key, "");
        if (v == null || v.isBlank()) {
            throw new AuthException("Google sign-in is not configured (" + key + " is missing).");
        }
        return v.trim();
    }
}
