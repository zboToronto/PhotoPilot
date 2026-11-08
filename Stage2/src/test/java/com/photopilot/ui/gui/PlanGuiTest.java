package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX F10 controls and worker callbacks against loopback Gemini/provider fakes; renders the shared layout. */
class PlanGuiTest {
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
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }

    @Test void guiGeneratesWithoutBlockingReviewsApprovesRevisesAndComparesPlans() throws Exception {
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
            assertTrue(label("plan-inputs").contains("Confirmed requirements:"), label("plan-inputs"));
            assertFalse(fx(() -> pane.lookup("#plan-generate").isDisabled()));

            gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true));
            // Same FX pulse as the click: generation runs off the FX thread with the controls disabled and progress shown.
            assertTrue(fx(() -> { ((Button) pane.lookup("#plan-generate")).fire();
                return pane.busy() && pane.feedbackText().contains("Generating the shoot plan") && pane.lookup("#plan-session").getParent().getParent().getParent().isDisabled(); }));
            ready();
            assertNotNull(fx(() -> pane.preview()));
            assertTrue(label("plan-validation").contains("Validation passed"), label("plan-validation"));
            assertTrue(label("plan-section-equipment").contains("Mounted: Sony FE 200-600mm G on Sony A1"));
            assertTrue(label("plan-section-schedule").contains("Afternoon flying display"));
            assertTrue(label("plan-evidence").contains("Tool call 1: calculation"));
            assertTrue(fx(() -> pane.dirty()), "an unsaved preview is protected from silent discard");
            snapshot("f10-preview-wide");
            click("plan-reject");
            assertTrue(fx(() -> pane.feedbackText().contains("Proposal rejected")));
            assertTrue(app.facade().planning().plans(id).value().orElseThrow().isEmpty());

            gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true));
            click("plan-generate"); click("plan-approve");
            assertTrue(fx(() -> pane.feedbackText().contains("Plan revision 1 approved and saved")), () -> "feedback: " + pane.feedbackText());
            ready();
            assertEquals("PLANNED", app.facade().sessions().open(id).value().orElseThrow().status());
            assertTrue(label("plan-history").contains("Revision 1: Initial approved plan"));
            assertTrue(label("plan-saved-equipment").contains(PlanFixtures.HEAVY_ZOOM));

            action(() -> ((TextArea) pane.lookup("#plan-request")).setText("Make the setup lighter, but keep the two-camera requirement and avoid lens changes."));
            gemini.replyWith(body -> PlanFixtures.lighter(body, PlanFixtures.TELE, PlanFixtures.LIGHTER));
            click("plan-revise");
            assertTrue(label("plan-compare").contains("Changed: equipment"), label("plan-compare"));
            assertTrue(label("plan-section-equipment").contains(PlanFixtures.LIGHTER));
            assertTrue(label("plan-saved-equipment").contains(PlanFixtures.HEAVY_ZOOM), "the saved plan is shown unchanged during the preview");
            snapshot("f10-revision-wide");
            click("plan-approve");
            assertTrue(fx(() -> pane.feedbackText().contains("Plan revision 2 approved and saved")), () -> "feedback: " + pane.feedbackText());
            ready();
            assertEquals(2, app.facade().planning().plans(id).value().orElseThrow().size());
            action(() -> { app.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            assertTrue(label("plan-history").contains("修订 2"), label("plan-history"));
            snapshot("f10-saved-narrow-zh");
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
