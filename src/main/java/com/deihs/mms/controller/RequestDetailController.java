package com.deihs.mms.controller;

import com.deihs.mms.dao.PartDao;
import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.AttachmentEntry;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.CommentEntry;
import com.deihs.mms.model.HistoryEntry;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.model.Part;
import com.deihs.mms.model.RequestDetail;
import com.deihs.mms.model.RequestPart;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.service.PartService;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.RequestDetailService;
import com.deihs.mms.service.Session;
import com.deihs.mms.service.StatusFlow;
import com.deihs.mms.service.ValidationException;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import com.deihs.mms.util.RequestCells;
import com.deihs.mms.util.ViewLoader;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * The read-heavy detail screen for one maintenance request: information,
 * status timeline, comments, attachments, and the actions the signed-in role
 * is allowed to take.
 *
 * <p>The whole screen is loaded as one background bundle so the timeline and
 * comments always describe the same version of the request. Writes re-check
 * every rule in {@link RequestDetailService}; this controller only decides
 * which buttons to show.</p>
 */
public class RequestDetailController {

    /** Statuses that can still be overdue. Mirrors the request list. */
    private static final Set<String> ACTIVE =
            Set.of(StatusFlow.PENDING, StatusFlow.ASSIGNED,
                    StatusFlow.IN_PROGRESS, StatusFlow.ON_HOLD);

    @FXML private ScrollPane pageScroll;
    @FXML private Button backButton;
    @FXML private Label titleLabel;
    @FXML private Label statusBadge;
    @FXML private Label overdueBadge;
    @FXML private Label loadingLabel;
    @FXML private VBox kvBox;
    @FXML private Label descriptionLabel;
    @FXML private HBox actionBox;
    @FXML private VBox attachmentBox;
    @FXML private VBox timelineBox;
    @FXML private VBox commentBox;
    @FXML private Label commentError;
    @FXML private TextArea commentArea;
    @FXML private Button postButton;
    @FXML private VBox partsBox;
    @FXML private Label partsCostLabel;
    @FXML private Button addPartButton;
    @FXML private VBox checklistBox;
    @FXML private Button applyChecklistButton;
    @FXML private Button addAttachmentButton;

    private final RequestDetailService service = new RequestDetailService();
    private final PartService partService = new PartService();
    private final PartDao partDao = new PartDao();
    private final UserDao userDao = new UserDao();
    private final com.deihs.mms.service.ChecklistService checklistService = new com.deihs.mms.service.ChecklistService();

    private User user;
    private int requestId;
    private Runnable backHandler;
    private RequestDetail detail;
    private List<LookupOption> personnel = List.of();
    /** Ignore a slow reply that arrives after a newer load has started. */
    private int generation;

    /** Everything one request needs, fetched together off the FX thread. */
    private record DetailBundle(
            RequestDetail detail,
            List<HistoryEntry> history,
            List<CommentEntry> comments,
            List<AttachmentEntry> attachments,
            List<LookupOption> personnel,
            BigDecimal repairCost,
            List<RequestPart> parts,
            BigDecimal partsCost,
            List<com.deihs.mms.model.RequestChecklist> checklists
    ) {
    }

    @FXML
    private void initialize() {
        user = Session.get();
        clearError();
    }

    public void setBackHandler(Runnable handler) {
        this.backHandler = handler;
    }

