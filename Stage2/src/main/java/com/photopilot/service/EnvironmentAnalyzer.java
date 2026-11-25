package com.photopilot.service;

import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAnalysis.*;
import com.photopilot.model.EnvironmentAssessment.LightPhase;
import com.photopilot.progress.ErrorKind;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Deterministic analysis of one F09 assessment snapshot for the dashboard and the terminal report
 * ({@link EnvironmentAnalysis}). It reads only what the assessment already holds (provider data, computed phases,
 * validated windows, notices) plus the local solar model, so building it never contacts a provider or Gemini and
 * switching views never changes a result. Rules:
 * <ul>
 * <li>Shooting-window statistics use the forecast hours whose timestamps lie in the availability (both ends
 * included); when none does, the forecast hour at or less than one hour before the start stands for the period
 * (labelled as such). Values are never interpolated, summed across hours as probabilities, or replaced by zero.</li>
 * <li>The provider's daily summary is kept separately and always labelled as a full-day value.</li>
 * <li>Overlaps are intersections of instants ({@link TimeInterval}); totals use unions, so no minute counts twice.</li>
 * <li>The AI outcome comes from the structured notices the service attached (failure reason keys), never from text.</li>
 * </ul>
 */
public final class EnvironmentAnalyzer {
    /** Forecast hours shown as context before and after the availability. */
    public static final Duration CONTEXT = Duration.ofHours(3);
    /** Light phases within this distance of the availability are listed even without overlap. */
    public static final Duration NEAR = Duration.ofHours(3);
    /** Chart sampling of the solar curve; event and phase times keep their one-minute calculation. */
    public static final Duration SOLAR_STEP = Duration.ofMinutes(10);
    /** A forecast hour may stand for an availability that contains no forecast timestamp only within this distance. */
    public static final Duration NEAREST_LIMIT = Duration.ofHours(1);
    /** Notices produced by the AI stage; an AI-only retry replaces exactly these. */
    public static final Set<String> AI_NOTICES = Set.of("env.notice.aiUnavailable", "env.notice.statementsDropped", "env.notice.noValidWindow",
        "env.notice.noWindowProposed", "env.notice.aiRetried");
    private static final Set<String> TECHNICAL = Set.of("env.notice.sunZone", "env.notice.modelMismatch", "env.notice.implausible",
        "env.notice.sunDate", "env.notice.sunMismatch", "env.notice.statementsDropped");
    private static final Set<String> TIMING = Set.of("env.notice.dateDiffers", "env.notice.timeOutside", "env.notice.durationLonger",
        "env.notice.sessionDate", "env.notice.timeAmbiguous", "env.notice.partlyPast");
    private static final Set<String> LOCATION = Set.of("env.resolve.limitation", "env.resolve.broader", "env.notice.zoneMismatch",
        "env.notice.zoneMismatchEntered", "env.notice.zoneFromLookup", "env.notice.zoneFromUser", "env.resolve.zoneAi", "env.resolve.zoneMismatch");
    private static final Set<String> COVERAGE = Set.of("env.notice.beyondHorizon", "env.notice.nextDayBeyondHorizon", "env.notice.weatherUnavailable",
        "env.notice.partialSeries", "env.notice.sunUnavailable", "env.notice.sunFallback", "env.notice.sunEventAbsent", "env.notice.zoneUnverified",
        "env.notice.stale", "env.notice.longRange");
    private static final Set<String> AI = Set.of("env.notice.aiUnavailable", "env.notice.noValidWindow", "env.notice.noWindowProposed",
        "env.notice.insufficient", "env.notice.aiRetried", "env.notice.statementsDropped");
    private static final Set<String> INFO = Set.of("env.notice.zoneFromLookup", "env.notice.zoneFromUser", "env.resolve.zoneAi", "env.notice.longRange",
        "env.notice.aiRetried", "env.notice.noWindowProposed");
    private static final Set<String> TWILIGHT = Set.of("civilDawn", "civilDusk", "nauticalDawn", "nauticalDusk", "astronomicalDawn", "astronomicalDusk");

    private EnvironmentAnalyzer() { }

