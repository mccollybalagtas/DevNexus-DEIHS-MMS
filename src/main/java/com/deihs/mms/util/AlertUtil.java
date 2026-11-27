package com.deihs.mms.util;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

public final class AlertUtil {

    private AlertUtil() {
    }

    public static void info(Window owner, String title, String message) {
        show(owner, Alert.AlertType.INFORMATION, title, message, null);
    }

    public static void warn(Window owner, String title, String message) {
        show(owner, Alert.AlertType.WARNING, title, message, null);
    }

    public static void error(Window owner, String title, String message) {
        show(owner, Alert.AlertType.ERROR, title, message, null);
    }

    /** Full error with expandable technical detail. */
    public static void error(Window owner, String title, String message, Throwable ex) {
        show(owner, Alert.AlertType.ERROR, title, message, ex);
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(owner, alert.getDialogPane());
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private static void show(Window owner, Alert.AlertType type, String title,
                             String message, Throwable ex) {
        Alert alert = new Alert(type);
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(owner, alert.getDialogPane());

        if (ex != null) {
            TextArea area = new TextArea(buildDetail(ex));
            area.setEditable(false);
            area.setWrapText(true);
            area.setMaxWidth(Double.MAX_VALUE);
            area.setMaxHeight(Double.MAX_VALUE);
            alert.getDialogPane().setExpandableContent(area);
            alert.getDialogPane().setExpanded(true);
        }

        alert.showAndWait();
    }

    private static void applyTheme(Window owner, DialogPane pane) {
        ViewLoader.applyAppStyles(pane);
        List<String> themeClasses = List.of(
                "theme-green", "theme-navy", "theme-dark",
                "density-compact", "text-large", "text-xlarge");
        Window source = owner;
        if (source == null || source.getScene() == null || source.getScene().getRoot() == null) {
            for (Window w : Window.getWindows()) {
                if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() != null) {
                    source = w;
                    break;
                }
            }
        }
        if (source != null && source.getScene() != null && source.getScene().getRoot() != null) {
            for (String cls : themeClasses) {
                if (source.getScene().getRoot().getStyleClass().contains(cls)) {
                    if (!pane.getStyleClass().contains(cls)) {
                        pane.getStyleClass().add(cls);
                    }
                }
            }
        }
    }

    private static String buildDetail(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable t = ex;
        while (t != null) {
            sb.append(t.getClass().getSimpleName()).append(": ")
              .append(t.getMessage() == null ? "(no message)" : t.getMessage())
              .append('\n');
            t = t.getCause();
        }
        return sb.toString();
    }
}
