package com.deihs.mms.controller;

import com.deihs.mms.Main;
import com.deihs.mms.model.Role;
import com.deihs.mms.model.User;
import com.deihs.mms.service.Permissions;
import com.deihs.mms.service.Session;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Icons;
import com.deihs.mms.util.ViewLoader;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The application frame: sidebar navigation, top bar, and the content area
 * where each screen is loaded.
 */
public class ShellController {

    @FXML private VBox menuBox;
    @FXML private StackPane contentArea;
    @FXML private Label pageTitle;
    @FXML private Label avatarLabel;
    @FXML private Label userNameLabel;
    @FXML private Label userRoleLabel;
    @FXML private Label scopeLabel;

    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Map<String, Nav> navs = new LinkedHashMap<>();
    private String currentKey = "dashboard";

    private record Nav(String key, String title, String icon) {
    }

    @FXML
    private void initialize() {
        User user = Session.get();

        avatarLabel.setText(user.initials());
        userNameLabel.setText(user.fullName());
        userRoleLabel.setText(user.roleName());
        scopeLabel.setText(switch (user.role()) {
            case PERSONNEL -> "Showing my assigned tasks";
            case STAFF -> "Showing my own requests";
            default -> "All school records";
        });

        buildMenu(user);
        navigate("dashboard");
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void buildMenu(User user) {
        for (Nav nav : navFor(user)) {
            navs.put(nav.key(), nav);

            Node iconNode = Icons.of(nav.icon(), 16);

            Button item = new Button(nav.title(), iconNode);
            item.getStyleClass().add("nav-item");
            item.setMaxWidth(Double.MAX_VALUE);
            item.setOnAction(e -> navigate(nav.key()));

            navButtons.put(nav.key(), item);
            menuBox.getChildren().add(item);
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

        navButtons.forEach((k, button) -> button.getStyleClass().remove("active"));
        Button active = navButtons.get(key);
        if (active != null && !active.getStyleClass().contains("active")) {
            active.getStyleClass().add("active");
        }

        contentArea.getChildren().setAll(loadView(nav));
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
        boolean ok = AlertUtil.confirm(contentArea.getScene().getWindow(),
                "Sign out", "Sign out of the DEIHS Maintenance Management System?");
        if (!ok) {
            return;
        }
        Session.clear();
        Parent login = ViewLoader.load("/com/deihs/mms/view/LoginView.fxml");
        Main.setRoot(login);
    }

    public String currentKey() {
        return currentKey;
    }
}
