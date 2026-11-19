package com.deihs.mms.util;

import com.deihs.mms.Main;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.util.Objects;

public final class ViewLoader {

    private ViewLoader() {
    }

    public static Parent load(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                    ViewLoader.class.getResource(fxmlPath),
                    "FXML not found: " + fxmlPath));
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load view: " + fxmlPath, e);
        }
    }

    public static FXMLLoader loader(String fxmlPath) {
        return new FXMLLoader(Objects.requireNonNull(
                ViewLoader.class.getResource(fxmlPath),
                "FXML not found: " + fxmlPath));
    }

    /**
     * Gives a dialog the same look as the main window. Dialogs live in their
     * own scene, so they do not inherit the application stylesheet.
     */
    public static void applyAppStyles(Parent parent) {
        String css = Objects.requireNonNull(
                ViewLoader.class.getResource(Main.STYLESHEET),
                "Stylesheet not found").toExternalForm();
        if (!parent.getStylesheets().contains(css)) {
            parent.getStylesheets().add(css);
        }
    }
}