    /**
     * The analysis of {@code assessment}; {@code aiPending} marks a factual snapshot shown while the Gemini
     * interpretation is still running. The assessment must carry its reviewed request and resolved availability.
     */
    public static EnvironmentAnalysis analyze(EnvironmentAssessment assessment, boolean aiPending) {
        Objects.requireNonNull(assessment, "assessment");
        var request = assessment.request().orElseThrow(() -> new IllegalArgumentException("assessment without reviewed inputs"));
        var availability = assessment.availability().orElseThrow(() -> new IllegalArgumentException("assessment without availability"));
        var context = assessment.context();
        ZoneId zone = availability.start().getZone();
        LocalDate date = request.date(), endDate = request.endDate();
        Instant open = availability.start().toInstant(), close = availability.end().toInstant();
        Instant from = date.atStartOfDay(zone).toInstant(), to = endDate.plusDays(1).atStartOfDay(zone).toInstant();

        var hours = new ArrayList<HourSample>();
        for (var hour : context.hourly()) {
            if (hour.time().isBefore(open.minus(CONTEXT)) || hour.time().isAfter(close.plus(CONTEXT))) continue;
            Role role = hour.time().isBefore(open) ? Role.BEFORE : hour.time().isAfter(close) ? Role.AFTER : Role.WINDOW;
            hours.add(new HourSample(hour.time(), hour.time().atZone(zone), hour.values(), role));
        }
        var window = window(hours, open);
        var coverage = coverage(context, availability, zone);

        double lat = request.place().latitude().orElseThrow(), lon = request.place().longitude().orElseThrow();
        var solar = SolarCalculator.samples(lat, lon, zone, date, endDate, SOLAR_STEP);
        var bands = SolarCalculator.bands(lat, lon, zone, date, endDate);
        var events = context.sunEvents().stream().filter(e -> !e.date().isBefore(date) && !e.date().isAfter(endDate))
            .sorted(Comparator.comparing(EnvironmentContext.SunEvent::time)).toList();

        var available = new TimeInterval(open, close);
        var overlaps = new ArrayList<PhaseOverlap>();
        var golden = new ArrayList<TimeInterval>(); var blue = new ArrayList<TimeInterval>();
        for (var phase : assessment.lightPhases()) {
            var interval = new TimeInterval(phase.start().toInstant(), phase.end().toInstant());
            var overlap = interval.intersect(available);
            overlaps.add(new PhaseOverlap(phase, overlap, overlap.isPresent() || distance(interval, available).compareTo(NEAR) <= 0));
            overlap.ifPresent(o -> (phase.golden() ? golden : blue).add(o));
        }
        var both = new ArrayList<>(golden); both.addAll(blue);
        Duration recommended = TimeInterval.total(assessment.windows().stream().map(w -> new TimeInterval(w.start().toInstant(), w.end().toInstant())).toList());

        var ai = ai(assessment, aiPending);
        var stages = stages(assessment, request, context, window, coverage, ai);
        boolean dataPartial = stages.stream().filter(s -> s.key() == StageKey.WEATHER || s.key() == StageKey.SUN).anyMatch(s -> !s.status().ok());
        Overall overall = ai.status() == AiStatus.PENDING ? Overall.AI_PENDING
            : ai.status() == AiStatus.SUCCESS ? (dataPartial ? Overall.DATA_PARTIAL : Overall.COMPLETE)
            : dataPartial ? Overall.DATA_PARTIAL_AI_INCOMPLETE : Overall.AI_INCOMPLETE;
        var findings = findings(assessment, overlaps, golden, blue, window, coverage);
        return new EnvironmentAnalysis(assessment, zone, date, endDate, availability, hours, window, context.weather(), coverage, solar, bands, events,
            overlaps, TimeInterval.total(golden), TimeInterval.total(blue), TimeInterval.total(both), recommended, stages, overall, ai, findings, from, to);
    }

    private static Duration distance(TimeInterval a, TimeInterval b) {
        if (a.end().isBefore(b.start())) return Duration.between(a.end(), b.start());
        if (b.end().isBefore(a.start())) return Duration.between(b.end(), a.start());
        return Duration.ZERO;
    }

