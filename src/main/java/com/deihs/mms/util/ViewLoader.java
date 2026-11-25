package com.deihs.mms.util;

import com.deihs.mms.Main;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.util.Objects;

public final class ViewLoader {

    /** Package that holds every view; bare file names resolve here. */
    private static final String VIEW_PACKAGE = "/com/deihs/mms/view/";

    private ViewLoader() {
    }

    /**
     * Accepts either an absolute classpath path ("/com/.../X.fxml") or a bare
     * file name ("X.fxml"), which is resolved against the view package.
     * Resolving here keeps every caller working no matter which form it uses.
     */
    private static java.net.URL resolve(String fxmlPath) {
        String path = fxmlPath.startsWith("/") ? fxmlPath : VIEW_PACKAGE + fxmlPath;
        return Objects.requireNonNull(ViewLoader.class.getResource(path),
                "FXML not found: " + fxmlPath + " (resolved to " + path + ")");
    }

    public static Parent load(String fxmlPath) {
        try {
            FXMLLoader loader = new FXMLLoader(resolve(fxmlPath));
            return loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load view: " + fxmlPath, e);
        }
    }

    public static FXMLLoader loader(String fxmlPath) {
        return new FXMLLoader(resolve(fxmlPath));
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
