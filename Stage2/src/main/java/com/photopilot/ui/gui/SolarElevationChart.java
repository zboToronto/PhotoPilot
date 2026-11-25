package com.photopilot.ui.gui;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.EnvironmentAnalysis;
import com.photopilot.model.EnvironmentAnalysis.SolarSample;
import java.time.*;
import java.util.*;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.TextAlignment;

/**
 * Solar elevation over the local day(s) from the deterministic NOAA model samples of the shared analysis: local time on
 * the horizontal axis (actual instants), elevation in degrees on the vertical axis with the horizon at 0°, golden- and
 * blue-hour elevation bands, the user's availability as a shaded period and provider sunrise, solar noon and sunset as
 * labelled markers. Negative elevations are drawn below the horizon. Hovering (or the keyboard: left/right) reads the
 * nearest computed sample: time, elevation, azimuth (clockwise from true north) and light category.
 */
final class SolarElevationChart extends CalculatorDiagram {
    record Marker(Instant at, String label) { }
    private static final double LEFT = 46, TOP = 14, BOTTOM = 30, RIGHT = 12;
    private List<SolarSample> samples = List.of();
    private List<Marker> markers = List.of();
    private Optional<Instant[]> highlight = Optional.empty();
    private ZoneId zone = ZoneOffset.UTC;
    private LocalDate base;
    private int hover = -1;
    private String hoverText = "";
    private java.util.function.Consumer<String> onHover = text -> { };