    public void show(int requestId) {
        this.requestId = requestId;
        load();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private void load() {
        int mine = ++generation;

        titleLabel.setText("Loading request…");
        statusBadge.setVisible(false);
        statusBadge.setManaged(false);
        overdueBadge.setVisible(false);
        overdueBadge.setManaged(false);
        loadingLabel.setVisible(true);
        loadingLabel.setManaged(true);

        Task<DetailBundle> task = new Task<>() {
            @Override
            protected DetailBundle call() throws Exception {
                RequestDetail d = service.load(requestId, user);
                List<LookupOption> people = Permissions.canAssignRequests(user)
                        ? userDao.findByRole(Role.PERSONNEL).stream()
                                .map(u -> new LookupOption(u.id(), u.fullName()))
                                .toList()
                        : List.of();
                return new DetailBundle(
                        d,
                        service.history(requestId),
                        service.comments(requestId),
                        service.attachments(requestId),
                        people,
                        service.repairCost(requestId),
                        partService.partsForRequest(requestId),
                        partService.partsCost(requestId),
                        checklistService.requestChecklists(requestId));
            }
        };

        task.setOnSucceeded(e -> {
            if (mine != generation) {
                return;
            }
            DetailBundle bundle = task.getValue();
            personnel = bundle.personnel();
            render(bundle);
        });

        task.setOnFailed(e -> {
            if (mine != generation) {
                return;
            }
            loadingLabel.setVisible(false);
            loadingLabel.setManaged(false);
            Throwable ex = task.getException();

            if (ex instanceof ValidationException) {
                AlertUtil.warn(window(), "Request unavailable", ex.getMessage());
            } else {
                AlertUtil.error(window(), "Could not open the request",
                        "The request details could not be read from the database.", ex);
            }
            goBack();
        });

        Thread thread = new Thread(task, "request-detail-load");
        thread.setDaemon(true);
        thread.start();
    }

    private void render(DetailBundle bundle) {
        detail = bundle.detail();

        loadingLabel.setVisible(false);
        loadingLabel.setManaged(false);

        titleLabel.setText(detail.ticketNo() + " · " + detail.title());

        statusBadge.setText(Format.pretty(detail.status()));
        statusBadge.getStyleClass().setAll("badge", RequestCells.badgeClass(detail.status()));
        statusBadge.setVisible(true);
        statusBadge.setManaged(true);

        boolean overdue = detail.overdue() && ACTIVE.contains(detail.status());
        overdueBadge.setVisible(overdue);
        overdueBadge.setManaged(overdue);

        renderInfo(detail, bundle.repairCost());
        renderActions(detail, bundle.repairCost());
        renderParts(bundle.parts(), bundle.partsCost());
        renderChecklists(bundle.checklists());
        renderTimeline(bundle.history(), detail);
        renderComments(bundle.comments());
        renderAttachments(bundle.attachments());
    }

    // ------------------------------------------------------------------
    // Information
    // ------------------------------------------------------------------

    private void renderInfo(RequestDetail d, BigDecimal repairCost) {
        kvBox.getChildren().clear();

        kvRow("Reported by", d.requester());
        kvRow("Facility", d.facility());
        if (d.equipment() != null) {
            kvRow("Equipment", d.equipment());
        }
        kvRow("Category", d.category());

        Label priority = valueLabel(Format.pretty(d.priority()));
        priority.getStyleClass().add("priority-" + d.priority().toLowerCase());
        kvRow("Priority", priority);

        kvRow("Reported", Format.dateTime(d.reportedAt()));
        kvRow("Target date", Format.date(d.targetDate()));
        kvRow("Assigned to", d.assignee() == null ? "— Unassigned —" : d.assignee());

        if (d.completedAt() != null) {
            kvRow("Completed", Format.dateTime(d.completedAt()));
        }
        if (d.closedAt() != null) {
            kvRow("Closed", Format.dateTime(d.closedAt()));
        }
        if (d.resolutionNotes() != null) {
            kvRow("Resolution", d.resolutionNotes());
        }
        kvRow("Repair cost", Format.peso(repairCost == null ? BigDecimal.ZERO : repairCost));
        String ap = d.normalizedApprovalStatus();
        if (!"NOT_REQUIRED".equals(ap)) {
            String approvalText = Format.pretty(ap);
            if (d.approvedByName() != null) approvalText += " by " + d.approvedByName();
            if (d.approvedAt() != null) approvalText += " on " + Format.dateTime(d.approvedAt());
            if (d.approvalCost() != null) approvalText += " (cost " + Format.peso(d.approvalCost()) + ")";
            kvRow("High-cost approval", approvalText);
            if (d.approvalNotes() != null && !d.approvalNotes().isBlank()) {
                kvRow("Approval notes", d.approvalNotes());
            }
        }

        descriptionLabel.setText(d.description() == null || d.description().isBlank()
                ? "No description was provided."
                : d.description());
    }

    private void kvRow(String key, String value) {
        kvRow(key, valueLabel(value == null || value.isBlank() ? "—" : value));
    }

    private void kvRow(String key, Label value) {
        Label k = new Label(key);
        k.getStyleClass().add("kv-key");
        k.setMinWidth(108);
        k.setPrefWidth(108);

        HBox row = new HBox(8, k, value);
        row.getStyleClass().add("kv-row");
        row.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(value, Priority.ALWAYS);
        kvBox.getChildren().add(row);
    }

    private static Label valueLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("kv-value");
        label.setWrapText(true);
        return label;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void renderActions(RequestDetail d, BigDecimal repairCost) {
        actionBox.getChildren().clear();

        if (Permissions.canAssignRequests(user)
                && (StatusFlow.PENDING.equals(d.status())
                    || StatusFlow.ASSIGNED.equals(d.status()))) {
            Button assign = new Button(d.assignee() == null ? "Assign" : "Reassign");
            assign.getStyleClass().add("btn-primary");
            assign.setOnAction(e -> openAssignDialog());
            actionBox.getChildren().add(assign);
        }

        if (!service.nextStatuses(d, user).isEmpty()) {
            Button update = new Button("Update Status");
            update.getStyleClass().add("btn-ghost");
            update.setOnAction(e -> openStatusDialog());
            actionBox.getChildren().add(update);
        }

        if (service.canEditCost(d, user)) {
            Button costBtn = new Button("Edit Repair Cost");
            costBtn.getStyleClass().add("btn-ghost");
            costBtn.setOnAction(e -> openCostDialog(repairCost));
            actionBox.getChildren().add(costBtn);
        }

        if (d.approvalPending() && Permissions.canApproveHighCost(user)) {
            Button approve = new Button("Approve Cost");
            approve.getStyleClass().add("btn-primary");
            approve.setOnAction(e -> decideApproval(true));
            Button reject = new Button("Reject Cost");
            reject.getStyleClass().add("btn-danger");
            reject.setOnAction(e -> decideApproval(false));
            actionBox.getChildren().addAll(approve, reject);
        }

        Button comment = new Button("Add Comment");
        comment.getStyleClass().add("btn-ghost");
        comment.setOnAction(e -> commentArea.requestFocus());
        actionBox.getChildren().add(comment);

        boolean empty = actionBox.getChildren().isEmpty();
        actionBox.setVisible(!empty);
        actionBox.setManaged(!empty);
        if (addAttachmentButton != null) {
            boolean canAttach = detail != null;
            addAttachmentButton.setVisible(canAttach);
            addAttachmentButton.setManaged(canAttach);
        }
    }

    // ------------------------------------------------------------------
    // Parts used
    // ------------------------------------------------------------------

    private void renderChecklists(List<com.deihs.mms.model.RequestChecklist> checklists) {
        checklistBox.getChildren().clear();
        if (checklists == null || checklists.isEmpty()) {
            Label empty = new Label("No checklist applied. Use a template for routine inspection steps.");
            empty.getStyleClass().add("page-sub");
            checklistBox.getChildren().add(empty);
            return;
        }
        for (com.deihs.mms.model.RequestChecklist cl : checklists) {
            VBox card = new VBox(6);
            card.getStyleClass().add("detail-card");
            long done = cl.items().stream().filter(com.deihs.mms.model.RequestChecklistItem::done).count();
            Label head = new Label(cl.name() + " (" + done + "/" + cl.items().size() + " done)");
            head.getStyleClass().add("form-label");
            card.getChildren().add(head);
            for (com.deihs.mms.model.RequestChecklistItem item : cl.items()) {
                javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(item.text());
                cb.setSelected(item.done());
                cb.setWrapText(true);
                cb.setOnAction(e -> {
                    try {
                        checklistService.setItemDone(requestId, item.id(), cb.isSelected(), user);
                        load();
                    } catch (Exception ex) {
                        AlertUtil.error(window(), "Checklist update failed", ex.getMessage(), ex);
                        load();
                    }
                });
                if (item.done() && item.doneBy() != null) {
                    cb.setText(item.text() + "  —  " + item.doneBy());
                }
                card.getChildren().add(cb);
            }
            checklistBox.getChildren().add(card);
        }
    }

    @FXML
    private void onApplyChecklistTemplate() {
        try {
            List<com.deihs.mms.model.ChecklistTemplate> templates = checklistService.templates(false);
            if (templates.isEmpty()) {
                AlertUtil.warn(window(), "No templates", "No active checklist templates found. Create one in Settings first.");
                return;
            }
            javafx.scene.control.ChoiceDialog<com.deihs.mms.model.ChecklistTemplate> dlg = new javafx.scene.control.ChoiceDialog<>(templates.get(0), templates);
            dlg.initOwner(window());
            dlg.setTitle("Apply Checklist Template");
            dlg.setHeaderText("Select a checklist template to apply to this request.");
            dlg.setContentText("Template:");
            // show template name in combo
            javafx.scene.control.ComboBox<com.deihs.mms.model.ChecklistTemplate> box = (javafx.scene.control.ComboBox<com.deihs.mms.model.ChecklistTemplate>) dlg.getDialogPane().lookup(".combo-box");
            if (box != null) {
                box.setCellFactory(lv -> new javafx.scene.control.ListCell<>() { @Override protected void updateItem(com.deihs.mms.model.ChecklistTemplate item, boolean empty) { super.updateItem(item, empty); setText(empty||item==null?null:item.name()); } });
                box.setButtonCell(new javafx.scene.control.ListCell<>() { @Override protected void updateItem(com.deihs.mms.model.ChecklistTemplate item, boolean empty) { super.updateItem(item, empty); setText(empty||item==null?null:item.name()); } });
            }
            dlg.showAndWait().ifPresent(t -> {
                try {
                    checklistService.applyTemplate(requestId, t.id(), user);
                    load();
                } catch (Exception ex) {
                    AlertUtil.error(window(), "Could not apply template", ex.getMessage(), ex);
                }
            });
        } catch (Exception ex) {
            AlertUtil.error(window(), "Could not load templates", ex.getMessage(), ex);
        }
    }

    private void renderParts(List<RequestPart> parts, BigDecimal partsCost) {
        partsBox.getChildren().clear();

        boolean canRecord = detail != null && partService.canRecordParts(detail, user);
        addPartButton.setVisible(canRecord);
        addPartButton.setManaged(canRecord);

        BigDecimal total = partsCost == null ? BigDecimal.ZERO : partsCost;
        partsCostLabel.setText("Parts cost: " + Format.peso(total));

        if (parts == null || parts.isEmpty()) {
            partsBox.getChildren().add(muted(canRecord
                    ? "No parts recorded yet. Use Add Part to deduct stock from the inventory."
                    : "No parts were recorded for this request."));
            return;
        }

        for (RequestPart p : parts) {
            Label name = new Label(p.name());
            name.getStyleClass().add("part-line-name");

            Label meta = new Label(p.quantity() + " " + p.unit()
                    + " × " + Format.peso(p.unitCost() == null ? BigDecimal.ZERO : p.unitCost()));
            meta.getStyleClass().add("part-line-meta");

            Label lineTotal = new Label(Format.peso(p.total()));
            lineTotal.getStyleClass().add("part-line-total");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox row = new HBox(10, name, meta, spacer, lineTotal);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("part-line");

            if (canRecord) {
                Button remove = new Button("Return");
                remove.getStyleClass().addAll("btn-ghost", "btn-table");
                remove.setOnAction(e -> removePart(p));
                row.getChildren().add(remove);
            }
            partsBox.getChildren().add(row);
        }
    }

    @FXML
    private void onAddPart() {
        if (detail == null) {
            return;
        }
        List<Part> active;
        try {
            active = partDao.search(null, false, false);
        } catch (SQLException ex) {
            AlertUtil.error(window(), "Could not load parts",
                    "The parts inventory could not be read from the database.", ex);
            return;
        }
        if (active.isEmpty()) {
            AlertUtil.warn(window(), "No parts available",
                    "Add parts to the inventory first, or restore archived items.");
            return;
        }

        ComboBox<LookupOption> partBox = new ComboBox<>();
        partBox.getStyleClass().add("combo-input");
        partBox.setMaxWidth(Double.MAX_VALUE);
        partBox.setPromptText("Choose a part");
        for (Part p : active) {
            String label = p.name() + " (" + p.unit() + ") — " + p.quantity() + " in stock";
            partBox.getItems().add(new LookupOption(p.id(), label));
        }

        TextField qtyField = new TextField("1");
        qtyField.getStyleClass().add("input");
        qtyField.setPrefWidth(120);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox content = new VBox(8,
                new Label("Record a part consumed by this repair. Stock is deducted immediately.") {{
                    getStyleClass().add("page-sub");
                    setWrapText(true);
                }},
                new Label("Part") {{ getStyleClass().add("field-label"); }},
                partBox,
                new Label("Quantity") {{ getStyleClass().add("field-label"); }},
                qtyField,
                error);
        content.setPrefWidth(420);

        Dialog<ButtonType> dialog = buildDialog("Add Part", content);
        ButtonType save = new ButtonType("Add Part", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        Node saveNode = dialog.getDialogPane().lookupButton(save);
        saveNode.getStyleClass().add("btn-primary");

        boolean[] saved = new boolean[1];
        saveNode.addEventFilter(ActionEvent.ACTION, event -> {
            LookupOption chosen = partBox.getValue();
            if (chosen == null) {
                showInline(error, "Choose a part to record.");
                event.consume();
                return;
            }
            int qty;
            try {
                qty = Integer.parseInt(qtyField.getText().trim().replace(",", ""));
            } catch (NumberFormatException ex) {
                showInline(error, "Enter a whole number for the quantity.");
                event.consume();
                return;
            }
            if (qty <= 0) {
                showInline(error, "Quantity must be greater than zero.");
                event.consume();
                return;
            }

            dialog.getDialogPane().setCursor(Cursor.WAIT);
            try {
                partService.consumeForRequest(requestId, chosen.id(), qty, user);
                saved[0] = true;
            } catch (ValidationException ex) {
                showInline(error, ex.getMessage());
                event.consume();
            } catch (SQLException ex) {
                showInline(error, ex.getMessage() == null
                        ? "The part could not be recorded." : ex.getMessage());
                event.consume();
            } finally {
                dialog.getDialogPane().setCursor(Cursor.DEFAULT);
            }
        });

        dialog.showAndWait();
        if (saved[0]) {
            load();
        }
    }

    private void removePart(RequestPart p) {
        if (!AlertUtil.confirm(window(), "Return part to stock?",
                "Return " + p.quantity() + " " + p.unit() + " of " + p.name()
                        + " to the inventory and remove it from this request?")) {
            return;
        }
        try {
            partService.removePart(p.id(), user);
            load();
        } catch (ValidationException ex) {
            AlertUtil.warn(window(), "Not allowed", ex.getMessage());
        } catch (SQLException ex) {
            AlertUtil.error(window(), "Could not return the part",
                    "The inventory could not be updated.", ex);
        }
    }

    // ------------------------------------------------------------------
    // Repair cost dialog
    // ------------------------------------------------------------------

    private void openCostDialog(BigDecimal currentCost) {
        if (detail == null) return;

        Label intro = new Label("Set the total repair cost for this request. This value is used in Reports.");
        intro.getStyleClass().add("page-sub");
        intro.setWrapText(true);

        TextField costField = new TextField();
        costField.getStyleClass().add("input");
        costField.setPromptText("0.00");
        costField.setText(currentCost == null ? "0.00" : currentCost.toPlainString());

        Label err = new Label();
        err.getStyleClass().add("form-error");
        err.setVisible(false);
        err.setManaged(false);
        err.setWrapText(true);

        VBox body = new VBox(10,
                intro,
                new VBox(6, new Label("Repair cost (PHP)") {{ getStyleClass().add("field-label"); }}, costField),
                err);
        body.getStyleClass().add("detail-dialog-body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle("Edit Repair Cost");
        dialog.setHeaderText("Repair cost for " + detail.ticketNo());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.getDialogPane().setContent(body);
        ViewLoader.applyAppStyles(dialog.getDialogPane());

        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.getStyleClass().add("btn-primary");
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            err.setVisible(false); err.setManaged(false);
            String raw = costField.getText() == null ? "" : costField.getText().trim().replace(",", "");
            BigDecimal value;
            try {
                value = new BigDecimal(raw.isEmpty() ? "0" : raw);
            } catch (NumberFormatException ex) {
                err.setText("Enter a valid amount, for example 1500.00.");
                err.setVisible(true); err.setManaged(true);
                return;
            }
            ok.setDisable(true);
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    service.updateRepairCost(requestId, value, user);
                    return null;
                }
            };
            task.setOnSucceeded(ev -> { ok.setDisable(false); dialog.setResult(ButtonType.OK); dialog.close(); load(); });
            task.setOnFailed(ev -> {
                ok.setDisable(false);
                Throwable ex = task.getException();
                err.setText(ex instanceof ValidationException ? ex.getMessage()
                        : "Could not save repair cost.");
                err.setVisible(true); err.setManaged(true);
            });
            Thread t = new Thread(task, "update-repair-cost");
            t.setDaemon(true);
            t.start();
        });

