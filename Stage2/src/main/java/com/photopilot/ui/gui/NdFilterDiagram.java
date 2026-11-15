package com.photopilot.ui.gui;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.i18n.LocalizationService;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.TextAlignment;

/**
 * The original and the filtered exposure time, twice: as two bars drawn to scale (which shows how much longer the
 * filtered exposure really is) and as a ladder of stops, one doubling per step, from the base time to the result
 * (a logarithmic scale, labelled as such). Both are drawn from the calculated time; nothing is animated or counted down.
 */
final class NdFilterDiagram extends CalculatorDiagram {
    private double base = 1 / 125.0, stops = 10, adjusted = 8.192;

    NdFilterDiagram(LocalizationService localization) { super(localization, 206); }

    void show(double base, double stops, double adjusted) {
        this.base = base; this.stops = stops; this.adjusted = adjusted;
        setAccessibleText(localization.text("calc.nd.diagramAccessible", CalculatorPresentation.exposureTime(base, localization),
            CalculatorPresentation.number(stops, 4), CalculatorPresentation.exposureTime(adjusted, localization)));
        redraw();
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        var p = palette();
        String without = localization.text("calc.nd.without"), with = localization.text("calc.nd.with");
        String baseText = CalculatorPresentation.exposureTime(base, localization), adjustedText = CalculatorPresentation.exposureTime(adjusted, localization);

        // 1. To scale.
        text(g, localization.text("calc.nd.toScale"), 12, 20, TextAlignment.LEFT, p.text(), strongFont(), width - 24);
        double labels = Math.min(width * 0.3, Math.max(width(without, font()), width(with, font())) + 10);
        double values = Math.min(width * 0.3, Math.max(width(baseText, strongFont()), width(adjustedText, strongFont())) + 10);
        double barLeft = 12 + labels, barWidth = Math.max(40, width - 12 - values - barLeft);
        double ratio = adjusted > 0 ? Math.min(1, base / adjusted) : 1;
        double rowA = 32, rowB = 58, barHeight = 16;
        text(g, without, 12, rowA + 12, TextAlignment.LEFT, p.muted(), font(), labels - 6);
        text(g, with, 12, rowB + 12, TextAlignment.LEFT, p.muted(), font(), labels - 6);
        g.setFill(p.track()); g.fillRoundRect(barLeft, rowA, barWidth, barHeight, 6, 6); g.fillRoundRect(barLeft, rowB, barWidth, barHeight, 6, 6);
        // The unfiltered bar keeps a visible minimum; when that is wider than its true length the note below says so.
        double baseLength = Math.max(2, ratio * barWidth);
        g.setFill(p.muted()); g.fillRoundRect(barLeft, rowA, baseLength, barHeight, Math.min(6, baseLength), 6);
        g.setFill(p.accent()); g.fillRoundRect(barLeft, rowB, barWidth, barHeight, 6, 6);
        text(g, baseText, barLeft + barWidth + 8, rowA + 12, TextAlignment.LEFT, p.text(), strongFont(), values - 8);
        text(g, adjustedText, barLeft + barWidth + 8, rowB + 12, TextAlignment.LEFT, p.accentText(), strongFont(), values - 8);
        // Said only when the drawing departs from its title: the upper bar would be too thin to see at its true length.
        if (stops > 0 && ratio * barWidth < 2) text(g, localization.text("calc.nd.scaleNoteMinimum"), 12, rowB + barHeight + 16, TextAlignment.LEFT, p.muted(), font(), width - 24);

        // 2. Stop ladder.
        double top = rowB + barHeight + 34;
        text(g, localization.text("calc.nd.ladder"), 12, top + 12, TextAlignment.LEFT, p.text(), strongFont(), width - 24);
        double axisY = top + 50, left = 30, right = width - 30;
        int whole = (int) Math.floor(stops + 1e-9);
        double span = Math.max(stops, 1e-9), step = (right - left) / Math.max(span, 1);
        g.setStroke(p.track()); line(g, left, axisY, left + stops * step, axisY);
        String startLabel = CalculatorPresentation.shutterMark(base), endLabel = CalculatorPresentation.shutterMark(adjusted);
        double endX = left + stops * step;
        double startHalf = width(startLabel, strongFont()) / 2 + 6, endHalf = width(endLabel, strongFont()) / 2 + 6;
        double lastRight = left + startHalf;
        for (int stop = 0; stop <= whole; stop++) {
            double x = left + stop * step;
            g.setStroke(p.muted()); line(g, x, axisY - 4, x, axisY + 4);
            if (stop == 0 || stop == stops) continue;
            // Intermediate times are labelled wherever they fit between the two ends.
            String label = CalculatorPresentation.shutterMark(CalculatorPresentation.shifted(base, stop));
            double half = width(label, font()) / 2;
            if (x - half > lastRight + 4 && x + half < endX - endHalf - 4) { text(g, label, x, axisY + 19, TextAlignment.CENTER, p.muted()); lastRight = x + half; }
        }
        // Start: a ring. Result: a filled diamond. The arrow between them carries the strength.
        g.setStroke(p.muted()); g.setLineWidth(1.5); g.strokeOval(left - 5, axisY - 5, 10, 10); g.setLineWidth(1);
        text(g, startLabel, left, axisY + 19, TextAlignment.CENTER, p.text(), strongFont(), 0);
        if (stops > 0) {
            g.setFill(p.accent()); g.fillPolygon(new double[] { endX, endX + 7, endX, endX - 7 }, new double[] { axisY - 8, axisY, axisY + 8, axisY }, 4);
            if (endX - endHalf > left + startHalf) text(g, endLabel, endX, axisY + 19, TextAlignment.CENTER, p.accentText(), strongFont(), 0);
            double arrowY = axisY - 16;
            g.setStroke(p.accentText()); g.setLineWidth(1.5);
            g.strokeLine(left, arrowY, endX - 2, arrowY); g.strokeLine(endX - 2, arrowY, endX - 8, arrowY - 4); g.strokeLine(endX - 2, arrowY, endX - 8, arrowY + 4);
            g.setLineWidth(1);
            String strength = localization.text("calc.nd.arrow", CalculatorPresentation.number(stops, 4), CalculatorPresentation.number(adjusted / base, 4));
            textWithin(g, strength, (left + endX) / 2, arrowY - 7, 12, width - 12, p.accentText(), strongFont());
        } else text(g, localization.text("calc.nd.noChange"), left + 14, axisY - 12, TextAlignment.LEFT, p.muted(), font(), width - left - 26);
    }
}
