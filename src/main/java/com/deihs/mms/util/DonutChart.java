package com.deihs.mms.util;

import com.deihs.mms.model.DashboardData.StatusSlice;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;

import java.util.List;

/**
 * A small donut chart drawn on a Canvas. JavaFX has no built-in donut, and
 * a Canvas keeps it crisp at any window size.
 */
public class DonutChart extends StackPane {

    private static final double RING = 15;

    private final Canvas canvas;
    private final Label valueLabel = new Label("0");
    private final Label captionLabel = new Label("total");

    private List<StatusSlice> slices = List.of();
    private int total;

    public DonutChart(double size) {
        this.canvas = new Canvas(size, size);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);

        valueLabel.getStyleClass().add("donut-value");
        captionLabel.getStyleClass().add("donut-caption");

        VBox center = new VBox(valueLabel, captionLabel);
        center.setAlignment(javafx.geometry.Pos.CENTER);
        center.setMouseTransparent(true);

        getChildren().addAll(canvas, center);
        draw();
    }

    public void setData(List<StatusSlice> slices, int total) {
        this.slices = slices == null ? List.of() : slices;
        this.total = total;
        valueLabel.setText(String.valueOf(total));
        draw();
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);

        double pad = RING / 2 + 2;
        double size = Math.min(w, h) - pad * 2;
        double x = (w - size) / 2;
        double y = (h - size) / 2;

        g.setLineWidth(RING);
        g.setLineCap(StrokeLineCap.BUTT);

        // Track
        g.setStroke(Color.web("#EDF2ED"));
        g.strokeOval(x, y, size, size);

        if (total <= 0) {
            return;
        }

        double start = 90; // 12 o'clock, counter-clockwise in JavaFX arcs
        for (StatusSlice slice : slices) {
            if (slice.count() <= 0) {
                continue;
            }
            double extent = -360.0 * slice.count() / total;
            g.setStroke(Color.web(slice.color()));

            // Leave a hairline gap between segments for readability.
            double gap = Math.min(2.0, Math.abs(extent) * 0.15);
            double drawExtent = extent > 0 ? extent - gap : extent + gap;
            g.strokeArc(x, y, size, size, start - Math.signum(extent) * gap / 2,
                    drawExtent, javafx.scene.shape.ArcType.OPEN);
            start += extent;
        }
    }
}
