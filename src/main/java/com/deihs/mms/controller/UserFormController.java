package com.deihs.mms.controller;

import com.deihs.mms.model.Choice;
import com.deihs.mms.model.User;
import com.deihs.mms.model.UserDraft;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class UserFormController {

    @FXML private Label errorLabel;
    @FXML private TextField employeeNoField;
    @FXML private TextField usernameField;
    @FXML private TextField firstNameField;
    @FXML private TextField lastNameField;
    @FXML private TextField emailField;
    @FXML private TextField phoneField;
    @FXML private TextField departmentField;
    @FXML private ComboBox<Choice> roleBox;
    @FXML private VBox passwordBox;
    @FXML private PasswordField passwordField;

    @FXML
    private void initialize() {
        roleBox.setItems(FXCollections.observableArrayList(
                new Choice("ADMIN", "School Administrator"),
                new Choice("MAINT_HEAD", "Maintenance Head"),
                new Choice("PERSONNEL", "Maintenance Personnel"),
                new Choice("STAFF", "Teacher / School Staff")
        ));
        roleBox.getSelectionModel().select(3); // STAFF default
    }

    public void setUser(User u) {
        if (u == null) return;
        employeeNoField.setText(u.employeeNo());
        usernameField.setText(u.username());
        firstNameField.setText(u.firstName());
        lastNameField.setText(u.lastName());
        emailField.setText(u.email() == null ? "" : u.email());
        phoneField.setText(u.phone() == null ? "" : u.phone());
        departmentField.setText(u.department());
        for (Choice c : roleBox.getItems()) {
            if (c.value().equals(u.role().code())) {
                roleBox.setValue(c);
                break;
            }
        }
    }

    public void setCreateMode(boolean create) {
        passwordBox.setVisible(create);
        passwordBox.setManaged(create);
    }

    public UserDraft draft() {
        Choice role = roleBox.getValue();
        return new UserDraft(
                employeeNoField.getText(),
                usernameField.getText(),
                firstNameField.getText(),
                lastNameField.getText(),
                empty(emailField.getText()),
                empty(phoneField.getText()),
                empty(departmentField.getText()),
                role == null ? null : role.value(),
                passwordField.getText()
        );
    }

    private static String empty(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    public void clearError() {
        errorLabel.setText(null);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