    SolarElevationChart(LocalizationService localization) {
        super(localization, 280);
        getStyleClass().add("env-chart"); setFocusTraversable(true);
        setOnMouseMoved(event -> select(nearest(event.getX())));
        setOnMouseExited(event -> { if (!isFocused()) select(-1); });
        setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case LEFT -> { select(Math.max(0, (hover < 0 ? samples.size() / 2 : hover) - 1)); event.consume(); }
                case RIGHT -> { select(Math.min(samples.size() - 1, (hover < 0 ? samples.size() / 2 : hover) + 1)); event.consume(); }
                default -> { }
            }
        });
    }

    void setData(EnvironmentAnalysis analysis, List<Marker> markers) {
        this.samples = analysis.solar(); this.markers = List.copyOf(markers); this.zone = analysis.zone(); this.base = analysis.date();
        this.highlight = Optional.of(new Instant[] {analysis.availability().start().toInstant(), analysis.availability().end().toInstant()});
        hover = -1; hoverText = ""; redraw();
    }
    void onHover(java.util.function.Consumer<String> action) { onHover = Objects.requireNonNull(action); }
    List<SolarSample> samples() { return samples; }
    String hoverText() { return hoverText; }
    /** Reads the sample nearest to {@code time}, as hovering there does. */
    void hover(Instant time) { select(indexAt(time)); }
    private int indexAt(Instant time) {
        int best = -1; long distance = Long.MAX_VALUE;
        for (int i = 0; i < samples.size(); i++) {
            long d = Math.abs(Duration.between(samples.get(i).time().toInstant(), time).toSeconds());
            if (d < distance) { distance = d; best = i; }
        }
        return best;
    }
    private void select(int index) {
        hover = index;
        hoverText = index < 0 ? "" : describe(samples.get(index));
        onHover.accept(hoverText);
        redraw();
    }
    private String describe(SolarSample s) {
        return localization.text("env.solar.hover", EnvironmentPresentation.clock(s.time(), base), EnvironmentPresentation.degrees(s.elevation()),
            EnvironmentPresentation.degrees(s.azimuth()), EnvironmentPresentation.lightAt(s.elevation(), localization));
    }

    private double[] range() {
        double low = samples.stream().mapToDouble(SolarSample::elevation).min().orElse(-20), high = samples.stream().mapToDouble(SolarSample::elevation).max().orElse(20);
        return new double[] {Math.min(-20, Math.floor(low / 10) * 10), Math.max(20, Math.ceil(high / 10) * 10)};
    }
    /** The sample nearest to the time under the pointer (the axis is linear in time). */
    private int nearest(double x) {
        if (samples.isEmpty()) return -1;
        double right = getWidth() - RIGHT;
        Instant from = samples.getFirst().time().toInstant(), to = samples.getLast().time().toInstant();
        double fraction = Math.max(0, Math.min(1, (x - LEFT) / Math.max(1, right - LEFT)));
        return indexAt(from.plusMillis(Math.round(fraction * Duration.between(from, to).toMillis())));
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        if (samples.size() < 2) return;
        var p = palette();
        boolean dark = p.text().getBrightness() > 0.5;
        double left = LEFT, right = width - RIGHT, top = TOP, bottom = height - BOTTOM;
        Instant from = samples.getFirst().time().toInstant(), to = samples.getLast().time().toInstant();
        double span = Duration.between(from, to).toSeconds();
        double[] r = range();
        java.util.function.DoubleUnaryOperator y = e -> bottom - (bottom - top) * (e - r[0]) / (r[1] - r[0]);
        java.util.function.Function<Instant, Double> x = t -> left + (right - left) * Duration.between(from, t).toSeconds() / span;
        // Golden (-4..6) and blue (-6..-4) hour elevation bands across the chart.
        g.setFill(IntervalTimelineChart.color(IntervalTimelineChart.Tone.GOLDEN, p, dark).deriveColor(0, 1, 1, 0.18));
        g.fillRect(left, y.applyAsDouble(6), right - left, y.applyAsDouble(-4) - y.applyAsDouble(6));
        g.setFill(IntervalTimelineChart.color(IntervalTimelineChart.Tone.BLUE, p, dark).deriveColor(0, 1, 1, 0.18));
        g.fillRect(left, y.applyAsDouble(-4), right - left, y.applyAsDouble(-6) - y.applyAsDouble(-4));
        highlight.ifPresent(h -> {
            double a = x.apply(h[0]), b = x.apply(h[1]);
            g.setFill(p.accent(dark ? 0.18 : 0.12)); g.fillRect(a, top, Math.max(1, b - a), bottom - top);
        });
        // Degree grid every 10 degrees (the horizon stronger) and hour labels.
        g.setStroke(p.hairline()); g.setTextBaseline(VPos.CENTER);
        for (double e = r[0]; e <= r[1]; e += 10) {
            double yy = y.applyAsDouble(e);
            line(g, left, yy, right, yy);
            text(g, EnvironmentPresentation.degrees(e).replace(".0", "") + "°", left - 6, yy, TextAlignment.RIGHT, p.muted(), font(), 0);
        }
        g.setStroke(p.text()); g.setLineWidth(1.5); line(g, left, y.applyAsDouble(0), right, y.applyAsDouble(0)); g.setLineWidth(1);
        text(g, localization.text("env.solar.horizon"), right - 4, y.applyAsDouble(0) - 9, TextAlignment.RIGHT, p.muted(), font(), 0);
        g.setTextBaseline(VPos.BASELINE);
        long hours = Duration.between(from, to).toHours();
        int every = hours <= 12 ? 2 : hours <= 30 ? 3 : 6;
        for (var t = samples.getFirst().time(); !t.toInstant().isAfter(to); t = t.plusHours(1)) {
            if (t.getMinute() != 0 || t.getHour() % every != 0) continue;
            double xx = x.apply(t.toInstant());
            g.setStroke(p.hairline()); line(g, xx, top, xx, bottom);
            textWithin(g, EnvironmentPresentation.clock(t, base), xx, bottom + 18, left - 10, right + 10, p.muted(), font());
        }
        // The computed curve: one segment per pair of consecutive samples, drawn thicker above the horizon.
        g.setLineWidth(2.4); g.setStroke(IntervalTimelineChart.color(IntervalTimelineChart.Tone.GOLDEN, p, dark));
        g.beginPath();
        for (int i = 0; i < samples.size(); i++) {
            double xx = x.apply(samples.get(i).time().toInstant()), yy = y.applyAsDouble(samples.get(i).elevation());
            if (i == 0) g.moveTo(xx, yy); else g.lineTo(xx, yy);
        }
        g.stroke(); g.setLineWidth(1);
        for (var marker : markers) {
            if (marker.at().isBefore(from) || marker.at().isAfter(to)) continue;
            double xx = x.apply(marker.at());
            g.setStroke(p.text(0.7)); g.setLineDashes(3, 3); line(g, xx, top, xx, bottom); g.setLineDashes();
            text(g, marker.label() + " " + EnvironmentPresentation.clock(marker.at().atZone(zone), base), Math.min(right - 4, xx + 4), top + 10,
                TextAlignment.LEFT, p.text(), font(), Math.max(40, right - xx - 6));
        }
        if (hover >= 0 && hover < samples.size()) {
            var s = samples.get(hover);
            double xx = x.apply(s.time().toInstant()), yy = y.applyAsDouble(s.elevation());
            g.setStroke(p.focus()); line(g, xx, top, xx, bottom);
            g.setFill(p.focus()); g.fillOval(xx - 4.5, yy - 4.5, 9, 9);
            IntervalTimelineChart.tooltip(g, hoverText, xx, Math.max(top + 30, yy), width, p, font(), width(hoverText, font()));
        }
    }
}
