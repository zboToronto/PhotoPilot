package com.photopilot.ui.cli;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.EnvironmentAnalysis;
import com.photopilot.model.EnvironmentAnalysis.*;
import com.photopilot.model.TimeInterval;
import com.photopilot.service.EnvironmentAnalyzer;
import com.photopilot.ui.cli.TextTable.Column;
import java.time.*;
import java.util.*;

import static com.photopilot.application.EnvironmentPresentation.*;

/**
 * Terminal layout of one {@link EnvironmentAnalysis}: the concise overview printed after an assessment and the detail
 * sections (hourly table, metric trend bars, sun and light, shooting-window overlaps, AI analysis, status, technical
 * report). It formats only: every value, interval, status and finding comes from the shared analysis and every phrase
 * from {@link EnvironmentPresentation} and the bundles, exactly as on the GUI dashboard. Plain ASCII marks (no colour,
 * no screen control). Tables fit the given width (wrapping cells, or one value per line when very narrow) and charts are
 * drawn only where the width allows, always next to the numbers they show; sentences are printed whole (the terminal
 * wraps them), so narrow or redirected output stays complete and searchable.
 */
final class EnvironmentTerminalReport {
    /** Metric names accepted by {@code env report trend <metric>}, mapped to the analysis keys. */
    static final Map<String, String> TREND_METRICS;
    static {
        var map = new LinkedHashMap<String, String>();
        map.put("temperature", "temperatureC"); map.put("rain", "precipitationProbabilityPct"); map.put("precipitation", "precipitationMm");
        map.put("cloud", "cloudCoverPct"); map.put("wind", "windSpeedKmh"); map.put("gusts", "windGustsKmh"); map.put("visibility", "visibilityM");
        TREND_METRICS = Collections.unmodifiableMap(map);
    }
    private static final int MIN_CHART = 24;
    private final EnvironmentAnalysis analysis;
    private final LocalizationService l;
    private final int width;
    private final ZoneId zone;
    private final LocalDate date;

    EnvironmentTerminalReport(EnvironmentAnalysis analysis, LocalizationService localization, int width) {
        this.analysis = Objects.requireNonNull(analysis); this.l = Objects.requireNonNull(localization);
        this.width = Math.max(CapabilityTerminalReport.MIN_WIDTH, Math.min(CapabilityTerminalReport.MAX_WIDTH, width));
        this.zone = analysis.zone(); this.date = analysis.date();
    }

    // ---- Overview ----------------------------------------------------------------------------------------------------

    List<String> overview() {
        var lines = new ArrayList<String>();
        lines.add(l.text("env.cli.title")); rule(lines); lines.add("");
        heading(lines, l.text("env.cli.context"));
        for (String line : shootingContext(analysis.assessment(), l)) lines.add(line);
        analysis.request().leadTime().filter(d -> !d.isZero()).ifPresent(d -> lines.add(l.text("env.info.leadLine", d.toMinutes())));
        lines.add("");

        heading(lines, l.text("env.cli.weather") + " — " + scope(analysis, l));
        var rows = new LinkedHashMap<String, String>();
        rows.put(l.text("env.metric.condition"), condition(analysis, l));
        for (String metric : EnvironmentAnalysis.METRICS) {
            String value = windowValue(analysis, metric, l);
            if (metric.equals("windSpeedKmh") && analysis.window().metric(metric).isPresent()) value += " · " + l.text("env.overview.direction", directions(analysis, l));
            String day = switch (metric) {
                case "precipitationProbabilityPct" -> daily(analysis, "precipitationProbabilityMaxPct", l).map(v -> l.text("env.overview.dayMax", v)).orElse("");
                case "windGustsKmh" -> daily(analysis, "windGustsMaxKmh", l).map(v -> l.text("env.overview.dayMax", v)).orElse("");
                default -> "";
            };
            rows.put(metric(metric, l), day.isEmpty() ? value : value + " (" + day + ")");
        }
        keyValues(lines, rows);
        lines.add(l.text("env.overview.daily", dailySummary(analysis, l)));
        lines.add("");

        heading(lines, l.text("env.cli.light"));
        var sun = new LinkedHashMap<String, String>();
        for (String kind : List.of("sunrise", "solarNoon", "sunset"))
            analysis.event(date, kind).ifPresent(e -> sun.put(sunEvent(kind, l), time(e.time().atZone(zone), date)));
        if (analysis.event(date, "sunrise").isEmpty() && analysis.event(date, "sunset").isEmpty()) sun.put(sunEvent("sunset", l), l.text("env.value.unavailable"));
        for (var overlap : analysis.relevantOverlaps())
            sun.put(phase(overlap.phase().kind(), l) + (overlap.phase().date().equals(date) ? "" : " (" + overlap.phase().date() + ")"),
                span(overlap.phase().start(), overlap.phase().end(), date) + " " + l.text("env.events.estimateTag"));
        keyValues(lines, sun);
        lines.add("");

        heading(lines, l.text("env.cli.windows"));
        keyValues(lines, windowRows());
        lines.add("");

        heading(lines, l.text("env.cli.status"));
        lines.addAll(status());
        lines.add("");

        heading(lines, l.text("env.cli.findings"));
        var key = analysis.keyFindings();
        if (key.isEmpty()) lines.add(l.text("env.findings.none"));
        for (var finding : key) {
            String mark = finding.level() == Level.WARN ? "[!] " : "[i] ";
            lines.add(mark + finding(finding, l));
        }
        lines.add("");
        heading(lines, l.text("env.cli.sections"));
        var commands = new TextTable(Column.fixed(l.text("capability.cli.command")), Column.text(l.text("capability.cli.shows"), 16));
        for (String section : List.of("hourly", "trend", "sun", "windows", "ai", "retry", "technical", "menu"))
            commands.row(l.text("env.cli.usage." + section), l.text("env.cli.section." + section));
        lines.addAll(commands.render(width));
        return lines;
    }

