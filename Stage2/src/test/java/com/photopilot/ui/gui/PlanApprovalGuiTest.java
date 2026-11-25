package com.photopilot.ui.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F10 GUI approval of a preview whose model reply had a mistake the assembly removed: blocked in the GUI, valid ones approve. */
class PlanApprovalGuiTest {
    @TempDir Path root;
    PlanPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F10 operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); ready(); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    /** The valid airshow reply with the iso setting given twice (the assembly keeps the first and reports the second). */
    static String duplicateSetting(String body) {
        var envelope = JsonParser.parseString(PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true)).getAsJsonObject();
        var setting = new JsonObject(); setting.addProperty("setting", "iso"); setting.addProperty("value", "400-12800"); setting.addProperty("camera", "all");
        envelope.getAsJsonObject("result").getAsJsonArray("settings").add(setting);
        return envelope.toString();
    }

    @Test void guiKeepsApprovalDisabledForABlockedPreviewAndApprovesAValidOne() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var gear = new PlanFixtures.Gear();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = PlanFixtures.session(app, gear, true);
            PlanFixtures.assess(app, gemini, id);
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane = new PlanPane(app.facade(), executor, app.localization()); workspace.connectPlanning(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                workspace.selectPlanning(); workspace.applyCss(); workspace.layout();
            }); ready();

            gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(PlanApprovalGuiTest::duplicateSetting);
            click("plan-generate");
            assertNotNull(fx(() -> pane.preview()));
            assertTrue(label("plan-validation").contains("Validation failed") && label("plan-validation").contains("was given twice"), label("plan-validation"));
            assertTrue(fx(() -> pane.feedbackText().contains("did not pass validation")), () -> "feedback: " + pane.feedbackText());
            assertTrue(fx(() -> pane.lookup("#plan-approve").isDisabled()), "approval is not offered for a blocked preview");
            click("plan-approve");
            assertTrue(app.facade().planning().plans(id).value().orElseThrow().isEmpty(), "nothing saved");
            assertEquals("DRAFT", app.facade().sessions().open(id).value().orElseThrow().status());
            click("plan-reject");

            gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true));
            click("plan-generate");
            assertTrue(label("plan-validation").contains("Validation passed"), label("plan-validation"));
            assertFalse(fx(() -> pane.lookup("#plan-approve").isDisabled()));
            click("plan-approve");
            assertTrue(fx(() -> pane.feedbackText().contains("Plan revision 1 approved and saved")), () -> "feedback: " + pane.feedbackText());
            ready();
            assertEquals("PLANNED", app.facade().sessions().open(id).value().orElseThrow().status());
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }

    /** Stage2OptRe2 D04 / C49 in the GUI: F03 14:00 for 60 min, F09 13:00-17:00, model window 13:10-13:45. */
    @Test void guiRefusesAWindowBeforeTheConfirmedStartTimeLikeTheCli() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var gear = new PlanFixtures.Gear();
        var zone = FakeEnvironmentProviders.TORONTO; var day = PlanFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            for (var item : gear.items()) PlanFixtures.ok(app.facade().inventory().add(item, true));
            var id = PlanFixtures.ok(app.facade().sessions().create("QA timing boundary", day)).id();
            var base = PlanFixtures.requirement(day);
            var fixed = new com.photopilot.model.ShootRequirement("Fixed 14:00 start", base.subject(), base.goals(),
                java.util.Optional.of(java.time.ZonedDateTime.of(day, java.time.LocalTime.of(14, 0), zone)), java.util.Optional.of(day), "", java.util.Optional.empty(),
                java.util.Optional.of(java.time.Duration.ofMinutes(60)), base.cameraCount(), base.lensChangesAllowed(), base.maxWeightGrams(), base.portability(),
                base.gearConstraints(), base.priorities(), base.fixedConstraints(), java.util.List.of(), true);
            PlanFixtures.ok(app.facade().planning().confirm(id, fixed.asDraft()));
            PlanFixtures.ok(app.facade().planning().confirmProfile(id, PlanFixtures.profile().asDraft()));
            var place = new com.photopilot.model.GeoLocation("Toronto", 43.65, -79.38, false, false, java.util.Optional.empty());
            gemini.reply(com.photopilot.environment.EnvironmentFixtures.airshowInterpretation(day));
            PlanFixtures.ok(app.facade().assessEnvironment(id, new com.photopilot.model.EnvironmentRequest(place, day, java.time.LocalTime.of(13, 0),
                java.time.LocalTime.of(17, 0), java.util.Optional.empty(), java.util.Optional.of(zone), "", java.util.List.of())));
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane = new PlanPane(app.facade(), executor, app.localization()); workspace.connectPlanning(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                workspace.selectPlanning(); workspace.applyCss(); workspace.layout();
            }); ready();
            var store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = java.nio.file.Files.readAllBytes(store);
            gemini.replyWith(body -> {
                var envelope = JsonParser.parseString(PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, false)).getAsJsonObject();
                var start = java.time.ZonedDateTime.of(day, java.time.LocalTime.of(13, 10), zone);
                var schedule = new com.google.gson.JsonArray();
                var w = new JsonObject(); w.addProperty("label", "Window before the confirmed start");
                w.addProperty("start", start.toLocalDateTime().toString()); w.addProperty("end", start.plusMinutes(35).toLocalDateTime().toString());
                w.addProperty("arrival", start.minusMinutes(5).toLocalDateTime().toString()); w.addProperty("light", "daylight");
                w.addProperty("conditions", "Use daylight for the display."); var ids = new com.google.gson.JsonArray(); ids.add("sunset"); w.add("evidence", ids);
                schedule.add(w); envelope.getAsJsonObject("result").add("schedule", schedule);
                return envelope.toString();
            });
            click("plan-generate");
            assertNotNull(fx(() -> pane.preview()));
            assertTrue(label("plan-validation").contains("Validation failed") && label("plan-validation").contains("outside the confirmed shooting time"), label("plan-validation"));
            assertTrue(fx(() -> pane.lookup("#plan-approve").isDisabled()), "approval is not offered for the C49 window");
            click("plan-approve");
            assertArrayEquals(before, java.nio.file.Files.readAllBytes(store), "the GUI path writes nothing");
            assertEquals("DRAFT", app.facade().sessions().open(id).value().orElseThrow().status());
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
