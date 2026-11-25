package com.photopilot.environment;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAnalysis.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentAssessment.LightPhase;
import com.photopilot.model.EnvironmentContext.Hour;
import com.photopilot.model.EnvironmentContext.SunEvent;
import com.photopilot.service.EnvironmentAnalyzer;
import com.photopilot.service.SolarCalculator;
import com.photopilot.validation.FeasibilityValidator;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared, deterministic F09 dashboard analysis (EnvironmentAnalyzer, TimeInterval, SolarCalculator positions and
 * bands) on hand-built snapshots: interval overlaps, shooting-window versus daily statistics, missing values, coverage,
 * solar samples across DST and hemispheres, light bands at high latitude, and AI/stage statuses. No provider or Gemini.
 */
class EnvironmentAnalysisTest {
    static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    static final LocalDate DAY = LocalDate.of(2026, 11, 30);
    static final GeoLocation PLACE = new GeoLocation("Toronto, Ontario, Canada", 43.65107, -79.347015, false, false, Optional.empty());
    static final Instant RETRIEVED = Instant.parse("2026-11-26T14:00:00Z");
    final LocalizationService english = LocalizationService.english();

    static ZonedDateTime at(LocalDate day, String time) { return ZonedDateTime.of(day, LocalTime.parse(time), TORONTO); }
    static Instant instant(String time) { return at(DAY, time).toInstant(); }
    static Hour hour(String time, Map<String, Double> values) { return new Hour(instant(time), values); }
    static Map<String, Double> weather(double temperature, double rain, double cloud) {
        var values = new LinkedHashMap<String, Double>();
        values.put("temperatureC", temperature); values.put("precipitationProbabilityPct", rain); values.put("cloudCoverPct", cloud);
        values.put("precipitationMm", 0.0); values.put("windSpeedKmh", 14.8); values.put("windGustsKmh", 24.1); values.put("windDirectionDeg", 275.0);
        values.put("visibilityM", 24100.0); values.put("weatherCode", 2.0);
        return values;
    }
    static EnvironmentContext context(List<Hour> hours, Map<String, Double> daily, List<SunEvent> events) {
        return new EnvironmentContext(PLACE, DAY, DAY, Optional.of(TORONTO), daily, hours, events, Map.of(),
            Map.of(EnvironmentContext.HOURLY, RETRIEVED, EnvironmentContext.DAILY, RETRIEVED, EnvironmentContext.SUN, RETRIEVED), Set.of(), List.of(), RETRIEVED);
    }
    static EnvironmentRequest request(String from, String until) {
        return new EnvironmentRequest(PLACE, DAY, LocalTime.parse(from), LocalTime.parse(until), Optional.empty(), Optional.empty(), "Backlit portraits", List.of());
    }
    static LightPhase phase(String kind, String from, String to) { return new LightPhase(kind, DAY, at(DAY, from), at(DAY, to), SolarCalculator.SOURCE); }
    static EnvironmentAssessment assessment(EnvironmentContext context, String from, String until, List<LightPhase> phases, List<ShootingWindow> windows,
            List<EnvironmentAssessment.RejectedWindow> rejected, Optional<EnvironmentAssessment.Interpretation> interpretation, List<LocalizedMessage> notices) {
        var request = request(from, until);
        var end = request.crossesMidnight() ? at(DAY.plusDays(1), until) : at(DAY, until);
        return new EnvironmentAssessment(context, request, "Backlit portraits", Optional.of(new Availability(at(DAY, from), end)), phases, windows, rejected,
            interpretation, notices, Set.of(), RETRIEVED);
    }
    static List<SunEvent> events() {
        return List.of(new SunEvent(DAY, "sunrise", instant("07:30"), "sunrise-sunset.org"), new SunEvent(DAY, "solarNoon", instant("12:06"), "sunrise-sunset.org"),
            new SunEvent(DAY, "sunset", instant("16:42"), "sunrise-sunset.org"));
    }
    static List<SunEvent> allEvents() {
        var events = new ArrayList<SunEvent>();
        String[] times = {"05:51", "06:24", "06:59", "07:30", "12:06", "16:42", "17:13", "17:48", "18:21"};
        for (int i = 0; i < times.length; i++) events.add(new SunEvent(DAY, EnvironmentContext.SUN_KINDS.get(i), instant(times[i]), "sunrise-sunset.org"));
        return events;
    }
    static boolean has(List<Finding> findings, String key) { return findings.stream().anyMatch(f -> f.message().key().equals(key)); }
    static Finding finding(List<Finding> findings, String key) { return findings.stream().filter(f -> f.message().key().equals(key)).findFirst().orElseThrow(); }

