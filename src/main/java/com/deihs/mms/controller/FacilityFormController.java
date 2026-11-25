package com.deihs.mms.controller;

import com.deihs.mms.model.Choice;
import com.deihs.mms.model.Facility;
import com.deihs.mms.model.FacilityDraft;
import com.deihs.mms.util.Format;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.util.List;

/**
 * Add/edit form for a facility. The dialog owns save/cancel; this controller
 * only collects and pre-fills values.
 */
public class FacilityFormController {

    private static final List<String> TYPE_SUGGESTIONS = List.of(
            "Classroom", "Laboratory", "Library", "Office",
            "Comfort Room", "Covered Court", "Gymnasium",
            "Canteen", "Clinic", "Storage", "Hallway", "Other"
    );

    @FXML private Label errorLabel;
    @FXML private TextField codeField;
    @FXML private TextField nameField;
    @FXML private TextField buildingField;
    @FXML private TextField floorField;
    @FXML private TextField roomField;
    @FXML private ComboBox<String> typeBox;
    @FXML private ComboBox<Choice> conditionBox;
    @FXML private TextArea notesArea;

    @FXML
    private void initialize() {
        typeBox.setItems(FXCollections.observableArrayList(TYPE_SUGGESTIONS));
        conditionBox.setItems(FXCollections.observableArrayList(
                new Choice("GOOD", Format.pretty("GOOD")),
                new Choice("FAIR", Format.pretty("FAIR")),
                new Choice("POOR", Format.pretty("POOR")),
                new Choice("NEEDS_REPAIR", Format.pretty("NEEDS_REPAIR")),
                new Choice("OUT_OF_ORDER", Format.pretty("OUT_OF_ORDER"))
        ));
        conditionBox.getSelectionModel().selectFirst();
    }

    public void setFacility(Facility f) {
        if (f == null) {
            return;
        }
        codeField.setText(f.code());
        nameField.setText(f.name());
        buildingField.setText(f.building());
        floorField.setText(f.floor());
        roomField.setText(f.roomNumber());
        typeBox.setValue(f.type());
        notesArea.setText(f.notes());
        for (Choice c : conditionBox.getItems()) {
            if (c.value().equals(f.condition())) {
                conditionBox.setValue(c);
                break;
            }
        }
    }

    public FacilityDraft draft() {
        Choice cond = conditionBox.getValue();
        String type = resolveType();
        return new FacilityDraft(
                codeField.getText(),
                nameField.getText(),
                buildingField.getText(),
                floorField.getText(),
                roomField.getText(),
                type,
                cond == null ? null : cond.value(),
                notesArea.getText()
        );
    }

    /** Prefers what the user typed (editable combo) and falls back to the selected value. */
    private String resolveType() {
        String typed = typeBox.getEditor() == null ? null : typeBox.getEditor().getText();
        if (typed != null && !typed.isBlank()) {
            return typed.trim();
        }
        String value = typeBox.getValue();
        return value == null || value.isBlank() ? null : value.trim();
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
