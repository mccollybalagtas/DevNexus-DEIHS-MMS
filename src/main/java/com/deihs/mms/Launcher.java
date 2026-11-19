package com.deihs.mms;

/**
 * Plain launcher used by the shaded jar.
 * JavaFX refuses to start when the main class extends Application and JavaFX
 * is on the classpath, so this indirection is required for distribution.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
