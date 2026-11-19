package com.deihs.mms.controller;

import com.deihs.mms.dao.UserDao;
import com.deihs.mms.model.AttachmentEntry;
import com.deihs.mms.model.Choice;
import com.deihs.mms.model.CommentEntry;
import com.deihs.mms.model.HistoryEntry;
import com.deihs.mms.model.LookupOption;
import com.deihs.mms.model.RequestDetail;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
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

    private final RequestDetailService service = new RequestDetailService();
    private final UserDao userDao = new UserDao();

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
            List<LookupOption> personnel
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
                        people);
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

        renderInfo(detail);
        renderActions(detail);
        renderTimeline(bundle.history(), detail);
        renderComments(bundle.comments());
        renderAttachments(bundle.attachments());
    }

    // ------------------------------------------------------------------
    // Information
    // ------------------------------------------------------------------

    private void renderInfo(RequestDetail d) {
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

    private void renderActions(RequestDetail d) {
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

        Button comment = new Button("Add Comment");
        comment.getStyleClass().add("btn-ghost");
        comment.setOnAction(e -> commentArea.requestFocus());
        actionBox.getChildren().add(comment);

        boolean empty = actionBox.getChildren().isEmpty();
        actionBox.setVisible(!empty);
        actionBox.setManaged(!empty);
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
            Label name = new Label(a.fileName());
            name.getStyleClass().add("attachment-name");

            String meta = Format.fileSize(a.fileSize()) + " · " + a.uploadedBy()
                    + " · " + Format.dateTime(a.uploadedAt());
            Label info = new Label(meta);
            info.getStyleClass().add("attachment-meta");
            info.setWrapText(true);

            VBox block = new VBox(2, name, info);
            attachmentBox.getChildren().add(block);
        }
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