    private Map<String, String> windowRows() {
        var rows = new LinkedHashMap<String, String>();
        rows.put(l.text("env.windows.available"), availabilityLine(analysis, l));
        rows.put(l.text("env.windows.golden"), minutes(analysis.goldenOverlap(), l));
        rows.put(l.text("env.windows.blue"), minutes(analysis.blueOverlap(), l));
        var windows = analysis.windows();
        rows.put(l.text("env.windows.recommended"), windows.isEmpty() ? l.text("env.windows.noneValidated")
            : l.text("env.windows.recommendedDetail", windows.size(), analysis.recommended().toMinutes()));
        return rows;
    }

    List<String> status() {
        var lines = new ArrayList<String>();
        for (var stage : analysis.stages()) {
            String tag = tag(stage.status());
            lines.add(String.format(Locale.ROOT, "%-7s", tag) + stageLine(stage, l));
        }
        lines.add("");
        lines.add(l.text("env.cli.overall", overall(analysis, l)));
        // The failure reason is already on the Gemini line above; here only what remains usable.
        if (analysis.ai().status() != AiStatus.SUCCESS) {
            lines.add(l.text("env.ai.factsKept"));
            if (analysis.windows().isEmpty()) lines.add(l.text("env.ai.noValidated"));
        }
        return lines;
    }

    // ---- Hourly forecast ---------------------------------------------------------------------------------------------

