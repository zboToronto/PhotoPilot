package com.photopilot.service;

import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.PlanValidator;
import com.photopilot.workflow.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static com.photopilot.workflow.Milestone.*;
import static com.photopilot.workflow.MilestoneStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Session workflow progress (milestones 01-07) derived from stored records only, evaluated with a fixed "today": only
 * confirmed requirements and reviewed profiles count, saved records are re-checked while the session is editable and
 * its shoot is ahead, the optional environment check never blocks the plan, the shoot is never completed by the
 * calendar, historical sessions keep their records' meaning, and progress reaches 100% only when complete.
 */
class SessionWorkflowServiceTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 23), AHEAD = TODAY.plusDays(12), PAST = TODAY.minusDays(9);
    static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    static final CompatibilityValidator VALIDATOR = new CompatibilityValidator();
    static final UUID PLAN_ID = UUID.randomUUID();
    final GearFixtures.Airshow kit = new GearFixtures.Airshow();
    /** F07 selection: one Sony E body with a Sony E standard zoom mounted, both owned. */
    final Loadout selection = new Loadout(UUID.randomUUID(), List.of(kit.a7.snapshot(), kit.standard.snapshot()), Map.of(kit.a7.id(), kit.standard.id()),
        new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());

    /** Confirmed: one body, lens changes allowed, a label-only place and a free-text time window. */
    static ShootRequirement requirement(String subject, LocalDate date, int cameras) {
        return new ShootRequirement("Portrait walk in the park", subject, List.of("Soft light"), Optional.empty(), Optional.of(date), "late afternoon",
            Optional.of(GeoLocation.manual("High Park")), Optional.of(Duration.ofHours(2)), Optional.of(cameras), Optional.of(true), Optional.empty(), "",
            List.of(), Map.of(), Map.of(), List.of(), true);
    }
    static final ShootRequirement R = requirement("Portrait", AHEAD, 1), R_PAST = requirement("Portrait", PAST, 1);
    /** Reviewed: exactly one body (a requirement for two makes it stale) and a standard focal range. */
    static EquipmentRequirementProfile profile(String origin) {
        return new EquipmentRequirementProfile(List.of(GearFixtures.need("cameraCount", 1.0, 1.0, 1, true), GearFixtures.need("focalLength", 35.0, 85.0, 2, true)),
            true, origin, List.of());
    }
    static final EquipmentRequirementProfile P = profile(EquipmentRequirementProfile.MANUAL);

    static PhotographySession session(SessionState state, LocalDate date, Optional<ShootRequirement> requirement, Optional<EquipmentRequirementProfile> profile,
            Optional<Loadout> selected, Optional<EnvironmentCheck> check, List<ShootPlan> plans, Optional<ExifStatistics> statistics, List<PostShootReview> reviews) {
        var at = Instant.parse("2026-11-20T10:00:00Z");
        return new PhotographySession(UUID.randomUUID(), "Park portraits", date, Optional.empty(), "", at, at, state, requirement, plans, reviews, profile,
            statistics, List.of(), selected, check);
    }
    static PhotographySession draft(Optional<ShootRequirement> requirement, Optional<EquipmentRequirementProfile> profile, Optional<Loadout> selected, Optional<EnvironmentCheck> check) {
        return session(new DraftState(), AHEAD, requirement, profile, selected, check, List.of(), Optional.empty(), List.of());
    }
    static PhotographySession planned(ShootRequirement requirement, ShootPlan... plans) {
        return session(new PlannedState(), AHEAD, Optional.of(requirement), Optional.of(P), Optional.empty(), Optional.empty(), List.of(plans), Optional.empty(), List.of());
    }
    PhotographySession historical(SessionState state, Optional<ExifStatistics> statistics, List<PostShootReview> reviews) {
        return session(state, PAST, Optional.of(R_PAST), Optional.of(P), Optional.empty(), Optional.empty(), List.of(plan(1, R_PAST, P)), statistics, reviews);
    }
    /** Approved plan as F10 writes it: the fixed constraints it was approved against, the shoot date and place, its gear. */
    ShootPlan plan(int revision, ShootRequirement requirement, EquipmentRequirementProfile profile) {
        return new ShootPlan(PLAN_ID, revision, true, List.of(), Optional.of(selection), Map.of("iso", "200"), List.of(), List.of(), List.of(), List.of(),
            PlanValidator.constraintsOf(requirement, Optional.of(profile)), List.of(), "Plan",
            Map.of("date", requirement.date().orElseThrow().toString(), "location", "High Park"), Map.of(), List.of(), List.of(), List.of(), "");
    }
    /** A plan written before F10 recorded its constraints and shoot details (the 12-value Stage 1 form). */
    ShootPlan olderPlan() {
        return new ShootPlan(PLAN_ID, 1, true, List.of(), Optional.of(selection), Map.of("iso", "200"), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }
    /** Record of an F09 assessment for High Park (with coordinates) on {@code date}, 15:00-19:00 Toronto, made under {@code basis}. */
    static EnvironmentCheck check(LocalDate date, Optional<ShootRequirement> basis) {
        return new EnvironmentCheck(Instant.parse("2026-11-22T15:00:00Z"), date, new GeoLocation("High Park", 43.6465, -79.4637, false, false, Optional.empty()),
            ZonedDateTime.of(date, LocalTime.of(15, 0), TORONTO), ZonedDateTime.of(date, LocalTime.of(19, 0), TORONTO), 2,
            basis.flatMap(ShootRequirement::location), basis.map(ShootRequirement::timeWindow));
    }
    static ExifStatistics statistics(int usable) {
        return new ExifStatistics(2, usable, Map.of(), usable > 0 ? Map.of(50.0, usable) : Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of("Fixture evidence"));
    }
    static PostShootReview review(ExifStatistics stats) {
        return new PostShootReview(UUID.randomUUID(), Instant.parse("2026-11-16T10:00:00Z"), stats, PLAN_ID, 1, List.of(), List.of("Arrive earlier"), List.of("Fixture"));
    }

    static SessionWorkflow at(PhotographySession session) { return at(session, Optional.empty(), TODAY); }
    static SessionWorkflow at(PhotographySession session, Optional<GearInventory> gear, LocalDate today) {
        return SessionWorkflowService.evaluate(session, gear, today, VALIDATOR);
    }
    static List<MilestoneStatus> statuses(SessionWorkflow workflow) { return workflow.milestones().stream().map(MilestoneState::status).toList(); }
    static String detail(SessionWorkflow workflow, Milestone milestone) { return workflow.state(milestone).detail().key(); }
    static Optional<Milestone> next(SessionWorkflow workflow) { return workflow.next().map(MilestoneState::milestone); }
    static GearInventory without(GearInventory inventory, Equipment removed) {
        return new GearInventory(inventory.items().stream().filter(item -> !item.id().equals(removed.id())).toList());
    }

    @Test void aNewSessionStartsAtTheRequirementsWithNothingCounted() {
        var workflow = at(draft(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        // Only 01 and the optional 04 can be acted on; everything else waits for an earlier step.
        assertEquals(List.of(NOT_STARTED, BLOCKED, BLOCKED, NOT_STARTED, BLOCKED, BLOCKED, BLOCKED), statuses(workflow));
        assertEquals(List.of("workflow.detail.describe", "workflow.detail.needsRequirement", "workflow.detail.needsProfile", "workflow.detail.assess",
            "workflow.detail.needsRequirement", "workflow.detail.needsPlan", "workflow.detail.needsShoot"), workflow.milestones().stream().map(m -> m.detail().key()).toList());
        assertEquals(Optional.of(REQUIREMENTS), next(workflow));
        assertEquals(0, workflow.completed()); assertEquals(7, workflow.applicable()); assertEquals(0, workflow.percent());
        assertFalse(workflow.complete()); assertFalse(workflow.attention());
        assertEquals(SessionWorkflow.Phase.GETTING_STARTED, workflow.phase());
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.next.REQUIREMENTS"), Optional.of("F03")), workflow.nextStep().orElseThrow());
        assertEquals(LocalizedMessage.of("workflow.progress", 0, 0, 7), workflow.progress());
    }

    @Test void aConfirmedRequirementCountsAndLeadsToTheEquipmentRequirements() {
        var workflow = at(draft(Optional.of(R), Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(List.of(COMPLETED, NOT_STARTED, BLOCKED, NOT_STARTED, BLOCKED, BLOCKED, BLOCKED), statuses(workflow));
        assertEquals("workflow.detail.confirmed", detail(workflow, REQUIREMENTS));
        assertEquals("workflow.detail.defineNeeds", detail(workflow, EQUIPMENT_NEEDS));
        assertEquals("workflow.detail.needsProfile", detail(workflow, PLAN));
        assertEquals(Optional.of(EQUIPMENT_NEEDS), next(workflow));
        assertEquals(1, workflow.completed()); assertEquals(7, workflow.applicable()); assertEquals(14, workflow.percent());
        assertEquals(SessionWorkflow.Phase.PLANNING, workflow.phase());
    }

    @Test void aRequirementChangeMakesTheProfileStaleWithoutTouchingUnrelatedMilestones() {
        var current = at(draft(Optional.of(R), Optional.of(P), Optional.empty(), Optional.empty()));
        assertEquals(List.of(COMPLETED, COMPLETED, NOT_STARTED, NOT_STARTED, NOT_STARTED, BLOCKED, BLOCKED), statuses(current));
        assertEquals(List.of("workflow.detail.profileManual", "workflow.detail.select", "workflow.detail.generate"),
            List.of(detail(current, EQUIPMENT_NEEDS), detail(current, GEAR_SELECTION), detail(current, PLAN)));
        assertEquals(Optional.of(GEAR_SELECTION), next(current)); assertEquals(29, current.percent());
        // F03 re-confirmed for two bodies: the stored one-body profile is kept but no longer fits.
        var stale = at(draft(Optional.of(requirement("Portrait", AHEAD, 2)), Optional.of(P), Optional.empty(), Optional.empty()));
        assertEquals(NEEDS_REVIEW, stale.status(EQUIPMENT_NEEDS));
        assertEquals("workflow.detail.profileStale", detail(stale, EQUIPMENT_NEEDS));
        assertEquals(BLOCKED, stale.status(GEAR_SELECTION), "selection waits for a usable profile");
        for (var milestone : List.of(REQUIREMENTS, ENVIRONMENT, PLAN, SHOOT, REVIEW)) assertEquals(current.state(milestone), stale.state(milestone), milestone::name);
        assertEquals(Optional.of(EQUIPMENT_NEEDS), next(stale)); assertTrue(stale.attention());
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.recheck.EQUIPMENT_NEEDS"), Optional.of("F06")), stale.nextStep().orElseThrow());
        assertEquals(1, stale.completed()); assertEquals(14, stale.percent());
        assertTrue(stale.percent() < current.percent(), "a stale record no longer counts");
        // Provenance only changes the reason shown.
        var assisted = at(draft(Optional.of(R), Optional.of(profile(EquipmentRequirementProfile.AI_ASSISTED)), Optional.empty(), Optional.empty()));
        assertEquals(COMPLETED, assisted.status(EQUIPMENT_NEEDS)); assertEquals("workflow.detail.profileAi", detail(assisted, EQUIPMENT_NEEDS));
    }

    @Test void anUnconfirmedRequirementOrUnreviewedProfileNeverCounts() {
        var fresh = at(draft(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        var unconfirmed = at(draft(Optional.of(R.asDraft()), Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(fresh.milestones(), unconfirmed.milestones(), "a draft requirement is the same as none");
        var unreviewed = at(draft(Optional.of(R), Optional.of(P.asDraft()), Optional.empty(), Optional.empty()));
        assertEquals(List.of(COMPLETED, NOT_STARTED, BLOCKED, NOT_STARTED, BLOCKED, BLOCKED, BLOCKED), statuses(unreviewed));
        assertEquals(Optional.of(EQUIPMENT_NEEDS), next(unreviewed)); assertEquals(1, unreviewed.completed());
    }

    @Test void aSavedSelectionIsRecheckedAgainstTheCurrentInventory() {
        var session = draft(Optional.of(R), Optional.of(P), Optional.of(selection), Optional.empty());
        assertTrue(PlanningService.selectionCheck(VALIDATOR, selection, R, kit.inventory()).current(), "fixture: compatible and owned");
        var current = at(session, Optional.of(kit.inventory()), TODAY);
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_STARTED, NOT_STARTED, BLOCKED, BLOCKED), statuses(current));
        assertEquals(LocalizedMessage.of("workflow.detail.selected", 2), current.state(GEAR_SELECTION).detail());
        assertEquals(Optional.of(ENVIRONMENT), next(current)); assertEquals(43, current.percent());
        // The mounted lens left the inventory: the selection is kept but needs review.
        var removed = at(session, Optional.of(without(kit.inventory(), kit.standard)), TODAY);
        assertEquals(NEEDS_REVIEW, removed.status(GEAR_SELECTION)); assertEquals("workflow.detail.selectionStale", detail(removed, GEAR_SELECTION));
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.recheck.GEAR_SELECTION"), Optional.of("F07")), removed.nextStep().orElseThrow());
        assertEquals(29, removed.percent());
        // A failed inventory read never turns a completed selection into one that needs review.
        assertEquals(current.milestones(), at(session, Optional.empty(), TODAY).milestones());
    }

    @Test void theEnvironmentCheckIsOptionalAndRecheckedAgainstTheConfirmedShoot() {
        // Before a plan, with the shoot ahead: recommended, never in the way of the plan.
        var ahead = at(draft(Optional.of(R), Optional.of(P), Optional.of(selection), Optional.empty()));
        assertEquals(NOT_STARTED, ahead.status(ENVIRONMENT)); assertEquals(NOT_STARTED, ahead.status(PLAN));
        // The shoot date has passed (F09 refuses past dates): skipped and left out of the count.
        var past = at(session(new DraftState(), PAST, Optional.of(R_PAST), Optional.of(P), Optional.of(selection), Optional.empty(), List.of(), Optional.empty(), List.of()));
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, NOT_STARTED, BLOCKED, BLOCKED), statuses(past));
        assertEquals("workflow.detail.environmentSkipped", detail(past, ENVIRONMENT));
        assertEquals(6, past.applicable()); assertEquals(50, past.percent()); assertEquals(Optional.of(PLAN), next(past));
        // A check recorded earlier that no longer applies to a past shoot date cannot be redone: skipped, never "re-check".
        var pastMoved = at(session(new DraftState(), PAST, Optional.of(requirement("Portrait", PAST.plusDays(1), 1)), Optional.of(P), Optional.of(selection),
            Optional.of(check(PAST, Optional.of(R_PAST))), List.of(), Optional.empty(), List.of()));
        assertEquals(NOT_APPLICABLE, pastMoved.status(ENVIRONMENT)); assertEquals("workflow.detail.environmentSkipped", detail(pastMoved, ENVIRONMENT));
        assertEquals(Optional.of(PLAN), next(pastMoved)); assertFalse(pastMoved.attention());
        // Once a plan is approved without a record: skipped as well.
        assertEquals(NOT_APPLICABLE, at(planned(R, plan(1, R, P))).status(ENVIRONMENT));
        // Recorded for the confirmed shoot: done, naming the place and date it was recorded for.
        var done = at(draft(Optional.of(R), Optional.of(P), Optional.of(selection), Optional.of(check(AHEAD, Optional.of(R)))));
        assertEquals(LocalizedMessage.of("workflow.detail.environment", "High Park", AHEAD.toString()), done.state(ENVIRONMENT).detail());
        assertEquals(COMPLETED, done.status(ENVIRONMENT)); assertEquals(Optional.of(PLAN), next(done)); assertEquals(57, done.percent());
        // F03 re-confirmed for another day, or with another free-text time window: the record no longer applies.
        var moved = at(draft(Optional.of(requirement("Portrait", AHEAD.plusDays(1), 1)), Optional.of(P), Optional.of(selection), Optional.of(check(AHEAD, Optional.of(R)))));
        assertEquals(NEEDS_REVIEW, moved.status(ENVIRONMENT)); assertEquals("workflow.detail.environmentStale", detail(moved, ENVIRONMENT));
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.recheck.ENVIRONMENT"), Optional.of("F09")), moved.nextStep().orElseThrow());
        var morning = new ShootRequirement(R.originalText(), R.subject(), R.goals(), R.time(), R.date(), "early morning", R.location(), R.duration(), R.cameraCount(),
            R.lensChangesAllowed(), R.maxWeightGrams(), R.portability(), R.gearConstraints(), R.priorities(), R.fixedConstraints(), List.of(), true);
        assertEquals(NEEDS_REVIEW, at(draft(Optional.of(morning), Optional.of(P), Optional.of(selection), Optional.of(check(AHEAD, Optional.of(R))))).status(ENVIRONMENT));
        // Without a confirmed requirement only the session date is compared.
        var dateOnly = draft(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(check(AHEAD, Optional.empty())));
        assertEquals(COMPLETED, at(dateOnly).status(ENVIRONMENT));
        assertEquals(NEEDS_REVIEW, at(dateOnly.edit(dateOnly.title(), AHEAD.plusDays(3), dateOnly.location(), "")).status(ENVIRONMENT));
    }

    @Test void aPlannedSessionWaitsForTheShootAndAsksForItOnceTheDateHasPassed() {
        var session = planned(R, plan(1, R, P));
        var ready = at(session, Optional.of(kit.inventory()), TODAY);
        // 03 is the gear of the approved plan; 04 was skipped; 06 is never completed by the calendar.
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, COMPLETED, NOT_STARTED, BLOCKED), statuses(ready));
        assertEquals(LocalizedMessage.of("workflow.detail.selectedInPlan", 0), ready.state(GEAR_SELECTION).detail());
        assertEquals(LocalizedMessage.of("workflow.detail.planApproved", 1), ready.state(PLAN).detail());
        assertEquals(LocalizedMessage.of("workflow.detail.shootReady", AHEAD.toString()), ready.state(SHOOT).detail());
        assertEquals(SessionWorkflow.Phase.READY, ready.phase()); assertEquals(4, ready.completed()); assertEquals(6, ready.applicable()); assertEquals(67, ready.percent());
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.next.SHOOT"), Optional.of(Milestone.SHOOT_TARGET)), ready.nextStep().orElseThrow());
        assertFalse(ready.attention());
        var after = AHEAD.plusDays(1);
        var overdue = at(session, Optional.of(kit.inventory()), after);
        assertEquals(ACTION_REQUIRED, overdue.status(SHOOT));
        assertEquals(LocalizedMessage.of("workflow.detail.shootOverdue", AHEAD.toString()), overdue.state(SHOOT).detail());
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.act.SHOOT"), Optional.of("SHOOT")), overdue.nextStep().orElseThrow());
        assertTrue(overdue.attention()); assertEquals(67, overdue.percent());
        // Once the date has passed nothing upstream is re-checked (stale profile, changed constraints, gear gone): the
        // next step is recording the shoot, not revising what led to it.
        var drifted = planned(requirement("Portrait", AHEAD, 2), plan(1, R, P));
        var empty = Optional.of(new GearInventory(List.of()));
        assertEquals(List.of(COMPLETED, NEEDS_REVIEW, COMPLETED, NOT_APPLICABLE, NEEDS_REVIEW, NOT_STARTED, BLOCKED), statuses(at(drifted, empty, TODAY)));
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, COMPLETED, ACTION_REQUIRED, BLOCKED), statuses(at(drifted, empty, after)));
        assertEquals(Optional.of(SHOOT), next(at(drifted, empty, after)));
        // The controlled linked fixture (shot on 2026-11-01, an older plan) is overdue today as well.
        var linked = at(SessionFixtures.linked(new PlannedState(), List.of(kit.a7.snapshot(), kit.standard.snapshot()), false), Optional.of(kit.inventory()), TODAY);
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, COMPLETED, ACTION_REQUIRED, BLOCKED), statuses(linked));
        assertEquals(LocalizedMessage.of("workflow.detail.shootOverdue", "2026-11-01"), linked.state(SHOOT).detail());
    }

    @Test void aPlanNeedsRevisionOnlyWhenWhatItRecordedChanged() {
        var inventory = Optional.of(kit.inventory());
        // Subject re-confirmed: the plan's fixed constraints no longer equal the current ones (the profile still fits).
        var renamed = requirement("Family portrait", AHEAD, 1);
        assertNotEquals(PlanValidator.constraintsOf(R, Optional.of(P)), PlanValidator.constraintsOf(renamed, Optional.of(P)));
        var stale = at(planned(renamed, plan(1, R, P)), inventory, TODAY);
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, NEEDS_REVIEW, NOT_STARTED, BLOCKED), statuses(stale));
        assertEquals(LocalizedMessage.of("workflow.detail.planStale", 1), stale.state(PLAN).detail());
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.recheck.PLAN"), Optional.of("F10")), stale.nextStep().orElseThrow());
        assertEquals(50, stale.percent());
        // Shoot moved to another day: same constraints, but the plan's recorded date no longer matches.
        var moved = requirement("Portrait", AHEAD.plusDays(2), 1);
        assertEquals(PlanValidator.constraintsOf(R, Optional.of(P)), PlanValidator.constraintsOf(moved, Optional.of(P)));
        assertEquals(NEEDS_REVIEW, at(planned(moved, plan(1, R, P)), inventory, TODAY).status(PLAN));
        // Changed constraints need a new plan, a changed date or gear a revision of the approved one (F10's two paths).
        assertEquals(LocalizedMessage.of("workflow.detail.planShootChanged", 1), at(planned(moved, plan(1, R, P)), inventory, TODAY).state(PLAN).detail());
        // The plan's gear left the inventory; an unreadable inventory is no reason to revise.
        assertEquals(NEEDS_REVIEW, at(planned(R, plan(1, R, P)), Optional.of(without(kit.inventory(), kit.standard)), TODAY).status(PLAN));
        assertEquals(LocalizedMessage.of("workflow.detail.planShootChanged", 1),
            at(planned(R, plan(1, R, P)), Optional.of(without(kit.inventory(), kit.standard)), TODAY).state(PLAN).detail());
        assertEquals(COMPLETED, at(planned(R, plan(1, R, P)), Optional.empty(), TODAY).status(PLAN));
        // A plan written before F10 recorded constraints and shoot details is not re-checked against them, only its gear.
        assertEquals(COMPLETED, at(planned(renamed, olderPlan()), inventory, TODAY).status(PLAN));
        assertEquals(COMPLETED, at(planned(moved, olderPlan()), inventory, TODAY).status(PLAN));
        assertEquals(NEEDS_REVIEW, at(planned(R, olderPlan()), Optional.of(without(kit.inventory(), kit.standard)), TODAY).status(PLAN));
        // The latest approved revision is the one judged.
        var revised = at(planned(renamed, plan(1, R, P), plan(2, renamed, P)), inventory, TODAY);
        assertEquals(LocalizedMessage.of("workflow.detail.planApproved", 2), revised.state(PLAN).detail());
        assertEquals(COMPLETED, revised.status(PLAN));
    }

    @Test void aCompletedShootWaitsForUsablePhotosThenForTheSavedReview() {
        var waiting = at(historical(new CompletedState(), Optional.empty(), List.of()));
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, COMPLETED, COMPLETED, NOT_STARTED), statuses(waiting));
        assertEquals("workflow.detail.shot", detail(waiting, SHOOT)); assertEquals("workflow.detail.importPhotos", detail(waiting, REVIEW));
        assertEquals(SessionWorkflow.Phase.AWAITING_REVIEW, waiting.phase()); assertEquals(83, waiting.percent());
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.next.REVIEW"), Optional.of("F11")), waiting.nextStep().orElseThrow());
        // Statistics without a usable photo cannot be reviewed (ReviewService's rule): still not started.
        assertEquals(NOT_STARTED, at(historical(new CompletedState(), Optional.of(statistics(0)), List.of())).status(REVIEW));
        var analysed = at(historical(new CompletedState(), Optional.of(statistics(1)), List.of()));
        assertEquals(IN_PROGRESS, analysed.status(REVIEW)); assertEquals("workflow.detail.analysed", detail(analysed, REVIEW));
        assertEquals(new com.photopilot.progress.NextStep(LocalizedMessage.of("workflow.continue.REVIEW"), Optional.of("F11")), analysed.nextStep().orElseThrow());
        assertEquals(83, analysed.percent(), "a started milestone is not a completed one");
    }

    @Test void aReviewedSessionIsCompleteAtOneHundredPercent() {
        var stats = statistics(1);
        var reviewed = at(historical(new ReviewedState(), Optional.of(stats), List.of(review(stats))));
        assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, NOT_APPLICABLE, COMPLETED, COMPLETED, COMPLETED), statuses(reviewed));
        assertEquals(LocalizedMessage.of("workflow.detail.reviewed", 1), reviewed.state(REVIEW).detail());
        assertTrue(reviewed.complete()); assertEquals(100, reviewed.percent()); assertEquals(6, reviewed.completed()); assertEquals(6, reviewed.applicable());
        assertTrue(reviewed.next().isEmpty()); assertTrue(reviewed.nextStep().isEmpty()); assertFalse(reviewed.attention());
        assertEquals(SessionWorkflow.Phase.REVIEWED, reviewed.phase());
        assertEquals(LocalizedMessage.of("workflow.progress", 100, 6, 6), reviewed.progress());
    }

    @Test void historicalSessionsAreNeverMarkedForReview() {
        // Completed with a saved selection, a check recorded for another day, a profile that no longer fits the
        // requirement and plan gear that has left the inventory: the records keep their meaning.
        var drifted = session(new CompletedState(), PAST, Optional.of(requirement("Portrait", PAST, 2)), Optional.of(P), Optional.of(selection),
            Optional.of(check(PAST.minusDays(3), Optional.empty())), List.of(plan(1, R_PAST, P)), Optional.empty(), List.of());
        for (var today : List.of(TODAY, PAST.minusDays(1))) {
            var workflow = at(drifted, Optional.of(new GearInventory(List.of())), today);
            assertEquals(List.of(COMPLETED, COMPLETED, COMPLETED, COMPLETED, COMPLETED, COMPLETED, NOT_STARTED), statuses(workflow), today::toString);
            assertFalse(workflow.attention());
            assertEquals(LocalizedMessage.of("workflow.detail.selected", 2), workflow.state(GEAR_SELECTION).detail());
        }
        var stats = statistics(1);
        var reviewed = new PhotographySession(drifted.id(), drifted.title(), drifted.date(), drifted.location(), drifted.notes(), drifted.createdAt(), drifted.updatedAt(),
            new ReviewedState(), drifted.requirements(), drifted.plans(), List.of(review(stats)), drifted.profile(), Optional.of(stats), List.of(), drifted.selectedLoadout(), drifted.environmentCheck());
        var complete = at(reviewed, Optional.of(new GearInventory(List.of())), TODAY);
        assertTrue(complete.complete()); assertEquals(100, complete.percent()); assertEquals(7, complete.applicable());
        // Records a historical session never had are left out instead of being asked for.
        var legacy = at(session(new CompletedState(), PAST, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(olderPlan()), Optional.empty(), List.of()));
        assertEquals(List.of(NOT_APPLICABLE, NOT_APPLICABLE, COMPLETED, NOT_APPLICABLE, COMPLETED, COMPLETED, NOT_STARTED), statuses(legacy));
        assertEquals("workflow.detail.notRecorded", detail(legacy, REQUIREMENTS)); assertEquals("workflow.detail.notRecorded", detail(legacy, EQUIPMENT_NEEDS));
        assertEquals(Optional.of(REVIEW), next(legacy)); assertEquals(75, legacy.percent());
    }

    @Test void percentIsTheCompletedShareAndReaches100OnlyWhenComplete() {
        var session = draft(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        for (var open : List.of(NOT_STARTED, IN_PROGRESS, NEEDS_REVIEW, ACTION_REQUIRED, BLOCKED))
            for (int applicable = 0; applicable <= 7; applicable++)
                for (int done = 0; done <= applicable; done++) {
                    var states = new ArrayList<MilestoneState>();
                    for (var milestone : Milestone.values()) {
                        int i = milestone.ordinal();
                        states.add(new MilestoneState(milestone, i < done ? COMPLETED : i < applicable ? open : NOT_APPLICABLE, LocalizedMessage.of("workflow.detail.confirmed")));
                    }
                    var workflow = new SessionWorkflow(session, SessionWorkflow.Phase.PLANNING, states);
                    String label = open + " " + done + "/" + applicable;
                    assertEquals(done, workflow.completed(), label); assertEquals(applicable, workflow.applicable(), label);
                    if (applicable == 0) assertEquals(0, workflow.percent(), label);
                    else if (done == applicable) { assertTrue(workflow.complete(), label); assertEquals(100, workflow.percent(), label); }
                    else {
                        assertFalse(workflow.complete(), label);
                        assertEquals(Math.round(100.0 * done / applicable), workflow.percent(), label);
                        assertTrue(workflow.percent() < 100, label);
                    }
                }
        // The record is checked: one state per milestone, in workflow order.
        var ordered = new ArrayList<>(at(session).milestones());
        assertThrows(IllegalArgumentException.class, () -> new SessionWorkflow(session, SessionWorkflow.Phase.PLANNING, ordered.subList(0, 6)));
        Collections.swap(ordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> new SessionWorkflow(session, SessionWorkflow.Phase.PLANNING, ordered));
    }

    /** One workflow per reachable situation, named; shared by the guidance and localization checks. */
    Map<String, SessionWorkflow> scenarios() {
        var inventory = Optional.of(kit.inventory());
        var map = new LinkedHashMap<String, SessionWorkflow>();
        map.put("new", at(draft(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())));
        map.put("requirement", at(draft(Optional.of(R), Optional.empty(), Optional.empty(), Optional.empty())));
        map.put("profileStale", at(draft(Optional.of(requirement("Portrait", AHEAD, 2)), Optional.of(P), Optional.empty(), Optional.empty())));
        map.put("profile", at(draft(Optional.of(R), Optional.of(profile(EquipmentRequirementProfile.AI_ASSISTED)), Optional.empty(), Optional.empty())));
        map.put("selectionStale", at(draft(Optional.of(R), Optional.of(P), Optional.of(selection), Optional.empty()), Optional.of(without(kit.inventory(), kit.standard)), TODAY));
        map.put("selected", at(draft(Optional.of(R), Optional.of(P), Optional.of(selection), Optional.empty()), inventory, TODAY));
        map.put("environmentStale", at(draft(Optional.of(requirement("Portrait", AHEAD.plusDays(1), 1)), Optional.of(P), Optional.of(selection), Optional.of(check(AHEAD, Optional.of(R))))));
        map.put("environment", at(draft(Optional.of(R), Optional.of(P), Optional.of(selection), Optional.of(check(AHEAD, Optional.of(R)))), inventory, TODAY));
        map.put("planStale", at(planned(requirement("Family portrait", AHEAD, 1), plan(1, R, P)), inventory, TODAY));
        map.put("planMoved", at(planned(requirement("Portrait", AHEAD.plusDays(2), 1), plan(1, R, P)), inventory, TODAY));
        map.put("planned", at(planned(R, plan(1, R, P)), inventory, TODAY));
        map.put("overdue", at(planned(R, plan(1, R, P)), inventory, AHEAD.plusDays(1)));
        map.put("completed", at(historical(new CompletedState(), Optional.empty(), List.of())));
        map.put("analysed", at(historical(new CompletedState(), Optional.of(statistics(1)), List.of())));
        map.put("reviewed", at(historical(new ReviewedState(), Optional.of(statistics(1)), List.of(review(statistics(1))))));
        map.put("legacy", at(session(new CompletedState(), PAST, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(olderPlan()), Optional.empty(), List.of())));
        map.put("past", at(session(new DraftState(), PAST, Optional.of(R_PAST), Optional.of(P), Optional.of(selection), Optional.empty(), List.of(), Optional.empty(), List.of())));
        return map;
    }

    @Test void theNextStepIsTheFirstActionableMilestoneOnItsOwnPage() {
        assertEquals(List.of("F03", "F06", "F07", "F09", "F10", "SHOOT", "F11"), Arrays.stream(Milestone.values()).map(Milestone::target).toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), Arrays.stream(Milestone.values()).map(Milestone::number).toList());
        assertEquals("workflow.milestone.SHOOT", SHOOT.titleKey()); assertEquals("feature.F10.title", PLAN.titleKey());
        var expected = new LinkedHashMap<String, String>();
        expected.put("new", "F03 workflow.next.REQUIREMENTS"); expected.put("requirement", "F06 workflow.next.EQUIPMENT_NEEDS");
        expected.put("profileStale", "F06 workflow.recheck.EQUIPMENT_NEEDS"); expected.put("profile", "F07 workflow.next.GEAR_SELECTION");
        expected.put("selectionStale", "F07 workflow.recheck.GEAR_SELECTION"); expected.put("selected", "F09 workflow.next.ENVIRONMENT");
        expected.put("environmentStale", "F09 workflow.recheck.ENVIRONMENT"); expected.put("environment", "F10 workflow.next.PLAN");
        expected.put("planStale", "F10 workflow.recheck.PLAN"); expected.put("planMoved", "F10 workflow.recheck.PLAN");
        expected.put("planned", "SHOOT workflow.next.SHOOT");
        expected.put("overdue", "SHOOT workflow.act.SHOOT"); expected.put("completed", "F11 workflow.next.REVIEW");
        expected.put("analysed", "F11 workflow.continue.REVIEW"); expected.put("legacy", "F11 workflow.next.REVIEW"); expected.put("past", "F10 workflow.next.PLAN");
        var scenarios = scenarios();
        for (var entry : expected.entrySet()) {
            var workflow = scenarios.get(entry.getKey()); var next = workflow.next().orElseThrow();
            var step = workflow.nextStep().orElseThrow();
            assertEquals(entry.getValue(), step.target().orElseThrow() + " " + step.message().key(), entry.getKey());
            assertEquals(SessionWorkflow.guidanceKey(next), step.message().key());
            // Everything before it is done, skipped or waiting; BLOCKED is never recommended.
            for (var earlier : workflow.milestones().subList(0, next.milestone().ordinal()))
                assertTrue(Set.of(COMPLETED, NOT_APPLICABLE, BLOCKED).contains(earlier.status()), entry.getKey() + ": " + earlier.milestone());
        }
        assertTrue(scenarios.get("reviewed").nextStep().isEmpty(), "a complete session has no next step");
    }

    @Test void everyReachableLabelGuidanceAndReasonExistsInBothLanguages() throws Exception {
        var english = bundle("i18n/messages_en.properties"); var chinese = bundle("i18n/messages_zh_CN.properties");
        var messages = new ArrayList<LocalizedMessage>();
        for (var status : MilestoneStatus.values()) messages.add(LocalizedMessage.of(status.labelKey()));
        for (var phase : SessionWorkflow.Phase.values()) messages.add(LocalizedMessage.of(phase.labelKey()));
        for (var milestone : Milestone.values()) messages.add(LocalizedMessage.of(milestone.titleKey()));
        var reached = EnumSet.noneOf(MilestoneStatus.class);
        for (var workflow : scenarios().values()) {
            messages.add(workflow.progress());
            for (var state : workflow.milestones()) {
                reached.add(state.status()); messages.add(state.detail());
                if (state.status().actionable()) messages.add(LocalizedMessage.of(SessionWorkflow.guidanceKey(state)));
            }
        }
        assertEquals(EnumSet.allOf(MilestoneStatus.class), reached, "the scenarios reach every status");
        // Every reason the service can give is reached by a scenario (so the check below is complete).
        var source = Files.readString(Path.of("src/main/java/com/photopilot/service/SessionWorkflowService.java"));
        var declared = new TreeSet<String>(); var matcher = Pattern.compile("\"(workflow\\.detail\\.[A-Za-z]+)\"").matcher(source);
        while (matcher.find()) declared.add(matcher.group(1));
        var details = new TreeSet<String>(); messages.forEach(m -> { if (m.key().startsWith("workflow.detail.")) details.add(m.key()); });
        assertEquals(declared, details);
        assertTrue(declared.size() >= 28, declared::toString);
        for (var message : messages)
            for (var bundle : List.of(english, chinese)) {
                String pattern = bundle.getProperty(message.key(), "");
                assertFalse(pattern.isBlank(), (bundle == english ? "English: " : "Chinese: ") + message.key());
                // Every placeholder of the sentence gets an argument.
                int placeholders = new MessageFormat(pattern).getFormatsByArgumentIndex().length;
                assertTrue(placeholders <= message.arguments().size(), message.key() + " has " + placeholders + " placeholders for " + message.arguments());
            }
    }
    private static Properties bundle(String name) throws Exception {
        var properties = new Properties();
        try (var input = SessionWorkflowServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            properties.load(new InputStreamReader(Objects.requireNonNull(input, name), StandardCharsets.UTF_8));
        }
        return properties;
    }
}
