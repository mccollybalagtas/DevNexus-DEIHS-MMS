package com.deihs.mms.controller;

import com.deihs.mms.Main;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.RegistrationDraft;
import com.deihs.mms.service.RegistrationService;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.EmailVerifier;
import com.deihs.mms.util.Lockout;
import com.deihs.mms.util.PasswordPolicy;
import com.deihs.mms.util.SmtpMailer;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

import java.sql.SQLException;
import java.util.List;

/**
 * Public account-request form. Nothing here creates a user directly: a valid
 * submission becomes a pending request that an administrator approves.
 */
public class RegisterController {

    @FXML private Label errorLabel;
    @FXML private TextField employeeNoField;
    @FXML private TextField usernameField;
    @FXML private TextField firstNameField;
    @FXML private TextField lastNameField;
    @FXML private TextField emailField;
    @FXML private TextField codeField;
    @FXML private TextField departmentField;
    @FXML private ComboBox<Choice> roleBox;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Button createButton;
    @FXML private Button sendCodeButton;
    @FXML private Button verifyCodeButton;
    @FXML private Label codeStatusLabel;
    @FXML private Label devCodeLabel;
    @FXML private Label strengthLabel;
    @FXML private Label passwordHint;
    @FXML private HBox strengthMeter;

    private final RegistrationService registrationService = new RegistrationService();
    private final EmailVerifier verifier = new EmailVerifier();
    private final Lockout lockout = new Lockout();

    private Timeline lockoutPulse;
    private boolean emailVerified;
    private String verifiedEmail;

    @FXML
    private void initialize() {
        roleBox.getItems().addAll(
                new Choice("STAFF", "Teacher / School Staff"),
                new Choice("PERSONNEL", "Maintenance Personnel"),
                new Choice("MAINT_HEAD", "Maintenance Head")
        );
        roleBox.getSelectionModel().selectFirst();

        passwordField.textProperty().addListener((obs, old, value) -> {
            updateStrength(value);
            updateLengthHint(value);
        });
        emailField.textProperty().addListener((obs, old, value) -> {
            if (emailVerified && (value == null
                    || !value.trim().equalsIgnoreCase(verifiedEmail))) {
                emailVerified = false;
                verifiedEmail = null;
                setCodeStatus("E-mail changed. Send a new code.", false);
            }
        });
        updateStrength("");
    }

    // ------------------------------------------------------------ e-mail code

    @FXML
    private void onSendCode() {
        if (lockout.isLocked()) {
            startLockoutCountdown("Too many attempts.");
            return;
        }
        hideError();
        String email = text(emailField);
        if (!RegistrationService.isValidEmail(email)) {
            showError("Enter a valid e-mail address before requesting a code.");
            emailField.requestFocus();
            return;
        }

        setSending(true);
        Task<EmailVerifier.Challenge> task = new Task<>() {
            @Override
            protected EmailVerifier.Challenge call() throws Exception {
                return verifier.send(email);
            }
        };
        task.setOnSucceeded(e -> {
            setSending(false);
            EmailVerifier.Challenge challenge = task.getValue();
            if (challenge.isDevMode()) {
                devCodeLabel.setText("Development mode: SMTP is not configured. "
                        + "Use this code to continue: " + challenge.devCode());
                show(devCodeLabel);
                setCodeStatus("No mail server is configured, so the code is shown here.", false);
            } else {
                hide(devCodeLabel);
                setCodeStatus("A " + EmailVerifier.CODE_LENGTH + "-digit code was sent to "
                        + email + ". Check the inbox and spam folder.", true);
            }
            codeField.requestFocus();
        });
        task.setOnFailed(e -> {
            setSending(false);
            Throwable ex = task.getException();
            String message = ex instanceof SmtpMailer.MailException
                    ? ex.getMessage()
                    : "Could not send the code: " + (ex == null ? "unknown error" : ex.getMessage());
            setCodeStatus(message, false);
            showError(message);
        });
        start(task, "deihs-verify-code");
    }

    @FXML
    private void onVerifyCode() {
        hideError();
        String email = text(emailField);
        try {
            verifier.verify(email, codeField.getText());
            emailVerified = true;
            verifiedEmail = email;
            setCodeStatus("E-mail verified. You can submit the request now.", true);
            codeField.setDisable(true);
            verifyCodeButton.setDisable(true);
            sendCodeButton.setDisable(true);
            createButton.requestFocus();
        } catch (EmailVerifier.VerificationException ex) {
            emailVerified = false;
            setCodeStatus(ex.getMessage(), false);
        }
    }

    // -------------------------------------------------------------- submit

