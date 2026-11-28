package com.deihs.mms.controller;

import com.deihs.mms.dao.FacilityDao;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.Facility;
import com.deihs.mms.model.FacilityDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.service.FacilityService;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.AssetTagDialog;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.CsvUtil;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Facility registry: search, filter by condition, add/edit/archive/restore.
 * Admin and Maintenance Head can manage; others would not reach this screen.
 */
public class FacilitiesController {

    @FXML private Button addButton;
    @FXML private Button exportButton;
    @FXML private Button importButton;
    @FXML private TextField searchField;
    @FXML private ComboBox<Choice> conditionBox;
    @FXML private CheckBox showArchivedBox;
    @FXML private Label countLabel;
    @FXML private TableView<Facility> table;
    @FXML private TableColumn<Facility, String> codeCol;
    @FXML private TableColumn<Facility, String> nameCol;
    @FXML private TableColumn<Facility, String> buildingCol;
    @FXML private TableColumn<Facility, String> floorCol;
    @FXML private TableColumn<Facility, String> roomCol;
    @FXML private TableColumn<Facility, String> typeCol;
    @FXML private TableColumn<Facility, String> conditionCol;
    @FXML private TableColumn<Facility, Integer> equipmentCol;
    @FXML private TableColumn<Facility, Integer> openCol;
    @FXML private TableColumn<Facility, Void> actionsCol;

    private final FacilityDao facilityDao = new FacilityDao();
    private final FacilityService facilityService = new FacilityService();
    private final ObservableList<Facility> rows = FXCollections.observableArrayList();

    private int generation;
    private boolean canManage;

    @FXML
    private void initialize() {
        User user = Session.get();
        canManage = Permissions.canManageFacilities(user);
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
        showArchivedBox.selectedProperty().addListener((obs, o, n) -> refresh());

        refresh();
    }

    private void setupFilters() {
        conditionBox.getItems().add(new Choice(null, "All conditions"));
        for (String c : List.of("GOOD", "FAIR", "POOR", "NEEDS_REPAIR", "OUT_OF_ORDER")) {
            conditionBox.getItems().add(new Choice(c, Format.pretty(c)));
        }
        conditionBox.getSelectionModel().selectFirst();
        showArchivedBox.setSelected(false);
    }

