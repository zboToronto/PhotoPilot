package com.photopilot.validation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;

/**
 * The one rule by which F06 capability analysis and F10 planning may use an F09 assessment: only while it applies to the
 * session's current confirmed requirement (place, date, time zone, shooting time) and only with the evidence groups that
 * are fresh at the moment of use. {@link #assessment()} is a filtered copy for that single use: stale groups, and F09
 * windows that rely on them or have already ended, are removed and {@code staleFields()} holds the current stale groups.
 * The stored assessment is never changed. Deterministic; no AI judgement is involved.
 */
public record EnvironmentEvidence(Status status, Optional<EnvironmentAssessment> assessment, Set<String> stale, List<LocalizedMessage> notes) {
    public enum Status {
        /** No assessment of this session in this process. */
        NONE,
        /** Applies to the confirmed place, date, time zone and shooting time. */
        APPLICABLE,
        /** Applies, but its availability covers only part of the confirmed shooting time. */
        PARTIAL,
        /** Made for another place than the confirmed location (or the location changed since). */
        LOCATION,
        /** Made for another date than the confirmed shooting date. */
        DATE,
        /** Its time zone disagrees with the confirmed start time or with its own availability. */
        ZONE,
        /** Its availability does not cover the confirmed shooting time, or the confirmed timing changed since. */
        WINDOW,
        /** Too little recorded context to establish that it applies (place, date, zone, availability, matching place). */
        UNVERIFIED
    }

    /**
     * The confirmed requirement in force when this process made the assessment (empty: none was confirmed then). It lets
     * a place the user reviewed in Environment Planning stand for an unchanged label-only requirement location.
     */
    public record Basis(Optional<ShootRequirement> requirement) {
        public Basis { Objects.requireNonNull(requirement, "requirement"); }
    }

    public EnvironmentEvidence {
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(assessment, "assessment");
        stale = Set.copyOf(stale); notes = List.copyOf(notes);
    }

    public static EnvironmentEvidence none() { return new EnvironmentEvidence(Status.NONE, Optional.empty(), Set.of(), List.of()); }
    /** Whether the (filtered) assessment may be used; a partial one is usable for the part it covers. */
    public boolean usable() { return status == Status.APPLICABLE || status == Status.PARTIAL; }

    /**
     * Checks {@code assessment} against {@code requirement} (shot on {@code shootingDate}, a local date) with {@code stale}
     * evidence groups evaluated at {@code now}. Order: recorded context, the assessment's own zone, the requirement's
     * start-time zone, date, location, shooting time. Location rules: a requirement without location does not constrain
     * the reviewed F09 place; coordinates match at four decimal places. A label can match directly or through a confirmed lookup
     * linked to the requirement that was active at assessment time; anything else is a mismatch (location changed since) or unverified (no record of how it was made).
     */
    public static EnvironmentEvidence check(EnvironmentAssessment assessment, ShootRequirement requirement, LocalDate shootingDate,
            Optional<Basis> basis, Set<String> stale, Instant now) {
        if (assessment == null) return none();
        Objects.requireNonNull(requirement, "requirement"); Objects.requireNonNull(shootingDate, "shootingDate");
        Objects.requireNonNull(basis, "basis"); Objects.requireNonNull(now, "now");
        var context = assessment.context();
        var request = assessment.request();
        Optional<GeoLocation> place = request.map(EnvironmentRequest::place).or(context::place)
            .filter(p -> p.latitude().isPresent() && p.longitude().isPresent());
        Optional<LocalDate> date = request.map(EnvironmentRequest::date).or(context::date);
        var zone = context.zone();
        var availability = assessment.availability();
        if (request.isEmpty() || place.isEmpty() || date.isEmpty() || zone.isEmpty() || availability.isEmpty())
            return rejected(Status.UNVERIFIED, LocalizedMessage.of("env.use.unverified"));
        var then = basis.map(b -> new Then(b.requirement().flatMap(ShootRequirement::location), b.requirement().map(ShootRequirement::timeWindow)));
        // An unchanged requirement cannot make an explicitly different shooting site its alias.
        var wanted = requirement.location().filter(p -> p.latitude().isEmpty()).map(GeoLocation::label);
        if (wanted.isPresent() && !sameLabel(wanted.get(), place.get().label())) {
            boolean linked = request.get().accepted().filter(c -> sameLabel(wanted.get(), c.query()) || sameLabel(wanted.get(), c.name())).isPresent();
            if (!linked) return basis.isEmpty()
                ? rejected(Status.UNVERIFIED, LocalizedMessage.of("env.use.locationUnverified", place.get().label(), wanted.get()))
                : rejected(Status.LOCATION, LocalizedMessage.of("env.use.location", place.get().label(), wanted.get()));
        }
        var verdict = rules(place.get(), date.get(), zone.get(), availability.get(), requirement, shootingDate, then);
        if (verdict.status() != Status.APPLICABLE && verdict.status() != Status.PARTIAL) return rejected(verdict.status(), verdict.notes().getFirst());
        return new EnvironmentEvidence(verdict.status(), Optional.of(fresh(assessment, place.get(), date.get(), stale, now)), stale, verdict.notes());
    }

