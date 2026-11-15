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
}
