package com.photopilot.ui.gui;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.DistanceAxis;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.i18n.LocalizationService;
import java.util.function.DoubleConsumer;
import javafx.animation.Interpolator;
import javafx.animation.Transition;
import javafx.scene.Cursor;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

/**
 * Side view of focusing, the one diagram of the depth of field and hyperfocal calculator in both focus modes: the
 * camera at the left of a linear distance axis, the focus distance, the interval of acceptable sharpness between the
 * near and far limits, and the hyperfocal distance.
 * <ul>
 *   <li>Manual focus: the subject is a handle on the focus distance. Dragging it along the axis (or moving it with the
 *   arrow keys) sets the focus distance itself, and everything is recalculated from it.</li>
 *   <li>Hyperfocal focus: the focus is locked at the calculated hyperfocal distance. The marker carries a padlock and
 *   says so, and it cannot be moved; the interval runs from the near limit to infinity.</li>
 * </ul>
 * The axis only places what was calculated. A far limit that does not fit is drawn broken off at the edge with its
 * finite value; an unbounded one fades out past the edge with the infinity sign. The two never look alike, and a
 * finite limit is never shown as infinity. When the range of the axis changes (another focus distance, the other focus
 * mode) the scale eases to the new range in a quarter of a second rather than jumping; nothing runs while no input changes.
 */
final class FocusDiagram extends CalculatorDiagram {
    private static final double LEFT = 46, RIGHT_MARGIN = 26, GRAB = 18;
    private static final Duration RESCALE = Duration.millis(240);
    private double focus = 3, near = 2.34, far = 4.19, hyperfocal = 10.5, minFocus = 0.1, maxFocus = 1000;
    private LengthUnit unit = LengthUnit.METRES;
    /** The range the axis has for the current values. */
    private DistanceAxis axis = new DistanceAxis(6);
    /** The range drawn: {@link #axis} except while the scale eases from the previous range to it. */
    private double shownMax = 6, startMax = 6, rescaleTick = Double.NaN;
    private final Transition rescale = new Transition() {
        { setCycleDuration(RESCALE); setInterpolator(Interpolator.EASE_BOTH); }
        // Geometric, so a change of range by a large factor eases as evenly as a small one.
        @Override protected void interpolate(double fraction) { shownMax = startMax * Math.pow(axis.max() / startMax, fraction); redraw(); }
    };
    private DoubleConsumer focusChanged = metres -> { };
    private boolean locked, dragging, hovering;
    /** Where on the subject the pointer took hold, so a press alone does not move it. */
    private double right, drawnWidth, grab;

    FocusDiagram(LocalizationService localization) {
        super(localization, 240);
        rescale.setOnFinished(event -> settle());
        setFocusTraversable(true);
        focusedProperty().addListener(observable -> redraw());
        setOnMouseMoved(event -> hover(movable() && overSubject(event.getX(), event.getY())));
        setOnMouseExited(event -> { if (!dragging) hover(false); });
        setOnMousePressed(event -> {
            if (!movable() || !overSubject(event.getX(), event.getY())) return;
            // Taking hold changes nothing; the axis settles, then keeps its range for the whole gesture.
            grab = event.getX() - x(focus);
            finishRescale(); dragging = true; requestFocus(); redraw();
        });
        setOnMouseDragged(event -> { if (dragging) drag(event.getX() - grab); });
        setOnMouseReleased(event -> {
            if (!dragging) return;
            dragging = false;
            // Released outside the subject (or outside the diagram): no hover left behind.
            boolean inside = event.getX() >= 0 && event.getX() <= getWidth() && event.getY() >= 0 && event.getY() <= getHeight();
            fit(); hover(inside && movable() && overSubject(event.getX(), event.getY())); redraw();
        });
        setOnKeyPressed(event -> {
            if (!movable() || event.getCode() != KeyCode.LEFT && event.getCode() != KeyCode.RIGHT) return;
            double step = axis.max() / (event.isShiftDown() ? 10 : 100) * (event.getCode() == KeyCode.LEFT ? -1 : 1);
            moveTo(unit.fromMetres(focus) + step); event.consume();
        });
    }

