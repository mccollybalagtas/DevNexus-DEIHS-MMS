package com.deihs.mms.util;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

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
