package com.photopilot.planning;

import com.photopilot.application.PlanPresentation;
import com.photopilot.command.ShootPlan;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.PlanPreview;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 F10 airshow acceptance through the real Facade -> PlanningService -> PhotoPilotAgent (bounded tool loop) ->
 * GeminiAdapter chain against loopback Gemini and environment-provider fakes, with real F01/F05 JSON persistence.
 */
class F10WorkflowTest {
    static final String LIGHTER_REQUEST = "Make the setup lighter, but keep the two-camera requirement and avoid lens changes.";
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    PlanPreview generate(UUID id, boolean schedule) {
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, schedule));
        return ok(app.facade().planning().generatePreview(id));
    }
    UUID approvedPlan() {
        var id = session(app, gear, true); assess(app, gemini, id);
        var preview = generate(id, true);
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(app.facade().planning().approve(id, preview.plan()));
        return id;
    }
    PlanPreview lighter(UUID id) {
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        return ok(app.facade().planning().revisePreview(id, LIGHTER_REQUEST));
    }
    static Set<String> lenses(ShootPlan plan) {
        var models = new TreeSet<String>(); plan.loadout().orElseThrow().ofType(EquipmentType.LENS).forEach(l -> models.add(l.model())); return models;
    }

    @Test void missingRequirementsOrProfileBlockPlanningWithoutCallingGemini() throws Exception {
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var bare = ok(app.facade().sessions().create("No requirement", day())).id();
        assertTrue(app.facade().planning().generatePreview(bare).hasError("plan.missing.requirement"));
        assertFalse(ok(app.facade().planning().planInputs(bare)).ready());
        ok(app.facade().planning().confirm(bare, requirement(day()).asDraft()));
        assertTrue(app.facade().planning().generatePreview(bare).hasError("plan.missing.profile"), "UC10 needs a reviewed capability profile");
        assertTrue(app.facade().generatePlan(bare).hasError("plan.missing.profile"), "the Stage 1 Facade route applies the same rules");
        assertTrue(app.facade().planning().revisePreview(bare, LIGHTER_REQUEST).hasError("plan.missing.profile"));
        assertTrue(gemini.requests.isEmpty(), "no model request without the mandatory inputs");
    }

    @Test void airshowPlanUsesToolsIsValidatedPreviewedAndSavedOnlyOnApprovalThenRestored() throws Exception {
        var id = session(app, gear, true);
        var environment = assess(app, gemini, id);
        var inputs = ok(app.facade().planning().planInputs(id));
        assertTrue(inputs.ready()); assertTrue(inputs.candidates() >= 2); assertTrue(inputs.environment().isPresent());
        byte[] before = Files.readAllBytes(store());
        int sent = gemini.requests.size();
        var preview = generate(id, true);
        var plan = preview.plan();
        // Unapproved preview built only from verified, owned, compatible gear.
        assertFalse(plan.approved()); assertEquals(1, plan.revision()); assertTrue(preview.approvable(), () -> preview.validation().toString());
        assertEquals(Set.of(HEAVY_ZOOM, TELE), lenses(plan));
        assertEquals(gear.airshow.tele.id(), plan.loadout().orElseThrow().cameraLensAssignments().get(gear.airshow.a1.id()));
        assertTrue(plan.loadout().orElseThrow().compatibility().isUsable());
        assertEquals("f/6.3-f/8", plan.settings().get(ShootPlan.settingKey("aperture", Optional.of(gear.airshow.a1.id()))));
        assertEquals(2, plan.windows().size());
        assertTrue(plan.windows().stream().allMatch(w -> environment.availability().orElseThrow().contains(w.start(), w.end())));
        assertEquals(FakeEnvironmentProviders.TORONTO, plan.windows().getFirst().start().getZone());
        assertFalse(plan.checklist().contains("Spare lens cloth"), "equipment in the checklist is left out");
        assertTrue(preview.evidence().stream().anyMatch(m -> m.key().equals("plan.note.checklistRemoved")));
        assertEquals("Fast-moving aircraft at an airshow", plan.sessionInfo().get("subject"));
        assertEquals(List.of("primary: Sharp fast jets in flight", "primary: Close-up passes at long focal length", "secondary: Golden-hour flybys"), plan.shotPriorities());
        // The deterministic tool ran inside the loop; its numbers are F08 results, not model text.
        assertEquals(1, preview.tools().size()); assertTrue(preview.tools().getFirst().succeeded());
        assertTrue(plan.calculations().stream().anyMatch(c -> c.input().operation() == CalculationKind.DEPTH_OF_FIELD));
        assertTrue(plan.calculations().stream().anyMatch(c -> c.input().operation() == CalculationKind.FIELD_OF_VIEW), "baseline field of view of each mounted lens");
        assertEquals(sent + 2, gemini.requests.size(), "one tool request, one final reply");
        String second = gemini.requests.getLast();
        assertTrue(second.contains("Tool call 1 you requested: calculation") && second.contains("Hyperfocal distance"));
        for (String body : gemini.requests.subList(sent, gemini.requests.size())) {
            assertFalse(body.contains(id.toString()) || body.contains(FakeGemini.KEY) || body.contains("43.6275") || body.contains("Toronto airshow plan"));
            for (var item : gear.items()) assertFalse(body.contains(item.id().toString()), "inventory identifiers stay local");
        }
        // Generation saved nothing and did not change the session state.
        assertArrayEquals(before, Files.readAllBytes(store()));
        assertEquals("DRAFT", ok(app.facade().sessions().open(id)).status());
        // Rejection discards the proposal; it can no longer be approved.
        app.facade().planning().reject(id, plan);
        assertTrue(app.facade().planning().approve(id, plan).hasError("plan.error.foreign"));
        assertArrayEquals(before, Files.readAllBytes(store()), "a rejected proposal is not saved");
        // A proposal issued for another session is foreign there.
        var other = ok(app.facade().sessions().create("Other", day())).id();
        var approved = generate(id, true);
        assertTrue(app.facade().planning().approve(other, approved.plan()).hasError("plan.error.foreign"));
        var saved = ok(app.facade().planning().approve(id, approved.plan()));
        assertTrue(saved.approved()); assertEquals(1, saved.revision());
        var session = ok(app.facade().sessions().open(id));
        assertEquals("PLANNED", session.status(), "initial valid approval moves Draft to Planned");
        assertTrue(app.facade().planning().generatePreview(id).hasError("plan.error.exists"), "changes go through UC11 revisions");
        assertTrue(ok(app.facade().inventory().affectedSessions(gear.airshow.tele.id())).contains(id), "F05 warns before removing planned gear");
        // A new application instance restores the approved plan from storage.
        app.close(); app = boot();
        var restored = ok(app.facade().planning().plans(id));
        assertEquals(1, restored.size());
        var reopened = restored.getFirst();
        assertEquals(saved.id(), reopened.id()); assertTrue(reopened.approved());
        assertEquals(saved.settings(), reopened.settings()); assertEquals(saved.sessionInfo(), reopened.sessionInfo());
        assertEquals(saved.strategy(), reopened.strategy()); assertEquals(lenses(saved), lenses(reopened));
        assertEquals(saved.windows().size(), reopened.windows().size()); assertTrue(reopened.windows().getFirst().conditions().startsWith("Afternoon flying display"));
        assertEquals(saved.calculations().size(), reopened.calculations().size());
        var text = String.join("\n", PlanPresentation.section(reopened, "equipment", LocalizationService.english()));
        assertTrue(text.contains("compatibility verified by deterministic rules"), text);
    }

    @Test void naturalLanguageRevisionIsAnIndependentDraftPreservingUnaffectedDecisionsUntilApproved() throws Exception {
        var id = approvedPlan();
        var base = ok(app.facade().planning().plans(id)).getFirst();
        byte[] before = Files.readAllBytes(store());
        var preview = lighter(id);
        var draft = preview.plan();
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        assertEquals(base.id(), draft.id()); assertEquals(2, draft.revision()); assertFalse(draft.approved());
        assertEquals(Set.of(LIGHTER, TELE), lenses(draft));
        assertTrue(draft.loadout().orElseThrow().totalWeightGrams().orElseThrow() < base.loadout().orElseThrow().totalWeightGrams().orElseThrow(), "lighter");
        assertEquals(2, draft.loadout().orElseThrow().units(EquipmentType.CAMERA), "two-camera requirement kept");
        assertTrue(draft.loadout().orElseThrow().units(EquipmentType.LENS) <= 2, "no lens changes");
        assertTrue(preview.changedSections().contains("loadout"));
        for (String kept : List.of("settings", "schedule", "strategy", "shotPriorities", "preparation", "checklist", "fixedConstraints"))
            assertFalse(preview.changedSections().contains(kept), kept + " is unaffected");
        assertEquals(base.settings(), draft.settings()); assertEquals(base.fixedConstraints(), draft.fixedConstraints());
        assertEquals(base.sections().get("schedule"), draft.sections().get("schedule"));
        var comparison = String.join("\n", PlanPresentation.comparison(preview, LocalizationService.english()));
        assertTrue(comparison.contains("Changed: equipment") && comparison.contains("Fixed constraints: preserved"), comparison);
        // The saved plan stays exactly as approved while the revision is only a preview.
        assertArrayEquals(before, Files.readAllBytes(store()));
        assertEquals(Set.of(HEAVY_ZOOM, TELE), lenses(ok(app.facade().planning().plans(id)).getFirst()));
        var saved = ok(app.facade().planning().approve(id, draft));
        assertEquals(2, saved.revision());
        var history = ok(app.facade().planning().plans(id));
        assertEquals(List.of(1, 2), history.stream().map(ShootPlan::revision).toList(), "version history is kept");
        assertEquals(Set.of(HEAVY_ZOOM, TELE), lenses(history.getFirst()), "the earlier revision is unchanged");
        assertEquals("PLANNED", ok(app.facade().sessions().open(id)).status(), "a revision never re-applies PLAN_APPROVED");
        assertTrue(history.getLast().changeSummary().contains(LIGHTER_REQUEST));
        assertTrue(app.facade().planning().approve(id, draft).hasError("plan.error.foreign"), "a saved preview cannot be approved again");
    }

    @Test void conflictingAmbiguousMalformedAndFailedRequestsLeaveStoredDataUnchanged() throws Exception {
        var id = approvedPlan();
        byte[] before = Files.readAllBytes(store());
        gemini.reply(conflict());
        var rejected = app.facade().planning().revisePreview(id, "Use only one camera body and swap lenses as needed.");
        assertTrue(rejected.hasError("plan.revise.conflict"), rejected.errors()::toString);
        assertTrue(rejected.errors().getFirst().contains("two camera bodies"), "the conflict is explained, not relaxed");
        gemini.reply(clarify());
        assertTrue(app.facade().planning().revisePreview(id, "Change it.").hasError("plan.revise.clarify"));
        gemini.reply("{\"step\":\"final\",\"result\":{\"status\":\"change\"");
        assertTrue(app.facade().planning().revisePreview(id, LIGHTER_REQUEST).hasError("plan.error.malformed"));
        gemini.status(503, "");
        assertTrue(app.facade().planning().revisePreview(id, LIGHTER_REQUEST).hasError("ai.error.serviceUnavailable"));
        gemini.status(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}");
        assertTrue(app.facade().planning().revisePreview(id, LIGHTER_REQUEST).hasError("ai.error.authentication"));
        gemini.reply("{\"step\":\"tool\",\"tool\":{\"name\":\"environment\",\"arguments\":\"{}\"}}");
        assertTrue(app.facade().planning().revisePreview(id, LIGHTER_REQUEST).hasError("ai.error.unauthorizedTool"), "EnvironmentTool is not permitted for F10");
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER).replace("\"L", "\"L9"));
        assertTrue(app.facade().planning().revisePreview(id, LIGHTER_REQUEST).hasError("plan.problem.unknownCandidate"), "invented loadouts are rejected");
        assertTrue(app.facade().planning().revisePreview(id, " ").hasError("plan.error.request"));
        assertArrayEquals(before, Files.readAllBytes(store()), "no failure path writes");
        assertEquals(1, ok(app.facade().planning().plans(id)).size());
    }

    @Test void staleRevisionsAndPersistenceFailuresNeverReplaceTheSavedPlan() throws Exception {
        var id = approvedPlan();
        var first = lighter(id); var second = lighter(id);
        ok(app.facade().planning().approve(id, second.plan()));
        assertTrue(app.facade().planning().approve(id, first.plan()).hasError("plan.error.stale"), "the base changed since this preview");
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        assertTrue(app.facade().planning().revisePreview(id, LIGHTER_REQUEST).hasError("plan.error.noChange"), "the lighter loadout is already planned");
        Assumptions.assumeTrue(Files.getFileAttributeView(store(), DosFileAttributeView.class) != null, "Windows read-only check");
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, HEAVY_ZOOM));
        var back = ok(app.facade().planning().revisePreview(id, "Go back to the 100-400 mm zoom."));
        assertTrue(back.approvable(), () -> back.validation().toString());
        byte[] before = Files.readAllBytes(store());
        var attributes = Files.getFileAttributeView(store(), DosFileAttributeView.class);
        attributes.setReadOnly(true);
        try {
            var failed = app.facade().planning().approve(id, back.plan());
            assertFalse(failed.isSuccess(), "a failed save is never reported as approval");
            assertTrue(failed.hasError("error.session.storage"), failed.errors()::toString);
        } finally { attributes.setReadOnly(false); }
        assertArrayEquals(before, Files.readAllBytes(store()));
        var history = ok(app.facade().planning().plans(id));
        assertEquals(List.of(1, 2), history.stream().map(ShootPlan::revision).toList(), "previously saved plans are preserved");
        assertEquals(3, ok(app.facade().planning().approve(id, back.plan())).revision(), "the same validated preview can be saved once storage works");
    }

    @Test void missingAndStaleEnvironmentEvidenceIsLabelledAndNeverPresentedAsCurrent() throws Exception {
        var id = session(app, gear, true);
        var untimed = generate(id, false);
        assertTrue(untimed.evidence().stream().anyMatch(m -> m.key().equals("plan.env.noTiming")));
        assertTrue(untimed.plan().windows().isEmpty()); assertTrue(untimed.approvable(), () -> untimed.validation().toString());
        assertTrue(untimed.plan().limitations().stream().anyMatch(l -> l.contains("no timed schedule")), "recorded with the plan");
        var invented = generate(id, true);
        assertFalse(invented.approvable(), "windows without a verified zone and availability are rejected");
        assertTrue(invented.validation().violations().stream().anyMatch(m -> m.key().equals("plan.violation.noTiming")));
        var fresh = assess(app, gemini, id);
        var old = Instant.now().minus(Duration.ofHours(5));
        var timestamps = new LinkedHashMap<String, Instant>(); fresh.context().sourceTimestamps().forEach((k, v) -> timestamps.put(k, old));
        var c = fresh.context();
        var staleContext = new EnvironmentContext(c.place().orElseThrow(), c.date().orElseThrow(), c.endDate().orElseThrow(), c.zone(), c.weather(), c.hourly(),
            c.sunEvents(), c.sources(), timestamps, c.unavailableFields(), c.notices(), old);
        var stale = new EnvironmentAssessment(staleContext, fresh.request().orElseThrow(), fresh.subject(), fresh.availability(), fresh.lightPhases(), fresh.windows(),
            fresh.rejected(), fresh.interpretation(), fresh.notices(), Set.of(), old);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
        var preview = ok(app.facade().planning().generatePreview(id, stale));
        assertTrue(preview.evidence().stream().anyMatch(m -> m.key().equals("plan.env.stale")));
        var environment = evidence(gemini.requests.getLast()).getAsJsonObject("environment");
        assertFalse(environment.has("hourly") || environment.has("daily"), "stale forecasts are withheld from Gemini");
        assertTrue(environment.getAsJsonArray("staleOmitted").toString().contains("hourlyForecast"));
        assertTrue(preview.plan().limitations().stream().anyMatch(l -> l.contains("Stale evidence withheld")));
        assertEquals("DRAFT", ok(app.facade().sessions().open(id)).status(), "nothing was approved");
    }
}
