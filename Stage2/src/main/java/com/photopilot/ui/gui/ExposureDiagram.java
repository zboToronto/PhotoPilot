package com.photopilot.ui.gui;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.i18n.LocalizationService;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * Exposure relationships as a slide rule: one scale each for aperture, exposure time and ISO, all drawn at the same
 * width per stop and each positioned so its current value is on the centre line. A step on any scale is therefore the
 * same distance, and trading a stop on one scale for a stop on another is visible directly. Dragging a scale changes
 * that setting. Below, the exposure is compared in stops with a reference the user captured; without one, nothing is
 * called brighter, darker or correct.
 */
final class ExposureDiagram extends CalculatorDiagram {
    private static final double ROW = 50, TOP = 24, RELATIVE_RANGE = 4;
    private double aperture = 8, shutter = 1 / 125.0, iso = 100;
    private OptionalDouble relativeStops = OptionalDouble.empty();
    private ExposureMode.Setting solved;
    private BiConsumer<ExposureMode.Setting, Double> adjust = (setting, value) -> { };
    // Geometry of the last drawing, for dragging.
    private double rulerLeft, rulerRight, perStop;
    private ExposureMode.Setting dragging;
    private double dragX, dragValue;

    ExposureDiagram(LocalizationService localization) {
        super(localization, 284);
        setOnMouseMoved(event -> setCursor(rulerAt(event.getX(), event.getY()) != null ? Cursor.H_RESIZE : Cursor.DEFAULT));
        setOnMousePressed(event -> {
            dragging = rulerAt(event.getX(), event.getY());
            if (dragging != null) { dragX = event.getX(); dragValue = value(dragging); }
        });
        setOnMouseDragged(event -> {
            if (dragging == null || perStop <= 0) return;
            // The scale follows the pointer: pulling it right brings its lower-exposure markings to the centre line.
            double stops = -(event.getX() - dragX) / perStop;
            var markings = markings(dragging);
            double raw = dragging == ExposureMode.Setting.APERTURE ? CalculatorPresentation.shiftedAperture(dragValue, stops) : CalculatorPresentation.shifted(dragValue, stops);
            double snapped = markings.get(CalculatorPresentation.nearest(markings, raw));
            if (snapped != value(dragging)) adjust.accept(dragging, snapped);
        });
        setOnMouseReleased(event -> dragging = null);
    }

    void onAdjust(BiConsumer<ExposureMode.Setting, Double> listener) { adjust = listener; }
    /** @param relativeStops stops more (+) or less (-) exposure than the captured reference, empty without one */
    void show(double aperture, double shutter, double iso, OptionalDouble relativeStops, ExposureMode.Setting solved) {
        this.aperture = aperture; this.shutter = shutter; this.iso = iso; this.relativeStops = relativeStops; this.solved = solved;
        setAccessibleText(localization.text("calc.exposure.diagramAccessible", CalculatorPresentation.aperture(aperture),
            CalculatorPresentation.exposureTime(shutter, localization), CalculatorPresentation.number(iso, 6)));
        redraw();
    }

