package com.photopilot.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.ExifValues;
import com.photopilot.model.GearNames;
import com.photopilot.model.GearSnapshot;
import com.photopilot.model.ShootingWindow;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.PlanValidator;

/**
 * Stage 1 UML element 103 (zero-based): {F11; deterministic statistics and plan comparison}
 * Pure Java over extracted records and the saved plan; no AI, no I/O, no clock. Each statistic counts only the
 * photographs that recorded that field, unknown equipment is never counted as a known camera or lens, and plan
 * comparisons state numerical differences only. Equipment names match exactly over normalized tokens (GearNames), so
 * differently named variants are reported as unmatched instead of being merged.
 */
public class StatisticsCalculator {
    /** Plan setting name -> statistic field, in display order. */
    static final Map<String, String> SETTINGS = new LinkedHashMap<>();
    static {
        SETTINGS.put("focalLength", "focalLength"); SETTINGS.put("shutterSpeed", "shutter"); SETTINGS.put("aperture", "aperture");
        SETTINGS.put("iso", "iso"); SETTINGS.put("exposureCompensation", "exposureCompensation");
    }
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int SMALL_SAMPLE = 10;

    public StatisticsCalculator() {
    }

    /**
     * Histograms, missing/invalid counts and capture span of the records. A record without any valid camera, lens or
     * exposure field is imported but unusable: it counts as missing for every field. Fails when nothing is usable.
     */
    public Result<ExifStatistics> summarize(List<ExifRecord> records) {
        if (records == null || records.isEmpty()) return Result.failureKey("review.error.noRecords");
        var missing = new HashMap<String, Integer>(); var invalid = new HashMap<String, Integer>();
        var focal = new HashMap<Double, Integer>(); var shutter = new HashMap<Double, Integer>(); var aperture = new HashMap<Double, Integer>();
        var iso = new HashMap<Integer, Integer>(); var compensation = new HashMap<Double, Integer>();
        var cameras = new HashMap<String, Integer>(); var lenses = new HashMap<String, Integer>();
        var offsets = new HashSet<ZoneOffset>();
        int usable = 0, timed = 0, zoned = 0;
        LocalDateTime first = null, last = null;
        for (ExifRecord record : records) {
            if (record == null) continue;
            var normalized = record.normalized();
            normalized.invalidFields().forEach(field -> invalid.merge(field, 1, Integer::sum));
            if (!normalized.validate().isSuccess()) { ExifRecord.FIELDS.forEach(field -> missing.merge(field, 1, Integer::sum)); continue; }
            usable++;
            normalized.missingFields().forEach(field -> missing.merge(field, 1, Integer::sum));
            normalized.cameraModel().ifPresent(v -> cameras.merge(v, 1, Integer::sum));
            normalized.lensModel().ifPresent(v -> lenses.merge(v, 1, Integer::sum));
            normalized.focalLengthMm().ifPresent(v -> focal.merge(v, 1, Integer::sum));
            normalized.shutterSeconds().ifPresent(v -> shutter.merge(v, 1, Integer::sum));
            normalized.aperture().ifPresent(v -> aperture.merge(v, 1, Integer::sum));
            normalized.iso().ifPresent(v -> iso.merge(v, 1, Integer::sum));
            normalized.exposureCompensation().ifPresent(v -> compensation.merge(v, 1, Integer::sum));
            if (normalized.localCaptureTime().isPresent()) {
                var time = normalized.localCaptureTime().get();
                timed++;
                if (first == null || time.isBefore(first)) first = time;
                if (last == null || time.isAfter(last)) last = time;
                if (normalized.captureOffset().isPresent()) { zoned++; offsets.add(normalized.captureOffset().get()); }
            }
        }
        int imported = (int) records.stream().filter(Objects::nonNull).count();
        if (usable == 0) return Result.failureKey("review.error.noUsable");
        var offset = zoned == timed && offsets.size() == 1 ? Optional.of(offsets.iterator().next().getId()) : Optional.<String>empty();
        var draft = new ExifStatistics(imported, usable, missing, focal, shutter, aperture, iso, cameras, lenses, List.of(), invalid, compensation,
            0, timed, zoned, Optional.ofNullable(first).map(LocalDateTime::toString), Optional.ofNullable(last).map(LocalDateTime::toString),
            offset, Optional.empty(), "");
        var limits = limitations(draft).stream().map(StatisticsCalculator::english).toList();
        var stats = new ExifStatistics(imported, usable, missing, focal, shutter, aperture, iso, cameras, lenses, limits, invalid, compensation,
            0, timed, zoned, draft.firstCapture(), draft.lastCapture(), offset, Optional.empty(), "");
        return stats.validate();
    }

