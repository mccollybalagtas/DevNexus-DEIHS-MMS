package com.deihs.mms.controller;

import com.deihs.mms.model.User;
import com.deihs.mms.service.ProfileService;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Appearance;
import com.deihs.mms.util.Avatars;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.PasswordPolicy;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

/**
 * Backs the self-service profile page.
 *
 * <p>All writes go through {@link ProfileService}, which always resolves the
 * target account from the session, so this screen can never edit another
 * user. After a successful save the controller refreshes itself and tells the
 * shell to refresh the top bar, drawer and popover.</p>
 */
public class ProfileController {

    private static final List<String> THEMES =
            List.of("Green (school)", "Navy blue", "Dark");
    private static final List<String> DENSITIES =
            List.of("Comfortable", "Compact");

    @FXML private StackPane photoHolder;
    @FXML private Label nameLabel;
    @FXML private Label roleLabel;
    @FXML private Label emailBadge;
    @FXML private Label employeeNoLabel;
    @FXML private Label departmentLabel;
    @FXML private Label lastLoginLabel;
    @FXML private Button changePhotoButton;
    @FXML private Button removePhotoButton;
    @FXML private Label photoStatus;

    @FXML private TextField firstNameField;
    @FXML private TextField lastNameField;
    @FXML private TextField usernameField;
    @FXML private TextField emailField;
    @FXML private TextField departmentField;
    @FXML private Label detailsStatus;
    @FXML private Button saveDetailsButton;

    @FXML private PasswordField currentPasswordField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Region strengthBar1;
    @FXML private Region strengthBar2;
    @FXML private Region strengthBar3;
    @FXML private Label strengthLabel;
    @FXML private Label passwordHint;
    @FXML private Label passwordStatus;
    @FXML private Button updatePasswordButton;

    @FXML private ComboBox<String> themeBox;
    @FXML private ComboBox<String> densityBox;
    @FXML private Label appearanceStatus;
    @FXML private Button saveAppearanceButton;

    private final ProfileService profileService = new ProfileService();
    private Runnable onProfileChanged;

    /** The shell listens so the top bar and drawer update in place. */
    public void setOnProfileChanged(Runnable listener) {
        this.onProfileChanged = listener;
    }

    @FXML
    private void initialize() {
        themeBox.getItems().setAll(THEMES);
        densityBox.getItems().setAll(DENSITIES);
        newPasswordField.textProperty().addListener((obs, old, value) -> updateStrength());
        updateStrength();

        User user = Session.get();
        fill(user);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void fill(User user) {
        Avatars.fill(photoHolder, user, 104);

        nameLabel.setText(user.fullName());
        roleLabel.setText(user.roleName());
        employeeNoLabel.setText(valueOrDash(user.employeeNo()));
        departmentLabel.setText(valueOrDash(user.department()));
        lastLoginLabel.setText(user.lastLogin() == null
                ? "First session" : Format.dateTime(user.lastLogin()));

        boolean verified = user.emailVerified();
        emailBadge.setText(verified ? "Email verified" : "Email not verified");
        emailBadge.getStyleClass().removeAll("badge-completed", "badge-pending");
        emailBadge.getStyleClass().add(verified ? "badge-completed" : "badge-pending");

        firstNameField.setText(nullToEmpty(user.firstName()));
        lastNameField.setText(nullToEmpty(user.lastName()));
        usernameField.setText(nullToEmpty(user.username()));
        emailField.setText(nullToEmpty(user.email()));
        departmentField.setText(nullToEmpty(user.department()));

        themeBox.getSelectionModel().select(themeIndex(user.themeOrDefault()));
        densityBox.getSelectionModel().select(
                "compact".equals(user.densityOrDefault()) ? 1 : 0);

        boolean hasPhoto = user.photoPath() != null && !user.photoPath().isBlank();
        removePhotoButton.setDisable(!hasPhoto);
    }

    private void notifyChanged() {
        if (onProfileChanged != null) {
            onProfileChanged.run();
        }
    }

    // ------------------------------------------------------------------
    // Photo
    // ------------------------------------------------------------------

    @FXML
    private void onChangePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a profile photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File file = chooser.showOpenDialog(window());
        if (file == null) {
            return;
        }
        try {
            User updated = profileService.savePhoto(Session.get(), file.toPath());
            fill(updated);
            showStatus(photoStatus, "Photo updated.", false);
            notifyChanged();
        } catch (ValidationException e) {
            showStatus(photoStatus, e.getMessage(), true);
        } catch (SQLException e) {
            AlertUtil.error(window(), "Profile photo",
                    "Could not save the photo: " + e.getMessage());
        }
    }

