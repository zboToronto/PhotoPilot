package com.photopilot.service;

import java.time.*;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.EnvironmentProvider;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentAssessment.Interpretation;
import com.photopilot.model.EnvironmentAssessment.LightPhase;
import com.photopilot.model.EnvironmentAssessment.RejectedWindow;
import com.photopilot.model.EnvironmentContext.Hour;
import com.photopilot.validation.EnvironmentEvidence;
import com.photopilot.validation.FeasibilityValidator;

/**
 * Stage 1 UML element 79 (zero-based): {F09; factual retrieval independent of AI interpretation}
 * {@link #retrieve} returns provider facts only (with an in-memory reuse cache bounded by type-specific freshness).
 * {@link #assess} resolves the user's availability in the verified location zone, computes light phases, sends the
 * verified evidence to Gemini for photographic interpretation and hands every proposal to
 * {@link FeasibilityValidator#checkTiming}. When Gemini fails, the factual evidence is still returned, labelled.
 * Nothing is persisted.
 */
public class EnvironmentService {
    /** Short-range forecasts are revised by the provider several times a day. */
    public static final Duration WEATHER_MAX_AGE = Duration.ofHours(3);
    /** Sun events are astronomical predictions for a fixed date and place; they do not change as time passes. */
    public static final Duration SUN_MAX_AGE = Duration.ofDays(30);
    /** Forecast lead time beyond which weather reliability is explicitly flagged as lower. */
    public static final int LONG_RANGE_DAYS = 7;
    public static final int MAX_WINDOWS = 4, MAX_CONSIDERATIONS = 6, MAX_ALTERNATIVES = 3, MAX_SUMMARY = 1200, MAX_STATEMENT = 400;
    /** Deterministic adverse-condition thresholds (documented, not AI judgement). */
    public static final double STRONG_GUST_KMH = 50, LOW_VISIBILITY_M = 1000, LIKELY_PRECIPITATION_PCT = 70;
    private static final int CACHE_SIZE = 16;
    static final String TASK = """
        You interpret verified environmental evidence for a photography session in the PhotoPilot planning app.
        The application evidence contains provider weather forecasts, provider sun times, computed light-phase estimates,
        the shoot context and the user's fixed availability period. Reply only with JSON that matches the response schema.
        Rules:
        1. Use only the evidence. Never invent or estimate weather values, sun times, travel times, distances or crowd levels.
           When you mention a measurement, quote an evidence value with its unit exactly; otherwise describe it in words.
        2. Propose zero to four shooting windows. Every window must lie completely inside the availability period. The
           availability period is a fixed user constraint: never extend, move or reinterpret it.
        3. Write start and end as local date-time with the UTC offset exactly as shown in the evidence
           (for example 2026-11-07T15:30-05:00).
        4. light is the dominant light in the window: daylight (between sunrise and sunset), goldenHour or blueHour
           (overlapping a computed phase), twilight (civil twilight), night (after civil dusk or before civil dawn), or mixed.
        5. evidence lists the ids of the evidence items the window relies on (for example "sunset", "goldenEvening",
           "h-2026-11-07T15:00"). Cite at least one id and use only ids that appear in the evidence.
        6. Do not propose arrival times or preparation durations; the application computes arrival from the user's settings.
        7. Fit the windows to the subject and goals (for example light direction for aircraft, darkness for night skies).
           If the evidence makes conditions unfavourable, say so in considerations and offer alternatives inside the
           availability period. If no window is defensible, return no windows and explain why in summary.
        8. Evidence marked unavailable or stale must not be relied on; mention the uncertainty instead.
        """;

