package com.photopilot.service;

import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.EnvironmentEvidence;
import com.photopilot.validation.PlanValidator;
import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneState;
import com.photopilot.workflow.MilestoneStatus;
import com.photopilot.workflow.SessionWorkflow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/**
 * Session-level workflow progress (milestones 01-07), derived only from persisted records: the confirmed requirement,
 * the reviewed capability profile, the saved selection, the recorded environment check, approved plans, the lifecycle
 * state, statistics and saved reviews, plus the current inventory for the staleness of saved gear. It reuses the
 * services' own rules (PlanningService.usableProfile, the saved-selection recheck, PlanValidator.constraintsOf,
 * PlanningService.sameShoot, EnvironmentEvidence) instead of restating them. No AI, network or writes; a running
 * request or an unsaved draft never changes the result. Shared by GUI and CLI.
 */
public class SessionWorkflowService {
    private final SessionService sessions;
    private final InventoryService inventory;
    private final CompatibilityValidator validator = new CompatibilityValidator();
    private final Clock clock;

    public SessionWorkflowService(SessionService sessions, InventoryService inventory, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** One session: one session read and one inventory read. */
    public Result<SessionWorkflow> of(UUID id) {
        var found = sessions.open(id);
        return found.isSuccess() ? Result.success(evaluate(found.value().orElseThrow(), gear())) : Result.failure(found);
    }

    /** Every session of the active profile in the repository's order: one sessions read and one inventory read. */
    public Result<List<SessionWorkflow>> overview() {
        var found = sessions.search("");
        if (!found.isSuccess()) return Result.failure(found);
        var gear = gear();
        return Result.success(found.value().orElseThrow().stream().map(session -> evaluate(session, gear)).toList());
    }

    /** The session as stored, with the current inventory (one read). */
    public SessionWorkflow evaluate(PhotographySession session) { return evaluate(session, gear()); }

    /**
     * Pure evaluation. {@code gear} is the current inventory; without it saved gear is not re-checked (a failed read
     * never turns a completed milestone into one that needs review).
     */
    public SessionWorkflow evaluate(PhotographySession session, Optional<GearInventory> gear) {
        return evaluate(session, gear, LocalDate.now(clock), validator);
    }

    /** The stored records alone, today: saved gear is not re-checked against the inventory. */
    public static SessionWorkflow evaluateStored(PhotographySession session) {
        return evaluate(session, Optional.empty(), LocalDate.now(), new CompatibilityValidator());
    }

    static SessionWorkflow evaluate(PhotographySession session, Optional<GearInventory> gear, LocalDate today, CompatibilityValidator validator) {
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(gear, "gear");
        boolean editable = session.state().canEditPlan();
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        var selection = session.selectedLoadout();
        var plan = PlanningService.latest(session.plans());
        boolean planned = !session.status().equals("DRAFT");
        LocalDate shootingDate = requirement.map(r -> PlanAssembly.shootingDate(session, r)).orElse(session.date());
        // Records are re-checked against upstream changes only while they can still be changed: completed and reviewed
        // sessions keep their records' meaning, and once a planned shoot's date has passed the next step is recording the
        // shoot, not revising what led to it. A draft is re-checked whatever its date, except its environment check, which
        // F09 cannot redo for a past date.
        boolean recheck = editable && !(planned && shootingDate.isBefore(today));
        var states = new ArrayList<MilestoneState>();

        // 01 Shooting requirements: only confirmed requirements are ever stored.
        if (requirement.isPresent()) states.add(state(Milestone.REQUIREMENTS, MilestoneStatus.COMPLETED, "workflow.detail.confirmed"));
        else states.add(editable ? state(Milestone.REQUIREMENTS, MilestoneStatus.NOT_STARTED, "workflow.detail.describe")
            : state(Milestone.REQUIREMENTS, MilestoneStatus.NOT_APPLICABLE, "workflow.detail.notRecorded"));

        // 02 Equipment requirements: the confirmed profile while it is still consistent with the confirmed requirement.
        if (profile.isPresent()) {
            boolean current = !recheck || PlanningService.usableProfile(session).isSuccess();
            states.add(current ? state(Milestone.EQUIPMENT_NEEDS, MilestoneStatus.COMPLETED, profile.get().aiAssisted() ? "workflow.detail.profileAi" : "workflow.detail.profileManual")
                : state(Milestone.EQUIPMENT_NEEDS, MilestoneStatus.NEEDS_REVIEW, "workflow.detail.profileStale"));
        } else if (!editable) states.add(state(Milestone.EQUIPMENT_NEEDS, MilestoneStatus.NOT_APPLICABLE, "workflow.detail.notRecorded"));
        else if (requirement.isEmpty()) states.add(state(Milestone.EQUIPMENT_NEEDS, MilestoneStatus.BLOCKED, "workflow.detail.needsRequirement"));
        else states.add(state(Milestone.EQUIPMENT_NEEDS, MilestoneStatus.NOT_STARTED, "workflow.detail.defineNeeds"));

        // 03 Equipment selection. Before a plan: the saved F07 selection, re-checked with F07's own rules (UC14
        // compatibility and F05 availability). Once a plan is approved its gear is what the shoot uses (F10 may plan with
        // another offer than the selection, and approval requires gear), so its staleness belongs to 05.
        if (planned) states.add(state(Milestone.GEAR_SELECTION, MilestoneStatus.COMPLETED,
            selection.isPresent() ? "workflow.detail.selected" : "workflow.detail.selectedInPlan", selection.map(l -> l.gear().size()).orElse(0)));
        else if (selection.isPresent()) {
            boolean current = !recheck || gear.isEmpty()
                || PlanningService.selectionCheck(validator, selection.get(), requirement.orElse(null), gear.get()).current();
            states.add(current ? state(Milestone.GEAR_SELECTION, MilestoneStatus.COMPLETED, "workflow.detail.selected", selection.get().gear().size())
                : state(Milestone.GEAR_SELECTION, MilestoneStatus.NEEDS_REVIEW, "workflow.detail.selectionStale"));
        } else if (PlanningService.usableProfile(session).isSuccess()) states.add(state(Milestone.GEAR_SELECTION, MilestoneStatus.NOT_STARTED, "workflow.detail.select"));
        else states.add(state(Milestone.GEAR_SELECTION, MilestoneStatus.BLOCKED, "workflow.detail.needsProfile"));

        // 04 Environment assessment (optional for planning): the recorded check while it still applies to the confirmed
        // shoot. Not recorded: recommended until a plan is approved, then left out of the count (skipped), as it is once
        // the shooting date has passed (F09 refuses past dates).
        var check = session.environmentCheck();
        if (check.isPresent()) {
            boolean current = !recheck || EnvironmentEvidence.applies(check.get(), requirement.orElse(null), shootingDate).usable();
            if (current) states.add(state(Milestone.ENVIRONMENT, MilestoneStatus.COMPLETED, "workflow.detail.environment", check.get().place().label(), check.get().date().toString()));
            // A check that no longer applies to a shoot date already past cannot be redone (F09 refuses past dates): skipped.
            else if (shootingDate.isBefore(today)) states.add(state(Milestone.ENVIRONMENT, MilestoneStatus.NOT_APPLICABLE, "workflow.detail.environmentSkipped"));
            else states.add(state(Milestone.ENVIRONMENT, MilestoneStatus.NEEDS_REVIEW, "workflow.detail.environmentStale"));
        } else if (planned || !editable || shootingDate.isBefore(today))
            states.add(state(Milestone.ENVIRONMENT, MilestoneStatus.NOT_APPLICABLE, "workflow.detail.environmentSkipped"));
        else states.add(state(Milestone.ENVIRONMENT, MilestoneStatus.NOT_STARTED, "workflow.detail.assess"));

        // 05 Photography plan: the latest approved revision while it still records the confirmed shoot and gear.
        if (plan.isPresent()) {
            var stale = recheck ? planStaleness(plan.get(), session, requirement, profile, gear, validator) : Optional.<String>empty();
            states.add(stale.isEmpty() ? state(Milestone.PLAN, MilestoneStatus.COMPLETED, "workflow.detail.planApproved", plan.get().revision())
                : state(Milestone.PLAN, MilestoneStatus.NEEDS_REVIEW, stale.get(), plan.get().revision()));
        } else if (requirement.isEmpty() || profile.isEmpty())
            states.add(state(Milestone.PLAN, MilestoneStatus.BLOCKED, requirement.isEmpty() ? "workflow.detail.needsRequirement" : "workflow.detail.needsProfile"));
        else states.add(state(Milestone.PLAN, MilestoneStatus.NOT_STARTED, "workflow.detail.generate"));

        // 06 Shooting completion: only the explicit lifecycle action completes it, never the calendar.
        states.add(switch (session.status()) {
            case "DRAFT" -> state(Milestone.SHOOT, MilestoneStatus.BLOCKED, "workflow.detail.needsPlan");
            case "PLANNED" -> shootingDate.isBefore(today) ? state(Milestone.SHOOT, MilestoneStatus.ACTION_REQUIRED, "workflow.detail.shootOverdue", shootingDate.toString())
                : state(Milestone.SHOOT, MilestoneStatus.NOT_STARTED, "workflow.detail.shootReady", shootingDate.toString());
            default -> state(Milestone.SHOOT, MilestoneStatus.COMPLETED, "workflow.detail.shot");
        });

        // 07 Post-shoot review: statistics of the completed shoot, then the saved review.
        states.add(switch (session.status()) {
            case "DRAFT", "PLANNED" -> state(Milestone.REVIEW, MilestoneStatus.BLOCKED, "workflow.detail.needsShoot");
            // The same statistics ReviewService requires for a review (at least one usable photo).
            case "COMPLETED" -> session.statistics().filter(stats -> stats.usableCount() > 0).isPresent()
                ? state(Milestone.REVIEW, MilestoneStatus.IN_PROGRESS, "workflow.detail.analysed")
                : state(Milestone.REVIEW, MilestoneStatus.NOT_STARTED, "workflow.detail.importPhotos");
            default -> state(Milestone.REVIEW, MilestoneStatus.COMPLETED, "workflow.detail.reviewed", session.reviews().size());
        });

        return new SessionWorkflow(session, phase(session, requirement.isPresent()), states);
    }

    /**
     * Whether the latest approved plan still records the session's current shoot: the fixed constraints it was approved
     * against (the requirement's limits and the profile's mandatory needs, as PlanValidator compares them), the shooting
     * date and confirmed place (PlanningService.sameShoot), and owned gear that is still in the inventory. Plans written
     * before F10 recorded their constraints or shoot details carry none; those parts are not re-checked. The reason tells
     * which F10 path applies: changed constraints need a new plan (F10 regenerates only then), a changed date, place or
     * gear a revision of the approved one.
     */
    private static Optional<String> planStaleness(ShootPlan plan, PhotographySession session, Optional<ShootRequirement> requirement,
            Optional<EquipmentRequirementProfile> profile, Optional<GearInventory> gear, CompatibilityValidator validator) {
        if (requirement.isEmpty()) return Optional.empty();
        if (!plan.fixedConstraints().isEmpty() && !plan.fixedConstraints().equals(PlanValidator.constraintsOf(requirement.get(), profile)))
            return Optional.of("workflow.detail.planStale");
        if (plan.sessionInfo().containsKey("date") && !PlanningService.sameShoot(plan, session, requirement.get()))
            return Optional.of("workflow.detail.planShootChanged");
        boolean owned = gear.isEmpty() || plan.loadout().map(loadout -> validator.availability(loadout, gear.get()).isEmpty()).orElse(true);
        return owned ? Optional.empty() : Optional.of("workflow.detail.planShootChanged");
    }

    private static SessionWorkflow.Phase phase(PhotographySession session, boolean described) {
        return switch (session.status()) {
            case "PLANNED" -> SessionWorkflow.Phase.READY;
            case "COMPLETED" -> SessionWorkflow.Phase.AWAITING_REVIEW;
            case "REVIEWED" -> SessionWorkflow.Phase.REVIEWED;
            default -> described ? SessionWorkflow.Phase.PLANNING : SessionWorkflow.Phase.GETTING_STARTED;
        };
    }

    private static MilestoneState state(Milestone milestone, MilestoneStatus status, String key, Object... arguments) {
        return new MilestoneState(milestone, status, LocalizedMessage.of(key, arguments));
    }

    private Optional<GearInventory> gear() {
        var loaded = inventory.list("");
        return loaded.isSuccess() ? loaded.value() : Optional.empty();
    }
}
