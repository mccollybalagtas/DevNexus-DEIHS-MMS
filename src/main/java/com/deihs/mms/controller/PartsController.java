package com.deihs.mms.controller;

import com.deihs.mms.dao.PartDao;
import com.deihs.mms.model.Part;
import com.deihs.mms.model.PartDraft;
import com.deihs.mms.model.User;
import com.deihs.mms.service.PartService;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
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
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/**
 * Parts inventory: search, filter low stock, add/edit/archive/restore.
 * Admin and Maintenance Head can manage stock; personnel consume parts from
 * the request detail screen.
 */
public class PartsController {

    @FXML private Button addButton;
    @FXML private TextField searchField;
    @FXML private CheckBox lowOnlyBox;
    @FXML private CheckBox showArchivedBox;
    @FXML private Label countLabel;
    @FXML private TableView<Part> table;
    @FXML private TableColumn<Part, String> nameCol;
    @FXML private TableColumn<Part, String> skuCol;
    @FXML private TableColumn<Part, String> unitCol;
    @FXML private TableColumn<Part, Integer> stockCol;
    @FXML private TableColumn<Part, Integer> reorderCol;
    @FXML private TableColumn<Part, String> costCol;
    @FXML private TableColumn<Part, String> valueCol;
    @FXML private TableColumn<Part, String> statusCol;
    @FXML private TableColumn<Part, Void> actionsCol;

    private final PartDao partDao = new PartDao();
    private final PartService partService = new PartService();
    private final ObservableList<Part> rows = FXCollections.observableArrayList();

    private int generation;
    private boolean canManage;

    @FXML
    private void initialize() {
        User user = Session.get();
        canManage = Permissions.canManageParts(user);
        addButton.setVisible(canManage);
        addButton.setManaged(canManage);

        setupTable();

        PauseTransition debounce = new PauseTransition(Duration.millis(250));
        debounce.setOnFinished(e -> refresh());
        searchField.textProperty().addListener((obs, o, n) -> debounce.playFromStart());
        lowOnlyBox.selectedProperty().addListener((obs, o, n) -> refresh());
        showArchivedBox.selectedProperty().addListener((obs, o, n) -> refresh());

        refresh();
    }

