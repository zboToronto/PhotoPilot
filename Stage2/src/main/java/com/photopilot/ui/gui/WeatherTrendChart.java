package com.photopilot.ui.gui;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.EnvironmentAnalysis;
import com.photopilot.model.EnvironmentAnalysis.HourSample;
import java.time.*;
import java.util.*;
import java.util.function.IntConsumer;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.TextAlignment;

/**
 * Hour-by-hour trend of one weather metric from the shared analysis. Points sit at their actual forecast instants;
 * consecutive points are joined only when they are at most one hour apart and both have a value, so a missing hour
 * leaves a visible gap instead of an invented line (a missing value is never drawn as zero). The availability is a
 * shaded period, the value axis follows {@link EnvironmentPresentation#axis} (percentages 0-100, temperatures below
 * zero kept), and hovering or selecting a point shows its exact value and time. A single value is drawn as a point.
 */
final class WeatherTrendChart extends CalculatorDiagram {
    private static final double LEFT = 52, TOP = 30, BOTTOM = 30, RIGHT = 14;
    private List<HourSample> hours = List.of();
    private String metric = "temperatureC";
    private Optional<Instant[]> highlight = Optional.empty();
    private ZoneId zone = ZoneOffset.UTC;
    private LocalDate base;
    private int hover = -1, selected = -1;
    private String hoverText = "";
    private IntConsumer onSelect = index -> { };

    WeatherTrendChart(LocalizationService localization) {
        super(localization, 240);
        getStyleClass().add("env-chart"); setFocusTraversable(true);
        setOnMouseMoved(event -> { hover = nearest(event.getX()); hoverText = describe(hover); redraw(); });
        setOnMouseExited(event -> { hover = -1; hoverText = selected >= 0 ? describe(selected) : ""; redraw(); });
        setOnMouseClicked(event -> { int index = nearest(event.getX()); if (index >= 0) { select(index); onSelect.accept(index); } });
        setOnKeyPressed(event -> {
            int next = switch (event.getCode()) { case LEFT -> selected - 1; case RIGHT -> selected + 1; default -> Integer.MIN_VALUE; };
            if (next == Integer.MIN_VALUE || hours.isEmpty()) return;
            next = Math.max(0, Math.min(hours.size() - 1, next < -1 ? 0 : next));
            select(next); onSelect.accept(next); event.consume();
        });
    }

    void setData(EnvironmentAnalysis analysis, String metric) {
        this.hours = analysis.hours(); this.zone = analysis.zone(); this.base = analysis.date(); this.metric = metric;
        this.highlight = Optional.of(new Instant[] {analysis.availability().start().toInstant(), analysis.availability().end().toInstant()});
        if (selected >= hours.size()) selected = -1;
        hoverText = selected >= 0 ? describe(selected) : "";
        redraw();
    }
    void metric(String metric) { this.metric = metric; hoverText = selected >= 0 ? describe(selected) : ""; redraw(); }
    String metric() { return metric; }
    void onSelect(IntConsumer action) { onSelect = Objects.requireNonNull(action); }
    void select(int index) { selected = index; hoverText = describe(index); redraw(); }
    int selected() { return selected; }
    String hoverText() { return hoverText; }
    /** Hovers the hour at {@code index}, as the pointer does. */
    void hover(int index) { hover = index; hoverText = describe(index); redraw(); }
    /** The plotted points: (x, y) in chart coordinates of every hour that has the metric; for tests. */
    List<double[]> points() {
        var found = new ArrayList<double[]>();
        double width = getWidth(), height = getHeight();
        for (var hour : hours) hour.value(metric).ifPresent(v -> found.add(new double[] {x(hour.time(), width), y(v, height)}));
        return found;
    }

