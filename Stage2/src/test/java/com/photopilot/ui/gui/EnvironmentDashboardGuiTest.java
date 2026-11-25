package com.photopilot.ui.gui;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.model.EnvironmentAnalysis;
import com.photopilot.model.EnvironmentAnalysis.*;
import com.photopilot.model.PlaceCandidate;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Environment Assessment dashboard in a real JavaFX window against loopback provider and Gemini fakes: location
 * card states, the styled advanced options, pre-flight conflicts, the facts shown while Gemini is pending, every
 * internal view with its charts drawn from the shared analysis, hour and metric selection, the AI-only retry without
 * provider requests, copy and record. Saves pictures of each view under target/ for visual review.
 */
class EnvironmentDashboardGuiTest {
    @TempDir Path root;
    EnvironmentPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F09 operation timed out"); } while (fx(() -> pane.busy()));
        action(() -> { workspace.applyCss(); workspace.layout(); });
    }
    void click(String id) throws Exception { action(() -> ((ButtonBase) pane.lookup("#" + id)).fire()); ready(); }
    void text(String id, String value) throws Exception { action(() -> ((TextField) pane.lookup("#" + id)).setText(value)); }
    String label(String id) throws Exception { return fx(() -> ((Labeled) pane.lookup("#" + id)).getText()); }
    boolean shown(String id) throws Exception {
        return fx(() -> { Node node = pane.lookup("#" + id); while (node != null && node != pane) { if (!node.isVisible()) return false; node = node.getParent(); } return true; });
    }
    void tab(EnvironmentDashboard.View view) throws Exception { click("env-tab-" + view.name()); }
    void picture(String name) throws Exception {
        action(() -> { workspace.applyCss(); workspace.layout(); });
        fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; });
        fx(() -> {
            var parameters = new SnapshotParameters(); parameters.setFill(Color.web(ThemeManager.get().theme().tokens().get("-pp-backdrop-base")));
            CalculatorGuiTest.write(pane.snapshot(parameters, null), name + "-page"); return true;
        });
    }
    void top() throws Exception { fx(() -> { SessionPane.reveal(pane, pane.dashboard()); return true; }); Thread.sleep(150); }

    @Test void dashboardViewsChartsRetryAndInputsWorkOnOneSnapshot() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var day = EnvironmentFixtures.day();
        var previousMode = ThemeManager.get().mode();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var l = app.localization();
            var id = app.facade().sessions().create("Toronto airshow", day).value().orElseThrow().id();
            assertTrue(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()).isSuccess());
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                ThemeManager.get().select(Theme.Mode.LIGHT);
                workspace = new PhotoPilotWorkspace(l);
                pane = new EnvironmentPane(app.facade(), executor, l); workspace.connectEnvironment(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1440, 900);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
                ThemeManager.get().style(scene.getStylesheets()); stage.setScene(scene); stage.show();
                workspace.selectEnvironment(); workspace.applyCss(); workspace.layout();
            }); ready();

            // Inputs: no location card before a search, advanced options closed, a date conflict shown before any request.
            assertFalse(shown("env-location-card")); assertFalse(fx(() -> ((TitledPane) pane.lookup("#env-advanced")).isExpanded()));
            assertTrue(fx(() -> pane.lookup("#env-advanced").getStyleClass().contains("env-disclosure")), "calculator-style disclosure, not the grey title bar");
            text("env-date", day.plusDays(2).toString());
            assertTrue(shown("env-conflicts"));
            assertTrue(label("env-conflict-lines").contains("The confirmed requirement date is " + day + ", but this assessment uses " + day.plusDays(2)));
            assertTrue(providers.requests.isEmpty(), "the conflict is found before any request");
            click("env-restore");
            assertFalse(shown("env-conflicts")); assertEquals(day.toString(), fx(() -> ((TextField) pane.lookup("#env-date")).getText()));

            // Location: candidates, a structured candidate list, then the compact confirmed card.
            text("env-location", "Toronto"); click("env-find");
            assertTrue(shown("env-location-card") && shown("env-places") && shown("env-confirm"));
            @SuppressWarnings("unchecked") var places = fx(() -> (ComboBox<PlaceCandidate>) pane.lookup("#env-places"));
            action(places::show); Thread.sleep(250);
            var names = fx(() -> ((ListView<?>) ((DropdownSkin<?>) places.getSkin()).getPopupContent()).lookupAll(".env-candidate-name").stream()
                .map(n -> ((Label) n).getText()).toList());
            var details = fx(() -> ((ListView<?>) ((DropdownSkin<?>) places.getSkin()).getPopupContent()).lookupAll(".env-candidate-detail").stream()
                .map(n -> ((Label) n).getText()).toList());
            assertTrue(names.contains("Toronto") && details.stream().anyMatch(d -> d.startsWith("Ontario, Canada · Open-Meteo geographic database")), details.toString());
            fx(() -> { CalculatorGuiTest.write(((DropdownSkin<?>) places.getSkin()).getPopupContent().snapshot(null, null), "f09-candidates"); return true; });
            action(places::hide);
            action(() -> places.setValue(places.getItems().getFirst()));
            click("env-confirm");
            assertFalse(shown("env-places")); assertFalse(shown("env-confirm")); assertTrue(shown("env-change"));
            assertEquals("Change location", label("env-change"));
            assertEquals("Toronto, Ontario, Canada", label("env-location-name"));
            assertTrue(label("env-location-status").startsWith("Location confirmed · Open-Meteo geographic database"));
            assertEquals("Time zone: America/Toronto (from the place lookup)", label("env-location-zone"));
            action(() -> ((TitledPane) pane.lookup("#env-advanced")).setExpanded(true));
            picture("f09-input-confirmed");
            action(() -> ((TitledPane) pane.lookup("#env-advanced")).setExpanded(false));
            for (var entry : Map.of("from", EnvironmentFixtures.clock(EnvironmentFixtures.from(day)), "until", EnvironmentFixtures.clock(EnvironmentFixtures.until(day)), "lead", "30").entrySet())
                text("env-" + entry.getKey(), entry.getValue());

            // Gemini is slow: the facts appear while it is pending and the views can be read; then it fails.
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            gemini.replyWith(body -> { started.countDown(); try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return "not json"; });
            action(() -> ((Button) pane.lookup("#env-assess")).fire());
            assertTrue(started.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!fx(() -> pane.dashboard().provisional() && pane.dashboard().analysis() != null)) { Thread.sleep(30); if (System.nanoTime() > deadline) fail("facts not shown"); }
            assertTrue(fx(() -> pane.busy()));
            assertTrue(label("env-stage-AI").contains("Pending") && label("env-overall").contains("AI analysis in progress"));
            assertTrue(fx(() -> pane.lookup("#env-retry-ai").isDisabled() || !pane.lookup("#env-retry-ai").isVisible()), "no retry while pending");
            action(() -> ((ToggleButton) pane.lookup("#env-tab-LIGHT")).fire());
            assertTrue(shown("env-view-LIGHT"), "the factual views can be read while Gemini is pending");
            action(() -> ((ToggleButton) pane.lookup("#env-tab-OVERVIEW")).fire());
            release.countDown(); ready();

            var assessment = fx(() -> pane.assessment());
            var analysis = fx(() -> pane.dashboard().analysis());
            assertNotNull(assessment); assertSame(assessment, analysis.assessment());
            assertEquals(AiStatus.INVALID, analysis.ai().status(), "an unusable reply is rejected, never shown as advice");
            assertTrue(fx(() -> pane.inputsCollapsed()) && shown("env-summary-bar") && !shown("env-inputs"));
            assertTrue(label("env-input-summary").startsWith("Toronto, Ontario, Canada · " + day));
            assertTrue(shown("env-view-OVERVIEW") && !shown("env-view-WEATHER"), "the overview is the default view");
            assertEquals("Environmental data available — AI analysis incomplete", label("env-overall"));
            assertTrue(label("env-stage-AI").contains("Failed") && label("env-stage-VALIDATION").contains("Not performed"));
            assertTrue(label("env-stage-WEATHER").startsWith("✓") && label("env-stage-AI").startsWith("✕"), "status in symbols and words");
            for (String metric : List.of("cloudCoverPct", "windSpeedKmh", "visibilityM", "precipitationProbabilityPct"))
                assertEquals(EnvironmentPresentation.windowValue(analysis, metric, l), label("env-metric-" + metric), "same value text as the CLI overview");
            assertEquals(EnvironmentPresentation.windowValue(analysis, "temperatureC", l), label("env-hero-temp"));
            assertEquals("full-day max 20%", label("env-metric-precipitationProbabilityPct-detail"), "daily values are labelled as such");
            assertTrue(shown("env-retry-ai") && !fx(() -> pane.lookup("#env-retry-ai").isDisabled()));
            assertTrue(fx(() -> pane.lookupAll(".env-finding").stream().anyMatch(n -> ((Label) n).getText().startsWith("AI interpretation unavailable"))));
            top(); picture("f09-overview");

            // Weather: hour cards, metric switching and the chart on actual timestamps.
            tab(EnvironmentDashboard.View.WEATHER);
            assertTrue(shown("env-view-WEATHER") && !shown("env-view-OVERVIEW"));
            var dashboard = fx(() -> pane.dashboard());
            assertEquals(analysis.hours().size(), fx(() -> dashboard.hourCards().size()));
            click("env-hour-2");
            assertEquals(2, fx(dashboard::selectedHour)); assertEquals(2, fx(() -> dashboard.trendChart().selected()));
            assertTrue(fx(dashboard::weatherDetail).startsWith(EnvironmentPresentation.clock(analysis.hours().get(2).local(), analysis.date())));
            click("env-trend-cloudCoverPct");
            assertEquals("cloudCoverPct", fx(() -> dashboard.trendChart().metric()));
            assertEquals(analysis.hours().size(), fx(() -> dashboard.trendChart().points().size()));
            var points = fx(() -> dashboard.trendChart().points());
            for (int i = 1; i < points.size(); i++) assertTrue(points.get(i)[0] > points.get(i - 1)[0], "x follows time");
            assertTrue(points.stream().mapToDouble(p -> p[1]).distinct().count() == 1, "equal values, equal heights");
            action(() -> dashboard.trendChart().hover(3));
            assertTrue(fx(() -> dashboard.trendChart().hoverText()).contains("Cloud cover 40%"));
            assertEquals(Set.of(), fx(() -> new HashSet<>(dashboard.trendChart().points().stream().filter(p -> Double.isNaN(p[1])).toList())));
            top(); picture("f09-weather");

            // Sun and light: the curve is exactly the model samples; hovering reads elevation and azimuth.
            tab(EnvironmentDashboard.View.LIGHT);
            assertSame(analysis.solar(), fx(() -> dashboard.solarChart().samples()));
            var sunset = analysis.event(analysis.date(), "sunset").orElseThrow();
            action(() -> dashboard.solarChart().hover(sunset.time()));
            String reading = label("env-solar-readout");
            assertTrue(reading.contains("elevation") && reading.contains("azimuth") && reading.contains("°"), reading);
            assertEquals(3, fx(() -> dashboard.lightTimeline().rows().size()));
            assertEquals(analysis.bands().size(), fx(() -> dashboard.lightTimeline().rows().getFirst().segments().size()));
            assertEquals(EnvironmentPresentation.clock(sunset.time().atZone(analysis.zone()), analysis.date()), label("env-metric-event-sunset"));
            top(); picture("f09-light");

            // Shooting windows: overlap cards and the interval chart from the same analysis.
            tab(EnvironmentDashboard.View.WINDOWS);
            assertEquals(EnvironmentPresentation.minutes(analysis.goldenOverlap(), l), label("env-metric-goldenOverlap"));
            var golden = analysis.relevantOverlaps().stream().filter(o -> o.phase().kind().equals("goldenEvening")).findFirst().orElseThrow();
            assertEquals(EnvironmentPresentation.overlap(golden, analysis, l), label("env-overlap-goldenEvening"));
            action(() -> dashboard.windowTimeline().hover(0, analysis.availability().start().toInstant().plus(Duration.ofMinutes(10))));
            assertTrue(fx(() -> dashboard.windowTimeline().hoverText()).startsWith("Availability ·"));
            assertTrue(label("env-windows").startsWith("No shooting window could be validated"));
            top(); picture("f09-windows");

            // AI analysis: the failure is explained; the retry asks only Gemini and keeps the evidence.
            tab(EnvironmentDashboard.View.AI);
            assertTrue(label("env-ai-tab-status").contains("rejected by validation"));
            long fetched = providers.count("/v1/forecast") + providers.count("/json") + providers.count("/v1/search"); int asked = gemini.requests.size();
            gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
            click("env-retry-ai-tab");
            assertEquals(fetched, providers.count("/v1/forecast") + providers.count("/json") + providers.count("/v1/search"));
            assertEquals(asked + 1, gemini.requests.size());
            var retried = fx(() -> pane.assessment());
            assertNotSame(assessment, retried); assertSame(assessment.context(), retried.context());
            assertTrue(retried.aiInterpreted() && retried.windows().size() == 2);
            assertTrue(shown("env-view-AI") && label("env-ai").contains("Afternoon daylight and the golden hour"));
            assertFalse(shown("env-retry-ai-tab"), "no retry offered after success");
            assertTrue(fx(() -> pane.feedbackText()).startsWith("AI analysis retried with the retained evidence"));
            top(); picture("f09-ai");
            tab(EnvironmentDashboard.View.WINDOWS);
            assertTrue(label("env-window-1").contains("Suggested arrival"));
            assertEquals(EnvironmentPresentation.minutes(fx(() -> dashboard.analysis().recommended()), l), label("env-metric-recommended"));
            picture("f09-windows-validated");

            // Technical details and copy.
            tab(EnvironmentDashboard.View.TECHNICAL);
            assertTrue(label("env-summary").contains("Local time zone: America/Toronto") && label("env-evidence").contains("Sunset:"));
            click("env-copy-technical");
            String copied = fx(() -> Clipboard.getSystemClipboard().getString());
            for (String line : EnvironmentPresentation.report(retried, l)) assertTrue(copied.contains(line), line);
            top(); picture("f09-technical");

            // Record from the overview; then reopen the inputs.
            tab(EnvironmentDashboard.View.OVERVIEW);
            click("env-record");
            assertEquals("Added to the session.", label("env-record-text"));
            assertTrue(app.facade().sessions().open(id).value().orElseThrow().environmentCheck().isPresent());
            click("env-edit-inputs");
            assertTrue(shown("env-inputs") && !shown("env-summary-bar"));

            // Dark theme, Chinese, laptop size.
            action(() -> { ThemeManager.get().select(Theme.Mode.DARK); l.useLanguage("zh-CN"); stage.setWidth(1280); stage.setHeight(800); });
            assertEquals("评估总览", label("env-tab-OVERVIEW"));
            assertEquals("天气预报", label("env-tab-WEATHER"));
            top(); picture("f09-overview-dark-zh");
            tab(EnvironmentDashboard.View.LIGHT); top(); picture("f09-light-dark-zh");
            tab(EnvironmentDashboard.View.WEATHER); top(); picture("f09-weather-dark-zh");
        } finally {
            action(() -> { ThemeManager.get().select(previousMode); if (pane != null) pane.dispose(); if (stage != null) stage.close(); });
            executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
