package com.photopilot.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 86 (zero-based): {F02; conversion produces unconfirmed draft}
 * One photography activity idea. Gemini proposes the candidate; {@code FeasibilityValidator.validate} decides its
 * {@link Feasibility} from deterministic checks. Persisted (F01 savedIdeas) fields hold only labels, never coordinates;
 * the check details, AI labels and ranking reasons are transient and belong to the discovery result that produced them.
 * {@link #toRequirement()} produces an unconfirmed F03 draft; only F03 confirmation creates a confirmed requirement.
 */
public class ActivitySuggestion {
    public enum Feasibility { FEASIBLE, UNVERIFIED, INFEASIBLE }
    /** Criteria checked deterministically, in display order. */
    public static final List<String> CRITERIA = List.of("time", "travel", "equipment", "environment", "avoid", "event");
    /** Light categories (F09 categories plus "any" for activities that do not depend on light). */
    public static final Set<String> LIGHT = Set.of("daylight", "goldenHour", "blueHour", "twilight", "night", "mixed", "any");
    public static final int MAX_TEXT = 600, MAX_LIMITATIONS = 12;

    /** Result of one criterion: its status and the reasons (localizable, rendered by GUI/CLI). */
    public record Check(String criterion, Feasibility status, List<LocalizedMessage> findings) {
        public Check { Objects.requireNonNull(criterion); Objects.requireNonNull(status); findings = List.copyOf(findings); }
    }
    /**
     * Straight-line distances (km) from the user's coordinates to every gazetteer match of the suggested place name, or
     * the reason none could be computed. Never a route distance and never an AI estimate.
     */
    public record TravelEvidence(String query, List<String> matches, List<Double> straightLineKm, Optional<LocalizedMessage> problem) {
        public TravelEvidence { Objects.requireNonNull(query); matches = List.copyOf(matches); straightLineKm = List.copyOf(straightLineKm); Objects.requireNonNull(problem); }
        public static TravelEvidence unavailable(String query, LocalizedMessage reason) { return new TravelEvidence(query, List.of(), List.of(), Optional.of(reason)); }
    }

    private UUID id;
    private String subject;
    private String genre;
    private String setting;
    private String place;
    private boolean atUserLocation;
    private Optional<ZonedDateTime> start;
    private Duration estimatedDuration;
    private Optional<Double> estimatedTravelKm;
    private String light;
    private List<String> gear;
    private String rationale;
    private List<String> limitations;
    private String status;
    private Optional<Instant> suggestedAt;
    // Discovery-result details; transient so saved ideas keep the v1 field schema.
    private transient String aiId = "";
    private transient boolean outdoor = true, needsClearSky, dependsOnEvent;
    private transient List<CapabilityNeed> gearNeeds = List.of();
    private transient Optional<TravelEvidence> travel = Optional.empty();
    private transient List<Check> checks = List.of();
    private transient List<LocalizedMessage> notes = List.of();
    private transient List<LocalizedMessage> rankReasons = List.of();

    /** Original Stage 1 constructor: a label-only idea whose feasibility has not been checked. */
    public ActivitySuggestion(String subject, String setting, Duration estimatedDuration, Optional<Double> estimatedTravelKm, String rationale, List<String> limitations) {
        this(UUID.randomUUID(), subject, "", setting, "", false, Optional.empty(), estimatedDuration, estimatedTravelKm, "", List.of(),
            rationale, limitations, Feasibility.UNVERIFIED.name(), Optional.empty());
    }

    public ActivitySuggestion(UUID id, String subject, String genre, String setting, String place, boolean atUserLocation,
            Optional<ZonedDateTime> start, Duration estimatedDuration, Optional<Double> estimatedTravelKm, String light, List<String> gear,
            String rationale, List<String> limitations, String status, Optional<Instant> suggestedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.genre = Objects.requireNonNull(genre, "genre");
        this.setting = Objects.requireNonNull(setting, "setting");
        this.place = Objects.requireNonNull(place, "place");
        this.atUserLocation = atUserLocation;
        this.start = Objects.requireNonNull(start, "start");
        this.estimatedDuration = Objects.requireNonNull(estimatedDuration, "estimatedDuration");
        this.estimatedTravelKm = Objects.requireNonNull(estimatedTravelKm, "estimatedTravelKm");
        this.light = Objects.requireNonNull(light, "light");
        this.gear = List.copyOf(gear);
        this.rationale = Objects.requireNonNull(rationale, "rationale");
        this.limitations = List.copyOf(limitations);
        this.status = Objects.requireNonNull(status, "status");
        this.suggestedAt = Objects.requireNonNull(suggestedAt, "suggestedAt");
        if (estimatedDuration.isNegative()) throw new IllegalArgumentException("Negative activity duration.");
        estimatedTravelKm.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid travel estimate.");
        });
        if (Arrays.stream(Feasibility.values()).noneMatch(value -> value.name().equals(status))) throw new IllegalArgumentException("Invalid feasibility status.");
    }

    /** Structural checks for storage and selection; feasibility is decided by FeasibilityValidator. */
    public Result<ActivitySuggestion> validate() {
        if (subject.isBlank() || subject.length() > 200 || genre.length() > 100 || setting.length() > MAX_TEXT || place.length() > 300
                || rationale.length() > MAX_TEXT || light.length() > 20 || gear.size() > 20 || limitations.size() > 40
                || gear.stream().anyMatch(item -> item.isBlank() || item.length() > MAX_TEXT)
                || limitations.stream().anyMatch(item -> item.isBlank() || item.length() > MAX_TEXT)
                || estimatedDuration.isZero() || estimatedDuration.compareTo(Duration.ofDays(1)) > 0)
            return Result.failureKey("discover.error.idea");
        return Result.success(this);
    }

    /**
     * Unconfirmed F03 draft from this idea. Only what the idea actually states is transferred; the place is a label
     * without coordinates, and open checks become clarification questions and review flags. Never confirmed here.
     */
    public ShootRequirement toRequirement() {
        var questions = new ArrayList<String>();
        var flagged = new TreeSet<String>();
        if (!atUserLocation && !place.isBlank()) {
            questions.add(EnglishMessages.text("discover.handoff.placeQuestion", place));
            flagged.add("location");
        }
        if (!status.equals(Feasibility.FEASIBLE.name())) questions.add(EnglishMessages.text("discover.handoff.unverifiedQuestion"));
        var text = new StringBuilder(EnglishMessages.text("discover.handoff.original", clause(subject)));
        if (!setting.isBlank()) text.append(' ').append(EnglishMessages.text("discover.handoff.setting", clause(setting)));
        if (!place.isBlank()) text.append(' ').append(EnglishMessages.text("discover.handoff.place", clause(place)));
        if (!gear.isEmpty()) text.append(' ').append(EnglishMessages.text("discover.handoff.gear", clause(String.join("; ", gear))));
        text.append(' ').append(EnglishMessages.text("discover.handoff.status", status));
        var goals = setting.isBlank() ? List.<String>of() : List.of(setting.length() > ShootRequirement.MAX_TEXT ? setting.substring(0, ShootRequirement.MAX_TEXT) : setting);
        String window = switch (light) { case "", "any", "mixed" -> ""; default -> EnglishMessages.text("discover.light." + light); };
        Optional<GeoLocation> location = place.isBlank() || !GeoLocation.manual(place).validate().isSuccess() ? Optional.empty() : Optional.of(GeoLocation.manual(place));
        var draft = new ShootRequirement(text.toString(), subject, goals, start, start.map(ZonedDateTime::toLocalDate), window, location,
            Optional.of(estimatedDuration), Optional.empty(), Optional.empty(), Optional.empty(), "", List.of(), Map.of(), Map.of(),
            questions.stream().limit(6).toList(), false);
        return draft.withReviewFields(flagged);
    }

    /** Text placed before a template's own full stop. */
    private static String clause(String text) { return text.strip().replaceAll("[.。]+$", ""); }

    /** Copy with the validator's checks and overall status; persisted fields other than status are unchanged. */
    public ActivitySuggestion withChecks(List<Check> results) {
        var worst = Feasibility.FEASIBLE;
        for (var check : results) if (check.status().ordinal() > worst.ordinal()) worst = check.status();
        var copy = copy(worst.name(), limitations, estimatedTravelKm);
        copy.checks = List.copyOf(results);
        return copy;
    }
    /** Copy with the straight-line distance (only when one unambiguous gazetteer match exists) and the travel evidence. */
    public ActivitySuggestion withTravel(TravelEvidence evidence) {
        Optional<Double> km = evidence.straightLineKm().size() == 1 ? Optional.of(Math.round(evidence.straightLineKm().getFirst() * 10) / 10.0) : Optional.empty();
        var copy = copy(status, limitations, km);
        copy.travel = Optional.of(evidence);
        return copy;
    }
    /** AI-only metadata of a freshly converted candidate (never persisted). */
    public ActivitySuggestion withProposal(String label, boolean isOutdoor, boolean clearSky, boolean event, List<CapabilityNeed> needs, List<LocalizedMessage> applicationNotes) {
        var copy = copy(status, limitations, estimatedTravelKm);
        copy.aiId = Objects.requireNonNull(label); copy.outdoor = isOutdoor; copy.needsClearSky = clearSky; copy.dependsOnEvent = event;
        copy.gearNeeds = List.copyOf(needs); copy.notes = List.copyOf(applicationNotes);
        return copy;
    }
    public ActivitySuggestion withRankReasons(List<LocalizedMessage> reasons) {
        var copy = copy(status, limitations, estimatedTravelKm);
        copy.rankReasons = List.copyOf(reasons);
        return copy;
    }
    /**
     * Copy for F01 storage: the deterministic findings that are not FEASIBLE are appended to the limitations in English
     * (like other saved records) so the saved idea keeps its caveats; transient details are dropped.
     */
    public ActivitySuggestion forStorage() {
        var stored = new ArrayList<>(limitations);
        for (var check : checks())
            if (check.status() != Feasibility.FEASIBLE)
                for (var finding : check.findings()) stored.add(EnglishMessages.text(finding.key(), finding.arguments().toArray()));
        for (var note : notes()) stored.add(EnglishMessages.text(note.key(), note.arguments().toArray()));
        var unique = stored.stream().map(String::strip).filter(text -> !text.isEmpty()).map(text -> text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text)
            .distinct().limit(40).toList();
        return new ActivitySuggestion(id, subject, genre, setting, place, atUserLocation, start, estimatedDuration, estimatedTravelKm, light,
            gear, rationale, unique, status, suggestedAt);
    }
    private ActivitySuggestion copy(String newStatus, List<String> newLimitations, Optional<Double> km) {
        var copy = new ActivitySuggestion(id, subject, genre, setting, place, atUserLocation, start, estimatedDuration, km, light, gear,
            rationale, newLimitations, newStatus, suggestedAt);
        copy.aiId = aiId(); copy.outdoor = outdoor; copy.needsClearSky = needsClearSky; copy.dependsOnEvent = dependsOnEvent;
        copy.gearNeeds = gearNeeds(); copy.travel = travel(); copy.checks = checks(); copy.notes = notes(); copy.rankReasons = rankReasons();
        return copy;
    }

    public UUID id() { return id; }
    public String subject() { return subject; }
    public String genre() { return genre; }
    public String setting() { return setting; }
    /** Where: the suggested destination name, or the user's own location label for an activity there. */
    public String place() { return place; }
    public boolean atUserLocation() { return atUserLocation; }
    public Optional<ZonedDateTime> start() { return start; }
    public Optional<ZonedDateTime> end() { return start.map(time -> time.plus(estimatedDuration)); }
    public Duration estimatedDuration() { return estimatedDuration; }
    /** Straight-line kilometres to the only gazetteer match of the place; never a route distance. */
    public Optional<Double> estimatedTravelKm() { return estimatedTravelKm; }
    public String light() { return light; }
    /** Owned equipment considerations (inventory names). */
    public List<String> gear() { return gear; }
    public String rationale() { return rationale; }
    public List<String> limitations() { return limitations; }
    public Feasibility feasibility() { return Feasibility.valueOf(status); }
    public Optional<Instant> suggestedAt() { return suggestedAt; }
    public String aiId() { return aiId == null ? "" : aiId; }
    public boolean outdoor() { return outdoor; }
    public boolean needsClearSky() { return needsClearSky; }
    public boolean dependsOnEvent() { return dependsOnEvent; }
    public List<CapabilityNeed> gearNeeds() { return gearNeeds == null ? List.of() : gearNeeds; }
    public Optional<TravelEvidence> travel() { return travel == null ? Optional.empty() : travel; }
    public List<Check> checks() { return checks == null ? List.of() : checks; }
    public Optional<Check> check(String criterion) { return checks().stream().filter(c -> c.criterion().equals(criterion)).findFirst(); }
    /** Application notes about the conversion (for example dropped AI statements). */
    public List<LocalizedMessage> notes() { return notes == null ? List.of() : notes; }
    public List<LocalizedMessage> rankReasons() { return rankReasons == null ? List.of() : rankReasons; }
}
