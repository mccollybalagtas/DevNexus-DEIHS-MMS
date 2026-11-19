package com.deihs.mms.controller;

import com.deihs.mms.Main;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.RegistrationDraft;
import com.deihs.mms.service.RegistrationService;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.ViewLoader;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.sql.SQLException;

public class RegisterController {

    @FXML private Label messageLabel;
    @FXML private TextField employeeNoField;
    @FXML private TextField usernameField;
    @FXML private TextField firstNameField;
    @FXML private TextField lastNameField;
    @FXML private TextField emailField;
    @FXML private TextField phoneField;
    @FXML private TextField departmentField;
    @FXML private ComboBox<Choice> roleBox;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmField;
    @FXML private Button submitButton;

    private final RegistrationService registrationService = new RegistrationService();

    @FXML
    private void initialize() {
        roleBox.getItems().addAll(
                new Choice("STAFF", "Teacher / School Staff"),
                new Choice("PERSONNEL", "Maintenance Personnel"),
                new Choice("MAINT_HEAD", "Maintenance Head")
        );
        roleBox.getSelectionModel().selectFirst();
    }

    @FXML
    private void onSubmit() {
        clearMessage();
        RegistrationDraft draft = new RegistrationDraft(
                employeeNoField.getText(),
                usernameField.getText(),
                passwordField.getText(),
                confirmField.getText(),
                firstNameField.getText(),
                lastNameField.getText(),
                emailField.getText(),
                phoneField.getText(),
                departmentField.getText(),
                roleBox.getValue() == null ? null : roleBox.getValue().value()
        );

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
            showSuccess("Your account request has been submitted and is pending administrator approval. "
                    + "You can sign in once it is approved.");
            clearForm();
        });

        task.setOnFailed(e -> {
            setBusy(false);
            Throwable ex = task.getException();
            if (ex instanceof ValidationException ve) {
                showError(ve.getMessage());
            } else if (ex instanceof SQLException) {
                showError("Could not submit your request. Please check the database connection and try again.");
            } else {
                showError("Could not submit your request: " + (ex == null ? "unknown error" : ex.getMessage()));
            }
        });

        Thread t = new Thread(task, "register-request");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onBack() {
        try {
            FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/LoginView.fxml");
            Parent login = loader.load();
            Main.setRoot(login);
        } catch (IOException | RuntimeException e) {
            showError("Could not return to sign in: " + e.getMessage());
        }
    }

    private void clearForm() {
        employeeNoField.clear();
        usernameField.clear();
        firstNameField.clear();
        lastNameField.clear();
        emailField.clear();
        phoneField.clear();
        departmentField.clear();
        passwordField.clear();
        confirmField.clear();
        roleBox.getSelectionModel().selectFirst();
    }

    private void setBusy(boolean busy) {
        submitButton.setDisable(busy);
        submitButton.setText(busy ? "Submitting…" : "Submit Request");
    }

    private void showError(String text) {
        messageLabel.getStyleClass().setAll("form-error");
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void showSuccess(String text) {
        messageLabel.getStyleClass().setAll("form-hint");
        messageLabel.setStyle("-fx-text-fill: #0E6B34; -fx-font-weight: bold;");
        messageLabel.setText(text);
        messageLabel.setVisible(true);
        messageLabel.setManaged(true);
    }

    private void clearMessage() {
        messageLabel.setText(null);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);
        messageLabel.setStyle(null);
    }
}
