package com.photopilot.planning;

import com.photopilot.application.PlanPresentation;
import com.photopilot.command.ShootPlan;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.ShootingWindow;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 Review2 #02 (accepted design: unchanged windows keep their approval evidence): a saved revision's window
 * conditions are presented as recorded at approval, never as a current forecast; a revision that keeps the schedule says
 * so; provenance and timestamps are preserved; no new provider request is made to show or keep them. GUI and CLI render
 * through the same PlanPresentation.section.
 */
class PlanEvidencePresentationTest {
    static final String AS_APPROVED = "Conditions are as recorded when this revision was approved, not a current forecast.";
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    static String schedule(ShootPlan plan, LocalizationService l) { return String.join("\n", PlanPresentation.section(plan, "schedule", l)); }
    String cli(String... commands) {
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        for (String command : commands) cli.execute(command);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void newlyAssessedPreviewHistoricalApprovalAndKeptScheduleAreToldApart() throws Exception {
        var id = session(app, gear, true); assess(app, gemini, id);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
        var preview = ok(app.facade().planning().generatePreview(id));
        var en = LocalizationService.english();
        assertFalse(schedule(preview.plan(), en).contains(AS_APPROVED), "a preview is built from the current assessment");
        var saved = ok(app.facade().planning().approve(id, preview.plan()));
        assertTrue(schedule(saved, en).endsWith(AS_APPROVED), schedule(saved, en));
        assertTrue(saved.limitations().stream().anyMatch(l -> l.startsWith("Environment assessment used: ") && l.contains("retrieved 20")),
            "provenance and retrieval time are kept with the revision: " + saved.limitations());
        var zh = LocalizationService.english(); zh.useLanguage("zh-CN");
        assertTrue(schedule(saved, zh).contains("条件为批准此修订时记录的内容，并非当前预报。"));

        // A new process: no F09 assessment; the saved plan still shows its own record, and no provider is asked again.
        app.close(); app = boot();
        int providerRequests = providers.requests.size();
        String opened = cli("plan open " + id);
        assertTrue(opened.contains(AS_APPROVED) && opened.contains("Environment assessment used: "), opened);
        // Unchanged windows: an equipment-only revision keeps them with their approval evidence (accepted design).
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var revision = ok(app.facade().planning().revisePreview(id, "Make the setup lighter."));
        assertTrue(revision.approvable(), revision.validation()::toString);
        String comparison = String.join("\n", PlanPresentation.comparison(revision, en));
        assertTrue(comparison.contains("Schedule kept from revision 1 with the evidence it was approved with; not re-checked against a new forecast."), comparison);
        assertEquals(saved.sections().get("schedule"), revision.plan().sections().get("schedule"));
        var second = ok(app.facade().planning().approve(id, revision.plan()));
        assertTrue(schedule(second, en).endsWith(AS_APPROVED));
        assertEquals(providerRequests, providers.requests.size(), "showing or keeping approval evidence needs no new weather request");
        // A changed window needs current evidence: without an assessment in this run it is not approvable.
        gemini.replyWith(body -> {
            var result = new com.google.gson.JsonObject(); result.addProperty("status", "change"); result.addProperty("summary", "Start later.");
            result.add("questions", new com.google.gson.JsonArray()); result.addProperty("conflict", "");
            var changes = new com.google.gson.JsonArray(); changes.add("schedule"); result.add("changes", changes);
            var start = ZonedDateTime.of(day(), LocalTime.of(15, 0), FakeEnvironmentProviders.TORONTO);
            var windows = new com.google.gson.JsonArray(); windows.add(PlanFixtures.window("Later", start, start.plusMinutes(30), start, "mixed", "Later display.", "sunset"));
            result.add("schedule", windows);
            var envelope = new com.google.gson.JsonObject(); envelope.addProperty("step", "final"); envelope.add("result", result); return envelope.toString();
        });
        var moved = app.facade().planning().revisePreview(id, "Start the display at 15:00.");
        assertTrue(moved.hasError("plan.violation.noTiming"), "a new window is checked against current evidence, which this run does not have: " + moved.errors());
        assertEquals(List.of(1, 2), ok(app.facade().planning().plans(id)).stream().map(ShootPlan::revision).toList(), "nothing else was saved");
    }

    @Test void aSavedPlanWithoutRecordedProvenanceIsStillNeverPresentedAsCurrent() {
        var start = ZonedDateTime.of(LocalDate.of(2026, 11, 1), LocalTime.of(15, 0), ZoneId.of("America/Toronto"));
        var legacy = new ShootPlan(UUID.randomUUID(), 1, false, List.of(), Optional.empty(), Map.of(),
            List.of(new ShootingWindow(start, start.plusHours(1), start, "Afternoon: partly cloudy", false)), List.of(), List.of(), List.of(), Map.of(), List.of())
            .approvedCopy().value().orElseThrow();
        assertTrue(legacy.limitations().isEmpty(), "an older record without the provenance line");
        assertTrue(schedule(legacy, LocalizationService.english()).endsWith(AS_APPROVED), "the expired, timestamp-less evidence is labelled as historical");
        var empty = new ShootPlan(UUID.randomUUID(), 1, false, List.of(), Optional.empty(), Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of())
            .approvedCopy().value().orElseThrow();
        assertFalse(schedule(empty, LocalizationService.english()).contains(AS_APPROVED), "nothing to label without windows");
    }
}
