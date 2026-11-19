package com.deihs.mms.controller;

import com.deihs.mms.dao.ReportDao;
import com.deihs.mms.model.ReportSummary;
import com.deihs.mms.model.ReportSummary.CountRow;
import com.deihs.mms.util.AlertUtil;
import com.deihs.mms.util.Format;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public class ReportsController {

    @FXML private DatePicker fromPicker;
    @FXML private DatePicker toPicker;
    @FXML private Label rangeLabel;
    @FXML private Label totalLabel;
    @FXML private Label openLabel;
    @FXML private Label completedLabel;
    @FXML private Label overdueLabel;
    @FXML private Label costLabel;
    @FXML private Label downtimeLabel;

    @FXML private TableView<CountRow> statusTable;
    @FXML private TableColumn<CountRow, String> statusNameCol;
    @FXML private TableColumn<CountRow, String> statusCountCol;
    @FXML private TableView<CountRow> priorityTable;
    @FXML private TableColumn<CountRow, String> priorityNameCol;
    @FXML private TableColumn<CountRow, String> priorityCountCol;
    @FXML private TableView<CountRow> categoryTable;
    @FXML private TableColumn<CountRow, String> categoryNameCol;
    @FXML private TableColumn<CountRow, String> categoryCountCol;
    @FXML private TableView<CountRow> facilityTable;
    @FXML private TableColumn<CountRow, String> facilityNameCol;
    @FXML private TableColumn<CountRow, String> facilityCountCol;

    private final ReportDao reportDao = new ReportDao();
    private ReportSummary current;
    private int generation;

    @FXML
    private void initialize() {
        LocalDate today = LocalDate.now();
        fromPicker.setValue(today.withDayOfMonth(1));
        toPicker.setValue(today);

        setupTable(statusTable, statusNameCol, statusCountCol, true);
        setupTable(priorityTable, priorityNameCol, priorityCountCol, true);
        setupTable(categoryTable, categoryNameCol, categoryCountCol, false);
        setupTable(facilityTable, facilityNameCol, facilityCountCol, false);

        onGenerate();
    }

    private void setupTable(TableView<CountRow> table, TableColumn<CountRow, String> nameCol,
                            TableColumn<CountRow, String> countCol, boolean prettyName) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No data for this period."));
        nameCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                prettyName ? Format.pretty(c.getValue().label()) : blank(c.getValue().label())));
        countCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(String.valueOf(c.getValue().count())));
    }

    @FXML
    private void onGenerate() {
        LocalDate from = fromPicker.getValue();
        LocalDate to = toPicker.getValue();
        if (from != null && to != null && to.isBefore(from)) {
            AlertUtil.error(ownerWindow(), "Invalid range", "'To' date must be on or after 'From' date.");
            return;
        }
        final int req = ++generation;
        Task<ReportSummary> task = new Task<>() {
            @Override protected ReportSummary call() throws Exception {
                return reportDao.summarize(from, to);
            }
        };
        task.setOnSucceeded(e -> { if (req == generation) apply(task.getValue()); });
        task.setOnFailed(e -> {
            if (req != generation) return;
            Throwable ex = task.getException();
            AlertUtil.error(ownerWindow(), "Could not generate report",
                    ex == null ? "Unknown error." : ex.getMessage());
        });
        Thread t = new Thread(task, "generate-report");
        t.setDaemon(true);
        t.start();
    }

    private void apply(ReportSummary r) {
        current = r;
        rangeLabel.setText(Format.date(r.from()) + " – " + Format.date(r.to()));
        totalLabel.setText(String.valueOf(r.totalRequests()));
        openLabel.setText(String.valueOf(r.openRequests()));
        completedLabel.setText(String.valueOf(r.completedRequests()));
        overdueLabel.setText(String.valueOf(r.overdueRequests()));
        costLabel.setText(Format.peso(r.totalCost()));
        downtimeLabel.setText(String.format(Locale.ROOT, "%.1f hrs downtime", r.totalDowntimeHours()));

        statusTable.setItems(FXCollections.observableArrayList(r.byStatus()));
        priorityTable.setItems(FXCollections.observableArrayList(r.byPriority()));
        categoryTable.setItems(FXCollections.observableArrayList(r.byCategory()));
        facilityTable.setItems(FXCollections.observableArrayList(r.byFacility()));
    }

    @FXML
    private void onExportCsv() {
        if (current == null) { AlertUtil.error(ownerWindow(), "No report", "Generate a report first."); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Report (CSV)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        chooser.setInitialFileName("deihs-report-" + current.from() + "-to-" + current.to() + ".csv");
        File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) return;
        try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            w.write("DEIHS Maintenance Report"); w.newLine();
            w.write("Period," + current.from() + " to " + current.to()); w.newLine();
            w.write("Total Requests," + current.totalRequests()); w.newLine();
            w.write("Open," + current.openRequests()); w.newLine();
            w.write("Completed/Closed," + current.completedRequests()); w.newLine();
            w.write("Overdue (open)," + current.overdueRequests()); w.newLine();
            w.write("Total Repair Cost," + Format.peso(current.totalCost())); w.newLine();
            w.write("Total Downtime Hours," + current.totalDowntimeHours()); w.newLine();
            w.newLine();
            writeSectionCsv(w, "By Status", current.byStatus(), true);
            writeSectionCsv(w, "By Priority", current.byPriority(), true);
            writeSectionCsv(w, "By Category", current.byCategory(), false);
            writeSectionCsv(w, "By Facility (Top 10)", current.byFacility(), false);
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Export failed", ex.getMessage());
            return;
        }
        AlertUtil.info(ownerWindow(), "Exported", "CSV report saved to:\n" + file.getAbsolutePath());
    }

    private void writeSectionCsv(BufferedWriter w, String title, List<CountRow> rows, boolean pretty) throws IOException {
        w.write(title); w.newLine();
        w.write("Label,Count"); w.newLine();
        for (CountRow r : rows) {
            String label = pretty ? Format.pretty(r.label()) : blank(r.label());
            w.write(csv(label) + "," + r.count()); w.newLine();
        }
        w.newLine();
    }

    @FXML
    private void onExportPdf() {
        if (current == null) { AlertUtil.error(ownerWindow(), "No report", "Generate a report first."); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Report (PDF)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        chooser.setInitialFileName("deihs-report-" + current.from() + "-to-" + current.to() + ".pdf");
        File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) return;

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - 50;
                float x = 50;

                y = text(cs, "DEIHS Maintenance Report", x, y, bold, 16, 22);
                y = text(cs, "Period: " + Format.date(current.from()) + " – " + Format.date(current.to()), x, y, regular, 11, 18);
                y = text(cs, "Total requests: " + current.totalRequests()
                        + "   Open: " + current.openRequests()
                        + "   Completed/Closed: " + current.completedRequests()
                        + "   Overdue (open): " + current.overdueRequests(), x, y, regular, 10, 14);
                y = text(cs, "Total repair cost: " + Format.peso(current.totalCost())
                        + "   Total downtime: " + String.format(Locale.ROOT, "%.1f hrs", current.totalDowntimeHours()),
                        x, y, regular, 10, 20);

                y = sectionPdf(cs, "By Status", current.byStatus(), true, x, y, bold, regular);
                y = sectionPdf(cs, "By Priority", current.byPriority(), true, x, y, bold, regular);
                y = sectionPdf(cs, "By Category", current.byCategory(), false, x, y, bold, regular);
                sectionPdf(cs, "Top Facilities", current.byFacility(), false, x, y, bold, regular);
            }

            doc.save(file);
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Export failed", ex.getMessage());
            return;
        }
        AlertUtil.info(ownerWindow(), "Exported", "PDF report saved to:\n" + file.getAbsolutePath());
    }

    private float sectionPdf(PDPageContentStream cs, String title, List<CountRow> rows, boolean pretty,
                             float x, float y, PDType1Font bold, PDType1Font regular) throws IOException {
        if (y < 80) return y; // simple single-page guard
        y = text(cs, title, x, y, bold, 11, 14);
        if (rows.isEmpty()) {
            return text(cs, "  No data", x, y, regular, 10, 14);
        }
        for (CountRow r : rows) {
            if (y < 60) break;
            String label = pretty ? Format.pretty(r.label()) : blank(r.label());
            y = text(cs, "  - " + label + ": " + r.count(), x, y, regular, 10, 13);
        }
        return y - 6;
    }

    private static float text(PDPageContentStream cs, String s, float x, float y,
                              org.apache.pdfbox.pdmodel.font.PDFont font, float size, float leading) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(safePdf(s));
        cs.endText();
        return y - leading;
    }

    private static String safePdf(String s) {
        if (s == null) return "";
        // Type1 Helvetica can't encode all Unicode (e.g. peso sign); replace unsupported chars.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u20B1') { out.append("PHP "); continue; } // ₱
            if (c >= 32 && c <= 126 || c >= 160 && c <= 255) out.append(c);
            else out.append('?');
        }
        return out.toString();
    }

    private static String csv(String value) {
        if (value == null) return "";
        String v = value.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v + "\"";
        }
        return v;
    }

    private static String blank(String v) { return v == null || v.isBlank() ? "—" : v; }

    private Window ownerWindow() {
        return rangeLabel.getScene() == null ? null : rangeLabel.getScene().getWindow();
    }
}
