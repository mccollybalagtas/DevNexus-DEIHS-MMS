package com.deihs.mms;

import com.deihs.mms.db.Database;
import com.deihs.mms.db.SchemaUpgrade;
import com.deihs.mms.util.ViewLoader;
import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.sql.SQLException;
import java.util.Objects;

/**
 * Application entry point. Opens the login screen; the shell is loaded
 * after a successful sign-in.
 */
public class Main extends Application {

    public static final String APP_NAME = "DEIHS Maintenance Management System";
    public static final String STYLESHEET = "/com/deihs/mms/css/styles.css";

    private static Scene mainScene;

    @Override
    public void start(Stage stage) {
        // Add the profile / theme / verification columns when upgrading an
        // existing install. A database that is not running yet must not stop
        // the login screen from opening, so failure is logged and retried
        // after a successful sign-in.
        try {
            SchemaUpgrade.ensure();
        } catch (SQLException e) {
            System.err.println("Schema upgrade deferred: " + e.getMessage());
        }

        Parent root = ViewLoader.load("/com/deihs/mms/view/LoginView.fxml");
        mainScene = new Scene(root, 1180, 720);
        mainScene.getStylesheets().add(
                Objects.requireNonNull(Main.class.getResource(STYLESHEET)).toExternalForm());

        stage.setTitle(APP_NAME);
        stage.getIcons().add(new Image(
                Objects.requireNonNull(Main.class.getResourceAsStream("/com/deihs/mms/img/deihs-logo.jpg"))));
        // DevNexus credit icon - added alongside the existing app icon.
        stage.getIcons().add(new Image(
                Objects.requireNonNull(Main.class.getResourceAsStream("/com/deihs/mms/img/devnexus-logo.png"))));
        stage.setScene(mainScene);
        stage.setMinWidth(1024);
        stage.setMinHeight(640);
        stage.setMaximized(true);
        stage.show();
    }

    @Override
    public void stop() {
        Database.close();
    }

    /** Replaces the root of the main scene (used to switch login -> shell). */
    public static void setRoot(Parent root) {
        mainScene.setRoot(root);
    }

    public static Scene getScene() {
        return mainScene;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
