package com.photopilot.ui.gui;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.application.MotionBlurPreview;
import com.photopilot.i18n.LocalizationService;
import java.util.OptionalInt;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Motion blur preview, drawn only from the calculated blur. Left: the whole frame to scale, with a 1.7 m figure at the
 * subject distance smeared along the motion by the calculated blur length; at this size a short blur is honestly
 * invisible. Right: a magnified detail in which a sharp edge and the same edge in this exposure are compared; its
 * scale (screen pixels per image pixel, or per micrometre on the sensor when the image width is unknown) is chosen from
 * the blur and written under the view. Screen pixels of the preview are never presented as image pixels, and the
 * preview illustrates a blur length; it does not predict a photograph.
 */
final class MotionBlurDiagram extends CalculatorDiagram {
    private double blur = 0.025, sensorWidth = 36, sensorHeight = 24, sceneWidth = 14.4, sceneHeight = 9.6;
    private OptionalInt imageWidth = OptionalInt.empty();
    private LengthUnit unit = LengthUnit.METRES;
    private MotionBlurPreview.Detail detail;

    /** Below this width the two views are stacked, and the diagram is taller to keep both readable. */
    private static final double SIDE_BY_SIDE = 430;
    MotionBlurDiagram(LocalizationService localization) { super(localization, 262); }
    @Override protected double heightFor(double width) { return width > 0 && width < SIDE_BY_SIDE ? 452 : 262; }

