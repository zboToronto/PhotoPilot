package com.photopilot.validation;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ActivitySuggestion;
import com.photopilot.model.ActivitySuggestion.Check;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.model.DiscoveryContext;
import com.photopilot.model.EnvironmentAssessment;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentAssessment.RejectedWindow;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.EnvironmentRequest;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.GearMatchReport.NeedResult;
import com.photopilot.model.GearMatchReport.NeedStatus;
import com.photopilot.model.Loadout;
import com.photopilot.model.ShootingWindow;

/**
 * Stage 1 UML element 65 (zero-based): {F02/F09; time, distance, gear and consent gates}
 * F09 deterministic time gates: local-time resolution in the verified zone (DST gaps rejected, overlaps resolved
 * conservatively and labelled) and {@link #checkTiming} for AI-proposed windows. A window survives only if it lies
 * inside the user's availability, is consistent with the sun data for its declared light, cites known evidence and
 * quotes no measurement absent from the provider data. Invalid windows are rejected with reasons; valid ones are kept.
 * F02 {@link #validate} checks each AI-proposed activity for time, straight-line travel, owned-equipment, light/weather
 * evidence, avoided subjects and event dependence, reusing the F07 need comparison and the F09 light rules.
 */
public class FeasibilityValidator {
    public static final Duration MIN_WINDOW = Duration.ofMinutes(5), SUN_TOLERANCE = Duration.ofMinutes(2);
    private final Clock clock;

    public FeasibilityValidator() {
        this(Clock.systemUTC());
    }

