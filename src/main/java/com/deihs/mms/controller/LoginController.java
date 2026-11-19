package com.deihs.mms.controller;

import com.deihs.mms.Main;
import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.RegistrationDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.service.AuthException;
import com.deihs.mms.service.AuthService;
import com.deihs.mms.service.GoogleOAuthService;
import com.deihs.mms.service.GoogleOAuthService.GoogleProfile;
import com.deihs.mms.service.RegistrationService;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.ViewLoader;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Modality;

import java.io.IOException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;

/**
 * Sign-in screen. The role always comes from the database — the user never
 * chooses it here.
 */
public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordVisible;
    @FXML private CheckBox showPassword;
    @FXML private Label errorLabel;
    @FXML private Button signInButton;
    @FXML private Button googleButton;

    private final AuthService authService = new AuthService();
    private final GoogleOAuthService googleOAuthService = new GoogleOAuthService();
    private final UserDao userDao = new UserDao();
    private final AuditDao auditDao = new AuditDao();
    private final RegistrationService registrationService = new RegistrationService();

    @FXML
    private void initialize() {
        usernameField.setOnAction(e -> passwordField.requestFocus());
        passwordField.setOnAction(e -> onSignIn());
        passwordVisible.setOnAction(e -> onSignIn());
        if (googleButton != null) {
            boolean configured = googleOAuthService.isConfigured();
            googleButton.setVisible(configured);
            googleButton.setManaged(configured);
        }
        Platform.runLater(usernameField::requestFocus);
    }

    @FXML
    private void onCreateAccount() {
        try {
            FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/RegisterView.fxml");
            Parent register = loader.load();
            Main.setRoot(register);
        } catch (IOException | RuntimeException e) {
            showError("Could not open registration: " + e.getMessage());
        }
    }

    @FXML
    private void onTogglePassword() {
        if (showPassword.isSelected()) {
            passwordVisible.setText(passwordField.getText());
            passwordVisible.setVisible(true);
            passwordVisible.setManaged(true);
            passwordField.setVisible(false);
            passwordField.setManaged(false);
            passwordVisible.requestFocus();
            passwordVisible.positionCaret(passwordVisible.getText().length());
        } else {
            passwordField.setText(passwordVisible.getText());
            passwordField.setVisible(true);
            passwordField.setManaged(true);
            passwordVisible.setVisible(false);
            passwordVisible.setManaged(false);
            passwordField.requestFocus();
            passwordField.positionCaret(passwordField.getText().length());
        }
    }

    @FXML
    private void onSignIn() {
        hideError();

        String username = usernameField.getText() == null ? "" : usernameField.getText().trim();
        String password = currentPassword();

        if (username.isEmpty() || password.isEmpty()) {
            showError("Enter your username and password.");
            return;
        }

        setBusy(true, "Signing in…");

        Task<User> task = new Task<>() {
            @Override
            protected User call() throws Exception {
                return authService.login(username, password);
            }
        };

        task.setOnSucceeded(e -> {
            User user = task.getValue();
            Session.set(user);
            openShell();
        });

        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable ex = task.getException();
            if (ex instanceof AuthException auth) {
                showError(auth.getMessage());
                passwordField.clear();
                passwordVisible.clear();
                passwordField.requestFocus();
            } else if (ex instanceof SQLException) {
                showError("Cannot reach the database. Make sure MySQL is running in XAMPP "
                        + "and the server address is correct.");
            } else {
                showError("Sign-in failed: " + (ex == null ? "unknown error" : ex.getMessage()));
            }
        });

        Thread worker = new Thread(task, "deihs-login");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onGoogleSignIn() {
        hideError();
        if (!googleOAuthService.isConfigured()) {
            showError("Google sign-in is not configured. Set google.clientId in db.properties.");
            return;
        }

        setBusy(true, "Opening Google…");
        Task<GoogleProfile> task = new Task<>() {
            @Override
            protected GoogleProfile call() throws Exception {
                return googleOAuthService.signIn();
            }
        };

        task.setOnSucceeded(e -> {
            GoogleProfile profile = task.getValue();
            handleGoogleProfile(profile);
        });

        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable ex = task.getException();
            String msg;
            if (ex instanceof AuthException ae) {
                msg = ae.getMessage();
            } else if (ex instanceof SQLException) {
                msg = "Cannot reach the database. Please try again.";
            } else {
                msg = "Google sign-in failed: " + (ex == null ? "unknown error" : ex.getMessage());
            }
            showError(msg);
        });

        Thread t = new Thread(task, "google-signin");
        t.setDaemon(true);
        t.start();
    }

    private void handleGoogleProfile(GoogleProfile profile) {
        try {
            if (profile.email() == null || profile.email().isBlank()) {
                setBusy(false, null);
                showError("Google account did not provide an email address. Use username/password sign-in.");
                return;
            }
            if (!profile.emailVerified()) {
                setBusy(false, null);
                showError("Your Google email address is not verified. Verify it in Google, then try again.");
                return;
            }

            // Match existing users by verified email.
            Optional<User> active = userDao.findActiveByEmail(profile.email());
            if (active.isPresent()) {
                User user = active.get();
                userDao.touchLastLogin(user.id());
                auditDao.log(user.id(), "LOGIN", "USER", String.valueOf(user.id()), "Signed in with Google");
                Session.set(user);
                openShell();
                return;
            }

            Optional<User> any = userDao.findByEmail(profile.email());
            if (any.isPresent()) {
                setBusy(false, null);
                showError("An account exists for this Google email but it is inactive. Contact the administrator.");
                return;
            }

            // Option A: no account -> collect required fields and submit pending request.
            setBusy(false, null);
            promptGoogleRegistration(profile);
        } catch (Exception ex) {
            setBusy(false, null);
            showError("Google sign-in failed: " + ex.getMessage());
        }
    }

    private void promptGoogleRegistration(GoogleProfile profile) {
        Dialog<RegistrationDraft> dialog = new Dialog<>();
        dialog.initOwner(signInButton.getScene() == null ? null : signInButton.getScene().getWindow());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Complete Account Request");
        dialog.setHeaderText("No account found for " + profile.email()
                + ". Submit a request for administrator approval.");

        TextField empField = new TextField();
        empField.setPromptText("e.g. EMP-0100");
        TextField userField = new TextField();
        userField.setText(suggestUsername(profile.email()));
        TextField firstField = new TextField(profile.givenName() == null ? "" : profile.givenName());
        TextField lastField = new TextField(profile.familyName() == null ? "" : profile.familyName());
        TextField deptField = new TextField();
        deptField.setPromptText("e.g. Science, Maintenance");
        ComboBox<Choice> roleBox = new ComboBox<>();
        roleBox.getItems().addAll(
                new Choice("STAFF", "Teacher / School Staff"),
                new Choice("PERSONNEL", "Maintenance Personnel"),
                new Choice("MAINT_HEAD", "Maintenance Head")
        );
        roleBox.getSelectionModel().selectFirst();

        Label err = new Label();
        err.setVisible(false); err.setManaged(false); err.setWrapText(true);
        err.setStyle("-fx-text-fill: #8B1E1E;");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(8, 0, 0, 0));
        int r = 0;
        grid.add(new Label("Google email"), 0, r); grid.add(new Label(profile.email()), 1, r++);
        grid.add(new Label("Employee / ID number"), 0, r); grid.add(empField, 1, r++);
        grid.add(new Label("Username"), 0, r); grid.add(userField, 1, r++);
        grid.add(new Label("First name"), 0, r); grid.add(firstField, 1, r++);
        grid.add(new Label("Last name"), 0, r); grid.add(lastField, 1, r++);
        grid.add(new Label("Department / Office"), 0, r); grid.add(deptField, 1, r++);
        grid.add(new Label("Requested role"), 0, r); grid.add(roleBox, 1, r++);
        grid.add(err, 0, r, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Submit Request");

        ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            err.setVisible(false); err.setManaged(false);
            String randomPw = randomPassword();
            RegistrationDraft draft = new RegistrationDraft(
                    empField.getText(),
                    userField.getText(),
                    randomPw,
                    randomPw,
                    firstField.getText(),
                    lastField.getText(),
                    profile.email(),
                    null,
                    deptField.getText(),
                    roleBox.getValue() == null ? null : roleBox.getValue().value()
            );
            try {
                registrationService.submit(draft);
            } catch (ValidationException ve) {
                err.setText(ve.getMessage());
                err.setVisible(true); err.setManaged(true);
                event.consume();
            } catch (SQLException se) {
                err.setText("Could not submit request: " + se.getMessage());
                err.setVisible(true); err.setManaged(true);
                event.consume();
            }
        });

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? new RegistrationDraft("", "", "", "", "", "", "", "", "", "") : null);
        dialog.showAndWait().ifPresent(d -> {
            showSuccess("Account request submitted for " + profile.email()
                    + ". You can sign in with Google once an administrator approves it.");
        });
    }

    private void showSuccess(String message) {
        errorLabel.setStyle("-fx-text-fill: #0E6B34; -fx-font-weight: bold;");
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private static String suggestUsername(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        local = local.replaceAll("[^a-zA-Z0-9._-]", "").toLowerCase();
        if (local.length() < 3) local = (local + "user").substring(0, Math.min(8, (local + "user").length()));
        if (local.length() > 50) local = local.substring(0, 50);
        return local;
    }

    private static String randomPassword() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void setBusy(boolean busy, String signInText) {
        signInButton.setDisable(busy);
        if (googleButton != null) googleButton.setDisable(busy);
        signInButton.setText(busy ? (signInText == null ? "Please wait…" : signInText) : "Sign In");
        if (busy && "Opening Google…".equals(signInText) && googleButton != null) {
            googleButton.setText("Waiting for Google…");
        } else if (!busy && googleButton != null) {
            googleButton.setText("Continue with Google");
        }
    }

    private String currentPassword() {
        return showPassword.isSelected() ? passwordVisible.getText() : passwordField.getText();
    }

    private void openShell() {
        try {
            FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/ShellView.fxml");
            Parent shell = loader.load();
            Main.setRoot(shell);
        } catch (IOException | RuntimeException e) {
            Session.clear();
            resetButton();
            showError("Could not open the main window: " + e.getMessage());
        }
    }

    private void resetButton() {
        setBusy(false, null);
    }

    private void showError(String message) {
        errorLabel.setStyle(null);
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setStyle(null);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setText("");
    }
}