    /** Blur and sensor in mm; the scene covered at the subject distance in metres. */
    void show(double blur, double sensorWidth, double sensorHeight, OptionalInt imageWidth, double sceneWidth, double sceneHeight, LengthUnit unit) {
        this.blur = blur; this.sensorWidth = sensorWidth; this.sensorHeight = sensorHeight; this.imageWidth = imageWidth;
        this.sceneWidth = sceneWidth; this.sceneHeight = sceneHeight; this.unit = unit;
        setAccessibleText(localization.text("calc.motion.accessible", CalculatorPresentation.sensorLength(blur), CalculatorPresentation.percent(blur / sensorWidth * 100)));
        redraw();
    }
    /** The detail view's scale as last drawn. */
    MotionBlurPreview.Detail detail() { return detail; }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        var p = palette();
        if (width >= SIDE_BY_SIDE) {
            double split = Math.floor(width * 0.5);
            frame(g, 0, 0, split, height); g.setStroke(p.hairline()); line(g, split, 12, split, height - 12); magnified(g, split, 0, width - split, height);
        } else {
            double half = Math.floor(height * 0.5);
            frame(g, 0, 0, width, half); g.setStroke(p.hairline()); line(g, 12, half, width - 12, half); magnified(g, 0, half, width, height - half);
        }
    }

    private void frame(GraphicsContext g, double x, double y, double w, double h) {
        var p = palette();
        text(g, localization.text("calc.motion.frameTitle"), x + 12, y + 18, TextAlignment.LEFT, p.text(), strongFont(), w - 24);
        double line = lineHeight(font()), availableWidth = w - 24, availableHeight = h - 30 - line - 10, aspect = sensorWidth / sensorHeight;
        if (availableHeight < 30) return;
        int frameWidth = (int) Math.min(availableWidth, availableHeight * aspect), frameHeight = (int) Math.max(1, Math.round(frameWidth / aspect));
        if (frameWidth < 8) return;
        double left = Math.round(x + 12 + (availableWidth - frameWidth) / 2), top = Math.round(y + 30 + (availableHeight - frameHeight) / 2);
        g.setFill(p.accent(0.08)); g.fillRect(left, top, frameWidth, frameHeight);
        // The figure is as tall in the frame as a 1.7 m subject is at this distance; it stands on a ground line when it fits.
        double figureHeight = sceneHeight > 0 ? frameHeight * MotionBlurPreview.REFERENCE_HEIGHT_METRES / sceneHeight : 0;
        boolean fits = figureHeight <= frameHeight * 0.86;
        double ground = fits ? Math.round(frameHeight * 0.9) : frameHeight, blurOnScreen = blur / sensorWidth * frameWidth;
        if (fits) { g.setStroke(p.hairline()); line(g, left, top + ground, left + frameWidth, top + ground); }
        float[] coverage = MotionBlurPreview.smear(MotionBlurPreview.Figure.mask(frameWidth, frameHeight, frameWidth / 2.0, ground, figureHeight), frameWidth, frameHeight, blurOnScreen);
        g.drawImage(image(coverage, frameWidth, frameHeight, p.text()), left, top);
        g.setStroke(p.accentText()); g.setLineWidth(1.5); g.strokeRect(left + .5, top + .5, frameWidth - 1, frameHeight - 1); g.setLineWidth(1);
        // Direction of the subject's motion across the frame.
        double arrowY = top + 12;
        g.setStroke(p.muted()); g.strokeLine(left + 8, arrowY, left + 36, arrowY); g.strokeLine(left + 36, arrowY, left + 31, arrowY - 3); g.strokeLine(left + 36, arrowY, left + 31, arrowY + 3);
        // One line under the frame: its real width and the figure's height, which together are its scale.
        double below = top + frameHeight + line + 2;
        textWithin(g, localization.text("calc.motion.frameCaption", CalculatorPresentation.distance(sceneWidth, unit), CalculatorPresentation.number(MotionBlurPreview.REFERENCE_HEIGHT_METRES, 2)),
            x + w / 2, below, x + 6, x + w - 6, p.text(), font());
    }

    private void magnified(GraphicsContext g, double x, double y, double w, double h) {
        var p = palette();
        text(g, localization.text("calc.motion.detailTitle"), x + 12, y + 18, TextAlignment.LEFT, p.text(), strongFont(), w - 24);
        double line = lineHeight(font()), left = Math.round(x + 12), viewWidth = Math.floor(w - 24), top = Math.round(y + 30), viewHeight = Math.floor(h - 30 - line - 10);
        if (viewHeight < 40 || viewWidth < 60) return;
        detail = MotionBlurPreview.detail(blur, sensorWidth, imageWidth, viewWidth);
        double scale = detail.screenPerUnit(), units = detail.unitsAcross(), start = 0.3 * units, block = 0.4 * units, rowHeight = Math.floor(viewHeight / 2);
        g.setFill(p.surface()); g.fillRect(left, top, viewWidth, viewHeight);
        g.save(); g.beginPath(); g.rect(left, top, viewWidth, viewHeight); g.clip();
        Color ink = p.text(0.82);
        // Upper row: the edge without motion. Lower row: the same edge averaged over the exposure (the calculated blur).
        // The lower row leaves a strip under its block for the mark that measures the blur length.
        double inset = line + 6, blockTop = top + inset, blockHeight = rowHeight - inset - 6, strip = strongFont().getSize() + 8;
        g.setFill(ink); g.fillRect(left + start * scale, blockTop, block * scale, blockHeight);
        for (int column = 0; column < viewWidth; column++) {
            double share = MotionBlurPreview.coverage((column + 0.5) / scale, start, block, detail.blurUnits());
            if (share <= 0) continue;
            g.setFill(p.text(0.82 * Math.min(1, share))); g.fillRect(left + column, top + rowHeight + inset, 1, blockHeight - strip);
        }
        if (detail.imagePixels() && scale >= 6) {
            // One cell per image pixel, only when a cell is large enough to see.
            g.setStroke(p.hairline());
            for (double gx = left + scale; gx < left + viewWidth; gx += scale) line(g, gx, top, gx, top + viewHeight);
            for (double gy = top + scale; gy < top + viewHeight; gy += scale) line(g, left, gy, left + viewWidth, gy);
        }
        g.restore();
        g.setStroke(p.hairline()); line(g, left, top + rowHeight, left + viewWidth, top + rowHeight);
        g.setStroke(p.muted()); g.strokeRect(left + .5, top + .5, viewWidth - 1, viewHeight - 1);
        text(g, localization.text("calc.motion.sharp"), left + 6, top + line, TextAlignment.LEFT, p.muted(), font(), viewWidth - 12);
        text(g, localization.text("calc.motion.blurred"), left + 6, top + rowHeight + line, TextAlignment.LEFT, p.muted(), font(), viewWidth - 12);
        // The length of the edge's ramp is the blur length: marked and labelled in the view's own unit.
        if (detail.blurUnits() > 0) {
            double from = left + (start - detail.blurUnits() / 2) * scale, to = left + (start + detail.blurUnits() / 2) * scale;
            double markY = top + viewHeight - strongFont().getSize() / 2 - 6;
            g.setStroke(p.accentText()); g.setLineWidth(1.5);
            g.strokeLine(from, markY, to, markY); g.strokeLine(from, markY - 4, from, markY + 4); g.strokeLine(to, markY - 4, to, markY + 4);
            g.setLineWidth(1);
            String length = detail.imagePixels() ? localization.text("calc.motion.pixels", CalculatorPresentation.number(detail.blurUnits(), 3)) : CalculatorPresentation.sensorLength(blur);
            // Beside the mark, on whichever side has room, level with it.
            double labelX = to + 8 + width(length, strongFont()) <= left + viewWidth - 4 ? to + 8 : from - 8 - width(length, strongFont());
            text(g, length, Math.max(left + 4, labelX), markY + strongFont().getSize() * 0.35, TextAlignment.LEFT, p.accentText(), strongFont(), 0);
        }
        // One line under the view: its scale, in image pixels or, when the image width is unknown, on the sensor.
        double below = top + viewHeight + line + 2;
        String scaleText = !detail.imagePixels() ? localization.text("calc.motion.detailMicrons", CalculatorPresentation.number(scale, 3))
            : scale >= 1 ? localization.text("calc.motion.detailPixels", CalculatorPresentation.number(scale, 3))
            : localization.text("calc.motion.detailReduced", CalculatorPresentation.number(1 / scale, 3));
        textWithin(g, scaleText, x + w / 2, below, x + 6, x + w - 6, p.text(), font());
    }

    /** The coverage mask as an image of one colour whose opacity is the coverage. */
    private static WritableImage image(float[] coverage, int width, int height, Color colour) {
        int rgb = ((int) Math.round(colour.getRed() * 255) << 16) | ((int) Math.round(colour.getGreen() * 255) << 8) | (int) Math.round(colour.getBlue() * 255);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = ((int) Math.round(Math.min(1, coverage[i]) * 235) << 24) | rgb;
        var image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }
}
