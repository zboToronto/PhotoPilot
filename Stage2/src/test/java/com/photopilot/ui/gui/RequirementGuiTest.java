package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
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

/** Actual JavaFX F03 controls and worker callbacks against a loopback Gemini fake; renders shared layouts. */
class RequirementGuiTest {
    @TempDir Path root;
    RequirementPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("Requirement operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); }
    void field(String key, String value) throws Exception { action(() -> ((TextField) pane.lookup("#requirement-field-" + key)).setText(value)); }
    String field(String key) throws Exception { return fx(() -> ((TextField) pane.lookup("#requirement-field-" + key)).getText()); }
    boolean visible(String id) throws Exception { return fx(() -> { var node = pane.lookup("#" + id); return node != null && node.isVisible() && node.getParent().isVisible(); }); }
    void launch(ApplicationBootstrap app, ExecutorService executor) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        action(() -> {
            workspace = new PhotoPilotWorkspace(app.localization());
            workspace.connectSessions(new SessionPane(app.facade(), executor, app.localization()));
            pane = new RequirementPane(app.facade(), executor, app.localization()); workspace.connectRequirements(pane);
            stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            workspace.selectRequirements(); workspace.applyCss(); workspace.layout();
        }); ready();
    }
    void snapshot(String name) throws Exception {
        fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; });
    }

    @Test void guiAnalyzesReviewsEditsConfirmsAndRestoresWithoutBlockingTheFxThread() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var id = app.facade().sessions().create("Airshow", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();
            launch(app, executor);
            assertEquals(id, fx(() -> ((ComboBox<com.photopilot.model.PhotographySession>) pane.lookup("#requirement-session")).getValue().id()));
            assertTrue(fx(() -> pane.lookup("#requirement-saved") == null), "no saved requirement yet");
            click("requirement-analyze"); assertTrue(fx(() -> pane.feedbackText().contains("Describe the planned shoot")));
            gemini.reply(FakeGemini.AIRSHOW);
            action(() -> ((TextArea) pane.lookup("#requirement-request")).setText(FakeGemini.AIRSHOW_REQUEST));
            // Same FX pulse as the click: the request is already off-thread, controls are disabled and progress is shown.
            assertTrue(fx(() -> { ((Button) pane.lookup("#requirement-analyze")).fire();
                return pane.busy() && pane.feedbackText().contains("Analyzing") && pane.lookup("#requirement-request").getParent().getParent().isDisabled(); }));
            ready();
            assertEquals("2", field("cameraCount")); assertEquals("3h", field("duration")); assertEquals("", field("location"));
            assertEquals("avoid", fx(() -> ((ComboBox<?>) pane.lookup("#requirement-field-lensChanges")).getValue()));
            assertTrue(visible("requirement-answers"), "clarification questions are shown");
            assertTrue(app.facade().sessions().open(id).value().orElseThrow().requirements().isEmpty(), "analysis never saves");
            snapshot("f03-review-wide");
            field("cameraCount", "0"); click("requirement-confirm");
            assertTrue(fx(() -> pane.feedbackText().contains("whole number from 1 to 10")));
            assertTrue(app.facade().sessions().open(id).value().orElseThrow().requirements().isEmpty());
            field("cameraCount", "2"); field("location", "Lakeshore airfield"); field("date", "2026-11-14");
            click("requirement-confirm"); ready();
            assertTrue(fx(() -> pane.feedbackText().contains("confirmed and saved")));
            assertNull(fx(() -> pane.draft())); assertFalse(fx(() -> pane.dirty()));
            var saved = app.facade().planning().requirement(id).value().orElseThrow();
            assertEquals("Lakeshore airfield", saved.location().orElseThrow().label()); assertEquals(Optional.of(false), saved.lensChangesAllowed());
            assertTrue(fx(() -> ((Label) pane.lookup("#requirement-saved")).getText().contains("Lakeshore airfield")));
            action(() -> stage.close());
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        // A new bootstrap and Stage restore the confirmed requirement from storage; editing works on a copy.
        var restartedExecutor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var restarted = new ApplicationBootstrap(gemini.environment(root))) {
            launch(restarted, restartedExecutor);
            assertTrue(fx(() -> ((Label) pane.lookup("#requirement-saved")).getText().contains("Lakeshore airfield")));
            click("requirement-edit"); field("subject", "Helicopters");
            click("requirement-cancel"); click("requirement-keep"); assertEquals("Helicopters", field("subject"));
            click("requirement-cancel"); click("requirement-discard");
            assertTrue(fx(() -> pane.feedbackText().contains("Draft discarded")));
            assertEquals("Aircraft at an airshow", restarted.facade().sessions().search("").value().orElseThrow().getFirst().requirements().orElseThrow().subject());
            action(() -> { restarted.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            click("requirement-edit"); snapshot("f03-review-narrow-zh");
        } finally { action(() -> { if (stage != null) stage.close(); }); restartedExecutor.shutdownNow(); restartedExecutor.awaitTermination(10, TimeUnit.SECONDS); }
    }

    @Test void aiFailureKeepsTheRequestAndManualEntryConfirms() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var id = app.facade().sessions().create("Night", LocalDate.of(2026, 11, 20)).value().orElseThrow().id();
            launch(app, executor);
            gemini.status(503, "");
            action(() -> ((TextArea) pane.lookup("#requirement-request")).setText("Milky Way over the lake"));
            click("requirement-analyze"); ready();
            assertTrue(fx(() -> pane.feedbackText().contains("AI parsing failed")));
            assertEquals("Milky Way over the lake", fx(() -> ((TextArea) pane.lookup("#requirement-request")).getText()));
            assertTrue(fx(() -> pane.dirty()), "kept request counts as unsaved input");
            click("requirement-manual"); field("subject", "Milky Way"); field("duration", "2h");
            click("requirement-confirm"); ready();
            var saved = app.facade().planning().requirement(id).value().orElseThrow();
            assertEquals("Milky Way over the lake", saved.originalText()); assertEquals("Milky Way", saved.subject());
            snapshot("f03-saved-wide");
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