    /** Evidence limits that follow from the counts themselves; shown with the statistics and given to the review. */
    public List<LocalizedMessage> limitations(ExifStatistics stats) {
        var lines = new ArrayList<LocalizedMessage>();
        if (stats.failedFileCount() > 0) lines.add(LocalizedMessage.of("review.limit.failedFiles", stats.failedFileCount()));
        if (stats.importedCount() > stats.usableCount()) lines.add(LocalizedMessage.of("review.limit.unusable", stats.importedCount() - stats.usableCount(), stats.importedCount()));
        for (String field : ExifRecord.FIELDS) {
            int missing = stats.missing(field), invalid = stats.invalid(field);
            if (missing > 0) lines.add(LocalizedMessage.of("review.limit.missing", label(field), missing, stats.importedCount()));
            if (invalid > 0) lines.add(LocalizedMessage.of("review.limit.invalid", label(field), invalid));
        }
        if (stats.timedCount() > stats.zonedCount()) lines.add(LocalizedMessage.of("review.limit.unzoned", stats.timedCount() - stats.zonedCount()));
        if (stats.usableCount() > 0 && stats.usableCount() < SMALL_SAMPLE) lines.add(LocalizedMessage.of("review.limit.smallSample", stats.usableCount()));
        lines.add(LocalizedMessage.of("review.limit.exifScope"));
        return lines;
    }

    /** Stage 1 contract: the plan-versus-actual differences as English text (persisted with a saved review). */
    public List<String> compare(ExifStatistics stats, ShootPlan plan) {
        return differences(stats, plan).stream().map(StatisticsCalculator::english).toList();
    }

    /** Evidence-based plan-versus-actual differences: equipment, lens coverage, planned settings and capture span. */
    public List<LocalizedMessage> differences(ExifStatistics stats, ShootPlan plan) {
        Objects.requireNonNull(stats, "stats"); Objects.requireNonNull(plan, "plan");
        var lines = new ArrayList<LocalizedMessage>();
        var gear = plan.loadout().map(l -> l.gear()).orElse(List.of());
        if (plan.loadout().isEmpty()) lines.add(LocalizedMessage.of("review.compare.noLoadout"));
        else {
            equipment(stats, gear, true, lines);
            equipment(stats, gear, false, lines);
            coverage(stats, gear, lines);
        }
        var names = new HashMap<UUID, String>();
        gear.forEach(g -> names.put(g.sourceGearId(), CompatibilityValidator.name(g)));
        SETTINGS.forEach((setting, field) -> setting(stats, plan, setting, field, names, lines));
        timing(stats, plan, lines);
        lines.add(LocalizedMessage.of("review.compare.scope"));
        return lines;
    }