    @Test void illustratedShootingWindowOverlapsGoldenHourForThreeMinutesAndBlueHourNotAtAll() {
        var phases = List.of(phase("goldenEvening", "15:57", "17:02"), phase("blueEvening", "17:02", "17:15"));
        var a = EnvironmentAnalyzer.analyze(assessment(context(List.of(), Map.of(), events()), "15:00", "16:00", phases, List.of(), List.of(),
            Optional.empty(), List.of()), false);
        assertEquals(Duration.ofMinutes(3), a.goldenOverlap());
        assertEquals(Duration.ZERO, a.blueOverlap());
        assertEquals(Duration.ofMinutes(3), a.lightOverlap(), "golden and blue counted once");
        var golden = a.overlaps().getFirst();
        assertEquals(new TimeInterval(instant("15:57"), instant("16:00")), golden.overlap().orElseThrow());
        assertFalse(golden.complete()); assertFalse(golden.contact());
        assertTrue(a.overlaps().get(1).overlap().isEmpty() && a.overlaps().get(1).near(), "blue hour listed as near, without overlap");
        assertEquals(List.of(3L), finding(a.findings(), "env.finding.goldenPartial").message().arguments().stream().map(o -> ((Number) o).longValue()).toList());
        assertTrue(has(a.findings(), "env.finding.blueNone"));
        assertEquals("15:57–16:00 · 3 min", EnvironmentPresentation.overlap(golden, a, english));
        assertEquals(Duration.ZERO, a.recommended(), "a mathematical overlap is never a recommendation");
    }

