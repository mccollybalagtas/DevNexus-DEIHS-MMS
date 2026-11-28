package com.deihs.mms.controller;

import com.deihs.mms.model.Part;
import com.deihs.mms.model.PartDraft;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import java.math.BigDecimal;

/**
 * Add/edit form for a part. The dialog owns save/cancel; this controller only
 * collects and pre-fills values. Numeric parsing is deliberately forgiving so
 * a blank field is treated as zero rather than throwing.
 */
public class PartFormController {

    @FXML private Label errorLabel;
    @FXML private TextField nameField;
    @FXML private TextField skuField;
    @FXML private TextField unitField;
    @FXML private TextField quantityField;
    @FXML private TextField reorderField;
    @FXML private TextField costField;

    @FXML
    private void initialize() {
        unitField.setText("pc");
        quantityField.setText("0");
        reorderField.setText("0");
        costField.setText("0.00");
    }

    public void setPart(Part part) {
        if (part == null) {
            return;
        }
        nameField.setText(part.name());
        skuField.setText(part.sku());
        unitField.setText(part.unit());
        quantityField.setText(String.valueOf(part.quantity()));
        reorderField.setText(String.valueOf(part.reorderLevel()));
        costField.setText(part.unitCost() == null ? "0.00" : part.unitCost().toPlainString());
    }

    public PartDraft draft() {
        return new PartDraft(
                nameField.getText(),
                skuField.getText(),
                unitField.getText(),
                parseInt(quantityField.getText()),
                parseInt(reorderField.getText()),
                parseDecimal(costField.getText())
        );
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return Integer.valueOf(raw.trim().replace(",", ""));
    }

    private static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw.trim().replace(",", ""));
    }

    public void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    public void clearError() {
        errorLabel.setText(null);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
