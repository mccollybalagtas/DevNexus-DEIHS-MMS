package com.deihs.mms.util;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/**
 * The green-and-gold school panel shared by the sign-in and registration
 * screens. Building it once in code keeps the two pages visually identical:
 * the photo, the overlay, the crest and the school wording can never drift
 * apart the way two hand-maintained FXML blocks would.
 *
 * <p>The photo is a background region rather than an {@code ImageView} so it
 * always covers the panel. The focal point is nudged to 40% from the top: the
 * source picture is wider than it is tall, and centring it cropped the
 * building's roofline.</p>
 */
public class BrandPanel extends StackPane {

    private static final String PHOTO =
            "/com/deihs/mms/img/login-building.jpg";
    private static final String CREST =
            "/com/deihs/mms/img/deihs-logo-small.png";

    private final Label line1 = new Label("DASMARIÑAS EAST");
    private final Label line2 = new Label("INTEGRATED HIGH SCHOOL");
    private final Label location = new Label("San Simon, City of Dasmariñas");
    private final Label footer = new Label("Maintenance Management System");

    public BrandPanel() {
        getStyleClass().add("login-brand");
        setMinWidth(420);

        getChildren().addAll(
                photoLayer(),
                new Region() {{
                    getStyleClass().add("login-brand-overlay");
                    setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                }},
                accentCircle(330, "#3B82F6", 0.10, -280, -250),
                accentCircle(260, "#60A5FA", 0.07, 330, 280),
                beam(),
                centreBlock(),
                footer());

        setPrefWidth(560);
        applyDisplayFont();
    }

    private Region photoLayer() {
        Region region = new Region();
        region.getStyleClass().add("login-building-layer");
        region.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        var url = BrandPanel.class.getResource(PHOTO);
        if (url != null) {
            region.setStyle("-fx-background-image: url('" + url.toExternalForm() + "');"
                    + "-fx-background-size: cover;"
                    + "-fx-background-position: center center;"
                    + "-fx-background-repeat: no-repeat;");
        }
        return region;
    }

    private Circle accentCircle(double radius, String fill, double opacity,
                                double x, double y) {
        Circle circle = new Circle(radius);
        circle.setFill(javafx.scene.paint.Color.web(fill, opacity));
        circle.setTranslateX(x);
        circle.setTranslateY(y);
        circle.setMouseTransparent(true);
        return circle;
    }

    private Region beam() {
        Region region = new Region();
        region.getStyleClass().add("login-brand-beam");
        region.setPrefSize(1250, 120);
        region.setMaxSize(1250, 120);
        region.setRotate(-16);
        region.setTranslateY(170);
        region.setMouseTransparent(true);
        return region;
    }

    private VBox centreBlock() {
        StackPane crestWrap = new StackPane();
        crestWrap.getStyleClass().add("login-logo-wrap");
        crestWrap.getChildren().addAll(
                ring(88, 0.07),
                ring(78, 0.05),
                crestImage());

        line1.getStyleClass().add("login-school-line1");
        line1.setAlignment(javafx.geometry.Pos.CENTER);
        line1.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox.setMargin(line1, new Insets(14, 0, 0, 0));

        line2.getStyleClass().add("login-school-line2");
        line2.setAlignment(javafx.geometry.Pos.CENTER);
        line2.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        Region ruleA = new Region();
        ruleA.getStyleClass().add("login-rule");
        ruleA.setPrefSize(52, 1);
        Region diamond = new Region();
        diamond.getStyleClass().add("login-rule-diamond");
        diamond.setPrefSize(5, 5);
        diamond.setRotate(45);
        Region ruleB = new Region();
        ruleB.getStyleClass().add("login-rule");
        ruleB.setPrefSize(52, 1);

        HBox rule = new HBox(10, ruleA, diamond, ruleB);
        rule.setAlignment(javafx.geometry.Pos.CENTER);
        VBox.setMargin(rule, new Insets(14, 0, 12, 0));

        location.getStyleClass().add("login-location");
        location.setAlignment(javafx.geometry.Pos.CENTER);
        location.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox box = new VBox(crestWrap, line1, line2, rule, location);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setMaxWidth(540);
        StackPane.setAlignment(box, javafx.geometry.Pos.CENTER);
        return box;
    }

    private Circle ring(double radius, double opacity) {
        Circle circle = new Circle(radius);
        circle.setFill(javafx.scene.paint.Color.web("#FFFFFF", opacity));
        circle.setMouseTransparent(true);
        return circle;
    }

    private ImageView crestImage() {
        ImageView view = new ImageView();
        var url = BrandPanel.class.getResource(CREST);
        if (url != null) {
            Image image = new Image(url.toExternalForm(), 146, 146, true, true);
            if (!image.isError()) {
                view.setImage(image);
            }
        }
        view.setFitWidth(146);
        view.setFitHeight(146);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.getStyleClass().add("login-logo-image");
        return view;
    }

    private Label footer() {
        footer.getStyleClass().add("login-brand-footer");
        StackPane.setAlignment(footer, javafx.geometry.Pos.BOTTOM_CENTER);
        StackPane.setMargin(footer, new Insets(0, 0, 26, 0));
        return footer;
    }

    /**
     * JavaFX CSS accepts one font family, not a fallback list, so pick the best
     * serif the host actually has (Georgia on Windows, FreeSerif on Linux).
     */
    private void applyDisplayFont() {
        String family = null;
        var families = javafx.scene.text.Font.getFamilies();
        for (String candidate : new String[]{"Georgia", "Times New Roman", "FreeSerif", "Serif"}) {
            if (families.contains(candidate)) {
                family = candidate;
                break;
            }
        }
        if (family == null) {
            return;
        }
        String css = "-fx-font-family: \"" + family + "\";";
        for (Label label : new Label[]{line1, line2, location}) {
            label.setStyle(css);
        }
    }
}
