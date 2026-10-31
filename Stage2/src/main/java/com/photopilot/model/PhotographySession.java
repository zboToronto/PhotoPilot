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
 * Session metadata and guarded lifecycle; linked feature workflows retain their own approval boundaries.
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
    private transient String revision = "";

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
        if (event == null) return Result.failureKey("error.session.state");
        var next = state.next(this, event);
        if (!next.isSuccess()) return Result.failure(next);
        PhotographySession changed = copy(title, date, location, notes, next.value().orElseThrow());
        return Result.success(changed);
    }

    public UUID id() { return id; }
    public String title() { return title; }
    public LocalDate date() { return date; }
    public Optional<GeoLocation> location() { return location; }
    public String notes() { return notes; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public SessionState state() { return state; }
    public String status() { return state.getClass().getSimpleName().replace("State", "").toUpperCase(java.util.Locale.ROOT); }
    public Optional<ShootRequirement> requirements() { return requirements; }
    public List<ShootPlan> plans() { return List.copyOf(plans); }
    public List<PostShootReview> reviews() { return List.copyOf(reviews); }
    public Optional<EquipmentRequirementProfile> profile() { return profile; }
    public Optional<ExifStatistics> statistics() { return statistics; }
    public List<ActivitySuggestion> savedIdeas() { return List.copyOf(savedIdeas); }
    public String revision() { return revision == null ? "" : revision; }
    /** Repository revision token; callers cannot bypass the persisted-content comparison. */
    public PhotographySession withRevision(String revision) {
        var copy = new PhotographySession(id, title, date, location, notes, createdAt, updatedAt,
            state, requirements, plans, reviews, profile, statistics, savedIdeas);
        copy.revision = Objects.requireNonNull(revision);
        return copy;
    }
    public PhotographySession edit(String title, LocalDate date, Optional<GeoLocation> location, String notes) {
        return copy(title, date, location, notes, state);
    }
    private PhotographySession copy(String title, LocalDate date, Optional<GeoLocation> location, String notes, SessionState state) {
        Instant now = Instant.now();
        if (!now.isAfter(updatedAt)) now = updatedAt.plusNanos(1);
        return new PhotographySession(id, title, date, location, notes, createdAt, now, state,
            requirements, plans, reviews, profile, statistics, savedIdeas).withRevision(revision());
    }
    public Result<PhotographySession> validate() {
        if (id == null || title == null || title.isBlank() || title.length() > 200 || date == null
                || notes == null || notes.length() > 20000 || location == null || state == null
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || requirements == null || plans == null || reviews == null || profile == null
                || statistics == null || savedIdeas == null)
            return Result.failureKey("error.session.details");
        if (location.isPresent() && !location.get().validate().isSuccess()) return Result.failure(location.get().validate());
        if (!java.util.Set.of("DRAFT", "PLANNED", "COMPLETED", "REVIEWED").contains(status()))
            return Result.failureKey("error.session.state");
        if (!status().equals("DRAFT") && plans.stream().noneMatch(ShootPlan::approved))
            return Result.failureKey("error.session.planRequired");
        if (status().equals("REVIEWED") && reviews.isEmpty()) return Result.failureKey("error.session.reviewRequired");
        var planKeys = new java.util.HashSet<String>();
        for (ShootPlan plan : plans) {
            if (!planKeys.add(plan.id() + ":" + plan.revision())) return Result.failureKey("error.session.links");
        }
        var reviewIds = new java.util.HashSet<UUID>();
        for (PostShootReview review : reviews) {
            if (!reviewIds.add(review.id()) || plans.stream().noneMatch(plan -> plan.approved()
                    && plan.id().equals(review.comparedPlanId()) && plan.revision() == review.comparedRevision()))
                return Result.failureKey("error.session.links");
        }
        return Result.success(this);
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