    private void setupTable() {
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No facilities match the current filters."));

        // Records expose accessors (code()), not JavaFX bean getters, so the
        // columns use lambdas instead of PropertyValueFactory.
        codeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().code())));
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        buildingCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().building())));
        floorCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().floor())));
        roomCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().roomNumber())));
        typeCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().type())));
        equipmentCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().equipmentCount()));
        openCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().openRequests()));

        conditionCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().condition()));
        conditionCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                badge.getStyleClass().setAll("badge", conditionBadgeClass(value));
                badge.setText(Format.pretty(value));
                setGraphic(badge);
                setText(null);
            }
        });

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
                    Facility f = rowFacility();
                    if (f != null) {
                        AssetTagDialog.show(ownerWindow(), "Facility", f.code(), f.name(),
                                String.join(" / ", java.util.stream.Stream.of(f.building(), f.floor(), f.roomNumber())
                                        .filter(v -> v != null && !v.isBlank()).toList()),
                                f.type());
                    }
                });
                edit.setOnAction(e -> {
                    Facility f = rowFacility();
                    if (f != null) openForm(f);
                });
                toggle.setOnAction(e -> {
                    Facility f = rowFacility();
                    if (f != null) toggleActive(f);
                });
            }

            private Facility rowFacility() {
                int i = getIndex();
                if (i < 0 || i >= getTableView().getItems().size()) return null;
                return getTableView().getItems().get(i);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                Facility f = rowFacility();
                if (f == null) {
                    setGraphic(null);
                    return;
                }
                box.getChildren().clear();
                box.getChildren().add(qr);
                if (canManage) {
                    box.getChildren().add(edit);
                    toggle.setText(f.active() ? "Archive" : "Restore");
                    toggle.getStyleClass().removeAll("btn-warn");
                    if (f.active()) {
                        toggle.getStyleClass().add("btn-warn");
                    }
                    box.getChildren().add(toggle);
                }
                setGraphic(box);
            }
        });

        actionsCol.setVisible(true);
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    private void refresh() {
        final int request = ++generation;
        Choice cond = conditionBox.getValue();
        String condition = cond == null ? null : cond.value();
        String keyword = searchField.getText();
        boolean includeArchived = showArchivedBox.isSelected();

        Task<List<Facility>> task = new Task<>() {
            @Override
            protected List<Facility> call() throws Exception {
                return facilityDao.search(keyword, condition, includeArchived);
            }
        };

        task.setOnSucceeded(e -> {
            if (request != generation) return;
            List<Facility> result = task.getValue();
            rows.setAll(result);
            countLabel.setText(result.size() + (result.size() == 1 ? " facility" : " facilities"));
        });

        task.setOnFailed(e -> {
            if (request != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not load facilities",
                    ex == null ? "Unknown error." : ex.getMessage());
        });

        Thread t = new Thread(task, "load-facilities");
        t.setDaemon(true);
        t.start();
    }

    private void openForm(Facility existing) {
        try {
            FXMLLoader loader = ViewLoader.loader("FacilityFormView.fxml");
            Parent form = loader.load();
            ViewLoader.applyAppStyles(form);
            FacilityFormController controller = loader.getController();
            if (existing != null) {
                controller.setFacility(existing);
            }

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(ownerWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setTitle(existing == null ? "Add Facility" : "Edit Facility");
            dialog.setHeaderText(existing == null
                    ? "Register a new campus facility."
                    : "Update " + existing.code() + " — " + existing.name() + ".");
            dialog.getDialogPane().setContent(form);
            dialog.getDialogPane().getStyleClass().add("form-dialog");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

            Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            ok.setText(existing == null ? "Add Facility" : "Save Changes");
            ok.getStyleClass().add("btn-primary");
            Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
            cancel.getStyleClass().add("btn-ghost");

            ok.addEventFilter(ActionEvent.ACTION, event -> {
                controller.clearError();
                FacilityDraft draft = controller.draft();
                try {
                    User user = Session.get();
                    if (existing == null) {
                        facilityService.create(draft, user);
                    } else {
                        facilityService.update(existing.id(), draft, user);
                    }
                } catch (ValidationException ve) {
                    controller.showError(ve.getMessage());
                    event.consume();
                } catch (SQLException se) {
                    AlertUtil.error(ownerWindow(), "Could not save facility", se.getMessage());
                    event.consume();
                }
            });

            dialog.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    refresh();
                }
            });
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Could not open form", ex.getMessage());
        }
    }

    private void toggleActive(Facility f) {
        boolean archiving = f.active();
        String title = archiving ? "Archive facility?" : "Restore facility?";
        String body = archiving
                ? "Archive " + f.code() + " — " + f.name() + "? It will be hidden from pickers but its history remains."
                : "Restore " + f.code() + " — " + f.name() + " to the active registry?";
        boolean confirmed = AlertUtil.confirm(ownerWindow(), title, body);
        if (!confirmed) return;

        try {
            facilityService.setActive(f.id(), f.code(), !f.active(), Session.get());
            refresh();
        } catch (ValidationException ve) {
            AlertUtil.error(ownerWindow(), "Not allowed", ve.getMessage());
        } catch (SQLException se) {
            AlertUtil.error(ownerWindow(), "Could not update facility", se.getMessage());
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

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Window ownerWindow() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }

    @FXML
    private void onExportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Facilities CSV");
        chooser.setInitialFileName("facilities.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) return;
        try {
            List<Facility> all = facilityDao.search(null, null, true);
            List<String> headers = List.of("code","name","building","floor","room_number","type","condition","notes","active");
            List<List<String>> rows = new ArrayList<>();
            for (Facility f : all) {
                rows.add(List.of(
                        nz(f.code()), nz(f.name()), nz(f.building()), nz(f.floor()), nz(f.roomNumber()),
                        nz(f.type()), nz(f.condition()), nz(f.notes()), f.active() ? "1" : "0"));
            }
            CsvUtil.write(file.toPath(), headers, rows);
            AlertUtil.info(ownerWindow(), "Export complete", "Exported " + rows.size() + " facilities to\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            AlertUtil.error(ownerWindow(), "Export failed", ex.getMessage(), ex);
        }
    }

    @FXML
    private void onImportCsv() {
        if (!canManage) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Facilities CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = chooser.showOpenDialog(ownerWindow());
        if (file == null) return;
        try {
            List<Map<String, String>> in = CsvUtil.readWithHeaders(file.toPath());
            int created = 0, updated = 0;
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                int rowNum = i + 2;
                Map<String, String> r = in.get(i);
                try {
                    String code = val(r, "code");
                    String name = val(r, "name");
                    if (code.isBlank() || name.isBlank()) {
                        errors.add("Row " + rowNum + ": code and name are required.");
                        continue;
                    }
                    FacilityDraft draft = new FacilityDraft(
                            code, name,
                            val(r, "building"), val(r, "floor"), val(r, "room_number"),
                            blankToNull(val(r, "type")),
                            defaultCondition(val(r, "condition")),
                            blankToNull(val(r, "notes")));
                    Facility existing = facilityDao.findByCode(code);
                    User user = Session.get();
                    if (existing == null) {
                        facilityService.create(draft, user);
                        created++;
                    } else {
                        facilityService.update(existing.id(), draft, user);
                        updated++;
                    }
                    Facility after = facilityDao.findByCode(code);
                    if (after != null) {
                        String active = val(r, "active");
                        if (!active.isBlank()) {
                            boolean wantActive = !"0".equals(active) && !"false".equalsIgnoreCase(active);
                            if (after.active() != wantActive) {
                                facilityService.setActive(after.id(), after.code(), wantActive, user);
                            }
                        }
                    }
                } catch (Exception ex) {
                    errors.add("Row " + rowNum + ": " + ex.getMessage());
                }
            }
            refresh();
            showImportResult("Facilities", created, updated, errors);
        } catch (Exception ex) {
            AlertUtil.error(ownerWindow(), "Import failed", ex.getMessage(), ex);
        }
    }

    private void showImportResult(String what, int created, int updated, List<String> errors) {
        StringBuilder msg = new StringBuilder();
        msg.append("Created: ").append(created).append("\nUpdated: ").append(updated);
        if (!errors.isEmpty()) {
            msg.append("\n\nErrors (").append(errors.size()).append("):\n");
            for (int i = 0; i < Math.min(errors.size(), 12); i++) {
                msg.append("- ").append(errors.get(i)).append("\n");
            }
            if (errors.size() > 12) msg.append("- ...").append(errors.size() - 12).append(" more");
            AlertUtil.warn(ownerWindow(), what + " import finished with errors", msg.toString());
        } else {
            AlertUtil.info(ownerWindow(), what + " import complete", msg.toString());
        }
    }

    private static String nz(String v) { return v == null ? "" : v; }
    private static String val(Map<String, String> r, String key) {
        String v = r.get(key);
        if (v == null) {
            // try case-insensitive
            for (Map.Entry<String, String> e : r.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) { v = e.getValue(); break; }
            }
        }
        return v == null ? "" : v.trim();
    }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static String defaultCondition(String v) {
        if (v == null || v.isBlank()) return "GOOD";
        String u = v.trim().toUpperCase().replace(' ', '_');
        return switch (u) {
            case "GOOD","FAIR","POOR","NEEDS_REPAIR","OUT_OF_ORDER" -> u;
            default -> "GOOD";
        };
    }
}
