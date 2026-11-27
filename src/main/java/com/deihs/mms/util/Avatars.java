package com.deihs.mms.util;

import com.deihs.mms.model.User;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Turns a user's optional photo into a round avatar node.
 *
 * <p>The photo is painted as a background region with {@code cover} sizing, so
 * a portrait or a landscape picture both fill the circle without stretching.
 * When there is no usable file the initials badge is returned instead, which
 * keeps every screen working even if the image was deleted or the share is
 * offline.</p>
 */
public final class Avatars {

    private Avatars() {
    }

    /** Loads a photo from disk, or null when it is missing / unreadable. */
    public static Image image(String photoPath) {
        if (photoPath == null || photoPath.isBlank()) {
            return null;
        }
        try {
            Path path = Paths.get(photoPath);
            if (!Files.isReadable(path)) {
                return null;
            }
            Image image = new Image(path.toUri().toString(), false);
            return image.isError() ? null : image;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A square, round avatar of the requested size: the photo when one is
     * available, otherwise the user's initials.
     */
    public static Node node(User user, double size) {
        Image image = user == null ? null : image(user.photoPath());
        if (image != null) {
            Region region = new Region();
            region.getStyleClass().add("avatar-photo");
            region.setMinSize(size, size);
            region.setPrefSize(size, size);
            region.setMaxSize(size, size);
            String url = Paths.get(user.photoPath()).toUri().toString();
            region.setStyle("-fx-background-image: url('" + url + "');"
                    + "-fx-background-size: cover;"
                    + "-fx-background-position: center center;"
                    + "-fx-background-repeat: no-repeat;");
            return region;
        }
        Label label = new Label(user == null ? "?" : user.initials());
        label.getStyleClass().add("avatar");
        label.setMinSize(size, size);
        label.setPrefSize(size, size);
        label.setMaxSize(size, size);
        label.setStyle("-fx-font-size: " + Math.round(size * 0.36) + "px;");
        return label;
    }

    /** Replaces the contents of a holder with a fresh avatar node. */
    public static void fill(StackPane holder, User user, double size) {
        holder.getChildren().setAll(node(user, size));
    }
}
