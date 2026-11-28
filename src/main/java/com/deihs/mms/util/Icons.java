package com.deihs.mms.util;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Small line icons drawn as SVG paths so they render identically on every
 * PC regardless of which fonts are installed. Paths are 24x24 Material
 * Design glyphs, scaled down to the requested size.
 */
public final class Icons {

    public static final String DASHBOARD =
            "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";

    public static final String REQUESTS =
            "M19 3h-4.18C14.4 1.84 13.3 1 12 1s-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14"
                    + "c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1"
                    + " .45-1 1-1zm-2 14l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z";

    public static final String FACILITIES =
            "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z";

    public static final String EQUIPMENT =
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61"
                    + "l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54"
                    + "c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94"
                    + "l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58"
                    + "c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32"
                    + "c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84"
                    + "c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22"
                    + "l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6"
                    + "s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";

    public static final String SCHEDULES =
            "M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14"
                    + "c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z";

    public static final String USERS =
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0"
                    + "c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2"
                    + "c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05"
                    + " 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z";

    public static final String REPORTS =
            "M5 9.2h3V19H5zM10.6 5h2.8v14h-2.8zm5.6 8H19v6h-2.8z";

    public static final String AUDIT =
            "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2z"
                    + "m0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z";

    public static final String TASKS =
            "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";

    public static final String MENU =
            "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z";

    public static final String PROFILE =
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2"
                    + "c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z";

    public static final String SETTINGS =
            "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58"
                    + "c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96"
                    + "c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84"
                    + "c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96"
                    + "c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58"
                    + "c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61"
                    + "l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54"
                    + "c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54"
                    + "c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32"
                    + "c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6"
                    + "s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";

    public static final String LOGOUT =
            "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.59L17 17l5-5zM4 5h8V3H4"
                    + "c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z";

    public static final String CHEVRON_RIGHT =
            "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z";

    public static final String CAMERA =
            "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6"
                    + "c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5"
                    + " 5 2.24 5 5-2.24 5-5 5zm0-2a3 3 0 1 0 0-6 3 3 0 0 0 0 6z";

    public static final String MAIL =
            "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6"
                    + "c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z";

    public static final String BELL =
            "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4"
                    + "c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.64 5.36 6 7.92 6 11v5"
                    + "l-2 2v1h16v-1l-2-2z";

    public static final String PARTS =
            "M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23"
                    + "C3.17 5.57 3 6.02 3 6.5V19c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5"
                    + "c0-.48-.17-.93-.46-1.27zM12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5z"
                    + "M5.12 5l.81-1h12l.94 1H5.12z";

    public static final String CALENDAR =
            "M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19"
                    + "c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z";

    public static final String BACKUP =
            "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14"
                    + "c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z"
                    + "M14 13v4h-4v-4H7l5-5 5 5h-3z";

    public static final String QR =
            "M3 11h8V3H3v8zm2-6h4v4H5V5zM3 21h8v-8H3v8zm2-6h4v4H5v-4zM13 3v8h8V3h-8zm6 6h-4V5h4v4z"
                    + "M19 19h2v2h-2zM13 13h2v2h-2zM15 15h2v2h-2zM13 17h2v2h-2zM17 17h2v2h-2z"
                    + "M19 15h2v2h-2zM15 19h2v2h-2z";


    public static final String CLOSE =
            "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19"
                    + " 12 13.41 17.59 19 19 17.59 13.41 12z";

    public static final String CHECK_CIRCLE =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z"
                    + "m-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z";

    public static final String SUN =
            "M6.76 4.84l-1.8-1.79-1.41 1.41 1.79 1.79 1.42-1.41zM4 10.5H1v2h3v-2z"
                    + "m9-9.95h-2V3.5h2V.55zm7.45 3.91l-1.41-1.41-1.79 1.79 1.41 1.41"
                    + " 1.79-1.79zm-3.21 13.7l1.79 1.8 1.41-1.41-1.8-1.79-1.4 1.4z"
                    + "M20 10.5v2h3v-2h-3zm-8-5c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69"
                    + " 6-6-2.69-6-6-6zm-1 16.95h2V19.5h-2v2.95zm-7.45-3.91l1.41 1.41"
                    + " 1.79-1.8-1.41-1.41-1.79 1.8z";

    public static final String MOON =
            "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01"
                    + "-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5"
                    + " 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9"
                    + " 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5"
                    + "s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5"
                    + "s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9"
                    + "s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z";

    private Icons() {
    }

    /** Returns a fixed-size, non-interactive icon node for use as a button graphic. */
    public static Node of(String path, double size) {
        return build(path, size, Color.web("#E6F0E9"), true);
    }

    /**
     * Coloured variant for top-bar, drawer and profile controls. It
     * deliberately does not carry the {@code nav-svg} class, so the sidebar
     * stylesheet rule cannot recolour it.
     */
    public static Node of(String path, double size, String color) {
        return build(path, size, Color.web(color), false);
    }

    private static Node build(String path, double size, Color color, boolean navStyle) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.setFill(color);
        if (navStyle) {
            svg.getStyleClass().add("nav-svg");
        }
        svg.setScaleX(size / 24.0);
        svg.setScaleY(size / 24.0);
        svg.setMouseTransparent(true);

        StackPane holder = new StackPane(svg);
        holder.setMinSize(size, size);
        holder.setPrefSize(size, size);
        holder.setMaxSize(size, size);
        holder.setMouseTransparent(true);
        return holder;
    }
}
