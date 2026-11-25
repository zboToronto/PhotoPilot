package com.photopilot.ui.cli;

import com.photopilot.application.CapabilityPresentation;
import com.photopilot.application.FocalScale;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import com.photopilot.ui.cli.TextTable.Column;
import java.util.*;
import java.util.stream.Collectors;

import static com.photopilot.application.CapabilityPresentation.*;

/**
 * Terminal layout of one {@link GearCapabilityAnalysis}: summary, tables, an ASCII focal chart on the shared
 * logarithmic {@link FocalScale}, the compatibility matrix and combination details. It formats only; every count,
 * interval and status comes from the analysis, and every phrase from {@link CapabilityPresentation} and the bundles.
 * Plain ASCII marks (no colour, no screen control), wrapped to the given width so narrow and redirected output stay
 * readable. Lenses and cameras are numbered in report order; those numbers are the arguments of the commands.
 */
final class CapabilityTerminalReport {
    static final int MIN_WIDTH = 40, DEFAULT_WIDTH = 80, MAX_WIDTH = 200;
    /** Below this many chart columns the chart is replaced by the interval lists alone. */
    private static final int MIN_PLOT = 30;
    /** Fields of {@code gear edit} that resolve an issue code. */
    private static final Map<String, List<String>> EDIT_FIELDS = Map.ofEntries(
        Map.entry("mount", List.of("mount")), Map.entry("mountNaming", List.of("mount")),
        Map.entry("sensorSize", List.of("sensorWidthMm", "sensorHeightMm")), Map.entry("sensorWidth", List.of("sensorWidthMm")),
        Map.entry("sensorHeight", List.of("sensorHeightMm")), Map.entry("sensorRange", List.of("sensorWidthMm", "sensorHeightMm")),
        Map.entry("resolution", List.of("resolutionMP")), Map.entry("resolutionRange", List.of("resolutionMP")),
        Map.entry("burstRate", List.of("burstFps")), Map.entry("burstRange", List.of("burstFps")),
        Map.entry("bodyStabilization", List.of("stabilization")), Map.entry("lensStabilization", List.of("stabilization")),
        Map.entry("apertureLongEnd", List.of("maxApertureAtMaxFocal")), Map.entry("apertureOrder", List.of("maxAperture", "maxApertureAtMaxFocal")),
        Map.entry("apertureRange", List.of("maxAperture")), Map.entry("autofocus", List.of("autofocus")),
        Map.entry("focalRange", List.of("minFocalMm", "maxFocalMm")), Map.entry("filterRange", List.of("filterDiameterMm")));
    private final GearCapabilityAnalysis report;
    private final LocalizationService l;
    private final int width;
    private final Map<UUID, String> names;

