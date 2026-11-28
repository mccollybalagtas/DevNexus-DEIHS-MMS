package com.deihs.mms.controller;

import com.deihs.mms.dao.DashboardDao;
import com.deihs.mms.model.DashboardData;
import com.deihs.mms.model.DashboardData.MonthPoint;
import com.deihs.mms.model.DashboardData.StatusSlice;
import com.deihs.mms.model.RequestRow;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.service.Session;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.DonutChart;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.RequestCells;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.sql.SQLException;
import java.time.Year;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dashboard. Administrators and the maintenance head see the whole school;
 * maintenance personnel see only the tasks assigned to them.
 */
public class DashboardController {

    @FXML private Label kpiPending;
    @FXML private Label kpiPendingSub;
    @FXML private Label kpiInProgress;
    @FXML private Label kpiInProgressSub;
    @FXML private Label kpiOverdue;
    @FXML private Label kpiOverdueSub;
    @FXML private Label kpiCompleted;
    @FXML private Label kpiCompletedSub;
    @FXML private Label yearLabel;

    @FXML private BarChart<String, Number> monthlyChart;
    @FXML private CategoryAxis monthAxis;
    @FXML private NumberAxis countAxis;

    @FXML private HBox donutRow;
    @FXML private VBox legendBox;

    @FXML private TableView<RequestRow> recentTable;
    @FXML private TableColumn<RequestRow, String> colTicket;
    @FXML private TableColumn<RequestRow, String> colRequester;
    @FXML private TableColumn<RequestRow, String> colAsset;
    @FXML private TableColumn<RequestRow, String> colIssue;
    @FXML private TableColumn<RequestRow, String> colPriority;
    @FXML private TableColumn<RequestRow, String> colStatus;
    @FXML private TableColumn<RequestRow, String> colDate;

    private Consumer<String> navigator = key -> {
    };
    private java.util.function.BiConsumer<String, String> requestNavigator = (status, priority) -> navigator.accept("requests");
    private boolean scoped;

    @FXML
    private void initialize() {
        User user = Session.get();
        scoped = user.role() == Role.PERSONNEL;
        Integer scope = scoped ? user.id() : null;

        yearLabel.setText(String.valueOf(Year.now().getValue()));
        countAxis.setTickLabelsVisible(false);
        countAxis.setTickMarkVisible(false);
        countAxis.setMinorTickVisible(false);
        countAxis.setForceZeroInRange(true);

        configureTable();
        loadAsync(scope);
    }