    private String describe(int index) {
        if (index < 0 || index >= hours.size()) return "";
        var hour = hours.get(index);
        String value = hour.value(metric).map(v -> EnvironmentPresentation.value(metric, v, localization)).orElse(localization.text("env.value.unavailable"));
        return EnvironmentPresentation.clock(hour.local(), base) + " · " + EnvironmentPresentation.metric(metric, localization) + " " + value
            + (hour.inWindow() ? "" : " · " + localization.text("env.weather.context"));
    }
    private Instant[] span() {
        Instant from = hours.getFirst().time().minus(Duration.ofMinutes(30)), to = hours.getLast().time().plus(Duration.ofMinutes(30));
        if (highlight.isPresent()) {
            if (highlight.get()[0].isBefore(from)) from = highlight.get()[0];
            if (highlight.get()[1].isAfter(to)) to = highlight.get()[1];
        }
        return new Instant[] {from, to};
    }
    private double x(Instant at, double width) {
        var s = span();
        return LEFT + (width - LEFT - RIGHT) * Duration.between(s[0], at).toSeconds() / Math.max(1, Duration.between(s[0], s[1]).toSeconds());
    }
    private double[] axis() { return EnvironmentPresentation.axis(metric, hours.stream().map(h -> h.value(metric)).flatMap(Optional::stream).toList()); }
    private double y(double value, double height) {
        double[] a = axis();
        return height - BOTTOM - (height - BOTTOM - TOP) * (value - a[0]) / (a[1] - a[0]);
    }
    private int nearest(double px) {
        int best = -1; double distance = Double.MAX_VALUE;
        for (int i = 0; i < hours.size(); i++) {
            double d = Math.abs(x(hours.get(i).time(), getWidth()) - px);
            if (d < distance) { distance = d; best = i; }
        }
        return best;
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        if (hours.isEmpty()) return;
        var p = palette();
        boolean dark = p.text().getBrightness() > 0.5;
        double top = TOP, bottom = height - BOTTOM, right = width - RIGHT;
        highlight.ifPresent(h -> {
            double a = x(h[0], width), b = x(h[1], width);
            g.setFill(p.accent(dark ? 0.18 : 0.11)); g.fillRect(a, top, Math.max(1, b - a), bottom - top);
            g.setStroke(p.accent(0.6)); g.setLineDashes(4, 3); line(g, a, top, a, bottom); line(g, b, top, b, bottom); g.setLineDashes();
        });
        double[] a = axis();
        g.setStroke(p.hairline()); g.setTextBaseline(VPos.CENTER);
        for (int i = 0; i <= 4; i++) {
            double value = a[0] + (a[1] - a[0]) * i / 4, yy = y(value, height);
            line(g, LEFT, yy, right, yy);
            text(g, EnvironmentPresentation.number(metric, value), LEFT - 6, yy, TextAlignment.RIGHT, p.muted(), font(), 0);
        }
        if (a[0] < 0 && a[1] > 0) { g.setStroke(p.text(0.6)); line(g, LEFT, y(0, height), right, y(0, height)); }
        text(g, EnvironmentPresentation.metric(metric, localization) + " (" + EnvironmentPresentation.unit(metric, localization) + ")", 8, top - 16,
            TextAlignment.LEFT, p.muted(), font(), 0);
        g.setTextBaseline(VPos.BASELINE);
        int every = hours.size() > 16 ? 3 : hours.size() > 8 ? 2 : 1;
        for (int i = 0; i < hours.size(); i++) {
            if (hours.get(i).local().getHour() % every != 0) continue;
            double xx = x(hours.get(i).time(), width);
            textWithin(g, EnvironmentPresentation.clock(hours.get(i).local(), base), xx, bottom + 18, 0, width, p.muted(), font());
        }
        // Lines only between neighbouring hours that both have a value: a gap in the data stays a gap.
        g.setStroke(p.accent()); g.setLineWidth(2.2);
        for (int i = 1; i < hours.size(); i++) {
            var previous = hours.get(i - 1); var current = hours.get(i);
            if (previous.value(metric).isEmpty() || current.value(metric).isEmpty()) continue;
            if (Duration.between(previous.time(), current.time()).compareTo(Duration.ofHours(1)) > 0) continue;
            g.strokeLine(x(previous.time(), width), y(previous.value(metric).get(), height), x(current.time(), width), y(current.value(metric).get(), height));
        }
        g.setLineWidth(1);
        for (int i = 0; i < hours.size(); i++) {
            var hour = hours.get(i);
            if (hour.value(metric).isEmpty()) {
                double xx = x(hour.time(), width);
                g.setStroke(p.muted()); g.setLineDashes(2, 3); line(g, xx, top, xx, bottom); g.setLineDashes();
                continue;
            }
            double xx = x(hour.time(), width), yy = y(hour.value(metric).get(), height), r = i == selected ? 6 : hour.inWindow() ? 4.5 : 3.5;
            g.setFill(hour.inWindow() ? p.accent() : p.surface()); g.fillOval(xx - r, yy - r, 2 * r, 2 * r);
            g.setStroke(i == selected ? p.focus() : p.accent()); g.setLineWidth(i == selected ? 2.5 : 1.5); g.strokeOval(xx - r, yy - r, 2 * r, 2 * r); g.setLineWidth(1);
        }
        int shown = hover >= 0 ? hover : selected;
        if (shown >= 0 && shown < hours.size() && !hoverText.isEmpty()) {
            var hour = hours.get(shown);
            double xx = x(hour.time(), width), yy = hour.value(metric).map(v -> y(v, height)).orElse((top + bottom) / 2);
            g.setStroke(p.focus()); line(g, xx, top, xx, bottom);
            IntervalTimelineChart.tooltip(g, hoverText, xx, Math.max(top + 30, yy), width, p, font(), width(hoverText, font()));
        }
    }
}
