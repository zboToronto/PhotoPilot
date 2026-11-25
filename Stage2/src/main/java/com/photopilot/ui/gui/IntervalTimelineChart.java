package com.photopilot.ui.gui;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.i18n.LocalizationService;
import java.time.*;
import java.util.*;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/**
 * F09 horizontal interval timeline on one linear time axis: labelled rows of segments placed by their actual start and
 * end instants (never by index), optional vertical markers (sunrise, sunset) and a highlighted period (the user's
 * availability) drawn through every row; where a segment meets the highlight it is drawn darker and outlined, so an
 * overlap is visible without colour alone. Hovering shows the segment's name and exact times. Used for the natural-light
 * timeline and the shooting-window comparison; every interval comes from the shared analysis.
 */
final class IntervalTimelineChart extends CalculatorDiagram {
    /** Colour families; text and the legend always name them, so colour is never the only cue. */
    enum Tone { NIGHT, ASTRONOMICAL, NAUTICAL, CIVIL, DAY, GOLDEN, BLUE, AVAILABLE, PREPARATION, RECOMMENDED }
    record Segment(Instant start, Instant end, Tone tone, String label) { }
    record Row(String label, List<Segment> segments) { Row { segments = List.copyOf(segments); } }
    record Marker(Instant at, String label) { }

    private static final double ROW = 26, GAP = 8, TOP = 8, AXIS = 26;
    private List<Row> rows = List.of();
    private List<Marker> markers = List.of();
    private Instant from = Instant.EPOCH, to = Instant.EPOCH.plusSeconds(3600);
    private Optional<Instant[]> highlight = Optional.empty();
    private ZoneId zone = ZoneOffset.UTC;
    private LocalDate base;
    private double hoverX = -1, hoverY = -1;
    private String hoverText = "";

    IntervalTimelineChart(LocalizationService localization) {
        super(localization, 120);
        getStyleClass().add("env-chart");
        setOnMouseMoved(event -> { hoverX = event.getX(); hoverY = event.getY(); redraw(); });
        setOnMouseExited(event -> { hoverX = -1; hoverText = ""; redraw(); });
    }

    void setData(List<Row> rows, List<Marker> markers, Instant from, Instant to, Optional<Instant[]> highlight, ZoneId zone, LocalDate base) {
        this.rows = List.copyOf(rows); this.markers = List.copyOf(markers); this.from = from; this.to = to.isAfter(from) ? to : from.plusSeconds(3600);
        this.highlight = highlight; this.zone = zone; this.base = base;
        requestParentLayout(); redraw();
    }
    List<Row> rows() { return rows; }
    /** The text shown for the pointer position (empty when the pointer is over no segment); for tests and screen readers. */
    String hoverText() { return hoverText; }
    /** Moves the hover to a time on a row, as a pointer would. */
    void hover(int row, Instant at) {
        double left = labelWidth() + 8, right = getWidth() - 8;
        hoverX = x(at, left, right); hoverY = TOP + row * (ROW + GAP) + ROW / 2; layoutChildren();
    }

    @Override protected double heightFor(double width) { return TOP + rows.size() * (ROW + GAP) + AXIS; }

    private double labelWidth() {
        double widest = 0;
        for (var row : rows) widest = Math.max(widest, width(row.label(), font()));
        return Math.min(220, widest + 12);
    }
    private double x(Instant at, double left, double right) {
        double span = Duration.between(from, to).toSeconds();
        return left + (right - left) * Duration.between(from, at).toSeconds() / span;
    }