    private void setupTable() {
        table.setItems(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No parts match the current filters."));

        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().name()));
        skuCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(blank(c.getValue().sku())));
        unitCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().unit()));
        stockCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().quantity()));
        reorderCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().reorderLevel()));
        costCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                Format.peso(c.getValue().unitCost() == null ? BigDecimal.ZERO : c.getValue().unitCost())));
        valueCol.setCellValueFactory(c -> {
            BigDecimal cost = c.getValue().unitCost() == null ? BigDecimal.ZERO : c.getValue().unitCost();
            return new ReadOnlyStringWrapper(
                    Format.peso(cost.multiply(BigDecimal.valueOf(c.getValue().quantity()))));
        });

        stockCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Integer value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.valueOf(value));
                getStyleClass().remove("stock-low-text");
                if (!empty && value != null) {
                    int i = getIndex();
                    if (i >= 0 && i < getTableView().getItems().size()) {
                        Part p = getTableView().getItems().get(i);
                        if (p != null && p.lowStock()) {
                            getStyleClass().add("stock-low-text");
                        }
                    }
                }
            }
        });

        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().active()
                        ? (c.getValue().lowStock() ? "Low stock" : "In stock")
                        : "Archived"));
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Label badge = new Label();
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                badge.getStyleClass().setAll("badge", switch (value) {
                    case "Low stock" -> "badge-poor";
                    case "In stock" -> "badge-good";
                    default -> "badge-cancelled";
                });
                badge.setText(value);
                setGraphic(badge);
                setText(null);
            }
        });

        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button edit = new Button("Edit");
            private final Button toggle = new Button();
            private final HBox box = new HBox(6, edit, toggle);

            {
                box.getStyleClass().add("table-actions");
                edit.getStyleClass().addAll("btn-ghost", "btn-table");
                toggle.getStyleClass().addAll("btn-ghost", "btn-table");
                edit.setOnAction(e -> {
                    Part p = rowPart();
                    if (p != null) openForm(p);
                });
                toggle.setOnAction(e -> {
                    Part p = rowPart();
                    if (p != null) toggleActive(p);
                });
            }

            private Part rowPart() {
                int i = getIndex();
                if (i < 0 || i >= getTableView().getItems().size()) return null;
                return getTableView().getItems().get(i);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || !canManage) {
                    setGraphic(null);
                    return;
                }
                Part p = rowPart();
                if (p == null) {
                    setGraphic(null);
                    return;
                }
                toggle.setText(p.active() ? "Archive" : "Restore");
                toggle.getStyleClass().removeAll("btn-warn");
                if (p.active()) {
                    toggle.getStyleClass().add("btn-warn");
                }
                setGraphic(box);
            }
        });

        actionsCol.setVisible(canManage);
    }

    @FXML
    private void onAdd() {
        openForm(null);
    }

    private void refresh() {
        final int request = ++generation;
        String keyword = searchField.getText();
        boolean lowOnly = lowOnlyBox.isSelected();
        boolean includeArchived = showArchivedBox.isSelected();

        Task<List<Part>> task = new Task<>() {
            @Override
            protected List<Part> call() throws Exception {
                return partDao.search(keyword, includeArchived, lowOnly);
            }
        };

        task.setOnSucceeded(e -> {
            if (request != generation) return;
            List<Part> result = task.getValue();
            rows.setAll(result);
            long low = result.stream().filter(Part::lowStock).filter(Part::active).count();
            String suffix = low > 0 ? " · " + low + " low on stock" : "";
            countLabel.setText(result.size() + (result.size() == 1 ? " part" : " parts") + suffix);
        });

        task.setOnFailed(e -> {
            if (request != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not load parts",
                    ex == null ? "Unknown error." : ex.getMessage());
        });

        Thread t = new Thread(task, "load-parts");
        t.setDaemon(true);
        t.start();
    }

    private void openForm(Part existing) {
        try {
            FXMLLoader loader = ViewLoader.loader("PartFormView.fxml");
            Parent form = loader.load();
            ViewLoader.applyAppStyles(form);
            PartFormController controller = loader.getController();
            if (existing != null) {
                controller.setPart(existing);
            }

            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(ownerWindow());
            dialog.initModality(Modality.WINDOW_MODAL);
            dialog.setTitle(existing == null ? "Add Part" : "Edit Part");
            dialog.setHeaderText(existing == null
                    ? "Add a part or consumable to the inventory."
                    : "Update " + existing.name() + ".");
            dialog.getDialogPane().setContent(form);
            dialog.getDialogPane().getStyleClass().add("form-dialog");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);

            Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            ok.setText(existing == null ? "Add Part" : "Save Changes");
            ok.getStyleClass().add("btn-primary");
            Button cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
            cancel.getStyleClass().add("btn-ghost");

            ok.addEventFilter(ActionEvent.ACTION, event -> {
                controller.clearError();
                PartDraft draft;
                try {
                    draft = controller.draft();
                } catch (NumberFormatException nfe) {
                    controller.showError("Quantity, reorder level, and unit cost must be numbers.");
                    event.consume();
                    return;
                }
                try {
                    User user = Session.get();
                    if (existing == null) {
                        partService.create(draft, user);
                    } else {
                        partService.update(existing.id(), draft, user);
                    }
                } catch (ValidationException ve) {
                    controller.showError(ve.getMessage());
                    event.consume();
                } catch (SQLException se) {
                    AlertUtil.error(ownerWindow(), "Could not save part", se.getMessage());
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

    private void toggleActive(Part p) {
        boolean archiving = p.active();
        String title = archiving ? "Archive part?" : "Restore part?";
        String body = archiving
                ? "Archive " + p.name() + "? It will be hidden from the picker but past usage stays intact."
                : "Restore " + p.name() + " to the active inventory?";
        if (!AlertUtil.confirm(ownerWindow(), title, body)) {
            return;
        }
        try {
            partService.setActive(p.id(), p.name(), !p.active(), Session.get());
            refresh();
        } catch (ValidationException ve) {
            AlertUtil.error(ownerWindow(), "Not allowed", ve.getMessage());
        } catch (SQLException se) {
            AlertUtil.error(ownerWindow(), "Could not update part", se.getMessage());
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Window ownerWindow() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }
}
