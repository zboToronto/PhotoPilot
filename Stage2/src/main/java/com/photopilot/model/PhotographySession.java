package com.photopilot.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.state.SessionEvent;
import com.photopilot.state.SessionState;

/**
 * Stage 1 UML element 24 (zero-based): {F01; State context}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PhotographySession {
    private UUID id;
    private String title;
    private LocalDate date;
    private Optional<GeoLocation> location;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
    private SessionState state;
    private Optional<ShootRequirement> requirements;
    private List<ShootPlan> plans;
    private List<PostShootReview> reviews;
    private Optional<EquipmentRequirementProfile> profile;
    private Optional<ExifStatistics> statistics;
    private List<ActivitySuggestion> savedIdeas;

    public PhotographySession(UUID id, String title, LocalDate date, Optional<GeoLocation> location, String notes, Instant createdAt, Instant updatedAt, SessionState state, Optional<ShootRequirement> requirements, List<ShootPlan> plans, List<PostShootReview> reviews, Optional<EquipmentRequirementProfile> profile, Optional<ExifStatistics> statistics, List<ActivitySuggestion> savedIdeas) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = Objects.requireNonNull(title, "title");
        this.date = Objects.requireNonNull(date, "date");
        this.location = Objects.requireNonNull(location, "location");
        this.notes = Objects.requireNonNull(notes, "notes");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.state = Objects.requireNonNull(state, "state");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.plans = List.copyOf(plans);
        this.reviews = List.copyOf(reviews);
        this.profile = Objects.requireNonNull(profile, "profile");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.savedIdeas = List.copyOf(savedIdeas);
    }

    public Result<PhotographySession> apply(SessionEvent event) {
        return Result.failure("NOT IMPLEMENTED: PhotographySession.apply");
    }

    public void attachRequirement(ShootRequirement requirement) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: PhotographySession.attachRequirement");
    }

    public void attachProfile(EquipmentRequirementProfile profile) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: PhotographySession.attachProfile");
    }

    public void attachStatistics(ExifStatistics stats) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: PhotographySession.attachStatistics");
    }

    public void appendApprovedPlan(ShootPlan plan) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: PhotographySession.appendApprovedPlan");
    }

    public void appendReview(PostShootReview review) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: PhotographySession.appendReview");
    }
}
