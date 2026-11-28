package com.deihs.mms.util;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Shows a printable QR asset tag for a facility or equipment item. */
public final class AssetTagDialog {

    private AssetTagDialog() {}

    public static void show(Window owner, String type, String code, String name, String location, String detail) {
        String payload = "DEIHS-ASSET|" + safe(type) + "|" + safe(code) + "|" + safe(name);
        ImageView qr = new ImageView();
        qr.setPreserveRatio(true);
        qr.setFitWidth(220);
        qr.setFitHeight(220);
        try {
            qr.setImage(QrCode.toFxImage(payload, 8, 2));
        } catch (Exception ex) {
            qr.setImage(null);
        }

        Label school = new Label("Dasmarinas East Integrated High School");
        school.getStyleClass().add("asset-tag-school");
        school.setWrapText(true);
        Label heading = new Label(type + " Asset Tag");
        heading.getStyleClass().add("asset-tag-title");
        Label nameLabel = new Label(nullToDash(name));
        nameLabel.getStyleClass().add("asset-tag-name");
        nameLabel.setWrapText(true);

        VBox info = new VBox(4,
                kv("Code", code),
                kv("Location", location),
                kv("Details", detail),
                kv("QR payload", payload)
        );
        info.getStyleClass().add("asset-tag-info");

        VBox card = new VBox(10, school, heading, qr, nameLabel, info);
        card.setAlignment(Pos.TOP_CENTER);
        card.setPadding(new Insets(18));
        card.getStyleClass().add("asset-tag-card");
        card.setMaxWidth(420);

        Button print = new Button("Print Tag");
        print.getStyleClass().add("btn-primary");
        print.setOnAction(e -> printNode(owner, card));

        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(type + " QR Tag");
        dialog.getDialogPane().setContent(card);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Node close = dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        close.getStyleClass().add("btn-ghost");
        HBox actions = new HBox(8, print);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(0, 4, 8, 4));
        dialog.getDialogPane().setExpandableContent(null);
        ((VBox) dialog.getDialogPane().getContent()).getChildren().add(actions);
        ViewLoader.applyAppStyles(dialog.getDialogPane());
        dialog.showAndWait();
    }

    private static HBox kv(String k, String v) {
        Label key = new Label(k + ":");
        key.getStyleClass().add("asset-tag-key");
        key.setMinWidth(72);
        Label val = new Label(nullToDash(v));
        val.getStyleClass().add("asset-tag-val");
        val.setWrapText(true);
        HBox.setHgrow(val, Priority.ALWAYS);
        HBox row = new HBox(8, key, val);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }

    private static void printNode(Window owner, Node node) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            AlertUtil.warn(owner, "Printing unavailable", "No printer was found on this system.");
            return;
        }
        if (!job.showPrintDialog(owner)) {
            return;
        }
        boolean ok = job.printPage(node);
        if (ok) {
            job.endJob();
            AlertUtil.info(owner, "Print sent", "The asset tag was sent to the printer.");
        } else {
            AlertUtil.error(owner, "Print failed", "The asset tag could not be printed.");
        }
    }

    private static String safe(String v) { return v == null ? "" : v.replace("|", "/").trim(); }
    private static String nullToDash(String v) { return v == null || v.isBlank() ? "—" : v.trim(); }
}