    private static void equipment(ExifStatistics stats, List<GearSnapshot> gear, boolean camera, List<LocalizedMessage> lines) {
        String field = camera ? "camera" : "lens";
        var recorded = camera ? stats.cameraCounts() : stats.lensCounts();
        int withData = stats.count(field);
        if (withData == 0) { lines.add(LocalizedMessage.of(camera ? "review.compare.cameraNoData" : "review.compare.lensNoData", stats.importedCount())); return; }
        var matched = new HashSet<String>();
        for (GearSnapshot item : gear.stream().filter(g -> g.type().equals(camera ? "CAMERA" : "LENS")).toList()) {
            String planned = CompatibilityValidator.name(item);
            var hits = recorded.keySet().stream().filter(name -> GearNames.sameModel(item.brand(), planned, name)).sorted().toList();
            int photos = hits.stream().mapToInt(recorded::get).sum();
            matched.addAll(hits);
            if (photos > 0) lines.add(LocalizedMessage.of(camera ? "review.compare.cameraUsed" : "review.compare.lensUsed",
                planned, photos, withData, ExifValues.percent(photos, withData), String.join(", ", hits)));
            else lines.add(LocalizedMessage.of(camera ? "review.compare.cameraUnmatched" : "review.compare.lensUnmatched", planned));
        }
        new TreeMap<>(recorded).forEach((name, photos) -> {
            if (!matched.contains(name)) lines.add(LocalizedMessage.of(camera ? "review.compare.cameraExtra" : "review.compare.lensExtra",
                name, photos, withData, ExifValues.percent(photos, withData)));
        });
        if (stats.missing(field) > 0) lines.add(LocalizedMessage.of(camera ? "review.compare.cameraMissing" : "review.compare.lensMissing",
            stats.missing(field), stats.importedCount()));
    }

    /** Photographs whose recorded focal length lies outside every planned lens's specified range. */
    private static void coverage(ExifStatistics stats, List<GearSnapshot> gear, List<LocalizedMessage> lines) {
        var ranges = new ArrayList<double[]>();
        for (GearSnapshot lens : gear.stream().filter(g -> g.type().equals("LENS")).toList()) {
            var min = number(lens, "minFocalMm"); var max = number(lens, "maxFocalMm");
            if (min.isPresent() && max.isPresent() && min.get() <= max.get()) ranges.add(new double[]{min.get(), max.get()});
        }
        int n = stats.count("focalLength");
        if (ranges.isEmpty() || n == 0) return;
        int outside = 0;
        for (var entry : stats.histogram("focalLength").entrySet()) {
            double focal = entry.getKey();
            if (ranges.stream().noneMatch(r -> focal >= r[0] - 0.5 && focal <= r[1] + 0.5)) outside += entry.getValue();
        }
        String covered = String.join(", ", ranges.stream().map(CompatibilityValidator::range).toList());
        lines.add(LocalizedMessage.of("review.compare.coverage", outside, n, ExifValues.percent(outside, n), covered));
    }

    private static void setting(ExifStatistics stats, ShootPlan plan, String setting, String field, Map<UUID, String> names, List<LocalizedMessage> lines) {
        var keys = plan.settings().keySet().stream().filter(key -> ShootPlan.settingName(key).equals(setting)).sorted().toList();
        int n = stats.count(field);
        if (keys.isEmpty()) {
            if (n > 0 && !field.equals("exposureCompensation")) lines.add(LocalizedMessage.of("review.compare.notPlanned", label(field), range(stats, field)));
            return;
        }
        for (String key : keys) {
            String value = plan.settings().get(key);
            var numbers = PlanValidator.settingNumbers(setting, value);
            if (numbers.isEmpty() || numbers.size() > 2 || numbers.stream().anyMatch(v -> !Double.isFinite(v))) {
                lines.add(LocalizedMessage.of("review.compare.unparsed", label(field), value)); continue;
            }
            if (n == 0) { lines.add(LocalizedMessage.of("review.compare.noData", label(field), value, stats.importedCount())); continue; }
            double low = Collections.min(numbers), high = Collections.max(numbers);
            int inside = 0, below = 0, above = 0;
            for (var entry : stats.histogram(field).entrySet()) {
                double v = entry.getKey();
                if (v < low - tolerance(low)) below += entry.getValue();
                else if (v > high + tolerance(high)) above += entry.getValue();
                else inside += entry.getValue();
            }
            String key2 = switch (field) {
                case "focalLength" -> "review.compare.range.focalLength";
                case "shutter" -> "review.compare.range.shutter";
                case "aperture" -> "review.compare.range.aperture";
                case "iso" -> "review.compare.range.iso";
                default -> "review.compare.range.compensation";
            };
            lines.add(LocalizedMessage.of(key2, value, inside, n, ExifValues.percent(inside, n), below, above, range(stats, field)));
            ShootPlan.settingCamera(key).ifPresent(camera -> lines.add(LocalizedMessage.of("review.compare.cameraBound", label(field), names.getOrDefault(camera, "?"))));
        }
    }

