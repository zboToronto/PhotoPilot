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
    /** F07 explicitly selected owned-gear loadout (immutable snapshots), consumed by F10. */
    private Optional<Loadout> selectedLoadout;
    private transient String revision = "";

    public PhotographySession(UUID id, String title, LocalDate date, Optional<GeoLocation> location, String notes, Instant createdAt, Instant updatedAt, SessionState state, Optional<ShootRequirement> requirements, List<ShootPlan> plans, List<PostShootReview> reviews, Optional<EquipmentRequirementProfile> profile, Optional<ExifStatistics> statistics, List<ActivitySuggestion> savedIdeas) {
        this(id, title, date, location, notes, createdAt, updatedAt, state, requirements, plans, reviews, profile, statistics, savedIdeas, Optional.empty());
    }

    public PhotographySession(UUID id, String title, LocalDate date, Optional<GeoLocation> location, String notes, Instant createdAt, Instant updatedAt, SessionState state, Optional<ShootRequirement> requirements, List<ShootPlan> plans, List<PostShootReview> reviews, Optional<EquipmentRequirementProfile> profile, Optional<ExifStatistics> statistics, List<ActivitySuggestion> savedIdeas, Optional<Loadout> selectedLoadout) {
        this.id =Objects.requireNonNull(id, "id");
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
        this.selectedLoadout = Objects.requireNonNull(selectedLoadout, "selectedLoadout");
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
    public Optional<Loadout> selectedLoadout() { return selectedLoadout == null ? Optional.empty() : selectedLoadout; }
    public String revision() { return revision == null ? "" : revision; }
    /** Repository revision token; callers cannot bypass the persisted-content comparison. */
    public PhotographySession withRevision(String revision) {
        var copy = new PhotographySession(id, title, date, location, notes, createdAt, updatedAt,
            state, requirements, plans, reviews, profile, statistics, savedIdeas, selectedLoadout);
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
            requirements, plans, reviews, profile, statistics, savedIdeas, selectedLoadout).withRevision(revision());
    }
    public Result<PhotographySession> validate() {
        if (id == null || title == null || title.isBlank() || title.length() > 200 || date == null
                || notes == null || notes.length() > 20000 || location == null || state == null
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || requirements == null || plans == null || reviews == null || profile == null
                || statistics == null || savedIdeas == null || selectedLoadout == null)
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
        if (savedIdeas.stream().map(ActivitySuggestion::id).distinct().count() != savedIdeas.size()) return Result.failureKey("error.session.links");
        var reviewIds = new java.util.HashSet<UUID>();
        for (PostShootReview review : reviews) {
            if (!review.status().equals(PostShootReview.SAVED) || review.sessionId().filter(owner -> !owner.equals(id)).isPresent())
                return Result.failureKey("error.session.links");
            if (!reviewIds.add(review.id()) || plans.stream().noneMatch(plan -> plan.approved()
                    && plan.id().equals(review.comparedPlanId()) && plan.revision() == review.comparedRevision()))
                return Result.failureKey("error.session.links");
        }
        return Result.success(this);
    }

    /**
     * F03: copy carrying a confirmed, valid requirement; the repository revision is kept for the stale-save check.
     * Completed/reviewed sessions keep their historical requirement.
     */
    public Result<PhotographySession> attachRequirement(ShootRequirement requirement) {
        if (requirement == null || !requirement.confirmed()) return Result.failureKey("requirement.error.unconfirmed");
        var valid = requirement.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        if (!state.canEditPlan()) return Result.failureKey("requirement.error.historical");
        Instant now = Instant.now();
        if (!now.isAfter(updatedAt)) now = updatedAt.plusNanos(1);
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, now, state,
            Optional.of(requirement), plans, reviews, profile, statistics, savedIdeas, selectedLoadout).withRevision(revision()));
    }

    /**
     * F06 (minimal confirmation path): copy carrying a reviewed capability profile. Like attachRequirement it returns
     * a Result copy instead of mutating; historical sessions keep their profile. An existing F07 selection is kept and
     * re-evaluated against the new profile whenever it is displayed or reselected.
     */
    public Result<PhotographySession> attachProfile(EquipmentRequirementProfile profile) {
        if (profile == null || !profile.reviewed()) return Result.failureKey("needs.error.unconfirmed");
        if (!profile.problems().isEmpty()) return Result.failures(profile.problems());
        if (!state.canEditPlan()) return Result.failureKey("needs.error.historical");
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, next(), state,
            requirements, plans, reviews, Optional.of(profile), statistics, savedIdeas, selectedLoadout).withRevision(revision()));
    }

    /** F07: copy carrying an explicitly selected, already revalidated loadout; replacement policy belongs to the caller. */
    public Result<PhotographySession> attachSelectedLoadout(Loadout loadout) {
        if (loadout == null) return Result.failureKey("match.error.noSelection");
        if (!loadout.compatibility().isUsable()) return Result.failureKey("match.error.notUsable");
        if (!state.canEditPlan()) return Result.failureKey("match.error.historical");
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, next(), state,
            requirements, plans, reviews, profile, statistics, savedIdeas, Optional.of(loadout)).withRevision(revision()));
    }
    /**
     * F02: copy with one more saved idea; returns a Result copy like attachRequirement. Historical sessions keep their
     * record, an idea is saved at most once, and a requirement or plan of the session is never touched.
     */
    public Result<PhotographySession> attachIdea(ActivitySuggestion idea) {
        if (idea == null) return Result.failureKey("discover.error.idea");
        var valid = idea.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        if (!state.canEditPlan()) return Result.failureKey("discover.error.historical");
        if (savedIdeas.stream().anyMatch(saved -> saved.id().equals(idea.id()))) return Result.failureKey("discover.error.alreadySaved", title);
        if (savedIdeas.size() >= MAX_SAVED_IDEAS) return Result.failureKey("discover.error.tooManySaved", MAX_SAVED_IDEAS);
        var ideas = new java.util.ArrayList<>(savedIdeas); ideas.add(idea);
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, next(), state,
            requirements, plans, reviews, profile, statistics, ideas, selectedLoadout).withRevision(revision()));
    }
    public static final int MAX_SAVED_IDEAS = 50;
    private Instant next() {
        Instant now = Instant.now();
        return now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1);
    }

    /**
     * F11 UC12: copy carrying validated statistics of a completed session (like attachRequirement it returns a Result
     * copy). Replacement policy belongs to ReviewService; a reviewed session keeps the evidence it was reviewed with.
     */
    public Result<PhotographySession> attachStatistics(ExifStatistics stats) {
        if (stats == null) return Result.failureKey("review.error.noUsable");
        var valid = stats.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        if (!(state instanceof com.photopilot.state.CompletedState)) return Result.failureKey("review.error.notCompleted", status());
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, next(), state,
            requirements, plans, reviews, profile, Optional.of(stats), savedIdeas, selectedLoadout).withRevision(revision()));
    }

    /**
     * F10: copy with one more approved plan revision appended to the history; earlier revisions are kept unchanged.
     * Like attachRequirement it returns a Result copy instead of mutating; the state transition and the atomic save
     * belong to the caller (PlanningService.approve).
     */
    public Result<PhotographySession> appendApprovedPlan(ShootPlan plan) {
        if (plan == null || !plan.approved()) return Result.failureKey("plan.error.notApproved");
        if (!state.canEditPlan()) return Result.failureKey("plan.error.historical");
        if (plans.stream().anyMatch(saved -> saved.id().equals(plan.id()) && saved.revision() >= plan.revision()))
            return Result.failureKey("plan.error.stale");
        var history = new java.util.ArrayList<>(plans); history.add(plan);
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, next(), state,
            requirements, history, reviews, profile, statistics, savedIdeas, selectedLoadout).withRevision(revision()));
    }

    /**
     * F11 UC13: copy with one more explicitly saved review of an approved plan revision of this session. The
     * REVIEW_SAVED transition and the atomic save belong to the caller (ReviewService.save).
     */
    public Result<PhotographySession> appendReview(PostShootReview review) {
        if (review == null || !review.status().equals(PostShootReview.SAVED)) return Result.failureKey("review.error.noDraft");
        if (review.sessionId().filter(owner -> !owner.equals(id)).isPresent()) return Result.failureKey("review.error.foreign");
        if (!(state instanceof com.photopilot.state.CompletedState)) return Result.failureKey("review.error.notCompleted", status());
        if (reviews.stream().anyMatch(saved -> saved.id().equals(review.id()))) return Result.failureKey("review.error.alreadySaved");
        var valid = review.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        var history = new java.util.ArrayList<>(reviews); history.add(review);
        return Result.success(new PhotographySession(id, title, date, location, notes, createdAt, next(), state,
            requirements, plans, history, profile, statistics, savedIdeas, selectedLoadout).withRevision(revision()));
    }
}