    /** Called with the new focus distance in metres while the subject is dragged or moved by keyboard (manual focus only). */
    void onFocus(DoubleConsumer listener) { focusChanged = listener; }
    /** Limits of the focus distance the subject can be moved to, in metres. */
    void focusRange(double minMetres, double maxMetres) { minFocus = minMetres; maxFocus = maxMetres; }
    /**
     * All distances in metres; {@code far} is +Infinity when the far limit is unbounded.
     * @param locked true while the focus is locked at the hyperfocal distance: the marker cannot be moved
     */
    void show(double focus, double near, double far, double hyperfocal, LengthUnit unit, boolean locked) {
        boolean sameUnit = unit == this.unit;
        this.focus = focus; this.near = near; this.far = far; this.hyperfocal = hyperfocal; this.unit = unit;
        lock(locked);
        if (!dragging) fit(sameUnit);
        String farText = Double.isInfinite(far) ? localization.text("calc.focus.unboundedWord") : CalculatorPresentation.distance(far, unit);
        setAccessibleText(localization.text(locked ? "calc.focus.accessibleLocked" : "calc.focus.accessible",
            CalculatorPresentation.distance(focus, unit), CalculatorPresentation.distance(near, unit), farText));
        redraw();
    }
    private void lock(boolean value) {
        if (locked == value) return;
        locked = value;
        if (locked) { dragging = false; hover(false); }
    }
    boolean locked() { return locked; }
    private boolean movable() { return !locked && !stale(); }
    private void hover(boolean over) {
        if (over == hovering) return;
        hovering = over; setCursor(over ? Cursor.H_RESIZE : Cursor.DEFAULT); redraw();
    }

    private void fit() { fit(true); }
    /** Fits the axis to the values; a change of range eases in when the diagram is on screen and the unit is the same. */
    private void fit(boolean sameUnit) {
        var next = DistanceAxis.fit(unit.fromMetres(focus), unit.fromMetres(far));
        if (next.max() == axis.max() && sameUnit) return;
        double from = shownMax;
        axis = next;
        if (sameUnit && onScreen() && from != next.max()) {
            // One tick spacing for the whole transition, the wider of the two ranges', so labels slide and never crowd.
            double previous = Double.isNaN(rescaleTick) ? new DistanceAxis(from).tick() : rescaleTick;
            rescaleTick = Math.max(previous, next.tick());
            startMax = from; rescale.playFromStart();
        } else { rescale.stop(); settle(); }
    }
    private boolean onScreen() { return getScene() != null && getScene().getWindow() != null && getScene().getWindow().isShowing() && isVisible() && getWidth() > 0; }
    private void settle() { shownMax = axis.max(); rescaleTick = Double.NaN; redraw(); }
    /** Ends a scale transition at once (before a gesture, and for pictures and checks of the final state). */
    void finishRescale() { if (rescale.getStatus() == javafx.animation.Animation.Status.RUNNING) { rescale.stop(); settle(); } }
    boolean rescaling() { return rescale.getStatus() == javafx.animation.Animation.Status.RUNNING; }
    /** The axis range for the current values. While the scale eases, the drawing reaches it within a quarter of a second. */
    DistanceAxis axis() { return axis; }
    /** How the far limit is drawn in the range on screen: within it, broken off beyond it with its value, or unbounded. */
    DistanceAxis.Extent farExtent() { return new DistanceAxis(shownMax).extent(unit.fromMetres(far)); }
    /** Where the focus marker is drawn, in this node's coordinates (for pointer gestures). */
    double subjectX() { return x(focus); }
    double axisY() { return getHeight() - 48; }