    @FXML
    private void onRemovePhoto() {
        if (!AlertUtil.confirm(window(), "Remove photo",
                "Remove your profile photo and show your initials instead?")) {
            return;
        }
        try {
            User updated = profileService.clearPhoto(Session.get());
            fill(updated);
            showStatus(photoStatus, "Photo removed.", false);
            notifyChanged();
        } catch (SQLException e) {
            AlertUtil.error(window(), "Profile photo",
                    "Could not remove the photo: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Details
    // ------------------------------------------------------------------

    @FXML
    private void onSaveDetails() {
        saveDetailsButton.setDisable(true);
        try {
            User updated = profileService.updateDetails(
                    Session.get(),
                    firstNameField.getText(),
                    lastNameField.getText(),
                    usernameField.getText(),
                    emailField.getText(),
                    departmentField.getText());
            fill(updated);
            showStatus(detailsStatus, "Your details were saved.", false);
            notifyChanged();
        } catch (ValidationException e) {
            showStatus(detailsStatus, e.getMessage(), true);
        } catch (SQLException e) {
            AlertUtil.error(window(), "Profile",
                    "Could not save your details: " + e.getMessage());
        } finally {
            saveDetailsButton.setDisable(false);
        }
    }

    // ------------------------------------------------------------------
    // Password
    // ------------------------------------------------------------------

    @FXML
    private void onUpdatePassword() {
        updatePasswordButton.setDisable(true);
        try {
            profileService.changePassword(
                    Session.get(),
                    currentPasswordField.getText(),
                    newPasswordField.getText(),
                    confirmPasswordField.getText());
            currentPasswordField.clear();
            newPasswordField.clear();
            confirmPasswordField.clear();
            updateStrength();
            showStatus(passwordStatus, "Your password was changed.", false);
        } catch (ValidationException e) {
            showStatus(passwordStatus, e.getMessage(), true);
        } catch (SQLException e) {
            AlertUtil.error(window(), "Password",
                    "Could not change your password: " + e.getMessage());
        } finally {
            updatePasswordButton.setDisable(false);
        }
    }

    private void updateStrength() {
        String password = newPasswordField.getText();
        PasswordPolicy.Strength strength = PasswordPolicy.strength(password);
        boolean empty = password == null || password.isEmpty();

        strengthLabel.setText(empty ? "Enter a password" : strength.label());
        strengthLabel.getStyleClass().removeAll(
                "strength-weak", "strength-good", "strength-strong");
        if (!empty) {
            strengthLabel.getStyleClass().add(strength.styleClass());
        }

        int filled = empty ? 0 : switch (strength) {
            case WEAK -> 1;
            case GOOD -> 2;
            case STRONG -> 3;
        };
        String fillClass = empty ? "bar-empty" : "bar-" + strength.name().toLowerCase();
        paintBar(strengthBar1, filled >= 1, fillClass);
        paintBar(strengthBar2, filled >= 2, fillClass);
        paintBar(strengthBar3, filled >= 3, fillClass);
    }

    private static void paintBar(Region bar, boolean filled, String fillClass) {
        bar.getStyleClass().removeAll(
                "bar-empty", "bar-weak", "bar-good", "bar-strong");
        bar.getStyleClass().add(filled ? fillClass : "bar-empty");
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    @FXML
    private void onSaveAppearance() {
        saveAppearanceButton.setDisable(true);
        try {
            User updated = profileService.savePreferences(
                    Session.get(),
                    themeKey(themeBox.getValue()),
                    densityKey(densityBox.getValue()));
            fill(updated);
            Appearance.applyTo(photoHolder);
            showStatus(appearanceStatus, "Your appearance was saved.", false);
            notifyChanged();
        } catch (SQLException e) {
            AlertUtil.error(window(), "Appearance",
                    "Could not save your appearance: " + e.getMessage());
        } finally {
            saveAppearanceButton.setDisable(false);
        }
    }

    private static int themeIndex(String theme) {
        return switch (theme) {
            case "navy" -> 1;
            case "dark" -> 2;
            default -> 0;
        };
    }

    private static String themeKey(String label) {
        return switch (label == null ? "" : label) {
            case "Navy blue" -> "navy";
            case "Dark" -> "dark";
            default -> "green";
        };
    }

    private static String densityKey(String label) {
        return "Compact".equals(label) ? "compact" : "comfortable";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Window window() {
        return photoHolder.getScene() == null ? null : photoHolder.getScene().getWindow();
    }

    private static void showStatus(Label label, String message, boolean error) {
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
        label.getStyleClass().removeAll("form-error", "form-success");
        label.getStyleClass().add(error ? "form-error" : "form-success");
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
