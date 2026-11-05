package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.review.ReviewFixtures;
import com.photopilot.service.ExifAnalysis;
import java.nio.file.Path;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX F11 controls and worker callbacks against the loopback Gemini fake; renders the shared layout. */
class ReviewGuiTest {
    @TempDir Path root;
    @TempDir Path photos;
    ReviewPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F11 operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((ButtonBase) pane.lookup("#" + id)).fire()); ready(); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    /** Shown only when the node and every ancestor up to the pane are visible. */
    boolean visible(String id) throws Exception {
        return fx(() -> {
            for (javafx.scene.Node node = pane.lookup("#" + id); node != null && node != pane; node = node.getParent()) if (!node.isVisible()) return false;
            return pane.lookup("#" + id) != null;
        });
    }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }

    @Test void guiImportsAnalysesWithoutBlockingReviewsSavesAndReopens() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = ReviewFixtures.completed(app, gemini, new PlanFixtures.Gear());
            var files = ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id));
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane = new ReviewPane(app.facade(), executor, app.localization()); workspace.connectReview(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                workspace.selectReview(); workspace.applyCss(); workspace.layout();
            }); ready();
            assertTrue(label("review-inputs").contains("Toronto airshow plan"), label("review-inputs"));
            assertTrue(label("review-inputs").contains("No EXIF statistics saved yet."));
            assertTrue(fx(() -> pane.lookup("#review-generate").isDisabled()), "UC13 needs saved statistics first");
            assertTrue(visible("review-analyze"), "a completed session accepts imports");
            click("review-analyze");
            assertTrue(fx(() -> pane.feedbackText().contains("Select at least one JPEG")));

            action(() -> files.forEach(pane::select));
            assertTrue(label("review-selected").contains("air-01.jpg") && !label("review-selected").contains(photos.toString()), "file names only");
            assertTrue(fx(() -> pane.dirty()), "a selection is protected from silent discard");
            int sent = gemini.requests.size();
            // Same FX pulse as the click: extraction runs off the FX thread with the controls disabled and progress shown.
            assertTrue(fx(() -> { ((Button) pane.lookup("#review-analyze")).fire();
                return pane.busy() && pane.feedbackText().contains("calculating statistics locally"); }));
            ready();
            assertEquals(sent, gemini.requests.size(), "analysis never calls Gemini");
            assertEquals(ExifAnalysis.Status.SAVED, fx(() -> pane.analysis().status()));
            assertTrue(label("review-files").contains("air-07-exported.jpg [JPEG]: no EXIF metadata"), label("review-files"));
            assertTrue(label("review-stats-cameras").contains("Sony A1: 4 (66.7%)"), label("review-stats-cameras"));
            assertTrue(label("review-stats-shutter").contains("Fastest 1/3200 s, slowest 1/800 s"), label("review-stats-shutter"));
            assertTrue(label("review-limits").contains("Lens is missing for 2 of 7 photos"), label("review-limits"));
            assertTrue(label("review-comparison").contains("Shutter speed: planned 1/1600-1/3200 s"), label("review-comparison"));
            assertFalse(fx(() -> pane.lookup("#review-generate").isDisabled()));
            assertFalse(fx(() -> pane.dirty()), "the saved selection is cleared");
            snapshot("f11-statistics-wide");

            gemini.replyWith(body -> ReviewFixtures.review(body, false));
            click("review-generate");
            assertNotNull(fx(() -> pane.draft()));
            assertTrue(label("review-draft-summary").startsWith("Of the 7 photos imported"), label("review-draft-summary"));
            assertTrue(label("review-draft-differences").contains("Recorded camera SONY ILCE-7M4"), label("review-draft-differences"));
            assertTrue(label("review-notes").contains("Related earlier sessions used as context: 0"), label("review-notes"));
            assertEquals("COMPLETED", app.facade().sessions().open(id).value().orElseThrow().status(), "a displayed draft is not saved");
            snapshot("f11-draft-wide");
            click("review-discard");
            assertTrue(fx(() -> pane.feedbackText().contains("Draft review discarded")));
            assertTrue(app.facade().reviews().reviews(id).value().orElseThrow().isEmpty());

            gemini.replyWith(body -> ReviewFixtures.review(body, false));
            click("review-generate"); click("review-save");
            assertTrue(fx(() -> pane.feedbackText().contains("Review saved")), () -> "feedback: " + pane.feedbackText());
            ready();
            assertEquals("REVIEWED", app.facade().sessions().open(id).value().orElseThrow().status());
            assertTrue(label("review-saved-summary").startsWith("Of the 7 photos imported"), label("review-saved-summary"));
            assertFalse(visible("review-analyze"), "a reviewed session keeps its evidence");
            action(() -> { app.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            assertTrue(label("review-inputs").contains("已复盘"), label("review-inputs"));
            snapshot("f11-saved-narrow-zh");
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
