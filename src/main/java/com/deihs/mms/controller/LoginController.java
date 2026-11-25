package com.deihs.mms.controller;

import com.deihs.mms.Main;
import com.deihs.mms.config.AppConfig;
import com.deihs.mms.dao.AuditDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.db.Database;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.stage.Modality;

import java.io.IOException;
import java.net.URL;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Sign-in screen. The role always comes from the database — the user never
 * chooses it here.
 */
public class LoginController {

    private static final String PREF_NODE = "com/deihs/mms/login";
    private static final String PREF_USERNAME = "rememberedUsername";

    @FXML private StackPane brandPane;
    @FXML private StackPane signInPane;
    @FXML private Region buildingLayer;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField passwordVisible;
    @FXML private Button togglePasswordButton;
    @FXML private SVGPath eyeOpenIcon;
    @FXML private SVGPath eyeClosedIcon;
    @FXML private CheckBox rememberMe;
    @FXML private Label errorLabel;
    @FXML private Label headingLabel;
    @FXML private Label schoolLine1;
    @FXML private Label schoolLine2;
    @FXML private Label locationLabel;
    @FXML private Button signInButton;
    @FXML private VBox googleBox;
    @FXML private Button googleButton;

    private final AuthService authService = new AuthService();
    private final GoogleOAuthService googleOAuthService = new GoogleOAuthService();
    private final UserDao userDao = new UserDao();
    private final AuditDao auditDao = new AuditDao();
    private final RegistrationService registrationService = new RegistrationService();

    private boolean passwordShown;

    @FXML
    private void initialize() {
        clipDecorativeLayers();
        applyBuildingPhoto();
        applyDisplayFont();
        restoreRememberedUsername();

        usernameField.setOnAction(e -> passwordField.requestFocus());
        passwordField.setOnAction(e -> onSignIn());
        passwordVisible.setOnAction(e -> onSignIn());

        boolean configured = googleOAuthService.isConfigured();
        if (googleBox != null) {
            googleBox.setVisible(configured);
            googleBox.setManaged(configured);
        }
        if (googleButton != null) {
            googleButton.setVisible(configured);
            googleButton.setManaged(configured);
        }

        Platform.runLater(() -> {
            if (usernameField.getText() == null || usernameField.getText().isBlank()) {
                usernameField.requestFocus();
            } else {
                passwordField.requestFocus();
            }
        });
    }

    /**
     * The decorative circles and light beams deliberately extend past the
     * panel edges. Without a clip they would paint over the opposite panel.
     */
    private void clipDecorativeLayers() {
        if (brandPane != null) {
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(brandPane.widthProperty());
            clip.heightProperty().bind(brandPane.heightProperty());
            brandPane.setClip(clip);
        }
        if (signInPane != null) {
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(signInPane.widthProperty());
            clip.heightProperty().bind(signInPane.heightProperty());
            signInPane.setClip(clip);
        }
    }

    /**
     * Uses a real campus photo when one is bundled at
     * /com/deihs/mms/img/login-building.jpg. Otherwise the navy gradient
     * defined in styles.css remains visible.
     */
    private void applyBuildingPhoto() {
        if (buildingLayer == null) {
            return;
        }
        URL photo = LoginController.class.getResource("/com/deihs/mms/img/login-building.jpg");
        if (photo == null) {
            buildingLayer.setVisible(false);
            buildingLayer.setManaged(false);
            return;
        }
        // Inline style wins over the stylesheet, and loading is synchronous so
        // the photo is present on the very first pulse (no blank flash).
        buildingLayer.setStyle("-fx-background-image: url('" + photo.toExternalForm() + "');"
                + "-fx-background-size: cover;"
                + "-fx-background-position: center center;"
                + "-fx-background-repeat: no-repeat;");
        buildingLayer.setVisible(true);
        buildingLayer.setManaged(true);
    }

    /**
     * JavaFX CSS accepts a single font family, not a fallback list. Pick the
     * best serif the host actually has so the display type looks right on
     * Windows (Georgia) and on Linux (FreeSerif / Serif).
     */
    private void applyDisplayFont() {
        String family = pickDisplayFamily();
        if (family == null) {
            return;
        }
        String css = "-fx-font-family: \"" + family + "\";";
        for (Label label : new Label[]{headingLabel, schoolLine1, schoolLine2, locationLabel}) {
            if (label != null) {
                label.setStyle(css);
            }
        }
    }

