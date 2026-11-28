package com.deihs.mms.controller;

import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.model.AuditLogEntry;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.sql.SQLException;
import java.util.List;

public class AuditLogController {

    @FXML private TextField searchField;
    @FXML private Label countLabel;
    @FXML private TableView<AuditLogEntry> table;
    @FXML private TableColumn<AuditLogEntry, String> timeCol;
    @FXML private TableColumn<AuditLogEntry, String> actorCol;
    @FXML private TableColumn<AuditLogEntry, String> actionCol;
    @FXML private TableColumn<AuditLogEntry, String> entityCol;
    @FXML private TableColumn<AuditLogEntry, String> entityIdCol;
    @FXML private TableColumn<AuditLogEntry, String> detailsCol;
    @FXML private TableColumn<AuditLogEntry, Void> diffCol;

    private final AuditDao auditDao = new AuditDao();
    private final ObservableList<AuditLogEntry> rows = FXCollections.observableArrayList();
    private int generation;

    @FXML
    private void initialize() {
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No audit log entries found."));

        timeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.dateTime(c.getValue().createdAt())));
        actorCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().actor())));
        actionCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().action())));
        entityCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().entityType())));
        entityIdCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().entityId())));
        detailsCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().details())));

        diffCol.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("View diff");
            {
                btn.getStyleClass().addAll("btn-ghost", "btn-table");
                btn.setOnAction(e -> {
                    int i = getIndex();
                    if (i >= 0 && i < getTableView().getItems().size()) {
                        showDiff(getTableView().getItems().get(i));
                    }
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); return; }
                int i = getIndex();
                AuditLogEntry entry = (i >= 0 && i < getTableView().getItems().size()) ? getTableView().getItems().get(i) : null;
                boolean hasDiff = entry != null && ((entry.oldValues() != null && !entry.oldValues().isBlank())
                        || (entry.newValues() != null && !entry.newValues().isBlank()));
                btn.setDisable(!hasDiff);
                btn.setText(hasDiff ? "View diff" : "No diff");
                setGraphic(btn);
            }
        });

        table.setRowFactory(tv -> {
            javafx.scene.control.TableRow<AuditLogEntry> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) showDiff(row.getItem());
            });
            return row;
        });

        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> refresh());
        searchField.textProperty().addListener((obs, o, n) -> debounce.playFromStart());

        refresh();
    }

    private void refresh() {
        final int req = ++generation;
        String keyword = searchField.getText();
        Task<List<AuditLogEntry>> task = new Task<>() {
            @Override protected List<AuditLogEntry> call() throws Exception {
                return auditDao.search(keyword, 500);
            }
        };
        task.setOnSucceeded(e -> {
            if (req != generation) return;
            rows.setAll(task.getValue());
            countLabel.setText(rows.size() + (rows.size() == 1 ? " entry" : " entries"));
        });
        task.setOnFailed(e -> {
            if (req != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not load audit log",
                    ex == null ? "Unknown error." : ex.getMessage());
        });
        Thread t = new Thread(task, "load-audit-log");
        t.setDaemon(true);
        t.start();
    }

    private void showDiff(AuditLogEntry entry) {
        if (entry == null) return;
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(ownerWindow());
        dialog.setTitle("Audit Change Details");
        dialog.setHeaderText(entry.action() + " — " + blank(entry.entityType()) + " " + blank(entry.entityId()));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TextArea oldArea = diffArea(entry.oldValues());
        TextArea newArea = diffArea(entry.newValues());
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        Label oldLbl = new Label("Old values");
        oldLbl.getStyleClass().add("card-title");
        Label newLbl = new Label("New values");
        newLbl.getStyleClass().add("card-title");
        grid.add(oldLbl, 0, 0);
        grid.add(newLbl, 1, 0);
        grid.add(oldArea, 0, 1);
        grid.add(newArea, 1, 1);
        GridPane.setHgrow(oldArea, Priority.ALWAYS);
        GridPane.setHgrow(newArea, Priority.ALWAYS);
        GridPane.setVgrow(oldArea, Priority.ALWAYS);
        GridPane.setVgrow(newArea, Priority.ALWAYS);

        Label meta = new Label("User: " + blank(entry.actor()) + "    Time: " + Format.dateTime(entry.createdAt()) + "\nDetails: " + blank(entry.details()));
        meta.setWrapText(true);
        VBox content = new VBox(8, meta, grid);
        content.setPadding(new Insets(4));
        VBox.setVgrow(grid, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(920, 520);
        com.deihs.mms.util.ViewLoader.applyAppStyles(dialog.getDialogPane());
        dialog.showAndWait();
    }

    private static TextArea diffArea(String value) {
        TextArea a = new TextArea(value == null || value.isBlank() ? "(none)" : prettyDiff(value));
        a.setEditable(false);
        a.setWrapText(true);
        a.setPrefRowCount(14);
        a.setStyle("-fx-font-family: 'monospace';");
        return a;
    }

    private static String prettyDiff(String value) {
        String v = value.trim();
        if (v.startsWith("{") && v.contains(":")) {
            return v.replace(",", ",\n").replace("{", "{\n ").replace("}", "\n}");
        }
        return v;
    }

    private static String blank(String v) { return v == null || v.isBlank() ? "—" : v; }

    private Window ownerWindow() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }
}