    private EnvironmentProvider provider;
    private LocationService location;
    private PhotoPilotAgent agent;
    private FeasibilityValidator validator;
    private final Clock clock;
    private final Map<String, EnvironmentContext> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, EnvironmentContext> eldest) { return size() > CACHE_SIZE; }
    };

    public EnvironmentService(EnvironmentProvider provider, LocationService location, PhotoPilotAgent agent, FeasibilityValidator validator) {
        this(provider, location, agent, validator, Clock.systemUTC());
    }

    public EnvironmentService(EnvironmentProvider provider, LocationService location, PhotoPilotAgent agent, FeasibilityValidator validator, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.location = Objects.requireNonNull(location, "location");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocationService location() { return location; }

    public Result<EnvironmentContext> retrieve(GeoLocation place, LocalDate date) {
        return retrieve(place, date, date);
    }

    /** Provider facts for [from, to]; a cached context is reused only while none of its evidence is stale. */
    public Result<EnvironmentContext> retrieve(GeoLocation place, LocalDate from, LocalDate to) {
        if (place == null || place.latitude().isEmpty() || place.longitude().isEmpty()) return Result.failureKey("env.location.error.coordinates");
        if (!place.validate().isSuccess()) return Result.failureKey("env.location.error.invalid");
        if (from == null || to == null) return Result.failureKey("env.error.date");
        String key = place.latitude().get() + "," + place.longitude().get() + "," + from + "," + to + "," + place.label();
        synchronized (cache) {
            var cached = cache.get(key);
            if (cached != null && staleFields(cached).isEmpty()) { com.photopilot.progress.Progress.stage("env.cached"); return Result.success(cached); }
            cache.remove(key);
        }
        var fetched = provider.fetch(place, from, to);
        if (fetched == null) return Result.failureKey("env.error.noData");
        if (fetched.isSuccess()) synchronized (cache) { cache.put(key, fetched.value().orElseThrow()); }
        return fetched;
    }

    /** Stale evidence groups under the type-specific limits (forecast vs astronomical). */
    public Set<String> staleFields(EnvironmentContext context) {
        Instant now = clock.instant();
        var stale = new TreeSet<String>();
        context.staleFields(now, WEATHER_MAX_AGE).stream().filter(f -> !f.equals(EnvironmentContext.SUN)).forEach(stale::add);
        context.staleFields(now, SUN_MAX_AGE).stream().filter(f -> f.equals(EnvironmentContext.SUN)).forEach(stale::add);
        return Collections.unmodifiableSet(stale);
    }

    /**
     * F06/F10 use of an earlier assessment, evaluated now with this service's clock and the same type-specific limits
     * as retrieval ({@link #staleFields}); the assessment's own {@code staleFields()} is only the state at assessment
     * time. A group that has data but no retrieval time cannot be shown to be fresh and counts as stale.
     * {@code basis} is the confirmed requirement this process assessed under (empty when unknown).
     */
    public EnvironmentEvidence evidence(EnvironmentAssessment assessment, ShootRequirement requirement, LocalDate shootingDate,
            Optional<EnvironmentEvidence.Basis> basis) {
        if (assessment == null) return EnvironmentEvidence.none();
        var context = assessment.context();
        var stale = new TreeSet<>(staleFields(context));
        var stamps = context.sourceTimestamps();
        if (!context.weather().isEmpty() && !stamps.containsKey(EnvironmentContext.DAILY)) stale.add(EnvironmentContext.DAILY);
        if (!context.hourly().isEmpty() && !stamps.containsKey(EnvironmentContext.HOURLY)) stale.add(EnvironmentContext.HOURLY);
        if (!context.sunEvents().isEmpty() && !stamps.containsKey(EnvironmentContext.SUN)) stale.add(EnvironmentContext.SUN);
        return EnvironmentEvidence.check(assessment, requirement, shootingDate, basis, stale, clock.instant());
    }

    /**
     * Stage 1 operation: uses only the confirmed requirement's own context. It succeeds only when that requirement
     * already has a location with coordinates and a clock start time; otherwise the missing inputs are named.
     */
    public Result<EnvironmentAssessment> assess(ShootRequirement req) {
        if (req == null || !req.confirmed()) return Result.failureKey("requirement.error.none");
        var missing = new ArrayList<String>();
        var place = req.location().filter(p -> p.latitude().isPresent());
        if (place.isEmpty()) missing.add("location coordinates");
        if (req.time().isEmpty()) missing.add("start time");
        if (req.duration().isEmpty()) missing.add("duration");
        if (!missing.isEmpty()) return Result.failureKey("env.error.needsInput", String.join(", ", missing));
        LocalDateTime start = req.time().get().toLocalDateTime();
        LocalDateTime end = start.plus(req.duration().get());
        if (Duration.between(start, end).compareTo(Duration.ofHours(24)) >= 0) return Result.failureKey("env.error.needsInput", "duration under 24 hours");
        return assess(req, new EnvironmentRequest(place.get(), start.toLocalDate(), start.toLocalTime(), end.toLocalTime(),
            Optional.empty(), Optional.empty(), "", List.of()));
    }

    /**
     * F09 assessment for reviewed inputs. {@code req} is the confirmed F03 requirement or null; it is read, never
     * changed. Fails only when no defensible factual basis exists (no data, unverified zone, invalid time).
     */
    public Result<EnvironmentAssessment> assess(ShootRequirement req, EnvironmentRequest request) {
        return assess(req, request, null);
    }

    /**
     * {@link #assess(ShootRequirement, EnvironmentRequest)} that hands the factual snapshot (provider data, resolved
     * availability, computed phases and notices; no interpretation yet) to {@code onFacts} on the calling thread just
     * before Gemini is asked, so an interface can show it while the interpretation is pending. The snapshot is not the
     * result: only the returned assessment is validated and remembered.
     */
    public Result<EnvironmentAssessment> assess(ShootRequirement req, EnvironmentRequest request, java.util.function.Consumer<EnvironmentAssessment> onFacts) {
        if (request == null) return Result.failureKey("env.error.time");
        var valid = request.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        ShootRequirement requirement = req != null && req.confirmed() ? req : null;
        String subject = requirement != null ? requirement.subject() : request.activity();
        if (subject.isBlank()) return Result.failureKey("env.error.subject");

        var retrieved = retrieve(request.place(), request.date(), request.endDate());
        if (!retrieved.isSuccess()) return Result.failure(retrieved);
        EnvironmentContext context = retrieved.value().orElseThrow();
        com.photopilot.progress.Progress.validating("env.zoneCheck");
        var notices = new ArrayList<>(context.notices());
        if (request.accepted().filter(PlaceCandidate::broaderArea).isPresent()) notices.add(LocalizedMessage.of("env.resolve.broader"));
        if (request.aiLocation()) notices.add(LocalizedMessage.of("env.resolve.limitation"));
        ZoneId zone;
        if (context.zone().isPresent()) {
            zone = context.zone().get();
            if (request.zoneEntered() || !request.aiLocation()) {
                request.zoneHint().filter(hint -> !hint.getRules().equals(zone.getRules())).ifPresent(hint -> notices.add(
                    LocalizedMessage.of(request.zoneEntered() ? "env.notice.zoneMismatchEntered" : "env.notice.zoneMismatch", hint.getId(), zone.getId())));
            }
            request.accepted().filter(PlaceCandidate::estimated).flatMap(PlaceCandidate::zone)
                .filter(hint -> !hint.getRules().equals(zone.getRules()))
                .ifPresent(hint -> notices.add(LocalizedMessage.of("env.resolve.zoneMismatch", hint.getId(), zone.getId())));
        } else if (request.zoneHint().isPresent()) {
            zone = request.zoneHint().get();
            // C16: a zone the user entered is used only if it is plausible for the coordinates; its source is stated.
            if (request.zoneEntered() || request.aiLocation()) {
                var implausible = implausibleZone(zone, request.place().longitude().orElseThrow(), request.date());
                if (implausible.isPresent()) return Result.failureKey("env.error.zoneImplausible", zone.getId(), implausible.get());
            }
            notices.add(LocalizedMessage.of(request.zoneEntered() ? "env.notice.zoneFromUser" : request.aiLocation() ? "env.resolve.zoneAi" : "env.notice.zoneFromLookup", zone.getId()));
        } else return Result.failureKey("env.error.zone");

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (request.date().isBefore(today)) return Result.failureKey("env.error.pastDate", request.date().toString());
        var resolved = validator.resolveAvailability(request, zone);
        if (!resolved.isSuccess()) return Result.failure(resolved);
        Availability availability = resolved.value().orElseThrow().availability();
        notices.addAll(resolved.value().orElseThrow().notices());
        if (!availability.end().toInstant().isAfter(now)) return Result.failureKey("env.error.availabilityOver");
        if (availability.start().toInstant().isBefore(now)) notices.add(LocalizedMessage.of("env.notice.partlyPast"));
        long lead = Duration.between(today.atStartOfDay(), request.date().atStartOfDay()).toDays();
        if (lead > LONG_RANGE_DAYS && context.hasWeather()) notices.add(LocalizedMessage.of("env.notice.longRange", lead));
        if (requirement != null) consistency(requirement, request, availability, notices);

        Set<String> stale = staleFields(context);
        if (!stale.isEmpty()) notices.add(LocalizedMessage.of("env.notice.stale", String.join(", ", stale)));
        double lat = request.place().latitude().get(), lon = request.place().longitude().get();
        com.photopilot.progress.Progress.stage("env.solar");
        List<LightPhase> phases = SolarCalculator.phases(lat, lon, zone, request.date(), request.endDate());
        modelCheck(context, lat, lon, zone, notices);
        var hours = context.hours(availability.start().toInstant(), availability.end().toInstant());
        indicators(hours, notices);

        var base = new EnvironmentAssessment(context, request, subject, Optional.of(availability), phases, List.of(), List.of(),
            Optional.empty(), notices, stale, now);
        if (!sufficient(base)) {
            return validator.checkTiming(base.withNotices(List.of(LocalizedMessage.of("env.notice.insufficient"))));
        }
        if (onFacts != null) onFacts.accept(base);
        return interpret(requirement, base);
    }

    /** Hourly weather for the availability or sun times for the date: without either, nothing is interpreted. */
    private static boolean sufficient(EnvironmentAssessment base) {
        var context = base.context(); var request = base.request().orElseThrow(); var availability = base.availability().orElseThrow();
        boolean sunKnown = context.sunEvent(request.date(), "sunrise").isPresent() || context.sunEvent(request.date(), "sunset").isPresent();
        return sunKnown || !context.hours(availability.start().toInstant(), availability.end().toInstant()).isEmpty();
    }

    /**
     * The Gemini interpretation of a factual snapshot and the deterministic validation of every proposal. It is the
     * only step that contacts Gemini; when Gemini fails the snapshot is returned with the reason as a notice.
     */
    private Result<EnvironmentAssessment> interpret(ShootRequirement requirement, EnvironmentAssessment base) {
        ZoneId zone = base.availability().orElseThrow().start().getZone();
        var prompt = new AgentContext(UUID.randomUUID(), List.of("Assess shooting windows for this photography session."),
            evidence(base, requirement, base.lightPhases(), staleFields(base.context())), List.of(), Optional.empty()).withResponseSchema(EnvironmentInterpretation.SCHEMA);
        var reply = agent.run(TASK, prompt, EnvironmentInterpretation.class);
        if (!reply.isSuccess()) {
            var reason = reply.messages().isEmpty() ? LocalizedMessage.of("env.ai.invalid") : reply.messages().getFirst();
            // The retrieved facts and local estimates are still the result; only the AI interpretation is missing.
            com.photopilot.progress.Progress.stage("env.factsOnly");
            return validator.checkTiming(base.withNotices(List.of(LocalizedMessage.of("env.notice.aiUnavailable", reason))));
        }
        com.photopilot.progress.Progress.validating("env.validate");
        return validator.checkTiming(convert(reply.value().orElseThrow(), base, zone));
    }

    /**
     * AI-only retry: interprets the factual snapshot of {@code previous} again (same provider data, retrieval times,
     * resolved availability and computed phases) without contacting any location, weather or sun provider. Refused,
     * with the reason, when the evidence is no longer fresh, the availability has ended, the snapshot never had enough
     * evidence to interpret, or it is not an F09 assessment. The interpretation, proposals and AI notices are replaced;
     * every other notice and {@code assessedAt} (the time the facts were assessed) are kept, and a notice records the
     * retry. {@code req} must be the confirmed requirement the snapshot was made under (the caller checks this).
     */
    public Result<EnvironmentAssessment> reinterpret(ShootRequirement req, EnvironmentAssessment previous) {
        var refusal = reinterpretRefusal(previous);
        if (refusal.isPresent()) return Result.failure(refusal.get());
        var notices = previous.notices().stream().filter(n -> !EnvironmentAnalyzer.AI_NOTICES.contains(n.key())).toList();
        var base = new EnvironmentAssessment(previous.context(), previous.request().get(), previous.subject(), previous.availability(), previous.lightPhases(),
            List.of(), List.of(), Optional.empty(), notices, previous.staleFields(), previous.assessedAt());
        if (!sufficient(base)) return Result.failureKey("env.retry.skipped");
        ZoneId zone = previous.availability().get().start().getZone();
        var retried = base.withNotices(List.of(LocalizedMessage.of("env.notice.aiRetried", clock.instant().atZone(zone).toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString(),
            previous.context().retrievedAt().atZone(zone).toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString())));
        return interpret(req != null && req.confirmed() ? req : null, retried);
    }

    /** Why the facts of {@code previous} cannot be interpreted again now (with this service's clock), or empty. */
    public Optional<LocalizedMessage> reinterpretRefusal(EnvironmentAssessment previous) {
        if (previous == null || previous.request().isEmpty() || previous.availability().isEmpty()) return Optional.of(LocalizedMessage.of("env.retry.none"));
        var stale = staleFields(previous.context());
        if (!stale.isEmpty()) return Optional.of(LocalizedMessage.of("env.retry.stale", String.join(", ", stale)));
        if (!previous.availability().get().end().toInstant().isAfter(clock.instant())) return Optional.of(LocalizedMessage.of("env.error.availabilityOver"));
        return Optional.empty();
    }

    /** Untrusted reply -> candidate windows (parse failures rejected immediately) and a filtered interpretation. */
    private EnvironmentAssessment convert(EnvironmentInterpretation reply, EnvironmentAssessment base, ZoneId zone) {
        var request = base.request().orElseThrow();
        Availability availability = base.availability().orElseThrow();
        var candidates = new ArrayList<ShootingWindow>();
        var rejected = new ArrayList<RejectedWindow>();
        var notices = new ArrayList<LocalizedMessage>();
        var proposed = reply.windows() == null ? List.<EnvironmentInterpretation.Window>of() : reply.windows();
        for (int i = 0; i < proposed.size(); i++) {
            var w = proposed.get(i);
            if (w == null) { rejected.add(new RejectedWindow("", "", "", List.of(LocalizedMessage.of("env.reject.format", "")))); continue; }
            String label = text(w.label());
            if (i >= MAX_WINDOWS) { rejected.add(new RejectedWindow(label, text(w.start()), text(w.end()), List.of(LocalizedMessage.of("env.reject.tooMany", MAX_WINDOWS)))); continue; }
            var reasons = new ArrayList<LocalizedMessage>();
            var start = FeasibilityValidator.parseProposed(w.start(), zone);
            var end = FeasibilityValidator.parseProposed(w.end(), zone);
            if (!start.isSuccess()) reasons.addAll(start.messages());
            if (!end.isSuccess()) reasons.addAll(end.messages());
            if (start.isSuccess() && end.isSuccess() && !end.value().get().isAfter(start.value().get())) reasons.add(LocalizedMessage.of("env.reject.order"));
            String light = text(w.light());
            if (!ShootingWindow.LIGHT.contains(light)) reasons.add(LocalizedMessage.of("env.reject.lightCategory", light));
            String conditions = text(w.conditions()), rationale = text(w.rationale());
            if (label.isEmpty() || label.length() > 120 || conditions.length() > ShootingWindow.MAX_TEXT || rationale.length() > ShootingWindow.MAX_TEXT)
                reasons.add(LocalizedMessage.of("env.reject.text"));
            if (!reasons.isEmpty()) { rejected.add(new RejectedWindow(label, text(w.start()), text(w.end()), reasons)); continue; }
            ZonedDateTime from = start.value().get(), to = end.value().get();
            var notes = new ArrayList<LocalizedMessage>();
            ZonedDateTime arrival = from;
            if (request.leadTime().isPresent() && !request.leadTime().get().isZero()) {
                arrival = from.minus(request.leadTime().get());
                if (arrival.isBefore(availability.start())) {
                    arrival = availability.start().isAfter(from) ? from : availability.start();
                    notes.add(LocalizedMessage.of("env.window.leadShortened", request.leadTime().get().toMinutes()));
                }
            }
            var evidence = w.evidence() == null ? List.<String>of() : w.evidence().stream().filter(Objects::nonNull).map(String::strip)
                .filter(id -> !id.isEmpty()).distinct().limit(16).toList();
            candidates.add(new ShootingWindow(from, to, arrival, conditions, true, label, light, rationale, evidence, notes));
        }
        int dropped = 0;
        String summary = text(reply.summary());
        if (summary.length() > MAX_SUMMARY || !FeasibilityValidator.unsupportedMeasurements(summary, base.context()).isEmpty()) { summary = ""; dropped++; }
        var considerations = new ArrayList<String>();
        var alternatives = new ArrayList<String>();
        dropped += statements(reply.considerations(), MAX_CONSIDERATIONS, base.context(), considerations);
        dropped += statements(reply.alternatives(), MAX_ALTERNATIVES, base.context(), alternatives);
        if (dropped > 0) notices.add(LocalizedMessage.of("env.notice.statementsDropped", dropped));
        var interpreted = new EnvironmentAssessment(base.context(), request, base.subject(), base.availability(), base.lightPhases(), candidates,
            rejected, Optional.of(new Interpretation(summary, considerations, alternatives)), base.notices(), base.staleFields(), base.assessedAt());
        return interpreted.withNotices(notices);
    }
    private static int statements(List<String> source, int limit, EnvironmentContext context, List<String> into) {
        int dropped = 0;
        for (String raw : source == null ? List.<String>of() : source) {
            String statement = text(raw);
            if (statement.isEmpty()) continue;
            if (into.size() >= limit || statement.length() > MAX_STATEMENT || !FeasibilityValidator.unsupportedMeasurements(statement, context).isEmpty()) dropped++;
            else into.add(statement);
        }
        return dropped;
    }

    /**
     * Confirmed F03 values that disagree with the reviewed F09 inputs are reported; F03 itself is never changed. The
     * date and the wall-clock start time are compared separately ({@link FeasibilityValidator#requirementConflicts}),
     * so a different date is never reported as a start time outside the availability.
     */
    private static void consistency(ShootRequirement requirement, EnvironmentRequest request, Availability availability, List<LocalizedMessage> notices) {
        notices.addAll(FeasibilityValidator.requirementConflicts(requirement, request.date(), availability.start().toLocalTime(),
            availability.end().toLocalTime(), availability.duration()));
    }

    /** Independent check of provider sunrise/sunset against the solar model; a large difference is reported. */
    /**
     * Largest accepted distance between a zone's UTC offset on the date and the coordinates' mean solar time
     * (longitude / 15 h). Real zones stay within about 3 h of it (western China, Spain, Argentina); a wrong zone for
     * the place, such as Asia/Tokyo for Toronto, is many hours off.
     */
    public static final double MAX_SOLAR_DIFFERENCE_HOURS = 3.5;
    /** The rounded difference in hours when an entered zone cannot be the coordinates' local time, else empty. */
    public static Optional<String> implausibleZone(ZoneId zone, double longitude, LocalDate date) {
        double offset = zone.getRules().getOffset(date.atTime(12, 0).atZone(zone).toInstant()).getTotalSeconds() / 3600.0;
        double difference = Math.abs(offset - longitude / 15.0) % 24;
        difference = Math.min(difference, 24 - difference);   // across the date line (Pacific/Kiritimati is UTC+14 at 157° W)
        return difference > MAX_SOLAR_DIFFERENCE_HOURS ? Optional.of(String.format(java.util.Locale.ROOT, "%.0f", difference)) : Optional.empty();
    }

    private static void modelCheck(EnvironmentContext context, double lat, double lon, ZoneId zone, List<LocalizedMessage> notices) {
        LocalDate day = context.date().orElse(null);
        if (day == null) return;
        Instant from = day.atStartOfDay(zone).toInstant(), to = day.plusDays(1).atStartOfDay(zone).toInstant();
        for (var kind : List.of("sunrise", "sunset")) {
            var reported = context.sunEvent(day, kind);
            var computed = SolarCalculator.crossing(lat, lon, from, to, SolarCalculator.SUNRISE_ELEVATION, kind.equals("sunrise"));
            if (reported.isPresent() && computed.isPresent() && Math.abs(Duration.between(reported.get(), computed.get()).toMinutes()) > 5)
                notices.add(LocalizedMessage.of("env.notice.modelMismatch", kind, Math.abs(Duration.between(reported.get(), computed.get()).toMinutes())));
        }
    }

    /** Deterministic adverse-weather indicators within the availability period (WMO codes and documented thresholds). */
    private static void indicators(List<Hour> hours, List<LocalizedMessage> notices) {
        if (hours.stream().anyMatch(h -> h.value("weatherCode").filter(c -> c >= 95).isPresent())) notices.add(LocalizedMessage.of("env.indicator.thunder"));
        if (hours.stream().anyMatch(h -> h.value("weatherCode").filter(c -> c == 45 || c == 48).isPresent())) notices.add(LocalizedMessage.of("env.indicator.fog"));
        if (hours.stream().anyMatch(h -> h.value("weatherCode").filter(c -> c >= 71 && c <= 77 || c == 85 || c == 86).isPresent())) notices.add(LocalizedMessage.of("env.indicator.snow"));
        hours.stream().flatMap(h -> h.value("windGustsKmh").stream()).max(Double::compare).filter(g -> g >= STRONG_GUST_KMH)
            .ifPresent(g -> notices.add(LocalizedMessage.of("env.indicator.gusts", g, STRONG_GUST_KMH)));
        hours.stream().flatMap(h -> h.value("visibilityM").stream()).min(Double::compare).filter(v -> v < LOW_VISIBILITY_M)
            .ifPresent(v -> notices.add(LocalizedMessage.of("env.indicator.visibility", v)));
        hours.stream().flatMap(h -> h.value("precipitationProbabilityPct").stream()).max(Double::compare).filter(p -> p >= LIKELY_PRECIPITATION_PCT)
            .ifPresent(p -> notices.add(LocalizedMessage.of("env.indicator.precipitation", p)));
    }

    /**
     * Evidence for Gemini: subject/goals/constraints, location label and zone (no coordinates), availability, provider
     * sun events, computed phases (labelled estimates), the daily summary and forecast hours around the availability.
     */
    private static String evidence(EnvironmentAssessment base, ShootRequirement requirement, List<LightPhase> phases, Set<String> stale) {
        var context = base.context();
        var request = base.request().orElseThrow();
        Availability availability = base.availability().orElseThrow();
        ZoneId zone = availability.start().getZone();
        var root = new JsonObject();
        var shoot = new JsonObject();
        shoot.addProperty("subject", base.subject());
        var goals = new JsonArray();
        (requirement != null ? requirement.goals() : request.goals()).forEach(goals::add);
        shoot.add("goals", goals);
        if (requirement != null) {
            if (!requirement.timeWindow().isBlank()) shoot.addProperty("timingPreference", requirement.timeWindow());
            requirement.duration().ifPresent(d -> shoot.addProperty("desiredDurationMinutes", d.toMinutes()));
            if (!requirement.priorities().isEmpty()) shoot.addProperty("priorities", requirement.priorities().toString());
            if (!requirement.fixedConstraints().isEmpty()) shoot.addProperty("fixedConstraints", requirement.fixedConstraints().toString());
        }
        root.add("shoot", shoot);
        var where = new JsonObject();
        if (!DestinationQuery.privateInput(request.place().label())) where.addProperty("label", request.place().label());
        where.addProperty("source", request.locationSource().name());
        request.accepted().ifPresent(c -> where.addProperty("geographicScope", c.scope().name()));
        where.addProperty("timeZone", zone.getId());
        root.add("location", where);
        root.addProperty("date", request.date().toString());
        var period = new JsonObject();
        period.addProperty("start", local(availability.start()));
        period.addProperty("end", local(availability.end()));
        period.addProperty("fixedUserConstraint", true);
        root.add("availability", period);
        var items = new JsonArray();
        var index = EnvironmentAssessment.evidenceIndex(context, phases);
        Instant from = availability.start().toInstant().minus(Duration.ofHours(1)), to = availability.end().toInstant().plus(Duration.ofHours(1));
        index.forEach((id, value) -> {
            var item = new JsonObject();
            item.addProperty("id", id);
            if (value instanceof EnvironmentContext.SunEvent event) {
                item.addProperty("type", "sunEvent"); item.addProperty("kind", event.kind());
                item.addProperty("time", local(event.time().atZone(zone))); item.addProperty("source", event.source() + " (provider data)");
            } else if (value instanceof LightPhase phase) {
                item.addProperty("type", "lightPhase"); item.addProperty("kind", phase.kind());
                item.addProperty("start", local(phase.start())); item.addProperty("end", local(phase.end()));
                item.addProperty("source", phase.source());
            } else if (value instanceof Hour hour) {
                if (hour.time().isBefore(from) || hour.time().isAfter(to)) return;
                item.addProperty("type", "forecastHour"); item.addProperty("time", local(hour.time().atZone(zone)));
                hour.values().forEach(item::addProperty);
                item.addProperty("source", "Open-Meteo forecast (provider data)");
            } else if (value instanceof Map<?, ?> daily) {
                item.addProperty("type", "dailyForecastSummary");
                daily.forEach((key, number) -> item.addProperty(key.toString(), (Number) number));
                item.addProperty("source", "Open-Meteo forecast (provider data)");
            }
            items.add(item);
        });
        root.add("evidence", items);
        var units = new JsonObject();
        EnvironmentContext.UNITS.forEach(units::addProperty);
        root.add("units", units);
        var unavailable = new JsonArray();
        context.unavailableFields().forEach(unavailable::add);
        root.add("unavailable", unavailable);
        var staleItems = new JsonArray();
        stale.forEach(staleItems::add);
        root.add("stale", staleItems);
        root.addProperty("retrievedAt", context.retrievedAt().toString());
        return root.toString();
    }
    private static String local(ZonedDateTime time) { return time.toOffsetDateTime().withSecond(0).withNano(0).toString(); }
    private static String text(String value) { return value == null ? "" : value.strip(); }
}
