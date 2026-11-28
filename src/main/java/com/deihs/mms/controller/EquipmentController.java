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
import javafx.stage.Modality;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class EquipmentController {

    @FXML private Button addButton;
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
}
