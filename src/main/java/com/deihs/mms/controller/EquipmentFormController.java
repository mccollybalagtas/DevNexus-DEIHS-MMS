package com.deihs.mms.controller;

import com.deihs.mms.dao.LookupDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.Equipment;
import com.deihs.mms.model.EquipmentDraft;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.util.Format;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class EquipmentFormController {

    private static final LookupOption NO_FACILITY = new LookupOption(0, "— None —");

    @FXML private Label errorLabel;
    @FXML private TextField propertyNoField;
    @FXML private TextField nameField;
    @FXML private TextField categoryField;
    @FXML private TextField brandField;
    @FXML private TextField modelField;
    @FXML private TextField serialField;
    @FXML private ComboBox<LookupOption> facilityBox;
    @FXML private ComboBox<Choice> conditionBox;
    @FXML private DatePicker acquiredPicker;
    @FXML private TextArea notesArea;

    private final LookupDao lookupDao = new LookupDao();

    @FXML
    private void initialize() {
        conditionBox.setItems(FXCollections.observableArrayList(
                new Choice("GOOD", Format.pretty("GOOD")),
                new Choice("FAIR", Format.pretty("FAIR")),
                new Choice("POOR", Format.pretty("POOR")),
                new Choice("NEEDS_REPAIR", Format.pretty("NEEDS_REPAIR")),
                new Choice("OUT_OF_ORDER", Format.pretty("OUT_OF_ORDER"))
        ));
        conditionBox.getSelectionModel().selectFirst();

        try {
            List<LookupOption> facilities = new ArrayList<>();
            facilities.add(NO_FACILITY);
            facilities.addAll(lookupDao.facilities());
            facilityBox.setItems(FXCollections.observableArrayList(facilities));
            facilityBox.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            showError("Could not load facilities: " + e.getMessage());
        }
    }

    public void setEquipment(Equipment e) {
        if (e == null) return;
        propertyNoField.setText(e.propertyNo());
        nameField.setText(e.name());
        categoryField.setText(e.category());
        brandField.setText(e.brand());
        modelField.setText(e.model());
        serialField.setText(e.serialNo());
        acquiredPicker.setValue(e.acquisitionDate());
        notesArea.setText(e.notes());

        for (Choice c : conditionBox.getItems()) {
            if (c.value().equals(e.condition())) { conditionBox.setValue(c); break; }
        }
        if (e.facilityId() == null) {
            facilityBox.getSelectionModel().select(NO_FACILITY);
        } else {
            for (LookupOption opt : facilityBox.getItems()) {
                if (opt.id() == e.facilityId()) { facilityBox.setValue(opt); break; }
            }
        }
    }

    public EquipmentDraft draft() {
        Choice cond = conditionBox.getValue();
        LookupOption fac = facilityBox.getValue();
        Integer facilityId = (fac == null || fac.id() == 0) ? null : fac.id();
        return new EquipmentDraft(
                propertyNoField.getText(),
                nameField.getText(),
                categoryField.getText(),
                brandField.getText(),
                modelField.getText(),
                serialField.getText(),
                facilityId,
                cond == null ? null : cond.value(),
                acquiredPicker.getValue(),
                notesArea.getText()
        );
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
