package com.deihs.mms.util;

import com.deihs.mms.config.AppConfig;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * A deliberately small SMTP client for the verification code.
 *
 * <p>The project builds offline and the Jakarta Mail artifacts are not in the
 * local repository, so pulling a full mail library in would break the build on
 * every machine that has not pre-downloaded it. The verification mail is a
 * single plain-text/HTML message with no attachments, which the protocol below
 * covers in about two hundred lines. When no host is configured the caller
 * falls back to showing the code on screen (development mode).</p>
 */
public final class SmtpMailer {

    /** Thrown when the server rejects or the connection cannot be made. */
    public static class MailException extends Exception {
        public MailException(String message) {
            super(message);
        }

        public MailException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int TIMEOUT_MS = 12_000;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String from;
    private final boolean startTls;
    private final boolean ssl;
    private final boolean auth;

    public SmtpMailer() {
        this.host = value("smtp.host");
        this.port = intValue("smtp.port", 587);
        this.user = value("smtp.user");
        this.password = value("smtp.password");
        String configuredFrom = value("smtp.from");
        this.from = configuredFrom.isBlank() ? user : configuredFrom;
        this.startTls = boolValue("smtp.starttls", true);
        this.ssl = boolValue("smtp.ssl", false);
        this.auth = boolValue("smtp.auth", true);
    }

    /** True when a host (and, for authenticated servers, a sender) is set. */
    public boolean isConfigured() {
        return !host.isBlank() && !from.isBlank();
    }

    /** Address shown in the "from" header, or a safe placeholder. */
    public String sender() {
        return from.isBlank() ? "no-reply@deihs.local" : from;
    }

    /**
     * Sends one HTML message. Blocks for the duration of the SMTP dialogue;
     * callers should run it on a background thread.
     */
    public void send(String to, String subject, String htmlBody) throws MailException {
        if (!isConfigured()) {
            throw new MailException("SMTP is not configured.");
        }
        if (to == null || to.isBlank()) {
            throw new MailException("No recipient address.");
        }

        Socket socket = null;
        try {
            socket = open();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            expect(in, 220, "server greeting");
            String ehlo = "EHLO " + localName();
            command(out, ehlo);
            expect(in, 250, "EHLO");

            if (startTls && !ssl) {
                command(out, "STARTTLS");
                expect(in, 220, "STARTTLS");
                socket = upgrade(socket);
                in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                command(out, ehlo);
                expect(in, 250, "EHLO after STARTTLS");
            }

            if (auth && !user.isBlank()) {
                command(out, "AUTH LOGIN");
                expect(in, 334, "AUTH LOGIN");
                command(out, Base64.getEncoder()
                        .encodeToString(user.getBytes(StandardCharsets.UTF_8)));
                expect(in, 334, "username");
                command(out, Base64.getEncoder()
                        .encodeToString(password.getBytes(StandardCharsets.UTF_8)));
                expect(in, 235, "authentication");
            }

            command(out, "MAIL FROM:<" + sender() + ">");
            expect(in, 250, "MAIL FROM");
            command(out, "RCPT TO:<" + to.trim() + ">");
            expect(in, 250, "RCPT TO");
            command(out, "DATA");
            expect(in, 354, "DATA");

            writeMessage(out, to.trim(), subject, htmlBody);
            expect(in, 250, "message body");

            command(out, "QUIT");
            // Some servers close before answering QUIT; that is not an error.
        } catch (IOException e) {
            throw new MailException("Could not reach the mail server: " + e.getMessage(), e);
        } finally {
            closeQuietly(socket);
        }
    }

    private Socket open() throws IOException {
        Socket socket;
        if (ssl) {
            socket = SSLSocketFactory.getDefault().createSocket();
        } else {
            socket = new Socket();
        }
        socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
        socket.setSoTimeout(TIMEOUT_MS);
        if (socket instanceof SSLSocket sslSocket) {
            sslSocket.startHandshake();
        }
        return socket;
    }

    private Socket upgrade(Socket plain) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket secure = (SSLSocket) factory.createSocket(
                plain, host, port, true);
        secure.setSoTimeout(TIMEOUT_MS);
        secure.startHandshake();
        return secure;
    }

    private void writeMessage(BufferedWriter out, String to, String subject,
                              String htmlBody) throws IOException {
        String boundary = "deihs-" + Long.toHexString(System.nanoTime());
        String body = htmlBody == null ? "" : htmlBody;

        out.write("From: DEIHS MMS <" + sender() + ">\r\n");
        out.write("To: <" + to + ">\r\n");
        out.write("Subject: " + encodeHeader(subject) + "\r\n");
        out.write("Date: " + DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now()) + "\r\n");
        out.write("MIME-Version: 1.0\r\n");
        out.write("Content-Type: text/html; charset=UTF-8\r\n");
        out.write("Content-Transfer-Encoding: base64\r\n");
        out.write("\r\n");

        String encoded = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        out.write(encoded);
        out.write("\r\n");
        // A lone dot would end the DATA phase, so the terminator is on its own line.
        out.write(".\r\n");
        out.flush();
    }

    /** RFC 2047 encoding for a non-ASCII subject. */
    private String encodeHeader(String text) {
        String safe = text == null ? "" : text;
        boolean ascii = safe.chars().allMatch(c -> c < 128);
        if (ascii) {
            return safe;
        }
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    private void command(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }

    private void expect(BufferedReader in, int expected, String stage)
            throws IOException, MailException {
        String line = in.readLine();
        if (line == null) {
            throw new MailException("Mail server closed the connection during " + stage + ".");
        }
        int code;
        try {
            code = Integer.parseInt(line.substring(0, 3));
        } catch (RuntimeException e) {
            throw new MailException("Unexpected reply during " + stage + ": " + line);
        }
        // Multi-line replies continue with "250-"; read to the final line.
        while (line.length() > 3 && line.charAt(3) == '-') {
            line = in.readLine();
            if (line == null) {
                break;
            }
        }
        if (code != expected) {
            throw new MailException("Mail server refused " + stage + " (" + code + "): "
                    + (line == null ? "" : line.trim()));
        }
    }

    private String localName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "localhost";
        }
    }

    private static String value(String key) {
        String raw = AppConfig.get(key, "");
        return raw == null ? "" : raw.trim();
    }

    private static int intValue(String key, int fallback) {
        try {
            return Integer.parseInt(value(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolValue(String key, boolean fallback) {
        String raw = value(key).toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return fallback;
        }
        return raw.equals("true") || raw.equals("1") || raw.equals("yes");
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }
}
