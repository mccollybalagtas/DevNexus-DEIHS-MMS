package com.deihs.mms.controller;

import com.deihs.mms.dao.AccountRequestDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.AccountRequest;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.User;
import com.deihs.mms.model.UserDraft;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.UserService;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

public class UsersController {

    @FXML private Button addButton;
    @FXML private VBox requestsCard;
    @FXML private Label pendingCountLabel;
    @FXML private TableView<AccountRequest> requestsTable;
    @FXML private TableColumn<AccountRequest, String> reqEmpCol;
    @FXML private TableColumn<AccountRequest, String> reqUsernameCol;
    @FXML private TableColumn<AccountRequest, String> reqNameCol;
    @FXML private TableColumn<AccountRequest, String> reqDeptCol;
    @FXML private TableColumn<AccountRequest, String> reqRoleCol;
    @FXML private TableColumn<AccountRequest, String> reqDateCol;
    @FXML private TableColumn<AccountRequest, Void> reqActionsCol;

    @FXML private TextField searchField;
    @FXML private CheckBox showInactiveBox;
    @FXML private Label userCountLabel;
    @FXML private TableView<User> usersTable;
    @FXML private TableColumn<User, String> empCol;
    @FXML private TableColumn<User, String> usernameCol;
    @FXML private TableColumn<User, String> nameCol;
    @FXML private TableColumn<User, String> roleCol;
    @FXML private TableColumn<User, String> deptCol;
    @FXML private TableColumn<User, String> statusCol;
    @FXML private TableColumn<User, String> lastLoginCol;
    @FXML private TableColumn<User, Void> userActionsCol;

    private final UserDao userDao = new UserDao();
    private final AccountRequestDao requestDao = new AccountRequestDao();
    private final UserService userService = new UserService();

    private final ObservableList<User> allUsers = FXCollections.observableArrayList();
    private final FilteredList<User> filteredUsers = new FilteredList<>(allUsers, u -> true);
    private final ObservableList<AccountRequest> pending = FXCollections.observableArrayList();

    private int generation;

    @FXML
    private void initialize() {
        setupRequestsTable();
        setupUsersTable();

        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> applyUserFilter());
        searchField.textProperty().addListener((obs, o, n) -> debounce.playFromStart());
        showInactiveBox.selectedProperty().addListener((obs, o, n) -> applyUserFilter());

