package com.photopilot.review;

import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.service.StatisticsCalculator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic UC12 statistics and plan comparison over hand-computed records (no I/O, no AI). */
class StatisticsCalculatorTest {
    final StatisticsCalculator calculator = new StatisticsCalculator();

    static ExifRecord record(String camera, String lens, Double focal, Double shutter, Double aperture, Integer iso, Double ev, String time, ZoneOffset offset) {
        return new ExifRecord("p.jpg", Optional.ofNullable(focal), Optional.ofNullable(shutter), Optional.ofNullable(aperture), Optional.ofNullable(iso),
            Optional.ofNullable(camera), Optional.ofNullable(lens), Optional.ofNullable(ev), Optional.ofNullable(time).map(LocalDateTime::parse),
            Optional.ofNullable(offset), Set.of());
    }
    static final ZoneOffset EST = ZoneOffset.ofHours(-5);
    /** Six photos: two bodies, one lens unknown, one focal missing, one exposure-free, one with nothing usable. */
    static List<ExifRecord> session() {
        return List.of(
            record("Sony A1", "Sony FE 200-600mm G", 400.0, 1.0 / 2500, 8.0, 800, -0.33, "2026-11-06T14:30:00", EST),
            record("Sony A1", "Sony FE 200-600mm G", 600.0, 1.0 / 3200, 8.0, 1600, -0.33, "2026-11-06T14:45:00", EST),
            record("Sony A1", null, 200.0, 1.0 / 1000, 6.3, 400, 0.0, "2026-11-06T15:10:00", EST),
            record("SONY ILCE-7M4", "Sony FE 100-400mm GM", null, 1.0 / 2000, 5.6, 3200, null, "2026-11-06T16:20:00", EST),
            record("SONY ILCE-7M4", "Sony FE 100-400mm GM", 150.0, null, null, null, null, null, null),
            record(null, null, null, null, null, null, 0.7, "2026-11-06T16:30:00", EST));
    }
    static ExifStatistics stats() { var r = new StatisticsCalculator().summarize(session()); assertTrue(r.isSuccess(), r.errors()::toString); return r.value().orElseThrow(); }

    @Test void countsUseTheCorrectDenominatorPerField() {
        var s = stats();
        assertEquals(6, s.importedCount()); assertEquals(5, s.usableCount(), "the record with only a compensation value is unusable");
        assertEquals(5, s.count("camera")); assertEquals(1, s.missing("camera"));
        assertEquals(4, s.count("lens")); assertEquals(2, s.missing("lens"), "unknown lens and the unusable photo");
        assertEquals(4, s.count("focalLength")); assertEquals(4, s.count("shutter")); assertEquals(4, s.count("aperture")); assertEquals(4, s.count("iso"));
        assertEquals(3, s.count("exposureCompensation"), "the unusable record's 0.7 EV is not counted");
        assertEquals(4, s.timedCount()); assertEquals(4, s.zonedCount());
        for (String field : ExifRecord.FIELDS) assertEquals(6, s.count(field) + s.missing(field), field);
        assertTrue(s.validate().isSuccess());
    }

    @Test void cameraAndLensUsageCountsNeverMergeOrInventEquipment() {
        var s = stats();
        assertEquals(Map.of("Sony A1", 3, "SONY ILCE-7M4", 2), s.cameraCounts());
        assertEquals(Map.of("Sony FE 200-600mm G", 2, "Sony FE 100-400mm GM", 2), s.lensCounts());
    }

