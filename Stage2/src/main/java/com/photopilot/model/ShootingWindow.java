package com.photopilot.model;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 75 (zero-based): {F09/F10; copied into approved revisions}
 * One suggested shooting window. F09 windows are AI-proposed from verified evidence, then deterministically checked
 * (FeasibilityValidator.checkTiming); the arrival time is computed by the application from the user's own
 * preparation lead time, never invented by the model.
 */
public class ShootingWindow {
    /** Declared lighting category; each is checked against sun data, except "mixed". */
    public static final Set<String> LIGHT = Set.of("daylight", "goldenHour", "blueHour", "twilight", "night", "mixed");
    public static final int MAX_TEXT = 600;

    private ZonedDateTime start;
    private ZonedDateTime end;
    private ZonedDateTime arrival;
    private String conditions;
    private boolean estimated;
    // F09 assessment metadata; transient so the saved v1 session schema (ShootPlan windows) is unchanged.
    private transient String label = "";
    private transient String light = "mixed";
    private transient String rationale = "";
    private transient List<String> evidence = List.of();
    private transient List<LocalizedMessage> notes = List.of();

    public ShootingWindow(ZonedDateTime start, ZonedDateTime end, ZonedDateTime arrival, String conditions, boolean estimated) {
        this.start = Objects.requireNonNull(start, "start");
        this.end = Objects.requireNonNull(end, "end");
        this.arrival = Objects.requireNonNull(arrival, "arrival");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.estimated = estimated;
        if (end.isBefore(start) || arrival.isAfter(start))
            throw new IllegalArgumentException("Invalid shooting-window chronology.");
    }

    /** F09 window with its label, declared light, AI rationale, cited evidence identifiers and application notes. */
    public ShootingWindow(ZonedDateTime start, ZonedDateTime end, ZonedDateTime arrival, String conditions, boolean estimated,
            String label, String light, String rationale, List<String> evidence, List<LocalizedMessage> notes) {
        this(start, end, arrival, conditions, estimated);
        this.label = Objects.requireNonNull(label, "label");
        this.light = Objects.requireNonNull(light, "light");
        this.rationale = Objects.requireNonNull(rationale, "rationale");
        this.evidence = List.copyOf(evidence);
        this.notes = List.copyOf(notes);
    }

    /** Structural checks only; availability, sunlight and evidence checks belong to FeasibilityValidator. */
    public Result<ShootingWindow> validate() {
        if (!end.isAfter(start)) return Result.failureKey("env.reject.order");
        if (arrival.isAfter(start)) return Result.failureKey("env.reject.arrival");
        if (!start.getZone().equals(end.getZone()) || !start.getZone().equals(arrival.getZone())) return Result.failureKey("env.reject.zone");
        if (!LIGHT.contains(light)) return Result.failureKey("env.reject.lightCategory", light);
        if (label.length() > 120 || conditions.length() > MAX_TEXT || rationale.length() > MAX_TEXT || evidence.size() > 16)
            return Result.failureKey("env.reject.text");
        return Result.success(this);
    }

    public ZonedDateTime start() { return start; }
    public ZonedDateTime end() { return end; }
    /** Suggested arrival: start minus the user-configured preparation lead time (start itself when none was given). */
    public ZonedDateTime arrival() { return arrival; }
    public String conditions() { return conditions; }
    public boolean estimated() { return estimated; }
    public String label() { return label; }
    public String light() { return light; }
    public String rationale() { return rationale; }
    public List<String> evidence() { return evidence; }
    public List<LocalizedMessage> notes() { return notes; }
    public java.time.Duration duration() { return java.time.Duration.between(start, end); }
}