    public FeasibilityValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * F02: checks one AI-proposed idea against the context and returns a copy carrying every check and the overall
     * status (worst check). FEASIBLE needs positive evidence for every applicable criterion; missing evidence is
     * UNVERIFIED, never favourable; a conflict with a known constraint is INFEASIBLE. Nothing is repaired or contacted.
     */
    public Result<ActivitySuggestion> validate(ActivitySuggestion idea, DiscoveryContext context) {
        if (idea == null) return Result.failureKey("discover.error.idea");
        if (context == null) return Result.failureKey("discover.error.context");
        var structural = idea.validate();
        if (!structural.isSuccess()) return structural;
        var checks = new ArrayList<Check>();
        checks.add(time(idea, context));
        checks.add(travel(idea, context));
        checks.add(equipment(idea, context));
        checks.add(environment(idea, context));
        checks.add(avoid(idea, context));
        if (idea.dependsOnEvent()) checks.add(check("event", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.event")));
        return Result.success(idea.withChecks(checks));
    }

    /** Cloud cover at or above which a clear-sky activity is infeasible throughout (documented threshold). */
    public static final double OVERCAST_PCT = 80;

    private Check time(ActivitySuggestion idea, DiscoveryContext context) {
        if (idea.start().isEmpty()) return check("time", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.time.noStart"));
        ZonedDateTime from = idea.start().get(), to = idea.end().orElseThrow();
        var available = context.availability();
        var found = new ArrayList<LocalizedMessage>();
        if (!from.getZone().equals(available.start().getZone())) found.add(LocalizedMessage.of("discover.check.time.zone", available.start().getZone().getId()));
        if (!available.contains(from, to))
            found.add(LocalizedMessage.of("discover.check.time.outside", clock(from), clock(to), clock(available.start()), clock(available.end())));
        if (idea.estimatedDuration().compareTo(context.maxActivity()) > 0)
            found.add(LocalizedMessage.of("discover.check.time.tooLong", idea.estimatedDuration().toMinutes(), context.maxActivity().toMinutes()));
        if (from.toInstant().isBefore(clock.instant())) found.add(LocalizedMessage.of("discover.check.time.past", clock(from)));
        if (!found.isEmpty()) return new Check("time", Feasibility.INFEASIBLE, found);
        found.add(LocalizedMessage.of("discover.check.time.ok", clock(from), clock(to), idea.estimatedDuration().toMinutes()));
        if (!idea.atUserLocation()) found.add(LocalizedMessage.of("discover.check.time.travelExcluded"));
        return new Check("time", Feasibility.FEASIBLE, found);
    }
    private static String clock(ZonedDateTime time) { return time.toLocalDate() + " " + time.toLocalTime().truncatedTo(ChronoUnit.MINUTES); }

    /** Only straight-line distances to gazetteer matches count; route distance and travel time are never estimated. */
    private static Check travel(ActivitySuggestion idea, DiscoveryContext context) {
        String max = num(context.maxDistanceKm());
        if (idea.atUserLocation()) return check("travel", Feasibility.FEASIBLE, LocalizedMessage.of("discover.check.travel.atLocation", idea.place()));
        if (context.maxDistanceKm() == 0) return check("travel", Feasibility.INFEASIBLE, LocalizedMessage.of("discover.check.travel.noTravel", idea.place()));
        if (!context.hasCoordinates()) return check("travel", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.travel.noCoordinates", idea.place()));
        var evidence = idea.travel();
        if (evidence.isEmpty()) return check("travel", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.travel.notChecked", idea.place()));
        if (evidence.get().problem().isPresent()) return check("travel", Feasibility.UNVERIFIED, evidence.get().problem().get());
        var km = evidence.get().straightLineKm();
        if (km.isEmpty()) return check("travel", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.travel.notChecked", idea.place()));
        double nearest = Collections.min(km), farthest = Collections.max(km);
        if (nearest > context.maxDistanceKm())
            return check("travel", Feasibility.INFEASIBLE, LocalizedMessage.of("discover.check.travel.beyond", idea.place(), num(nearest), max));
        if (farthest <= context.maxDistanceKm())
            return new Check("travel", Feasibility.FEASIBLE, List.of(LocalizedMessage.of("discover.check.travel.within", idea.place(), num(farthest), max),
                LocalizedMessage.of("discover.check.travel.straightLine")));
        return check("travel", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.travel.ambiguous", idea.place(), km.size(), max));
    }

    /** Hard capability needs compared with every UC14-verified owned loadout (F07's deterministic comparison). */
    private static Check equipment(ActivitySuggestion idea, DiscoveryContext context) {
        if (!context.inventoryReadable()) return check("equipment", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.gear.unreadable"));
        if (context.gear().items().isEmpty()) return check("equipment", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.gear.empty"));
        var loadouts = context.usableLoadouts();
        if (loadouts.isEmpty()) return check("equipment", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.gear.noLoadout"));
        if (idea.gearNeeds().isEmpty()) return check("equipment", Feasibility.FEASIBLE, LocalizedMessage.of("discover.check.gear.noNeeds", loadouts.size()));
        var profile = new EquipmentRequirementProfile(idea.gearNeeds(), false);
        List<NeedResult> unknownOnly = null, fewestUnmet = null;
        for (Loadout loadout : loadouts) {
            var results = CompatibilityValidator.evaluateNeeds(loadout, profile);
            long unmet = results.stream().filter(r -> r.status() == NeedStatus.UNMET).count();
            if (results.stream().allMatch(r -> r.status() == NeedStatus.MET)) {
                var found = new ArrayList<LocalizedMessage>();
                found.add(LocalizedMessage.of("discover.check.gear.met", bodiesAndLenses(loadout)));
                results.forEach(r -> found.add(r.detail()));
                return new Check("equipment", Feasibility.FEASIBLE, found);
            }
            if (unmet == 0 && unknownOnly == null) unknownOnly = results;
            if (fewestUnmet == null || unmet < fewestUnmet.stream().filter(r -> r.status() == NeedStatus.UNMET).count()) fewestUnmet = results;
        }
        var found = new ArrayList<LocalizedMessage>();
        if (unknownOnly != null) {
            found.add(LocalizedMessage.of("discover.check.gear.unknown"));
            unknownOnly.stream().filter(r -> r.status() == NeedStatus.UNKNOWN).forEach(r -> found.add(r.detail()));
            return new Check("equipment", Feasibility.UNVERIFIED, found);
        }
        found.add(LocalizedMessage.of("discover.check.gear.unmet", loadouts.size()));
        fewestUnmet.stream().filter(r -> r.status() == NeedStatus.UNMET).forEach(r -> found.add(r.detail()));
        return new Check("equipment", Feasibility.INFEASIBLE, found);
    }
    private static String bodiesAndLenses(Loadout loadout) {
        return String.join(" + ", loadout.gear().stream().filter(g -> g.type().equals("CAMERA") || g.type().equals("LENS"))
            .map(g -> (g.selectedQuantity() > 1 ? g.selectedQuantity() + " x " : "") + CompatibilityValidator.name(g)).toList());
    }

    /** Sun-dependent light is checked like F09 windows; outdoor ideas need current forecast hours covering them. */
    private Check environment(ActivitySuggestion idea, DiscoveryContext context) {
        boolean lightDependent = !Set.of("any", "mixed", "").contains(idea.light());
        if (!idea.outdoor() && !lightDependent) return check("environment", Feasibility.FEASIBLE, LocalizedMessage.of("discover.check.env.notApplicable"));
        if (context.environment().isEmpty())
            return check("environment", Feasibility.UNVERIFIED, LocalizedMessage.of(context.hasCoordinates() ? "discover.check.env.unavailable" : "discover.check.env.noCoordinates"));
        if (idea.start().isEmpty()) return check("environment", Feasibility.UNVERIFIED, LocalizedMessage.of("discover.check.time.noStart"));
        var env = context.environment().get();
        ZonedDateTime from = idea.start().get(), to = idea.end().orElseThrow();
        var found = new ArrayList<LocalizedMessage>();
        var status = Feasibility.FEASIBLE;
        if (lightDependent) {
            if (context.staleFields().contains(EnvironmentContext.SUN)) {
                found.add(LocalizedMessage.of("discover.check.env.stale", EnvironmentContext.SUN)); status = worse(status, Feasibility.UNVERIFIED);
            } else {
                var window = new ShootingWindow(from, to, from, "", true, idea.subject(), idea.light(), "", List.of(), List.of());
                var place = context.location().orElseThrow();
                var request = new EnvironmentRequest(place, context.start().toLocalDate(), context.start().toLocalTime(), context.end().toLocalTime(),
                    Optional.empty(), Optional.empty(), "", List.of());
                var assessment = new EnvironmentAssessment(env, request, idea.subject(), Optional.of(context.availability()), context.lightPhases(),
                    List.of(), List.of(), Optional.empty(), List.of(), context.staleFields(), clock.instant());
                var problem = lightProblem(window, assessment);
                if (problem.isEmpty()) found.add(LocalizedMessage.of("discover.check.env.lightOk", LocalizedMessage.of("discover.light." + idea.light())));
                else if (problem.get().key().equals("env.reject.light")) {
                    found.add(LocalizedMessage.of("discover.check.env.lightConflict", LocalizedMessage.of("discover.light." + idea.light()))); status = Feasibility.INFEASIBLE;
                } else { found.add(LocalizedMessage.of("discover.check.env.lightUnverified", LocalizedMessage.of("discover.light." + idea.light()))); status = worse(status, Feasibility.UNVERIFIED); }
            }
        }
        if (idea.outdoor()) {
            var hours = env.hours(from.toInstant().truncatedTo(ChronoUnit.HOURS), to.toInstant());
            if (context.staleFields().contains(EnvironmentContext.HOURLY)) {
                found.add(LocalizedMessage.of("discover.check.env.stale", EnvironmentContext.HOURLY)); status = worse(status, Feasibility.UNVERIFIED);
            } else if (hours.isEmpty()) {
                found.add(LocalizedMessage.of("discover.check.env.noForecast", from.toLocalDate().toString())); status = worse(status, Feasibility.UNVERIFIED);
            } else {
                found.add(LocalizedMessage.of("discover.check.env.forecast", hours.size(), clock(env.retrievedAt().atZone(from.getZone())) + " " + from.getZone().getId()));
                var clouds = hours.stream().flatMap(h -> h.value("cloudCoverPct").stream()).toList();
                if (idea.needsClearSky()) {
                    if (clouds.size() < hours.size()) { found.add(LocalizedMessage.of("discover.check.env.cloudUnknown")); status = worse(status, Feasibility.UNVERIFIED); }
                    else if (clouds.stream().allMatch(c -> c >= OVERCAST_PCT)) {
                        found.add(LocalizedMessage.of("discover.check.env.overcast", num(Collections.min(clouds)), num(Collections.max(clouds)), num(OVERCAST_PCT)));
                        status = Feasibility.INFEASIBLE;
                    } else found.add(LocalizedMessage.of("discover.check.env.cloud", num(Collections.min(clouds)), num(Collections.max(clouds))));
                }
                warnings(hours, found);
            }
        }
        return new Check("environment", status, found);
    }
    /** Deterministic adverse-forecast warnings (same thresholds as F09); they lower the ranking but do not decide feasibility. */
    private static void warnings(List<EnvironmentContext.Hour> hours, List<LocalizedMessage> found) {
        if (hours.stream().anyMatch(h -> h.value("weatherCode").filter(c -> c >= 95).isPresent())) found.add(LocalizedMessage.of("discover.check.env.warn.thunder"));
        hours.stream().flatMap(h -> h.value("precipitationProbabilityPct").stream()).max(Double::compare)
            .filter(p -> p >= com.photopilot.service.EnvironmentService.LIKELY_PRECIPITATION_PCT)
            .ifPresent(p -> found.add(LocalizedMessage.of("discover.check.env.warn.precipitation", num(p))));
        hours.stream().flatMap(h -> h.value("windGustsKmh").stream()).max(Double::compare)
            .filter(g -> g >= com.photopilot.service.EnvironmentService.STRONG_GUST_KMH)
            .ifPresent(g -> found.add(LocalizedMessage.of("discover.check.env.warn.gusts", num(g))));
        hours.stream().flatMap(h -> h.value("visibilityM").stream()).min(Double::compare)
            .filter(v -> v < com.photopilot.service.EnvironmentService.LOW_VISIBILITY_M)
            .ifPresent(v -> found.add(LocalizedMessage.of("discover.check.env.warn.visibility", num(v))));
    }

    private static Check avoid(ActivitySuggestion idea, DiscoveryContext context) {
        if (context.avoid().isEmpty()) return check("avoid", Feasibility.FEASIBLE, LocalizedMessage.of("discover.check.avoid.none"));
        String text = (idea.subject() + " " + idea.genre() + " " + idea.setting() + " " + idea.place()).toLowerCase(Locale.ROOT);
        var conflicts = context.avoid().stream().filter(term -> term.length() >= 2 && text.contains(term.toLowerCase(Locale.ROOT))).toList();
        return conflicts.isEmpty() ? check("avoid", Feasibility.FEASIBLE, LocalizedMessage.of("discover.check.avoid.ok"))
            : check("avoid", Feasibility.INFEASIBLE, LocalizedMessage.of("discover.check.avoid.conflict", String.join(", ", conflicts)));
    }

    private static Check check(String criterion, Feasibility status, LocalizedMessage finding) { return new Check(criterion, status, List.of(finding)); }
    private static Feasibility worse(Feasibility a, Feasibility b) { return a.ordinal() >= b.ordinal() ? a : b; }
    private static String num(double value) { return CompatibilityValidator.num(Math.round(value * 10) / 10.0); }

    /** Great-circle (straight-line) distance in km; never a route distance. */
    public static double straightLineKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Distances and travel times, which no evidence ever contains; e.g. "15 km", "20-minute drive", "两小时车程". */
    private static final Pattern TRAVEL = Pattern.compile(
        "(?iu)\\d+(?:\\.\\d+)?\\s*(?:[-–]\\s*\\d+(?:\\.\\d+)?\\s*)?(?:km|kilomet(?:er|re)s?|mi|miles?|公里|千米|英里)(?![\\p{L}/])"
        + "|\\d+\\s*(?:[-–]\\s*\\d+\\s*)?[- ]?(?:min(?:ute)?s?|h(?:ou)?rs?|hours?|分钟|小时)\\s*(?:drive|walk|ride|away|by car|by transit|车程|步行|路程)"
        + "|(?:drive|walk|ride)\\s+of\\s+\\d+");
    private static final Pattern FOCAL = Pattern.compile("(?i)\\d+(?:\\.\\d+)?(?:\\s*[-–]\\s*\\d+(?:\\.\\d+)?)?\\s*mm");
    private static final EnvironmentContext NO_EVIDENCE = new EnvironmentContext(Map.of(), Optional.empty(), Optional.empty(), Map.of(), Map.of(), Set.of(), Instant.EPOCH);

    /**
     * F02 AI text check: distances and travel times are always unverified; other measurements must match provider
     * values (focal lengths in mm are equipment values and are excluded). Empty when the text quotes nothing unverified.
     */
    public static List<String> unverifiedFigures(String text, Optional<EnvironmentContext> evidence) {
        if (text == null || text.isBlank()) return List.of();
        var found = new LinkedHashSet<String>();
        var travel = TRAVEL.matcher(text);
        while (travel.find()) found.add(travel.group().strip());
        String masked = TRAVEL.matcher(FOCAL.matcher(text).replaceAll(" ")).replaceAll(" ");
        found.addAll(unsupportedMeasurements(masked, evidence.orElse(NO_EVIDENCE)));
        return List.copyOf(found);
    }

    /** The user's on-site period in the location zone, plus labels for any ambiguous local time. */
    public record ResolvedAvailability(Availability availability, List<LocalizedMessage> notices) {
        public ResolvedAvailability { notices = List.copyOf(notices); }
    }

    /**
     * Resolves local wall-clock availability in {@code zone}. A time that does not exist (DST gap) is rejected. A time
     * that occurs twice (DST overlap) is resolved so the period can only shrink (later occurrence for the start,
     * earlier for the end) and labelled, so the user's constraint is never widened silently.
     */
    public Result<ResolvedAvailability> resolveAvailability(EnvironmentRequest request, ZoneId zone) {
        if (request == null) return Result.failureKey("env.error.time");
        if (zone == null) return Result.failureKey("env.error.zone");
        var valid = request.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        var notes = new ArrayList<LocalizedMessage>();
        var start = resolveLocal(LocalDateTime.of(request.date(), request.availableFrom()), zone, true, notes);
        if (!start.isSuccess()) return Result.failure(start);
        var end = resolveLocal(LocalDateTime.of(request.endDate(), request.availableUntil()), zone, false, notes);
        if (!end.isSuccess()) return Result.failure(end);
        if (!end.value().orElseThrow().isAfter(start.value().orElseThrow())) return Result.failureKey("env.error.emptyAvailability");
        return Result.success(new ResolvedAvailability(new Availability(start.value().orElseThrow(), end.value().orElseThrow()), notes));
    }

    public static Result<ZonedDateTime> resolveLocal(LocalDateTime local, ZoneId zone, boolean laterOnOverlap, List<LocalizedMessage> notes) {
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty())
            return Result.failureKey("env.error.timeGap", local.toLocalTime().toString(), local.toLocalDate().toString(), zone.getId());
        ZonedDateTime resolved = ZonedDateTime.of(local, zone);
        if (offsets.size() > 1) {
            resolved = laterOnOverlap ? resolved.withLaterOffsetAtOverlap() : resolved.withEarlierOffsetAtOverlap();
            notes.add(LocalizedMessage.of("env.notice.timeAmbiguous", local.toLocalTime().toString(), local.toLocalDate().toString(),
                resolved.getOffset().getId(), zone.getId()));
        }
        return Result.success(resolved);
    }

    /**
     * A model-proposed local time. With an offset, it must be a valid offset for that local time in the zone;
     * without one, nonexistent and ambiguous local times are rejected rather than guessed.
     */
    public static Result<ZonedDateTime> parseProposed(String text, ZoneId zone) {
        String value = Objects.toString(text, "").strip();
        if (value.isEmpty() || value.length() > 40) return Result.failureKey("env.reject.format", value);
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            LocalDateTime local = parsed.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
            if (!zone.getRules().isValidOffset(local, parsed.getOffset())) return Result.failureKey("env.reject.offset", value, zone.getId());
            return Result.success(ZonedDateTime.ofStrict(local, parsed.getOffset(), zone));
        } catch (DateTimeParseException withoutOffset) {
            try {
                LocalDateTime local = LocalDateTime.parse(value).truncatedTo(ChronoUnit.MINUTES);
                int offsets = zone.getRules().getValidOffsets(local).size();
                if (offsets == 0) return Result.failureKey("env.reject.gap", value, zone.getId());
                if (offsets > 1) return Result.failureKey("env.reject.ambiguous", value, zone.getId());
                return Result.success(ZonedDateTime.of(local, zone));
            } catch (DateTimeParseException unreadable) {
                return Result.failureKey("env.reject.format", value);
            }
        } catch (DateTimeException invalid) {
            return Result.failureKey("env.reject.offset", value, zone.getId());
        }
    }

    /** Validates every proposed window; never repairs one. Rejections carry all their reasons. */
    public Result<EnvironmentAssessment> checkTiming(EnvironmentAssessment assessment) {
        if (assessment == null) return Result.failureKey("env.error.noAssessment");
        var accepted = new ArrayList<ShootingWindow>();
        var rejected = new ArrayList<>(assessment.rejected());
        Set<String> known = assessment.evidenceIds();
        Instant now = clock.instant();
        for (ShootingWindow window : assessment.windows()) {
            var reasons = reasons(window, assessment, known, accepted, now);
            if (reasons.isEmpty()) accepted.add(window);
            else rejected.add(new RejectedWindow(window.label(), window.start().toString(), window.end().toString(), reasons));
        }
        accepted.sort(Comparator.comparing(ShootingWindow::start));
        var notices = new ArrayList<LocalizedMessage>();
        if (accepted.isEmpty() && !rejected.isEmpty()) notices.add(LocalizedMessage.of("env.notice.noValidWindow"));
        else if (accepted.isEmpty() && assessment.aiInterpreted()) notices.add(LocalizedMessage.of("env.notice.noWindowProposed"));
        return Result.success(assessment.validated(accepted, rejected, notices));
    }

    private List<LocalizedMessage> reasons(ShootingWindow window, EnvironmentAssessment assessment, Set<String> known,
            List<ShootingWindow> accepted, Instant now) {
        var structural = window.validate();
        if (!structural.isSuccess()) return structural.messages();
        var found = new ArrayList<LocalizedMessage>();
        var context = assessment.context();
        if (assessment.availability().isEmpty()) return List.of(LocalizedMessage.of("env.reject.noAvailability"));
        Availability available = assessment.availability().get();
        if (context.zone().isPresent() && !window.start().getZone().equals(context.zone().get()))
            found.add(LocalizedMessage.of("env.reject.zone"));
        if (!available.contains(window.start(), window.end()))
            found.add(LocalizedMessage.of("env.reject.availability", available.start().toLocalDateTime().toString(), available.end().toLocalDateTime().toString()));
        if (window.arrival().isBefore(available.start())) found.add(LocalizedMessage.of("env.reject.arrival"));
        if (window.duration().compareTo(MIN_WINDOW) < 0) found.add(LocalizedMessage.of("env.reject.short", MIN_WINDOW.toMinutes()));
        if (!window.end().toInstant().isAfter(now)) found.add(LocalizedMessage.of("env.reject.past"));
        lightProblem(window, assessment).ifPresent(found::add);
        if (window.evidence().isEmpty()) found.add(LocalizedMessage.of("env.reject.noEvidence"));
        var unknown = window.evidence().stream().filter(id -> !known.contains(id)).toList();
        if (!unknown.isEmpty()) found.add(LocalizedMessage.of("env.reject.unknownEvidence", String.join(", ", unknown)));
        var unsupported = unsupportedMeasurements(window.label() + " " + window.conditions() + " " + window.rationale(), context);
        if (!unsupported.isEmpty()) found.add(LocalizedMessage.of("env.reject.measurement", String.join(", ", unsupported)));
        if (accepted.stream().anyMatch(a -> a.start().isEqual(window.start()) && a.end().isEqual(window.end())))
            found.add(LocalizedMessage.of("env.reject.duplicate"));
        return found;
    }

    /** Declared light must agree with provider sun events or the computed phases; unverifiable claims are rejected. */
    private static Optional<LocalizedMessage> lightProblem(ShootingWindow window, EnvironmentAssessment assessment) {
        var context = assessment.context();
        LocalDate day = window.start().toLocalDate();
        String light = window.light();
        var unverifiable = Optional.of(LocalizedMessage.of("env.reject.lightUnverified", light));
        var conflict = Optional.of(LocalizedMessage.of("env.reject.light", light));
        switch (light) {
            case "mixed" -> { return Optional.empty(); }
            case "daylight" -> {
                var rise = context.sunEvent(day, "sunrise"); var set = context.sunEvent(day, "sunset");
                if (rise.isEmpty() || set.isEmpty()) return unverifiable;
                return within(window, rise.get(), set.get()) ? Optional.empty() : conflict;
            }
            case "goldenHour", "blueHour" -> {
                var phases = assessment.lightPhases().stream().filter(p -> light.equals("goldenHour") ? p.golden() : p.blue()).toList();
                if (phases.isEmpty()) return unverifiable;
                return phases.stream().anyMatch(p -> overlaps(window, p.start().toInstant(), p.end().toInstant())) ? Optional.empty() : conflict;
            }
            case "twilight" -> {
                var intervals = new ArrayList<Instant[]>();
                for (LocalDate d : List.of(day.minusDays(1), day, day.plusDays(1))) {
                    pair(context, d, "civilDawn", d, "sunrise").ifPresent(intervals::add);
                    pair(context, d, "sunset", d, "civilDusk").ifPresent(intervals::add);
                }
                if (intervals.isEmpty()) return unverifiable;
                return intervals.stream().anyMatch(i -> overlaps(window, i[0], i[1])) ? Optional.empty() : conflict;
            }
            case "night" -> {
                var intervals = new ArrayList<Instant[]>();
                for (LocalDate d : List.of(day.minusDays(1), day)) pair(context, d, "civilDusk", d.plusDays(1), "civilDawn").ifPresent(intervals::add);
                // Without the next morning's dawn, darkness is still verified up to the end of the evidence range.
                context.sunEvent(day, "civilDusk").ifPresent(dusk -> {
                    if (context.sunEvent(day.plusDays(1), "civilDawn").isEmpty())
                        intervals.add(new Instant[]{dusk, day.plusDays(1).atStartOfDay(window.start().getZone()).toInstant()});
                });
                if (intervals.isEmpty()) return unverifiable;
                return intervals.stream().anyMatch(i -> within(window, i[0], i[1])) ? Optional.empty() : conflict;
            }
            default -> { return Optional.of(LocalizedMessage.of("env.reject.lightCategory", light)); }
        }
    }
    private static Optional<Instant[]> pair(EnvironmentContext context, LocalDate first, String from, LocalDate second, String to) {
        var a = context.sunEvent(first, from); var b = context.sunEvent(second, to);
        return a.isPresent() && b.isPresent() && b.get().isAfter(a.get()) ? Optional.of(new Instant[]{a.get(), b.get()}) : Optional.empty();
    }
    private static boolean within(ShootingWindow window, Instant from, Instant to) {
        return !window.start().toInstant().isBefore(from.minus(SUN_TOLERANCE)) && !window.end().toInstant().isAfter(to.plus(SUN_TOLERANCE));
    }
    private static boolean overlaps(ShootingWindow window, Instant from, Instant to) {
        Instant start = window.start().toInstant().isAfter(from) ? window.start().toInstant() : from;
        Instant end = window.end().toInstant().isBefore(to) ? window.end().toInstant() : to;
        return Duration.between(start, end).compareTo(Duration.ofMinutes(1)) >= 0;
    }

    /** Number + unit, longest units first; Chinese unit words are included for zh-CN replies. */
    private static final Pattern MEASUREMENT = Pattern.compile(
        "(?<![\\w.])(-?\\d+(?:\\.\\d+)?)\\s*(°C|℃|摄氏度|°F|℉|华氏度|%|％|km/h|kph|公里/小时|千米/小时|公里每小时|mph|m/s|米/秒|mm|毫米|km|公里|千米|m(?![a-zA-Z/])|米|°)");

    /**
     * Measurements in AI text that do not match any provider value of the same quantity (within display rounding).
     * Units the providers do not report (°F, mph, m/s) are always unsupported. Solar-elevation thresholds of the
     * documented light-phase model are accepted as degrees.
     */
    public static List<String> unsupportedMeasurements(String text, EnvironmentContext context) {
        if (text == null || text.isBlank()) return List.of();
        var allowed = new HashMap<String, List<Double>>();
        for (var hour : context.hourly()) hour.values().forEach((key, value) -> add(allowed, key, value));
        context.weather().forEach((key, value) -> add(allowed, key, value));
        allowed.computeIfAbsent("degrees", k -> new ArrayList<>()).addAll(List.of(-6.0, -4.0, 6.0));
        var unsupported = new ArrayList<String>();
        var matcher = MEASUREMENT.matcher(text);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String quantity = switch (matcher.group(2)) {
                case "°C", "℃", "摄氏度" -> "temperature";
                case "%", "％" -> "percent";
                case "km/h", "kph", "公里/小时", "千米/小时", "公里每小时" -> "speed";
                case "mm", "毫米" -> "precipitation";
                case "km", "公里", "千米" -> "kilometres";
                case "m", "米" -> "metres";
                case "°" -> "degrees";
                default -> "unsupportedUnit";
            };
            boolean supported = allowed.getOrDefault(quantity, List.of()).stream()
                .anyMatch(v -> Math.abs(v - value) <= Math.max(quantity.equals("kilometres") ? 0.05 : 0.5, Math.abs(v) * 0.01));
            if (!supported) unsupported.add(matcher.group().strip());
        }
        return unsupported;
    }
    private static void add(Map<String, List<Double>> allowed, String key, double value) {
        String quantity = key.startsWith("temperature") ? "temperature" : key.startsWith("cloudCover") || key.startsWith("precipitationProbability") ? "percent"
            : key.startsWith("wind") && key.endsWith("Kmh") ? "speed" : key.startsWith("precipitation") ? "precipitation"
            : key.equals("windDirectionDeg") ? "degrees" : key.equals("visibilityM") ? "metres" : "";
        if (quantity.isEmpty()) return;
        allowed.computeIfAbsent(quantity, k -> new ArrayList<>()).add(value);
        if (quantity.equals("metres")) allowed.computeIfAbsent("kilometres", k -> new ArrayList<>()).add(value / 1000);
    }
}