    private double value(ExposureMode.Setting setting) { return switch (setting) { case APERTURE -> aperture; case SHUTTER -> shutter; case ISO -> iso; }; }
    private static List<Double> markings(ExposureMode.Setting setting) {
        return switch (setting) { case APERTURE -> CalculatorPresentation.APERTURES; case SHUTTER -> CalculatorPresentation.SHUTTERS; case ISO -> CalculatorPresentation.ISOS; };
    }
    private static List<Double> fullStops(ExposureMode.Setting setting) {
        return switch (setting) { case APERTURE -> CalculatorPresentation.FULL_STOP_APERTURES; case SHUTTER -> CalculatorPresentation.FULL_STOP_SHUTTERS; case ISO -> CalculatorPresentation.FULL_STOP_ISOS; };
    }
    /** Stops of exposure from the current value to a marking; positive is more exposure and is drawn to the right. */
    private double offset(ExposureMode.Setting setting, double marking) {
        return setting == ExposureMode.Setting.APERTURE ? CalculatorPresentation.apertureStops(aperture, marking) : CalculatorPresentation.stopsBetween(value(setting), marking);
    }
    private String mark(ExposureMode.Setting setting, double marking) {
        return switch (setting) {
            case APERTURE -> CalculatorPresentation.number(marking, 3);
            case SHUTTER -> CalculatorPresentation.shutterMark(marking);
            case ISO -> CalculatorPresentation.number(marking, 6);
        };
    }
    private String current(ExposureMode.Setting setting) {
        return switch (setting) {
            case APERTURE -> CalculatorPresentation.aperture(aperture);
            case SHUTTER -> CalculatorPresentation.exposureTime(shutter, localization);
            case ISO -> localization.text("calc.exposure.isoValue", CalculatorPresentation.number(iso, 6));
        };
    }
    private ExposureMode.Setting rulerAt(double x, double y) {
        if (x < rulerLeft || x > rulerRight) return null;
        int row = (int) Math.floor((y - TOP) / ROW);
        if (row < 0 || row > 2) return null;
        var setting = ExposureMode.Setting.values()[row];
        return setting == solved ? null : setting;
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        var p = palette();
        var settings = ExposureMode.Setting.values();
        String[] captions = { localization.text("calc.input.aperture"), localization.text("calc.input.shutter"), localization.text("calc.input.iso") };
        double captionWidth = 0;
        for (String caption : captions) captionWidth = Math.max(captionWidth, width(caption, strongFont()));
        captionWidth = Math.min(captionWidth, width * 0.26);
        rulerLeft = 12 + captionWidth + 12; rulerRight = width - 12;
        double span = rulerRight - rulerLeft, centre = rulerLeft + span / 2;
        // About 38 px per stop keeps every full-stop label readable; a narrow window simply shows fewer stops.
        perStop = span / Math.max(4, Math.min(9, Math.floor(span / 38)));

        text(g, "← " + localization.text("calc.exposure.less"), rulerLeft, 14, TextAlignment.LEFT, p.muted());
        text(g, localization.text("calc.exposure.more") + " →", rulerRight, 14, TextAlignment.RIGHT, p.muted());

        for (int row = 0; row < 3; row++) {
            var setting = settings[row];
            double top = TOP + row * ROW, lineY = top + 26;
            if (setting == solved) { g.setFill(p.accent(0.10)); g.fillRoundRect(6, top + 2, width - 12, ROW - 4, 10, 10); }
            text(g, captions[row], 12, top + 20, TextAlignment.LEFT, p.text(), strongFont(), captionWidth);
            if (setting == solved) text(g, localization.text("calc.exposure.solvedTag"), 12, top + 36, TextAlignment.LEFT, p.accentText(), font(), captionWidth);
            g.setStroke(p.track()); line(g, rulerLeft, lineY, rulerRight, lineY);
            g.save(); g.beginPath(); g.rect(rulerLeft, top, span, ROW); g.clip();
            var full = fullStops(setting);
            var labelled = new java.util.ArrayList<double[]>();
            for (double marking : markings(setting)) {
                double x = centre + offset(setting, marking) * perStop;
                if (x < rulerLeft - 30 || x > rulerRight + 30) continue;
                boolean whole = full.contains(marking);
                g.setStroke(whole ? p.muted() : p.subtle()); line(g, x, lineY, x, lineY + (whole ? 7 : 4));
                if (!whole) continue;
                // A full-stop label is skipped only where it would run into one already drawn (very long markings).
                String label = mark(setting, marking);
                double from = x - width(label, font()) / 2 - 3, to = x + width(label, font()) / 2 + 3;
                if (labelled.stream().anyMatch(other -> from < other[1] && to > other[0])) continue;
                text(g, label, x, lineY + 20, TextAlignment.CENTER, p.muted());
                labelled.add(new double[] { from, to });
            }
            g.restore();
            // The current value sits on the centre line, above the scale.
            String value = current(setting);
            double pill = width(value, strongFont()) + 14;
            g.setFill(setting == solved ? p.accent() : p.surface()); g.fillRoundRect(centre - pill / 2, top + 4, pill, 18, 9, 9);
            g.setStroke(p.accentText()); g.strokeRoundRect(centre - pill / 2 + .5, top + 4.5, pill - 1, 17, 9, 9);
            text(g, value, centre, top + 17, TextAlignment.CENTER, setting == solved ? p.onAccent() : p.accentText(), strongFont(), 0);
        }
        g.setStroke(p.accentText()); g.setLineWidth(1.5);
        for (int row = 0; row < 3; row++) { double top = TOP + row * ROW; g.strokeLine(centre, top + 22, centre, top + 30); }
        g.setLineWidth(1);

        // Relative to the captured reference: a fixed scale of stops, centred on the reference.
        double base = TOP + 3 * ROW + 6, barY = base + 48;
        text(g, localization.text("calc.exposure.relative"), 12, base + 14, TextAlignment.LEFT, p.text(), strongFont(), width - 24);
        double relativePerStop = span / (2 * RELATIVE_RANGE);
        g.setStroke(p.track()); line(g, rulerLeft, barY, rulerRight, barY);
        for (int stop = (int) -RELATIVE_RANGE; stop <= RELATIVE_RANGE; stop++) {
            double x = centre + stop * relativePerStop;
            g.setStroke(p.muted()); line(g, x, barY - 4, x, barY + 4);
            text(g, stop == 0 ? localization.text("calc.exposure.referenceMark") : CalculatorPresentation.signedStops(stop).replace(".0", ""), x, barY + 18, TextAlignment.CENTER, p.muted());
        }
        // Without a reference the scale stays empty; that none is set is said once, in the result tile below.
        if (relativeStops.isEmpty()) return;
        double stops = relativeStops.getAsDouble(), shown = Math.max(-RELATIVE_RANGE, Math.min(RELATIVE_RANGE, stops)), x = centre + shown * relativePerStop;
        // A diamond for "these settings", a ring for the reference: shape as well as colour.
        g.setStroke(p.accentText()); g.setLineWidth(1.5); g.strokeOval(centre - 5, barY - 5, 10, 10); g.setLineWidth(1);
        g.setFill(p.accent()); g.fillPolygon(new double[] { x, x + 7, x, x - 7 }, new double[] { barY - 8, barY, barY + 8, barY }, 4);
        String label = localization.text("calc.exposure.relativeValue", CalculatorPresentation.signedStops(stops)) + (Math.abs(stops) > RELATIVE_RANGE ? " →" : "");
        textWithin(g, label, x, barY - 13, rulerLeft, rulerRight, p.accentText(), strongFont());
        // Two grey patches: what that many stops look like. Clipped at black and white; not a photograph.
        double patchY = barY + 26, patch = 16;
        String note = localization.text("calc.exposure.demonstration");
        double level = CalculatorPresentation.demonstrationGrey(stops), reference = CalculatorPresentation.demonstrationGrey(0);
        double x0 = 12;
        g.setFill(Color.gray(reference)); g.fillRect(x0, patchY, patch, patch); g.setFill(Color.gray(level)); g.fillRect(x0 + patch, patchY, patch, patch);
        g.setStroke(p.muted()); g.strokeRect(x0 + .5, patchY + .5, 2 * patch - 1, patch - 1); line(g, x0 + patch, patchY, x0 + patch, patchY + patch);
        text(g, note, x0 + 2 * patch + 8, patchY + 12, TextAlignment.LEFT, p.muted(), font(), width - x0 - 2 * patch - 20);
    }
}
