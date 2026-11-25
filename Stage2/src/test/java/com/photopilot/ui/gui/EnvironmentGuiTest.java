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
            assertTrue(fx(() -> pane.feedbackText().contains("3 matching places found")));
            assertTrue(gemini.requests.isEmpty(), "database matches skip Gemini location resolution");
            assertEquals("Toronto", field("env-location"));
            click("env-find");
            @SuppressWarnings("unchecked") var places = fx(() -> (ComboBox<PlaceCandidate>) pane.lookup("#env-places"));
            assertEquals(3, fx(() -> places.getItems().size()));
            assertEquals("", field("env-latitude"), "an ambiguous name is not resolved until the user chooses");
            action(() -> places.setValue(places.getItems().getFirst()));
            assertEquals("", field("env-latitude"), "preview awaits confirmation");
            click("env-confirm");
            assertEquals("43.70643", field("env-latitude"));
            assertTrue(label("env-location-status").contains("Open-Meteo"));
            assertNull(fx(() -> pane.lookup("#env-device")), "an unsupported device never prompts for location");
            assertFalse(fx(() -> ((TitledPane) pane.lookup("#env-advanced")).isExpanded()));

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

    @Test void fallbackReviewRecoveryAndLateLookupUseOnlyTheCurrentSessionAndDestination() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var day = EnvironmentFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var first = app.facade().sessions().create("First", day).value().orElseThrow();
            var second = app.facade().sessions().create("Second", day).value().orElseThrow();
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane = new EnvironmentPane(app.facade(), executor, app.localization()); workspace.connectEnvironment(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1060, 760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
                ThemeManager.get().style(scene.getStylesheets()); stage.setScene(scene); stage.show();
                workspace.selectEnvironment(); workspace.applyCss(); workspace.layout();
            }); ready();
            @SuppressWarnings("unchecked") var sessions = fx(() -> (ComboBox<com.photopilot.model.PhotographySession>)pane.lookup("#env-session"));
            action(() -> sessions.setValue(first));
            text("env-location", "Harbour Park in Westport"); text("env-from","13:00"); text("env-until","16:00"); text("env-activity","Landscape");
            String park = com.photopilot.environment.HybridLocationTest.resolved(com.photopilot.environment.HybridLocationTest.PARK);
            gemini.reply(park);
            click("env-assess");
            assertTrue(label("env-location-status").contains("geographically unverified"));
            assertTrue(label("env-location-status").contains("43.63"));
            assertEquals("",field("env-latitude")); assertEquals(0,providers.count("/v1/forecast"));
            assertNull(fx(() -> pane.assessment()));
            click("env-change");
            assertEquals(0,providers.count("/v1/forecast"),"rejecting an estimate cannot start an assessment");
            gemini.reply(park); gemini.status(503,"");
            click("env-assess"); click("env-confirm");
            assertTrue(label("env-summary").contains("AI-estimated shooting location"));
            assertTrue(label("env-limitations").contains("exact site"));
            assertEquals(43.62753871, fx(() -> pane.assessment().request().orElseThrow().place().latitude().orElseThrow()));
            assertTrue(app.facade().planning().environmentFor(first.id()).isPresent());
            text("env-location","Unknown destination");
            assertEquals("",field("env-longitude")); assertNull(fx(() -> pane.assessment()));
            assertTrue(app.facade().planning().environmentFor(first.id()).isEmpty());
            gemini.reply("{\"status\":\"UNRESOLVED\",\"candidates\":[]}");
            click("env-find"); assertTrue(label("env-location-status").contains("manually"));
            action(() -> ((TitledPane)pane.lookup("#env-advanced")).setExpanded(true));
            text("env-latitude","95"); text("env-longitude","-79.4"); click("env-manual");
            assertTrue(fx(() -> pane.feedbackText().contains("Latitude must be between")));
            text("env-latitude","43.6"); click("env-manual"); gemini.status(503,""); click("env-assess");
            assertTrue(label("env-summary").contains("User-provided coordinates"));
            var previousMode = ThemeManager.get().mode();
            try {
                action(() -> { ThemeManager.get().select(Theme.Mode.DARK); app.localization().useLanguage("zh-CN"); });
                assertTrue(label("env-summary").contains("用户提供的坐标")); snapshot("f09-manual-dark-zh");
            } finally { action(() -> { ThemeManager.get().select(previousMode); app.localization().useLanguage("en"); }); }
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            providers.geocode = q -> {
                started.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return FakeEnvironmentProviders.defaultGeocode(q);
            };
            text("env-location", "Toronto");
            action(() -> ((Button)pane.lookup("#env-find")).fire());
            assertTrue(started.await(4,TimeUnit.SECONDS));
            action(() -> { sessions.setValue(second); ((TextField)pane.lookup("#env-location")).setText("New destination"); });
            release.countDown(); ready();
            assertEquals("New destination",field("env-location")); assertEquals("",field("env-latitude"));
            assertTrue(fx(() -> ((ComboBox<?>)pane.lookup("#env-places")).getItems().isEmpty()));
            assertTrue(app.facade().planning().environmentFor(second.id()).isEmpty());
        } finally { action(() -> { if (pane != null) pane.dispose(); if (stage != null) stage.close(); }); executor.shutdownNow(); }
    }
}
