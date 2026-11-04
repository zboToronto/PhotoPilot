package com.photopilot.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 76 (zero-based): {F09; guidance grounded in timestamped source data}
 * F09 result, reusable by F10: the factual context, deterministically computed light phases (estimates), the
 * user's resolved availability, validated AI-proposed windows, rejected proposals with reasons, the labelled AI
 * interpretation and every limitation. Nothing here is saved by F09.
 */
public class EnvironmentAssessment {
    /** The user's on-site period resolved in the verified location time zone. */
    public record Availability(ZonedDateTime start, ZonedDateTime end) {
        public Availability {
            Objects.requireNonNull(start); Objects.requireNonNull(end);
            if (!end.isAfter(start)) throw new IllegalArgumentException("Availability must end after it starts.");
        }
        public java.time.Duration duration() { return java.time.Duration.between(start, end); }
        public boolean contains(ZonedDateTime from, ZonedDateTime to) { return !from.isBefore(start) && !to.isAfter(end); }
    }
    /** A computed light phase (golden/blue hour); always an estimate from a documented solar-position model. */
    public record LightPhase(String kind, LocalDate date, ZonedDateTime start, ZonedDateTime end, String source) {
        public LightPhase { Objects.requireNonNull(kind); Objects.requireNonNull(date); Objects.requireNonNull(start); Objects.requireNonNull(end); Objects.requireNonNull(source); }
        public boolean golden() { return kind.startsWith("golden"); }
        public boolean blue() { return kind.startsWith("blue"); }
    }
    /** A model proposal that failed validation; times are kept as proposed text because they may be unparseable. */
    public record RejectedWindow(String label, String start, String end, List<LocalizedMessage> reasons) {
        public RejectedWindow { label = Objects.requireNonNullElse(label, ""); start = Objects.requireNonNullElse(start, ""); end = Objects.requireNonNullElse(end, ""); reasons = List.copyOf(reasons); }
    }
    /** Gemini's photographic interpretation: advice, never a measurement. */
    public record Interpretation(String summary, List<String> considerations, List<String> alternatives) {
        public Interpretation { summary = Objects.requireNonNullElse(summary, ""); considerations = List.copyOf(considerations); alternatives = List.copyOf(alternatives); }
    }

    private EnvironmentContext context;
    private List<ShootingWindow> windows;
    private List<String> limitations;
    private EnvironmentRequest request;
    private String subject = "";
    private Optional<Availability> availability = Optional.empty();
    private List<LightPhase> lightPhases = List.of();
    private List<RejectedWindow> rejected = List.of();
    private Optional<Interpretation> interpretation = Optional.empty();
    private List<LocalizedMessage> notices = List.of();
    private Set<String> staleFields = Set.of();
    private Instant assessedAt = Instant.EPOCH;

    public EnvironmentAssessment(EnvironmentContext context, List<ShootingWindow> windows, List<String> limitations) {
        this.context = Objects.requireNonNull(context, "context");
        this.windows = List.copyOf(windows);
        this.limitations = List.copyOf(limitations);
    }

    public EnvironmentAssessment(EnvironmentContext context, EnvironmentRequest request, String subject, Optional<Availability> availability,
            List<LightPhase> lightPhases, List<ShootingWindow> windows, List<RejectedWindow> rejected, Optional<Interpretation> interpretation,
            List<LocalizedMessage> notices, Set<String> staleFields, Instant assessedAt) {
        this(context, windows, List.of());
        this.request = Objects.requireNonNull(request, "request");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.lightPhases = List.copyOf(lightPhases);
        this.rejected = List.copyOf(rejected);
        this.interpretation = Objects.requireNonNull(interpretation, "interpretation");
        this.notices = List.copyOf(notices);
        this.staleFields = Set.copyOf(staleFields);
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt");
    }

    /** Copy after deterministic validation: accepted windows, all rejections and any additional notices. */
    public EnvironmentAssessment validated(List<ShootingWindow> accepted, List<RejectedWindow> rejections, List<LocalizedMessage> moreNotices) {
        var all = new ArrayList<>(notices); all.addAll(moreNotices);
        var copy = new EnvironmentAssessment(context, request, subject, availability, lightPhases, accepted, rejections, interpretation, all, staleFields, assessedAt);
        copy.limitations = limitations;
        return copy;
    }
    public EnvironmentAssessment withNotices(List<LocalizedMessage> moreNotices) { return validated(windows, rejected, moreNotices); }

    /**
     * Identifiers the model may cite: sun events and light phases (suffix "+1" for the following day), the
     * provider's daily summary and each forecast hour ("h-" + local date-time). Deterministic for one context.
     */
    public static Map<String, Object> evidenceIndex(EnvironmentContext context, List<LightPhase> phases) {
        var index = new LinkedHashMap<String, Object>();
        var zone = context.zone().orElse(java.time.ZoneOffset.UTC);
        LocalDate base = context.date().orElse(null);
        if (!context.weather().isEmpty()) index.put("daily", context.weather());
        for (var event : context.sunEvents()) index.put(event.kind() + suffix(base, event.date()), event);
        for (var phase : phases) index.put(phase.kind() + suffix(base, phase.date()), phase);
        for (var hour : context.hourly()) index.put(hourId(hour.time().atZone(zone)), hour);
        return index;
    }
    public static String hourId(ZonedDateTime local) { return "h-" + local.toLocalDateTime().withSecond(0).withNano(0); }
    private static String suffix(LocalDate base, LocalDate day) {
        if (base == null || day.equals(base)) return "";
        long offset = java.time.temporal.ChronoUnit.DAYS.between(base, day);
        return (offset > 0 ? "+" : "") + offset;
    }
    public Set<String> evidenceIds() { return evidenceIndex(context, lightPhases).keySet(); }

    public EnvironmentContext context() { return context; }
    public List<ShootingWindow> windows() { return windows; }
    /** English limitation text (original constructor values plus rendered notices). */
    public List<String> limitations() {
        var all = new ArrayList<>(limitations);
        notices.forEach(notice -> all.add(EnglishMessages.text(notice.key(), notice.arguments().toArray())));
        return all;
    }
    public Optional<EnvironmentRequest> request() { return Optional.ofNullable(request); }
    public String subject() { return subject; }
    public Optional<Availability> availability() { return availability; }
    public List<LightPhase> lightPhases() { return lightPhases; }
    public List<RejectedWindow> rejected() { return rejected; }
    public Optional<Interpretation> interpretation() { return interpretation; }
    public boolean aiInterpreted() { return interpretation.isPresent(); }
    public List<LocalizedMessage> notices() { return notices; }
    /** Evidence groups that were already stale when the assessment was made. */
    public Set<String> staleFields() { return staleFields; }
    public Instant assessedAt() { return assessedAt; }
}