    /** Loads dashboard metrics off the FX thread so the window never freezes. */
    private void loadAsync(Integer scope) {
        Task<DashboardData> task = new Task<>() {
            @Override
            protected DashboardData call() throws Exception {
                return new DashboardDao().load(scope);
            }
        };
        task.setOnSucceeded(e -> apply(task.getValue()));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            if (ex instanceof SQLException sql) {
                showDatabaseError(sql);
            } else {
                showDatabaseError(new SQLException(
                        ex == null ? "Unknown error" : ex.getMessage(), ex));
            }
        });
        Thread worker = new Thread(task, "deihs-dashboard");
        worker.setDaemon(true);
        worker.start();
    }

    public void setNavigator(Consumer<String> navigator) {
        this.navigator = navigator == null ? key -> {
        } : navigator;
    }

    public void setRequestNavigator(java.util.function.BiConsumer<String, String> requestNavigator) {
        this.requestNavigator = requestNavigator == null ? (s, p) -> navigator.accept("requests") : requestNavigator;
    }

    // ------------------------------------------------------------------
    // Population
    // ------------------------------------------------------------------

    private void configureTable() {
        recentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        recentTable.setPlaceholder(new Label("No requests yet."));

        colTicket.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().ticketNo()));
        colTicket.setCellFactory(RequestCells.ticket());

        colRequester.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().requester()));
        colAsset.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().asset()));
        colIssue.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().title()));

        colPriority.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().priority()));
        colPriority.setCellFactory(RequestCells.priority());

        colStatus.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().status()));
        colStatus.setCellFactory(RequestCells.status());

        colDate.setCellValueFactory(c ->
                new ReadOnlyStringWrapper(Format.relative(c.getValue().reportedAt())));
    }

    private void apply(DashboardData data) {
        kpiPending.setText(String.valueOf(data.pending()));
        kpiPendingSub.setText(data.pendingNewToday() > 0
                ? data.pendingNewToday() + " new today"
                : "Nothing new today");

        kpiInProgress.setText(String.valueOf(data.inProgress()));
        kpiInProgressSub.setText(data.inProgressDueThisWeek() > 0
                ? data.inProgressDueThisWeek() + " due this week"
                : "No deadlines this week");

        kpiOverdue.setText(String.valueOf(data.overdue()));
        kpiOverdueSub.setText(data.overdue() == 0 ? "All on schedule" : "Needs attention");

        kpiCompleted.setText(String.valueOf(data.completedThisMonth()));
        kpiCompletedSub.setText(scoped ? "Completed by me" : "This month");

        applyMonthlyChart(data.monthly());
        applyDonut(data.slices(), data.total());
        recentTable.getItems().setAll(data.recent());
    }

    private void applyMonthlyChart(List<MonthPoint> points) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (int i = 0; i < points.size(); i++) {
            MonthPoint point = points.get(i);
            XYChart.Data<String, Number> data = new XYChart.Data<>(point.label(), point.count());
            if (i == points.size() - 1) {
                // Highlight the current month in gold, like the approved design.
                data.nodeProperty().addListener((obs, old, node) -> {
                    if (node != null) {
                        node.setStyle("-fx-bar-fill: #F5C518;");
                    }
                });
            }
            series.getData().add(data);
        }
        monthlyChart.getData().setAll(series);
        monthlyChart.setBarGap(6);
        monthlyChart.setCategoryGap(20);
        monthAxis.setAnimated(false);
    }

    private void applyDonut(List<StatusSlice> slices, int total) {
        donutRow.getChildren().removeIf(node -> node instanceof DonutChart);

        DonutChart donut = new DonutChart(136);
        donut.setData(slices, total);
        donutRow.getChildren().add(0, donut);

        legendBox.getChildren().clear();
        for (StatusSlice slice : slices) {
            if (slice.count() == 0) {
                continue;
            }
            Region dot = new Region();
            dot.getStyleClass().add("legend-dot");
            dot.setStyle("-fx-background-color: " + slice.color() + ";");

            Label label = new Label(slice.label() + " · " + slice.count());
            label.getStyleClass().add("legend-label");

            HBox row = new HBox(8, dot, label);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setStyle("-fx-cursor: hand;");
            row.setOnMouseClicked(e -> {
                String status = switch (slice.label()) {
                    case "Pending" -> "PENDING";
                    case "In Progress" -> "IN_PROGRESS";
                    case "Completed" -> "COMPLETED";
                    case "On Hold / Other" -> "ON_HOLD";
                    default -> null;
                };
                if (status != null) requestNavigator.accept(status, null);
            });
            legendBox.getChildren().add(row);
        }
        if (legendBox.getChildren().isEmpty()) {
            Label empty = new Label("No requests recorded yet.");
            empty.getStyleClass().add("legend-label");
            legendBox.getChildren().add(empty);
        }
    }

    private void showDatabaseError(SQLException e) {
        kpiPending.setText("—");
        kpiInProgress.setText("—");
        kpiOverdue.setText("—");
        kpiCompleted.setText("—");
        recentTable.setPlaceholder(new Label("Dashboard data is unavailable."));

        Platform.runLater(() -> AlertUtil.error(
                recentTable.getScene() == null ? null : recentTable.getScene().getWindow(),
                "Database unavailable",
                "Could not load the dashboard. Make sure MySQL is running in XAMPP "
                        + "and the server address is correct.",
                e));
    }

    @FXML
    private void onViewAllRequests() {
        navigator.accept(Session.get().role() == Role.PERSONNEL ? "tasks" : "requests");
    }

    @FXML
    private void onPendingClick() {
        requestNavigator.accept("PENDING", null);
    }

    @FXML
    private void onProgressClick() {
        requestNavigator.accept("IN_PROGRESS", null);
    }

    @FXML
    private void onOverdueClick() {
        requestNavigator.accept("OVERDUE", null);
    }

    @FXML
    private void onCompletedClick() {
        requestNavigator.accept("COMPLETED", null);
    }
}
