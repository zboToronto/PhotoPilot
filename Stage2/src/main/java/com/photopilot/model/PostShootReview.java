package com.photopilot.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stage 1 UML element 106 (zero-based): {F11; saved for future history retrieval}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PostShootReview {
    private UUID id;
    private Instant createdAt;
    private ExifStatistics statistics;
    private UUID comparedPlanId;
    private int comparedRevision;
    private List<String> planDifferences;
    private List<String> lessons;
    private List<String> limitations;

    public PostShootReview(UUID id, Instant createdAt, ExifStatistics statistics, UUID comparedPlanId, int comparedRevision, List<String> planDifferences, List<String> lessons, List<String> limitations) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.comparedPlanId = Objects.requireNonNull(comparedPlanId, "comparedPlanId");
        this.comparedRevision = comparedRevision;
        this.planDifferences = List.copyOf(planDifferences);
        this.lessons = List.copyOf(lessons);
        this.limitations = List.copyOf(limitations);
        if (comparedRevision < 1) throw new IllegalArgumentException("Compared plan revision must be positive.");
    }

    public UUID id() { return id; }
    public UUID comparedPlanId() { return comparedPlanId; }
    public int comparedRevision() { return comparedRevision; }
}
