package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.matching.GearFixtures;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX F07 controls and worker callbacks against a loopback Gemini fake; renders the shared layout. */
class GearMatchGuiTest {
    @TempDir Path root;
    GearMatchPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F07 operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); ready(); }
    void text(String id, String value) throws Exception { action(() -> ((TextField) pane.lookup("#" + id)).setText(value)); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    @SuppressWarnings("unchecked")
    void need(String capability, String min, String max, String priority, boolean mandatory) throws Exception {
        action(() -> ((ComboBox<String>) pane.lookup("#match-need-capability")).setValue(capability));
        text("match-need-minimum", min); text("match-need-maximum", max); text("match-need-priority", priority);
        action(() -> ((CheckBox) pane.lookup("#match-need-mandatory")).setSelected(mandatory));
        click("match-need-add");
    }
    void launch(ApplicationBootstrap app, ExecutorService executor) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        action(() -> {
            workspace = new PhotoPilotWorkspace(app.localization());
            pane = new GearMatchPane(app.facade(), executor, app.localization()); workspace.connectMatching(pane);
            stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            workspace.selectMatching(); workspace.applyCss(); workspace.layout();
        }); ready();
    }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }

    @Test void guiConfirmsProfileMatchesWithoutBlockingSelectsAndRestoresTheLoadout() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var gear = new GearFixtures.Airshow();
        UUID id;
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            for (var item : gear.items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            id = app.facade().sessions().create("Lakeshore airshow", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();
            assertTrue(app.facade().planning().confirm(id, GearFixtures.airshowRequirement().asDraft()).isSuccess());
            launch(app, executor);
            assertTrue(label("match-profile").contains("no confirmed capability profile"));
            click("match-profile-edit");
            need("focalLength", "100", "600", "1", true); need("cameraCount", "2", "2", "2", true); need("burstRate", "10", "", "2", true);
            need("lensCount", "", "2", "3", true); need("totalWeight", "", "6000", "4", false);
            need("focalLength", "", "", "1", true);
            assertTrue(fx(() -> pane.feedbackText().contains("needs a minimum or maximum")), "invalid need is rejected with its reason");
            assertTrue(app.facade().planning().profile(id).hasError("needs.error.none"), "drafts are not saved");
            assertTrue(fx(() -> pane.dirty()));
            snapshot("f07-profile-wide");
            click("match-profile-confirm");
            assertTrue(fx(() -> pane.feedbackText().contains("Capability profile confirmed and saved")), () -> "feedback: " + pane.feedbackText());
            assertEquals(5, app.facade().planning().profile(id).value().orElseThrow().needs().size());
            assertTrue(label("match-profile").contains("Focal length: 100-600 mm"));

            gemini.status(503, "");
            // Same FX pulse as the click: matching runs off the FX thread with controls disabled and progress shown.
            assertTrue(fx(() -> { ((Button) pane.lookup("#match-run")).fire();
                return pane.busy() && pane.feedbackText().contains("Matching your gear") && pane.lookup("#match-run").getParent().getParent().getParent().isDisabled(); }));
            ready();
            var report = fx(() -> pane.report());
            assertNotNull(report); assertTrue(report.recommendations().getFirst().fullySuitable());
            assertTrue(label("match-summary").contains("AI ranking unavailable"));
            assertTrue(label("match-candidate-1").contains("verified by deterministic rules"));
            assertTrue(label("match-rejected").contains("Excluded (incompatible)"));
            snapshot("f07-results-wide");
            click("match-select-1");
            assertTrue(label("match-confirmation").contains("Save this loadout to session Lakeshore airshow?"));
            click("match-cancel");
            assertTrue(app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().isEmpty(), "cancel never saves");
            click("match-select-1"); click("match-confirm");
            assertTrue(fx(() -> pane.feedbackText().contains("Loadout selected and saved")), () -> "feedback: " + pane.feedbackText());
            assertEquals(report.recommendations().getFirst().loadout().id(), app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().orElseThrow().id());
            ready();
            assertTrue(label("match-saved").contains("Selected loadout (full match)"));
            action(() -> stage.close());
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        // A new bootstrap and Stage restore the selection from storage and re-check it; Chinese at minimum size.
        var restartedExecutor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var restarted = new ApplicationBootstrap(gemini.environment(root))) {
            launch(restarted, restartedExecutor); ready();
            assertTrue(label("match-saved").contains("Rechecked now: still owned, unchanged and compatible."));
            action(() -> { restarted.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            gemini.status(503, "");
            click("match-run");
            assertTrue(label("match-candidate-1").contains("确定性规则"));
            snapshot("f07-results-narrow-zh");
        } finally { action(() -> { if (stage != null) stage.close(); }); restartedExecutor.shutdownNow(); restartedExecutor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
