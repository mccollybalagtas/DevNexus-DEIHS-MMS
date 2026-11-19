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
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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

    private static String blank(String v) { return v == null || v.isBlank() ? "—" : v; }

    private Window ownerWindow() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }
}