    private static String pickDisplayFamily() {
        var families = Font.getFamilies();
        for (String candidate : new String[]{"Georgia", "Times New Roman", "FreeSerif", "Serif"}) {
            if (families.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void restoreRememberedUsername() {
        try {
            String saved = Preferences.userRoot().node(PREF_NODE).get(PREF_USERNAME, "");
            if (saved != null && !saved.isBlank()) {
                usernameField.setText(saved);
                rememberMe.setSelected(true);
            }
        } catch (RuntimeException ignored) {
            // A locked-down preferences store must never block sign-in.
        }
    }

    private void persistRememberedUsername() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);
            String name = usernameField.getText() == null ? "" : usernameField.getText().trim();
            if (rememberMe.isSelected() && !name.isEmpty()) {
                prefs.put(PREF_USERNAME, name);
            } else {
                prefs.remove(PREF_USERNAME);
            }
            prefs.flush();
        } catch (Exception ignored) {
            // Preferences are a convenience only; never fail a sign-in over them.
        }
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
    private void onForgotPassword() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (signInButton.getScene() != null) {
            alert.initOwner(signInButton.getScene().getWindow());
        }
        alert.setTitle("Forgot Password");
        alert.setHeaderText("Password resets are handled by the School Administrator");
        alert.setContentText("For security, passwords cannot be reset from this screen.\n\n"
                + "Please contact the School Administrator (or the Maintenance Head) to request a reset. "
                + "You will receive a temporary password and can change it after signing in.");
        ViewLoader.applyAppStyles(alert.getDialogPane());
        alert.showAndWait();
    }

    @FXML
    private void onTogglePassword() {
        passwordShown = !passwordShown;

        if (passwordShown) {
            passwordVisible.setText(passwordField.getText());
            passwordVisible.setVisible(true);
            passwordVisible.setManaged(true);
            passwordField.setVisible(false);
            passwordField.setManaged(false);

            eyeOpenIcon.setVisible(false);
            eyeClosedIcon.setVisible(true);
            togglePasswordButton.setAccessibleText("Hide password");

            passwordVisible.requestFocus();
            passwordVisible.positionCaret(passwordVisible.getText().length());
        } else {
            passwordField.setText(passwordVisible.getText());
            passwordField.setVisible(true);
            passwordField.setManaged(true);
            passwordVisible.setVisible(false);
            passwordVisible.setManaged(false);

            eyeOpenIcon.setVisible(true);
            eyeClosedIcon.setVisible(false);
            togglePasswordButton.setAccessibleText("Show password");

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
            persistRememberedUsername();
            Session.set(user);
            openShell();
        });

        task.setOnFailed(e -> {
            setBusy(false, null);
            Throwable ex = task.getException();
            if (ex instanceof AuthException auth) {
                showError(auth.getMessage());
                clearPassword();
                focusPassword();
            } else if (ex instanceof SQLException sql) {
                Database.reset(); // let the user start MySQL and retry without restarting
                sql.printStackTrace(System.err);
                showError("Cannot reach the database at "
                        + AppConfig.get("db.host", "127.0.0.1") + ":"
                        + AppConfig.get("db.port", "3306")
                        + ". Start MySQL (XAMPP) and check the server address. ("
                        + compact(sql) + ")");
                clearPassword();
                focusPassword();
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
        if (rememberMe != null) rememberMe.setDisable(busy);
        if (togglePasswordButton != null) togglePasswordButton.setDisable(busy);
        signInButton.setText(busy ? (signInText == null ? "Please wait…" : signInText) : "Sign In");
        if (busy && "Opening Google…".equals(signInText) && googleButton != null) {
            googleButton.setText("Waiting for Google…");
        } else if (!busy && googleButton != null) {
            googleButton.setText("Continue with Google");
        }
    }

    private String currentPassword() {
        return passwordShown ? passwordVisible.getText() : passwordField.getText();
    }

    private void clearPassword() {
        passwordField.clear();
        passwordVisible.clear();
    }

    private void focusPassword() {
        if (passwordShown) {
            passwordVisible.requestFocus();
        } else {
            passwordField.requestFocus();
        }
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

    /** Short, single-line reason for a connection failure shown in the form label. */
    private static String compact(SQLException ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return "connection failed";
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 140 ? msg.substring(0, 137) + "…" : msg;
    }
}
