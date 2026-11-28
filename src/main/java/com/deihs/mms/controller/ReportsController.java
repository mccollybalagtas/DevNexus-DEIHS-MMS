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
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
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

import java.math.BigDecimal;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

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
    @FXML private BarChart<String, Number> categoryChart;
    @FXML private BarChart<String, Number> priorityChart;
    @FXML private LineChart<String, Number> trendChart;

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
        downtimeLabel.setText(String.format(Locale.ROOT, "%.1f hrs downtime · parts %s",
                r.totalDowntimeHours(), Format.peso(r.totalPartsCost() == null ? BigDecimal.ZERO : r.totalPartsCost())));

        statusTable.setItems(FXCollections.observableArrayList(r.byStatus()));
        priorityTable.setItems(FXCollections.observableArrayList(r.byPriority()));
        categoryTable.setItems(FXCollections.observableArrayList(r.byCategory()));
        facilityTable.setItems(FXCollections.observableArrayList(r.byFacility()));
        renderCharts(r);
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
            w.write('\uFEFF'); // UTF-8 BOM for Excel
            csvLine(w, "Dasmarinas East Integrated High School");
            csvLine(w, "DEIHS Maintenance Management System — Maintenance Report");
            csvLine(w, "Generated", Format.dateTime(java.time.LocalDateTime.now()));
            csvLine(w, "Reporting Period", Format.date(current.from()) + " to " + Format.date(current.to()));
            csvLine(w);
            csvLine(w, "SUMMARY");
            csvLine(w, "Metric", "Value");
            csvLine(w, "Total Requests", String.valueOf(current.totalRequests()));
            csvLine(w, "Open Requests", String.valueOf(current.openRequests()));
            csvLine(w, "Completed/Closed", String.valueOf(current.completedRequests()));
            csvLine(w, "Overdue (Open)", String.valueOf(current.overdueRequests()));
            csvLine(w, "Total Repair Cost", Format.peso(current.totalCost()));
            csvLine(w, "Total Parts Cost", Format.peso(current.totalPartsCost() == null ? BigDecimal.ZERO : current.totalPartsCost()));
            csvLine(w, "Total Downtime (Hours)", String.format(Locale.ROOT, "%.1f", current.totalDowntimeHours()));
            csvLine(w);
            writeSectionCsv(w, "Requests by Status", current.byStatus(), true);
            writeSectionCsv(w, "Requests by Priority", current.byPriority(), true);
            writeSectionCsv(w, "Requests by Category", current.byCategory(), false);
            writeSectionCsv(w, "Top Facilities by Requests", current.byFacility(), false);
            writeSectionCsv(w, "Requests by Month", current.byMonth(), false);
        } catch (IOException ex) {
            AlertUtil.error(ownerWindow(), "Export failed", ex.getMessage());
            return;
        }
        AlertUtil.info(ownerWindow(), "Exported", "CSV report saved to:\n" + file.getAbsolutePath());
    }

    private void csvLine(BufferedWriter w, String... cols) throws IOException {
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) w.write(',');
            w.write(csv(cols[i]));
        }
        w.newLine();
    }

    private void writeSectionCsv(BufferedWriter w, String title, List<CountRow> rows, boolean pretty) throws IOException {
        csvLine(w, title);
        csvLine(w, "Label", "Count");
        if (rows == null || rows.isEmpty()) {
            csvLine(w, "No data for this period", "0");
        } else {
            for (CountRow r : rows) {
                String label = pretty ? Format.pretty(r.label()) : blank(r.label());
                csvLine(w, label, String.valueOf(r.count()));
            }
        }
        csvLine(w);
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
            PdfWriter pdf = new PdfWriter(doc);
            pdf.newPage();
            pdf.header();
            pdf.summary(current);
            pdf.section("Requests by Status", current.byStatus(), true);
            pdf.section("Requests by Priority", current.byPriority(), true);
            pdf.section("Requests by Category", current.byCategory(), false);
            pdf.section("Top Facilities by Requests", current.byFacility(), false);
            pdf.section("Requests by Month", current.byMonth(), false);
            pdf.footerAll();
            doc.save(file);
        } catch (Exception ex) {
            AlertUtil.error(ownerWindow(), "Export failed", ex.getMessage());
            return;
        }
        AlertUtil.info(ownerWindow(), "Exported", "PDF report saved to:\n" + file.getAbsolutePath());
    }

    private static final class PdfWriter {
        private final PDDocument doc;
        private PDPage page;
        private PDPageContentStream cs;
        private float y;
        private int pageNo;
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private static final float MARGIN = 54;
        private static final float BOTTOM = 54;
        private PDImageXObject logo;

        PdfWriter(PDDocument doc) { this.doc = doc; }

        private PDImageXObject loadLogo() {
            if (logo != null) return logo;
            String[] candidates = {"/com/deihs/mms/img/deihs-logo.png", "/com/deihs/mms/img/deihs-logo-small.png", "/com/deihs/mms/img/deihs-logo.jpg"};
            for (String c : candidates) {
                try (InputStream in = ReportsController.class.getResourceAsStream(c)) {
                    if (in == null) continue;
                    BufferedImage img = ImageIO.read(in);
                    if (img == null) continue;
                    logo = LosslessFactory.createFromImage(doc, img);
                    return logo;
                } catch (Exception ignored) { }
            }
            return null;
        }

        private void drawWatermark() throws IOException {
            PDImageXObject wm = loadLogo();
            if (wm == null) return;
            float pw = page.getMediaBox().getWidth();
            float ph = page.getMediaBox().getHeight();
            float wmW = Math.min(pw * 0.45f, 260f);
            float wmH = wmW * ((float) wm.getHeight() / wm.getWidth());
            float x = (pw - wmW) / 2f;
            float wy = (ph - wmH) / 2f;
            cs.saveGraphicsState();
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.07f);
            gs.setStrokingAlphaConstant(0.07f);
            cs.setGraphicsStateParameters(gs);
            cs.drawImage(wm, x, wy, wmW, wmH);
            cs.restoreGraphicsState();
        }

        void newPage() throws IOException {
            if (cs != null) { cs.close(); }
            page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            pageNo++;
            cs = new PDPageContentStream(doc, page);
            drawWatermark();
            y = page.getMediaBox().getHeight() - MARGIN;
        }

        float width() { return page.getMediaBox().getWidth() - MARGIN * 2; }

        void ensureSpace(float needed) throws IOException {
            if (y - needed < BOTTOM) newPage();
        }

        void header() throws IOException {
            float w = width();
            // header band
            cs.setNonStrokingColor(0.055f, 0.420f, 0.204f); // #0E6B34
            cs.addRect(MARGIN, y - 42, w, 48);
            cs.fill();
            cs.setNonStrokingColor(1f, 1f, 1f);
            PDImageXObject seal = loadLogo();
            float textX = MARGIN + 14;
            if (seal != null) {
                float sealH = 34;
                float sealW = sealH * ((float) seal.getWidth() / seal.getHeight());
                cs.drawImage(seal, MARGIN + 10, y - 36, sealW, sealH);
                textX = MARGIN + 16 + sealW;
            }
            textAt("Dasmarinas East Integrated High School", textX, y - 10, bold, 11);
            textAt("Maintenance Management System - Report", textX, y - 28, regular, 10);
            cs.setNonStrokingColor(0.12f, 0.16f, 0.20f);
            y -= 58;
            drawLine(MARGIN, y, MARGIN + w, y, 0.85f, 0.85f, 0.85f);
            y -= 14;
            textAt("Generated: " + Format.dateTime(java.time.LocalDateTime.now()), MARGIN, y, regular, 9);
            y -= 18;
        }

        void summary(ReportSummary r) throws IOException {
            ensureSpace(130);
            sectionTitle("Report Summary");
            textAt("Period: " + Format.date(r.from()) + " to " + Format.date(r.to()), MARGIN, y, regular, 10);
            y -= 16;
            float colW = width() / 3f;
            summaryBox(MARGIN, y, colW - 6, "Total Requests", String.valueOf(r.totalRequests()));
            summaryBox(MARGIN + colW, y, colW - 6, "Open Requests", String.valueOf(r.openRequests()));
            summaryBox(MARGIN + colW * 2, y, colW - 6, "Completed/Closed", String.valueOf(r.completedRequests()));
            y -= 52;
            summaryBox(MARGIN, y, colW - 6, "Overdue (Open)", String.valueOf(r.overdueRequests()));
            summaryBox(MARGIN + colW, y, colW - 6, "Repair Cost", Format.peso(r.totalCost()));
            summaryBox(MARGIN + colW * 2, y, colW - 6, "Parts Cost", Format.peso(r.totalPartsCost() == null ? BigDecimal.ZERO : r.totalPartsCost()));
            y -= 52;
            summaryBox(MARGIN, y, colW - 6, "Downtime", String.format(Locale.ROOT, "%.1f hrs", r.totalDowntimeHours()));
            y -= 62;
        }

        void summaryBox(float x, float topY, float w, String label, String value) throws IOException {
            cs.setNonStrokingColor(0.965f, 0.976f, 0.965f);
            cs.addRect(x, topY - 40, w, 44);
            cs.fill();
            cs.setStrokingColor(0.863f, 0.898f, 0.867f);
            cs.addRect(x, topY - 40, w, 44);
            cs.stroke();
            cs.setNonStrokingColor(0.42f, 0.47f, 0.52f);
            textAt(label.toUpperCase(Locale.ROOT), x + 10, topY - 13, bold, 8);
            cs.setNonStrokingColor(0.12f, 0.16f, 0.20f);
            textAt(value, x + 10, topY - 30, bold, 13);
        }

        void section(String title, List<CountRow> rows, boolean pretty) throws IOException {
            ensureSpace(70);
            sectionTitle(title);
            tableHeader();
            if (rows == null || rows.isEmpty()) {
                ensureSpace(20);
                cs.setNonStrokingColor(0.42f, 0.47f, 0.52f);
                textAt("No data for this period.", MARGIN + 8, y, regular, 9);
                y -= 18;
            } else {
                for (CountRow r : rows) {
                    ensureSpace(20);
                    String label = pretty ? Format.pretty(r.label()) : blank(r.label());
                    tableRow(label, String.valueOf(r.count()));
                }
            }
            y -= 10;
        }

        void sectionTitle(String title) throws IOException {
            ensureSpace(24);
            cs.setNonStrokingColor(0.055f, 0.420f, 0.204f);
            textAt(title, MARGIN, y, bold, 12);
            y -= 6;
            drawLine(MARGIN, y, MARGIN + width(), y, 0.863f, 0.898f, 0.867f);
            y -= 14;
            cs.setNonStrokingColor(0.12f, 0.16f, 0.20f);
        }

        void tableHeader() throws IOException {
            ensureSpace(22);
            float w = width();
            cs.setNonStrokingColor(0.933f, 0.953f, 0.933f);
            cs.addRect(MARGIN, y - 14, w, 20);
            cs.fill();
            cs.setNonStrokingColor(0.33f, 0.38f, 0.43f);
            textAt("LABEL", MARGIN + 8, y - 8, bold, 8.5f);
            textAt("COUNT", MARGIN + w - 60, y - 8, bold, 8.5f);
            y -= 22;
            cs.setNonStrokingColor(0.12f, 0.16f, 0.20f);
        }

        void tableRow(String label, String count) throws IOException {
            float w = width();
            drawLine(MARGIN, y - 12, MARGIN + w, y - 12, 0.92f, 0.93f, 0.94f);
            cs.setNonStrokingColor(0.12f, 0.16f, 0.20f);
            textAt(truncate(label, 78), MARGIN + 8, y - 6, regular, 9.5f);
            textAt(count, MARGIN + w - 60, y - 6, regular, 9.5f);
            y -= 20;
        }

        void drawLine(float x1, float y1, float x2, float y2, float r, float g, float b) throws IOException {
            cs.setStrokingColor(r, g, b);
            cs.setLineWidth(0.7f);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        void textAt(String s, float x, float ty, org.apache.pdfbox.pdmodel.font.PDFont font, float size) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, ty);
            cs.showText(safePdf(s));
            cs.endText();
        }

        void footerAll() throws IOException {
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage p = doc.getPage(i);
                try (PDPageContentStream fcs = new PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    float pw = p.getMediaBox().getWidth();
                    fcs.setStrokingColor(0.863f, 0.898f, 0.867f);
                    fcs.moveTo(MARGIN, 42);
                    fcs.lineTo(pw - MARGIN, 42);
                    fcs.stroke();
                    fcs.beginText();
                    fcs.setNonStrokingColor(0.42f, 0.47f, 0.52f);
                    fcs.setFont(regular, 8);
                    fcs.newLineAtOffset(MARGIN, 30);
                    fcs.showText(safePdf("DEIHS MMS - Confidential Maintenance Report"));
                    fcs.endText();
                    String pn = "Page " + (i + 1) + " of " + total;
                    float pnW = regular.getStringWidth(safePdf(pn)) / 1000 * 8;
                    fcs.beginText();
                    fcs.setFont(regular, 8);
                    fcs.newLineAtOffset(pw - MARGIN - pnW, 30);
                    fcs.showText(safePdf(pn));
                    fcs.endText();
                }
            }
            if (cs != null) { cs.close(); cs = null; }
        }

        private static String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max - 3) + "...";
        }
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

    private void renderCharts(ReportSummary r) {
        fillBarChart(categoryChart, r.byCategory(), false);
        fillBarChart(priorityChart, r.byPriority(), true);
        fillLineChart(trendChart, r.byMonth());
    }

    private void fillBarChart(BarChart<String, Number> chart, List<CountRow> rows, boolean pretty) {
        if (chart == null) return;
        chart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        if (rows != null) {
            for (CountRow row : rows) {
                String label = pretty ? Format.pretty(row.label()) : blank(row.label());
                series.getData().add(new XYChart.Data<>(label, row.count()));
            }
        }
        chart.getData().add(series);
    }

    private void fillLineChart(LineChart<String, Number> chart, List<CountRow> rows) {
        if (chart == null) return;
        chart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        if (rows != null) {
            for (CountRow row : rows) {
                series.getData().add(new XYChart.Data<>(blank(row.label()), row.count()));
            }
        }
        chart.getData().add(series);
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
