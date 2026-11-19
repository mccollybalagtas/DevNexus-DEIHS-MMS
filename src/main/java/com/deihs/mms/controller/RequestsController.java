package com.deihs.mms.controller;

import com.deihs.mms.dao.RequestDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.CreatedRequest;
import com.deihs.mms.model.RequestRow;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.RequestService;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.RequestCells;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The request list. One controller serves all three audiences; the scope
 * decides which rows are fetched and which columns make sense.
 *
 * <ul>
 *   <li>{@link Scope#ALL} - administrators and the maintenance head see every
 *       request in the school.</li>
 *   <li>{@link Scope#MY_TASKS} - maintenance personnel see only the work
 *       assigned to them, ordered by target date.</li>
 *   <li>{@link Scope#MY_REQUESTS} - teachers and staff see only what they
 *       filed themselves.</li>
 * </ul>
 */
public class RequestsController {

    public enum Scope { ALL, MY_TASKS, MY_REQUESTS }

    @FXML private Label heading;
    @FXML private Label subheading;
    @FXML private Label countLabel;
    @FXML private Button newButton;
    @FXML private TextField searchField;
    @FXML private ComboBox<Choice> statusFilter;
    @FXML private ComboBox<Choice> priorityFilter;
    @FXML private TableView<RequestRow> table;
    @FXML private TableColumn<RequestRow, String> colTicket;
    @FXML private TableColumn<RequestRow, String> colRequester;
    @FXML private TableColumn<RequestRow, String> colAsset;
    @FXML private TableColumn<RequestRow, String> colIssue;
    @FXML private TableColumn<RequestRow, String> colPriority;
    @FXML private TableColumn<RequestRow, String> colStatus;
    @FXML private TableColumn<RequestRow, String> colAssignee;
    @FXML private TableColumn<RequestRow, String> colDue;
    @FXML private TableColumn<RequestRow, String> colDate;

    private final RequestDao requestDao = new RequestDao();
    private final RequestService requestService = new RequestService();
    private final ObservableList<RequestRow> rows = FXCollections.observableArrayList();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));

    private User user;
    private Scope scope;
    private IntConsumer openRequest;
    /** Increments on every load so a slow reply cannot overwrite a newer one. */
    private int generation;

    @FXML
    private void initialize() {
        user = Session.get();
        scope = scopeFor(user.role());

        applyScope();
        buildFilters();
        buildColumns();

        table.setItems(rows);
        table.setPlaceholder(new Label(emptyText()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setRowFactory(view -> openableRow());

        debounce.setOnFinished(e -> load());
        searchField.textProperty().addListener((o, old, val) -> restartDebounce());
        statusFilter.valueProperty().addListener((o, old, val) -> restartDebounce());
        priorityFilter.valueProperty().addListener((o, old, val) -> restartDebounce());

        load();
    }

    // ------------------------------------------------------------------
    // Scope
    // ------------------------------------------------------------------

    private static Scope scopeFor(Role role) {
        return switch (role) {
            case PERSONNEL -> Scope.MY_TASKS;
            case STAFF -> Scope.MY_REQUESTS;
            default -> Scope.ALL;
        };
    }

    private void applyScope() {
        switch (scope) {
            case ALL -> {
                heading.setText("Maintenance Requests");
                subheading.setText("Every request filed across the school, newest first.");
                table.getColumns().remove(colDue);
            }
            case MY_TASKS -> {
                heading.setText("My Tasks");
                subheading.setText("Work assigned to you, soonest target date first.");
                table.getColumns().remove(colAssignee);
            }
            case MY_REQUESTS -> {
                heading.setText("My Requests");
                subheading.setText("Requests you have filed and their current progress.");
                table.getColumns().remove(colRequester);
            }
        }

        boolean canFile = Permissions.canCreateRequest(user);
        newButton.setVisible(canFile);
        newButton.setManaged(canFile);
    }

    private String emptyText() {
        return switch (scope) {
            case MY_TASKS -> "Nothing assigned to you right now.";
            case MY_REQUESTS -> "You have not filed any requests yet.";
            default -> "No requests match these filters.";
        };
    }

    // ------------------------------------------------------------------
    // Filters and columns
    // ------------------------------------------------------------------

    private void buildFilters() {
        statusFilter.getItems().add(new Choice(null, "All statuses"));
        for (String s : List.of("PENDING", "ASSIGNED", "IN_PROGRESS", "ON_HOLD",
                "COMPLETED", "CLOSED", "REJECTED", "CANCELLED")) {
            statusFilter.getItems().add(new Choice(s, Format.pretty(s)));
        }
        statusFilter.getSelectionModel().selectFirst();

        priorityFilter.getItems().add(new Choice(null, "All priorities"));
        for (String p : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) {
            priorityFilter.getItems().add(new Choice(p, Format.pretty(p)));
        }
        priorityFilter.getSelectionModel().selectFirst();
    }

    private void buildColumns() {
        colTicket.setCellValueFactory(c -> text(c.getValue().ticketNo()));
        colTicket.setCellFactory(RequestCells.ticket());

        colRequester.setCellValueFactory(c -> text(c.getValue().requester()));
        colAsset.setCellValueFactory(c -> text(c.getValue().asset()));
        colIssue.setCellValueFactory(c -> text(c.getValue().title()));

        colPriority.setCellValueFactory(c -> text(c.getValue().priority()));
        colPriority.setCellFactory(RequestCells.priority());

        colStatus.setCellValueFactory(c -> text(c.getValue().status()));
        colStatus.setCellFactory(RequestCells.status());

        colAssignee.setCellValueFactory(c -> text(
                c.getValue().assignee() == null ? "Unassigned" : c.getValue().assignee()));

        colDue.setCellValueFactory(c -> text(Format.date(c.getValue().targetDate())));
        colDue.setCellFactory(RequestCells.due());

        colDate.setCellValueFactory(c -> text(c.getValue().reportedAt() == null
                ? "—" : Format.date(c.getValue().reportedAt().toLocalDate())));
    }

    private static ReadOnlyStringWrapper text(String value) {
        return new ReadOnlyStringWrapper(value);
    }

    private TableRow<RequestRow> openableRow() {
        TableRow<RequestRow> row = new TableRow<>();
        row.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && e.getClickCount() == 2 && !row.isEmpty()
                    && openRequest != null) {
                openRequest.accept(row.getItem().id());
            }
        });
        return row;
    }

    /** Called by the shell once the detail screen exists. */
    public void setOpenRequestHandler(IntConsumer handler) {
        this.openRequest = handler;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void restartDebounce() {
        debounce.playFromStart();
    }

    private void load() {
        String keyword = searchField.getText();
        String status = selected(statusFilter);
        String priority = selected(priorityFilter);

        Integer assignedTo = scope == Scope.MY_TASKS ? user.id() : null;
        Integer requesterId = scope == Scope.MY_REQUESTS ? user.id() : null;
        boolean byDue = scope == Scope.MY_TASKS;

        int mine = ++generation;
        countLabel.setText("Loading…");

        Task<List<RequestRow>> task = new Task<>() {
            @Override
            protected List<RequestRow> call() throws Exception {
                return requestDao.search(keyword, status, priority,
                        assignedTo, requesterId, byDue);
            }
        };

        task.setOnSucceeded(e -> {
            if (mine != generation) {
                return;
            }
            rows.setAll(task.getValue());
            updateCount();
        });

        task.setOnFailed(e -> {
            if (mine != generation) {
                return;
            }
            rows.clear();
            countLabel.setText("");
            AlertUtil.error(window(), "Could not load requests",
                    "The request list could not be read from the database.",
                    task.getException());
        });

        Thread thread = new Thread(task, "requests-load");
        thread.setDaemon(true);
        thread.start();
    }

    private void updateCount() {
        String noun = scope == Scope.MY_TASKS ? "task" : "request";
        countLabel.setText(rows.size() + " " + noun + (rows.size() == 1 ? "" : "s"));
    }

    private static String selected(ComboBox<Choice> box) {
        Choice choice = box.getValue();
        return choice == null ? null : choice.value();
    }

    // ------------------------------------------------------------------
    // New request
    // ------------------------------------------------------------------

    @FXML
    private void onNewRequest() {
        FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/RequestFormView.fxml");
        Parent form;
        try {
            form = loader.load();
        } catch (IOException | RuntimeException e) {
            AlertUtil.error(window(), "Could not open the form",
                    "The request form could not be built.", e);
            return;
        }
        RequestFormController controller = loader.getController();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle("New Maintenance Request");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setPrefWidth(600);
        ViewLoader.applyAppStyles(dialog.getDialogPane());

        ButtonType submit = new ButtonType("Submit Request", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL);
        dialog.getDialogPane().getStyleClass().add("form-dialog");

        Node submitNode = dialog.getDialogPane().lookupButton(submit);
        submitNode.getStyleClass().add("btn-primary");

        CreatedRequest[] created = new CreatedRequest[1];
        submitNode.addEventFilter(ActionEvent.ACTION, event -> {
            dialog.getDialogPane().setCursor(Cursor.WAIT);
            try {
                created[0] = requestService.submit(controller.draft(), user);
            } catch (ValidationException e) {
                controller.showError(e.getMessage());
                event.consume();
            } catch (SQLException e) {
                controller.showError("The request could not be saved. Please try again.");
                AlertUtil.error(window(), "Could not save the request",
                        "The database rejected the new request.", e);
                event.consume();
            } finally {
                dialog.getDialogPane().setCursor(Cursor.DEFAULT);
            }
        });

        dialog.showAndWait();

        if (created[0] != null) {
            AlertUtil.info(window(), "Request submitted",
                    "Your request was filed as " + created[0].ticketNo()
                            + ".\n\nQuote this ticket number when following up.");
            load();
        }
    }

    // ------------------------------------------------------------------

    private javafx.stage.Window window() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }
}
