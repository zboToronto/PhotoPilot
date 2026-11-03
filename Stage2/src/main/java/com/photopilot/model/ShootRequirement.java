package com.photopilot.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 36 (zero-based): {F03; confirmed before capability analysis}
 * Structured shooting requirements. Unspecified values stay explicitly absent (empty Optional or blank text);
 * nothing is defaulted on the user's behalf. Drafts become usable by F06-F10 only through {@link #confirm()}.
 */
public class ShootRequirement {
    /** Deterministic bounds; values outside them are rejected rather than clamped. */
    public static final int MAX_CAMERAS = 10, MAX_PRIORITY_RANK = 10, MAX_TEXT = 500, MAX_ORIGINAL_TEXT = 8000;
    public static final Duration MAX_DURATION = Duration.ofDays(14);
    public static final double MAX_WEIGHT_GRAMS = 100_000;
    /** Editable field names shared by validation findings, review hints, GUI and CLI. */
    public static final List<String> FIELDS = List.of("subject", "goals", "date", "time", "timeWindow", "location", "duration",
        "cameraCount", "lensChanges", "maxWeight", "portability", "gearConstraints", "priorities", "constraints");

    private String originalText;
    private String subject;
    private List<String> goals;
    private Optional<ZonedDateTime> time;
    private Optional<LocalDate> date;
    private String timeWindow;
    private Optional<GeoLocation> location;
    private Optional<Duration> duration;
    private Optional<Integer> cameraCount;
    private Optional<Boolean> lensChangesAllowed;
    private Optional<Double> maxWeightGrams;
    private String portability;
    private List<String> gearConstraints;
    private Map<String, Integer> priorities;
    private Map<String, String> fixedConstraints;
    private List<String> clarificationQuestions;
    private boolean confirmed;
    /** Draft-only review hints (ambiguous/contradictory fields); never persisted. */
    private transient Set<String> reviewFields = Set.of();

    /** Original Stage 1 constructor: every value given here counts as specified. */
    public ShootRequirement(String originalText, String subject, List<String> goals, Optional<ZonedDateTime> time, Optional<GeoLocation> location, Duration duration, int cameraCount, boolean lensChangesAllowed, Optional<Double> maxWeightGrams, List<String> gearConstraints, Map<String, Integer> priorities, Map<String, String> fixedConstraints, List<String> clarificationQuestions, boolean confirmed) {
        this(originalText, subject, goals, time, Optional.empty(), "", location, Optional.of(duration), Optional.of(cameraCount),
            Optional.of(lensChangesAllowed), maxWeightGrams, "", gearConstraints, priorities, fixedConstraints, clarificationQuestions, confirmed);
    }

    public ShootRequirement(String originalText, String subject, List<String> goals, Optional<ZonedDateTime> time,
            Optional<LocalDate> date, String timeWindow, Optional<GeoLocation> location, Optional<Duration> duration,
            Optional<Integer> cameraCount, Optional<Boolean> lensChangesAllowed, Optional<Double> maxWeightGrams,
            String portability, List<String> gearConstraints, Map<String, Integer> priorities,
            Map<String, String> fixedConstraints, List<String> clarificationQuestions, boolean confirmed) {
        this.originalText = Objects.requireNonNull(originalText, "originalText");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.goals = List.copyOf(goals);
        this.time = Objects.requireNonNull(time, "time");
        this.date = Objects.requireNonNull(date, "date");
        this.timeWindow = Objects.requireNonNull(timeWindow, "timeWindow");
        this.location = Objects.requireNonNull(location, "location");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.cameraCount = Objects.requireNonNull(cameraCount, "cameraCount");
        this.lensChangesAllowed = Objects.requireNonNull(lensChangesAllowed, "lensChangesAllowed");
        this.maxWeightGrams = Objects.requireNonNull(maxWeightGrams, "maxWeightGrams");
        this.portability = Objects.requireNonNull(portability, "portability");
        this.gearConstraints = List.copyOf(gearConstraints);
        this.priorities = ordered(priorities);
        this.fixedConstraints = ordered(fixedConstraints);
        this.clarificationQuestions = List.copyOf(clarificationQuestions);
        this.confirmed = confirmed;
        // Storage sanity only; contextual rules live in problems()/validate().
        if (duration.isPresent() && duration.get().isNegative() || cameraCount.isPresent() && cameraCount.get() < 1)
            throw new IllegalArgumentException("Invalid requirement duration or camera count.");
        maxWeightGrams.ifPresent(value -> {
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid weight constraint.");
        });
    }

    private static <V> Map<String, V> ordered(Map<String, V> source) {
        var copy = new LinkedHashMap<String, V>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }

    /** One deterministic finding; {@code field} names the affected {@code RequirementForm} field. */
    public record Problem(String field, LocalizedMessage message) { }

