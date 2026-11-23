package com.deihs.mms.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads db.properties from the classpath. Any key can be overridden at
 * launch time with -Dkey=value, which is how a client PC points at the
 * XAMPP host on the LAN:
 *
 *   java -Ddb.host=192.168.1.10 -jar deihs-mms.jar
 */
public final class AppConfig {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = AppConfig.class.getResourceAsStream("/db.properties")) {
            if (in == null) {
                throw new IllegalStateException("db.properties is missing from the classpath");
            }
            PROPS.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        for (String key : PROPS.stringPropertyNames()) {
            String override = System.getProperty(key);
            if (override != null) {
                PROPS.setProperty(key, override);
            }
        }
    }

    private AppConfig() {
    }

    public static String get(String key) {
        return PROPS.getProperty(key);
    }

    public static String get(String key, String fallback) {
        return PROPS.getProperty(key, fallback);
    }

    public static int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(PROPS.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String jdbcUrl() {
        String host = get("db.host", "localhost");
        int port = getInt("db.port", 3306);
        String name = get("db.name", "deihs_mms");
        return "jdbc:mysql://" + host + ":" + port + "/" + name
                + "?useSSL=false"
                + "&allowPublicKeyRetrieval=true"
                + "&serverTimezone=Asia/Manila"
                + "&characterEncoding=UTF-8"
                + "&connectTimeout=5000";
    }
}