    @Test void intervalArithmeticHandlesNoPartialFullContactUnionsMidnightAndDaylightSaving() {
        var available = new TimeInterval(instant("15:00"), instant("16:00"));
        assertTrue(available.intersect(new TimeInterval(instant("17:00"), instant("18:00"))).isEmpty(), "no overlap");
        assertEquals(Duration.ofMinutes(30), available.intersect(new TimeInterval(instant("15:30"), instant("17:00"))).orElseThrow().duration(), "partial");
        assertEquals(Duration.ofMinutes(20), available.intersect(new TimeInterval(instant("15:10"), instant("15:30"))).orElseThrow().duration(), "complete");
        assertEquals(available, available.intersect(available).orElseThrow(), "identical boundaries");
        var contact = available.intersect(new TimeInterval(instant("16:00"), instant("16:30"))).orElseThrow();
        assertTrue(contact.duration().isZero(), "boundary contact is a zero-length intersection");
        var parts = List.of(new TimeInterval(instant("15:00"), instant("15:30")), new TimeInterval(instant("15:20"), instant("15:40")),
            new TimeInterval(instant("15:40"), instant("15:50")), new TimeInterval(instant("17:00"), instant("17:05")));
        assertEquals(2, TimeInterval.union(parts).size(), "overlapping and touching periods merge");
        assertEquals(Duration.ofMinutes(55), TimeInterval.total(parts), "no minute counts twice");
        var overnight = new TimeInterval(at(DAY, "22:00").toInstant(), at(DAY.plusDays(1), "02:00").toInstant());
        assertEquals(Duration.ofHours(4), overnight.duration());
        var fallBack = LocalDate.of(2026, 11, 1);
        var repeated = new TimeInterval(ZonedDateTime.of(fallBack, LocalTime.of(0, 30), TORONTO).toInstant(), ZonedDateTime.of(fallBack, LocalTime.of(2, 30), TORONTO).toInstant());
        assertEquals(Duration.ofHours(3), repeated.duration(), "local 00:30-02:30 lasts three hours when the clocks go back");
        assertThrows(IllegalArgumentException.class, () -> new TimeInterval(instant("16:00"), instant("15:00")));

        // Contact and full overlap through the analyzer: availability ending as golden hour starts, blue hour inside it.
        var a = EnvironmentAnalyzer.analyze(assessment(context(List.of(), Map.of(), events()), "15:00", "17:30",
            List.of(phase("goldenEvening", "17:30", "18:00"), phase("blueEvening", "16:40", "16:55")), List.of(), List.of(), Optional.empty(), List.of()), false);
        assertTrue(a.overlaps().getFirst().contact()); assertTrue(has(a.findings(), "env.finding.goldenTouch"));
        assertTrue(a.overlaps().get(1).complete()); assertEquals(Duration.ofMinutes(15), a.blueOverlap());
        assertTrue(has(a.findings(), "env.finding.blueFull"));
        // Cross-midnight availability with a phase on the next calendar day.
        var night = EnvironmentAnalyzer.analyze(assessment(context(List.of(), Map.of(), events()), "22:00", "08:00",
            List.of(new LightPhase("blueMorning", DAY.plusDays(1), at(DAY.plusDays(1), "06:50"), at(DAY.plusDays(1), "07:05"), SolarCalculator.SOURCE)),
            List.of(), List.of(), Optional.empty(), List.of()), false);
        assertEquals(Duration.ofMinutes(15), night.blueOverlap());
        assertEquals(DAY.plusDays(1), night.endDate());
    }