    /** Capture span against the planned windows, only when every timestamp recorded the same UTC offset. */
    private static void timing(ExifStatistics stats, ShootPlan plan, List<LocalizedMessage> lines) {
        if (plan.windows().isEmpty()) return;
        if (stats.timedCount() == 0 || stats.firstCapture().isEmpty()) { lines.add(LocalizedMessage.of("review.compare.noTimes")); return; }
        if (stats.captureOffset().isEmpty()) { lines.add(LocalizedMessage.of("review.compare.timesUnzoned", stats.timedCount(), stats.zonedCount())); return; }
        var offset = ZoneOffset.of(stats.captureOffset().get());
        var first = LocalDateTime.parse(stats.firstCapture().get()).atOffset(offset).toInstant();
        var last = LocalDateTime.parse(stats.lastCapture().orElseThrow()).atOffset(offset).toInstant();
        ZonedDateTime start = plan.windows().stream().map(ShootingWindow::start).min(Comparator.naturalOrder()).orElseThrow();
        ZonedDateTime end = plan.windows().stream().map(ShootingWindow::end).max(Comparator.naturalOrder()).orElseThrow();
        String relation = !first.isBefore(start.toInstant()) && !last.isAfter(end.toInstant()) ? "review.compare.timing.within"
            : last.isBefore(start.toInstant()) || first.isAfter(end.toInstant()) ? "review.compare.timing.outside" : "review.compare.timing.overlap";
        var zone = start.getZone();
        lines.add(LocalizedMessage.of("review.compare.timing", MINUTE.format(first.atZone(zone)), MINUTE.format(last.atZone(zone)), zone.getId(),
            stats.zonedCount(), plan.windows().size(), MINUTE.format(start), MINUTE.format(end.withZoneSameInstant(zone)), LocalizedMessage.of(relation)));
    }

    /** Localized field name as a nested message argument (rendered by the presentation). */
    public static LocalizedMessage label(String field) {
        return LocalizedMessage.of(switch (field) {
            case "camera" -> "review.field.camera";
            case "lens" -> "review.field.lens";
            case "focalLength" -> "review.field.focalLength";
            case "shutter" -> "review.field.shutter";
            case "aperture" -> "review.field.aperture";
            case "iso" -> "review.field.iso";
            case "exposureCompensation" -> "review.field.exposureCompensation";
            default -> "review.field.captureTime";
        });
    }
    /** Recorded minimum-maximum of a numeric field, e.g. "1/4000 s - 1/250 s". */
    static String range(ExifStatistics stats, String field) {
        return stats.summary(field).map(s -> s.min() == s.max() ? ExifValues.format(field, s.min())
            : ExifValues.format(field, s.min()) + " - " + ExifValues.format(field, s.max())).orElse("-");
    }
    /** English text of a message whose arguments may themselves be messages (field names, relations). */
    public static String english(LocalizedMessage message) {
        return EnglishMessages.text(message.key(), message.arguments().stream()
            .map(argument -> argument instanceof LocalizedMessage nested ? english(nested) : argument).toArray());
    }
    private static double tolerance(double value) { return 1e-6 * Math.max(1, Math.abs(value)); }
    private static Optional<Double> number(GearSnapshot item, String spec) {
        try {
            double value = Double.parseDouble(Objects.toString(item.specs().get(spec), "").strip());
            return Double.isFinite(value) && value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException missing) { return Optional.empty(); }
    }
}