    /**
     * Whether a recorded check ({@link EnvironmentCheck}, workflow milestone 04) still applies to the session's current
     * confirmed requirement shot on {@code shootingDate}, by the same rules as {@link #check} (zone, date, location,
     * shooting time, unchanged label-only location and free-text timing against what was confirmed when it was
     * recorded). Without a confirmed requirement only the date is compared. Freshness of provider data is not judged
     * here: it ages by the hour, and F06/F10 re-check it at each use.
     */
    public static EnvironmentEvidence applies(EnvironmentCheck recorded, ShootRequirement requirement, LocalDate shootingDate) {
        Objects.requireNonNull(recorded, "recorded"); Objects.requireNonNull(shootingDate, "shootingDate");
        if (requirement == null) return recorded.date().equals(shootingDate) ? new EnvironmentEvidence(Status.APPLICABLE, Optional.empty(), Set.of(), List.of())
            : rejected(Status.DATE, LocalizedMessage.of("env.use.date", recorded.date().toString(), shootingDate.toString()));
        var available = new EnvironmentAssessment.Availability(recorded.availableFrom(), recorded.availableUntil());
        var then = recorded.madeUnderRequirement() ? new Then(recorded.basisLocation(), recorded.basisTimeWindow()) : new Then(Optional.empty(), Optional.empty());
        var verdict = rules(recorded.place(), recorded.date(), recorded.availableFrom().getZone(), available, requirement, shootingDate, Optional.of(then));
        if (verdict.status() != Status.APPLICABLE && verdict.status() != Status.PARTIAL) return rejected(verdict.status(), verdict.notes().getFirst());
        return new EnvironmentEvidence(verdict.status(), Optional.empty(), Set.of(), verdict.notes());
    }

    /** The confirmed location and time-window text in force when an assessment was made (empty: none was confirmed). */
    private record Then(Optional<GeoLocation> location, Optional<String> timeWindow) { }
    private record Verdict(Status status, List<LocalizedMessage> notes) { }

    /** The shared rules once the recorded context is complete; a rejection carries its one reason. */
    private static Verdict rules(GeoLocation place, LocalDate date, java.time.ZoneId zone, EnvironmentAssessment.Availability available,
            ShootRequirement requirement, LocalDate shootingDate, Optional<Then> basis) {
        if (!available.start().getZone().getRules().equals(zone.getRules()))
            return verdict(Status.ZONE, LocalizedMessage.of("env.use.zone", zone.getId(), available.start().getZone().getId()));
        var start = requirement.time();
        if (start.isPresent() && !zone.getRules().getOffset(start.get().toInstant()).equals(start.get().getOffset()))
            return verdict(Status.ZONE, LocalizedMessage.of("env.use.zone", zone.getId(), start.get().getZone().getId()));
        if (!date.equals(shootingDate))
            return verdict(Status.DATE, LocalizedMessage.of("env.use.date", date.toString(), shootingDate.toString()));
        var location = location(place, requirement, basis);
        if (location.isPresent()) return location.get();

        var notes = new ArrayList<LocalizedMessage>();
        Status status = Status.APPLICABLE;
        if (start.isPresent()) {
            Instant from = start.get().toInstant(), to = requirement.duration().map(from::plus).orElse(from);
            Instant open = available.start().toInstant(), close = available.end().toInstant();
            String wanted = span(start.get().withZoneSameInstant(zone), to.atZone(zone)), assessed = span(available.start(), available.end());
            boolean covered = !from.isBefore(open) && !to.isAfter(close) && from.isBefore(close);
            boolean overlaps = from.isBefore(close) && (to.isAfter(open) || !from.isBefore(open));
            if (!overlaps) return verdict(Status.WINDOW, LocalizedMessage.of("env.use.window", wanted, assessed));
            if (!covered) { status = Status.PARTIAL; notes.add(LocalizedMessage.of("env.use.partial", wanted, assessed)); }
        } else if (basis.flatMap(Then::timeWindow).filter(then -> !sameLabel(then, requirement.timeWindow())).isPresent()) {
            // A free-text timing preference cannot be checked against the availability; a changed one needs a new review.
            String then = basis.flatMap(Then::timeWindow).orElseThrow();
            return verdict(Status.WINDOW, LocalizedMessage.of("env.use.timingChanged", then, requirement.timeWindow()));
        }
        return new Verdict(status, notes);
    }
    private static Verdict verdict(Status status, LocalizedMessage reason) { return new Verdict(status, List.of(reason)); }

