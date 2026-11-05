package com.photopilot.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 106 (zero-based): {F11; saved for future history retrieval}
 * Structured post-shoot review. {@code planDifferences} and {@code evidence} are deterministic (StatisticsCalculator
 * comparisons and statistic references); summary, observations, lessons and improvements are the validated AI
 * interpretation; limitations combine deterministic evidence limits with the model's own. The review carries a copy of
 * the statistics it interpreted, so replacing session statistics never rewrites a saved review. A DRAFT exists only in
 * memory; a session stores SAVED reviews. Fields after {@code limitations} are additive F11 schema fields; reviews saved
 * before them have no recorded session id, empty structured sections and status SAVED.
 */
public class PostShootReview {
    public static final String DRAFT = "DRAFT", SAVED = "SAVED";
    public static final int MAX_ITEMS = 12, MAX_TEXT = 1200;

    private UUID id;
    private Instant createdAt;
    private ExifStatistics statistics;
    private UUID comparedPlanId;
    private int comparedRevision;
    private List<String> planDifferences;
    private List<String> lessons;
    private List<String> limitations;
    private Optional<UUID> sessionId;
    private String summary;
    private List<String> technicalObservations;
    private List<String> equipmentObservations;
    private List<String> exposureObservations;
    private List<String> improvements;
    private List<String> evidence;
    private String status;

    public PostShootReview(UUID id, Instant createdAt, ExifStatistics statistics, UUID comparedPlanId, int comparedRevision, List<String> planDifferences, List<String> lessons, List<String> limitations) {
        this(id, createdAt, statistics, comparedPlanId, comparedRevision, planDifferences, lessons, limitations,
            Optional.empty(), "", List.of(), List.of(), List.of(), List.of(), List.of(), SAVED);
    }

    public PostShootReview(UUID id, Instant createdAt, ExifStatistics statistics, UUID comparedPlanId, int comparedRevision,
            List<String> planDifferences, List<String> lessons, List<String> limitations, Optional<UUID> sessionId, String summary,
            List<String> technicalObservations, List<String> equipmentObservations, List<String> exposureObservations,
            List<String> improvements, List<String> evidence, String status) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.comparedPlanId = Objects.requireNonNull(comparedPlanId, "comparedPlanId");
        this.comparedRevision = comparedRevision;
        this.planDifferences = List.copyOf(planDifferences);
        this.lessons = List.copyOf(lessons);
        this.limitations = List.copyOf(limitations);
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.summary = Objects.requireNonNull(summary, "summary");
        this.technicalObservations = List.copyOf(technicalObservations);
        this.equipmentObservations = List.copyOf(equipmentObservations);
        this.exposureObservations = List.copyOf(exposureObservations);
        this.improvements = List.copyOf(improvements);
        this.evidence = List.copyOf(evidence);
        this.status = Objects.requireNonNull(status, "status");
        if (comparedRevision < 1) throw new IllegalArgumentException("Compared plan revision must be positive.");
        if (!status.equals(DRAFT) && !status.equals(SAVED)) throw new IllegalArgumentException("Unknown review status.");
    }

    /** Structural limits of a review before it may be saved: bounded sections and some interpretation to keep. */
    public Result<PostShootReview> validate() {
        var sections = List.of(planDifferences, lessons, limitations, technicalObservations, equipmentObservations, exposureObservations, improvements);
        for (var section : sections)
            if (section.size() > MAX_ITEMS * 2 || section.stream().anyMatch(item -> item == null || item.isBlank() || item.length() > MAX_TEXT))
                return Result.failureKey("review.error.invalidReview");
        if (summary.length() > MAX_TEXT * 2 || evidence.size() > 60) return Result.failureKey("review.error.invalidReview");
        if (summary.isBlank() && technicalObservations.isEmpty() && equipmentObservations.isEmpty() && exposureObservations.isEmpty())
            return Result.failureKey("review.error.unsupported");
        return Result.success(this);
    }

    /** The copy a session stores once the user explicitly saves the draft. */
    public PostShootReview savedCopy() {
        return new PostShootReview(id, createdAt, statistics, comparedPlanId, comparedRevision, planDifferences, lessons, limitations,
            sessionId, summary, technicalObservations, equipmentObservations, exposureObservations, improvements, evidence, SAVED);
    }

    public UUID id() { return id; }
    public Instant createdAt() { return createdAt; }
    public ExifStatistics statistics() { return statistics; }
    public UUID comparedPlanId() { return comparedPlanId; }
    public int comparedRevision() { return comparedRevision; }
    public List<String> planDifferences() { return planDifferences; }
    public List<String> lessons() { return lessons; }
    public List<String> limitations() { return limitations; }
    public Optional<UUID> sessionId() { return sessionId == null ? Optional.empty() : sessionId; }
    public String summary() { return summary; }
    public List<String> technicalObservations() { return technicalObservations; }
    public List<String> equipmentObservations() { return equipmentObservations; }
    public List<String> exposureObservations() { return exposureObservations; }
    public List<String> improvements() { return improvements; }
    public List<String> evidence() { return evidence; }
    public String status() { return status; }
}
