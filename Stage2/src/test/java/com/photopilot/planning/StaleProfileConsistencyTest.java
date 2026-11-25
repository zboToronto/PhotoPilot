package com.photopilot.planning;

import com.photopilot.command.ShootPlan;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.PlanningService;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 Review2 #03 / C47 follow-up: F07 and F10 judge a confirmed F06 profile by the same rule
 * ({@link PlanningService#usableProfile}). A profile confirmed for requirement A that contradicts the re-confirmed
 * requirement B is kept (history) but authorizes neither matching nor planning until it is confirmed again.
 */
class StaleProfileConsistencyTest {
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }

    /** Requirement B: same shoot, but at most 5.5 kg; profile A allows 6 kg, which F06 consistency rejects (weightConflict). */
    static ShootRequirement lighter(ShootRequirement a) {
        return new ShootRequirement(a.originalText(), a.subject(), a.goals(), a.time(), a.date(), a.timeWindow(), a.location(), a.duration(),
            a.cameraCount(), a.lensChangesAllowed(), Optional.of(5500.0), a.portability(), a.gearConstraints(), a.priorities(), a.fixedConstraints(), List.of(), false);
    }
    /** Profile B: profile A with the weight ceiling of requirement B. */
    static EquipmentRequirementProfile profileB() {
        var needs = new ArrayList<CapabilityNeed>();
        for (var need : PlanFixtures.profile().needs())
            needs.add(need.capability().equals("totalWeight") ? GearFixtures.need("totalWeight", null, 5500.0, need.priority(), false) : need);
        return new EquipmentRequirementProfile(needs, false);
    }
    static List<String> keys(List<LocalizedMessage> messages) { return messages.stream().map(LocalizedMessage::key).toList(); }
    PhotoPilotCLI cli(ByteArrayOutputStream bytes) {
        return new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
    }

    @Test void aProfileContradictedByTheReconfirmedRequirementAuthorizesNeitherMatchingNorPlanningUntilReconfirmed() throws Exception {
        var id = session(app, gear, true);
        // Requirement A + profile A: matching and planning are available.
        assertFalse(ok(app.facade().planning().matchGear(id, "portability")).recommendations().isEmpty(), "compatible candidates under A");
        assertTrue(ok(app.facade().planning().planInputs(id)).ready());
        gemini.replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
        var previewA = ok(app.facade().planning().generatePreview(id));
        assertTrue(previewA.approvable(), previewA.validation()::toString);

        // Requirement B is confirmed: profile A no longer fits.
        ok(app.facade().planning().confirm(id, lighter(requirement(day())).asDraft()));
        byte[] before = Files.readAllBytes(store());
        int sent = gemini.requests.size();
        var match = app.facade().planning().matchGear(id, "portability");
        assertTrue(match.hasError("needs.error.stale") && match.hasError("needs.error.weightConflict"), match.errors()::toString);
        var inputs = ok(app.facade().planning().planInputs(id));
        assertFalse(inputs.ready(), "F10 applies the F07 rule");
        assertEquals(List.of("needs.error.stale", "needs.error.weightConflict"), keys(inputs.blockers()));
        assertTrue(inputs.profile().isPresent(), "the stale profile is kept, not deleted");
        var generated = app.facade().planning().generatePreview(id);
        assertTrue(generated.hasError("needs.error.stale") && generated.hasError("needs.error.weightConflict"), generated.errors()::toString);
        assertTrue(app.facade().generatePlan(id).hasError("needs.error.stale"), "the Stage 1 Facade route applies the same rule");
        var approval = app.facade().planning().approve(id, previewA.plan());
        assertTrue(approval.hasError("needs.error.stale"), "a preview made under A cannot be approved with the stale profile: " + approval.errors());
        assertEquals(sent, gemini.requests.size(), "no model request is made with a stale profile");
        assertArrayEquals(before, Files.readAllBytes(store()), "nothing is written");
        var workflow = ok(app.facade().workflow().of(id));
        assertEquals(MilestoneStatus.NEEDS_REVIEW, workflow.status(Milestone.EQUIPMENT_NEEDS));
        assertEquals(MilestoneStatus.BLOCKED, workflow.status(Milestone.GEAR_SELECTION));
        assertNotEquals(MilestoneStatus.COMPLETED, workflow.status(Milestone.PLAN));
        assertEquals(Milestone.EQUIPMENT_NEEDS, workflow.next().orElseThrow().milestone(), "the next step is to review the gear requirements");

        // CLI: the same refusal, exit 2, nothing written.
        var bytes = new ByteArrayOutputStream(); var cli = cli(bytes);
        assertEquals(2, cli.execute("plan generate " + id));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("no longer matches the confirmed shooting requirements"), bytes::toString);
        bytes.reset();
        assertEquals(0, cli.execute("plan show " + id));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("no longer matches the confirmed shooting requirements"), bytes::toString);
        assertArrayEquals(before, Files.readAllBytes(store()));

        // After a restart the stored records give the same verdict.
        app.close(); app = boot();
        assertTrue(app.facade().planning().generatePreview(id).hasError("needs.error.stale"));
        assertTrue(app.facade().planning().matchGear(id, "portability").hasError("needs.error.stale"));

        // Recovery: profile B is confirmed (replacing A); matching and planning work again.
        var session = ok(app.facade().sessions().open(id));
        ok(app.facade().planning().confirmProfile(session, profileB(), true));
        assertTrue(app.facade().planning().matchGear(id, "portability").isSuccess());
        assertTrue(ok(app.facade().planning().planInputs(id)).ready());
        gemini.replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
        var previewB = ok(app.facade().planning().generatePreview(id));
        assertTrue(previewB.approvable(), previewB.validation()::toString);
        ok(app.facade().planning().approve(id, previewB.plan()));
        assertEquals("PLANNED", ok(app.facade().sessions().open(id)).status());
        assertEquals(MilestoneStatus.COMPLETED, ok(app.facade().workflow().of(id)).status(Milestone.EQUIPMENT_NEEDS));
        assertEquals(MilestoneStatus.COMPLETED, ok(app.facade().workflow().of(id)).status(Milestone.PLAN));
    }

    @Test void anApprovedPlanFromTheOldProfileIsKeptAndANewRevisionWaitsForTheReconfirmedProfile() throws Exception {
        var id = session(app, gear, true);
        gemini.replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
        var first = ok(app.facade().planning().approve(id, ok(app.facade().planning().generatePreview(id)).plan()));
        ok(app.facade().planning().confirm(id, lighter(requirement(day())).asDraft()));
        assertEquals(MilestoneStatus.NEEDS_REVIEW, ok(app.facade().workflow().of(id)).status(Milestone.PLAN), "the approved plan records requirement A");
        assertTrue(app.facade().planning().generatePreview(id).hasError("needs.error.stale"), "no regeneration on the stale profile");
        int sent = gemini.requests.size();
        assertTrue(app.facade().planning().revisePreview(id, "Make it lighter.").hasError("needs.error.stale"), "no revision on the stale profile");
        assertEquals(sent, gemini.requests.size(), "refused before any model request");
        app.close(); app = boot();
        var history = ok(app.facade().planning().plans(id));
        assertEquals(List.of(1), history.stream().map(ShootPlan::revision).toList());
        assertEquals(first.fixedConstraints(), history.getFirst().fixedConstraints(), "historical approval evidence is unchanged");
        assertEquals("PLANNED", ok(app.facade().sessions().open(id)).status());
        ok(app.facade().planning().confirmProfile(ok(app.facade().sessions().open(id)), profileB(), true));
        gemini.replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
        var regenerated = ok(app.facade().planning().generatePreview(id));
        assertTrue(regenerated.approvable(), regenerated.validation()::toString);
        assertEquals(2, ok(app.facade().planning().approve(id, regenerated.plan())).revision(), "the regeneration becomes revision 2");
        assertEquals(List.of(1, 2), ok(app.facade().planning().plans(id)).stream().map(ShootPlan::revision).toList());
    }
}