    /** Same label ignoring case and spacing; never a similarity guess. */
    public static boolean sameLabel(String first, String second) { return normalized(first).equals(normalized(second)); }

    private static Optional<Verdict> location(GeoLocation assessed, ShootRequirement requirement, Optional<Then> basis) {
        var wanted = requirement.location();
        if (wanted.isEmpty()) return Optional.empty();
        var confirmed = wanted.get();
        var mismatch = Optional.of(verdict(Status.LOCATION, LocalizedMessage.of("env.use.location", assessed.label(), confirmed.label())));
        if (confirmed.latitude().isPresent() && confirmed.longitude().isPresent()) return samePoint(confirmed, assessed) ? Optional.empty() : mismatch;
        if (sameLabel(confirmed.label(), assessed.label())) return Optional.empty();
        if (basis.isEmpty()) return Optional.of(verdict(Status.UNVERIFIED, LocalizedMessage.of("env.use.locationUnverified", assessed.label(), confirmed.label())));
        var then = basis.get().location();
        return then.isPresent() && sameLocation(then.get(), confirmed) ? Optional.empty() : mismatch;
    }
    private static boolean sameLocation(GeoLocation first, GeoLocation second) {
        if (!sameLabel(first.label(), second.label()) || first.latitude().isPresent() != second.latitude().isPresent()) return false;
        return first.latitude().isEmpty() || samePoint(first, second);
    }
    /** Four-decimal point comparison for requirement evidence and the existing F02 place check. */
    static boolean samePoint(GeoLocation first, GeoLocation second) {
        return String.format(Locale.ROOT, "%.4f,%.4f", first.latitude().orElseThrow(), first.longitude().orElseThrow())
            .equals(String.format(Locale.ROOT, "%.4f,%.4f", second.latitude().orElseThrow(), second.longitude().orElseThrow()));
    }
    private static String normalized(String text) { return Objects.toString(text, "").strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String span(ZonedDateTime from, ZonedDateTime to) {
        return from.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) + "-" + to.toLocalTime().truncatedTo(ChronoUnit.MINUTES) + " " + from.getZone().getId();
    }
    private static EnvironmentEvidence rejected(Status status, LocalizedMessage reason) {
        return new EnvironmentEvidence(status, Optional.empty(), Set.of(), List.of(reason));
    }

    /** Copy without stale groups; F09 windows survive only while they have not ended and every evidence item they cite is fresh. */
    private static EnvironmentAssessment fresh(EnvironmentAssessment assessment, GeoLocation place, LocalDate date, Set<String> stale, Instant now) {
        var c = assessment.context();
        var index = EnvironmentAssessment.evidenceIndex(c, assessment.lightPhases());
        var view = new EnvironmentContext(c.place().orElse(place), c.date().orElse(date), c.endDate().orElse(c.date().orElse(date)), c.zone(),
            stale.contains(EnvironmentContext.DAILY) ? Map.of() : c.weather(), stale.contains(EnvironmentContext.HOURLY) ? List.of() : c.hourly(),
            stale.contains(EnvironmentContext.SUN) ? List.of() : c.sunEvents(), c.sources(), c.sourceTimestamps(), c.unavailableFields(), c.notices(), c.retrievedAt());
        var windows = assessment.windows().stream().filter(w -> w.end().toInstant().isAfter(now)
            && w.evidence().stream().allMatch(id -> freshItem(index.get(id), stale))).toList();
        return new EnvironmentAssessment(view, assessment.request().orElseThrow(), assessment.subject(), assessment.availability(), assessment.lightPhases(),
            windows, assessment.rejected(), assessment.interpretation(), assessment.notices(), stale, assessment.assessedAt());
    }
    /** Computed light phases never age; provider items are fresh while their group is; unknown ids are never trusted. */
    private static boolean freshItem(Object item, Set<String> stale) {
        if (item instanceof EnvironmentAssessment.LightPhase) return true;
        if (item instanceof EnvironmentContext.SunEvent) return !stale.contains(EnvironmentContext.SUN);
        if (item instanceof EnvironmentContext.Hour) return !stale.contains(EnvironmentContext.HOURLY);
        if (item instanceof Map<?, ?>) return !stale.contains(EnvironmentContext.DAILY);
        return false;
    }
}
