package com.photopilot.ui.gui;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.application.MotionBlurPreview;
import com.photopilot.i18n.LocalizationService;
import java.util.OptionalDouble;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.ArcType;
import javafx.scene.text.TextAlignment;

/**
 * Field of view in two drawings that change with the inputs. Left: the camera from above with its horizontal angle of
 * view as a cone, drawn at the true angle. Right: the frame in the sensor's proportions with the vertical and diagonal
 * angles and, when a subject distance is given, the scene it covers there with a 1.7 m figure to the same scale.
 * An ideal rectilinear projection: no distortion, fisheye mapping or focus breathing.
 */
final class FieldOfViewDiagram extends CalculatorDiagram {
    private double horizontal = 39.6, vertical = 27, diagonal = 46.8, sensorWidth = 36, sensorHeight = 24;
    private OptionalDouble distance = OptionalDouble.empty();
    private double sceneWidth, sceneHeight;
    private LengthUnit unit = LengthUnit.METRES;

    /** Below this width the two drawings are stacked, and the diagram is taller to keep both readable. */
    private static final double SIDE_BY_SIDE = 430;
    FieldOfViewDiagram(LocalizationService localization) { super(localization, 250); }
    @Override protected double heightFor(double width) { return width > 0 && width < SIDE_BY_SIDE ? 440 : 250; }

    /** Angles in degrees, sensor in mm, distance and scene in metres (the scene only matters with a distance). */
    void show(double horizontal, double vertical, double diagonal, double sensorWidth, double sensorHeight, OptionalDouble distance, double sceneWidth, double sceneHeight, LengthUnit unit) {
        this.horizontal = horizontal; this.vertical = vertical; this.diagonal = diagonal; this.sensorWidth = sensorWidth; this.sensorHeight = sensorHeight;
        this.distance = distance; this.sceneWidth = sceneWidth; this.sceneHeight = sceneHeight; this.unit = unit;
        setAccessibleText(localization.text("calc.fov.accessible", CalculatorPresentation.angle(horizontal), CalculatorPresentation.angle(vertical), CalculatorPresentation.angle(diagonal)));
        redraw();
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        var p = palette();
        boolean sideBySide = width >= SIDE_BY_SIDE;
        double split = sideBySide ? Math.floor(width * 0.52) : width, half = Math.floor(height * 0.45);
        cone(g, 0, 0, split, sideBySide ? height : half);
        if (sideBySide) { g.setStroke(p.hairline()); line(g, split, 12, split, height - 12); frame(g, split, 0, width - split, height); }
        else { g.setStroke(p.hairline()); line(g, 12, half, width - 12, half); frame(g, 0, half, width, height - half); }
    }

