package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared localized formatting of the inventory capability report, used by the GUI dashboard and the CLI alike. All
 * observations and calculations come from GearCapabilityService; this class only turns them into text (photographic
 * units, f-numbers, Yes / No / Unknown, localized findings) and assembles the complete technical report.
 */
public final class CapabilityPresentation {
    private CapabilityPresentation() { }
    /** Sections of the complete technical report ({@link #all}). */
    public enum Section { OVERVIEW, COVERAGE, APERTURE, COMPATIBILITY, QUALITY, SUMMARY, ASSUMPTIONS }
    /** Camera fields that describe another equipment type; shown only when that type is shown. */
    private static final Map<String, EquipmentType> FIELD_TYPES = Map.of("batteryTypes", EquipmentType.BATTERY, "storageFormats", EquipmentType.STORAGEMEDIA);
    /** Report order of the dashboard and CLI summary metrics. */
    public static final List<EquipmentType> REPORT_TYPES = List.of(EquipmentType.CAMERA, EquipmentType.LENS, EquipmentType.FILTER);

    public static String format(GearCapabilityAnalysis report, Section section, LocalizationService l) {
        return format(report, section, l, EnumSet.allOf(EquipmentType.class));
    }
    /** As {@link #format(GearCapabilityAnalysis, Section, LocalizationService)}, with the overview limited to {@code types}. */
    public static String format(GearCapabilityAnalysis report, Section section, LocalizationService l, Set<EquipmentType> types) {
        var lines = new ArrayList<String>();
        var names = names(report.equipment());
        switch (section) {
            case OVERVIEW -> {
                var equipment = report.equipment().stream().filter(e -> types.contains(e.type())).toList();
                lines.add(l.text("capability.count", equipment.size()));
                for (var type : EquipmentType.values()) if (types.contains(type))
                    lines.add(l.text("capability.quantity", l.text("gear.type." + type.name()), l.text("capability.unitsRecords", report.units(type), report.records(type))));
                if (equipment.isEmpty()) lines.add(l.text("capability.empty"));
                for (var e : equipment) {
                    lines.add(names.get(e.id()) + " [" + e.id() + "]");
                    lines.add("  " + l.text("capability.item", l.text("gear.type." + e.type().name()), e.quantity()));
                    for (var field : e.type().fields()) if (types.contains(FIELD_TYPES.getOrDefault(field.key(), e.type())))
                        lines.add("  " + l.text("gear.field." + field.key()) + ": " + field(field, e.specifications().get(field.key()), l));
                    lines.add("  " + l.text("gear.field.weightGrams") + ": " + e.weightGrams().map(w -> number(w) + " g").orElse(l.text("capability.value.notRecorded")));
                }
                lines.add(l.text("capability.evidence"));
            }
            case COVERAGE -> {
                lines.add(l.text("capability.actual"));
                lines.add(l.text("capability.covered", intervals(report.coverage(), l)));
                report.focalSpan().ifPresent(span -> lines.add(l.text("capability.extent", focal(span))));
                lines.add(l.text("capability.overlaps", intervals(report.overlaps(), l)));
                lines.add(l.text("capability.gaps", report.gaps().isEmpty() ? l.text("capability.none") : report.gaps().stream()
                    .map(g -> "(" + number(g.startMm()) + ", " + number(g.endMm()) + ") mm").collect(Collectors.joining("; "))));
                lines.add(l.text("capability.gapNote"));
                for (var lens : report.lenses()) lines.add(names.get(lens.lens().id()) + ": " + focal(lens.focal()) + " (" + lensType(lens, l) + ")");
                lines.add(l.text("capability.poolNote"));
                for (var camera : report.cameras())
                    lines.add(l.text("capability.cameraCoverage", names.get(camera.camera().id()), intervals(camera.mountedCoverage(), l),
                        camera.equivalentCoverage().isEmpty() ? l.text("capability.none") : camera.equivalentCoverage().stream().map(CapabilityPresentation::equivalent).collect(Collectors.joining("; "))));
            }
            case APERTURE -> {
                lines.add(l.text("capability.largest", report.largestAperture().map(CapabilityPresentation::fNumber).orElse(l.text("capability.unknown"))));
                if (!report.widestLenses().isEmpty()) lines.add(l.text("capability.widestLenses", join(report.widestLenses(), names)));
                for (var entry : report.lenses()) {
                    var lens = entry.lens();
                    lines.add(names.get(lens.id()) + ": " + l.text("capability.aperture." + entry.apertureKind().name(),
                        number(lens.maxAperture()), lens.maxApertureAtMaxFocal().map(CapabilityPresentation::number).orElse(l.text("capability.unknown"))));
                }
                lines.add(l.text("capability.apertureNote"));
            }
            case COMPATIBILITY -> {
                if (report.pairs().isEmpty()) lines.add(l.text("capability.noPairs"));
                for (var pair : report.pairs()) {
                    lines.add(names.get(pair.camera().id()) + " + " + names.get(pair.lens().id()) + ": " + relation(pair.mount().relation(), l));
                    lines.add("  " + relationDetail(pair.mount(), l));
                    lines.add("  " + l.text("capability.detail.overall") + ": " + assessment(pair.mount().status(), l));
                    lines.add("  " + l.text("capability.equivalent", equivalentText(pair, l)));
                }
                lines.add(l.text("capability.compatibilityNote"));
                lines.add(l.text("capability.equivalentNote"));
            }
            case QUALITY -> {
                lines.add(l.text("capability.quality.summary", report.quality().size() - report.needsAttention().size(), report.quality().size()));
                if (report.needsAttention().isEmpty()) lines.add(l.text("capability.quality.none"));
                for (var item : report.quality()) {
                    if (item.issues().isEmpty()) continue;
                    lines.add(names.get(item.item().id()) + " (" + l.text("gear.type." + item.item().type().name()) + ")");
                    for (var category : IssueCategory.values()) {
                        var issues = item.of(category);
                        if (!issues.isEmpty()) lines.add("  " + category(category, l) + ": " + issues.stream().map(i -> issue(i, l)).collect(Collectors.joining("; ")));
                    }
                }
                for (var naming : report.mountNaming()) lines.add(namingText(naming, l));
                lines.add(l.text("capability.quality.keyFields"));
            }
            case SUMMARY -> {
                lines.add(l.text("capability.summaryCount", report.units(EquipmentType.CAMERA), report.units(EquipmentType.LENS)));
                lines.add(l.text("capability.covered", intervals(report.coverage(), l)));
                lines.add(l.text("capability.summaryIntervals", report.coverage().size(), report.overlaps().size(), report.gaps().size()));
                lines.add(l.text("capability.largest", report.largestAperture().map(CapabilityPresentation::fNumber).orElse(l.text("capability.unknown"))));
                for (var finding : report.findings()) lines.add("[" + level(finding.level(), l) + "] " + finding(finding, report, l));
                lines.add(l.text("capability.compatibilityNote"));
                lines.add(l.text("capability.scope"));
            }
            case ASSUMPTIONS -> assumptions(l).forEach(line -> lines.add("- " + line));
        }
        return String.join("\n", lines);
    }
    /** The complete technical report: every section, identifiers included (advanced view and {@code capability all}). */
    public static String all(GearCapabilityAnalysis report, LocalizationService l) { return all(report, l, EnumSet.allOf(EquipmentType.class)); }
    public static String all(GearCapabilityAnalysis report, LocalizationService l, Set<EquipmentType> types) {
        return Arrays.stream(Section.values()).map(s -> l.text("capability.section." + s.name()) + "\n" + format(report, s, l, types)).collect(Collectors.joining("\n\n"));
    }