    @Test void shootingWindowStatisticsStayApartFromDailyValuesAndNeverTurnMissingIntoZero() {
        var hours = new ArrayList<Hour>();
        hours.add(hour("12:00", weather(1.0, 37, 80)));
        hours.add(hour("14:00", weather(-0.5, 20, 50)));
        hours.add(hour("15:00", weather(-2.5, 6, 21)));
        var sixteen = weather(-1.0, 6, 12); sixteen.remove("visibilityM"); sixteen.remove("precipitationMm");
        hours.add(hour("16:00", sixteen));
        hours.add(hour("17:00", weather(-3.0, 50, 90)));
        var daily = new LinkedHashMap<String, Double>(); daily.put("precipitationProbabilityMaxPct", 37.0); daily.put("temperatureMinC", -4.0); daily.put("temperatureMaxC", 2.0);
        var a = EnvironmentAnalyzer.analyze(assessment(context(hours, daily, events()), "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(), List.of()), false);
        assertEquals(Scope.WINDOW, a.window().scope());
        assertEquals(List.of(instant("15:00"), instant("16:00")), a.window().samples().stream().map(HourSample::time).toList(), "both boundaries included");
        assertEquals(List.of(Role.BEFORE, Role.BEFORE, Role.WINDOW, Role.WINDOW, Role.AFTER), a.hours().stream().map(HourSample::role).toList());
        var rain = a.window().metric("precipitationProbabilityPct").orElseThrow();
        assertEquals(6, rain.max(), "the window value, not the 37% daily maximum");
        assertEquals(37.0, a.daily().get("precipitationProbabilityMaxPct"), "the daily maximum is kept separately");
        assertEquals("6%", EnvironmentPresentation.windowValue(a, "precipitationProbabilityPct", english));
        assertEquals("full-day max 37%", english.text("env.overview.dayMax", EnvironmentPresentation.daily(a, "precipitationProbabilityMaxPct", english).orElseThrow()));
        var temperature = a.window().metric("temperatureC").orElseThrow();
        assertEquals(-2.5, temperature.min()); assertEquals(-1.0, temperature.max(), "negative temperatures keep their values");
        assertEquals("-2.5–-1.0 °C", EnvironmentPresentation.range(temperature, english));
        var visibility = a.window().metric("visibilityM").orElseThrow();
        assertEquals(1, visibility.samples()); assertEquals(1, visibility.missing());
        assertEquals(Optional.empty(), a.window().metric("doesNotExist"));
        assertEquals(StageStatus.PARTIAL, a.stage(StageKey.WEATHER).orElseThrow().status(), "a missing value makes the weather stage partial");
        // A metric no hour has is "Unavailable", never 0.
        var noWind = new ArrayList<Hour>();
        for (var h : hours) { var values = new LinkedHashMap<>(h.values()); values.remove("windGustsKmh"); noWind.add(new Hour(h.time(), values)); }
        var b = EnvironmentAnalyzer.analyze(assessment(context(noWind, daily, events()), "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(), List.of()), false);
        assertEquals("Unavailable", EnvironmentPresentation.windowValue(b, "windGustsKmh", english));
        assertEquals("W", EnvironmentPresentation.directions(a, english), "repeated wind sectors are named once");
    }

    @Test void shortWindowsUseTheNearestHourAndMissingHoursAreReported() {
        var hours = List.of(hour("15:00", weather(6.8, 6, 21)), hour("16:00", weather(6.5, 6, 12)), hour("18:00", weather(5.8, 6, 30)));
        var shortWindow = EnvironmentAnalyzer.analyze(assessment(context(hours, Map.of(), events()), "15:10", "15:50", List.of(), List.of(), List.of(),
            Optional.empty(), List.of()), false);
        assertEquals(Scope.NEAREST, shortWindow.window().scope());
        assertEquals(instant("15:00"), shortWindow.window().samples().getFirst().time());
        assertTrue(has(shortWindow.findings(), "env.finding.nearest"));
        assertEquals(0, shortWindow.coverage().expected());
        var gap = EnvironmentAnalyzer.analyze(assessment(context(hours, Map.of(), events()), "15:00", "18:00", List.of(), List.of(), List.of(),
            Optional.empty(), List.of()), false);
        assertEquals(4, gap.coverage().expected()); assertEquals(3, gap.coverage().available());
        assertEquals(List.of(at(DAY, "17:00")), gap.coverage().missing());
        assertTrue(has(gap.findings(), "env.finding.missingHours"));
        assertEquals(StageStatus.PARTIAL, gap.stage(StageKey.WEATHER).orElseThrow().status());
        var none = EnvironmentAnalyzer.analyze(assessment(context(List.of(), Map.of(), events()), "15:00", "16:00", List.of(), List.of(), List.of(),
            Optional.empty(), List.of(LocalizedMessage.of("env.notice.beyondHorizon", 16, "2026-11-29"))), false);
        assertEquals(StageStatus.UNAVAILABLE, none.stage(StageKey.WEATHER).orElseThrow().status());
        assertEquals("env.notice.beyondHorizon", none.stage(StageKey.WEATHER).orElseThrow().detail().orElseThrow().key());
        assertEquals(Overall.DATA_PARTIAL_AI_INCOMPLETE, none.overall());
    }

    @Test void primaryConditionIsTheMostFrequentCodeAndTiesGoToTheMoreSevere() {
        var hours = new ArrayList<Hour>();
        int[] codes = {2, 61, 2, 61};
        for (int i = 0; i < codes.length; i++) { var v = weather(5, 10, 50); v.put("weatherCode", (double) codes[i]); hours.add(hour(String.format("%02d:00", 14 + i), v)); }
        var a = EnvironmentAnalyzer.analyze(assessment(context(hours, Map.of(), events()), "14:00", "17:00", List.of(), List.of(), List.of(), Optional.empty(), List.of()), false);
        assertEquals(Optional.of(61), a.window().primaryCondition());
        assertEquals("rain", EnvironmentPresentation.condition(a, english));
        assertEquals(List.of(2, 61, 2, 61), a.window().conditions());
    }

    @Test void aiOutcomesAndStagesNeverShowAFailedOrSkippedStepAsCompleted() {
        var context = context(List.of(hour("15:00", weather(6, 6, 20)), hour("16:00", weather(6, 6, 20))), Map.of(), allEvents());
        var timeout = EnvironmentAnalyzer.analyze(assessment(context, "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(),
            List.of(LocalizedMessage.of("env.notice.aiUnavailable", LocalizedMessage.of("ai.error.timeout", 30)))), false);
        assertEquals(AiStatus.TIMED_OUT, timeout.ai().status());
        assertEquals(StageStatus.TIMED_OUT, timeout.stage(StageKey.AI).orElseThrow().status());
        assertEquals(StageStatus.NOT_PERFORMED, timeout.stage(StageKey.VALIDATION).orElseThrow().status(), "nothing was validated");
        assertEquals(Overall.AI_INCOMPLETE, timeout.overall());
        assertTrue(EnvironmentPresentation.aiOutcome(timeout, english).containsAll(List.of("Gemini interpretation timed out",
            "Weather and sunlight information remain available.", "No AI-validated shooting recommendation is currently available.")));
        assertEquals("[FAIL]", EnvironmentPresentation.tag(timeout.stage(StageKey.AI).orElseThrow().status()));
        for (var stage : timeout.stages()) if (stage.key() != StageKey.AI && stage.key() != StageKey.VALIDATION) assertTrue(stage.status().ok(), stage.toString());

        var unavailable = EnvironmentAnalyzer.ai(assessment(context, "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(),
            List.of(LocalizedMessage.of("env.notice.aiUnavailable", LocalizedMessage.of("ai.error.serviceUnavailable", 503)))), false);
        assertEquals(AiStatus.UNAVAILABLE, unavailable.status());
        var invalid = EnvironmentAnalyzer.analyze(assessment(context, "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(),
            List.of(LocalizedMessage.of("env.notice.aiUnavailable", LocalizedMessage.of("env.ai.invalid")))), false);
        assertEquals(AiStatus.INVALID, invalid.ai().status()); assertEquals(StageStatus.FAILED, invalid.stage(StageKey.AI).orElseThrow().status());
        var skipped = EnvironmentAnalyzer.analyze(assessment(context, "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(),
            List.of(LocalizedMessage.of("env.notice.insufficient"))), false);
        assertEquals(AiStatus.SKIPPED, skipped.ai().status()); assertEquals(StageStatus.SKIPPED, skipped.stage(StageKey.AI).orElseThrow().status());
        var pending = EnvironmentAnalyzer.analyze(assessment(context, "15:00", "16:00", List.of(), List.of(), List.of(), Optional.empty(), List.of()), true);
        assertEquals(StageStatus.PENDING, pending.stage(StageKey.AI).orElseThrow().status());
        assertEquals(StageStatus.PENDING, pending.stage(StageKey.VALIDATION).orElseThrow().status());
        assertEquals(Overall.AI_PENDING, pending.overall());

        var window = new ShootingWindow(at(DAY, "15:10"), at(DAY, "15:50"), at(DAY, "15:10"), "", true, "Backlight", "daylight", "", List.of("sunset"), List.of());
        var rejected = new EnvironmentAssessment.RejectedWindow("Late", "2026-11-30T18:00-05:00", "2026-11-30T19:00-05:00", List.of(LocalizedMessage.of("env.reject.availability", "a", "b")));
        var success = EnvironmentAnalyzer.analyze(assessment(context, "15:00", "16:00", List.of(), List.of(window), List.of(rejected),
            Optional.of(new EnvironmentAssessment.Interpretation("Fine.", List.of(), List.of())), List.of()), false);
        assertEquals(AiStatus.SUCCESS, success.ai().status());
        var validation = success.stage(StageKey.VALIDATION).orElseThrow();
        assertEquals(StageStatus.SUCCESS, validation.status());
        assertEquals("env.stage.validated", validation.detail().orElseThrow().key());
        assertEquals(Duration.ofMinutes(40), success.recommended());
        assertEquals(Overall.COMPLETE, success.overall());
    }

    @Test void solarSamplesFollowTheModelThroughDaylightSavingAndBothHemispheres() {
        var fallBack = LocalDate.of(2026, 11, 1);
        var samples = SolarCalculator.samples(43.65, -79.38, TORONTO, fallBack, fallBack, Duration.ofMinutes(10));
        assertEquals(25 * 6 + 1, samples.size(), "the 25-hour day keeps every instant");
        for (var s : samples) assertEquals(SolarCalculator.elevation(43.65, -79.38, s.time().toInstant()), s.elevation(), 1e-12);
        var highest = samples.stream().max(Comparator.comparingDouble(EnvironmentAnalysis.SolarSample::elevation)).orElseThrow();
        assertEquals(180, highest.azimuth(), 3, "the sun culminates due south in Toronto");
        assertTrue(samples.stream().filter(s -> s.time().getHour() == 9).allMatch(s -> s.azimuth() > 90 && s.azimuth() < 180), "morning sun in the south-east");
        assertTrue(samples.stream().filter(s -> s.time().getHour() == 15).allMatch(s -> s.azimuth() > 180 && s.azimuth() < 270), "afternoon sun in the south-west");
        assertTrue(samples.getFirst().elevation() < -30, "negative elevation at midnight");
        // Summer solstice culmination: 90 - 43.65 + 23.44 degrees.
        var june = SolarCalculator.samples(43.65, -79.38, TORONTO, LocalDate.of(2027, 6, 21), LocalDate.of(2027, 6, 21), Duration.ofMinutes(1));
        assertEquals(69.8, june.stream().mapToDouble(EnvironmentAnalysis.SolarSample::elevation).max().orElseThrow(), 0.3);
        // Southern hemisphere: Sydney culminates in the north near the December solstice.
        var sydney = ZoneId.of("Australia/Sydney");
        var summer = SolarCalculator.samples(-33.87, 151.21, sydney, LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 21), Duration.ofMinutes(10));
        var top = summer.stream().max(Comparator.comparingDouble(EnvironmentAnalysis.SolarSample::elevation)).orElseThrow();
        assertEquals(79.6, top.elevation(), 0.6);
        assertTrue(top.azimuth() < 15 || top.azimuth() > 345, "culmination due north: " + top.azimuth());
    }

    @Test void lightBandsCoverTheDayIncludingPolarDayAndPolarNight() {
        var bands = SolarCalculator.bands(43.65, -79.38, TORONTO, DAY, DAY);
        assertEquals(DAY.atStartOfDay(TORONTO), bands.getFirst().start()); assertEquals(DAY.plusDays(1).atStartOfDay(TORONTO), bands.getLast().end());
        for (int i = 1; i < bands.size(); i++) assertEquals(bands.get(i - 1).end(), bands.get(i).start(), "contiguous");
        assertEquals(List.of(LightKind.NIGHT, LightKind.ASTRONOMICAL, LightKind.NAUTICAL, LightKind.CIVIL, LightKind.DAY, LightKind.CIVIL, LightKind.NAUTICAL,
            LightKind.ASTRONOMICAL, LightKind.NIGHT), bands.stream().map(EnvironmentAnalysis.LightBand::kind).toList());
        var day = bands.stream().filter(b -> b.kind() == LightKind.DAY).findFirst().orElseThrow();
        var sunrise = SolarCalculator.crossing(43.65, -79.38, DAY.atStartOfDay(TORONTO).toInstant(), DAY.plusDays(1).atStartOfDay(TORONTO).toInstant(),
            SolarCalculator.SUNRISE_ELEVATION, true).orElseThrow();
        assertEquals(0, Duration.between(sunrise, day.start().toInstant()).toMinutes(), "daylight starts at the model sunrise");
        var tromso = ZoneId.of("Europe/Oslo");
        var midsummer = SolarCalculator.bands(69.65, 18.96, tromso, LocalDate.of(2027, 6, 21), LocalDate.of(2027, 6, 21));
        assertEquals(List.of(LightKind.DAY), midsummer.stream().map(EnvironmentAnalysis.LightBand::kind).toList(), "polar day");
        var midwinter = SolarCalculator.bands(69.65, 18.96, tromso, LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 21));
        assertTrue(midwinter.stream().noneMatch(b -> b.kind() == LightKind.DAY), "polar night has no daylight");
        assertEquals("Civil twilight · golden hour", EnvironmentPresentation.lightAt(-2, english));
        assertEquals("Civil twilight · blue hour", EnvironmentPresentation.lightAt(-5, english));
        assertEquals("Night", EnvironmentPresentation.lightAt(-25, english));
    }

    @Test void analysisOfAnAssessmentWithoutGoldenOrBlueHourSaysSo() {
        var a = EnvironmentAnalyzer.analyze(assessment(context(List.of(), Map.of(), List.of()), "12:00", "13:00", List.of(), List.of(), List.of(),
            Optional.empty(), List.of()), false);
        assertTrue(has(a.findings(), "env.finding.goldenAbsent") && has(a.findings(), "env.finding.blueAbsent"));
        assertEquals(StageStatus.UNAVAILABLE, a.stage(StageKey.SUN).orElseThrow().status());
        assertFalse(a.solar().isEmpty(), "the solar curve is computed even without provider sun data");
    }

    @Test void requirementConflictsCompareTheDateAndTheWallClockTimeSeparately() {
        // Regression: confirmed 2026-11-20 15:00 and an assessment for 2026-11-30 15:00-16:00 is a date conflict only.
        var requirement = new ShootRequirement("portraits", "Backlit portraits", List.of(), Optional.of(ZonedDateTime.of(LocalDate.of(2026, 11, 20), LocalTime.of(15, 0), ZoneId.systemDefault())),
            Optional.of(LocalDate.of(2026, 11, 20)), "afternoon", Optional.empty(), Optional.of(Duration.ofMinutes(60)), Optional.of(1), Optional.of(true),
            Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), true);
        var found = FeasibilityValidator.requirementConflicts(requirement, DAY, LocalTime.of(15, 0), LocalTime.of(16, 0), Duration.ofMinutes(60));
        assertEquals(List.of("env.notice.dateDiffers"), found.stream().map(LocalizedMessage::key).toList());
        assertEquals("The confirmed requirement date is 2026-11-20, but this assessment uses 2026-11-30. The requirement was not changed.", found.getFirst().render(english));
        var later = FeasibilityValidator.requirementConflicts(requirement, LocalDate.of(2026, 11, 20), LocalTime.of(16, 30), LocalTime.of(18, 0), Duration.ofMinutes(90));
        assertEquals(List.of("env.notice.timeOutside"), later.stream().map(LocalizedMessage::key).toList());
        assertEquals("The confirmed requirement start time 15:00 is outside your availability (16:30–18:00). The requirement was not changed.", later.getFirst().render(english));
        var overnight = FeasibilityValidator.requirementConflicts(requirement, LocalDate.of(2026, 11, 20), LocalTime.of(14, 0), LocalTime.of(2, 0), Duration.ofHours(12));
        assertTrue(overnight.isEmpty(), "15:00 lies inside 14:00-02:00 across midnight");
        var short60 = FeasibilityValidator.requirementConflicts(requirement, LocalDate.of(2026, 11, 20), LocalTime.of(15, 0), LocalTime.of(15, 30), Duration.ofMinutes(30));
        assertEquals(List.of("env.notice.durationLonger"), short60.stream().map(LocalizedMessage::key).toList());
        assertEquals(Optional.of(Duration.ofHours(12)), FeasibilityValidator.wallClockLength(LocalTime.of(14, 0), LocalTime.of(2, 0)));
        assertEquals(Optional.empty(), FeasibilityValidator.wallClockLength(LocalTime.of(14, 0), LocalTime.of(14, 0)));
    }
}