    CapabilityTerminalReport(GearCapabilityAnalysis report, LocalizationService localization, int width) {
        this.report = Objects.requireNonNull(report); this.l = Objects.requireNonNull(localization);
        this.width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, width)); this.names = names(report.equipment());
    }

    /** {@code COLUMNS} when the shell exports it, otherwise 80. */
    static int width(Map<String, String> environment) {
        try { return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Integer.parseInt(environment.getOrDefault("COLUMNS", "").strip()))); }
        catch (NumberFormatException missing) { return DEFAULT_WIDTH; }
    }

    int cameraCount() { return report.cameras().size(); }
    int lensCount() { return report.lenses().size(); }

    // ---- Summary -----------------------------------------------------------------------------------------------------

    List<String> summary() {
        var lines = new ArrayList<String>();
        say(lines, l.text("capability.cli.title")); lines.add("");
        heading(lines, l.text("capability.cli.summary"));
        var rows = new LinkedHashMap<String, String>();
        for (var type : REPORT_TYPES) rows.put(l.text("capability.metric." + type.name()), l.text("capability.unitsRecords", report.units(type), report.records(type)));
        rows.put(l.text("capability.metric.span"), report.focalSpan().map(CapabilityPresentation::focal).orElse(l.text("capability.none")));
        rows.put(l.text("capability.metric.gaps"), String.valueOf(report.gaps().size()));
        rows.put(l.text("capability.metric.aperture"), report.largestAperture().map(CapabilityPresentation::fNumber).orElse(l.text("capability.value.unknown")));
        keyValues(lines, rows);
        if (report.equipment().isEmpty()) say(lines, l.text("capability.empty"));
        lines.add("");
        heading(lines, l.text("capability.cli.findings"));
        for (var finding : report.findings()) {
            String tag = "[" + level(finding.level(), l) + "] ";
            lines.addAll(TextTable.hanging(tag + finding(finding, report, l), width, TextTable.width(tag)));
        }
        lines.add("");
        heading(lines, l.text("capability.cli.sections"));
        var commands = new TextTable(Column.fixed(l.text("capability.cli.command")), Column.text(l.text("capability.cli.shows"), 16));
        for (var section : List.of("cameras", "lenses", "coverage", "matrix", "pair", "quality", "all"))
            commands.row(l.text("capability.cli.usage." + section), l.text("capability.cli.section." + section));
        lines.addAll(commands.render(width));
        return lines;
    }

    // ---- Equipment tables --------------------------------------------------------------------------------------------

    /** All recorded equipment as tables (cameras, lenses, filters, other types); identifiers are in {@code capability all}. */
    List<String> overview() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.section.OVERVIEW"));
        if (report.equipment().isEmpty()) { say(lines, l.text("capability.empty")); return lines; }
        subheading(lines, l.text("capability.metric.CAMERA")); lines.addAll(cameraTable()); lines.add("");
        subheading(lines, l.text("capability.metric.LENS")); lines.addAll(lensTable()); lines.add("");
        var filters = report.equipment().stream().filter(Filter.class::isInstance).map(Filter.class::cast).toList();
        subheading(lines, l.text("capability.metric.FILTER"));
        if (filters.isEmpty()) say(lines, l.text("capability.none"));
        else {
            var table = new TextTable(Column.number("#"), Column.text(l.text("capability.column.model"), 12), Column.fixed(l.text("capability.column.diameter")),
                Column.fixed(l.text("capability.column.ndStops")), Column.number(l.text("capability.column.quantity")));
            int i = 1;
            for (var f : filters) table.row(String.valueOf(i++), names.get(f.id()), mm(f.diameterMm()), f.ndStops().map(CapabilityPresentation::number).orElse(notRecorded(l)), String.valueOf(f.quantity()));
            lines.addAll(table.render(width));
        }
        var others = report.equipment().stream().filter(e -> !REPORT_TYPES.contains(e.type())).toList();
        if (!others.isEmpty()) {
            lines.add(""); subheading(lines, l.text("capability.cli.otherEquipment"));
            var table = new TextTable(Column.fixed(l.text("capability.column.type")), Column.text(l.text("capability.column.model"), 12), Column.number(l.text("capability.column.quantity")));
            for (var e : others) table.row(l.text("gear.type." + e.type().name()), names.get(e.id()), String.valueOf(e.quantity()));
            lines.addAll(table.render(width));
        }
        return lines;
    }

    List<String> cameras() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.tab.CAMERAS"));
        lines.addAll(cameraTable());
        return lines;
    }

    private List<String> cameraTable() {
        var lines = new ArrayList<String>();
        if (report.cameras().isEmpty()) { say(lines, l.text("capability.cli.noCameras")); return lines; }
        var table = new TextTable(Column.number("#"), Column.text(l.text("capability.column.model"), 12), Column.number(l.text("capability.column.quantity")),
            Column.text(l.text("capability.column.mount"), 5), Column.text(l.text("capability.column.sensor"), 9),
            Column.text(l.text("capability.column.resolution"), 6), Column.text(l.text("capability.column.burst"), 6), Column.text(l.text("capability.column.ibis"), 4));
        var details = new TextTable(Column.number("#"), Column.text(l.text("capability.column.format"), 8), Column.fixed(l.text("capability.column.crop")),
            Column.text(l.text("capability.column.af"), 12));
        int i = 1;
        for (var entry : report.cameras()) {
            var c = entry.camera(); String number = String.valueOf(i++);
            table.row(number, names.get(c.id()), String.valueOf(c.quantity()), mount(entry.mount(), l), sensor(c, l),
                megapixels(c.resolutionMP(), l), fps(c.burstFps(), l), yesNo(c.stabilization(), l));
            details.row(number, sensorFormat(entry, l), crop(entry.cropFactor(), l), list(c.autofocusModes(), l));
        }
        lines.addAll(table.render(width)); lines.add(""); lines.addAll(details.render(width));
        return lines;
    }

    List<String> lenses() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.tab.LENSES"));
        lines.addAll(lensTable()); lines.add("");
        lines.addAll(aperture());
        return lines;
    }

    private List<String> lensTable() {
        var lines = new ArrayList<String>();
        if (report.lenses().isEmpty()) { say(lines, l.text("capability.cli.noLenses")); return lines; }
        var table = new TextTable(Column.number("#"), Column.text(l.text("capability.column.lens"), 12), Column.text(l.text("capability.column.mount"), 5),
            Column.text(l.text("capability.column.type"), 4), Column.text(l.text("capability.column.focal"), 6), Column.text(l.text("capability.column.aperture"), 6),
            Column.text(l.text("capability.column.ois"), 3), Column.text(l.text("capability.column.autofocus"), 3), Column.text(l.text("capability.column.filter"), 5),
            Column.number(l.text("capability.column.quantity")));
        int i = 1;
        for (var entry : report.lenses()) {
            var lens = entry.lens();
            table.row(String.valueOf(i++), names.get(lens.id()), mount(entry.mount(), l), lensType(entry, l), focal(entry.focal()),
                CapabilityPresentation.aperture(entry, l), yesNo(lens.stabilization(), l), yesNo(lens.autofocus(), l), filterThread(lens, l), String.valueOf(lens.quantity()));
        }
        lines.addAll(table.render(width));
        return lines;
    }

    List<String> aperture() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.section.APERTURE"));
        if (report.lenses().isEmpty()) { say(lines, l.text("capability.cli.noLenses")); return lines; }
        lines.addAll(TextTable.wrap(l.text("capability.finding.WIDEST_APERTURE", fNumber(report.largestAperture().orElseThrow()),
            report.widestLenses().stream().map(e -> names.get(e.id())).collect(Collectors.joining(", "))), width));
        var table = new TextTable(Column.number("#"), Column.text(l.text("capability.column.lens"), 12), Column.text(l.text("capability.column.behaviour"), 10),
            Column.fixed(l.text("capability.column.aperture")));
        int i = 1;
        for (var entry : report.lenses())
            table.row(String.valueOf(i++), names.get(entry.lens().id()), apertureKind(entry.apertureKind(), l), CapabilityPresentation.aperture(entry, l));
        lines.addAll(table.render(width));
        lines.addAll(TextTable.wrap(l.text("capability.apertureNote"), width));
        return lines;
    }

    // ---- Focal coverage ----------------------------------------------------------------------------------------------

    /** Physical coverage of every lens, or (with a camera) the conditional 35 mm-equivalent coverage on that body. */
    List<String> coverage(Optional<CameraCapability> camera) {
        var lines = new ArrayList<String>();
        heading(lines, camera.map(c -> l.text("capability.chart.equivalentAxis", names.get(c.camera().id())))
            .orElse(l.text("capability.chart.physicalAxis")));
        if (report.lenses().isEmpty()) { say(lines, l.text("capability.cli.noLenses")); return lines; }
        var rows = new ArrayList<ChartRow>();
        for (var entry : report.lenses()) {
            String label = names.get(entry.lens().id());
            if (camera.isEmpty()) rows.add(new ChartRow(label, List.of(entry.focal()), List.of(), List.of(), ""));
            else {
                var pair = report.pair(camera.get().camera().id(), entry.lens().id()).orElseThrow();
                rows.add(pair.equivalentFocal().map(e -> new ChartRow(label, List.of(e), List.of(), List.of(), ""))
                    .orElse(new ChartRow(label, List.of(), List.of(), List.of(), equivalentText(pair, l))));
            }
        }
        List<Interval> combined = camera.map(CameraCapability::equivalentCoverage).orElse(report.coverage());
        var gaps = GearCapabilityAnalysis.gapsBetween(combined);
        rows.add(new ChartRow(l.text(camera.isPresent() ? "capability.chart.combinedEquivalent" : "capability.chart.combined"), List.of(), combined, gaps,
            combined.isEmpty() ? l.text("capability.none") : ""));
        lines.addAll(chart(rows));
        lines.add("");
        if (camera.isEmpty()) {
            lines.addAll(TextTable.wrap(l.text("capability.covered", intervals(report.coverage(), l)), width));
            keyValues(lines, Map.of(
                l.text("capability.cli.continuous"), intervals(report.continuous(), l),
                l.text("capability.cli.isolated"), intervals(report.isolated(), l),
                l.text("capability.cli.gaps"), report.gaps().isEmpty() ? l.text("capability.none")
                    : report.gaps().stream().map(g -> l.text("capability.cli.between", mm(g.startMm()), mm(g.endMm()))).collect(Collectors.joining("; ")),
                l.text("capability.cli.overlaps"), intervals(report.overlaps(), l)), List.of(l.text("capability.cli.continuous"), l.text("capability.cli.isolated"),
                    l.text("capability.cli.gaps"), l.text("capability.cli.overlaps")));
            lines.addAll(TextTable.wrap(l.text("capability.poolNote"), width));
        } else {
            var c = camera.get();
            keyValues(lines, Map.of(
                l.text("capability.cli.mountedPhysical"), intervals(c.mountedCoverage(), l),
                l.text("capability.cli.mountedEquivalent"), c.equivalentCoverage().isEmpty() ? l.text("capability.none")
                    : c.equivalentCoverage().stream().map(CapabilityPresentation::equivalent).collect(Collectors.joining("; ")),
                l.text("capability.column.crop"), crop(c.cropFactor(), l)),
                List.of(l.text("capability.cli.mountedPhysical"), l.text("capability.cli.mountedEquivalent"), l.text("capability.column.crop")));
            lines.addAll(TextTable.wrap(l.text("capability.equivalentNote"), width));
        }
        return lines;
    }

    /** One chart line: a lens's interval (a point for a prime), or the combined coverage with its gaps, or a reason. */
    private record ChartRow(String label, List<Interval> ranges, List<Interval> combined, List<Interval> gaps, String reason) { }

    private List<String> chart(List<ChartRow> rows) {
        var plotted = new ArrayList<Interval>();
        rows.forEach(row -> { plotted.addAll(row.ranges()); plotted.addAll(row.combined()); });
        var scale = FocalScale.of(plotted);
        int labelWidth = Math.min(rows.stream().mapToInt(r -> TextTable.width(r.label())).max().orElse(10), Math.max(12, width / 3));
        int plot = width - labelWidth - 2;
        var lines = new ArrayList<String>();
        if (scale.isEmpty() || plot < MIN_PLOT) { lines.addAll(TextTable.wrap(l.text("capability.cli.chartUnavailable"), width)); return lines; }
        var axis = scale.get();
        char[] labels = blank(plot), ruler = new char[plot];
        Arrays.fill(ruler, '-');
        int free = 0;
        for (double tick : axis.ticks(Math.max(2, plot / 6))) {
            int col = column(axis, tick, plot);
            ruler[col] = '+';
            String text = number(tick); int start = Math.max(0, Math.min(plot - text.length(), col - text.length() / 2));
            if (start < free) continue;
            text.getChars(0, text.length(), labels, start); free = start + text.length() + 1;
        }
        lines.add(" ".repeat(labelWidth + 2) + TextTable.strip(new String(labels)));
        lines.add(" ".repeat(labelWidth + 2) + new String(ruler));
        for (var row : rows) {
            char[] cells = blank(plot);
            for (var range : row.ranges()) draw(cells, axis, range, plot, '=', 'o');
            for (var gap : row.gaps()) {
                int from = column(axis, gap.startMm(), plot) + 1, to = column(axis, gap.endMm(), plot) - 1;
                for (int c = from; c <= to; c++) cells[c] = '.';
            }
            for (var range : row.combined()) {
                int from = column(axis, range.startMm(), plot), to = column(axis, range.endMm(), plot);
                for (int c = from; c <= to; c++) cells[c] = range.point() ? 'o' : '#';
            }
            // A reason (no plottable interval) wraps inside the plot area instead of a bar.
            var bar = row.reason().isEmpty() ? List.of(TextTable.strip(new String(cells))) : TextTable.wrap(row.reason(), plot);
            boolean own = TextTable.width(row.label()) > labelWidth;
            if (own) lines.addAll(TextTable.wrap(row.label(), width));
            for (int i = 0; i < bar.size(); i++)
                lines.add((i == 0 && !own ? TextTable.pad(row.label(), labelWidth, TextTable.Align.LEFT) : " ".repeat(labelWidth)) + "  " + bar.get(i));
        }
        lines.add("");
        lines.addAll(TextTable.wrap(l.text("capability.cli.chartLegend"), width));
        return lines;
    }
    private static int column(FocalScale axis, double mm, int plot) { return (int) Math.round(axis.fraction(mm) * (plot - 1)); }
    private static char[] blank(int size) { char[] cells = new char[size]; Arrays.fill(cells, ' '); return cells; }
    /** A zoom as {@code [===]} between its endpoint columns (at least one column), a prime as {@code o}. */
    private static void draw(char[] cells, FocalScale axis, Interval range, int plot, char fill, char point) {
        int from = column(axis, range.startMm(), plot), to = column(axis, range.endMm(), plot);
        if (range.point()) { cells[from] = point; return; }
        for (int c = from; c <= to; c++) cells[c] = fill;
        if (to - from >= 2) { cells[from] = '['; cells[to] = ']'; }
    }

    // ---- Compatibility -----------------------------------------------------------------------------------------------

    /** Lenses as rows, cameras as columns C1..Cn (in blocks that fit the width), or a single camera's column. */
    List<String> matrix(Optional<Integer> cameraIndex) {
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.section.COMPATIBILITY"));
        if (report.pairs().isEmpty()) { say(lines, l.text("capability.noPairs")); return lines; }
        var columns = new ArrayList<Integer>();
        if (cameraIndex.isPresent()) columns.add(cameraIndex.get()); else for (int i = 0; i < report.cameras().size(); i++) columns.add(i);
        var legend = new TextTable(Column.fixed(l.text("capability.cli.column")), Column.text(l.text("capability.column.camera"), 12), Column.text(l.text("capability.column.mount"), 6));
        for (int i : columns) {
            var camera = report.cameras().get(i);
            legend.row("C" + (i + 1), names.get(camera.camera().id()), mount(camera.mount(), l));
        }
        lines.addAll(legend.render(width)); lines.add("");
        int labelWidth = Math.min(28, Math.max(12, width / 3));
        int perBlock = Math.max(1, (width - labelWidth - 6) / 5);
        for (int start = 0; start < columns.size(); start += perBlock) {
            var block = columns.subList(start, Math.min(columns.size(), start + perBlock));
            if (columns.size() > perBlock) say(lines, l.text("capability.cli.matrixBlock", "C" + (block.getFirst() + 1), "C" + (block.getLast() + 1), columns.size()));
            var heads = new ArrayList<Column>(List.of(Column.number("#"), Column.text(l.text("capability.column.lens"), 10)));
            for (int i : block) heads.add(Column.fixed(" C" + (i + 1)));
            var table = new TextTable(heads.toArray(Column[]::new));
            int row = 1;
            for (var entry : report.lenses()) {
                var cells = new ArrayList<String>(List.of(String.valueOf(row++), names.get(entry.lens().id()) + " (" + mount(entry.mount(), l) + ")"));
                for (int i : block) cells.add("  " + symbol(report.pair(report.cameras().get(i).camera().id(), entry.lens().id()).orElseThrow().mount().relation()));
                table.row(cells.toArray(String[]::new));
            }
            lines.addAll(table.render(width)); lines.add("");
        }
        for (var relation : List.of(Relation.SAME_LABEL, Relation.EQUIVALENT_LABELS, Relation.UNKNOWN, Relation.DIFFERENT_SYSTEMS))
            lines.addAll(TextTable.hanging(symbol(relation) + " = " + l.text("capability.cli.legend." + relation.name()), width, 4));
        lines.addAll(TextTable.wrap(l.text("capability.matrixNote"), width));
        lines.addAll(TextTable.wrap(l.text("capability.cli.pairHint"), width));
        return lines;
    }

    /** Everything the GUI detail panel shows for one combination, in the same order. */
    List<String> pair(int lensIndex, int cameraIndex) {
        var lens = report.lenses().get(lensIndex); var camera = report.cameras().get(cameraIndex);
        var pair = report.pair(camera.camera().id(), lens.lens().id()).orElseThrow();
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.detail.title"));
        keyValues(lines, pairDetails(pair, lens, names, l));
        lines.add("");
        for (String note : pairNotes(l)) lines.addAll(TextTable.hanging("- " + note, width, 2));
        return lines;
    }

    // ---- Data quality ------------------------------------------------------------------------------------------------

    List<String> quality() {
        var lines = new ArrayList<String>();
        heading(lines, l.text("capability.section.QUALITY"));
        say(lines, l.text("capability.quality.summary", report.quality().size() - report.needsAttention().size(), report.quality().size()));
        if (report.equipment().isEmpty()) { say(lines, l.text("capability.empty")); return lines; }
        if (report.needsAttention().isEmpty()) say(lines, l.text("capability.quality.none"));
        // Items that need attention first, with every finding and the command that edits them ...
        for (var item : report.needsAttention()) {
            lines.add("");
            lines.addAll(TextTable.wrap(names.get(item.item().id()) + " (" + l.text("gear.type." + item.item().type().name()) + ")", width));
            for (var category : IssueCategory.values()) {
                var issues = item.of(category);
                if (issues.isEmpty()) continue;
                lines.addAll(TextTable.hanging("  " + category(category, l) + ": " + issues.stream().map(i -> issue(i, l)).collect(Collectors.joining("; ")), width, 4));
            }
            var fields = new LinkedHashSet<String>();
            item.issues().stream().filter(i -> i.category() != IssueCategory.OPTIONAL).forEach(i -> fields.addAll(EDIT_FIELDS.getOrDefault(i.code(), List.of())));
            if (!fields.isEmpty()) lines.addAll(TextTable.hanging("  " + l.text("capability.cli.review", "gear edit " + item.item().id() + " "
                + fields.stream().map(f -> f + "=...").collect(Collectors.joining(" "))), width, 4));
        }
        // ... then, one line each, items that only lack optional details (not errors).
        var optional = report.quality().stream().filter(q -> !q.needsAttention() && !q.issues().isEmpty()).toList();
        if (!optional.isEmpty()) {
            lines.add(""); say(lines, l.text("capability.quality.optionalTitle"));
            for (var item : optional) lines.addAll(TextTable.hanging("  " + names.get(item.item().id()) + ": "
                + item.issues().stream().map(i -> issue(i, l)).collect(Collectors.joining("; ")), width, 4));
        }
        if (!report.mountNaming().isEmpty()) {
            lines.add("");
            for (var naming : report.mountNaming()) lines.addAll(TextTable.hanging(namingText(naming, l), width, 2));
            lines.addAll(TextTable.wrap(l.text("capability.quality.namingAdvice"), width));
        }
        lines.add("");
        lines.addAll(TextTable.wrap(l.text("capability.quality.keyFields"), width));
        return lines;
    }

    List<String> technical() { return List.of(CapabilityPresentation.all(report, l)); }

    // ---- Layout helpers ----------------------------------------------------------------------------------------------

    private void heading(List<String> lines, String text) {
        lines.addAll(TextTable.wrap(text, width));
        lines.add("-".repeat(Math.min(width, Math.max(40, TextTable.width(text)))));
    }
    private static void subheading(List<String> lines, String text) { lines.add(text); }
    /** One message, wrapped to the width. */
    private void say(List<String> lines, String text) { lines.addAll(TextTable.wrap(text, width)); }
    private void keyValues(List<String> lines, Map<String, String> rows) { keyValues(lines, rows, new ArrayList<>(rows.keySet())); }
    private void keyValues(List<String> lines, Map<String, String> rows, List<String> order) {
        int labelWidth = Math.min(order.stream().mapToInt(TextTable::width).max().orElse(0), Math.max(12, width / 2 - 2));
        for (String key : order) {
            String value = rows.get(key);
            if (TextTable.width(key) > labelWidth) { lines.addAll(TextTable.wrap(key, width)); key = ""; }
            var parts = TextTable.wrap(value, width - labelWidth - 2);
            for (int i = 0; i < parts.size(); i++) lines.add(TextTable.strip(TextTable.pad(i == 0 ? key : "", labelWidth, TextTable.Align.LEFT) + "  " + parts.get(i)));
        }
    }
}
