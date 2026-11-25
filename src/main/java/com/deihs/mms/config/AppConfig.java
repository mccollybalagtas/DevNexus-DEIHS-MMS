package com.deihs.mms.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Reads db.properties from the classpath, then applies an optional
 * db.local.properties (working directory, then ~/.deihs-mms/), then any
 * -Dkey=value launch overrides. The committed file therefore ships the
 * XAMPP defaults (port 3306) while a developer or a client PC can point at
 * another server without editing tracked files:
 *
 *   java -Ddb.host=192.168.1.10 -jar deihs-mms.jar
 */
public final class AppConfig {

    private static final Properties PROPS = new Properties();
    private static String localOverride;

    static {
        loadClasspathDefaults();
        loadLocalOverride();
        applySystemOverrides();
    }

    private AppConfig() {
    }

    private static void loadClasspathDefaults() {
        try (InputStream in = AppConfig.class.getResourceAsStream("/db.properties")) {
            if (in == null) {
                throw new IllegalStateException("db.properties is missing from the classpath");
            }
            PROPS.load(in);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Optional, untracked file. Keeping it out of the repo means the shipped
     * defaults stay on XAMPP's port 3306 while this machine can still use a
     * different instance.
     */
    private static void loadLocalOverride() {
        Path[] candidates = {
                Paths.get("db.local.properties"),
                Paths.get(System.getProperty("user.home"), ".deihs-mms", "db.local.properties")
        };
        for (Path candidate : candidates) {
            if (!Files.isReadable(candidate)) {
                continue;
            }
            try (InputStream in = Files.newInputStream(candidate)) {
                PROPS.load(in);
                localOverride = candidate.toAbsolutePath().toString();
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
            return;
        }
    }

    private static void applySystemOverrides() {
        for (String key : PROPS.stringPropertyNames()) {
            String override = System.getProperty(key);
            if (override != null) {
                PROPS.setProperty(key, override);
            }
        }
    }

    /** Path of the local override file in use, or null when there is none. */
    public static String localOverride() {
        return localOverride;
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