        refresh();
    }

    private void setupRequestsTable() {
        requestsTable.setItems(pending);
        requestsTable.setPlaceholder(new Label("No pending account requests."));
        requestsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        reqEmpCol.setCellValueFactory(new PropertyValueFactory<>("employeeNo"));
        reqUsernameCol.setCellValueFactory(new PropertyValueFactory<>("username"));
        reqNameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().fullName()));
        reqDeptCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().department())));
        reqRoleCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(roleLabel(c.getValue().requestedRole())));
        reqDateCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.dateTime(c.getValue().createdAt())));

        reqActionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button approve = new Button("Approve");
            private final Button reject = new Button("Reject");
            private final HBox box = new HBox(6, approve, reject);
            {
                box.getStyleClass().add("table-actions");
                approve.getStyleClass().addAll("btn-primary", "btn-table");
                reject.getStyleClass().addAll("btn-ghost", "btn-table", "btn-warn");
                approve.setOnAction(e -> {
                    AccountRequest r = row();
                    if (r != null) onApprove(r);
                });
                reject.setOnAction(e -> {
                    AccountRequest r = row();
                    if (r != null) onReject(r);
                });
            }
            private AccountRequest row() {
                int i = getIndex();
                return (i < 0 || i >= getTableView().getItems().size()) ? null : getTableView().getItems().get(i);
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : box);
            }
        });
    }

    private void setupUsersTable() {
        usersTable.setItems(filteredUsers);
        usersTable.setPlaceholder(new Label("No users found."));
        usersTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        empCol.setCellValueFactory(new PropertyValueFactory<>("employeeNo"));
        usernameCol.setCellValueFactory(new PropertyValueFactory<>("username"));
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().fullName()));
        roleCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().role().displayName()));
        deptCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().department())));
        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().active() ? "Active" : "Inactive"));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setGraphic(null); return; }
                badge.getStyleClass().setAll("badge", "Active".equals(value) ? "badge-completed" : "badge-cancelled");
                badge.setText(value);
                setGraphic(badge);
                setText(null);
            }
        });
        lastLoginCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.dateTime(c.getValue().lastLogin())));

        userActionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button edit = new Button("Edit");
            private final Button reset = new Button("Reset PW");
            private final Button toggle = new Button();
            private final HBox box = new HBox(6, edit, reset, toggle);
            {
                box.getStyleClass().add("table-actions");
                edit.getStyleClass().addAll("btn-ghost", "btn-table");
                reset.getStyleClass().addAll("btn-ghost", "btn-table");
                toggle.getStyleClass().addAll("btn-ghost", "btn-table");
                edit.setOnAction(e -> { User u = row(); if (u != null) openUserForm(u); });
                reset.setOnAction(e -> { User u = row(); if (u != null) onResetPassword(u); });
                toggle.setOnAction(e -> { User u = row(); if (u != null) onToggleActive(u); });
            }
            private User row() {
                int i = getIndex();
                return (i < 0 || i >= getTableView().getItems().size()) ? null : getTableView().getItems().get(i);
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                User u = empty ? null : row();
                if (u == null) { setGraphic(null); return; }
                toggle.setText(u.active() ? "Deactivate" : "Activate");
                toggle.getStyleClass().removeAll("btn-warn");
                if (u.active()) toggle.getStyleClass().add("btn-warn");
                // avoid deactivating yourself via UI hint still allowed check is in service
                setGraphic(box);
            }
        });
    }

    @FXML
    private void onAddUser() {
        openUserForm(null);
    }

    @FXML
    private void onRefresh() {
        refresh();
    }

    private void refresh() {
        final int req = ++generation;
        Task<Result> task = new Task<>() {
            @Override protected Result call() throws Exception {
                return new Result(userDao.findAll(), requestDao.findPending());
            }
        };
        task.setOnSucceeded(e -> {
            if (req != generation) return;
            Result r = task.getValue();
            allUsers.setAll(r.users);
            pending.setAll(r.requests);
            pendingCountLabel.setText(r.requests.size() + " pending");
            applyUserFilter();
        });
        task.setOnFailed(e -> {
            if (req != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not load users",
                    ex == null ? "Unknown error." : ex.getMessage());
        });
        Thread t = new Thread(task, "load-users");
        t.setDaemon(true);
        t.start();
    }

    private void applyUserFilter() {
        String q = searchField.getText();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        boolean showInactive = showInactiveBox.isSelected();
        filteredUsers.setPredicate(u -> {
            if (!showInactive && !u.active()) return false;
            if (needle.isEmpty()) return true;
            return contains(u.employeeNo(), needle)
                    || contains(u.username(), needle)
                    || contains(u.fullName(), needle)
                    || contains(u.department(), needle)
                    || contains(u.role().displayName(), needle);
        });
        userCountLabel.setText(filteredUsers.size() + " users");
    }

    private void openUserForm(User existing) {
        try {
            FXMLLoader loader = ViewLoader.loader("UserFormView.fxml");
            Parent form = loader.load();
            ViewLoader.applyAppStyles(form);
            UserFormController controller = loader.getController();
            controller.setCreateMode(existing == null);
            if (existing != null) controller.setUser(existing);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(ownerWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setTitle(existing == null ? "Add User" : "Edit User");
            dialog.setHeaderText(existing == null ? "Create a new user account."
                    : "Update " + existing.username() + " (" + existing.fullName() + ").");
            dialog.getDialogPane().setContent(form);
            dialog.getDialogPane().getStyleClass().add("form-dialog");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

            Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            ok.setText(existing == null ? "Create User" : "Save Changes");
            ok.getStyleClass().add("btn-primary");
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).getStyleClass().add("btn-ghost");

            ok.addEventFilter(ActionEvent.ACTION, event -> {
                controller.clearError();
                UserDraft draft = controller.draft();
                try {
                    User actor = Session.get();
                    if (existing == null) userService.create(draft, actor);
                    else userService.update(existing.id(), draft, actor);
                } catch (ValidationException ve) {
                    controller.showError(ve.getMessage());
                    event.consume();
                } catch (SQLException se) {
                    AlertUtil.error(ownerWindow(), "Could not save user", se.getMessage());
                    event.consume();
                }
            });

            dialog.showAndWait().ifPresent(bt -> { if (bt == ButtonType.OK) refresh(); });
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Could not open form", ex.getMessage());
        }
    }

    private void onToggleActive(User u) {
        boolean deactivating = u.active();
        String msg = deactivating
                ? "Deactivate " + u.username() + "? They will no longer be able to sign in."
                : "Activate " + u.username() + "?";
        if (!AlertUtil.confirm(ownerWindow(), deactivating ? "Deactivate user?" : "Activate user?", msg)) return;
        try {
            userService.setActive(u.id(), u.username(), !u.active(), Session.get());
            refresh();
        } catch (ValidationException ve) {
            AlertUtil.error(ownerWindow(), "Not allowed", ve.getMessage());
        } catch (SQLException se) {
            AlertUtil.error(ownerWindow(), "Could not update user", se.getMessage());
        }
    }

    private void onResetPassword(User u) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(ownerWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Reset Password");
        dialog.setHeaderText("Set a new password for " + u.username() + ".");

        PasswordField pw = new PasswordField();
        pw.setPromptText("At least 6 characters");
        pw.getStyleClass().add("input");
        Label err = new Label();
        err.getStyleClass().add("form-error");
        err.setVisible(false); err.setManaged(false); err.setWrapText(true);
        VBox content = new VBox(8, new Label("New password"), pw, err);
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Reset Password");
        ok.getStyleClass().add("btn-primary");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).getStyleClass().add("btn-ghost");

        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                userService.resetPassword(u.id(), u.username(), pw.getText(), Session.get());
            } catch (ValidationException ve) {
                err.setText(ve.getMessage());
                err.setVisible(true); err.setManaged(true);
                event.consume();
            } catch (SQLException se) {
                AlertUtil.error(ownerWindow(), "Could not reset password", se.getMessage());
                event.consume();
            }
        });

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? pw.getText() : null);
        String newPassword = dialog.showAndWait().orElse(null);
        if (newPassword != null) {
            AlertUtil.info(ownerWindow(), "Password reset",
                    "The password for " + u.username() + " has been updated.");
        }
    }

    private void onApprove(AccountRequest r) {
        Dialog<Choice> dialog = new Dialog<>();
        dialog.initOwner(ownerWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Approve Account Request");
        dialog.setHeaderText("Approve " + r.fullName() + " (" + r.username() + ")?");

        ComboBox<Choice> roleBox = new ComboBox<>();
        roleBox.getStyleClass().add("combo-input");
        roleBox.setMaxWidth(Double.MAX_VALUE);
        roleBox.getItems().addAll(
                new Choice("STAFF", "Teacher / School Staff"),
                new Choice("PERSONNEL", "Maintenance Personnel"),
                new Choice("MAINT_HEAD", "Maintenance Head"),
                new Choice("ADMIN", "School Administrator")
        );
        for (Choice c : roleBox.getItems()) {
            if (c.value().equals(r.requestedRole())) { roleBox.setValue(c); break; }
        }
        if (roleBox.getValue() == null) roleBox.getSelectionModel().selectFirst();

        Label info = new Label("Requested: " + roleLabel(r.requestedRole()) + ". Confirm or change the role, then approve to create an active account.");
        info.setWrapText(true);
        info.getStyleClass().add("form-hint");

        VBox content = new VBox(8, info, new Label("Assign role"), roleBox);
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Approve & Create Account");
        ok.getStyleClass().add("btn-primary");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).getStyleClass().add("btn-ghost");

        ok.addEventFilter(ActionEvent.ACTION, event -> {
            Choice chosen = roleBox.getValue();
            if (chosen == null) { event.consume(); return; }
            try {
                userService.approveRequest(r.id(), chosen.value(), Session.get());
            } catch (ValidationException ve) {
                AlertUtil.error(ownerWindow(), "Cannot approve", ve.getMessage());
                event.consume();
            } catch (SQLException se) {
                AlertUtil.error(ownerWindow(), "Could not approve request", se.getMessage());
                event.consume();
            }
        });

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? roleBox.getValue() : null);
        dialog.showAndWait().ifPresent(ch -> refresh());
    }

    private void onReject(AccountRequest r) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(ownerWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Reject Account Request");
        dialog.setHeaderText("Reject request from " + r.fullName() + " (" + r.username() + ")?");

        TextField notes = new TextField();
        notes.setPromptText("Reason (optional)");
        notes.getStyleClass().add("input");
        VBox content = new VBox(8, new Label("Rejection note (optional)"), notes);
        content.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Reject Request");
        ok.getStyleClass().addAll("btn-primary");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).getStyleClass().add("btn-ghost");

        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                userService.rejectRequest(r.id(), notes.getText(), Session.get());
            } catch (ValidationException ve) {
                AlertUtil.error(ownerWindow(), "Cannot reject", ve.getMessage());
                event.consume();
            } catch (SQLException se) {
                AlertUtil.error(ownerWindow(), "Could not reject request", se.getMessage());
                event.consume();
            }
        });

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? notes.getText() : null);
        dialog.showAndWait().ifPresent(n -> refresh());
    }

    private static String roleLabel(String code) {
        if (code == null) return "—";
        try { return com.deihs.mms.model.Role.fromCode(code).displayName(); }
        catch (Exception e) { return code; }
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Window ownerWindow() {
        return usersTable.getScene() == null ? null : usersTable.getScene().getWindow();
    }

    private record Result(List<User> users, List<AccountRequest> requests) {}
}
