package com.deihs.mms.controller;

import com.deihs.mms.Main;
import com.deihs.mms.model.Notification;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.service.BackupService;
import com.deihs.mms.service.NotificationService;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.ProfileService;
import com.deihs.mms.service.Session;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Appearance;
import com.deihs.mms.util.Avatars;
import com.deihs.mms.util.Icons;
import com.deihs.mms.util.ViewLoader;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * The application frame: sidebar navigation, top bar, and the content area
 * where each screen is loaded.
 *
 * <p>The sidebar is a hover-expanded rail: it rests at 72 px showing only the
 * crest and the nav icons, then grows to 236 px while the pointer is over it.
 * The top bar carries a three-line button that opens the account drawer and
 * the account chip, which shows a small preview on hover and opens the full
 * profile page when clicked.</p>
 */
public class ShellController {

    private static final double SIDEBAR_COLLAPSED = 72;
    private static final double SIDEBAR_EXPANDED = 236;
    private static final String DISMISS_INSTALLED = "deihs.mms.shell.dismiss";
    private static ShellController activeShell;

    @FXML private StackPane shellRoot;
    @FXML private HBox appShell;
    @FXML private VBox sidebar;
    @FXML private HBox brandBox;
    @FXML private StackPane brandLogo;
    @FXML private VBox brandText;
    @FXML private VBox menuBox;
    @FXML private VBox sideFoot;
    @FXML private Label scopeLabel;
    @FXML private Label versionLabel;
    @FXML private Button menuButton;
    @FXML private StackPane bellHolder;
    @FXML private Button bellButton;
    @FXML private Label bellBadge;
    @FXML private Label pageTitle;
    @FXML private HBox userChip;
    @FXML private StackPane avatarHolder;
    @FXML private Label userNameLabel;
    @FXML private Label userRoleLabel;
    @FXML private StackPane contentArea;
    @FXML private Region drawerScrim;
    @FXML private VBox profilePopover;
    @FXML private StackPane popoverAvatar;
    @FXML private Label popoverName;
    @FXML private Label popoverRole;
    @FXML private Label popoverEmail;
    @FXML private StackPane popoverStatusIcon;
    @FXML private Label popoverStatus;
    @FXML private VBox drawer;
    @FXML private Button drawerCloseButton;
    @FXML private StackPane drawerAvatar;
    @FXML private Label drawerName;
    @FXML private Label drawerRole;
    @FXML private Label drawerEmail;
    @FXML private Button drawerProfileButton;
    @FXML private Button drawerSettingsButton;
    @FXML private Button drawerLogoutButton;
    @FXML private Button sidebarSignOutButton;

    private final Map<String, Nav> navs = new LinkedHashMap<>();
    private final Map<String, NavRow> navRows = new LinkedHashMap<>();
    private String currentKey = "dashboard";

    private Timeline sidebarTimeline;
    private boolean sidebarExpanded;
    private PauseTransition hoverHide;
    private boolean drawerOpen;

    private static final Preferences PREFS = Preferences.userNodeForPackage(ShellController.class);

    private final NotificationService notifications = new NotificationService();
    private Timeline notificationTimer;

    private record Nav(String key, String title, String icon) {
    }

    @FXML
    private void initialize() {
        activeShell = this;

        User user = Session.get();
        Appearance.apply(shellRoot, user);

        drawerProfileButton.setText("My profile");
        drawerSettingsButton.setText("Appearance & settings");
        drawerLogoutButton.setText("Log out");
        if (!drawerLogoutButton.getStyleClass().contains("drawer-item-danger")) {
            drawerLogoutButton.getStyleClass().add("drawer-item-danger");
        }
        if (sidebarSignOutButton != null) {
            sidebarSignOutButton.setText("Sign out");
            sidebarSignOutButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            sidebarSignOutButton.setAlignment(Pos.CENTER);
        }
        refreshChrome();

        drawerScrim.setOnMouseClicked(e -> closeDrawer());
        installSidebarAnimation();
        installHoverPreview();
        installSceneHandlers();

        applyFontPreference();
        refreshIdentity();
        refreshNotifications();
        buildMenu(user);
        navigate("dashboard");

        notificationTimer = new Timeline(new KeyFrame(
                Duration.seconds(45), e -> refreshNotifications()));
        notificationTimer.setCycleCount(Timeline.INDEFINITE);
        notificationTimer.play();
    }

