package com.photopilot.planning;

import com.google.gson.*;
import com.photopilot.command.ReviseStrategyCommand;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.PlanPreview;
import com.photopilot.service.PlanningService;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.PlanValidator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F10 approval boundary: {@link PlanningService#approve} decides from the preview it issued (its original report,
 * assembly problems included, and the content it was validated with) and from the current session, requirement,
 * profile and inventory; never from what a caller checked or shows. Loopback Gemini and provider fakes only.
 */
class PlanApprovalBoundaryTest {
    /** Model-reply mistakes PlanAssembly removes from the plan (never repairs) and records as blocking problems. */
    enum Mistake {
        DUPLICATE_SETTING("plan.problem.duplicateSetting"), UNKNOWN_BODY("plan.violation.settingCamera"),
        UNSUPPORTED_SETTING("plan.violation.settingName"), UNREADABLE_WINDOW("plan.violation.window");
        final String key;
        Mistake(String key) { this.key = key; }
    }

    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(root); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot(Path dir) { return new ApplicationBootstrap(providers.environment(gemini.environment(dir))); }
    PlanningService planning() { return app.facade().planning(); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    byte[] stored() throws IOException { return Files.readAllBytes(store()); }
    String status(UUID id) { return ok(app.facade().sessions().open(id)).status(); }

    /** Session with confirmed requirement, reviewed profile, inventory and a remembered F09 assessment. */
    UUID ready() { var id = session(app, gear, true); assess(app, gemini, id); return id; }
    /** The airshow generation reply (one tool request, then the plan) with the given mistakes added. */
    PlanPreview generate(UUID id, Mistake... mistakes) {
        gemini.reply(depthOfFieldRequest()).replyWith(body -> withMistakes(plan(body, TELE, HEAVY_ZOOM, true), mistakes));
        return ok(planning().generatePreview(id));
    }
    UUID approvedPlan() {
        var id = ready(); var preview = generate(id);
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(planning().approve(id, preview.plan()));
        return id;
    }

    static String withMistakes(String reply, Mistake... mistakes) {
        var envelope = JsonParser.parseString(reply).getAsJsonObject(); var result = envelope.getAsJsonObject("result");
        var settings = result.getAsJsonArray("settings");
        for (var mistake : mistakes) switch (mistake) {
            case DUPLICATE_SETTING -> settings.add(setting("iso", "400-12800", "all"));
            case UNKNOWN_BODY -> settings.add(setting("whiteBalance", "Daylight", "G99"));
            case UNSUPPORTED_SETTING -> settings.add(setting("flashPower", "1/4", "all"));
            case UNREADABLE_WINDOW -> {
                var window = new JsonObject(); window.addProperty("label", "Evening flypast"); window.addProperty("start", "after the show");
                window.addProperty("end", "at sunset"); window.addProperty("arrival", ""); window.addProperty("light", "mixed");
                window.addProperty("conditions", "Late flypast."); window.add("evidence", new JsonArray());
                result.getAsJsonArray("schedule").add(window);
            }
        }
        return envelope.toString();
    }
    /** UC11 reply that changes only the settings, opening the A1's aperture beyond its mounted lens (a validation violation). */
    static String widerAperture(String request) {
        var evidence = evidence(request); var result = new JsonObject();
        result.addProperty("status", "change"); result.addProperty("summary", "Open the aperture for more light; everything else stays.");
        result.add("questions", new JsonArray()); result.addProperty("conflict", ""); result.add("changes", strings("settings"));
        var settings = new JsonArray();
        settings.add(setting("shutterSpeed", "1/1600-1/3200 s", "all")); settings.add(setting("aperture", "f/2.8", gear(evidence, "A1")));
        settings.add(setting("iso", "100-3200", "all")); settings.add(setting("autofocusMode", "AF-C with subject tracking", "all"));
        result.add("settings", settings);
        var envelope = new JsonObject(); envelope.addProperty("step", "final"); envelope.add("result", result);
        return envelope.toString();
    }
    static Set<String> keys(List<LocalizedMessage> messages) { var keys = new TreeSet<String>(); messages.forEach(m -> keys.add(m.key())); return keys; }
    static void rejected(Result<?> result, String... keys) {
        assertFalse(result.isSuccess(), "approval must fail");
        for (String key : keys) assertTrue(result.hasError(key), () -> key + " expected in " + keys(result.messages()));
    }
    /** The same requirement as the fixture with fewer goals, so its fixed constraints differ. */
    static ShootRequirement fewerGoals() {
        return new ShootRequirement("Airshow: two bodies, minimal lens changes, telephoto, portable", "Fast-moving aircraft at an airshow",
            List.of("Sharp aircraft in flight"), Optional.empty(), Optional.of(day()), "afternoon to sunset", Optional.empty(),
            Optional.of(Duration.ofHours(3)), Optional.of(2), Optional.of(false), Optional.empty(), "keep the kit portable", List.of(),
            Map.of("telephoto reach", 1, "portability", 2), Map.of(), List.of(), true);
    }

    // A
    @Test void anAssemblyProblemIsRecordedWithThePreviewAndMakesItNotApprovable() {
        var id = ready();
        var preview = generate(id, Mistake.DUPLICATE_SETTING);
        assertFalse(preview.approvable());
        assertFalse(preview.validation().valid());
        assertEquals(Set.of("plan.problem.duplicateSetting"), keys(preview.validation().blocking()), "the reason stays with the preview");
        assertEquals("100-3200", preview.plan().settings().get("iso"), "the second value was removed, not merged");
    }

    // B, C, O
    @Test void aSanitizedPlanThatPassesValidationIsStillRejectedWhenTheServiceIsCalledDirectly() throws Exception {
        var id = ready();
        var valid = generate(id);
        var blocked = generate(id, Mistake.DUPLICATE_SETTING);
        // The removed mistake leaves a plan identical to a valid one, and the plan validator alone accepts it.
        assertTrue(valid.approvable(), () -> valid.validation().toString());
        assertEquals(valid.plan().sections(), blocked.plan().sections(), "sanitization removed the invalid input");
        var assessment = planning().environmentFor(id).orElseThrow();
        var validator = new PlanValidator(new CompatibilityValidator(), new TechnicalCalculator());
        var context = new PlanValidator.Context(Optional.of(ok(planning().profile(id))), Optional.of(new GearInventory(gear.items())), Optional.of(assessment),
            assessment.availability(), assessment.context().zone(), Optional.of(day()), Optional.empty());
        var alone = validator.assess(blocked.plan(), ok(planning().requirement(id)), context);
        assertTrue(alone.valid(), () -> "the sanitized plan passes PlanValidator on its own: " + alone);
        assertEquals(Set.of("plan.problem.duplicateSetting"), keys(blocked.validation().blocking()), "only the assembly problem blocks the preview");
        // A direct call that never asks preview.approvable() is still refused, with the original reason.
        byte[] before = stored();
        rejected(planning().approve(id, blocked.plan()), "plan.problem.duplicateSetting");
        assertArrayEquals(before, stored(), "nothing is saved");
        assertEquals("DRAFT", status(id), "no Draft -> Planned transition");
        assertTrue(ok(planning().plans(id)).isEmpty());
        rejected(planning().approve(id, blocked.plan()), "plan.problem.duplicateSetting");
    }

    // D
    @Test void aValidPreviewIsApprovedSavedAndMovesTheSessionFromDraftToPlanned() throws Exception {
        var id = ready();
        var preview = generate(id);
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        var saved = ok(planning().approve(id, preview.plan()));
        assertTrue(saved.approved()); assertEquals(1, saved.revision()); assertEquals(preview.plan().sections(), saved.sections());
        assertEquals("PLANNED", status(id));
        app.close(); app = boot(root);
        assertEquals(List.of(1), ok(planning().plans(id)).stream().map(ShootPlan::revision).toList(), "persisted");
    }

    // E
    @Test void nonBlockingFindingsDoNotPreventApproval() {
        var id = ready();
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true).replace("\"100-3200\"", "\"Auto ISO\""));
        var preview = ok(planning().generatePreview(id));
        assertTrue(keys(preview.validation().unverified()).contains("plan.unverified.setting"), () -> preview.validation().toString());
        assertTrue(preview.evidence().stream().anyMatch(m -> m.key().equals("plan.note.checklistRemoved")), "an application note, not a blocker");
        assertFalse(preview.plan().limitations().isEmpty());
        assertTrue(preview.validation().blocking().isEmpty()); assertTrue(preview.approvable());
        assertEquals(1, ok(planning().approve(id, preview.plan())).revision());
    }

    // F
    @Test void everyOriginalBlockingProblemIsReportedAndApprovalFails() throws Exception {
        var id = ready();
        var preview = generate(id, Mistake.values());
        for (var mistake : Mistake.values()) assertTrue(keys(preview.validation().blocking()).contains(mistake.key), mistake::name);
        byte[] before = stored();
        rejected(planning().approve(id, preview.plan()), Arrays.stream(Mistake.values()).map(m -> m.key).toArray(String[]::new));
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id));
    }

    // G
    @Test void unknownForgedAndCopiedPreviewsAreNotApprovable() throws Exception {
        var id = ready();
        var preview = generate(id);
        var p = preview.plan();
        var forged = new ShootPlan(UUID.randomUUID(), 1, false, List.of(), p.loadout(), p.settings(), p.windows(), p.checklist(), p.preparation(),
            p.shotPriorities(), p.fixedConstraints(), p.calculations(), p.summary(), p.sessionInfo(), p.strategy(), p.equipmentNotes(), p.tradeOffs(), p.limitations(), "");
        byte[] before = stored();
        rejected(planning().approve(id, forged), "plan.error.foreign");
        var copy = p.withExplanation(p.summary(), p.changeSummary(), p.limitations(), p.calculations(), p.provisionalReasons());
        assertEquals(p.sections(), copy.sections());
        rejected(planning().approve(id, copy), "plan.error.foreign");
        assertArrayEquals(before, stored());
        assertEquals(1, ok(planning().approve(id, p)).revision(), "only the issued object itself is approvable");
    }

    // H
    @Test void aCallerSideCopyOrChangeOfABlockedPreviewIsNotTrusted() throws Exception {
        var id = ready();
        var blocked = generate(id, Mistake.UNKNOWN_BODY);
        var clean = new PlanValidator.Report(List.of(), List.of(), List.of(), List.of());
        var forged = new PlanPreview(blocked.sessionId(), blocked.plan(), blocked.base(), clean, blocked.evidence(), blocked.tools(), blocked.questions(),
            blocked.request(), blocked.changedSections());
        assertTrue(forged.approvable(), "the caller-side copy claims it is approvable");
        byte[] before = stored();
        rejected(planning().approve(forged.sessionId(), forged.plan()), "plan.violation.settingCamera");
        // "Repairing" the issued draft in place (PlanCommands can still edit an unapproved draft) is detected as well.
        var repaired = new HashMap<>(blocked.plan().settings()); repaired.put("whiteBalance", "Daylight");
        ok(new ReviseStrategyCommand(repaired, Map.of(), List.of(), List.of(), List.of(), List.of(), Set.of("settings")).applyTo(blocked.plan()));
        rejected(planning().approve(id, blocked.plan()), "plan.error.modified");
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id));
    }

    // I
    @Test void anEligiblePreviewCannotLendItsIdentityOrApprovalToOtherContent() throws Exception {
        var id = ready();
        var eligible = generate(id);
        var blocked = generate(id, Mistake.UNSUPPORTED_SETTING);
        var e = eligible.plan(); var b = blocked.plan();
        assertTrue(eligible.approvable()); assertFalse(blocked.approvable());
        byte[] before = stored();
        var substituted = new ShootPlan(e.id(), e.revision(), false, b.provisionalReasons(), b.loadout(), b.settings(), b.windows(), b.checklist(), b.preparation(),
            b.shotPriorities(), b.fixedConstraints(), b.calculations(), b.summary(), b.sessionInfo(), b.strategy(), b.equipmentNotes(), b.tradeOffs(), b.limitations(), "");
        rejected(planning().approve(id, substituted), "plan.error.foreign");
        rejected(planning().approve(id, b), "plan.violation.settingName");
        // The eligible preview's identity cannot carry content it was not validated with.
        new ReviseStrategyCommand(Map.of("iso", "100-25600"), Map.of(), List.of(), List.of(), List.of(), List.of(), Set.of("settings")).applyTo(e);
        rejected(planning().approve(id, e), "plan.error.modified");
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id));
        assertEquals(1, ok(planning().approve(id, generate(id).plan())).revision(), "an unchanged eligible preview is still approvable");
    }

    // J
    @Test void requirementChangesAfterThePreviewStillBlockApproval() throws Exception {
        var id = ready();
        var preview = generate(id);
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(planning().confirm(id, fewerGoals().asDraft()));
        byte[] before = stored();
        rejected(planning().approve(id, preview.plan()), "plan.violation.constraintsChanged");
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id));
    }

    // K
    @Test void inventoryChangesAfterThePreviewStillBlockApproval() throws Exception {
        var id = ready();
        var preview = generate(id);
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(app.facade().inventory().remove(gear.airshow.tele.id(), true));
        byte[] before = stored();
        rejected(planning().approve(id, preview.plan()));
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id));
        assertTrue(ok(planning().plans(id)).isEmpty());
    }

    // L
    @Test void aBlockedRegenerationLeavesTheApprovedPlanHistoryAndStateUnchanged() throws Exception {
        var id = approvedPlan();
        var saved = ok(planning().plans(id)).getFirst();
        ok(planning().confirm(id, fewerGoals().asDraft()));
        assess(app, gemini, id);
        var regenerated = generate(id, Mistake.DUPLICATE_SETTING);
        assertTrue(regenerated.plan().regeneration()); assertEquals(2, regenerated.plan().revision());
        assertEquals(Set.of("plan.problem.duplicateSetting"), keys(regenerated.validation().blocking()));
        byte[] before = stored();
        rejected(planning().approve(id, regenerated.plan()), "plan.problem.duplicateSetting");
        assertArrayEquals(before, stored(), "history and state unchanged");
        var history = ok(planning().plans(id));
        assertEquals(List.of(1), history.stream().map(ShootPlan::revision).toList());
        assertEquals(saved.sections(), history.getFirst().sections(), "the approved plan is unchanged");
        assertEquals("PLANNED", status(id));
        assertEquals(2, ok(planning().approve(id, generate(id).plan())).revision(), "the same regeneration without the mistake is approvable");
    }

    // M, N (revision)
    @Test void aBlockedRevisionCannotBeApprovedUntilACorrectedRevisionIsPrepared() throws Exception {
        var id = approvedPlan();
        gemini.replyWith(PlanApprovalBoundaryTest::widerAperture);
        var blocked = ok(planning().revisePreview(id, "Open the aperture for more light."));
        assertTrue(blocked.revision()); assertEquals(2, blocked.plan().revision());
        assertFalse(blocked.approvable()); assertTrue(keys(blocked.validation().blocking()).contains("plan.violation.aperture"), () -> blocked.validation().toString());
        byte[] before = stored();
        rejected(planning().approve(id, blocked.plan()), "plan.violation.aperture");
        assertArrayEquals(before, stored()); assertEquals(1, ok(planning().plans(id)).size()); assertEquals("PLANNED", status(id));
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var corrected = ok(planning().revisePreview(id, F10WorkflowTest.LIGHTER_REQUEST));
        assertNotSame(blocked.plan(), corrected.plan()); assertTrue(corrected.approvable(), () -> corrected.validation().toString());
        assertEquals(2, ok(planning().approve(id, corrected.plan())).revision());
        rejected(planning().approve(id, blocked.plan()), "plan.violation.aperture");
        assertEquals(List.of(1, 2), ok(planning().plans(id)).stream().map(ShootPlan::revision).toList());
    }

    // N (generation)
    @Test void aCorrectedPreviewWithANewIdentityIsApprovableWhileTheBlockedOneStaysBlocked() {
        var id = ready();
        var blocked = generate(id, Mistake.UNREADABLE_WINDOW);
        rejected(planning().approve(id, blocked.plan()), "plan.violation.window");
        var corrected = generate(id);
        assertNotEquals(blocked.plan().id(), corrected.plan().id());
        assertEquals(1, ok(planning().approve(id, corrected.plan())).revision());
        rejected(planning().approve(id, blocked.plan()), "plan.violation.window");
        assertEquals(1, ok(planning().plans(id)).size());
    }

    // P
    @Test void previewsStayBoundToTheirSessionAndApplicationInstance() throws Exception {
        var id = ready();
        var preview = generate(id);
        var other = ok(app.facade().sessions().create("Other", day())).id();
        rejected(planning().approve(other, preview.plan()), "plan.error.foreign");
        try (var elsewhere = boot(root.resolve("other-profile"))) {
            rejected(elsewhere.facade().planning().approve(id, preview.plan()), "plan.error.foreign");
        }
        assertEquals("DRAFT", status(id)); assertEquals("DRAFT", status(other));
        assertEquals(1, ok(planning().approve(id, preview.plan())).revision());
    }

    // S
    @Test void aPreviewWithoutItsRegistrationIsNeverApprovable() throws Exception {
        var id = ready();
        var rejectedByUser = generate(id);
        planning().reject(id, rejectedByUser.plan());
        rejected(planning().approve(id, rejectedByUser.plan()), "plan.error.foreign");
        var evicted = generate(id);
        assertTrue(evicted.approvable());
        for (int i = 0; i < 64; i++) { // PlanningService keeps the 64 most recent previews
            gemini.replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
            ok(planning().generatePreview(id));
        }
        rejected(planning().approve(id, evicted.plan()), "plan.error.foreign");
        var beforeRestart = generate(id);
        assertTrue(beforeRestart.approvable());
        app.close(); app = boot(root);
        byte[] before = stored();
        rejected(planning().approve(id, beforeRestart.plan()), "plan.error.foreign");
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id)); assertTrue(ok(planning().plans(id)).isEmpty());
    }

    // Persistence boundary: no save path other than approval can store an approved plan.
    @Test void aBlockedPlanCannotBeStoredThroughAPlainSessionSave() throws Exception {
        var id = ready();
        var blocked = generate(id, Mistake.DUPLICATE_SETTING);
        var forged = ok(ok(ok(app.facade().sessions().open(id)).appendApprovedPlan(ok(blocked.plan().approvedCopy()))).apply(com.photopilot.state.SessionEvent.PLAN_APPROVED));
        byte[] before = stored();
        rejected(app.facade().sessions().save(forged), "error.session.history");
        rejected(app.facade().sessions().update(forged, "Renamed", forged.date(), forged.location(), forged.notes()), "error.session.history");
        rejected(planning().confirm(forged, forged.requirements().orElseThrow()), "error.session.history");
        assertArrayEquals(before, stored()); assertEquals("DRAFT", status(id)); assertTrue(ok(planning().plans(id)).isEmpty());
    }

    // Approval through a profile-scoped context (ProfileAccess) appends like the plain bootstrap.
    @Test void approvalWorksThroughAProfileScopedContext() throws Exception {
        app.close(); app = null;
        try (var profiles = new com.photopilot.application.ProfileManager(providers.environment(gemini.environment(root.resolve("profiles"))))) {
            app = profiles.activeContext();
            var id = ready();
            var preview = generate(id);
            assertTrue(preview.approvable(), () -> preview.validation().toString());
            assertEquals(1, ok(planning().approve(id, preview.plan())).revision());
            assertEquals("PLANNED", status(id));
            app = null;
        }
    }

    // Q (CLI; the GUI counterpart is PlanApprovalGuiTest)
    @Test void cliRefusesABlockedPreviewAndApprovesAValidOne() throws Exception {
        var id = ready();
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        gemini.reply(depthOfFieldRequest()).replyWith(body -> withMistakes(plan(body, TELE, HEAVY_ZOOM, true), Mistake.DUPLICATE_SETTING));
        assertEquals(0, cli.execute("plan generate " + id));
        String generated = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(generated.contains("Validation failed") && generated.contains("was given twice"), generated);
        bytes.reset();
        assertEquals(2, cli.execute("plan approve"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("did not pass validation and cannot be approved"), bytes.toString(StandardCharsets.UTF_8));
        assertTrue(ok(planning().plans(id)).isEmpty()); assertEquals("DRAFT", status(id));
        assertEquals(0, cli.execute("plan reject"));
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
        assertEquals(0, cli.execute("plan generate " + id));
        bytes.reset();
        assertEquals(0, cli.execute("plan approve"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Plan revision 1 approved and saved"), bytes.toString(StandardCharsets.UTF_8));
        assertEquals("PLANNED", status(id));
    }
}
