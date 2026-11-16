package com.photopilot.planning;

import com.photopilot.command.ShootPlan;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F10 revisions stay consistent with the current confirmed requirement and with what the service accepts: a re-confirmed
 * shooting date is recorded in the revision, a change request the service accepts can be approved, and malformed model
 * lists give the normal error results. Loopback Gemini and provider fakes only.
 */
class PlanRevisionConsistencyTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }

    /** An approved plan without a schedule (no F09 evidence needed), so revisions stay approvable after a date change. */
    UUID approvedUntimedPlan() {
        var id = session(app, gear, true);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
        var preview = ok(app.facade().planning().generatePreview(id));
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(app.facade().planning().approve(id, preview.plan()));
        return id;
    }

    @Test void aRevisionAfterARescheduledShootRecordsTheCurrentDate() {
        var id = approvedUntimedPlan();
        assertEquals(day().toString(), ok(app.facade().planning().plans(id)).getFirst().sessionInfo().get("date"));
        ok(app.facade().planning().confirm(id, requirement(day().plusDays(1)).asDraft()));
        assertTrue(app.facade().planning().generatePreview(id).hasError("plan.error.exists"), "a new date keeps the fixed constraints, so it is a revision");
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var preview = ok(app.facade().planning().revisePreview(id, F10WorkflowTest.LIGHTER_REQUEST));
        assertEquals(day().plusDays(1).toString(), preview.plan().sessionInfo().get("date"));
        assertTrue(preview.changedSections().contains("sessionInfo"), preview.changedSections()::toString);
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        var saved = ok(app.facade().planning().approve(id, preview.plan()));
        assertEquals(day().plusDays(1).toString(), saved.sessionInfo().get("date"), "the approved revision shows the shoot it plans");
        assertEquals(day().toString(), ok(app.facade().planning().plans(id)).getFirst().sessionInfo().get("date"), "revision 1 is unchanged");
        // Without a date or place change the facts stay as they are (no extra changed section).
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, HEAVY_ZOOM));
        var next = ok(app.facade().planning().revisePreview(id, "Go back to the 100-400 mm zoom."));
        assertFalse(next.changedSections().contains("sessionInfo"), next.changedSections()::toString);
    }

    @Test void aPlaceLookedUpInMemoryIsNotMistakenForARescheduledShootAfterARestart() {
        var id = session(app, gear, true); assess(app, gemini, id);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
        var preview = ok(app.facade().planning().generatePreview(id));
        ok(app.facade().planning().approve(id, preview.plan()));
        assertTrue(ok(app.facade().planning().plans(id)).getFirst().sessionInfo().containsKey("location"), "the F09 place is recorded");
        app.close(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root)));
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var revised = ok(app.facade().planning().revisePreview(id, F10WorkflowTest.LIGHTER_REQUEST));
        assertFalse(revised.changedSections().contains("sessionInfo"), "same date, and the requirement names no place: " + revised.changedSections());
    }

    @Test void aReconfirmationBetweenPreviewAndApprovalIsNotApprovedWithOutdatedFacts() {
        var id = approvedUntimedPlan();
        ok(app.facade().planning().confirm(id, requirement(day().plusDays(1)).asDraft()));
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var preview = ok(app.facade().planning().revisePreview(id, F10WorkflowTest.LIGHTER_REQUEST));
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(app.facade().planning().confirm(id, requirement(day().plusDays(2)).asDraft()));
        var refused = app.facade().planning().approve(id, preview.plan());
        assertTrue(refused.hasError("plan.violation.constraintsChanged"), refused.errors()::toString);
        assertEquals(1, ok(app.facade().planning().plans(id)).size());
    }

    @Test void theModelsOwnChangeSummaryKeepsItsLimit() {
        var id = approvedUntimedPlan();
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER).replace("\"summary\":\"Use the lighter", "\"summary\":\"" + "Long model text. ".repeat(100) + "Use the lighter"));
        var preview = ok(app.facade().planning().revisePreview(id, "Lighter, please."));
        assertFalse(preview.approvable());
        assertTrue(preview.validation().violations().stream().anyMatch(m -> m.key().equals("plan.violation.text")), () -> preview.validation().toString());
    }

    @Test void aProfileConfirmedAgainstAnOutdatedSessionIsReportedAsChangedFirst() {
        var id = session(app, gear, true);
        var opened = ok(app.facade().sessions().open(id));
        // Three lenses conflict with "no lens changes, two bodies", but not once lens changes are allowed.
        var needs = new ArrayList<>(profile().needs()); needs.removeIf(n -> n.capability().equals("lensCount"));
        needs.add(com.photopilot.model.CapabilityNeed.of("lensCount", Optional.of(3.0), Optional.empty(), 1, true, ""));
        var edited = new com.photopilot.model.EquipmentRequirementProfile(needs, false);
        assertTrue(app.facade().planning().confirmProfile(opened, edited, true).hasError("needs.error.lensChangeConflict"), "the conflict is real for this version");
        var allowed = new com.photopilot.model.ShootRequirement("Airshow: two bodies, telephoto, portable", "Fast-moving aircraft at an airshow",
            List.of("Sharp aircraft in flight", "Close-up passes"), Optional.empty(), Optional.of(day()), "afternoon to sunset", Optional.empty(),
            Optional.of(java.time.Duration.ofHours(3)), Optional.of(2), Optional.of(true), Optional.empty(), "keep the kit portable", List.of(),
            Map.of("telephoto reach", 1, "portability", 2), Map.of(), List.of(), true);
        ok(app.facade().planning().confirm(id, allowed.asDraft()));
        // The stored session moved on; the caller is told to reload, not shown a conflict that no longer exists.
        var result = app.facade().planning().confirmProfile(opened, edited, true);
        assertTrue(result.hasError("error.session.changed"), result.errors()::toString);
        assertTrue(app.facade().planning().confirmProfile(ok(app.facade().sessions().open(id)), edited, true).isSuccess(), "after reloading it saves");
    }

    @Test void aLongChangeRequestTheServiceAcceptsCanBeApproved() {
        var id = approvedUntimedPlan();
        String request = (F10WorkflowTest.LIGHTER_REQUEST + " ").repeat(30).strip();
        request = request.substring(0, Math.min(request.length(), com.photopilot.service.PlanningService.MAX_CHANGE_REQUEST)).strip();
        assertTrue(request.length() > 1900, "close to the accepted maximum");
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var preview = ok(app.facade().planning().revisePreview(id, request));
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        var saved = ok(app.facade().planning().approve(id, preview.plan()));
        assertTrue(saved.changeSummary().contains(request), "the request is recorded in full");
    }

    @Test void nullEntriesInModelListsGiveTheNormalResults() {
        var id = approvedUntimedPlan();
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER).replace("\"changes\":[\"loadout\"]", "\"changes\":[\"loadout\",null]"));
        var revised = app.facade().planning().revisePreview(id, F10WorkflowTest.LIGHTER_REQUEST);
        assertTrue(revised.hasError("plan.problem.section"), revised.errors()::toString);
        var draft = ok(app.facade().sessions().create("Requirement only", day())).id();
        gemini.reply(FakeGemini.AIRSHOW.replace("\"flaggedFields\":[]", "\"flaggedFields\":[null,\"date\"]"));
        var parsed = ok(app.facade().planning().parse(draft, FakeGemini.AIRSHOW_REQUEST));
        assertEquals("Aircraft at an airshow", parsed.subject());
        assertEquals(1, ok(app.facade().planning().plans(id)).stream().map(ShootPlan::revision).distinct().count(), "nothing was saved");
    }
}