    /** Keeps top-bar and drawer icons readable in light and dark themes. */
    private void refreshChrome() {
        boolean dark = shellRoot.getStyleClass().contains("theme-dark");
        String icon = dark ? "#E6EBF0" : "#33414F";
        String muted = dark ? "#9AA6B2" : "#6B7785";
        String danger = dark ? "#FF8A80" : "#B3261E";
        menuButton.setGraphic(Icons.of(Icons.MENU, 20, icon));
        if (bellButton != null) {
            bellButton.setGraphic(Icons.of(Icons.BELL, 19, icon));
        }
        drawerCloseButton.setGraphic(Icons.of(Icons.CLOSE, 18, muted));
        drawerProfileButton.setGraphic(Icons.of(Icons.PROFILE, 18, icon));
        drawerSettingsButton.setGraphic(Icons.of(Icons.SETTINGS, 18, icon));
        drawerLogoutButton.setGraphic(Icons.of(Icons.LOGOUT, 18, danger));
        if (sidebarSignOutButton != null) {
            sidebarSignOutButton.setGraphic(Icons.of(Icons.LOGOUT, 18, danger));
        }
    }

    private void applyFontPreference() {
        String size = PREFS.get("fontSize." + Session.get().id(), "default");
        shellRoot.getStyleClass().removeAll("text-large", "text-xlarge");
        if ("large".equals(size)) shellRoot.getStyleClass().add("text-large");
        if ("xlarge".equals(size)) shellRoot.getStyleClass().add("text-xlarge");
    }

