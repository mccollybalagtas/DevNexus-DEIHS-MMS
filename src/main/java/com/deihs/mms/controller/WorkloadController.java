package com.deihs.mms.controller;

import com.deihs.mms.dao.WorkloadDao;
import com.deihs.mms.model.RequestRow;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.model.WorkloadSummary;
import com.deihs.mms.service.Session;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.List;
import java.util.function.IntConsumer;

public class WorkloadController {

    @FXML private ComboBox<TechOption> technicianBox;
    @FXML private DatePicker fromPicker;
    @FXML private DatePicker toPicker;
    @FXML private Label statusLabel;
    @FXML private TableView<WorkloadSummary> summaryTable;
    @FXML private TableColumn<WorkloadSummary, String> techCol;
    @FXML private TableColumn<WorkloadSummary, Number> openCol;
    @FXML private TableColumn<WorkloadSummary, Number> overdueCol;
    @FXML private TableColumn<WorkloadSummary, Number> dueTodayCol;
    @FXML private TableColumn<WorkloadSummary, Number> dueSoonCol;
    @FXML private TableColumn<WorkloadSummary, Number> slaCol;
    @FXML private TableView<RequestRow> agendaTable;
    @FXML private TableColumn<RequestRow, String> ticketCol;
    @FXML private TableColumn<RequestRow, String> titleCol;
    @FXML private TableColumn<RequestRow, String> assigneeCol;
    @FXML private TableColumn<RequestRow, String> statusCol;
    @FXML private TableColumn<RequestRow, String> priorityCol;
    @FXML private TableColumn<RequestRow, String> targetCol;
    @FXML private TableColumn<RequestRow, String> slaDueCol;

    private final WorkloadDao dao = new WorkloadDao();
    private User user;
    private IntConsumer openRequestHandler;
    private record TechOption(Integer id, String name) { @Override public String toString() { return name; } }

    public void setOpenRequestHandler(IntConsumer handler) { this.openRequestHandler = handler; }

    @FXML
    private void initialize() {
        user = Session.get();
        setupTables();
        setupRoleScope();
        onThisWeek();
    }

    private void setupTables() {
        summaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        agendaTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        techCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        openCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().openRequests()));
        overdueCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().overdue()));
        dueTodayCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().dueToday()));
        dueSoonCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().dueSoon()));
        slaCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().slaBreached()));

        ticketCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().ticketNo()));
        titleCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().title()));
        assigneeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(nz(c.getValue().assignee(), "Unassigned")));
        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.pretty(c.getValue().status())));
        priorityCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.pretty(c.getValue().priority())));
        targetCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.date(c.getValue().targetDate())));
        slaDueCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.dateTime(c.getValue().slaDueAt())));

        agendaTable.setRowFactory(tv -> {
            TableRow<RequestRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty() && openRequestHandler != null) {
                    openRequestHandler.accept(row.getItem().id());
                }
            });
            return row;
        });
        summaryTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && (user.role() == Role.ADMIN || user.role() == Role.MAINT_HEAD)) {
                for (TechOption opt : technicianBox.getItems()) {
                    if (opt.id() != null && opt.id() == n.userId()) { technicianBox.setValue(opt); break; }
                }
                loadAgenda();
            }
        });
    }

    private void setupRoleScope() {
        if (user.role() == Role.PERSONNEL) {
            technicianBox.setItems(FXCollections.observableArrayList(new TechOption(user.id(), user.fullName())));
            technicianBox.getSelectionModel().selectFirst();
            technicianBox.setDisable(true);
        } else {
            technicianBox.setItems(FXCollections.observableArrayList(new TechOption(null, "All technicians")));
            technicianBox.getSelectionModel().selectFirst();
        }
    }

    @FXML private void onRefresh() { loadAll(); }
    @FXML private void onClearFilters() {
        if (user.role() != Role.PERSONNEL) technicianBox.getSelectionModel().selectFirst();
        fromPicker.setValue(null); toPicker.setValue(null);
        loadAll();
    }
    @FXML private void onThisWeek() {
        LocalDate now = LocalDate.now();
        fromPicker.setValue(now.with(java.time.DayOfWeek.MONDAY));
        toPicker.setValue(now.with(java.time.DayOfWeek.SUNDAY));
        loadAll();
    }
    @FXML private void onThisMonth() {
        LocalDate now = LocalDate.now();
        fromPicker.setValue(now.withDayOfMonth(1));
        toPicker.setValue(now.withDayOfMonth(now.lengthOfMonth()));
        loadAll();
    }

    private Integer selectedTechId() {
        TechOption opt = technicianBox.getValue();
        if (opt == null) return user.role() == Role.PERSONNEL ? user.id() : null;
        if (opt.id() == null) return user.role() == Role.PERSONNEL ? user.id() : null;
        return opt.id();
    }

    private void loadAll() { loadSummary(); loadAgenda(); }

    private void loadSummary() {
        final Integer techId = user.role() == Role.PERSONNEL ? user.id() : null;
        Task<List<WorkloadSummary>> task = new Task<>() { @Override protected List<WorkloadSummary> call() throws Exception { return dao.workloadSummary(techId); } };
        task.setOnSucceeded(e -> {
            List<WorkloadSummary> rows = task.getValue();
            summaryTable.setItems(FXCollections.observableArrayList(rows));
            if (user.role() != Role.PERSONNEL) {
                TechOption selected = technicianBox.getValue();
                List<TechOption> opts = new java.util.ArrayList<>();
                opts.add(new TechOption(null, "All technicians"));
                for (WorkloadSummary s : rows) opts.add(new TechOption(s.userId(), s.name()));
                technicianBox.setItems(FXCollections.observableArrayList(opts));
                if (selected != null) {
                    for (TechOption o : opts) if (java.util.Objects.equals(o.id(), selected.id())) { technicianBox.setValue(o); break; }
                }
                if (technicianBox.getValue() == null) technicianBox.getSelectionModel().selectFirst();
            }
        });
        task.setOnFailed(e -> showErr("Could not load workload summary", task.getException()));
        Thread t = new Thread(task, "load-workload-summary"); t.setDaemon(true); t.start();
    }

    private void loadAgenda() {
        final Integer techId = selectedTechId();
        final LocalDate from = fromPicker.getValue();
        final LocalDate to = toPicker.getValue();
        Task<List<RequestRow>> task = new Task<>() { @Override protected List<RequestRow> call() throws Exception { return dao.agenda(from, to, techId); } };
        task.setOnSucceeded(e -> {
            List<RequestRow> rows = task.getValue();
            agendaTable.setItems(FXCollections.observableArrayList(rows));
            statusLabel.setText(rows.size() + " open request(s) in range");
        });
        task.setOnFailed(e -> showErr("Could not load agenda", task.getException()));
        Thread t = new Thread(task, "load-workload-agenda"); t.setDaemon(true); t.start();
    }

    private void showErr(String title, Throwable ex) {
        Window w = agendaTable.getScene() == null ? null : agendaTable.getScene().getWindow();
        AlertUtil.error(w, title, ex == null ? "Unknown error" : ex.getMessage(), ex);
    }

    private static String nz(String v, String fallback) { return v == null || v.isBlank() ? fallback : v; }
}