    private static WindowWeather window(List<HourSample> hours, Instant open) {
        var inside = hours.stream().filter(HourSample::inWindow).toList();
        Scope scope = Scope.WINDOW;
        List<HourSample> selected = inside;
        if (inside.isEmpty()) {
            var nearest = hours.stream().filter(h -> !h.time().isAfter(open) && Duration.between(h.time(), open).compareTo(NEAREST_LIMIT) < 0)
                .reduce((first, second) -> second);
            scope = nearest.isPresent() ? Scope.NEAREST : Scope.NONE;
            selected = nearest.map(List::of).orElse(List.of());
        }
        var metrics = new LinkedHashMap<String, Metric>();
        for (String key : EnvironmentAnalysis.METRICS) {
            var values = selected.stream().map(h -> h.value(key)).flatMap(Optional::stream).toList();
            if (values.isEmpty()) continue;
            metrics.put(key, new Metric(key, Collections.min(values), Collections.max(values), values.size(), selected.size() - values.size()));
        }
        var codes = selected.stream().map(h -> h.value("weatherCode")).flatMap(Optional::stream).map(Double::intValue).toList();
        // Primary condition: the most frequent WMO code among the selected hours; a tie goes to the higher (more severe) code.
        var counts = new TreeMap<Integer, Long>();
        codes.forEach(code -> counts.merge(code, 1L, Long::sum));
        Optional<Integer> primary = counts.entrySet().stream().max(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue)
            .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey);
        return new WindowWeather(scope, selected, metrics, codes, primary);
    }

    private static Coverage coverage(EnvironmentContext context, EnvironmentAssessment.Availability availability, ZoneId zone) {
        var times = new HashSet<Instant>();
        context.hourly().forEach(h -> times.add(h.time()));
        int expected = 0;
        var missing = new ArrayList<ZonedDateTime>();
        ZonedDateTime mark = availability.start().truncatedTo(ChronoUnit.HOURS);
        if (mark.isBefore(availability.start())) mark = mark.plusHours(1);
        for (; !mark.isAfter(availability.end()); mark = mark.plusHours(1)) {
            expected++;
            if (!times.contains(mark.toInstant())) missing.add(mark);
        }
        var hourly = context.hourly();
        return new Coverage(!hourly.isEmpty(), expected, expected - missing.size(), missing,
            hourly.isEmpty() ? Optional.empty() : Optional.of(hourly.getFirst().time().atZone(zone)),
            hourly.isEmpty() ? Optional.empty() : Optional.of(hourly.getLast().time().atZone(zone)));
    }

    /** The AI outcome recorded in the assessment: interpretation present, a failure reason, skipped, or still pending. */
    public static AiOutcome ai(EnvironmentAssessment assessment, boolean pending) {
        int accepted = assessment.windows().size(), rejected = assessment.rejected().size();
        if (assessment.aiInterpreted()) return new AiOutcome(AiStatus.SUCCESS, Optional.empty(), accepted, rejected);
        var failure = assessment.notices().stream().filter(n -> n.key().equals("env.notice.aiUnavailable")).findFirst();
        if (failure.isPresent()) {
            var reason = failure.get().arguments().stream().filter(LocalizedMessage.class::isInstance).map(LocalizedMessage.class::cast).findFirst();
            String key = reason.map(LocalizedMessage::key).orElse("env.ai.invalid");
            AiStatus status = key.equals("env.ai.invalid") ? AiStatus.INVALID : switch (ErrorKind.of(key)) {
                case TIMEOUT -> AiStatus.TIMED_OUT;
                case RATE_LIMITED, SERVICE_UNAVAILABLE, NETWORK, CREDENTIALS -> AiStatus.UNAVAILABLE;
                case INVALID_RESPONSE -> AiStatus.INVALID;
                default -> AiStatus.FAILED;
            };
            return new AiOutcome(status, reason, accepted, rejected);
        }
        if (pending) return new AiOutcome(AiStatus.PENDING, Optional.empty(), accepted, rejected);
        boolean insufficient = assessment.notices().stream().anyMatch(n -> n.key().equals("env.notice.insufficient"));
        return new AiOutcome(AiStatus.SKIPPED, Optional.of(LocalizedMessage.of(insufficient ? "env.notice.insufficient" : "env.ai.none")), accepted, rejected);
    }

    private static List<Stage> stages(EnvironmentAssessment assessment, EnvironmentRequest request, EnvironmentContext context, WindowWeather window,
            Coverage coverage, AiOutcome ai) {
        var stages = new ArrayList<Stage>();
        boolean approximate = request.aiLocation() || request.accepted().filter(PlaceCandidate::broaderArea).isPresent();
        stages.add(new Stage(StageKey.LOCATION, approximate ? StageStatus.PARTIAL : StageStatus.SUCCESS,
            Optional.of(LocalizedMessage.of("env.resolve.source." + request.locationSource()))));

        var notices = assessment.notices();
        boolean weatherGaps = context.unavailableFields().stream().anyMatch(f -> f.startsWith("hourly") || f.startsWith("daily"));
        if (!context.hasWeather())
            stages.add(new Stage(StageKey.WEATHER, StageStatus.UNAVAILABLE, notices.stream().filter(n -> n.key().equals("env.notice.beyondHorizon")
                || n.key().equals("env.notice.weatherUnavailable")).findFirst().or(() -> Optional.of(LocalizedMessage.of("env.source.weatherNone")))));
        else if (window.scope() == Scope.NONE)
            stages.add(new Stage(StageKey.WEATHER, StageStatus.PARTIAL, Optional.of(LocalizedMessage.of("env.stage.weatherNone"))));
        else if (!coverage.missing().isEmpty() || window.scope() == Scope.NEAREST || weatherGaps
                || window.metrics().values().stream().anyMatch(m -> m.missing() > 0))
            stages.add(new Stage(StageKey.WEATHER, StageStatus.PARTIAL, Optional.of(LocalizedMessage.of("env.stage.weatherPartial", coverage.available(), coverage.expected()))));
        else stages.add(new Stage(StageKey.WEATHER, StageStatus.SUCCESS, Optional.of(LocalizedMessage.of("env.stage.weatherHours", window.samples().size()))));

        var day = request.date();
        var kinds = context.sunEvents().stream().filter(e -> e.date().equals(day)).map(EnvironmentContext.SunEvent::kind).toList();
        var sources = String.join(", ", context.sunEvents().stream().map(EnvironmentContext.SunEvent::source).distinct().toList());
        // Complete: every event of the shooting date was reported. Otherwise some are missing (provider fallback without
        // twilight, or events that do not occur at high latitudes); the notices say which.
        if (kinds.isEmpty()) stages.add(new Stage(StageKey.SUN, StageStatus.UNAVAILABLE, Optional.of(LocalizedMessage.of("env.source.sunNone"))));
        else if (kinds.containsAll(EnvironmentContext.SUN_KINDS))
            stages.add(new Stage(StageKey.SUN, StageStatus.SUCCESS, Optional.of(LocalizedMessage.of("env.stage.sunSource", sources))));
        else stages.add(new Stage(StageKey.SUN, StageStatus.PARTIAL, Optional.of(LocalizedMessage.of(kinds.stream().anyMatch(TWILIGHT::contains)
            ? "env.stage.sunSource" : "env.stage.sunPartial", sources))));

        stages.add(new Stage(StageKey.SOLAR, StageStatus.SUCCESS, Optional.of(LocalizedMessage.of("env.stage.solar", assessment.lightPhases().size()))));
        boolean adjusted = notices.stream().anyMatch(n -> n.key().equals("env.notice.timeAmbiguous") || n.key().equals("env.notice.partlyPast"));
        stages.add(new Stage(StageKey.AVAILABILITY, adjusted ? StageStatus.PARTIAL : StageStatus.SUCCESS,
            Optional.of(LocalizedMessage.of("env.stage.availability", assessment.availability().orElseThrow().duration().toMinutes()))));

        StageStatus aiStage = switch (ai.status()) {
            case SUCCESS -> StageStatus.SUCCESS;
            case PENDING -> StageStatus.PENDING;
            case TIMED_OUT -> StageStatus.TIMED_OUT;
            case UNAVAILABLE -> StageStatus.UNAVAILABLE;
            case INVALID, FAILED -> StageStatus.FAILED;
            case SKIPPED -> StageStatus.SKIPPED;
        };
        stages.add(new Stage(StageKey.AI, aiStage, ai.reason()));
        int proposals = ai.accepted() + ai.rejected();
        if (ai.status() == AiStatus.PENDING) stages.add(new Stage(StageKey.VALIDATION, StageStatus.PENDING, Optional.empty()));
        else if (proposals == 0) stages.add(new Stage(StageKey.VALIDATION, StageStatus.NOT_PERFORMED, Optional.of(LocalizedMessage.of("env.stage.noProposals"))));
        else stages.add(new Stage(StageKey.VALIDATION, StageStatus.SUCCESS, Optional.of(LocalizedMessage.of("env.stage.validated", ai.accepted(), ai.rejected()))));
        return List.copyOf(stages);
    }

    private static List<Finding> findings(EnvironmentAssessment assessment, List<PhaseOverlap> overlaps, List<TimeInterval> golden, List<TimeInterval> blue,
            WindowWeather window, Coverage coverage) {
        var found = new ArrayList<Finding>();
        for (var notice : assessment.notices()) {
            String key = notice.key();
            Topic topic = TIMING.contains(key) ? Topic.TIMING : LOCATION.contains(key) ? Topic.LOCATION : key.startsWith("env.indicator.") ? Topic.WEATHER
                : COVERAGE.contains(key) ? Topic.COVERAGE : AI.contains(key) ? Topic.AI : Topic.DATA;
            found.add(new Finding(INFO.contains(key) ? Level.INFO : Level.WARN, topic, notice, TECHNICAL.contains(key)));
        }
        if (!coverage.missing().isEmpty()) found.add(new Finding(Level.WARN, Topic.COVERAGE, LocalizedMessage.of("env.finding.missingHours",
            coverage.missing().size(), coverage.expected()), false));
        if (window.scope() == Scope.NEAREST) found.add(new Finding(Level.INFO, Topic.COVERAGE, LocalizedMessage.of("env.finding.nearest",
            window.samples().getFirst().local().toLocalTime().toString()), false));
        light(found, overlaps, golden, true);
        light(found, overlaps, blue, false);
        found.sort(Comparator.comparing(Finding::level).reversed().thenComparing(Finding::topic));
        return List.copyOf(found);
    }

    private static void light(List<Finding> found, List<PhaseOverlap> overlaps, List<TimeInterval> intersections, boolean golden) {
        String name = golden ? "golden" : "blue";
        var phases = overlaps.stream().filter(o -> o.phase().golden() == golden).toList();
        if (phases.isEmpty()) { found.add(new Finding(Level.INFO, Topic.LIGHT, LocalizedMessage.of("env.finding." + name + "Absent"), false)); return; }
        Duration total = TimeInterval.total(intersections);
        if (total.isZero()) {
            boolean touches = phases.stream().anyMatch(PhaseOverlap::contact);
            found.add(new Finding(Level.INFO, Topic.LIGHT, LocalizedMessage.of("env.finding." + name + (touches ? "Touch" : "None")), false));
        } else {
            boolean complete = phases.stream().filter(o -> o.overlap().isPresent() && !o.contact()).allMatch(PhaseOverlap::complete);
            found.add(new Finding(Level.INFO, Topic.LIGHT, LocalizedMessage.of("env.finding." + name + (complete ? "Full" : "Partial"), total.toMinutes()), false));
        }
    }

    /** Validated AI windows starting with an arrival before their start: the user's preparation interval for each. */
    public static List<TimeInterval> preparation(EnvironmentAssessment assessment) {
        return assessment.windows().stream().filter(w -> w.arrival().isBefore(w.start()))
            .map(w -> new TimeInterval(w.arrival().toInstant(), w.start().toInstant())).toList();
    }

    /** Phases on the dates of the assessment, grouped by kind name for presentation order. */
    public static List<LightPhase> phases(EnvironmentAnalysis analysis, String kind) {
        return analysis.overlaps().stream().map(PhaseOverlap::phase).filter(p -> p.kind().equals(kind)).toList();
    }
}