    /** Required fields, accepted values and internal consistency. Independent of any AI reasoning. */
    public List<Problem> problems() {
        var found = new ArrayList<Problem>();
        if (subject.isBlank()) found.add(problem("subject", "requirement.error.subject"));
        if (subject.length() > 200) found.add(problem("subject", "requirement.error.tooLong"));
        if (originalText.length() > MAX_ORIGINAL_TEXT) found.add(problem("request", "requirement.error.requestLength", MAX_ORIGINAL_TEXT));
        if (goals.size() > 20 || goals.stream().anyMatch(goal -> goal.isBlank() || goal.length() > MAX_TEXT)) found.add(problem("goals", "requirement.error.list"));
        if (gearConstraints.size() > 20 || gearConstraints.stream().anyMatch(item -> item.isBlank() || item.length() > MAX_TEXT))
            found.add(problem("gearConstraints", "requirement.error.list"));
        if (timeWindow.length() > MAX_TEXT) found.add(problem("timeWindow", "requirement.error.tooLong"));
        if (portability.length() > MAX_TEXT) found.add(problem("portability", "requirement.error.tooLong"));
        if (time.isPresent() && date.isPresent() && !time.get().toLocalDate().equals(date.get()))
            found.add(problem("time", "requirement.error.timeDate"));
        location.ifPresent(place -> { if (!place.validate().isSuccess()) found.add(problem("location", "requirement.error.location")); });
        duration.ifPresent(value -> {
            if (value.isZero() || value.isNegative() || value.compareTo(MAX_DURATION) > 0) found.add(problem("duration", "requirement.error.duration"));
        });
        cameraCount.ifPresent(value -> { if (value < 1 || value > MAX_CAMERAS) found.add(problem("cameraCount", "requirement.error.cameraCount", MAX_CAMERAS)); });
        maxWeightGrams.ifPresent(value -> {
            if (!Double.isFinite(value) || value <= 0 || value > MAX_WEIGHT_GRAMS) found.add(problem("maxWeight", "requirement.error.weight"));
        });
        if (priorities.size() > 20 || priorities.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                || entry.getKey().length() > 100 || entry.getValue() < 1 || entry.getValue() > MAX_PRIORITY_RANK))
            found.add(problem("priorities", "requirement.error.priorities", MAX_PRIORITY_RANK));
        if (fixedConstraints.size() > 20 || fixedConstraints.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                || entry.getValue().isBlank() || entry.getKey().length() > 100 || entry.getValue().length() > MAX_TEXT))
            found.add(problem("constraints", "requirement.error.constraints"));
        return List.copyOf(found);
    }
    private static Problem problem(String field, String key, Object... arguments) { return new Problem(field, LocalizedMessage.of(key, arguments)); }

    public Result<ShootRequirement> validate() {
        var found = problems();
        return found.isEmpty() ? Result.success(this) : Result.failures(found.stream().map(Problem::message).toList());
    }

    /** Validated, explicitly confirmed copy. Clarification state belongs to the draft and is cleared. */
    public Result<ShootRequirement> confirm() {
        var valid = validate();
        if (!valid.isSuccess()) return valid;
        return Result.success(new ShootRequirement(originalText, subject, goals, time, date, timeWindow, location, duration,
            cameraCount, lensChangesAllowed, maxWeightGrams, portability, gearConstraints, priorities, fixedConstraints, List.of(), true));
    }

    /** Unconfirmed copy for editing; the stored confirmed value is untouched until a new confirmation saves. */
    public ShootRequirement asDraft() {
        return new ShootRequirement(originalText, subject, goals, time, date, timeWindow, location, duration, cameraCount,
            lensChangesAllowed, maxWeightGrams, portability, gearConstraints, priorities, fixedConstraints, clarificationQuestions, false);
    }

    public ShootRequirement withReviewFields(Set<String> fields) {
        var copy = new ShootRequirement(originalText, subject, goals, time, date, timeWindow, location, duration, cameraCount,
            lensChangesAllowed, maxWeightGrams, portability, gearConstraints, priorities, fixedConstraints, clarificationQuestions, confirmed);
        copy.reviewFields = Set.copyOf(fields);
        return copy;
    }

    /** Optional details the user has not specified; reported, never filled in. */
    public List<String> unspecifiedFields() {
        var missing = new ArrayList<String>();
        if (date.isEmpty() && time.isEmpty() && timeWindow.isBlank()) missing.add("date");
        if (location.isEmpty()) missing.add("location");
        if (duration.isEmpty()) missing.add("duration");
        if (cameraCount.isEmpty()) missing.add("cameraCount");
        if (lensChangesAllowed.isEmpty()) missing.add("lensChanges");
        if (goals.isEmpty()) missing.add("goals");
        return List.copyOf(missing);
    }

    public String originalText() { return originalText; }
    public String subject() { return subject; }
    public List<String> goals() { return goals; }
    public Optional<ZonedDateTime> time() { return time; }
    public Optional<LocalDate> date() { return date.isPresent() ? date : time.map(ZonedDateTime::toLocalDate); }
    public String timeWindow() { return timeWindow; }
    public Optional<GeoLocation> location() { return location; }
    public Optional<Duration> duration() { return duration; }
    public Optional<Integer> cameraCount() { return cameraCount; }
    public Optional<Boolean> lensChangesAllowed() { return lensChangesAllowed; }
    public Optional<Double> maxWeightGrams() { return maxWeightGrams; }
    public String portability() { return portability; }
    public List<String> gearConstraints() { return gearConstraints; }
    public Map<String, Integer> priorities() { return priorities; }
    public Map<String, String> fixedConstraints() { return fixedConstraints; }
    public List<String> clarificationQuestions() { return clarificationQuestions; }
    public boolean confirmed() { return confirmed; }
    public Set<String> reviewFields() { return reviewFields == null ? Set.of() : reviewFields; }
}
