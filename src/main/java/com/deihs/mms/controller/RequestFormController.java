package com.deihs.mms.controller;

import com.deihs.mms.dao.LookupDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.model.RequestDraft;
import com.deihs.mms.util.Format;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DateCell;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * The New Request form. It only collects values; {@code RequestService}
 * decides whether they are acceptable and owns the insert.
 */
public class RequestFormController {

    private static final LookupOption NO_EQUIPMENT = new LookupOption(0, "— None —");

    @FXML private Label errorLabel;
    @FXML private Label photoLabel;
    @FXML private TextField titleField;
    @FXML private ComboBox<LookupOption> categoryBox;
    @FXML private ComboBox<Choice> priorityBox;
    @FXML private ComboBox<LookupOption> facilityBox;
    @FXML private ComboBox<LookupOption> equipmentBox;
    @FXML private TextArea descriptionArea;
    @FXML private DatePicker targetDatePicker;

    private final LookupDao lookupDao = new LookupDao();
    private File photo;

    @FXML
    private void initialize() {
        buildPriorities();
        loadCategories();
        loadFacilities();
        loadEquipment(null);
        limitTargetDates();

        facilityBox.valueProperty().addListener((o, old, val) ->
                loadEquipment(val == null ? null : val.id()));

        // A category carries a sensible default priority, e.g. Plumbing -> High.
        categoryBox.valueProperty().addListener((o, old, val) -> {
            if (val == null) {
                return;
            }
            try {
                String priority = lookupDao.defaultPriority(val.id());
                priorityBox.getItems().stream()
                        .filter(c -> c.value().equals(priority))
                        .findFirst()
                        .ifPresent(priorityBox::setValue);
            } catch (SQLException ignored) {
                // The default is a convenience; the user's own choice still stands.
            }
        });
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    private void buildPriorities() {
        for (String p : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) {
            priorityBox.getItems().add(new Choice(p, Format.pretty(p)));
        }
        priorityBox.setValue(priorityBox.getItems().get(1));
    }

    private void loadCategories() {
        try {
            categoryBox.getItems().setAll(lookupDao.categories());
        } catch (SQLException e) {
            showError("The category list could not be loaded.");
        }
    }

    private void loadFacilities() {
        try {
            facilityBox.getItems().setAll(lookupDao.facilities());
        } catch (SQLException e) {
            showError("The facility list could not be loaded.");
        }
    }

    private void loadEquipment(Integer facilityId) {
        try {
            equipmentBox.getItems().setAll(NO_EQUIPMENT);
            equipmentBox.getItems().addAll(lookupDao.equipment(facilityId));
            equipmentBox.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            equipmentBox.getItems().setAll(NO_EQUIPMENT);
            showError("The equipment list could not be loaded.");
        }
    }

    /** Past target dates make no sense for a repair. */
    private void limitTargetDates() {
        targetDatePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isBefore(LocalDate.now()));
            }
        });
    }

    // ------------------------------------------------------------------
    // Photo
    // ------------------------------------------------------------------

    @FXML
    private void onChoosePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a photo of the problem");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Images", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.webp"));

        File chosen = chooser.showOpenDialog(
                photoLabel.getScene() == null ? null : photoLabel.getScene().getWindow());
        if (chosen != null) {
            photo = chosen;
            photoLabel.setText(chosen.getName());
            clearError();
        }
    }

    @FXML
    private void onRemovePhoto() {
        photo = null;
        photoLabel.setText("No photo selected");
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    public RequestDraft draft() {
        LookupOption category = categoryBox.getValue();
        LookupOption facility = facilityBox.getValue();
        LookupOption equipment = equipmentBox.getValue();
        Choice priority = priorityBox.getValue();

        return new RequestDraft(
                titleField.getText(),
                category == null ? null : category.id(),
                facility == null ? null : facility.id(),
                equipment == null || equipment.id() == 0 ? null : equipment.id(),
                priority == null ? null : priority.value(),
                descriptionArea.getText(),
                targetDatePicker.getValue(),
                photo);
    }

    public void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