    private double x(double metres) { return LEFT + unit.fromMetres(metres) / shownMax * (right - LEFT); }
    private boolean overSubject(double px, double py) { return Math.abs(px - x(focus)) <= GRAB && py > 20 && py < axisY() + 14; }
    private void drag(double px) { moveTo(axis.distance((px - LEFT) / (right - LEFT))); }
    /**
     * Moves the focus to a distance given in the display unit: rounded to what the user reads (three significant
     * digits), then kept inside the axis and the allowed focus range, so rounding never leaves that range.
     */
    private void moveTo(double shown) {
        double limited = Math.max(unit.fromMetres(minFocus), Math.min(Math.min(axis.max(), unit.fromMetres(maxFocus)), shown));
        double metres = unit.toMetres(CalculatorPresentation.significant(limited, 3));
        focusChanged.accept(Math.max(minFocus, Math.min(maxFocus, metres)));
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        var p = palette();
        right = width - RIGHT_MARGIN; drawnWidth = width;
        double span = right - LEFT, axisY = height - 48, labelA = 18, labelB = 34, bandTop = 44;
        double xn = x(near), xs = x(focus);
        // Judged against the range on screen, so what is drawn and what it is called always match, also while easing.
        var extent = farExtent();
        double xf = extent == DistanceAxis.Extent.WITHIN ? Math.min(x(far), right) : right;

        // Axis: linear, from the camera (0) to the axis maximum, in the chosen unit.
        g.setStroke(p.muted()); line(g, LEFT, axisY, right, axisY);
        double tick = Double.isNaN(rescaleTick) ? axis.tick() : rescaleTick;
        for (int i = 0; i * tick <= shownMax * (1 + 1e-9); i++) {
            double value = i * tick, tx = LEFT + value / shownMax * span;
            line(g, tx, axisY, tx, axisY + 5);
            text(g, CalculatorPresentation.number(value, 4), tx, axisY + 18, TextAlignment.CENTER, p.muted());
        }
        text(g, localization.text("calc.focus.axis", unit.symbol), 10, height - 8, TextAlignment.LEFT, p.subtle(), font(), width - 20);

        // Interval of acceptable sharpness: tinted and hatched, so it does not depend on colour alone.
        double bandRight = extent == DistanceAxis.Extent.UNBOUNDED ? width : xf, bandLeft = Math.min(xn, bandRight);
        g.setFill(extent == DistanceAxis.Extent.UNBOUNDED
            ? new LinearGradient(right - 60, 0, width, 0, false, CycleMethod.NO_CYCLE, new Stop(0, p.accent(0.20)), new Stop(1, p.accent(0.02)))
            : p.accent(0.20));
        g.fillRect(bandLeft, bandTop, bandRight - bandLeft, axisY - bandTop);
        g.save(); g.beginPath(); g.rect(bandLeft, bandTop, Math.min(bandRight, right) - bandLeft, axisY - bandTop); g.clip();
        g.setStroke(p.accent(0.42));
        for (double hx = bandLeft - (axisY - bandTop); hx < bandRight; hx += 9) g.strokeLine(hx, axisY, hx + (axisY - bandTop), bandTop);
        g.restore();
        g.setStroke(p.accentText()); g.setLineWidth(1.5);
        if (xn <= right) { g.strokeLine(xn, labelA + 4, xn, axisY); }
        g.strokeLine(bandLeft, bandTop, Math.min(bandRight, right), bandTop);
        switch (extent) {
            case WITHIN -> g.strokeLine(xf, labelB + 4, xf, axisY);
            case BEYOND_VIEW -> {
                // Broken off: a zigzag edge and the finite distance. The limit exists, it is only outside this view.
                double step = (axisY - bandTop) / 8;
                g.beginPath(); g.moveTo(right, bandTop);
                for (int i = 1; i <= 8; i++) g.lineTo(right + (i % 2 == 1 ? 6 : 0), bandTop + i * step);
                g.stroke();
            }
            case UNBOUNDED -> {
                // Open ended: an arrow along the top edge past the axis and the infinity sign.
                g.strokeLine(right, bandTop, width - 4, bandTop);
                g.strokeLine(width - 4, bandTop, width - 10, bandTop - 4); g.strokeLine(width - 4, bandTop, width - 10, bandTop + 4);
                Font large = Font.font(strongFont().getFamily(), javafx.scene.text.FontWeight.BOLD, strongFont().getSize() * 2.2);
                text(g, CalculatorPresentation.INFINITY, right - 20, bandTop + (axisY - bandTop) / 2 + large.getSize() * 0.3, TextAlignment.CENTER, p.accentText(), large, 0);
            }
        }
        g.setLineWidth(1);

        // Near label to the left of the interval, far label to its right; each falls back inside when the edge is close.
        String nearText = localization.text("calc.focus.near", CalculatorPresentation.distance(near, unit));
        double nearWidth = width(nearText, strongFont());
        if (xn - 6 - nearWidth >= 4) text(g, nearText, xn - 6, labelA, TextAlignment.RIGHT, p.text(), strongFont(), 0);
        else text(g, nearText, Math.min(xn + 6, width - 4 - nearWidth), labelA, TextAlignment.LEFT, p.text(), strongFont(), 0);
        String farText = switch (extent) {
            case WITHIN -> localization.text("calc.focus.far", CalculatorPresentation.distance(far, unit));
            case BEYOND_VIEW -> localization.text("calc.focus.farBeyond", CalculatorPresentation.distance(far, unit));
            case UNBOUNDED -> localization.text("calc.focus.farUnbounded");
        };
        double farWidth = width(farText, strongFont());
        if (extent == DistanceAxis.Extent.WITHIN && xf + 6 + farWidth <= width - 4) text(g, farText, xf + 6, labelB, TextAlignment.LEFT, p.text(), strongFont(), 0);
        else text(g, farText, width - 4, labelB, TextAlignment.RIGHT, p.text(), strongFont(), width - 8);

        // Hyperfocal distance: a dashed line where it is in view, otherwise a pointer at the edge with its value. Only when
        // the focus is at that distance (locked, or set to exactly that value) does the focus label name it instead; a
        // focus merely close to it on screen keeps its own label and H keeps its label too.
        double xh = x(hyperfocal);
        boolean atHyperfocal = locked || Double.isInfinite(far) && Math.abs(focus - hyperfocal) <= 1e-9 * hyperfocal;
        String hyperfocalText = localization.text("calc.focus.hyperfocal", CalculatorPresentation.distance(hyperfocal, unit));
        if (!atHyperfocal && xh <= right + 0.5) {
            if (Math.abs(xh - xs) > 2) { g.setStroke(p.muted()); g.setLineDashes(4, 4); g.strokeLine(xh, bandTop + 2, xh, axisY); g.setLineDashes(); }
            // Left of its line when the focus is to the right or there is no room on the right, but never over the camera.
            double labelWidth = width(hyperfocalText, font());
            boolean fitsLeft = xh - 5 - labelWidth >= LEFT + 4, fitsRight = xh + 5 + labelWidth <= width - 4;
            boolean leftSide = fitsLeft && (xh < xs || !fitsRight);
            // Close to the subject the label moves up, clear of the figure and the focus label.
            double labelY = Math.abs(xh - xs) < 90 ? bandTop + 18 : axisY - 8;
            tag(g, hyperfocalText, leftSide ? xh - 5 : xh + 5, labelY, leftSide ? TextAlignment.RIGHT : TextAlignment.LEFT, p.muted(), font(), false);
        } else if (!atHyperfocal) tag(g, hyperfocalText + " →", width - 4, axisY - 8, TextAlignment.RIGHT, p.muted(), font(), false);

        // Camera: its lens front is the origin of the axis. Symbols are not to scale.
        g.setFill(p.muted()); camera(g, LEFT, axisY - 14);

        // Focus distance: the subject to drag in manual focus, a locked marker with hyperfocal focus.
        g.setStroke(p.accentText()); g.setLineWidth(2); g.strokeLine(xs, bandTop - 8, xs, axisY); g.setLineWidth(1);
        String focusText = localization.text(locked ? "calc.focus.focusLocked" : atHyperfocal ? "calc.focus.focusAtHyperfocal" : "calc.focus.focus",
            CalculatorPresentation.distance(focus, unit));
        double figureHeight = Math.min(66, (axisY - bandTop) * 0.62);
        if (!locked) { g.setFill(p.text()); figure(g, xs, axisY, figureHeight); }
        double tagY = locked ? bandTop + 30 : axisY - figureHeight - 10, half = (width(focusText, strongFont()) + (locked ? padlockWidth() : 0)) / 2 + 7;
        tag(g, focusText, Math.max(half + 2, Math.min(width - half - 2, xs)), tagY, TextAlignment.CENTER, p.accentText(), strongFont(), locked);
        // Handle on the axis: a filled circle, ringed while the diagram has keyboard focus (in either mode) or the subject is
        // hovered or dragged, with arrows only where it can be moved.
        boolean ring = isFocused() || movable() && (dragging || hovering);
        if (ring) { g.setStroke(p.focus()); g.setLineWidth(2); g.strokeOval(xs - 11, axisY - 11, 22, 22); g.setLineWidth(1); }
        g.setFill(p.accent()); g.fillOval(xs - 7, axisY - 7, 14, 14);
        g.setStroke(p.onAccent()); g.strokeOval(xs - 6.5, axisY - 6.5, 13, 13);
        if (!locked) {
            g.setFill(p.accentText());
            g.fillPolygon(new double[] { xs - 11, xs - 16, xs - 11 }, new double[] { axisY - 4, axisY, axisY + 4 }, 3);
            g.fillPolygon(new double[] { xs + 11, xs + 16, xs + 11 }, new double[] { axisY - 4, axisY, axisY + 4 }, 3);
        }
    }