    @Override protected void draw(GraphicsContext g, double width, double height) {
        var p = palette();
        double left = labelWidth() + 8, right = width - 8, bottom = TOP + rows.size() * (ROW + GAP) - GAP;
        boolean dark = p.text().getBrightness() > 0.5;
        // Hour grid and labels on the shared axis (every 1, 2, 3 or 6 hours depending on the span).
        long hours = Math.max(1, Duration.between(from, to).toHours());
        int every = hours <= 8 ? 1 : hours <= 16 ? 2 : hours <= 30 ? 3 : 6;
        g.setStroke(p.hairline());
        var tick = from.atZone(zone).withMinute(0).withSecond(0).withNano(0);
        if (tick.toInstant().isBefore(from)) tick = tick.plusHours(1);
        for (; !tick.toInstant().isAfter(to); tick = tick.plusHours(1)) {
            if (tick.getHour() % every != 0) continue;
            double x = x(tick.toInstant(), left, right);
            line(g, x, TOP - 2, x, bottom + 4);
            textWithin(g, EnvironmentPresentation.clock(tick, base), x, bottom + 18, left - 20, right + 8, p.muted(), font());
        }
        // Availability band through every row.
        highlight.ifPresent(h -> {
            double a = x(h[0], left, right), b = x(h[1], left, right);
            g.setFill(p.accent(dark ? 0.16 : 0.10)); g.fillRect(a, TOP - 4, Math.max(1, b - a), bottom - TOP + 8);
            g.setStroke(p.accent(0.65)); g.setLineDashes(4, 3); line(g, a, TOP - 4, a, bottom + 4); line(g, b, TOP - 4, b, bottom + 4); g.setLineDashes();
        });
        hoverText = "";
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            double y = TOP + i * (ROW + GAP);
            g.setTextBaseline(VPos.CENTER);
            text(g, row.label(), 0, y + ROW / 2, TextAlignment.LEFT, p.text(), font(), left - 12);
            g.setFill(p.track()); g.fillRoundRect(left, y + 3, right - left, ROW - 6, 8, 8);
            for (var segment : row.segments()) {
                if (!segment.end().isAfter(from) || !segment.start().isBefore(to)) continue;
                Instant s = segment.start().isBefore(from) ? from : segment.start(), e = segment.end().isAfter(to) ? to : segment.end();
                double a = x(s, left, right), b = Math.max(a + 2, x(e, left, right));
                g.setFill(color(segment.tone(), p, dark)); g.fillRoundRect(a, y + 3, b - a, ROW - 6, 6, 6);
                highlight.ifPresent(h -> {
                    if (segment.tone() == Tone.AVAILABLE) return;
                    Instant os = s.isAfter(h[0]) ? s : h[0], oe = e.isBefore(h[1]) ? e : h[1];
                    if (oe.isAfter(os)) {
                        double oa = x(os, left, right), ob = Math.max(oa + 2, x(oe, left, right));
                        g.setFill(p.text(dark ? 0.30 : 0.22)); g.fillRoundRect(oa, y + 3, ob - oa, ROW - 6, 6, 6);
                        g.setStroke(p.text()); g.setLineWidth(1.5); g.strokeRoundRect(oa + .75, y + 3.75, ob - oa - 1.5, ROW - 7.5, 6, 6); g.setLineWidth(1);
                    }
                });
                if (b - a > width(segment.label(), font()) + 10 && segment.tone() != Tone.NIGHT)
                    text(g, segment.label(), (a + b) / 2, y + ROW / 2, TextAlignment.CENTER, onTone(segment.tone(), p, dark), font(), b - a - 6);
                if (hoverX >= a && hoverX <= b && hoverY >= y && hoverY <= y + ROW)
                    hoverText = segment.label() + " · " + EnvironmentPresentation.span(segment.start().atZone(zone), segment.end().atZone(zone), base)
                        + " · " + EnvironmentPresentation.minutes(Duration.between(segment.start(), segment.end()), localization);
            }
        }
        g.setTextBaseline(VPos.BASELINE);
        for (var marker : markers) {
            if (marker.at().isBefore(from) || marker.at().isAfter(to)) continue;
            double x = x(marker.at(), left, right);
            g.setStroke(p.text()); g.setLineWidth(1.5); line(g, x, TOP - 6, x, bottom + 2); g.setLineWidth(1);
            g.setFill(p.text()); g.fillPolygon(new double[] {x - 4, x + 4, x}, new double[] {TOP - 8, TOP - 8, TOP - 3}, 3);
            if (Math.abs(hoverX - x) <= 4) hoverText = marker.label() + " · " + EnvironmentPresentation.clock(marker.at().atZone(zone), base);
        }
        if (!hoverText.isEmpty()) tooltip(g, hoverText, hoverX, Math.max(hoverY, TOP + 4), width, p);
    }

    /** A rounded tooltip box near the pointer, kept inside the chart. */
    static void tooltip(GraphicsContext g, String text, double x, double y, double width, Palette p, javafx.scene.text.Font font, double textWidth) {
        double boxWidth = Math.min(width - 8, textWidth + 18), boxHeight = font.getSize() + 14;
        double bx = Math.max(4, Math.min(width - boxWidth - 4, x + 12)), by = Math.max(2, y - boxHeight - 6);
        g.setFill(p.surface()); g.fillRoundRect(bx, by, boxWidth, boxHeight, 10, 10);
        g.setStroke(p.hairline()); g.strokeRoundRect(bx + .5, by + .5, boxWidth - 1, boxHeight - 1, 10, 10);
        g.setFill(p.text()); g.setFont(font); g.setTextAlign(TextAlignment.LEFT); g.setTextBaseline(VPos.CENTER);
        g.fillText(text, bx + 9, by + boxHeight / 2, boxWidth - 18);
        g.setTextBaseline(VPos.BASELINE);
    }
    private void tooltip(GraphicsContext g, String text, double x, double y, double width, Palette p) { tooltip(g, text, x, y, width, p, font(), width(text, font())); }

    /** Restrained light-phase colours: a blue ramp from night to civil twilight, warm daylight and golden hour. */
    static Color color(Tone tone, Palette p, boolean dark) {
        return switch (tone) {
            case NIGHT -> dark ? Color.rgb(18, 24, 52) : Color.rgb(38, 46, 92);
            case ASTRONOMICAL -> dark ? Color.rgb(36, 48, 102) : Color.rgb(58, 72, 138);
            case NAUTICAL -> dark ? Color.rgb(58, 80, 150) : Color.rgb(88, 110, 182);
            case CIVIL -> dark ? Color.rgb(96, 124, 196) : Color.rgb(140, 162, 220);
            case DAY -> dark ? Color.rgb(214, 196, 128, 0.85) : Color.rgb(247, 230, 166);
            case GOLDEN -> dark ? Color.rgb(232, 168, 72) : Color.rgb(226, 160, 64);
            case BLUE -> dark ? Color.rgb(112, 142, 236) : Color.rgb(84, 116, 214);
            case AVAILABLE -> p.accent();
            case PREPARATION -> p.accent(0.35);
            case RECOMMENDED -> dark ? Color.rgb(104, 201, 138) : Color.rgb(46, 140, 86);
        };
    }
    private static Color onTone(Tone tone, Palette p, boolean dark) {
        return switch (tone) {
            case DAY, GOLDEN, PREPARATION -> Color.rgb(31, 35, 43);
            case CIVIL -> dark ? Color.WHITE : Color.rgb(31, 35, 43);
            default -> Color.WHITE;
        };
    }
}