    @Test void focalApertureIsoAndShutterStatisticsAreExact() {
        var s = stats();
        var focal = s.summary("focalLength").orElseThrow();
        assertEquals(4, focal.count()); assertEquals(150, focal.min()); assertEquals(600, focal.max());
        assertEquals(300, focal.median(), 1e-12, "median of 150, 200, 400, 600"); assertEquals(337.5, focal.mean(), 1e-12);
        var aperture = s.summary("aperture").orElseThrow();
        assertEquals(5.6, aperture.min()); assertEquals(8.0, aperture.max()); assertEquals(7.15, aperture.median(), 1e-12);
        assertEquals(Map.of(5.6, 1, 6.3, 1, 8.0, 2), s.apertureCounts());
        var iso = s.summary("iso").orElseThrow();
        assertEquals(400, iso.min()); assertEquals(3200, iso.max()); assertEquals(1200, iso.median(), 1e-12); assertEquals(1500, iso.mean(), 1e-12);
        var shutter = s.summary("shutter").orElseThrow();
        assertEquals("1/3200 s", ExifValues.shutter(shutter.min()), "fastest"); assertEquals("1/1000 s", ExifValues.shutter(shutter.max()), "slowest");
        assertEquals(Set.of("1/3200 s", "1/2500 s", "1/2000 s", "1/1000 s"), new TreeSet<>(s.shutterCounts().keySet().stream().map(ExifValues::shutter).toList()));
        assertEquals("f/5.6", ExifValues.format("aperture", 5.6)); assertEquals("-0.33 EV", ExifValues.format("exposureCompensation", -0.33));
        assertEquals("ISO 3200", ExifValues.format("iso", 3200)); assertEquals("400 mm", ExifValues.format("focalLength", 400));
        assertEquals(Optional.of("2026-11-06T14:30"), s.firstCapture());
        assertEquals(Optional.of("2026-11-06T16:20"), s.lastCapture(), "the unusable photo's 16:30 timestamp is not part of the span");
        assertEquals(Optional.of("-05:00"), s.captureOffset());
    }

    @Test void noUsableMetadataFailsAndEmptyInputIsRejected() {
        assertTrue(calculator.summarize(List.of()).hasError("review.error.noRecords"));
        assertTrue(calculator.summarize(List.of(record(null, null, null, null, null, null, null, "2026-11-06T14:00:00", null)))
            .hasError("review.error.noUsable"));
    }

    @Test void invalidValuesAreExcludedFromEveryStatisticAndCounted() {
        var records = List.of(record("Canon EOS R5", null, -5.0, 0.0, 0.1, 0, null, null, null),
            record("Canon EOS R5", null, 100.0, 1.0 / 500, 4.0, 200, 30.0, null, null));
        var s = calculator.summarize(records).value().orElseThrow();
        assertEquals(Map.of(100.0, 1), s.focalLengthCounts()); assertEquals(1, s.invalid("focalLength")); assertEquals(1, s.invalid("shutter"));
        assertEquals(1, s.invalid("aperture")); assertEquals(1, s.invalid("iso")); assertEquals(1, s.invalid("exposureCompensation"));
        assertEquals(0, s.count("exposureCompensation"));
        assertTrue(s.limitations().stream().anyMatch(l -> l.contains("unreadable or impossible")), s.limitations().toString());
    }

    @Test void partialMetadataStillProducesTheOtherStatistics() {
        var s = calculator.summarize(List.of(record(null, null, 35.0, null, null, null, null, null, null))).value().orElseThrow();
        assertEquals(1, s.usableCount()); assertEquals(1, s.count("focalLength")); assertEquals(0, s.count("camera"));
        assertTrue(s.limitations().stream().anyMatch(l -> l.startsWith("Camera is missing for 1 of 1")), s.limitations().toString());
        assertTrue(s.limitations().stream().anyMatch(l -> l.contains("does not show image quality")));
    }

    static GearSnapshot snapshot(String type, String brand, String model, Map<String, String> specs) {
        return new GearSnapshot(UUID.randomUUID(), type, brand, model, 1, Instant.parse("2026-11-01T00:00:00Z"), specs, Set.of(), Map.of());
    }
    static ShootPlan plan(Map<String, String> settings, List<GearSnapshot> gear, List<ShootingWindow> windows) {
        var loadout = new Loadout(UUID.randomUUID(), gear, Map.of(), new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
        return new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.of(loadout), settings, windows, List.of(), List.of(), List.of(), Map.of(), List.of());
    }
    static String text(List<LocalizedMessage> lines) { return String.join("\n", lines.stream().map(StatisticsCalculator::english).toList()); }