    private double padlockWidth() { return Math.ceil(strongFont().getSize() * 0.9) + 5; }
    /**
     * A label on a small backing of the diagram's surface colour, so it stays readable over the hatching; with a padlock
     * before the text when it names a locked value.
     */
    private void tag(GraphicsContext g, String value, double x, double y, TextAlignment align, Color colour, Font font, boolean padlock) {
        double icon = padlock ? padlockWidth() : 0, textWidth = width(value, font);
        // Kept inside the diagram: shifted in from an edge, and squeezed only when it is wider than the whole diagram.
        double room = drawnWidth - 16, squeeze = icon + textWidth > room ? Math.max(20, room - icon) : 0;
        double total = icon + (squeeze > 0 ? squeeze : textWidth);
        double left = align == TextAlignment.LEFT ? x : align == TextAlignment.RIGHT ? x - total : x - total / 2;
        left = Math.max(8, Math.min(drawnWidth - 8 - total, left));
        g.setFill(palette().surface()); g.fillRoundRect(left - 4, y - font.getSize(), total + 8, font.getSize() + 5, 6, 6);
        if (padlock) padlock(g, left, y, font.getSize() * 0.9, colour);
        text(g, value, left + icon, y, TextAlignment.LEFT, colour, font, squeeze);
    }
    /** A closed padlock of the given height standing on the text baseline at (x, baseline). */
    private static void padlock(GraphicsContext g, double x, double baseline, double size, Color colour) {
        double bodyWidth = size * 0.9, bodyHeight = size * 0.58, top = baseline - bodyHeight;
        g.setFill(colour); g.fillRoundRect(x, top, bodyWidth, bodyHeight, 3, 3);
        g.setStroke(colour); g.setLineWidth(Math.max(1.5, size * 0.13));
        double shackle = bodyWidth * 0.62;
        g.strokeArc(x + (bodyWidth - shackle) / 2, top - shackle * 0.72, shackle, shackle * 1.2, 0, 180, ArcType.OPEN);
        g.strokeLine(x + (bodyWidth - shackle) / 2, top - shackle * 0.12, x + (bodyWidth - shackle) / 2, top);
        g.strokeLine(x + (bodyWidth + shackle) / 2, top - shackle * 0.12, x + (bodyWidth + shackle) / 2, top);
        g.setLineWidth(1);
    }
}
