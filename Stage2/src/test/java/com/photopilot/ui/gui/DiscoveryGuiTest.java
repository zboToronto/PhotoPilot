package com.photopilot.ui.gui;

import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.discovery.DiscoveryFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual JavaFX F02 controls and worker callbacks against loopback provider/Gemini fakes: no request on opening,
 * background generation, shared presentation text, dismiss/save, and the hand-off into the real F03 pane.
 */
class DiscoveryGuiTest {
    @TempDir Path root;
    DiscoveryPane pane; RequirementPane requirements; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F02 operation timed out"); } while (fx(() -> pane.busy() || requirements.busy()));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); ready(); }
    void text(String id, String value) throws Exception { action(() -> ((TextField) pane.lookup("#" + id)).setText(value)); }
    String field(String id) throws Exception { return fx(() -> ((TextField) pane.lookup("#" + id)).getText()); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }

    @Test void guiFindsIdeasWithoutBlockingSeparatesStatusesAndHandsASelectedIdeaToF03() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            for (var item : new GearFixtures.Airshow().items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            byte[] gear = Files.readAllBytes(app.storageScope().inventoryFile());
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                requirements = new RequirementPane(app.facade(), executor, app.localization()); workspace.connectRequirements(requirements);
                pane = new DiscoveryPane(app.facade(), executor, app.localization(), (id, draft) -> { workspace.selectRequirements(); requirements.review(id, draft); });
                workspace.connectDiscovery(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                workspace.selectDiscovery(); workspace.applyCss(); workspace.layout();
            }); ready();
            assertTrue(label("discover-gear").contains("11 item(s) recorded"), () -> "the read-only F05 inventory is shown for review");
            assertTrue(providers.requests.isEmpty() && gemini.requests.isEmpty(), "opening F02 contacts no provider, device or Gemini");
            assertTrue(fx(() -> ((CheckBox) pane.lookup("#discover-new-session")).isSelected()), "no session exists, so a new one would be created");

            for (var entry : DiscoveryFixtures.form(day).entrySet()) if (!entry.getKey().equals("history")) text("discover-" + entry.getKey(), entry.getValue());
            text("discover-distance", "far");
            click("discover-generate");
            assertTrue(fx(() -> pane.feedbackText().contains("maximum travel distance must be")), () -> pane.feedbackText());
            assertEquals(DiscoveryFixtures.PLACE, field("discover-location"), "inputs are kept after a validation failure");
            assertTrue(gemini.requests.isEmpty());
            text("discover-distance", "40");
            click("discover-device");
            assertTrue(fx(() -> pane.feedbackText().contains("not supported on this computer")), "no consent prompt for an unsupported device");
            assertTrue(fx(() -> pane.dirty()));

            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            // Same FX pulse as the click: the request runs off the FX thread with the inputs disabled.
            assertTrue(fx(() -> { ((Button) pane.lookup("#discover-generate")).fire();
                return pane.busy() && pane.feedbackText().contains("asking Gemini") && pane.lookup("#discover-location").getParent().getParent().getParent().isDisabled(); }));
            ready();
            var result = fx(() -> pane.result());
            assertEquals(2, result.feasible().size());
            assertTrue(fx(() -> pane.feedbackText().contains("2 feasible and 1 unverified")), () -> pane.feedbackText());
            var first = result.ideas().getFirst();
            assertEquals(String.join("\n", DiscoveryPresentation.idea(first, app.localization())), label("discover-idea-1"), "GUI cards use the shared presentation (same text as the CLI)");
            assertTrue(label("discover-idea-1").contains("Straight-line distance") && label("discover-idea-1").contains("Light and weather: Feasible"));
            assertTrue(label("discover-context").contains("verified by the weather provider"));
            assertTrue(label("discover-summary").contains("2 feasible, 1 unverified, 3 not suitable"));
            assertNotNull(fx(() -> pane.lookup("#discover-continue-3")), "unverified ideas can continue with their limitations");
            assertNull(fx(() -> pane.lookup("#discover-continue-4")), "not-suitable ideas are never offered for planning");
            assertNull(fx(() -> pane.lookup("#discover-save-5")));
            assertTrue(label("discover-ai").contains("advice, not verified facts"));
            snapshot("f02-results-wide");

            click("discover-dismiss-3");
            assertNull(fx(() -> pane.lookup("#discover-idea-3")), "dismissed idea removed from the results");
            assertTrue(fx(() -> pane.feedbackText().contains("was dismissed")));

            click("discover-save-1");
            assertTrue(fx(() -> pane.feedbackText().contains("was saved in")), () -> pane.feedbackText());
            var sessions = app.facade().sessions().search("").value().orElseThrow();
            assertEquals(1, sessions.size());
            assertEquals(first.id(), sessions.getFirst().savedIdeas().getFirst().id());
            ready();
            assertTrue(label("discover-saved").contains("Aircraft departures at the island airport"));
            assertArrayEquals(gear, Files.readAllBytes(app.storageScope().inventoryFile()), "the inventory is never written");

            // Continue in Requirements: the real F03 pane opens an unconfirmed draft for the chosen session.
            click("discover-continue-2");
            ready();
            var draft = fx(() -> requirements.draft());
            String f03Feedback = fx(() -> requirements.feedbackText());
            assertNotNull(draft, f03Feedback);
            assertFalse(draft.confirmed());
            assertEquals("Skyline reflections along the waterfront", draft.subject());
            assertTrue(app.facade().sessions().open(sessions.getFirst().id()).value().orElseThrow().requirements().isEmpty(), "nothing confirmed until F03 confirmation");
            assertTrue(fx(() -> requirements.isVisible()), "the F03 page is shown");

            // Gemini outage: inputs kept, no ideas invented. Chinese at minimum size.
            action(() -> { workspace.selectDiscovery(); app.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            ready();
            gemini.status(503, "");
            click("discover-generate");
            assertTrue(fx(() -> pane.feedbackText().contains("没有生成任何建议")), () -> pane.feedbackText());
            assertEquals("40", field("discover-distance"));
            assertNull(fx(() -> pane.result()));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            click("discover-generate");
            assertTrue(label("discover-idea-1").contains("直线距离"));
            snapshot("f02-results-narrow-zh");
            action(() -> app.localization().useLanguage("en"));
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
