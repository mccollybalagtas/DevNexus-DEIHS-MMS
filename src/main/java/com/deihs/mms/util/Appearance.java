package com.deihs.mms.util;

import com.deihs.mms.model.User;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;

/**
 * Applies the per-user look to the application root.
 *
 * <p>The stylesheet paints the green school theme by default. Alternative
 * themes and the compact density are switched on with extra style classes on
 * the shell root, so every child inherits them without each screen having to
 * know which theme is active.</p>
 */
public final class Appearance {

    private Appearance() {
    }

    public static void apply(Region root, User user) {
        if (root == null || user == null) {
            return;
        }
        apply(root, user.themeOrDefault(), user.densityOrDefault());
    }

    public static void apply(Region root, String theme, String density) {
        if (root == null) {
            return;
        }
        root.getStyleClass().removeIf(c ->
                c.startsWith("theme-") || "density-compact".equals(c));
        root.getStyleClass().add("theme-" + normaliseTheme(theme));
        if ("compact".equals(density)) {
            root.getStyleClass().add("density-compact");
        }
    }

    /**
     * Applies the current session's look to whatever root the node lives in.
     * Used by the profile page, which edits preferences in place.
     */
    public static void applyTo(Node node) {
        Parent root = node == null ? null : node.getParent();
        while (root != null && root.getParent() != null) {
            root = root.getParent();
        }
        if (root instanceof Region region) {
            apply(region, com.deihs.mms.service.Session.get());
        }
    }

    public static String normaliseTheme(String theme) {
        return switch (theme == null ? "" : theme.trim().toLowerCase()) {
            case "navy" -> "navy";
            case "dark" -> "dark";
            default -> "green";
        };
    }
}