    @FXML
    private void onCreateAccount() {
        if (lockout.isLocked()) {
            startLockoutCountdown("Too many attempts.");
            return;
        }
        hideError();

        String employeeNo = text(employeeNoField);
        String username = text(usernameField);
        String firstName = text(firstNameField);
        String lastName = text(lastNameField);
        String email = text(emailField);
        String department = text(departmentField);
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String confirm = confirmField.getText() == null ? "" : confirmField.getText();

        if (employeeNo.isEmpty()) {
            showError("Enter your employee number.");
            employeeNoField.requestFocus();
            return;
        }
        String usernameError = PasswordPolicy.usernameError(username);
        if (usernameError != null) {
            showError(usernameError);
            usernameField.requestFocus();
            return;
        }
        if (password.length() > PasswordPolicy.MAX_LENGTH) {
            lockout.lockNow();
            startLockoutCountdown("Password must be "
                    + PasswordPolicy.MAX_LENGTH + " characters or fewer.");
            return;
        }
        String passwordError = PasswordPolicy.passwordError(password);
        if (passwordError != null) {
            showError(passwordError);
            passwordField.requestFocus();
            return;
        }
        if (!password.equals(confirm)) {
            showError("Passwords do not match.");
            confirmField.requestFocus();
            return;
        }
        if (!RegistrationService.isValidEmail(email)) {
            showError("Enter a valid e-mail address, for example name@gmail.com.");
            emailField.requestFocus();
            return;
        }
        if (!emailVerified || !email.equalsIgnoreCase(verifiedEmail)) {
            showError("Verify your e-mail address with the code before submitting.");
            emailField.requestFocus();
            return;
        }
        Choice role = roleBox.getValue();
        if (role == null) {
            showError("Choose the role you are requesting.");
            roleBox.requestFocus();
            return;
        }

        RegistrationDraft draft = new RegistrationDraft(
                employeeNo, username, password, confirm,
                firstName, lastName, email, null, department,
                role.value(), true);

        setBusy(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                registrationService.submit(draft);
                return null;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(createButton.getScene() == null
                    ? null : createButton.getScene().getWindow());
            alert.setTitle("Request submitted");
            alert.setHeaderText("Your account request was submitted");
            alert.setContentText("An administrator will review it. "
                    + "You can sign in once the account is approved.");
            ViewLoader.applyAppStyles(alert.getDialogPane());
            alert.showAndWait();
            backToLogin();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = task.getException();
            if (ex instanceof ValidationException ve) {
                showError(ve.getMessage());
            } else if (ex instanceof SQLException) {
                showError("Could not submit the request because the database is unavailable. "
                        + "Please try again in a moment.");
            } else {
                showError("Could not submit the request: "
                        + (ex == null ? "unknown error" : ex.getMessage()));
            }
        });
        start(task, "deihs-register");
    }

    @FXML
    private void onBackToSignIn() {
        backToLogin();
    }

    private void backToLogin() {
        try {
            Main.setRoot(ViewLoader.load("/com/deihs/mms/view/LoginView.fxml"));
        } catch (RuntimeException e) {
            showError("Could not open the sign-in screen: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ helpers

    private void updateStrength(String password) {
        PasswordPolicy.Strength strength = PasswordPolicy.strength(password);
        int score = PasswordPolicy.score(password);
        int filled = score <= 1 ? 1 : score <= 3 ? 2 : 3;

        List<Node> bars = strengthMeter.getChildren().stream()
                .filter(n -> n.getStyleClass().contains("strength-bar"))
                .toList();
        for (int i = 0; i < bars.size(); i++) {
            Node bar = bars.get(i);
            bar.getStyleClass().removeAll("strength-weak", "strength-good", "strength-strong");
            if (i < filled) {
                bar.getStyleClass().add(strength.styleClass());
            }
        }
        strengthLabel.getStyleClass().removeAll("strength-weak", "strength-good", "strength-strong");
        strengthLabel.getStyleClass().add(strength.styleClass());
        strengthLabel.setText(strength.label());
    }

    private void updateLengthHint(String password) {
        String value = password == null ? "" : password;
        if (value.length() > PasswordPolicy.MAX_LENGTH) {
            passwordHint.setText("Too long — " + PasswordPolicy.MAX_LENGTH
                    + " characters maximum. Submitting will pause the form for 20 seconds.");
            passwordHint.setStyle("-fx-text-fill: #B3261E;");
        } else {
            passwordHint.setText("Use at least " + PasswordPolicy.MIN_LENGTH
                    + " characters with a letter and a number.");
            passwordHint.setStyle(null);
        }
    }

    private void setSending(boolean sending) {
        sendCodeButton.setDisable(sending || emailVerified);
        sendCodeButton.setText(sending ? "Sending…" : "Send code");
        if (!sending && !emailVerified) {
            verifyCodeButton.setDisable(false);
        }
    }

    private void setBusy(boolean busy) {
        createButton.setDisable(busy || lockout.isLocked());
        sendCodeButton.setDisable(busy || emailVerified);
        verifyCodeButton.setDisable(busy || emailVerified);
        createButton.setText(busy ? "Submitting…" : "Submit request");
    }

    private void setCodeStatus(String message, boolean good) {
        codeStatusLabel.setText(message);
        codeStatusLabel.setStyle(good ? "-fx-text-fill: #0E6B34;" : "-fx-text-fill: #B3261E;");
        show(codeStatusLabel);
    }

    private void startLockoutCountdown(String reason) {
        stopLockoutPulse();
        setBusy(false);
        createButton.setDisable(true);
        updateLockoutMessage(reason);
        lockoutPulse = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (lockout.isLocked()) {
                updateLockoutMessage(reason);
            } else {
                stopLockoutPulse();
                hideError();
                setBusy(false);
            }
        }));
        lockoutPulse.setCycleCount(Timeline.INDEFINITE);
        lockoutPulse.play();
    }

    private void updateLockoutMessage(String reason) {
        long seconds = lockout.remainingSeconds();
        if (seconds > 0) {
            showError(reason + " Try again in " + seconds + "s.");
        }
    }

    private void stopLockoutPulse() {
        if (lockoutPulse != null) {
            lockoutPulse.stop();
            lockoutPulse = null;
        }
    }

    private static void start(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static String text(TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    private void showError(String message) {
        errorLabel.setText(message);
        show(errorLabel);
    }

    private void hideError() {
        hide(errorLabel);
        errorLabel.setText("");
    }

    private static void show(Node node) {
        node.setVisible(true);
        node.setManaged(true);
    }

    private static void hide(Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }
}