    /** Analysis assumptions and limitations, shared by the technical views of both interfaces. */
    public static List<String> assumptions(LocalizationService l) {
        return List.of(l.text("capability.assumption.recorded"), l.text("capability.assumption.coverage"), l.text("capability.assumption.scale"),
            l.text("capability.assumption.aperture"), l.text("capability.assumption.mount"), l.text("capability.assumption.equivalent"),
            l.text("capability.assumption.quality"), l.text("capability.assumption.readOnly"));
    }

    // ---- Names ----------------------------------------------------------------------------------------------------

    public static String name(Equipment e) { return e.brand() + " " + e.model(); }
    /** Display names in report order; a repeated brand and model gets "#2", "#3" so duplicate records stay distinguishable. */
    public static Map<UUID, String> names(List<? extends Equipment> items) {
        var counts = new HashMap<String, Integer>(); var seen = new HashMap<String, Integer>();
        items.forEach(e -> counts.merge(name(e), 1, Integer::sum));
        var result = new LinkedHashMap<UUID, String>();
        for (var e : items) {
            String base = name(e); int index = seen.merge(base, 1, Integer::sum);
            result.put(e.id(), counts.get(base) > 1 && index > 1 ? base + " #" + index : base);
        }
        return result;
    }
    private static String join(List<? extends Equipment> items, Map<UUID, String> names) {
        return items.stream().map(e -> names.getOrDefault(e.id(), name(e))).collect(Collectors.joining(", "));
    }

