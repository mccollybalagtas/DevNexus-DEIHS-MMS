package com.deihs.mms.controller;

import com.deihs.mms.dao.EquipmentDao;
import com.deihs.mms.dao.LookupDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.Equipment;
import com.deihs.mms.model.EquipmentDraft;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.model.User;
import com.deihs.mms.service.EquipmentService;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.AssetTagDialog;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.CsvUtil;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EquipmentController {

    @FXML private Button addButton;
    @FXML private Button exportButton;
    @FXML private Button importButton;
    @FXML private TextField searchField;
    @FXML private ComboBox<Choice> conditionBox;
    @FXML private ComboBox<LookupOption> facilityBox;
    @FXML private CheckBox showArchivedBox;
    @FXML private Label countLabel;
    @FXML private TableView<Equipment> table;
    @FXML private TableColumn<Equipment, String> propertyCol;
    @FXML private TableColumn<Equipment, String> nameCol;
    @FXML private TableColumn<Equipment, String> categoryCol;
    @FXML private TableColumn<Equipment, String> brandCol;
    @FXML private TableColumn<Equipment, String> facilityCol;
    @FXML private TableColumn<Equipment, String> conditionCol;
    @FXML private TableColumn<Equipment, String> acquiredCol;
    @FXML private TableColumn<Equipment, Void> actionsCol;

    private final EquipmentDao equipmentDao = new EquipmentDao();
    private final LookupDao lookupDao = new LookupDao();
    private final EquipmentService equipmentService = new EquipmentService();
    private final ObservableList<Equipment> rows = FXCollections.observableArrayList();

    private int generation;
    private boolean canManage;

    @FXML
    private void initialize() {
        User user = Session.get();
        canManage = Permissions.canManageEquipment(user);
        addButton.setVisible(canManage);
        addButton.setManaged(canManage);
        importButton.setVisible(canManage);
        importButton.setManaged(canManage);

        setupFilters();
        setupTable();

        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> refresh());
        searchField.textProperty().addListener((obs, o, n) -> debounce.playFromStart());
        conditionBox.valueProperty().addListener((obs, o, n) -> refresh());
        facilityBox.valueProperty().addListener((obs, o, n) -> refresh());
        showArchivedBox.selectedProperty().addListener((obs, o, n) -> refresh());

        refresh();
    }

    private void setupFilters() {
        conditionBox.getItems().add(new Choice(null, "All conditions"));
        for (String c : List.of("GOOD", "FAIR", "POOR", "NEEDS_REPAIR", "OUT_OF_ORDER")) {
            conditionBox.getItems().add(new Choice(c, Format.pretty(c)));
        }
        conditionBox.getSelectionModel().selectFirst();

        try {
            List<LookupOption> facilities = new ArrayList<>();
            facilities.add(new LookupOption(0, "All facilities"));
            facilities.addAll(lookupDao.facilities());
            facilityBox.setItems(FXCollections.observableArrayList(facilities));
            facilityBox.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            facilityBox.setItems(FXCollections.observableArrayList(new LookupOption(0, "All facilities")));
            facilityBox.getSelectionModel().selectFirst();
        }
    }

    private void setupTable() {
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No equipment matches the current filters."));

        // Records expose accessors (propertyNo()), not JavaFX bean getters.
        propertyCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().propertyNo())));
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        categoryCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().category())));
        brandCol.setCellValueFactory(c -> {
            Equipment e = c.getValue();
            String bm = join(e.brand(), e.model());
            return new ReadOnlyStringWrapper(bm == null ? "—" : bm);
        });
        facilityCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().facilityName())));
        acquiredCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(Format.date(c.getValue().acquisitionDate())));

        conditionCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().condition()));
        conditionCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) { setGraphic(null); setText(null); return; }
                badge.getStyleClass().setAll("badge", conditionBadgeClass(value));
                badge.setText(Format.pretty(value));
                setGraphic(badge);
                setText(null);
            }
        });

        actionsCol.setVisible(true);
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button qr = new Button("QR Tag");
            private final Button edit = new Button("Edit");
            private final Button toggle = new Button();
            private final HBox box = new HBox(6);
            {
                box.getStyleClass().add("table-actions");
                qr.getStyleClass().addAll("btn-ghost", "btn-table");
                edit.getStyleClass().addAll("btn-ghost", "btn-table");
                toggle.getStyleClass().addAll("btn-ghost", "btn-table");
                qr.setOnAction(e -> {
                    Equipment eq = row();
                    if (eq != null) {
                        String location = eq.facilityName() == null ? "—" : eq.facilityName();
                        String details = join(eq.brand(), eq.model());
                        AssetTagDialog.show(ownerWindow(), "Equipment", eq.propertyNo(), eq.name(), location, details);
                    }
                });
                edit.setOnAction(e -> { Equipment eq = row(); if (eq != null) openForm(eq); });
                toggle.setOnAction(e -> { Equipment eq = row(); if (eq != null) toggleActive(eq); });
            }
            private Equipment row() {
                int i = getIndex();
                return (i < 0 || i >= getTableView().getItems().size()) ? null : getTableView().getItems().get(i);
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                Equipment eq = empty ? null : row();
                if (eq == null) { setGraphic(null); return; }
                box.getChildren().clear();
                box.getChildren().add(qr);
                if (canManage) {
                    box.getChildren().add(edit);
                    toggle.setText(eq.active() ? "Archive" : "Restore");
                    toggle.getStyleClass().removeAll("btn-warn");
                    if (eq.active()) toggle.getStyleClass().add("btn-warn");
                    box.getChildren().add(toggle);
                }
                setGraphic(box);
            }
        });
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    private void refresh() {
        final int req = ++generation;
        Choice cond = conditionBox.getValue();
        LookupOption fac = facilityBox.getValue();
        String condition = cond == null ? null : cond.value();
        Integer facilityId = (fac == null || fac.id() == 0) ? null : fac.id();
        String keyword = searchField.getText();
        boolean includeArchived = showArchivedBox.isSelected();

        Task<List<Equipment>> task = new Task<>() {
            @Override protected List<Equipment> call() throws Exception {
                return equipmentDao.search(keyword, condition, facilityId, includeArchived);
            }
        };
        task.setOnSucceeded(e -> {
            if (req != generation) return;
            List<Equipment> result = task.getValue();
            rows.setAll(result);
            countLabel.setText(result.size() + (result.size() == 1 ? " item" : " items"));
        });
        task.setOnFailed(e -> {
            if (req != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not load equipment",
                    ex == null ? "Unknown error." : ex.getMessage());
        });
        Thread t = new Thread(task, "load-equipment");
        t.setDaemon(true);
        t.start();
    }

    private void openForm(Equipment existing) {
        try {
            FXMLLoader loader = ViewLoader.loader("EquipmentFormView.fxml");
            Parent form = loader.load();
            ViewLoader.applyAppStyles(form);
            EquipmentFormController controller = loader.getController();
            if (existing != null) controller.setEquipment(existing);

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(ownerWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setTitle(existing == null ? "Add Equipment" : "Edit Equipment");
            dialog.setHeaderText(existing == null ? "Register new equipment."
                    : "Update " + existing.propertyNo() + " — " + existing.name() + ".");
            dialog.getDialogPane().setContent(form);
            dialog.getDialogPane().getStyleClass().add("form-dialog");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

            Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            ok.setText(existing == null ? "Add Equipment" : "Save Changes");
            ok.getStyleClass().add("btn-primary");
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).getStyleClass().add("btn-ghost");

            ok.addEventFilter(ActionEvent.ACTION, event -> {
                controller.clearError();
                EquipmentDraft draft = controller.draft();
                try {
                    User user = Session.get();
                    if (existing == null) equipmentService.create(draft, user);
                    else equipmentService.update(existing.id(), draft, user);
                } catch (ValidationException ve) {
                    controller.showError(ve.getMessage());
                    event.consume();
                } catch (SQLException se) {
                    AlertUtil.error(ownerWindow(), "Could not save equipment", se.getMessage());
                    event.consume();
                }
            });

            dialog.showAndWait().ifPresent(bt -> { if (bt == ButtonType.OK) refresh(); });
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Could not open form", ex.getMessage());
        }
    }

    private void toggleActive(Equipment eq) {
        boolean archiving = eq.active();
        String body = archiving
                ? "Archive " + eq.propertyNo() + " — " + eq.name() + "? It will be hidden from pickers but its history remains."
                : "Restore " + eq.propertyNo() + " — " + eq.name() + " to the inventory?";
        if (!AlertUtil.confirm(ownerWindow(), archiving ? "Archive equipment?" : "Restore equipment?", body)) return;
        try {
            equipmentService.setActive(eq.id(), eq.propertyNo(), !eq.active(), Session.get());
            refresh();
        } catch (ValidationException ve) {
            AlertUtil.error(ownerWindow(), "Not allowed", ve.getMessage());
        } catch (SQLException se) {
            AlertUtil.error(ownerWindow(), "Could not update equipment", se.getMessage());
        }
    }

    private static String conditionBadgeClass(String condition) {
        if (condition == null) return "badge-cancelled";
        return switch (condition) {
            case "GOOD" -> "badge-good";
            case "FAIR" -> "badge-fair";
            case "POOR" -> "badge-poor";
            case "NEEDS_REPAIR" -> "badge-needs-repair";
            case "OUT_OF_ORDER" -> "badge-out-of-order";
            default -> "badge-cancelled";
        };
    }

    private static String join(String a, String b) {
        String sa = a == null || a.isBlank() ? null : a.trim();
        String sb = b == null || b.isBlank() ? null : b.trim();
        if (sa == null && sb == null) return null;
        if (sa == null) return sb;
        if (sb == null) return sa;
        return sa + " " + sb;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Window ownerWindow() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }

    @FXML
    private void onExportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Equipment CSV");
        chooser.setInitialFileName("equipment.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) return;
        try {
            List<Equipment> all = equipmentDao.search(null, null, null, true);
            List<String> headers = List.of("property_no","name","category","brand","model","serial_no","facility_code","condition","acquisition_date","notes","active");
            List<List<String>> rows = new ArrayList<>();
            com.deihs.mms.dao.FacilityDao fdao = new com.deihs.mms.dao.FacilityDao();
            for (Equipment e : all) {
                String facCode = "";
                if (e.facilityId() != null && e.facilityId() > 0) {
                    try {
                        // facilityName is available, but CSV should use stable code; lookup via facility list is cheap enough here
                        for (com.deihs.mms.model.Facility f : fdao.search(null, null, true)) {
                            if (f.id() == e.facilityId()) { facCode = nz(f.code()); break; }
                        }
                    } catch (Exception ignored) {}
                }
                rows.add(List.of(nz(e.propertyNo()), nz(e.name()), nz(e.category()), nz(e.brand()), nz(e.model()), nz(e.serialNo()),
                        facCode, nz(e.condition()), e.acquisitionDate() == null ? "" : e.acquisitionDate().toString(), nz(e.notes()), e.active() ? "1" : "0"));
            }
            CsvUtil.write(file.toPath(), headers, rows);
            AlertUtil.info(ownerWindow(), "Export complete", "Exported " + rows.size() + " equipment items to\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            AlertUtil.error(ownerWindow(), "Export failed", ex.getMessage(), ex);
        }
    }

    @FXML
    private void onImportCsv() {
        if (!canManage) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Equipment CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = chooser.showOpenDialog(ownerWindow());
        if (file == null) return;
        try {
            List<Map<String, String>> in = CsvUtil.readWithHeaders(file.toPath());
            com.deihs.mms.dao.FacilityDao facilityDao = new com.deihs.mms.dao.FacilityDao();
            int created = 0, updated = 0;
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                int rowNum = i + 2;
                Map<String, String> r = in.get(i);
                try {
                    String propertyNo = val(r, "property_no");
                    String name = val(r, "name");
                    if (propertyNo.isBlank() || name.isBlank()) {
                        errors.add("Row " + rowNum + ": property_no and name are required.");
                        continue;
                    }
                    Integer facilityId = null;
                    String facilityCode = val(r, "facility_code");
                    if (!facilityCode.isBlank()) {
                        com.deihs.mms.model.Facility fac = facilityDao.findByCode(facilityCode);
                        if (fac == null) {
                            errors.add("Row " + rowNum + ": facility_code '" + facilityCode + "' not found.");
                            continue;
                        }
                        facilityId = fac.id();
                    }
                    LocalDate acq = null;
                    String acqS = val(r, "acquisition_date");
                    if (!acqS.isBlank()) {
                        try { acq = LocalDate.parse(acqS); } catch (Exception e) { errors.add("Row " + rowNum + ": invalid acquisition_date (use YYYY-MM-DD)."); continue; }
                    }
                    EquipmentDraft draft = new EquipmentDraft(propertyNo, name, blankToNull(val(r,"category")),
                            blankToNull(val(r,"brand")), blankToNull(val(r,"model")), blankToNull(val(r,"serial_no")),
                            facilityId, defaultCondition(val(r,"condition")), acq, blankToNull(val(r,"notes")));
                    Equipment existing = equipmentDao.findByPropertyNo(propertyNo);
                    User user = Session.get();
                    if (existing == null) { equipmentService.create(draft, user); created++; }
                    else { equipmentService.update(existing.id(), draft, user); updated++; }
                    Equipment after = equipmentDao.findByPropertyNo(propertyNo);
                    if (after != null) {
                        String active = val(r, "active");
                        if (!active.isBlank()) {
                            boolean wantActive = !"0".equals(active) && !"false".equalsIgnoreCase(active);
                            if (after.active() != wantActive) equipmentService.setActive(after.id(), after.propertyNo(), wantActive, user);
                        }
                    }
                } catch (Exception ex) {
                    errors.add("Row " + rowNum + ": " + ex.getMessage());
                }
            }
            refresh();
            showImportResult("Equipment", created, updated, errors);
        } catch (Exception ex) {
            AlertUtil.error(ownerWindow(), "Import failed", ex.getMessage(), ex);
        }
    }

    private void showImportResult(String what, int created, int updated, List<String> errors) {
        StringBuilder msg = new StringBuilder();
        msg.append("Created: ").append(created).append("\nUpdated: ").append(updated);
        if (!errors.isEmpty()) {
            msg.append("\n\nErrors (").append(errors.size()).append("):\n");
            for (int i = 0; i < Math.min(errors.size(), 12); i++) msg.append("- ").append(errors.get(i)).append("\n");
            if (errors.size() > 12) msg.append("- ...").append(errors.size() - 12).append(" more");
            AlertUtil.warn(ownerWindow(), what + " import finished with errors", msg.toString());
        } else {
            AlertUtil.info(ownerWindow(), what + " import complete", msg.toString());
        }
    }

    private static String nz(String v) { return v == null ? "" : v; }
    private static String val(Map<String, String> r, String key) {
        String v = r.get(key);
        if (v == null) for (Map.Entry<String, String> e : r.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) { v = e.getValue(); break; }
        return v == null ? "" : v.trim();
    }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static String defaultCondition(String v) {
        if (v == null || v.isBlank()) return "GOOD";
        String u = v.trim().toUpperCase().replace(' ', '_');
        return switch (u) { case "GOOD","FAIR","POOR","NEEDS_REPAIR","OUT_OF_ORDER" -> u; default -> "GOOD"; };
    }
}
