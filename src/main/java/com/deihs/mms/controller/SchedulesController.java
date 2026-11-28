package com.deihs.mms.controller;

import com.deihs.mms.dao.ScheduleDao;
import com.deihs.mms.model.Schedule;
import com.deihs.mms.model.ScheduleDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.ScheduleService;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class SchedulesController {

    @FXML private Button addButton;
    @FXML private Button generateButton;
    @FXML private TextField searchField;
    @FXML private CheckBox dueOnlyBox;
    @FXML private CheckBox showInactiveBox;
    @FXML private Label countLabel;
    @FXML private TableView<Schedule> table;
    @FXML private TableColumn<Schedule, String> titleCol;
    @FXML private TableColumn<Schedule, String> targetCol;
    @FXML private TableColumn<Schedule, String> freqCol;
    @FXML private TableColumn<Schedule, String> nextDueCol;
    @FXML private TableColumn<Schedule, String> lastDoneCol;
    @FXML private TableColumn<Schedule, String> assignedCol;
    @FXML private TableColumn<Schedule, String> statusCol;
    @FXML private TableColumn<Schedule, Void> actionsCol;
    @FXML private TabPane tabPane;
    @FXML private GridPane calendarGrid;
    @FXML private Label monthLabel;
    @FXML private Label calendarLegendLabel;

    private final ScheduleDao scheduleDao = new ScheduleDao();
    private final ScheduleService scheduleService = new ScheduleService();
    private final ObservableList<Schedule> rows = FXCollections.observableArrayList();

    private int generation;
    private boolean canManage;
    private boolean canMarkDone;
    private YearMonth calendarMonth = YearMonth.now();

    @FXML
    private void initialize() {
        User user = Session.get();
        canManage = Permissions.canManageSchedules(user);
        canMarkDone = canManage || user.role() == com.deihs.mms.model.Role.PERSONNEL;
        addButton.setVisible(canManage);
        addButton.setManaged(canManage);
        generateButton.setVisible(canManage);
        generateButton.setManaged(canManage);

        setupTable();

        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> refresh());
        searchField.textProperty().addListener((obs, o, n) -> debounce.playFromStart());
        dueOnlyBox.selectedProperty().addListener((obs, o, n) -> refresh());
        showInactiveBox.selectedProperty().addListener((obs, o, n) -> refresh());

        refresh();
    }

    private void setupTable() {
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No schedules match the current filters."));

        titleCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().title()));
        targetCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(targetLabel(c.getValue())));
        freqCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.pretty(c.getValue().frequency())));
        nextDueCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.date(c.getValue().nextDueDate())));
        lastDoneCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.date(c.getValue().lastDoneDate())));
        assignedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().assignedName())));

        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(statusLabel(c.getValue())));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                int i = getIndex();
                Schedule s = (empty || i < 0 || i >= getTableView().getItems().size())
                        ? null : getTableView().getItems().get(i);
                if (s == null) { setGraphic(null); setText(null); return; }
                badge.getStyleClass().setAll("badge", statusBadge(s));
                badge.setText(statusLabel(s));
                setGraphic(badge);
                setText(null);
            }
        });

        nextDueCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty) { setText(null); setStyle(""); return; }
                int i = getIndex();
                Schedule s = (i < 0 || i >= getTableView().getItems().size())
                        ? null : getTableView().getItems().get(i);
                setText(value);
                if (s != null && s.active() && s.nextDueDate() != null
                        && !s.nextDueDate().isAfter(LocalDate.now())) {
                    setStyle("-fx-text-fill: #A12B22; -fx-font-weight: bold;");
                } else {
                    setStyle("");
                }
            }
        });

        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button mark = new Button("Mark Done");
            private final Button edit = new Button("Edit");
            private final Button toggle = new Button();
            private final HBox box = new HBox(6);

            {
                box.getStyleClass().add("table-actions");
                mark.getStyleClass().addAll("btn-primary", "btn-table");
                edit.getStyleClass().addAll("btn-ghost", "btn-table");
                toggle.getStyleClass().addAll("btn-ghost", "btn-table");
                mark.setOnAction(e -> { Schedule s = row(); if (s != null) onMarkDone(s); });
                edit.setOnAction(e -> { Schedule s = row(); if (s != null) openForm(s); });
                toggle.setOnAction(e -> { Schedule s = row(); if (s != null) toggleActive(s); });
            }
            private Schedule row() {
                int i = getIndex();
                return (i < 0 || i >= getTableView().getItems().size()) ? null : getTableView().getItems().get(i);
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                Schedule s = empty ? null : row();
                if (s == null) { setGraphic(null); return; }
                box.getChildren().clear();
                if (canMarkDone && s.active()) box.getChildren().add(mark);
                if (canManage) {
                    box.getChildren().add(edit);
                    toggle.setText(s.active() ? "Archive" : "Restore");
                    toggle.getStyleClass().removeAll("btn-warn");
                    if (s.active()) toggle.getStyleClass().add("btn-warn");
                    box.getChildren().add(toggle);
                }
                setGraphic(box.getChildren().isEmpty() ? null : box);
            }
        });
    }

    @FXML
    private void onAdd() { openForm(null); }

    @FXML
    private void onGenerateWorkOrders() {
        if (!AlertUtil.confirm(ownerWindow(), "Generate preventive work orders?",
                "Create maintenance requests for all active schedules that are due and do not already have an open linked work order?")) {
            return;
        }
        generateButton.setDisable(true);
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                return scheduleService.generateDueWorkOrders(Session.get());
            }
        };
        task.setOnSucceeded(e -> {
            generateButton.setDisable(false);
            int count = task.getValue() == null ? 0 : task.getValue();
            AlertUtil.info(ownerWindow(), "Work orders generated",
                    count == 0 ? "No due schedules needed a new work order."
                            : count + (count == 1 ? " work order was created." : " work orders were created."));
            refresh();
        });
        task.setOnFailed(e -> {
            generateButton.setDisable(false);
            Throwable ex = task.getException();
            if (ex instanceof ValidationException ve) {
                AlertUtil.warn(ownerWindow(), "Cannot generate work orders", ve.getMessage());
            } else {
                AlertUtil.error(ownerWindow(), "Could not generate work orders",
                        ex == null ? "Unknown error." : ex.getMessage());
            }
        });
        Thread t = new Thread(task, "generate-pm-work-orders");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onPrevMonth() {
        calendarMonth = calendarMonth.minusMonths(1);
        renderCalendar();
    }

    @FXML
    private void onNextMonth() {
        calendarMonth = calendarMonth.plusMonths(1);
        renderCalendar();
    }

    @FXML
    private void onTodayMonth() {
        calendarMonth = YearMonth.now();
        renderCalendar();
    }

    private void refresh() {
        final int req = ++generation;
        String keyword = searchField.getText();
        boolean includeInactive = showInactiveBox.isSelected();
        boolean dueOnly = dueOnlyBox.isSelected();

        Task<List<Schedule>> task = new Task<>() {
            @Override protected List<Schedule> call() throws Exception {
                return scheduleDao.search(keyword, includeInactive, dueOnly);
            }
        };
        task.setOnSucceeded(e -> {
            if (req != generation) return;
            rows.setAll(task.getValue());
            countLabel.setText(rows.size() + (rows.size() == 1 ? " schedule" : " schedules"));
            renderCalendar();
        });
        task.setOnFailed(e -> {
            if (req != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not load schedules",
                    ex == null ? "Unknown error." : ex.getMessage());
        });
        Thread t = new Thread(task, "load-schedules");
        t.setDaemon(true);
        t.start();
    }

    private void openForm(Schedule existing) {
        try {
            FXMLLoader loader = ViewLoader.loader("ScheduleFormView.fxml");
            Parent form = loader.load();
            ViewLoader.applyAppStyles(form);
            ScheduleFormController controller = loader.getController();
            if (existing != null) controller.setSchedule(existing);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(ownerWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setTitle(existing == null ? "Add Schedule" : "Edit Schedule");
            dialog.setHeaderText(existing == null ? "Create a preventive maintenance schedule."
                    : "Update schedule: " + existing.title());
            dialog.getDialogPane().setContent(form);
            dialog.getDialogPane().getStyleClass().add("form-dialog");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

            Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            ok.setText(existing == null ? "Create Schedule" : "Save Changes");
            ok.getStyleClass().add("btn-primary");
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).getStyleClass().add("btn-ghost");

            ok.addEventFilter(ActionEvent.ACTION, event -> {
                controller.clearError();
                ScheduleDraft draft = controller.draft();
                try {
                    User user = Session.get();
                    if (existing == null) scheduleService.create(draft, user);
                    else scheduleService.update(existing.id(), draft, user);
                } catch (ValidationException ve) {
                    controller.showError(ve.getMessage());
                    event.consume();
                } catch (SQLException se) {
                    AlertUtil.error(ownerWindow(), "Could not save schedule", se.getMessage());
                    event.consume();
                }
            });

            dialog.showAndWait().ifPresent(bt -> { if (bt == ButtonType.OK) refresh(); });
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Could not open form", ex.getMessage());
        }
    }

    private void onMarkDone(Schedule s) {
        LocalDate next = ScheduleService.advance(LocalDate.now(), s.frequency());
        String msg = "Mark \"" + s.title() + "\" as done today?\n\nLast done will be set to "
                + Format.date(LocalDate.now()) + " and next due will advance to " + Format.date(next) + ".";
        if (!AlertUtil.confirm(ownerWindow(), "Mark schedule done?", msg)) return;
        try {
            scheduleService.markDone(s.id(), s.title(), s.frequency(), LocalDate.now(), Session.get());
            refresh();
        } catch (ValidationException ve) {
            AlertUtil.error(ownerWindow(), "Not allowed", ve.getMessage());
        } catch (SQLException se) {
            AlertUtil.error(ownerWindow(), "Could not update schedule", se.getMessage());
        }
    }

    private void toggleActive(Schedule s) {
        boolean archiving = s.active();
        String msg = archiving ? "Archive this schedule? It will stop appearing as due."
                : "Restore this schedule?";
        if (!AlertUtil.confirm(ownerWindow(), archiving ? "Archive schedule?" : "Restore schedule?", msg)) return;
        try {
            scheduleService.setActive(s.id(), s.title(), !s.active(), Session.get());
            refresh();
        } catch (ValidationException ve) {
            AlertUtil.error(ownerWindow(), "Not allowed", ve.getMessage());
        } catch (SQLException se) {
            AlertUtil.error(ownerWindow(), "Could not update schedule", se.getMessage());
        }
    }

    private void renderCalendar() {
        if (calendarGrid == null) return;
        calendarGrid.getChildren().clear();
        calendarGrid.getColumnConstraints().clear();
        calendarGrid.getRowConstraints().clear();

        for (int i = 0; i < 7; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 7.0);
            cc.setHgrow(Priority.ALWAYS);
            calendarGrid.getColumnConstraints().add(cc);
        }

        monthLabel.setText(calendarMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + calendarMonth.getYear());

        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < days.length; i++) {
            Label hdr = new Label(days[i]);
            hdr.getStyleClass().add("calendar-head");
            hdr.setMaxWidth(Double.MAX_VALUE);
            hdr.setAlignment(Pos.CENTER);
            calendarGrid.add(hdr, i, 0);
        }

        Map<LocalDate, List<Schedule>> byDate = rows.stream()
                .filter(s -> s.active() && s.nextDueDate() != null)
                .filter(s -> YearMonth.from(s.nextDueDate()).equals(calendarMonth))
                .sorted(Comparator.comparing(Schedule::nextDueDate).thenComparing(Schedule::title))
                .collect(Collectors.groupingBy(Schedule::nextDueDate));

        LocalDate first = calendarMonth.atDay(1);
        int shift = (first.getDayOfWeek().getValue() + 6) % 7; // Monday=0
        LocalDate cursor = first.minusDays(shift);
        LocalDate today = LocalDate.now();

        int row = 1;
        for (int week = 0; week < 6; week++) {
            for (int col = 0; col < 7; col++) {
                LocalDate date = cursor.plusDays((long) week * 7 + col);
                VBox cell = new VBox(4);
                cell.getStyleClass().add("calendar-cell");
                cell.setMinHeight(92);
                cell.setMaxWidth(Double.MAX_VALUE);
                GridPane.setHgrow(cell, Priority.ALWAYS);
                GridPane.setVgrow(cell, Priority.ALWAYS);

                boolean inMonth = YearMonth.from(date).equals(calendarMonth);
                if (!inMonth) cell.getStyleClass().add("calendar-cell-out");
                if (date.equals(today)) cell.getStyleClass().add("calendar-cell-today");

                Label day = new Label(String.valueOf(date.getDayOfMonth()));
                day.getStyleClass().add("calendar-day");
                cell.getChildren().add(day);

                List<Schedule> items = byDate.getOrDefault(date, List.of());
                int shown = 0;
                for (Schedule s : items) {
                    if (shown >= 3) break;
                    Label item = new Label("• " + trim(s.title(), 28));
                    item.getStyleClass().add("calendar-item");
                    if (!s.nextDueDate().isAfter(today)) {
                        item.getStyleClass().add("calendar-item-due");
                    }
                    item.setWrapText(true);
                    cell.getChildren().add(item);
                    shown++;
                }
                if (items.size() > shown) {
                    Label more = new Label("+" + (items.size() - shown) + " more");
                    more.getStyleClass().add("calendar-more");
                    cell.getChildren().add(more);
                }
                calendarGrid.add(cell, col, row + week);
            }
        }

        long dueCount = rows.stream()
                .filter(Schedule::active)
                .filter(s -> s.nextDueDate() != null && !s.nextDueDate().isAfter(today))
                .count();
        calendarLegendLabel.setText(dueCount == 0
                ? "No due or overdue schedules right now."
                : dueCount + " schedule(s) are due or overdue. Use Generate Work Orders to create requests.");
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }

    private static String targetLabel(Schedule s) {
        String f = s.facilityName();
        String e = s.equipmentName();
        if (f != null && e != null) return f + " · " + e;
        if (f != null) return f;
        if (e != null) return e;
        return "—";
    }

    private static String statusLabel(Schedule s) {
        if (!s.active()) return "Inactive";
        if (s.nextDueDate() != null && !s.nextDueDate().isAfter(LocalDate.now())) return "Due";
        return "Active";
    }

    private static String statusBadge(Schedule s) {
        if (!s.active()) return "badge-cancelled";
        if (s.nextDueDate() != null && !s.nextDueDate().isAfter(LocalDate.now())) return "badge-overdue";
        return "badge-completed";
    }

    private static String blank(String v) { return v == null || v.isBlank() ? "—" : v; }

    private Window ownerWindow() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }
}