    @Test void planVersusActualReportsOnlyNumericalDifferencesWithDenominators() {
        var a1 = snapshot("CAMERA", "Sony", "A1", Map.of());
        var a7 = snapshot("CAMERA", "Sony", "A7 IV", Map.of());
        var tele = snapshot("LENS", "Sony", "FE 200-600mm G", Map.of("minFocalMm", "200", "maxFocalMm", "600"));
        var zone = ZoneId.of("America/Toronto");
        var window = new ShootingWindow(ZonedDateTime.of(2026, 11, 6, 14, 0, 0, 0, zone), ZonedDateTime.of(2026, 11, 6, 16, 0, 0, 0, zone),
            ZonedDateTime.of(2026, 11, 6, 13, 30, 0, 0, zone), "Display", true);
        var plan = plan(Map.of("shutterSpeed", "1/2000-1/3200 s", "focalLength", "200-400 mm", "iso", "100-1600", "aperture", "auto",
            ShootPlan.settingKey("exposureCompensation", Optional.of(a1.sourceGearId())), "-0.3"), List.of(a1, a7, tele), List.of(window));
        String report = text(calculator.differences(stats(), plan));
        assertTrue(report.contains("Planned camera Sony A1: 3 of 5 photos with camera data (60.0%), recorded as Sony A1."), report);
        assertTrue(report.contains("Planned camera Sony A7 IV: no recorded camera model matches this name."), "ILCE-7M4 is never merged into A7 IV: " + report);
        assertTrue(report.contains("Recorded camera SONY ILCE-7M4 (2 of 5 photos, 40.0%) is not in the approved loadout"), report);
        assertTrue(report.contains("Planned lens Sony FE 200-600mm G: 2 of 4 photos with lens data (50.0%)"), report);
        assertTrue(report.contains("Recorded lens Sony FE 100-400mm GM (2 of 4 photos, 50.0%)"), report);
        assertTrue(report.contains("2 of 6 photos have no lens model"), report);
        assertTrue(report.contains("1 of 4 photos with focal-length data (25.0%) lie outside the focal ranges of the planned lenses (200-600 mm)"), report);
        assertTrue(report.contains("Focal length: planned 200-400 mm; 2 of 4 photos with focal-length data were within it (50.0%), 1 shorter and 1 longer"), report);
        assertTrue(report.contains("Shutter speed: planned 1/2000-1/3200 s; 3 of 4 photos with shutter data were within it (75.0%), 0 faster and 1 slower (recorded 1/3200 s - 1/1000 s)."), report);
        assertTrue(report.contains("ISO: planned 100-1600; 3 of 4 photos with ISO data were within it (75.0%), 0 lower and 1 higher"), report);
        assertTrue(report.contains("Aperture: the planned value \"auto\" is not numeric and was not compared."), report);
        assertTrue(report.contains("Exposure compensation: planned -0.3; 0 of 3 photos"), report);
        assertTrue(report.contains("(Exposure compensation was planned for Sony A1; the statistics combine all cameras.)"), report);
        assertTrue(report.contains("Timestamped photos span 2026-11-06 14:30 to 2026-11-06 16:20 (America/Toronto; 4 photos with a recorded offset)"), report);
        assertTrue(report.contains("partly overlaps the planned windows"), report);
        assertTrue(report.contains("They do not show whether photos succeeded"), report);
        assertFalse(report.toLowerCase(Locale.ROOT).matches("(?s).*\\b(sharp|blurr?y|score|better)\\b.*"), "no outcome or quality claims: " + report);
        assertEquals(calculator.compare(stats(), plan), calculator.differences(stats(), plan).stream().map(StatisticsCalculator::english).toList());
    }

    @Test void comparisonWithoutLoadoutTimesOrDataSaysSoInsteadOfGuessing() {
        var bare = new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.empty(), Map.of("iso", "100"), List.of(
            new ShootingWindow(ZonedDateTime.parse("2026-11-06T14:00-05:00[America/Toronto]"), ZonedDateTime.parse("2026-11-06T15:00-05:00[America/Toronto]"),
                ZonedDateTime.parse("2026-11-06T13:30-05:00[America/Toronto]"), "x", true)), List.of(), List.of(), List.of(), Map.of(), List.of());
        var unzoned = calculator.summarize(List.of(record("Canon EOS R5", null, 50.0, null, null, null, null, "2026-11-06T14:10:00", null))).value().orElseThrow();
        String report = text(calculator.differences(unzoned, bare));
        assertTrue(report.contains("The approved plan has no equipment loadout"), report);
        assertTrue(report.contains("ISO: planned 100, but none of the 1 photos recorded it."), report);
        assertTrue(report.contains("Capture times have no single recorded UTC offset"), report);
        assertTrue(report.contains("Focal length: the plan sets no value; recorded 50 mm."), report);
    }
}
