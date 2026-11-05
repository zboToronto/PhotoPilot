package com.photopilot.ui.gui;

import com.photopilot.capability.CapabilityFixtures;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX F06 controls and worker callbacks against a loopback Gemini fake, in the shared workspace layout. */
class CapabilityProfileGuiTest {
    @TempDir Path root;
    CapabilityPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F06 operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); ready(); }
    void text(String id, String value) throws Exception { action(() -> ((TextInputControl) pane.lookup("#" + id)).setText(value)); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    String feedback() throws Exception { return fx(() -> pane.profile().feedbackText()); }
    /** Shown only when the node and every ancestor up to the pane are visible. */
    boolean shown(String id) throws Exception {
        return fx(() -> { Node node = pane.lookup("#" + id); while (node != null && node != pane) { if (!node.isVisible()) return false; node = node.getParent(); } return node == pane; });
    }
    @SuppressWarnings("unchecked")
    void enter(String capability, String min, String max, String priority) throws Exception {
        action(() -> ((ComboBox<String>) pane.lookup("#needs-need-capability")).setValue(capability));
        text("needs-need-minimum", min); text("needs-need-maximum", max); text("needs-need-priority", priority);
        click("needs-need-add");
    }
    void launch(ApplicationBootstrap app, ExecutorService executor) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        action(() -> {
            workspace = new PhotoPilotWorkspace(app.localization());
            pane = new CapabilityPane(app.facade(), executor, app.localization()); workspace.connectCapabilities(pane);
            stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            ((ToggleButton) workspace.lookup("#nav-F06")).fire(); workspace.applyCss(); workspace.layout();
        }); ready();
    }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }

    @Test void guiAnalyzesReviewsEditsClarifiesCancelsConfirmsReplacesAndRestores() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        UUID id;
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            id = confirmedSession(app, gemini);
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            launch(app, executor);
            assertTrue(label("needs-requirement").contains("Fast-moving aircraft"), label("needs-requirement"));
            assertTrue(label("needs-saved").contains("no confirmed capability profile"));
            assertTrue(shown("capability-refresh"), "the inventory report stays on the page");
            snapshot("f06-profile-start");

            gemini.status(503, "");
            click("needs-analyze");
            assertTrue(feedback().contains("AI capability analysis failed"), feedback());
            assertNull(fx(() -> pane.profile().proposal()), "a failed analysis opens nothing");
            assertFalse(shown("needs-confirm"));

            gemini.reply(PROPOSAL);
            click("needs-analyze");
            assertTrue(feedback().contains("ready for review"), feedback());
            assertTrue(shown("needs-confirm")); assertTrue(fx(() -> pane.dirty()));
            assertTrue(label("needs-origin").contains("proposed by Gemini"));
            assertTrue(label("needs-tradeoffs").contains("Telephoto reach competes"));
            assertTrue(label("needs-review").contains("Weather sealing"));
            assertTrue(label("needs-questions").contains("How far from the flight line"));
            assertTrue(label("needs-need-line-focalLength").contains("300-500 mm"));
            assertTrue(label("needs-need-validation").contains("The needs are valid"));
            snapshot("f06-profile-review");

            enter("totalWeight", "", "5000", "2");
            assertTrue(label("needs-need-line-totalWeight").contains("at most 5000 g | priority 2"));
            enter("focalLength", "", "", "1");
            assertTrue(feedback().contains("needs a minimum or maximum"), "invalid input is rejected with its reason");
            click("needs-need-remove-storageCapacity");
            assertNull(fx(() -> pane.lookup("#needs-need-line-storageCapacity")));
            enter("lensCount", "3", "", "2");
            assertTrue(label("needs-need-validation").contains("one lens per body"), "contradiction is shown live");
            click("needs-confirm");
            assertTrue(app.facade().planning().profile(id).hasError("needs.error.none"), "an invalid draft is never saved");
            enter("lensCount", "", "2", "3");

            gemini.reply(reply("[" + need("focalLength", 400, 600, 1, true, "Distant passes.") + ","
                + need("totalWeight", null, 5000, 2, true, "Keep it light.") + "]"));
            text("needs-answers", "About 400 metres from the flight line.");
            click("needs-clarify");
            assertTrue(label("needs-need-line-focalLength").contains("400-600 mm"), label("needs-need-line-focalLength"));
            assertTrue(label("needs-review").contains("Camera bodies set to exactly 2"));

            byte[] before = Files.readAllBytes(store);
            click("needs-cancel"); click("needs-discard");
            assertNull(fx(() -> pane.profile().proposal())); assertFalse(fx(() -> pane.dirty()));
            assertArrayEquals(before, Files.readAllBytes(store), "cancel never writes");

            gemini.reply(PROPOSAL);
            click("needs-analyze"); click("needs-confirm");
            assertTrue(feedback().contains("Capability profile confirmed and saved"), feedback());
            ready();
            assertTrue(label("needs-saved").contains("Focal length: 300-500 mm") && label("needs-saved").contains("proposed by Gemini"));
            assertEquals(10, app.facade().planning().profile(id).value().orElseThrow().needs().size());

            gemini.reply(reply("[" + need("focalLength", 200, 600, 1, true, "Wider reach.") + "]"));
            click("needs-analyze");
            assertTrue(shown("needs-replace-notice"));
            assertEquals("Replace saved profile", fx(() -> ((Button) pane.lookup("#needs-confirm")).getText()));
            click("needs-confirm"); ready();
            assertTrue(label("needs-saved").contains("200-600 mm"), "explicit replacement saved");

            click("needs-manual");
            assertFalse(shown("needs-replace-notice"), "editing the saved profile is the explicit change");
            click("needs-need-remove-lensCount"); click("needs-confirm"); ready();
            var saved = app.facade().planning().profile(id).value().orElseThrow();
            assertTrue(saved.need("lensCount").isEmpty()); assertEquals(EquipmentRequirementProfile.AI_ASSISTED, saved.origin());
            action(() -> stage.close());
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }

        // A new bootstrap and Stage restore the saved profile; Chinese at the minimum size without clipping.
        var restartedExecutor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var restarted = new ApplicationBootstrap(gemini.environment(root))) {
            launch(restarted, restartedExecutor);
            assertTrue(label("needs-saved").contains("200-600 mm"), label("needs-saved"));
            action(() -> { restarted.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); });
            Thread.sleep(200);
            action(() -> { workspace.applyCss(); workspace.layout(); });
            assertTrue(label("needs-saved").contains("来源"), label("needs-saved"));
            assertTrue(fx(() -> { var node = pane.lookup("#needs-saved"); return node.localToScene(node.getBoundsInLocal()).getMaxX() <= workspace.getScene().getWidth(); }), "no horizontal clipping");
            gemini.reply(CapabilityFixtures.PROPOSAL);
            click("needs-analyze");
            snapshot("f06-profile-review-zh-narrow");
        } finally { action(() -> { if (stage != null) stage.close(); }); restartedExecutor.shutdownNow(); restartedExecutor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