    /** Top view: the horizontal angle of view at its true angle; with a distance, the width covered there. */
    private void cone(GraphicsContext g, double x, double y, double w, double h) {
        var p = palette();
        text(g, localization.text("calc.fov.topView"), x + 12, y + 18, TextAlignment.LEFT, p.text(), strongFont(), w - 24);
        double apexX = x + 46, apexY = y + 22 + (h - 22 - 22) / 2, halfAngle = Math.toRadians(horizontal / 2);
        double maxLength = x + w - 16 - apexX, maxHalf = (h - 22 - 30) / 2;
        // The cone is as long as fits: limited by the panel's width for narrow angles and by its height for wide ones.
        double length = Math.min(maxLength, maxHalf / Math.tan(halfAngle)), half = length * Math.tan(halfAngle);
        double farX = apexX + length;
        g.setFill(p.accent(0.18)); g.fillPolygon(new double[] { apexX, farX, farX }, new double[] { apexY, apexY - half, apexY + half }, 3);
        g.setStroke(p.accentText()); g.setLineWidth(1.5);
        g.strokeLine(apexX, apexY, farX, apexY - half); g.strokeLine(apexX, apexY, farX, apexY + half);
        g.setLineWidth(1);
        g.setStroke(p.muted()); g.setLineDashes(3, 4); g.strokeLine(apexX, apexY, farX, apexY); g.setLineDashes();
        // Angle arc and value.
        double radius = Math.min(34, length * 0.45);
        g.setStroke(p.accentText()); g.strokeArc(apexX - radius, apexY - radius, 2 * radius, 2 * radius, -horizontal / 2, horizontal, ArcType.OPEN);
        String angle = localization.text("calc.fov.horizontalValue", CalculatorPresentation.angle(horizontal));
        text(g, angle, Math.min(apexX + radius + 8, x + w - 12 - width(angle, strongFont())), apexY - 6, TextAlignment.LEFT, p.accentText(), strongFont(), 0);
        g.setFill(p.muted()); camera(g, apexX, apexY);
        if (distance.isPresent()) {
            // The far edge of the cone is the scene width at the subject distance.
            g.setStroke(p.text()); g.setLineWidth(2); g.strokeLine(farX, apexY - half, farX, apexY + half); g.setLineWidth(1);
            String covered = localization.text("calc.fov.widthAt", CalculatorPresentation.distance(sceneWidth, unit), CalculatorPresentation.distance(distance.getAsDouble(), unit));
            textWithin(g, covered, apexX + length / 2, y + h - 10, x + 8, x + w - 8, p.text(), font());
        }
    }

    /** The frame in the sensor's proportions; with a distance it is the scene covered there, to scale. */
    private void frame(GraphicsContext g, double x, double y, double w, double h) {
        var p = palette();
        String title = distance.isPresent() ? localization.text("calc.fov.frameAt", CalculatorPresentation.distance(distance.getAsDouble(), unit)) : localization.text("calc.fov.frame");
        text(g, title, x + 12, y + 18, TextAlignment.LEFT, p.text(), strongFont(), w - 24);
        double availableWidth = w - 24 - 20, availableHeight = h - 30 - 28, aspect = sensorWidth / sensorHeight;
        double frameWidth = Math.min(availableWidth, availableHeight * aspect), frameHeight = frameWidth / aspect;
        double left = x + 12 + (availableWidth - frameWidth) / 2, top = y + 30 + (availableHeight - frameHeight) / 2;
        g.setFill(p.accent(0.10)); g.fillRect(left, top, frameWidth, frameHeight);
        g.setStroke(p.accentText()); g.setLineWidth(1.5); g.strokeRect(left, top, frameWidth, frameHeight); g.setLineWidth(1);
        g.setStroke(p.muted()); g.setLineDashes(4, 4); g.strokeLine(left, top + frameHeight, left + frameWidth, top); g.setLineDashes();
        if (distance.isPresent() && sceneHeight > 0) {
            // A 1.7 m figure standing on the lower edge, to the frame's scale; cut off where it is taller than the frame.
            double figureHeight = frameHeight * MotionBlurPreview.REFERENCE_HEIGHT_METRES / sceneHeight;
            g.save(); g.beginPath(); g.rect(left, top, frameWidth, frameHeight); g.clip();
            g.setFill(p.text(0.80)); figure(g, left + frameWidth * 0.3, top + frameHeight, figureHeight);
            g.restore();
        }
        // Vertical angle along the right edge, read bottom to top.
        g.save(); g.translate(left + frameWidth + 14, top + frameHeight / 2); g.rotate(-90);
        text(g, localization.text("calc.fov.verticalValue", CalculatorPresentation.angle(vertical)), 0, 0, TextAlignment.CENTER, p.accentText(), strongFont(), frameHeight + 20);
        g.restore();
        // Under the frame only the angle of its dashed diagonal; the scene's size is the result tile below the diagram.
        double below = top + frameHeight + lineHeight(font()) + 2;
        textWithin(g, localization.text("calc.fov.diagonalValue", CalculatorPresentation.angle(diagonal)), left + frameWidth / 2, below, x + 6, x + w - 6, p.muted(), font());
    }
}
