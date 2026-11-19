package com.deihs.mms.controller;

import com.deihs.mms.dao.LookupDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.Schedule;
import com.deihs.mms.model.ScheduleDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.util.Format;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ScheduleFormController {

    private static final LookupOption NONE_FACILITY = new LookupOption(0, "— No facility —");
    private static final LookupOption NONE_EQUIPMENT = new LookupOption(0, "— No equipment —");
    private static final LookupOption NONE_ASSIGNEE = new LookupOption(0, "— Unassigned —");

    @FXML private Label errorLabel;
    @FXML private TextField titleField;
    @FXML private ComboBox<LookupOption> facilityBox;
    @FXML private ComboBox<LookupOption> equipmentBox;
    @FXML private ComboBox<Choice> frequencyBox;
    @FXML private DatePicker nextDuePicker;
    @FXML private ComboBox<LookupOption> assigneeBox;
    @FXML private TextArea descriptionArea;

    private final LookupDao lookupDao = new LookupDao();
    private final UserDao userDao = new UserDao();

    @FXML
    private void initialize() {
        frequencyBox.setItems(FXCollections.observableArrayList(
                new Choice("DAILY", "Daily"),
                new Choice("WEEKLY", "Weekly"),
                new Choice("MONTHLY", "Monthly"),
                new Choice("QUARTERLY", "Quarterly"),
                new Choice("SEMI_ANNUAL", "Semi-annual"),
                new Choice("ANNUAL", "Annual")
        ));
        frequencyBox.getSelectionModel().select(2);
        nextDuePicker.setValue(LocalDate.now().plusWeeks(1));

        try {
            List<LookupOption> facilities = new ArrayList<>();
            facilities.add(NONE_FACILITY);
            facilities.addAll(lookupDao.facilities());
            facilityBox.setItems(FXCollections.observableArrayList(facilities));
            facilityBox.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            facilityBox.setItems(FXCollections.observableArrayList(NONE_FACILITY));
        }

        try {
            List<LookupOption> equipment = new ArrayList<>();
            equipment.add(NONE_EQUIPMENT);
            equipment.addAll(lookupDao.equipment(null));
            equipmentBox.setItems(FXCollections.observableArrayList(equipment));
            equipmentBox.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            equipmentBox.setItems(FXCollections.observableArrayList(NONE_EQUIPMENT));
        }

        try {
            List<LookupOption> assignees = new ArrayList<>();
            assignees.add(NONE_ASSIGNEE);
            for (User u : userDao.findByRole(Role.PERSONNEL)) {
                assignees.add(new LookupOption(u.id(), u.fullName()));
            }
            assigneeBox.setItems(FXCollections.observableArrayList(assignees));
            assigneeBox.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            assigneeBox.setItems(FXCollections.observableArrayList(NONE_ASSIGNEE));
        }
    }

    public void setSchedule(Schedule s) {
        if (s == null) return;
        titleField.setText(s.title());
        descriptionArea.setText(s.description());
        nextDuePicker.setValue(s.nextDueDate());
        selectById(facilityBox, s.facilityId());
        selectById(equipmentBox, s.equipmentId());
        selectById(assigneeBox, s.assignedTo());
        for (Choice c : frequencyBox.getItems()) {
            if (c.value().equals(s.frequency())) { frequencyBox.setValue(c); break; }
        }
    }

    private void selectById(ComboBox<LookupOption> box, Integer id) {
        if (id == null) { box.getSelectionModel().selectFirst(); return; }
        for (LookupOption opt : box.getItems()) {
            if (opt.id() == id) { box.setValue(opt); return; }
        }
    }

    public ScheduleDraft draft() {
        Choice freq = frequencyBox.getValue();
        return new ScheduleDraft(
                idOf(facilityBox),
                idOf(equipmentBox),
                titleField.getText(),
                descriptionArea.getText(),
                freq == null ? null : freq.value(),
                nextDuePicker.getValue(),
                idOf(assigneeBox)
        );
    }

    private Integer idOf(ComboBox<LookupOption> box) {
        LookupOption opt = box.getValue();
        return (opt == null || opt.id() == 0) ? null : opt.id();
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
