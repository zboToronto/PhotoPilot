package com.photopilot.ui.gui;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.model.PlaceCandidate;
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

/** Actual JavaFX F09 controls and worker callbacks against loopback provider/Gemini fakes; renders the shared layout. */
class EnvironmentGuiTest {
    @TempDir Path root;
    EnvironmentPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F09 operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((Button) pane.lookup("#" + id)).fire()); ready(); }
    void text(String id, String value) throws Exception { action(() -> ((TextField) pane.lookup("#" + id)).setText(value)); }
    String field(String id) throws Exception { return fx(() -> ((TextField) pane.lookup("#" + id)).getText()); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }

    @Test void guiReviewsLocationAssessesWithoutBlockingAndSeparatesFactsFromAi() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var day = EnvironmentFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = app.facade().sessions().create("Toronto airshow", day).value().orElseThrow().id();
            assertTrue(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()).isSuccess());
            var store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = Files.readAllBytes(store);
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane = new EnvironmentPane(app.facade(), executor, app.localization()); workspace.connectEnvironment(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                workspace.selectEnvironment(); workspace.applyCss(); workspace.layout();
            }); ready();
            assertTrue(label("env-context").contains("Subject: Aviation / Airshow photography"));
            assertEquals(day.toString(), field("env-date"), "prefilled from the confirmed requirement");
            assertEquals("", field("env-latitude"), "no coordinates are invented");
            assertTrue(fx(() -> pane.lookup("#env-activity").isDisabled()), "F09-only activity is not needed with a confirmed requirement");
            assertTrue(providers.requests.isEmpty(), "opening F09 contacts no provider and no device");

            text("env-location", "Toronto"); text("env-from", "13:00"); text("env-until", "19:00");
            click("env-assess");
            assertTrue(fx(() -> pane.feedbackText().contains("Latitude and longitude are required")), () -> pane.feedbackText());
            assertEquals("Toronto", field("env-location"), "input kept after a validation failure");
            click("env-find");
            assertTrue(fx(() -> pane.feedbackText().contains("3 matching places found")));
            @SuppressWarnings("unchecked") var places = fx(() -> (ComboBox<PlaceCandidate>) pane.lookup("#env-places"));
            assertEquals(3, fx(() -> places.getItems().size()));
            assertEquals("", field("env-latitude"), "an ambiguous name is not resolved until the user chooses");
            action(() -> places.setValue(places.getItems().getFirst()));
            assertEquals("43.70643", field("env-latitude"));
            assertTrue(fx(() -> pane.feedbackText().contains("approximate")));
            click("env-device");
            assertTrue(fx(() -> pane.feedbackText().contains("not supported on this computer")), "no consent prompt for an unsupported device");

            var values = EnvironmentFixtures.form(day);
            for (var entry : values.entrySet()) text("env-" + entry.getKey(), entry.getValue());
            assertTrue(fx(() -> pane.dirty()));
            gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
            // Same FX pulse as the click: the assessment runs off the FX thread with the inputs disabled.
            assertTrue(fx(() -> { ((Button) pane.lookup("#env-assess")).fire();
                return pane.busy() && pane.feedbackText().contains("Retrieving weather and sun data") && pane.lookup("#env-location").getParent().getParent().getParent().isDisabled(); }));
            ready();
            var assessment = fx(() -> pane.assessment());
            assertNotNull(assessment);
            assertEquals(2, assessment.windows().size());
            assertTrue(fx(() -> pane.feedbackText().contains("2 validated shooting window(s)")));
            assertTrue(label("env-summary").contains("Local time zone: America/Toronto"));
            assertTrue(label("env-window-1").contains("Suggested arrival") && label("env-window-1").contains("Sunset"), () -> "cited evidence is named readably");
            assertTrue(fx(() -> pane.lookupAll(".section-title").stream().anyMatch(n -> n instanceof Label h && h.getText().equals("Window 1: Afternoon flying display"))));
            assertTrue(label("env-window-2").contains("Light: golden hour") && label("env-window-2").contains("Evening golden hour"));
            var shared = EnvironmentPresentation.windows(assessment, app.localization()).getFirst();
            assertEquals(String.join("\n", shared.subList(1, shared.size())), label("env-window-1"), "GUI cards use the shared presentation (same text as the CLI)");
            assertTrue(label("env-evidence").contains("Sunset:") && label("env-evidence").contains("cloud cover 40%"));
            assertTrue(label("env-phases").contains("Evening golden hour") && label("env-phases").contains("estimate"));
            assertTrue(label("env-ai").contains("AI interpretation of the verified data"));
            assertTrue(label("env-rejected").contains("After-dark static display") && label("env-rejected").contains("Outside your availability"));
            assertTrue(label("env-limitations").contains("Travel time"));
            assertArrayEquals(before, Files.readAllBytes(store), "assessments are never saved");
            snapshot("f09-results-wide");

            // Reassess after changing the time; Gemini outage keeps the facts. Chinese at minimum size.
            action(() -> { app.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            text("env-lead", "10");
            gemini.status(503, "");
            click("env-assess");
            String evidence = label("env-evidence");
            assertTrue(evidence.contains("日落"), evidence);
            assertTrue(label("env-ai").contains("没有 AI 解读"));
            assertTrue(label("env-limitations").contains("AI 解读不可用"));
            snapshot("f09-results-narrow-zh");
            action(() -> app.localization().useLanguage("en"));
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