        dialog.showAndWait();
    }

    // ------------------------------------------------------------------
    // Assign dialog
    // ------------------------------------------------------------------

    private void openAssignDialog() {
        if (detail == null) {
            return;
        }

        Label intro = new Label("Hand this request to a maintenance person.");
        intro.getStyleClass().add("page-sub");

        ComboBox<LookupOption> personBox = new ComboBox<>();
        personBox.getStyleClass().add("combo-input");
        personBox.setMaxWidth(Double.MAX_VALUE);
        personBox.setPromptText("Choose a maintenance person");
        personBox.getItems().setAll(personnel);
        if (detail.assigneeId() != null) {
            personBox.getItems().stream()
                    .filter(p -> p.id() == detail.assigneeId())
                    .findFirst()
                    .ifPresent(personBox::setValue);
        }

        Label remarksLabel = new Label("Remarks (optional)");
        remarksLabel.getStyleClass().add("field-label");
        TextArea remarks = new TextArea();
        remarks.getStyleClass().addAll("input", "text-area-input");
        remarks.setPromptText("Anything the assignee should know before starting");
        remarks.setPrefRowCount(3);
        remarks.setWrapText(true);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox content = new VBox(8, intro, personBox, remarksLabel, remarks, error);
        content.setPrefWidth(420);

        Dialog<ButtonType> dialog = buildDialog("Assign Request", content);
        ButtonType save = new ButtonType("Assign", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        Node saveNode = dialog.getDialogPane().lookupButton(save);
        saveNode.getStyleClass().add("btn-primary");
        if (personnel.isEmpty()) {
            error.setText("No active maintenance personnel accounts are available.");
            error.setVisible(true);
            error.setManaged(true);
            saveNode.setDisable(true);
        }

        boolean[] saved = new boolean[1];
        saveNode.addEventFilter(ActionEvent.ACTION, event -> {
            LookupOption chosen = personBox.getValue();
            if (chosen == null) {
                showInline(error, "Choose who should handle this request.");
                event.consume();
                return;
            }

            dialog.getDialogPane().setCursor(Cursor.WAIT);
            try {
                service.assign(requestId, chosen.id(), remarks.getText(), user);
                saved[0] = true;
            } catch (ValidationException ex) {
                showInline(error, ex.getMessage());
                event.consume();
            } catch (SQLException ex) {
                showInline(error, "The assignment could not be saved. Please try again.");
                AlertUtil.error(window(), "Could not assign the request",
                        "The database rejected the assignment.", ex);
                event.consume();
            } finally {
                dialog.getDialogPane().setCursor(Cursor.DEFAULT);
            }
        });

        dialog.showAndWait();

        if (saved[0]) {
            load();
        }
    }

    // ------------------------------------------------------------------
    // Status dialog
    // ------------------------------------------------------------------

    private void openStatusDialog() {
        if (detail == null) {
            return;
        }
        List<String> next = service.nextStatuses(detail, user);
        if (next.isEmpty()) {
            return;
        }

        Label intro = new Label("Move this " + Format.pretty(detail.status()).toLowerCase()
                + " request to a new status.");
        intro.getStyleClass().add("page-sub");

        ComboBox<Choice> statusBox = new ComboBox<>();
        statusBox.getStyleClass().add("combo-input");
        statusBox.setMaxWidth(Double.MAX_VALUE);
        statusBox.setPromptText("Choose the new status");
        for (String status : next) {
            statusBox.getItems().add(new Choice(status, Format.pretty(status)));
        }

        Label remarksLabel = new Label("Remarks (optional)");
        remarksLabel.getStyleClass().add("field-label");
        TextArea remarks = new TextArea();
        remarks.getStyleClass().addAll("input", "text-area-input");
        remarks.setPrefRowCount(3);
        remarks.setWrapText(true);

        statusBox.valueProperty().addListener((o, old, val) -> {
            boolean required = val != null && StatusFlow.requiresRemarks(val.value());
            remarksLabel.setText(required ? "Remarks (required)" : "Remarks (optional)");
            remarks.setPromptText(required
                    ? "Explain why this request is taking this path"
                    : "Anything worth recording for this status change");
        });

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox content = new VBox(8, intro, statusBox, remarksLabel, remarks, error);
        content.setPrefWidth(420);

        Dialog<ButtonType> dialog = buildDialog("Update Status", content);
        ButtonType save = new ButtonType("Update", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);

        Node saveNode = dialog.getDialogPane().lookupButton(save);
        saveNode.getStyleClass().add("btn-primary");

        boolean[] saved = new boolean[1];
        saveNode.addEventFilter(ActionEvent.ACTION, event -> {
            Choice chosen = statusBox.getValue();
            if (chosen == null) {
                showInline(error, "Choose the new status.");
                event.consume();
                return;
            }

            dialog.getDialogPane().setCursor(Cursor.WAIT);
            try {
                service.changeStatus(requestId, chosen.value(), remarks.getText(), user);
                saved[0] = true;
            } catch (ValidationException ex) {
                showInline(error, ex.getMessage());
                event.consume();
            } catch (SQLException ex) {
                showInline(error, "The status change could not be saved. Please try again.");
                AlertUtil.error(window(), "Could not update the request",
                        "The database rejected the status change.", ex);
                event.consume();
            } finally {
                dialog.getDialogPane().setCursor(Cursor.DEFAULT);
            }
        });

        dialog.showAndWait();

        if (saved[0]) {
            load();
        }
    }

    private Dialog<ButtonType> buildDialog(String title, VBox content) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle(title);
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        ViewLoader.applyAppStyles(dialog.getDialogPane());
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        return dialog;
    }

    private static void showInline(Label error, String message) {
        error.setText(message == null ? "That change could not be saved." : message);
        error.setVisible(true);
        error.setManaged(true);
    }

    // ------------------------------------------------------------------
    // Timeline
    // ------------------------------------------------------------------

    private void renderTimeline(List<HistoryEntry> history, RequestDetail d) {
        timelineBox.getChildren().clear();

        if (history.isEmpty()) {
            timelineBox.getChildren().add(muted("No history was recorded for this request."));
            return;
        }

        for (int i = 0; i < history.size(); i++) {
            HistoryEntry entry = history.get(i);
            boolean last = i == history.size() - 1;

            String title = entry.fromStatus() == null
                    ? "Reported"
                    : Format.pretty(entry.toStatus());
            String meta = Format.dateTime(entry.changedAt()) + " · " + entry.changedBy();

            String marker = last && !d.isTerminal() ? "tl-now" : "tl-done";
            timelineBox.getChildren().add(
                    timelineRow(title, meta, entry.remarks(), marker, last));
        }

        for (String waiting : waitingPath(d.status())) {
            timelineBox.getChildren().add(
                    timelineRow(Format.pretty(waiting), "Waiting", null,
                            "tl-todo", true));
        }
    }

    /**
     * The steps a request still has in front of it. ON_HOLD resumes at
     * IN_PROGRESS; terminal requests have nothing left.
     */
    private static List<String> waitingPath(String status) {
        return switch (status == null ? "" : status) {
            case StatusFlow.PENDING ->
                    List.of(StatusFlow.ASSIGNED, StatusFlow.IN_PROGRESS, StatusFlow.COMPLETED);
            case StatusFlow.ASSIGNED ->
                    List.of(StatusFlow.IN_PROGRESS, StatusFlow.COMPLETED);
            case StatusFlow.IN_PROGRESS ->
                    List.of(StatusFlow.COMPLETED);
            case StatusFlow.ON_HOLD ->
                    List.of(StatusFlow.IN_PROGRESS, StatusFlow.COMPLETED);
            case StatusFlow.COMPLETED ->
                    List.of(StatusFlow.CLOSED);
            default -> List.of();
        };
    }

    private static HBox timelineRow(String title, String meta, String remarks,
                                    String markerClass, boolean last) {
        Region dot = new Region();
        dot.getStyleClass().addAll("tl-dot", markerClass);
        dot.setMinSize(10, 10);
        dot.setPrefSize(10, 10);
        dot.setMaxSize(10, 10);

        VBox marker = new VBox(dot);
        marker.setAlignment(Pos.TOP_CENTER);
        marker.setMinWidth(12);
        marker.setPrefWidth(12);

        if (!last) {
            Region line = new Region();
            line.getStyleClass().add("tl-line");
            line.setMinWidth(2);
            line.setPrefWidth(2);
            VBox.setVgrow(line, Priority.ALWAYS);
            marker.getChildren().add(line);
        }

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("tl-title");

        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().add("tl-meta");
        metaLabel.setWrapText(true);

        VBox text = new VBox(2, titleLabel, metaLabel);
        HBox.setHgrow(text, Priority.ALWAYS);

        if (remarks != null && !remarks.isBlank()) {
            Label note = new Label(remarks);
            note.getStyleClass().add("tl-remarks");
            note.setWrapText(true);
            text.getChildren().add(note);
        }

        HBox row = new HBox(10, marker, text);
        row.getStyleClass().add("tl");
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    // ------------------------------------------------------------------
    // Comments
    // ------------------------------------------------------------------

    private void renderComments(List<CommentEntry> comments) {
        commentBox.getChildren().clear();

        if (comments.isEmpty()) {
            commentBox.getChildren().add(
                    muted("No comments yet. Start the conversation below."));
            return;
        }

        for (CommentEntry c : comments) {
            Label author = new Label(c.author());
            author.getStyleClass().add("comment-author");

            Label meta = new Label(Format.dateTime(c.createdAt()));
            meta.getStyleClass().add("comment-meta");

            HBox head = new HBox(8, author, meta);
            head.setAlignment(Pos.CENTER_LEFT);

            Label text = new Label(c.text());
            text.getStyleClass().add("comment-text");
            text.setWrapText(true);
            text.setMaxWidth(Double.MAX_VALUE);

            VBox card = new VBox(3, head, text);
            card.getStyleClass().add("comment");
            commentBox.getChildren().add(card);
        }
    }

    @FXML
    private void onPostComment() {
        if (detail == null) {
            return;
        }
        clearError();
        postButton.setDisable(true);
        try {
            CommentEntry saved = service.addComment(requestId, commentArea.getText(), user);
            if (saved != null) {
                commentArea.clear();
                load();
            }
        } catch (ValidationException ex) {
            showCommentError(ex.getMessage());
        } catch (SQLException ex) {
            showCommentError("The comment could not be saved. Please try again.");
            AlertUtil.error(window(), "Could not post the comment",
                    "The database rejected the comment.", ex);
        } finally {
            postButton.setDisable(false);
        }
    }

    private void showCommentError(String message) {
        commentError.setText(message);
        commentError.setVisible(true);
        commentError.setManaged(true);
    }

    private void clearError() {
        commentError.setVisible(false);
        commentError.setManaged(false);
    }

    // ------------------------------------------------------------------
    // Attachments
    // ------------------------------------------------------------------

    private void renderAttachments(List<AttachmentEntry> attachments) {
        attachmentBox.getChildren().clear();

        if (attachments.isEmpty()) {
            attachmentBox.getChildren().add(
                    muted("No files were attached to this request."));
            return;
        }

        for (AttachmentEntry a : attachments) {
            String kind = a.normalizedKind();
            String prefix = switch (kind) { case "BEFORE" -> "[BEFORE] "; case "AFTER" -> "[AFTER] "; default -> ""; };
            Label name = new Label(prefix + a.fileName());
            name.getStyleClass().add("attachment-name");
            name.setStyle("-fx-cursor: hand; -fx-underline: true;");
            name.setOnMouseClicked(e -> openAttachment(a));

            String meta = kind + " · " + Format.fileSize(a.fileSize()) + " · " + a.uploadedBy()
                    + " · " + Format.dateTime(a.uploadedAt());
            Label info = new Label(meta);
            info.getStyleClass().add("attachment-meta");
            info.setWrapText(true);

            VBox block = new VBox(2, name, info);
            attachmentBox.getChildren().add(block);
        }
    }

    private void openAttachment(AttachmentEntry a) {
        try {
            java.io.File f = new java.io.File(a.filePath());
            if (!f.exists()) { AlertUtil.warn(window(), "File not found", "The attachment file is not available on this PC:\n" + a.filePath()); return; }
            if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(f);
        } catch (Exception ex) {
            AlertUtil.error(window(), "Could not open attachment", ex.getMessage(), ex);
        }
    }

    @FXML
    private void onAddAttachment() {
        if (detail == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add Attachment");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Images / PDF", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.pdf"),
                new FileChooser.ExtensionFilter("All Files", "*.*"));
        java.io.File file = chooser.showOpenDialog(window());
        if (file == null) return;
        ChoiceDialog<String> kindDialog = new ChoiceDialog<>("GENERAL", java.util.List.of("GENERAL","BEFORE","AFTER"));
        kindDialog.initOwner(window());
        kindDialog.setTitle("Attachment Type");
        kindDialog.setHeaderText("Select attachment type for " + file.getName());
        kindDialog.setContentText("Type:");
        java.util.Optional<String> kind = kindDialog.showAndWait();
        if (kind.isEmpty()) return;
        Task<Void> task = new Task<>() { @Override protected Void call() throws Exception { service.addAttachment(requestId, file, kind.get(), user); return null; } };
        task.setOnSucceeded(e -> load());
        task.setOnFailed(e -> AlertUtil.error(window(), "Could not add attachment", String.valueOf(task.getException().getMessage()), task.getException()));
        Thread t = new Thread(task, "add-attachment"); t.setDaemon(true); t.start();
    }

    private void decideApproval(boolean approve) {
        if (detail == null) return;
        TextInputDialog dlg = new TextInputDialog();
        dlg.initOwner(window());
        dlg.setTitle(approve ? "Approve High-Cost Repair" : "Reject High-Cost Repair");
        dlg.setHeaderText(approve ? "Approve this high-cost repair?" : "Reject this high-cost repair?");
        dlg.setContentText(approve ? "Notes (optional):" : "Reason (required):");
        java.util.Optional<String> res = dlg.showAndWait();
        if (res.isEmpty()) return;
        Task<Void> task = new Task<>() { @Override protected Void call() throws Exception { service.decideApproval(requestId, approve, res.get(), user); return null; } };
        task.setOnSucceeded(e -> load());
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String msg = ex instanceof ValidationException ? ex.getMessage() : "Could not save approval decision.";
            AlertUtil.error(window(), "Approval failed", msg, ex instanceof ValidationException ? null : ex);
        });
        Thread t = new Thread(task, "decide-approval"); t.setDaemon(true); t.start();
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @FXML
    private void onBack() {
        goBack();
    }

    private void goBack() {
        if (backHandler != null) {
            backHandler.run();
        }
    }

    // ------------------------------------------------------------------

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("page-sub");
        label.setWrapText(true);
        return label;
    }

    private javafx.stage.Window window() {
        return pageScroll.getScene() == null ? null : pageScroll.getScene().getWindow();
    }
}