    private String fontPref() {
        if (shellRoot.getStyleClass().contains("text-xlarge")) return "xlarge";
        if (shellRoot.getStyleClass().contains("text-large")) return "large";
        return "default";
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    /** Re-reads the session so a profile edit shows up everywhere at once. */
    private void refreshIdentity() {
        User user = Session.get();

        userNameLabel.setText(user.fullName());
        userRoleLabel.setText(user.roleName());
        Avatars.fill(avatarHolder, user, 30);
        Avatars.fill(drawerAvatar, user, 56);
        Avatars.fill(popoverAvatar, user, 44);

        String email = user.email() == null || user.email().isBlank()
                ? "No email on file" : user.email();
        drawerName.setText(user.fullName());
        drawerRole.setText(user.roleName());
        drawerEmail.setText(email);
        popoverName.setText(user.fullName());
        popoverRole.setText(user.roleName());
        popoverEmail.setText(email);

        boolean verified = user.emailVerified();
        popoverStatus.setText(verified ? "Email verified" : "Email not verified");
        popoverStatus.getStyleClass().removeAll("popover-status-ok", "popover-status-warn");
        popoverStatus.getStyleClass().add(verified ? "popover-status-ok" : "popover-status-warn");
        popoverStatusIcon.getChildren().setAll(Icons.of(
                verified ? Icons.CHECK_CIRCLE : Icons.MAIL, 15,
                verified ? "#0E6B34" : "#B7791F"));

        scopeLabel.setText(switch (user.role()) {
            case PERSONNEL -> "Showing my assigned tasks";
            case STAFF -> "Showing my own requests";
            default -> "All school records";
        });
        refreshChrome();
    }

    // ------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------

    private void installSidebarAnimation() {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(sidebar.widthProperty());
        clip.heightProperty().bind(sidebar.heightProperty());
        sidebar.setClip(clip);

        sidebar.setOnMouseEntered(e -> setSidebarExpanded(true));
        sidebar.setOnMouseExited(e -> setSidebarExpanded(false));
    }

    private void setSidebarExpanded(boolean expand) {
        if (sidebarExpanded == expand) {
            return;
        }
        sidebarExpanded = expand;

        if (sidebarTimeline != null) {
            sidebarTimeline.stop();
        }
        sidebarTimeline = new Timeline(new KeyFrame(
                Duration.millis(expand ? 200 : 180),
                new KeyValue(sidebar.prefWidthProperty(),
                        expand ? SIDEBAR_EXPANDED : SIDEBAR_COLLAPSED,
                        Interpolator.EASE_BOTH)));
        sidebarTimeline.play();

        if (sidebarSignOutButton != null) {
            sidebarSignOutButton.setContentDisplay(expand ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
            sidebarSignOutButton.setAlignment(expand ? Pos.CENTER_LEFT : Pos.CENTER);
        }
    }

    /**
     * Fades a label in step with the sidebar width. Below roughly 100 px the
     * text is fully transparent, above 166 px it is fully visible, and the
     * space between gives the expand animation a soft reveal.
     */
    private void bindSidebarReveal(Node node) {
        node.opacityProperty().bind(Bindings.createDoubleBinding(() -> {
            double width = sidebar.getPrefWidth();
            return Math.max(0, Math.min(1, (width - 96) / 70.0));
        }, sidebar.prefWidthProperty()));
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void buildMenu(User user) {
        bindSidebarReveal(brandText);
        bindSidebarReveal(sideFoot);

        for (Nav nav : navFor(user)) {
            navs.put(nav.key(), nav);
            NavRow row = new NavRow(nav);
            navRows.put(nav.key(), row);
            menuBox.getChildren().add(row.node);
        }
    }

    private List<Nav> navFor(User u) {
        List<Nav> list = new ArrayList<>();
        list.add(new Nav("dashboard", "Dashboard", Icons.DASHBOARD));

        if (u.role() == Role.PERSONNEL) {
            list.add(new Nav("tasks", "My Tasks", Icons.TASKS));
        } else {
            list.add(new Nav("requests", "Requests", Icons.REQUESTS));
        }

        if (Permissions.canManageFacilities(u)) {
            list.add(new Nav("facilities", "Facilities", Icons.FACILITIES));
        }
        if (Permissions.canManageEquipment(u)) {
            list.add(new Nav("equipment", "Equipment", Icons.EQUIPMENT));
        }
        if (u.role() != Role.STAFF) {
            list.add(new Nav("schedules", "Schedules", Icons.SCHEDULES));
        }
        if (Permissions.canManageParts(u)) {
            list.add(new Nav("parts", "Parts", Icons.PARTS));
        }
        if (Permissions.canManageUsers(u)) {
            list.add(new Nav("users", "Users", Icons.USERS));
        }
        if (Permissions.canViewReports(u)) {
            list.add(new Nav("reports", "Reports", Icons.REPORTS));
        }
        if (Permissions.canViewAuditLog(u)) {
            list.add(new Nav("audit", "Audit Log", Icons.AUDIT));
        }
        return list;
    }

    public void navigate(String key) {
        Nav nav = navs.get(key);
        if (nav == null) {
            return;
        }
        currentKey = key;
        pageTitle.setText(nav.title());
        setActiveNav(key);
        contentArea.getChildren().setAll(loadView(nav));
    }

    private void setActiveNav(String key) {
        navRows.forEach((rowKey, row) -> row.setActive(rowKey.equals(key)));
    }

    private Node loadView(Nav nav) {
        if ("dashboard".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/DashboardView.fxml");
                Parent view = loader.load();
                DashboardController controller = loader.getController();
                controller.setNavigator(this::navigate);
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("requests".equals(nav.key()) || "tasks".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/RequestsView.fxml");
                Parent view = loader.load();
                RequestsController controller = loader.getController();
                controller.setOpenRequestHandler(id -> openRequest(nav, id));
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("facilities".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/FacilitiesView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("users".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/UsersView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("equipment".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/EquipmentView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("schedules".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/SchedulesView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("parts".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/PartsView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("audit".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/AuditLogView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        if ("reports".equals(nav.key())) {
            try {
                FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/ReportsView.fxml");
                Parent view = loader.load();
                return view;
            } catch (IOException | RuntimeException e) {
                return errorView(nav.title(), e);
            }
        }
        return placeholder(nav);
    }

    /**
     * Replaces the list with the detail screen. Back re-navigates to the list
     * key so the rows are rebuilt from fresh data instead of holding a stale
     * controller in memory.
     */
    private void openRequest(Nav nav, int requestId) {
        try {
            FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/RequestDetailView.fxml");
            Parent view = loader.load();
            RequestDetailController controller = loader.getController();
            controller.setBackHandler(() -> navigate(nav.key()));
            controller.show(requestId);
            contentArea.getChildren().setAll(view);
        } catch (IOException | RuntimeException e) {
            contentArea.getChildren().setAll(errorView(nav.title(), e));
        }
    }

    // ------------------------------------------------------------------
    // Profile page
    // ------------------------------------------------------------------

    private void showProfile() {
        hidePopover();
        if (drawerOpen) {
            closeDrawer();
        }
        currentKey = "profile";
        pageTitle.setText("My profile");
        setActiveNav("");
        try {
            FXMLLoader loader = ViewLoader.loader("/com/deihs/mms/view/ProfileView.fxml");
            Parent view = loader.load();
            ProfileController controller = loader.getController();
            controller.setOnProfileChanged(this::refreshIdentity);
            contentArea.getChildren().setAll(view);
        } catch (IOException | RuntimeException e) {
            contentArea.getChildren().setAll(errorView("My profile", e));
        }
    }

    // ------------------------------------------------------------------
    // Account drawer
    // ------------------------------------------------------------------

    @FXML
    private void onOpenDrawer() {
        hidePopover();
        refreshIdentity();
        drawerOpen = true;

        drawerScrim.setVisible(true);
        drawerScrim.setManaged(true);
        drawerScrim.setOpacity(0);
        FadeTransition scrimIn = new FadeTransition(Duration.millis(180), drawerScrim);
        scrimIn.setToValue(1);
        scrimIn.play();

        drawer.setVisible(true);
        drawer.setManaged(true);
        drawer.toFront();
        drawer.setTranslateX(340);
        TranslateTransition slide = new TranslateTransition(Duration.millis(230), drawer);
        slide.setToX(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        slide.play();
    }

    private void closeDrawer() {
        if (!drawerOpen) {
            return;
        }
        drawerOpen = false;

        TranslateTransition slide = new TranslateTransition(Duration.millis(180), drawer);
        slide.setToX(340);
        slide.setInterpolator(Interpolator.EASE_IN);
        slide.setOnFinished(e -> {
            drawer.setVisible(false);
            drawer.setManaged(false);
        });
        slide.play();

        FadeTransition scrimOut = new FadeTransition(Duration.millis(160), drawerScrim);
        scrimOut.setToValue(0);
        scrimOut.setOnFinished(e -> {
            drawerScrim.setVisible(false);
            drawerScrim.setManaged(false);
        });
        scrimOut.play();
    }

    @FXML
    private void onCloseDrawer() {
        closeDrawer();
    }

    @FXML
    private void onOpenProfileFromDrawer() {
        showProfile();
    }

    @FXML
    private void onOpenProfileFromPopover() {
        showProfile();
    }

    // ------------------------------------------------------------------
    // Hover preview
    // ------------------------------------------------------------------

    private void installHoverPreview() {
        hoverHide = new PauseTransition(Duration.millis(220));
        hoverHide.setOnFinished(e -> hidePopover());

        userChip.setOnMouseEntered(e -> {
            if (drawerOpen) {
                return;
            }
            hoverHide.stop();
            showPopover();
        });
        userChip.setOnMouseExited(e -> scheduleHide());
        profilePopover.setOnMouseEntered(e -> hoverHide.stop());
        profilePopover.setOnMouseExited(e -> scheduleHide());
        userChip.setOnMouseClicked(e -> showProfile());
    }

    private void scheduleHide() {
        hoverHide.playFromStart();
    }

    private void showPopover() {
        if (profilePopover.isVisible()) {
            return;
        }
        refreshIdentity();
        profilePopover.setVisible(true);
        profilePopover.setManaged(true);
        profilePopover.toFront();
        profilePopover.setOpacity(0);
        profilePopover.setTranslateY(-6);

        FadeTransition fade = new FadeTransition(Duration.millis(140), profilePopover);
        fade.setToValue(1);
        fade.play();

        TranslateTransition slide = new TranslateTransition(Duration.millis(160), profilePopover);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        slide.play();
    }

    private void hidePopover() {
        if (hoverHide != null) {
            hoverHide.stop();
        }
        profilePopover.setVisible(false);
        profilePopover.setManaged(false);
    }

    // ------------------------------------------------------------------
    // Scene-wide dismissal
    // ------------------------------------------------------------------

    private void installSceneHandlers() {
        Platform.runLater(() -> {
            Scene scene = shellRoot.getScene();
            if (scene == null || scene.getProperties().containsKey(DISMISS_INSTALLED)) {
                return;
            }
            scene.getProperties().put(DISMISS_INSTALLED, Boolean.TRUE);

            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                ShellController shell = activeShell;
                if (shell != null) {
                    shell.handleScenePress(event);
                }
            });
            scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                ShellController shell = activeShell;
                if (shell != null && event.getCode() == KeyCode.ESCAPE) {
                    shell.handleEscape();
                }
            });
        });
    }

    private void handleScenePress(MouseEvent event) {
        if (!profilePopover.isVisible()) {
            return;
        }
        Object target = event.getTarget();
        if (!(target instanceof Node node)
                || (!isInside(node, profilePopover) && !isInside(node, userChip))) {
            hidePopover();
        }
    }

    private void handleEscape() {
        if (drawerOpen) {
            closeDrawer();
        } else {
            hidePopover();
        }
    }

    private static boolean isInside(Node node, Node ancestor) {
        Node cursor = node;
        while (cursor != null) {
            if (cursor == ancestor) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Notification centre
    // ------------------------------------------------------------------

    private void refreshNotifications() {
        User user = Session.get();
        if (user == null || bellBadge == null) {
            return;
        }
        int count = notifications.unreadCount(user.id());
        boolean show = count > 0;
        bellBadge.setText(count > 99 ? "99+" : String.valueOf(count));
        bellBadge.setVisible(show);
        bellBadge.setManaged(show);
    }

    @FXML
    private void onOpenNotifications() {
        if (drawerOpen) {
            closeDrawer();
        }
        hidePopover();

        User user = Session.get();
        if (user == null) {
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        Window owner = shellRoot.getScene() == null ? null : shellRoot.getScene().getWindow();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Notifications");
        ButtonType close = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(close);
        ViewLoader.applyAppStyles(dialog.getDialogPane());
        dialog.getDialogPane().getStyleClass().addAll(shellRoot.getStyleClass());
        dialog.getDialogPane().setPrefWidth(540);

        Label heading = new Label("Notifications");
        heading.getStyleClass().add("page-h1");

        Button markAll = new Button("Mark all as read");
        markAll.getStyleClass().add("btn-ghost");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, heading, spacer, markAll);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox list = new VBox(8);
        list.setPadding(new Insets(2));

        Runnable reload = () -> {
            list.getChildren().clear();
            List<Notification> items = notifications.recent(user.id(), 40);
            if (items.isEmpty()) {
                Label empty = new Label("You're all caught up.");
                empty.getStyleClass().add("empty-text");
                empty.setPadding(new Insets(24));
                list.getChildren().add(empty);
            } else {
                for (Notification item : items) {
                    list.getChildren().add(notificationRow(dialog, item));
                }
            }
            markAll.setDisable(notifications.unreadCount(user.id()) == 0);
        };
        reload.run();

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(400);
        scroll.getStyleClass().add("page-scroll");

        VBox content = new VBox(12, header, scroll);
        content.setPadding(new Insets(2, 4, 2, 4));
        dialog.getDialogPane().setContent(content);

        markAll.setOnAction(e -> {
            notifications.markAllRead(user.id());
            refreshNotifications();
            reload.run();
        });

        dialog.showAndWait();
        refreshNotifications();
    }

    private Node notificationRow(Dialog<?> dialog, Notification item) {
        Region dot = new Region();
        dot.getStyleClass().add("notif-dot");
        dot.setMinSize(8, 8);
        dot.setPrefSize(8, 8);
        dot.setMaxSize(8, 8);
        dot.setVisible(!item.read());
        dot.setManaged(!item.read());

        Label title = new Label(item.title());
        title.getStyleClass().add("notif-title");
        title.setWrapText(true);

        Label message = new Label(item.message());
        message.getStyleClass().add("notif-message");
        message.setWrapText(true);

        Label time = new Label(relativeTime(item.createdAt()));
        time.getStyleClass().add("notif-time");

        VBox text = new VBox(3, title, message, time);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, dot, text);
        row.getStyleClass().add("notif-row");
        row.setAlignment(Pos.TOP_LEFT);
        if (!item.read()) {
            row.getStyleClass().add("notif-unread");
        }

        row.setOnMouseClicked(e -> {
            notifications.markRead(item.id(), Session.get().id());
            refreshNotifications();
            dialog.close();
            if (item.isRequestLink()) {
                openNotificationRequest(item.linkId());
            }
        });
        return row;
    }

    private void openNotificationRequest(int requestId) {
        String key = navs.containsKey("requests") ? "requests" : "tasks";
        Nav nav = navs.get(key);
        if (nav == null) {
            return;
        }
        navigate(key);
        openRequest(nav, requestId);
    }

    private static String relativeTime(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "";
        }
        java.time.Duration age = java.time.Duration.between(createdAt, LocalDateTime.now());
        long minutes = age.toMinutes();
        if (minutes < 1) {
            return "Just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = age.toHours();
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = age.toDays();
        if (days < 7) {
            return days + "d ago";
        }
        return createdAt.toLocalDate().toString();
    }

    // ------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------

    @FXML
    private void onOpenSettings() {
        if (drawerOpen) {
            closeDrawer();
        }

        User user = Session.get();
        Window owner = shellRoot.getScene() == null ? null : shellRoot.getScene().getWindow();

        Dialog<ButtonType> dialog = new Dialog<>();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle("Appearance & settings");
        dialog.setHeaderText("Personalise your workspace");

        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        ViewLoader.applyAppStyles(dialog.getDialogPane());
        dialog.getDialogPane().getStyleClass().addAll(shellRoot.getStyleClass());

        ComboBox<String> themeBox = new ComboBox<>();
        themeBox.getItems().addAll("Green (school)", "Navy blue", "Dark");
        themeBox.getStyleClass().add("combo-input");
        themeBox.setMaxWidth(Double.MAX_VALUE);
        themeBox.getSelectionModel().select(themeIndex(user.themeOrDefault()));

        ComboBox<String> densityBox = new ComboBox<>();
        densityBox.getItems().addAll("Comfortable", "Compact");
        densityBox.getStyleClass().add("combo-input");
        densityBox.setMaxWidth(Double.MAX_VALUE);
        densityBox.getSelectionModel().select(
                "compact".equals(user.densityOrDefault()) ? 1 : 0);

        ComboBox<String> fontSizeBox = new ComboBox<>();
        fontSizeBox.getItems().addAll("Default", "Large", "Extra large");
        fontSizeBox.getStyleClass().add("combo-input");
        fontSizeBox.setMaxWidth(Double.MAX_VALUE);
        fontSizeBox.getSelectionModel().select(switch (fontPref()) {
            case "large" -> 1;
            case "xlarge" -> 2;
            default -> 0;
        });

        // Live preview: the dialog applies the choice straight away and puts
        // the previous look back when it is cancelled.
        Runnable preview = () -> {
            Appearance.apply(shellRoot, themeKey(themeBox.getValue()), densityKey(densityBox.getValue()));
            shellRoot.getStyleClass().removeAll("text-large", "text-xlarge");
            String fs = switch (fontSizeBox.getSelectionModel().getSelectedIndex()) {
                case 1 -> "large";
                case 2 -> "xlarge";
                default -> "default";
            };
            if ("large".equals(fs)) shellRoot.getStyleClass().add("text-large");
            if ("xlarge".equals(fs)) shellRoot.getStyleClass().add("text-xlarge");
            dialog.getDialogPane().getStyleClass().setAll("dialog-pane");
            dialog.getDialogPane().getStyleClass().addAll(shellRoot.getStyleClass());
            refreshChrome();
        };
        themeBox.valueProperty().addListener((obs, old, value) -> preview.run());
        densityBox.valueProperty().addListener((obs, old, value) -> preview.run());
        fontSizeBox.valueProperty().addListener((obs, old, value) -> preview.run());

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.add(new Label("Theme"), 0, 0);
        grid.add(themeBox, 1, 0);
        grid.add(new Label("Density"), 0, 1);
        grid.add(densityBox, 1, 1);
        grid.add(new Label("Text size"), 0, 2);
        grid.add(fontSizeBox, 1, 2);

        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(80);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setMinWidth(220);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);

        Label hint = new Label("Themes change the sidebar and accent colours. "
                + "Compact density tightens tables, cards and spacing.");
        hint.getStyleClass().add("form-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(320);

        VBox content = new VBox(12, grid, hint);
        content.setPadding(new Insets(4, 4, 2, 4));
        if (Permissions.canManageUsers(user)) {
            content.getChildren().add(buildBackupSection(owner));
        }
        dialog.getDialogPane().setContent(content);

        String oldFont = fontPref();
        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == save) {
            try {
                User updated = new ProfileService().savePreferences(
                        user, themeKey(themeBox.getValue()), densityKey(densityBox.getValue()));
                String fs = switch (fontSizeBox.getSelectionModel().getSelectedIndex()) {
                    case 1 -> "large";
                    case 2 -> "xlarge";
                    default -> "default";
                };
                PREFS.put("fontSize." + user.id(), fs);
                Appearance.apply(shellRoot, updated);
                applyFontPreference();
                refreshIdentity();
                refreshChrome();
            } catch (SQLException e) {
                Appearance.apply(shellRoot, user);
                PREFS.put("fontSize." + user.id(), oldFont);
                applyFontPreference();
                refreshChrome();
                AlertUtil.error(owner, "Settings",
                        "Could not save your settings: " + e.getMessage());
            }
        } else {
            Appearance.apply(shellRoot, user);
            PREFS.put("fontSize." + user.id(), oldFont);
            applyFontPreference();
            refreshChrome();
        }
    }

    // ------------------------------------------------------------------
    // Backup & restore (administrators)
    // ------------------------------------------------------------------

    private Node buildBackupSection(Window owner) {
        Label title = new Label("Backup & restore");
        title.getStyleClass().add("card-title");

        Label hint = new Label("Save a complete copy of the database, or restore a "
                + "previous backup. Restoring replaces all current records.");
        hint.getStyleClass().add("form-hint");
        hint.setWrapText(true);
        hint.setMaxWidth(340);

        Label status = new Label(lastBackupText());
        status.getStyleClass().add("form-hint");
        status.setWrapText(true);
        status.setMaxWidth(340);

        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);

        Button backup = new Button("Back up now…");
        backup.getStyleClass().add("btn-ghost");
        Button restore = new Button("Restore from file…");
        restore.getStyleClass().add("btn-ghost");

        backup.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save database backup");
            chooser.setInitialDirectory(BackupService.defaultFolder().toFile());
            chooser.setInitialFileName(BackupService.suggestedFileName());
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("SQL dump", "*.sql"));
            File chosen = chooser.showSaveDialog(owner);
            if (chosen == null) {
                return;
            }
            Path target = chosen.toPath();
            if (!target.getFileName().toString().toLowerCase().endsWith(".sql")) {
                target = target.resolveSibling(target.getFileName() + ".sql");
            }
            runBackup(target, status, progress, backup, restore, owner);
        });

        restore.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Choose a backup to restore");
            chooser.setInitialDirectory(BackupService.defaultFolder().toFile());
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("SQL dump", "*.sql"));
            File chosen = chooser.showOpenDialog(owner);
            if (chosen == null) {
                return;
            }
            boolean ok = AlertUtil.confirm(owner, "Restore database",
                    "This will replace ALL current records with the contents of\n"
                            + chosen.getName() + ".\n\nContinue?");
            if (!ok) {
                return;
            }
            runRestore(chosen.toPath(), status, progress, backup, restore, owner);
        });

        HBox buttons = new HBox(8, backup, restore);

        VBox box = new VBox(6, title, hint, buttons, status, progress);
        box.getStyleClass().add("settings-section");
        return box;
    }

    private static String lastBackupText() {
        String last = BackupService.lastBackupAt();
        if (last == null || last.isBlank()) {
            return "No backup has been taken from this PC yet.";
        }
        int split = last.indexOf('|');
        String stamp = split < 0 ? last : last.substring(0, split);
        String path = split < 0 ? "" : last.substring(split + 1);
        return "Last backup: " + stamp + (path.isBlank() ? "" : "  ·  " + path);
    }

    private void runBackup(Path target, Label status, ProgressBar progress,
                           Button backup, Button restore, Window owner) {
        setBackupBusy(true, progress, backup, restore, status, "Preparing backup…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                new BackupService().backup(target, this::updateMessage);
                return null;
            }
        };
        task.messageProperty().addListener((obs, old, message) -> status.setText(message));
        task.setOnSucceeded(e -> {
            setBackupBusy(false, progress, backup, restore, status,
                    "Backup saved to " + target);
            AlertUtil.info(owner, "Backup complete",
                    "The database was saved to:\n" + target.toAbsolutePath());
        });
        task.setOnFailed(e -> {
            setBackupBusy(false, progress, backup, restore, status,
                    "Backup failed. See the message for details.");
            Throwable error = task.getException();
            AlertUtil.error(owner, "Backup failed",
                    error == null ? "Unknown error." : error.getMessage(), error);
        });
        Thread thread = new Thread(task, "deihs-backup");
        thread.setDaemon(true);
        thread.start();
    }