    List<String> hourly() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("env.cli.hourly", abbreviation(analysis.availability().start())));
        if (analysis.hours().isEmpty()) { lines.add(l.text("env.weather.none")); return lines; }
        // Short headers keep the table within 80 columns; the units follow in one line under it.
        var table = new TextTable(Column.fixed(" "), Column.fixed(l.text("env.cli.col.time")), Column.text(l.text("env.metric.condition"), 8),
            Column.number(shortMetric("temperatureC", l)), Column.number(shortMetric("precipitationProbabilityPct", l)), Column.number(shortMetric("precipitationMm", l)),
            Column.number(shortMetric("cloudCoverPct", l)), Column.number(shortMetric("windSpeedKmh", l)), Column.fixed(shortMetric("windDirectionDeg", l)),
            Column.number(shortMetric("windGustsKmh", l)), Column.number(shortMetric("visibilityM", l)));
        for (var hour : analysis.hours()) {
            table.row(hour.inWindow() ? "*" : "", clock(hour.local(), date), hour.value("weatherCode").map(c -> condition(c.intValue(), l)).orElse("-"),
                cell(hour, "temperatureC"), cell(hour, "precipitationProbabilityPct"), cell(hour, "precipitationMm"), cell(hour, "cloudCoverPct"),
                cell(hour, "windSpeedKmh"), hour.value("windDirectionDeg").map(EnvironmentPresentation::compass).orElse("-"),
                cell(hour, "windGustsKmh"), cell(hour, "visibilityM"));
        }
        lines.addAll(table.render(width));
        lines.add("");
        lines.add(l.text("env.cli.units"));
        lines.add(l.text("env.cli.windowMark", availabilityLine(analysis, l)));
        lines.add(l.text("env.cli.missingMark"));
        lines.add(l.text("env.weather.directionNote"));
        lines.add(freshness(analysis, l) + " · " + l.text("env.weather.forecastNote"));
        lines.add(l.text("env.overview.daily", dailySummary(analysis, l)));
        return lines;
    }
    private static String cell(HourSample hour, String metric) { return hour.value(metric).map(v -> number(metric, v)).orElse("-"); }

    /**
     * One metric hour by hour: the exact value and an ASCII bar on a linear axis stated in the heading (percentages
     * 0-100, amounts and speeds from zero, temperature between its rounded extremes). Missing hours get no bar.
     */
    List<String> trend(String metric) {
        var lines = new ArrayList<String>();
        heading(lines, l.text("env.cli.trend", metric(metric, l) + " (" + unit(metric, l) + ")"));
        var values = analysis.hours().stream().map(h -> h.value(metric)).flatMap(Optional::stream).toList();
        if (values.isEmpty()) { lines.add(l.text("env.weather.none")); return lines; }
        double[] axis = axis(metric, values);
        lines.add(l.text("env.cli.axis", number(metric, axis[0]), number(metric, axis[1]), unit(metric, l)));
        if (values.size() == 1) lines.add(l.text("env.weather.single"));
        int label = 2 + 5 + 1, valueWidth = 10, bar = width - label - valueWidth - 3;
        for (var hour : analysis.hours()) {
            String prefix = (hour.inWindow() ? "* " : "  ") + clock(hour.local(), date);
            var value = hour.value(metric);
            if (value.isEmpty()) { lines.add(TextTable.strip(prefix + " |" + " ".repeat(Math.max(0, bar)) + "  " + l.text("env.value.unavailable"))); continue; }
            String text = value(metric, value.get(), l);
            if (bar < 8) { lines.add(prefix + "  " + text); continue; }
            int filled = (int) Math.round((value.get() - axis[0]) / (axis[1] - axis[0]) * bar);
            filled = Math.max(0, Math.min(bar, filled));
            lines.add(prefix + " |" + "#".repeat(filled) + " ".repeat(bar - filled) + "  " + text);
        }
        lines.add("");
        lines.add(l.text("env.cli.windowMark", availabilityLine(analysis, l)));
        return lines;
    }

    // ---- Sun and light -----------------------------------------------------------------------------------------------

    List<String> sun() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("env.cli.sun") + " (" + abbreviation(analysis.availability().start()) + ")");
        subheading(lines, l.text("env.cli.events"));
        if (analysis.events().isEmpty()) lines.add("  " + l.text("env.evidence.sunNone"));
        var events = new TextTable(Column.text(l.text("env.cli.col.event"), 10), Column.fixed(l.text("env.cli.col.time")), Column.text(l.text("env.cli.col.source"), 12));
        for (var event : analysis.events())
            events.row(sunEvent(event.kind(), l), clock(event.time().atZone(zone), date), event.source());
        if (!events.isEmpty()) lines.addAll(events.render(width));
        lines.add("");
        subheading(lines, l.text("env.cli.phases"));
        var phases = new TextTable(Column.text(l.text("env.windows.column.phase"), 10), Column.fixed(l.text("env.windows.column.time")), Column.number(l.text("env.windows.column.minutes")));
        for (var overlap : analysis.overlaps())
            phases.row(phase(overlap.phase().kind(), l), span(overlap.phase().start(), overlap.phase().end(), date),
                String.valueOf(Duration.between(overlap.phase().start(), overlap.phase().end()).toMinutes()));
        if (phases.isEmpty()) lines.add("  " + l.text("env.phase.none")); else lines.addAll(phases.render(width));
        lines.add(l.text("env.light.definitions"));
        lines.add("");
        lines.addAll(lightTimeline());
        lines.add("");
        subheading(lines, l.text("env.cli.solarTable"));
        var solar = new TextTable(Column.fixed(l.text("env.cli.col.time")), Column.number(l.text("env.cli.col.elevation")), Column.number(l.text("env.cli.col.azimuth")),
            Column.text(l.text("env.cli.col.light"), 10));
        for (var sample : analysis.solar()) {
            if (sample.time().getMinute() != 0) continue;
            solar.row(clock(sample.time(), date), degrees(sample.elevation()) + "°", degrees(sample.azimuth()) + "°", lightAt(sample.elevation(), l));
        }
        lines.addAll(solar.render(width));
        lines.add(l.text("env.solar.azimuthNote"));
        lines.add(l.text("env.solar.model", EnvironmentAnalyzer.SOLAR_STEP.toMinutes()));
        lines.add("");
        lines.add(l.text("env.light.limitations"));
        return lines;
    }

    /** Day-long ASCII light timeline: one character per fixed number of minutes, chosen at its midpoint. */
    private List<String> lightTimeline() {
        var lines = new ArrayList<String>();
        int label = labelWidth(List.of(l.text("env.cli.row.light"), l.text("env.cli.row.phases"), l.text("env.cli.row.available"))), columns = Math.min(96, width - label - 2);
        long total = Duration.between(analysis.from(), analysis.to()).toMinutes();
        if (columns < MIN_CHART) { lines.add(l.text("env.cli.timelineNarrow")); return lines; }
        double step = (double) total / columns;
        subheading(lines, l.text("env.cli.timeline", clock(analysis.from(), zone, date), clock(analysis.to(), zone, date), String.format(Locale.ROOT, "%.0f", step)));
        StringBuilder band = new StringBuilder(), light = new StringBuilder(), available = new StringBuilder();
        var availability = new TimeInterval(analysis.availability().start().toInstant(), analysis.availability().end().toInstant());
        for (int i = 0; i < columns; i++) {
            Instant at = analysis.from().plusSeconds(Math.round((i + 0.5) * step * 60));
            band.append(analysis.bands().stream().filter(b -> b.interval().contains(at)).findFirst().map(b -> BAND_MARK.get(b.kind())).orElse(' '));
            char phase = '.';
            for (var overlap : analysis.overlaps()) {
                var p = overlap.phase();
                if (!at.isBefore(p.start().toInstant()) && at.isBefore(p.end().toInstant())) phase = p.golden() ? 'G' : 'B';
            }
            light.append(phase);
            available.append(availability.contains(at) ? '=' : '.');
        }
        lines.add(pad(l.text("env.cli.row.light"), label) + "|" + band + "|");
        lines.add(pad(l.text("env.cli.row.phases"), label) + "|" + light + "|");
        lines.add(pad(l.text("env.cli.row.available"), label) + "|" + available + "|");
        lines.add(pad("", label) + " " + ticks(columns, step));
        lines.add(l.text("env.cli.legend"));
        lines.add(l.text("env.cli.shortNote"));
        return lines;
    }
    private static final Map<LightKind, Character> BAND_MARK = Map.of(LightKind.NIGHT, 'N', LightKind.ASTRONOMICAL, 'A', LightKind.NAUTICAL, 'U',
        LightKind.CIVIL, 'C', LightKind.DAY, 'D');
    /** Hour labels under a chart row every few hours, placed at their exact column. */
    private String ticks(int columns, double step) {
        var row = new char[columns + 6];
        Arrays.fill(row, ' ');
        long total = Duration.between(analysis.from(), analysis.to()).toHours();
        int every = total > 30 ? 12 : 6;
        for (ZonedDateTime t = analysis.from().atZone(zone); !t.toInstant().isAfter(analysis.to()); t = t.plusHours(every)) {
            int column = (int) Math.round(Duration.between(analysis.from(), t.toInstant()).toMinutes() / step);
            String text = String.format(Locale.ROOT, "%02d", t.getHour());
            for (int i = 0; i < text.length() && column + i < row.length; i++) row[column + i] = text.charAt(i);
        }
        return TextTable.strip(new String(row));
    }

    // ---- Shooting windows --------------------------------------------------------------------------------------------

    List<String> windows() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("env.cli.windows"));
        var rows = windowRows();
        rows.put(l.text("env.windows.anyLight"), minutes(analysis.lightOverlap(), l));
        keyValues(lines, rows);
        lines.add("");
        subheading(lines, l.text("env.windows.overlapTitle"));
        var table = new TextTable(Column.text(l.text("env.windows.column.phase"), 10), Column.fixed(l.text("env.windows.column.time")),
            Column.text(l.text("env.windows.column.overlap"), 12));
        for (var overlap : analysis.relevantOverlaps())
            table.row(phase(overlap.phase().kind(), l), span(overlap.phase().start(), overlap.phase().end(), date), overlap(overlap, analysis, l));
        if (table.isEmpty()) lines.add("  " + l.text("env.phase.none")); else lines.addAll(table.render(width));
        lines.add(l.text("env.windows.overlapNote"));
        lines.add("");
        lines.addAll(intervalChart());
        lines.add("");
        subheading(lines, l.text("env.windows.recommendedTitle"));
        var cards = EnvironmentPresentation.windows(analysis.assessment(), l);
        if (cards.isEmpty()) lines.add(l.text("env.windows.noneValidated"));
        for (var card : cards) {
            lines.add(card.getFirst());
            for (String line : card.subList(1, card.size())) lines.add("  " + line);
        }
        return lines;
    }

    /** Availability, preparation, light phases and validated windows on one linear axis around the availability. */
    private List<String> intervalChart() {
        var lines = new ArrayList<String>();
        var available = new TimeInterval(analysis.availability().start().toInstant(), analysis.availability().end().toInstant());
        var rows = new LinkedHashMap<String, List<TimeInterval>>();
        rows.put(l.text("env.windows.row.available"), List.of(available));
        var golden = new ArrayList<TimeInterval>(); var blue = new ArrayList<TimeInterval>();
        for (var o : analysis.relevantOverlaps())
            (o.phase().golden() ? golden : blue).add(new TimeInterval(o.phase().start().toInstant(), o.phase().end().toInstant()));
        rows.put(l.text("env.windows.row.golden"), golden);
        rows.put(l.text("env.windows.row.blue"), blue);
        rows.put(l.text("env.windows.row.preparation"), EnvironmentAnalyzer.preparation(analysis.assessment()));
        rows.put(l.text("env.windows.row.recommended"), analysis.windows().stream().map(w -> new TimeInterval(w.start().toInstant(), w.end().toInstant())).toList());
        Instant start = available.start(), end = available.end();
        for (var list : rows.values()) for (var interval : list) {
            if (interval.start().isBefore(start)) start = interval.start();
            if (interval.end().isAfter(end)) end = interval.end();
        }
        start = start.minus(Duration.ofMinutes(30)); end = end.plus(Duration.ofMinutes(30));
        var labels = new ArrayList<>(rows.keySet()); labels.add(sunEvent("sunrise", l)); labels.add(sunEvent("sunset", l));
        int label = labelWidth(labels), columns = Math.min(72, width - label - 2);
        if (columns < MIN_CHART) { lines.add(l.text("env.cli.timelineNarrow")); return lines; }
        double step = (double) Duration.between(start, end).toMinutes() / columns;
        subheading(lines, l.text("env.cli.timeline", clock(start, zone, date), clock(end, zone, date), String.format(Locale.ROOT, "%.0f", step)));
        for (var row : rows.entrySet()) {
            var text = new StringBuilder();
            for (int i = 0; i < columns; i++) {
                Instant at = start.plusSeconds(Math.round((i + 0.5) * step * 60));
                boolean inside = row.getValue().stream().anyMatch(iv -> !at.isBefore(iv.start()) && at.isBefore(iv.end()));
                boolean both = inside && !row.getKey().equals(l.text("env.windows.row.available")) && available.contains(at);
                text.append(both ? '#' : inside ? '=' : '.');
            }
            lines.add(pad(row.getKey(), label) + "|" + text + "|");
        }
        for (String kind : List.of("sunrise", "sunset")) {
            var event = analysis.event(date, kind);
            if (event.isEmpty() || event.get().time().isBefore(start) || event.get().time().isAfter(end)) continue;
            int column = (int) Math.round(Duration.between(start, event.get().time()).toMinutes() / step);
            lines.add(pad(sunEvent(kind, l), label) + " " + " ".repeat(Math.max(0, Math.min(columns - 1, column))) + "^ " + clock(event.get().time().atZone(zone), date));
        }
        lines.add(l.text("env.cli.intervalLegend"));
        lines.add(l.text("env.cli.shortNote"));
        return lines;
    }

    // ---- AI analysis and technical details ---------------------------------------------------------------------------

    List<String> ai() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("env.cli.ai"));
        for (String line : aiOutcome(analysis, l)) lines.add(line);
        lines.add("");
        subheading(lines, l.text("env.card.ai"));
        for (String line : interpretation(analysis.assessment(), l)) lines.add(line);
        lines.add("");
        subheading(lines, l.text("env.windows.recommendedTitle"));
        var cards = EnvironmentPresentation.windows(analysis.assessment(), l);
        if (cards.isEmpty()) lines.add(l.text("env.windows.noneValidated"));
        for (var card : cards) for (String line : card) lines.add(line);
        lines.add("");
        subheading(lines, l.text("env.card.rejected"));
        for (String line : rejected(analysis.assessment(), l)) lines.add(line);
        return lines;
    }

    List<String> technical() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("env.cli.technical"));
        var rows = new LinkedHashMap<String, String>();
        rows.put(l.text("env.info.input"), analysis.request().accepted().map(c -> c.query()).filter(q -> !q.isBlank()).orElse(analysis.request().place().label()));
        rows.put(l.text("env.info.location"), placeLine(analysis));
        rows.put(l.text("env.info.coordinates"), coordinates(analysis));
        rows.put(l.text("env.info.source"), provenance(analysis, l));
        rows.put(l.text("env.info.zone"), zone.getId() + " · " + zoneSource(analysis.assessment(), l));
        rows.put(l.text("env.info.assessed"), time(analysis.assessment().assessedAt().atZone(zone), null));
        analysis.assessment().context().sourceTimestamps().forEach((group, stamp) -> rows.put(l.text("env.info.retrievedGroup", group), time(stamp.atZone(zone), null)));
        keyValues(lines, rows);
        lines.add("");
        subheading(lines, l.text("env.cli.status"));
        lines.addAll(status());
        lines.add("");
        subheading(lines, l.text("env.findings.all"));
        if (analysis.findings().isEmpty()) lines.add(l.text("env.findings.none"));
        for (var finding : analysis.findings()) lines.add("- " + finding(finding, l));
        lines.add("");
        lines.addAll(report(analysis.assessment(), l));
        lines.add("");
        lines.add(l.text("env.light.definitions"));
        lines.add(l.text("env.light.limitations"));
        return lines;
    }

    // ---- Shared building blocks --------------------------------------------------------------------------------------

    private void heading(List<String> lines, String text) {
        lines.add(text);
        lines.add("-".repeat(Math.min(width, Math.max(10, Math.min(50, TextTable.width(text))))));
    }
    private void subheading(List<String> lines, String text) { lines.add(text); }
    private void rule(List<String> lines) { lines.add("-".repeat(Math.min(width, 50))); }
    /**
     * "Label:   value" rows with one aligned label column (at most half the width). Values are never cut or wrapped
     * here: a long value continues on the terminal's own line wrap, so a redirected report keeps whole sentences.
     */
    private void keyValues(List<String> lines, Map<String, String> rows) {
        int labelWidth = Math.min(width / 2, rows.keySet().stream().mapToInt(k -> TextTable.width(k) + 1).max().orElse(0) + 2);
        for (var row : rows.entrySet()) {
            String label = row.getKey() + ":";
            lines.add(TextTable.strip(TextTable.width(label) >= labelWidth ? label + " " + row.getValue() : TextTable.pad(label, labelWidth, TextTable.Align.LEFT) + row.getValue()));
        }
    }
    private static String pad(String text, int width) { return TextTable.pad(text, width, TextTable.Align.LEFT); }
    /** Width of a chart's label column: its longest label plus a space (labels are never cut). */
    private static int labelWidth(Collection<String> labels) { return labels.stream().mapToInt(TextTable::width).max().orElse(0) + 1; }
}
