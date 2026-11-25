package com.photopilot.model;

import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentAssessment.LightPhase;
import com.photopilot.model.EnvironmentContext.SunEvent;
import java.time.*;
import java.util.*;

/**
 * Structured, deterministic reading of one {@link EnvironmentAssessment} snapshot that the F09 dashboard (GUI) and the
 * terminal report (CLI) both display: forecast hours around the availability with their role, statistics over the
 * shooting window kept apart from the provider's daily summary, solar position samples and light bands from the solar
 * model, provider sun events, overlaps of the availability with golden and blue hour, stage statuses, the AI outcome
 * and the findings. Built by {@code service.EnvironmentAnalyzer}; never persisted, never sent to Gemini. Missing
 * provider values stay missing (no zeros), and every computed item is distinguishable from provider data.
 */
public record EnvironmentAnalysis(EnvironmentAssessment assessment, ZoneId zone, LocalDate date, LocalDate endDate, Availability availability,
        List<HourSample> hours, WindowWeather window, Map<String, Double> daily, Coverage coverage,
        List<SolarSample> solar, List<LightBand> bands, List<SunEvent> events, List<PhaseOverlap> overlaps,
        Duration goldenOverlap, Duration blueOverlap, Duration lightOverlap, Duration recommended,
        List<Stage> stages, Overall overall, AiOutcome ai, List<Finding> findings, Instant from, Instant to) {

    /** Hourly metrics the dashboard compares, in display order; wind direction is circular and shown per sample only. */
    public static final List<String> METRICS = List.of("temperatureC", "precipitationProbabilityPct", "precipitationMm", "cloudCoverPct",
        "windSpeedKmh", "windGustsKmh", "visibilityM");

    public EnvironmentAnalysis {
        Objects.requireNonNull(assessment); Objects.requireNonNull(zone); Objects.requireNonNull(date); Objects.requireNonNull(endDate);
        Objects.requireNonNull(availability); Objects.requireNonNull(window); Objects.requireNonNull(coverage); Objects.requireNonNull(overall);
        Objects.requireNonNull(ai); Objects.requireNonNull(from); Objects.requireNonNull(to);
        hours = List.copyOf(hours); daily = Collections.unmodifiableMap(new LinkedHashMap<>(daily)); solar = List.copyOf(solar);
        bands = List.copyOf(bands); events = List.copyOf(events); overlaps = List.copyOf(overlaps); stages = List.copyOf(stages);
        findings = List.copyOf(findings);
    }

    /** Where a forecast hour lies relative to the user's availability (context hours are shown, never mixed in). */
    public enum Role { BEFORE, WINDOW, AFTER }
    /** One provider forecast hour with only the values the provider supplied. */
    public record HourSample(Instant time, ZonedDateTime local, Map<String, Double> values, Role role) {
        public HourSample { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
        public Optional<Double> value(String key) { return Optional.ofNullable(values.get(key)); }
        public boolean inWindow() { return role == Role.WINDOW; }
    }

    /**
     * How the shooting-window statistics were selected. WINDOW: the forecast hours whose timestamps lie inside the
     * availability (start and end inclusive). NEAREST: no timestamp falls inside, so the forecast hour at or within one
     * hour before the start stands for the period. NONE: no forecast hour applies.
     */
    public enum Scope { WINDOW, NEAREST, NONE }
    /** Minimum and maximum of one metric over the selected samples; {@code missing} samples lacked the value. */
    public record Metric(String key, double min, double max, int samples, int missing) {
        public boolean single() { return min == max; }
    }
    /** Weather over the shooting window: the selected samples, per-metric statistics and the condition codes in time order. */
    public record WindowWeather(Scope scope, List<HourSample> samples, Map<String, Metric> metrics, List<Integer> conditions, Optional<Integer> primaryCondition) {
        public WindowWeather {
            samples = List.copyOf(samples); metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics)); conditions = List.copyOf(conditions);
            Objects.requireNonNull(scope); Objects.requireNonNull(primaryCondition);
        }
        public Optional<Metric> metric(String key) { return Optional.ofNullable(metrics.get(key)); }
    }
    /**
     * Forecast coverage of the availability: the local whole hours inside it ({@code expected}), how many of them the
     * provider answered, the ones it did not, and the first/last forecast hour retrieved at all.
     */
    public record Coverage(boolean weatherAvailable, int expected, int available, List<ZonedDateTime> missing,
            Optional<ZonedDateTime> first, Optional<ZonedDateTime> last) {
        public Coverage { missing = List.copyOf(missing); Objects.requireNonNull(first); Objects.requireNonNull(last); }
        public boolean complete() { return weatherAvailable && missing.isEmpty() && expected > 0; }
    }

    /** Natural-light categories by geometric solar elevation (standard thresholds -18°, -12°, -6°, -0.833°). */
    public enum LightKind { NIGHT, ASTRONOMICAL, NAUTICAL, CIVIL, DAY }
    /** Solar position from the NOAA model at one sample time; azimuth in degrees clockwise from true north. */
    public record SolarSample(ZonedDateTime time, double elevation, double azimuth) { }
    /** A period of one light category from the solar model (computed estimate, one-minute resolution). */
    public record LightBand(LightKind kind, ZonedDateTime start, ZonedDateTime end) {
        public TimeInterval interval() { return new TimeInterval(start.toInstant(), end.toInstant()); }
    }
    /** A computed golden/blue phase and its intersection with the availability (zero length: the two only touch). */
    public record PhaseOverlap(LightPhase phase, Optional<TimeInterval> overlap, boolean near) {
        public PhaseOverlap { Objects.requireNonNull(phase); Objects.requireNonNull(overlap); }
        public Duration duration() { return overlap.map(TimeInterval::duration).orElse(Duration.ZERO); }
        public boolean contact() { return overlap.isPresent() && overlap.get().duration().isZero(); }
        public boolean complete() {
            return overlap.filter(o -> o.start().equals(phase.start().toInstant()) && o.end().equals(phase.end().toInstant())).isPresent();
        }
    }

    public enum StageKey { LOCATION, WEATHER, SUN, SOLAR, AVAILABILITY, AI, VALIDATION }
    /** Outcome of one assessment stage; only SUCCESS is shown as completed without qualification. */
    public enum StageStatus { SUCCESS, PARTIAL, PENDING, FAILED, TIMED_OUT, UNAVAILABLE, SKIPPED, NOT_PERFORMED;
        public boolean ok() { return this == SUCCESS; }
        public boolean problem() { return this == FAILED || this == TIMED_OUT || this == UNAVAILABLE; }
    }
    public record Stage(StageKey key, StageStatus status, Optional<LocalizedMessage> detail) {
        public Stage { Objects.requireNonNull(key); Objects.requireNonNull(status); Objects.requireNonNull(detail); }
    }
    /** What happened to the Gemini interpretation of this snapshot (PENDING only while it is still running). */
    public enum AiStatus { SUCCESS, PENDING, TIMED_OUT, UNAVAILABLE, INVALID, SKIPPED, FAILED;
        public boolean failed() { return this == TIMED_OUT || this == UNAVAILABLE || this == INVALID || this == FAILED; }
    }
    public record AiOutcome(AiStatus status, Optional<LocalizedMessage> reason, int accepted, int rejected) {
        public AiOutcome { Objects.requireNonNull(status); Objects.requireNonNull(reason); }
    }
    /** One-line state of the whole snapshot. */
    public enum Overall { COMPLETE, AI_PENDING, AI_INCOMPLETE, DATA_PARTIAL, DATA_PARTIAL_AI_INCOMPLETE }

    public enum Level { INFO, WARN }
    public enum Topic { TIMING, LOCATION, WEATHER, COVERAGE, LIGHT, AI, DATA }
    /** A deterministic observation or a provider/validation notice; technical ones are shown with the technical details. */
    public record Finding(Level level, Topic topic, LocalizedMessage message, boolean technical) {
        public Finding { Objects.requireNonNull(level); Objects.requireNonNull(topic); Objects.requireNonNull(message); }
    }

    public EnvironmentRequest request() { return assessment.request().orElseThrow(); }
    public Optional<Stage> stage(StageKey key) { return stages.stream().filter(s -> s.key() == key).findFirst(); }
    /** Overlaps worth showing: those that meet the availability or lie within a few hours of it. */
    public List<PhaseOverlap> relevantOverlaps() { return overlaps.stream().filter(o -> o.overlap().isPresent() || o.near()).toList(); }
    public List<Finding> keyFindings() { return findings.stream().filter(f -> !f.technical()).toList(); }
    /** Validated AI windows (already checked by FeasibilityValidator); never a mathematical overlap. */
    public List<ShootingWindow> windows() { return assessment.windows(); }
    public Optional<SunEvent> event(LocalDate day, String kind) {
        return events.stream().filter(e -> e.date().equals(day) && e.kind().equals(kind)).findFirst();
    }
}