    private void runRestore(Path source, Label status, ProgressBar progress,
                            Button backup, Button restore, Window owner) {
        setBackupBusy(true, progress, backup, restore, status, "Preparing restore…");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                new BackupService().restore(source, this::updateMessage);
                return null;
            }
        };
        task.messageProperty().addListener((obs, old, message) -> status.setText(message));
        task.setOnSucceeded(e -> {
            setBackupBusy(false, progress, backup, restore, status,
                    "Restore finished. Sign out and back in to reload the data.");
            AlertUtil.info(owner, "Restore complete",
                    "The database was restored from " + source.getFileName()
                            + ".\n\nSign out and sign in again so every screen reloads.");
        });
        task.setOnFailed(e -> {
            setBackupBusy(false, progress, backup, restore, status,
                    "Restore failed. See the message for details.");
            Throwable error = task.getException();
            AlertUtil.error(owner, "Restore failed",
                    error == null ? "Unknown error." : error.getMessage(), error);
        });
        Thread thread = new Thread(task, "deihs-restore");
        thread.setDaemon(true);
        thread.start();
    }

    private static void setBackupBusy(boolean busy, ProgressBar progress,
                                      Button backup, Button restore,
                                      Label status, String message) {
        backup.setDisable(busy);
        restore.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        status.setText(message);
    }

    private static int themeIndex(String theme) {
        return switch (theme) {
            case "navy" -> 1;
            case "dark" -> 2;
            default -> 0;
        };
    }

    private static String themeKey(String label) {
        return switch (label == null ? "" : label) {
            case "Navy blue" -> "navy";
            case "Dark" -> "dark";
            default -> "green";
        };
    }

    private static String densityKey(String label) {
        return "Compact".equals(label) ? "compact" : "comfortable";
    }

    // ------------------------------------------------------------------
    // Placeholder for screens that are still being built
    // ------------------------------------------------------------------

    private Node placeholder(Nav nav) {
        String description = switch (nav.key()) {
            case "requests" -> "Search, filter, and open maintenance requests. Submit a new request and follow its progress.";
            case "tasks" -> "The tasks assigned to you, ordered by target date, with quick status updates.";
            case "facilities" -> "The facility registry with building, room, and condition information.";
            case "equipment" -> "Equipment inventory linked to the facility where each item is installed.";
            case "schedules" -> "Preventive maintenance schedule with due dates and assigned personnel.";
            case "users" -> "Create and manage accounts for administrators, maintenance staff, and teachers.";
            case "reports" -> "Request volume, repair costs, and downloadable PDF summaries.";
            case "audit" -> "A read-only record of sign-ins and important system changes.";
            default -> "";
        };

        Label heading = new Label(nav.title());
        heading.getStyleClass().add("page-h1");

        Label sub = new Label("This screen is next in the build queue.");
        sub.getStyleClass().add("page-sub");

        Label emptyTitle = new Label("Not built yet");
        emptyTitle.getStyleClass().add("empty-title");

        Label emptyText = new Label(description);
        emptyText.getStyleClass().add("empty-text");
        emptyText.setWrapText(true);
        emptyText.setMaxWidth(560);

        VBox card = new VBox(6, emptyTitle, emptyText);
        card.getStyleClass().add("card");

        VBox page = new VBox(10, heading, sub, card);
        page.getStyleClass().add("page");

        ScrollPane scroll = new ScrollPane(page);
        scroll.getStyleClass().add("page-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxWidth(Double.MAX_VALUE);
        scroll.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private Node errorView(String title, Exception e) {
        Label heading = new Label(title);
        heading.getStyleClass().add("page-h1");

        Label emptyTitle = new Label("This screen could not be opened");
        emptyTitle.getStyleClass().add("empty-title");

        Label emptyText = new Label(String.valueOf(e.getMessage()));
        emptyText.getStyleClass().add("empty-text");
        emptyText.setWrapText(true);

        VBox card = new VBox(6, emptyTitle, emptyText);
        card.getStyleClass().add("card");

        VBox page = new VBox(10, heading, card);
        page.getStyleClass().add("page");

        ScrollPane scroll = new ScrollPane(page);
        scroll.getStyleClass().add("page-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxWidth(Double.MAX_VALUE);
        scroll.setMaxHeight(Double.MAX_VALUE);
        return scroll;
    }

    // ------------------------------------------------------------------
    // Session
    // ------------------------------------------------------------------

    @FXML
    private void onLogout() {
        Window owner = contentArea.getScene() == null
                ? null : contentArea.getScene().getWindow();
        boolean ok = AlertUtil.confirm(owner,
                "Sign out", "Sign out of the DEIHS Maintenance Management System?");
        if (!ok) {
            return;
        }
        hidePopover();
        Session.clear();
        activeShell = null;
        Parent login = ViewLoader.load("/com/deihs/mms/view/LoginView.fxml");
        Main.setRoot(login);
    }

    public String currentKey() {
        return currentKey;
    }

    // ------------------------------------------------------------------
    // Sidebar row
    // ------------------------------------------------------------------

    /**
     * One navigation row. The highlight is a separate region whose opacity is
     * animated, which gives a smooth hover without repainting the label.
     */
    private final class NavRow {

        private final String key;
        private final StackPane node;
        private final Region highlight;
        private final Region bar;
        private final FadeTransition hoverIn;
        private final FadeTransition hoverOut;
        private boolean active;

        NavRow(Nav nav) {
            key = nav.key();

            node = new StackPane();
            node.getStyleClass().add("nav-row");
            node.setAlignment(Pos.CENTER_LEFT);
            node.setMaxWidth(Double.MAX_VALUE);
            node.setFocusTraversable(false);

            highlight = new Region();
            highlight.getStyleClass().add("nav-highlight");
            highlight.setOpacity(0);
            highlight.setMouseTransparent(true);

            bar = new Region();
            bar.getStyleClass().add("nav-bar");
            bar.setVisible(false);
            bar.setMouseTransparent(true);
            StackPane.setAlignment(bar, Pos.CENTER_LEFT);

            StackPane iconHolder = new StackPane(Icons.of(nav.icon(), 16));
            iconHolder.setMinSize(24, 24);
            iconHolder.setPrefSize(24, 24);
            iconHolder.setMaxSize(24, 24);

            Label label = new Label(nav.title());
            label.getStyleClass().add("nav-label");
            label.setMaxWidth(Double.MAX_VALUE);
            bindSidebarReveal(label);

            HBox content = new HBox(12, iconHolder, label);
            content.setAlignment(Pos.CENTER_LEFT);
            content.setPadding(new Insets(0, 12, 0, 16));
            content.setMouseTransparent(true);

            node.getChildren().addAll(highlight, bar, content);
            node.setOnMouseClicked(e -> navigate(key));

            Tooltip tooltip = new Tooltip(nav.title());
            tooltip.setShowDelay(Duration.millis(400));
            Tooltip.install(node, tooltip);

            hoverIn = new FadeTransition(Duration.millis(110), highlight);
            hoverIn.setToValue(1);
            hoverOut = new FadeTransition(Duration.millis(160), highlight);
            hoverOut.setToValue(0);

            node.setOnMouseEntered(e -> {
                hoverOut.stop();
                hoverIn.playFromStart();
            });
            node.setOnMouseExited(e -> {
                hoverIn.stop();
                if (!active) {
                    hoverOut.playFromStart();
                }
            });
        }

        void setActive(boolean value) {
            active = value;
            node.getStyleClass().remove("nav-active");
            if (value) {
                node.getStyleClass().add("nav-active");
            }
            bar.setVisible(value);
            hoverOut.stop();
            if (value) {
                hoverIn.stop();
                highlight.setOpacity(1);
            } else if (!node.isHover()) {
                hoverOut.playFromStart();
            }
        }
    }
}