    // ---- Numbers and units ----------------------------------------------------------------------------------------

    /** Recorded value without trailing zeros (33.0 → 33, 35.90 → 35.9); never rounded. */
    public static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    /** Calculated value rounded half-up to {@code decimals}, trailing zeros removed. */
    public static String rounded(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    public static String mm(double value) { return number(value) + " mm"; }
    public static String focal(Interval r) { return number(r.startMm()) + (r.point() ? "" : "–" + number(r.endMm())) + " mm"; }
    /** A 35 mm-equivalent interval at photographic precision (0.1 mm at most). */
    public static String equivalent(Interval r) {
        String start = rounded(r.startMm(), 1), end = rounded(r.endMm(), 1);
        return start + (r.point() || start.equals(end) ? "" : "–" + end) + " mm";
    }
    public static String intervals(List<Interval> ranges, LocalizationService l) {
        return ranges.isEmpty() ? l.text("capability.none") : ranges.stream().map(CapabilityPresentation::focal).collect(Collectors.joining("; "));
    }
    public static String fNumber(double value) { return "f/" + number(value); }
    /** Prime or constant zoom "f/2.8"; variable zoom "f/4.5–6.3"; zoom without a long-end value "f/4.5 (long end unknown)". */
    public static String aperture(LensCapability lens, LocalizationService l) {
        double wide = lens.lens().maxAperture();
        return switch (lens.apertureKind()) {
            case PRIME, CONSTANT -> fNumber(wide);
            case VARIABLE_ENDPOINTS -> "f/" + number(wide) + "–" + number(lens.lens().maxApertureAtMaxFocal().orElseThrow());
            case UNKNOWN_ZOOM -> l.text("capability.value.longEndUnknown", fNumber(wide));
        };
    }
    /**
     * Display length (0–1) of an aperture comparison bar: stops brighter than f/16, {@code 2·log2(16 / N)}, over the
     * eight stops from f/16 to f/1, clamped. A visual aid for recorded maximum apertures only; it is not a rating.
     */
    public static double apertureScale(double fNumber) {
        return Math.max(0.04, Math.min(1, 2 * Math.log(16 / fNumber) / Math.log(2) / 8));
    }
    public static String megapixels(Optional<Double> value, LocalizationService l) { return value.map(v -> number(v) + " MP").orElse(notRecorded(l)); }
    public static String fps(Optional<Double> value, LocalizationService l) { return value.map(v -> number(v) + " fps").orElse(notRecorded(l)); }
    public static String sensor(Camera camera, LocalizationService l) {
        if (camera.sensorWidthMm().isPresent() && camera.sensorHeightMm().isPresent())
            return number(camera.sensorWidthMm().get()) + " × " + number(camera.sensorHeightMm().get()) + " mm";
        if (camera.sensorWidthMm().isEmpty() && camera.sensorHeightMm().isEmpty()) return notRecorded(l);
        return l.text("capability.value.sensorPartial", camera.sensorWidthMm().map(CapabilityPresentation::mm).orElse("?"),
            camera.sensorHeightMm().map(CapabilityPresentation::mm).orElse("?"));
    }
    /** Diagonal crop factor against 36 × 24 mm, one decimal ("1.5×"). */
    public static String crop(Optional<Double> value, LocalizationService l) {
        return value.map(v -> BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString() + "×").orElse(l.text("capability.value.unknown"));
    }
    public static String sensorFormat(CameraCapability camera, LocalizationService l) {
        return camera.format().map(f -> l.text("capability.format." + f.name())).orElse(l.text("capability.value.unknown"));
    }
    public static String yesNo(Optional<Boolean> value, LocalizationService l) {
        return value.map(v -> l.text(v ? "capability.value.yes" : "capability.value.no")).orElse(l.text("capability.value.unknown"));
    }
    public static String list(Set<String> values, LocalizationService l) {
        return values.isEmpty() ? notRecorded(l) : String.join(", ", new TreeSet<>(values));
    }
    public static String filterThread(Lens lens, LocalizationService l) { return lens.filterDiameterMm().map(CapabilityPresentation::mm).orElse(notRecorded(l)); }
    public static String notRecorded(LocalizationService l) { return l.text("capability.value.notRecorded"); }
    /** The mount as recorded (never rewritten), or "Not recorded" for a blank or placeholder value. */
    public static String mount(MountReading reading, LocalizationService l) { return reading.known() ? reading.recorded().strip() : notRecorded(l); }
    public static String system(MountReading reading, LocalizationService l) {
        return !reading.known() ? notRecorded(l) : reading.system().map(MountSystem::displayName).orElse(l.text("capability.value.notRecognized"));
    }
    public static String lensType(LensCapability lens, LocalizationService l) { return l.text(lens.prime() ? "capability.lensType.PRIME" : "capability.lensType.ZOOM"); }
    public static String apertureKind(ApertureKind kind, LocalizationService l) { return l.text("capability.apertureKind." + kind.name()); }

    /** A recorded specification field with its unit; missing values say "Not recorded". */
    private static String field(EquipmentType.Field field, String value, LocalizationService l) {
        if (value == null || value.isBlank()) return notRecorded(l);
        if (field.kind().equals("boolean")) return yesNo(Optional.of(Boolean.parseBoolean(value)), l);
        if (!field.kind().equals("positive") && !field.kind().equals("nonnegative")) return value;
        double n;
        try { n = Double.parseDouble(value); } catch (NumberFormatException e) { return value; }
        return switch (field.key()) {
            case "maxAperture", "maxApertureAtMaxFocal" -> fNumber(n);
            case "resolutionMP" -> number(n) + " MP";
            case "burstFps" -> number(n) + " fps";
            case "capacityGB" -> number(n) + " GB";
            case "writeSpeedMBps" -> number(n) + " MB/s";
            case "capacityMah" -> number(n) + " mAh";
            default -> field.key().endsWith("Mm") ? mm(n) : number(n);
        };
    }

    // ---- Compatibility ---------------------------------------------------------------------------------------------

    /** One-character matrix symbol, plain ASCII so redirected and Windows console output stay readable. */
    public static String symbol(Relation relation) {
        return switch (relation) {
            case SAME_LABEL -> "M";
            case EQUIVALENT_LABELS -> "A";
            case DIFFERENT_SYSTEMS -> "X";
            default -> "?";
        };
    }
    public static String relation(Relation relation, LocalizationService l) { return l.text("capability.relation." + relation.name()); }
    public static String relationDetail(MountComparison mount, LocalizationService l) {
        String camera = mount(mount.camera(), l), lens = mount(mount.lens(), l);
        return switch (mount.relation()) {
            case SAME_LABEL -> l.text("capability.relation.detail.SAME_LABEL", lens);
            case EQUIVALENT_LABELS -> mount.camera().system().isPresent() && mount.lens().system().isPresent()
                ? l.text("capability.relation.detail.EQUIVALENT_SYSTEM", camera, lens, mount.camera().system().get().displayName())
                : l.text("capability.relation.detail.EQUIVALENT_VARIANT", camera, lens);
            case POSSIBLE_ALIAS -> l.text("capability.relation.detail.POSSIBLE_ALIAS", camera, lens,
                mount.camera().system().or(() -> mount.lens().system()).map(MountSystem::displayName).orElse("?"));
            case DIFFERENT_LABELS -> l.text("capability.relation.detail.DIFFERENT_LABELS", camera, lens);
            case DIFFERENT_SYSTEMS -> l.text("capability.relation.detail.DIFFERENT_SYSTEMS", system(mount.camera(), l), system(mount.lens(), l));
            case UNKNOWN -> l.text("capability.relation.detail.UNKNOWN", camera, lens);
        };
    }
    /**
     * The rows of one camera–lens assessment, label to value in display order: the GUI detail panel and
     * {@code capability pair} both show exactly these. Capabilities the inventory does not record (image circle,
     * electronic communication, adapters) are stated as not recorded, never inferred.
     */
    public static LinkedHashMap<String, String> pairDetails(CameraLensPair pair, LensCapability lens, Map<UUID, String> names, LocalizationService l) {
        var rows = new LinkedHashMap<String, String>();
        var mount = pair.mount();
        rows.put(l.text("capability.detail.camera"), names.getOrDefault(pair.camera().id(), name(pair.camera())));
        rows.put(l.text("capability.detail.lens"), names.getOrDefault(pair.lens().id(), name(pair.lens())));
        rows.put(l.text("capability.detail.cameraMount"), mount(mount.camera(), l));
        rows.put(l.text("capability.detail.lensMount"), mount(mount.lens(), l));
        rows.put(l.text("capability.detail.identified"), system(mount.camera(), l) + " / " + system(mount.lens(), l));
        rows.put(l.text("capability.detail.mountCheck"), relation(mount.relation(), l));
        rows.put(l.text("capability.detail.reason"), relationDetail(mount, l));
        rows.put(l.text("capability.detail.overall"), status(mount.status(), l) + " — " + assessment(mount.status(), l));
        rows.put(l.text("capability.detail.imageCircle"), l.text("capability.value.notInInventory"));
        rows.put(l.text("capability.detail.electronic"), l.text("capability.value.notInInventory"));
        rows.put(l.text("capability.detail.autofocus"), l.text("capability.value.autofocusPair", yesNo(pair.lens().autofocus(), l)));
        rows.put(l.text("capability.detail.stabilization"), l.text("capability.value.stabilizationPair", yesNo(pair.camera().stabilization(), l), yesNo(pair.lens().stabilization(), l)));
        rows.put(l.text("capability.detail.adapter"), l.text("capability.value.noAdapter"));
        rows.put(l.text("capability.detail.physical"), focal(lens.focal()));
        rows.put(l.text("capability.detail.equivalent"), equivalentText(pair, l));
        rows.put(l.text("capability.detail.aperture"), aperture(lens, l));
        return rows;
    }
    /** Limitations shown with every combination detail. */
    public static List<String> pairNotes(LocalizationService l) {
        return List.of(l.text("capability.matrixNote"), l.text("capability.equivalentNote"));
    }
    public static String status(PairStatus status, LocalizationService l) { return l.text("capability.pairStatus." + status.name()); }
    public static String assessment(PairStatus status, LocalizationService l) { return l.text("capability.assessment." + status.name()); }
    public static String equivalentText(CameraLensPair pair, LocalizationService l) {
        return switch (pair.equivalentStatus()) {
            case CALCULATED -> l.text("capability.value.conditional", equivalent(pair.equivalentFocal().orElseThrow()));
            case MOUNT_NOT_ESTABLISHED -> l.text("capability.value.equivalentNoMount");
            case SENSOR_UNKNOWN -> l.text("capability.value.equivalentNoSensor");
        };
    }

    // ---- Findings and data quality ---------------------------------------------------------------------------------

    public static String level(Level level, LocalizationService l) { return l.text("capability.level." + level.name()); }
    public static String finding(Finding finding, GearCapabilityAnalysis report, LocalizationService l) {
        var names = names(report.equipment());
        String key = "capability.finding." + finding.kind().name();
        return switch (finding.kind()) {
            case CONTINUOUS_COVERAGE -> l.text(key, focal(finding.intervals().getFirst()));
            case ISOLATED_FOCAL -> l.text(key, focal(finding.intervals().getFirst()), join(finding.items(), names));
            case COVERAGE_GAP -> l.text(key, mm(finding.intervals().getFirst().startMm()), mm(finding.intervals().getFirst().endMm()));
            case WIDEST_APERTURE -> l.text(key, fNumber(finding.value().orElseThrow()), join(finding.items(), names));
            case FAST_LENSES -> l.text(key, fNumber(finding.value().orElseThrow()), String.valueOf(finding.count()));
            case ALL_MOUNTS_MATCH, MOUNT_CONFLICTS, MOUNT_NOT_ESTABLISHED -> l.text(key, String.valueOf(finding.count()));
            case LENS_WITHOUT_BODY, BODY_WITHOUT_LENS, INCOMPLETE_CAMERAS, INCOMPLETE_LENSES -> l.text(key, join(finding.items(), names));
            case MOUNT_NAMING -> l.text(key, report.mountNaming().stream().map(n -> n.mount() + " (" + String.join(" / ", n.labels().keySet()) + ")").collect(Collectors.joining("; ")));
            case NO_EQUIPMENT, NO_LENSES, NO_CAMERAS, NO_FILTERS -> l.text(key);
        };
    }
    public static String category(IssueCategory category, LocalizationService l) { return l.text("capability.quality.category." + category.name()); }
    public static String issue(Issue issue, LocalizationService l) { return l.text("capability.issue." + issue.code(), issue.arguments().toArray()); }
    public static String namingText(MountNaming naming, LocalizationService l) {
        var names = new ArrayList<String>();
        naming.labels().forEach((label, items) -> names.add("\"" + label + "\" × " + items.size()));
        return l.text("capability.quality.naming", naming.mount(), String.join(", ", names));
    }
}
