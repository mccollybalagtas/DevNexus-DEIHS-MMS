package com.deihs.mms.util;

import com.deihs.mms.model.RequestRow;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.util.Callback;

import java.util.Locale;
import java.util.Set;

/**
 * Cell factories shared by every request table so badges and priorities
 * look identical across screens.
 */
public final class RequestCells {

    private static final Set<String> ACTIVE =
            Set.of("PENDING", "ASSIGNED", "IN_PROGRESS", "ON_HOLD");

    private RequestCells() {
    }

    public static Callback<TableColumn<RequestRow, String>, TableCell<RequestRow, String>> ticket() {
        return column -> new TableCell<>() {
            private final Label label = new Label();

            {
                label.getStyleClass().add("ticket-cell");
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                label.setText(value);
                setGraphic(label);
                setText(null);
            }
        };
    }

    public static Callback<TableColumn<RequestRow, String>, TableCell<RequestRow, String>> status() {
        return column -> new TableCell<>() {
            private final Label label = new Label();

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                RequestRow row = rowAt();
                label.getStyleClass().setAll("badge");
                if (row != null && row.overdue() && ACTIVE.contains(value)) {
                    label.setText("Overdue");
                    label.getStyleClass().add("badge-overdue");
                } else {
                    label.setText(Format.pretty(value));
                    label.getStyleClass().add(badgeClass(value));
                }
                setGraphic(label);
                setText(null);
            }

            private RequestRow rowAt() {
                int index = getIndex();
                if (index < 0 || getTableView() == null
                        || index >= getTableView().getItems().size()) {
                    return null;
                }
                return getTableView().getItems().get(index);
            }
        };
    }

    public static Callback<TableColumn<RequestRow, String>, TableCell<RequestRow, String>> priority() {
        return column -> new TableCell<>() {
            private final Label label = new Label();

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                label.setText(Format.pretty(value));
                label.getStyleClass().setAll("priority-" + value.toLowerCase(Locale.ROOT));
                setGraphic(label);
                setText(null);
            }
        };
    }

    /** Target date, painted red when the request is late. */
    public static Callback<TableColumn<RequestRow, String>, TableCell<RequestRow, String>> due() {
        return column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().remove("due-overdue");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                RequestRow row = rowAt(this);
                if (row != null && row.overdue() && ACTIVE.contains(row.status())) {
                    getStyleClass().add("due-overdue");
                }
            }
        };
    }

    private static RequestRow rowAt(TableCell<RequestRow, String> cell) {
        int index = cell.getIndex();
        if (index < 0 || cell.getTableView() == null
                || index >= cell.getTableView().getItems().size()) {
            return null;
        }
        return cell.getTableView().getItems().get(index);
    }

    public static String badgeClass(String status) {
        return switch (status) {
            case "PENDING" -> "badge-pending";
            case "ASSIGNED" -> "badge-assigned";
            case "IN_PROGRESS" -> "badge-progress";
            case "ON_HOLD" -> "badge-hold";
            case "COMPLETED" -> "badge-completed";
            case "CLOSED" -> "badge-closed";
            default -> "badge-rejected";
        };
    }
}
