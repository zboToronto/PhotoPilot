package com.photopilot.ui.gui;

import com.photopilot.application.DiscoveryGuide;
import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.discovery.DiscoveryFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual JavaFX F02 Guided Discovery controls and worker callbacks against loopback provider/Gemini fakes: no request on
 * opening, inline validation, background generation, shared presentation text on the cards, dismiss/save, and the
 * hand-off into the real F03 pane. (The guided stages themselves are covered by {@link GuidedDiscoveryGuiTest}.)
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
    /** Nodes inside closed disclosures exist once their skins do, so lookups apply CSS first. */
    Node node(String id) { pane.applyCss(); return pane.lookup("#" + id); }
    Node find(String id) throws Exception { return fx(() -> node(id)); }
    void click(String id) throws Exception { var node = find(id); assertNotNull(node, id); action(() -> ((ButtonBase) node).fire()); ready(); }
    void text(String id, String value) throws Exception { var node = find(id); action(() -> ((TextField) node).setText(value)); }
    String field(String id) throws Exception { return fx(() -> ((TextField) node(id)).getText()); }
    String label(String id) throws Exception { var node = find(id); assertNotNull(node, id); return fx(() -> ((Label) node).getText()); }
    String accessible(String id) throws Exception { var node = find(id); assertNotNull(node, id); return fx(node::getAccessibleText); }
    boolean shown(String id) throws Exception { var node = find(id); return node != null && fx(() -> CalculatorGuiTest.visible(node)); }
    /** The texts a user can see under {@code id}: shown labels outside any closed disclosure. */
    List<String> visibleTexts(String id) throws Exception { var node = find(id); assertNotNull(node, id); return fx(() -> GuidedDiscoveryGuiTest.visibleTexts(node)); }
    /** One visible check row per check of the card, each naming its check and its status. */
    void checkRowsShown(String card, DiscoveryPresentation.IdeaView view) throws Exception {
        var rows = fx(() -> node(card).lookupAll(".discover-check").stream().filter(CalculatorGuiTest::visible).map(GuidedDiscoveryGuiTest::visibleTexts).toList());
        assertEquals(view.checks().size(), rows.size(), "one visible row per check: " + rows);
        for (var check : view.checks())
            assertTrue(rows.stream().anyMatch(row -> row.contains(check.name()) && row.contains(check.statusText())), check.line() + " has a visible row: " + rows);
    }
    void snapshot(String name) throws Exception { fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }
    /** Enters the reviewed form values the way a user would: text fields, the date picker and the history check box. */
    void fill(Map<String, String> form) throws Exception {
        for (var entry : form.entrySet()) {
            switch (entry.getKey()) {
                case "date" -> { var picker = (DatePicker) find("discover-date"); action(() -> { picker.getEditor().setText(entry.getValue()); picker.setValue(LocalDate.parse(entry.getValue())); }); }
                case "history" -> { var box = (CheckBox) find("discover-history"); action(() -> box.setSelected(!"no".equals(entry.getValue()))); }
                default -> text("discover-" + entry.getKey(), entry.getValue());
            }
        }
    }

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
            assertTrue(label("discover-gear-list").contains("11 item(s) recorded"), () -> "the read-only F05 inventory is shown for review");
            assertTrue(providers.requests.isEmpty() && gemini.requests.isEmpty(), "opening F02 contacts no provider, device or Gemini");
            assertTrue(fx(() -> ((CheckBox) node("discover-new-session")).isSelected()), "no session exists, so a new one would be created");

            var form = new LinkedHashMap<>(DiscoveryFixtures.form(day));
            form.put("distance", "far");
            fill(form);
            click("discover-generate");
            assertTrue(fx(() -> pane.feedbackText().contains("maximum travel distance must be")), () -> pane.feedbackText());
            assertTrue(shown("discover-error-distance") && label("discover-error-distance").contains("maximum travel distance must be"), "the error is shown next to the distance");
            assertEquals(DiscoveryGuide.Stage.LOCATION_TIME, fx(() -> pane.stage()), "the stage with the problem is opened");
            assertEquals(DiscoveryFixtures.PLACE, field("discover-location"), "inputs are kept after a validation failure");
            assertTrue(gemini.requests.isEmpty());
            text("discover-distance", "40");
            assertFalse(shown("discover-error-distance"), "editing the input clears its message");
            // The read-only inventory as step 2 shows it: one tile per category, and the grouped list under View equipment.
            click("discover-step-2");
            assertEquals(DiscoveryGuide.Stage.PREFERENCES, fx(() -> pane.stage()));
            for (String type : List.of("CAMERA", "LENS", "BATTERY", "STORAGEMEDIA")) assertTrue(shown("discover-gear-" + type), type + " tile is shown");
            assertTrue(shown("discover-gear-details"));
            assertEquals("View equipment (11 inventory record(s))", fx(() -> ((TitledPane) node("discover-gear-details")).getText()));
            click("discover-device");
            assertTrue(fx(() -> pane.feedbackText().contains("not supported on this computer")), "no consent prompt for an unsupported device");
            assertTrue(fx(() -> pane.dirty()));

            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            // Same FX pulse as the click: the request runs off the FX thread with the inputs disabled.
            var generate = (Button) find("discover-generate");
            assertTrue(fx(() -> { generate.fire();
                return pane.busy() && pane.feedbackText().contains("asking Gemini") && pane.lookup("#discover-location").isDisabled(); }));
            ready();
            var result = fx(() -> pane.result());
            assertEquals(1, result.feasible().size(), "the at-location idea is only Gemini's claim, so it stays unverified");
            assertTrue(fx(() -> pane.feedbackText().contains("1 feasible and 2 unverified")), () -> pane.feedbackText());
            var first = result.ideas().getFirst();
            assertEquals(String.join("\n", DiscoveryPresentation.idea(first, app.localization())), accessible("discover-idea-1"), "GUI cards use the shared presentation (same text as the CLI)");
            assertTrue(accessible("discover-idea-1").contains("Straight-line distance") && accessible("discover-idea-1").contains("Light and weather: Feasible"));
            // And what the card visibly shows: the key facts and one row per check with its status.
            var view = DiscoveryPresentation.view(first, app.localization());
            var card = visibleTexts("discover-idea-1");
            assertTrue(card.contains(view.where()) && card.contains(view.when()) && card.contains(view.distance().orElseThrow()), "visible facts: " + card);
            view.light().ifPresent(line -> assertTrue(card.contains(line), line));
            view.equipment().ifPresent(line -> assertTrue(card.contains(line), line));
            assertTrue(card.stream().anyMatch(text -> text.contains("Straight-line distance")), card::toString);
            assertTrue(card.contains("Light and weather") && card.contains("Feasible"), card::toString);
            checkRowsShown("discover-idea-1", view);
            assertTrue(label("discover-context").contains("verified by the weather provider"));
            assertTrue(label("discover-summary").contains("1 feasible, 2 unverified, 3 not suitable"));
            assertNotNull(find("discover-continue-3"), "unverified ideas can continue with their limitations");
            assertNull(find("discover-continue-4"), "not-suitable ideas are never offered for planning");
            assertNull(find("discover-save-5"));
            assertTrue(label("discover-ai").contains("advice, not verified facts"));
            snapshot("f02-results-wide");

            click("discover-dismiss-3");
            assertNull(find("discover-idea-3"), "dismissed idea removed from the results");
            assertTrue(fx(() -> pane.feedbackText().contains("was dismissed")));

            click("discover-save-1");
            assertTrue(fx(() -> pane.feedbackText().contains("was saved in")), () -> pane.feedbackText());
            var sessions = app.facade().sessions().search("").value().orElseThrow();
            assertEquals(1, sessions.size());
            assertEquals(first.id(), sessions.getFirst().savedIdeas().getFirst().id());
            ready();
            assertTrue(label("discover-saved").contains("Aircraft departures at the island airport"));
            assertTrue(fx(() -> node("discover-save-1").isDisabled()), "a saved idea shows that it is saved instead of offering it again");
            // The saved idea as the Saved ideas tab shows it.
            click("discover-tab-saved");
            assertTrue(shown("discover-saved-idea-1"), "the saved idea's card is shown");
            assertTrue(visibleTexts("discover-saved-idea-1").contains("Aircraft departures at the island airport"), () -> "saved card shows the subject");
            click("discover-tab-results");
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
            // The page calls an idea 灵感 (建议 is only the AI's own suggestion): the failure card's title and text agree.
            assertTrue(fx(() -> pane.feedbackText().contains("没有生成任何灵感")), () -> pane.feedbackText());
            assertTrue(shown("discover-error") && label("discover-error-text").contains("没有生成任何灵感"), "the failure has its own state on the results stage");
            assertEquals(app.localization().text("discover.guided.errorTitle"), label("discover-error-title"));
            String failureText = label("discover-error-text");
            assertTrue(failureText.startsWith("没有生成任何灵感，也没有编造任何灵感。"), "same word as the title: " + failureText);
            for (String shownText : visibleTexts("discover-error")) assertFalse(shownText.contains("建议"), "the failure card never calls an idea 建议: " + shownText);
            // The whole page (the failure card lies below the fold at this size), for design review.
            AppearanceGuiTest.settle();
            fx(() -> { var page = (javafx.scene.layout.Region) pane.getParent(); page.applyCss(); page.layout();
                var parameters = new javafx.scene.SnapshotParameters(); parameters.setFill(GuidedDiscoveryGuiTest.LIGHT);
                CalculatorGuiTest.write(page.snapshot(parameters, null), "f02-error-zh"); return true; });
            assertEquals("40", field("discover-distance"));
            assertNull(fx(() -> pane.result()));
            // The fallback stays one click away, as before the redesign: the next step names Shooting Requirements and opens it.
            var next = fx(() -> node("discover-error").lookup("#progress-next")); var open = fx(() -> node("discover-error").lookup("#progress-open"));
            assertNotNull(next); assertNotNull(open);
            assertTrue(fx(() -> CalculatorGuiTest.visible(next) && CalculatorGuiTest.visible(open)), "the next step and its Open action are visible");
            assertEquals(app.localization().text("progress.next", app.localization().text("next.manual.discover")), fx(() -> ((Labeled) next).getText()));
            assertEquals(app.localization().text("progress.open", app.localization().text("feature.F03.title")), fx(() -> ((Labeled) open).getText()));
            action(() -> ((Button) open).fire());
            assertTrue(fx(() -> requirements.isVisible() && ((ToggleButton) workspace.lookup("#nav-F03")).isSelected()), "Open goes to Shooting Requirements through the normal navigation");
            assertFalse(fx(() -> CalculatorGuiTest.visible(pane)));
            action(() -> workspace.selectDiscovery()); ready();
            assertTrue(shown("discover-error"), "back on the page, the failure state is unchanged");
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            assertTrue(shown("discover-retry"), "the visible Try again of the failure state retries");
            click("discover-retry");
            assertTrue(accessible("discover-idea-1").contains("直线距离"));
            assertTrue(visibleTexts("discover-idea-1").stream().anyMatch(text -> text.contains("直线距离")), "the card visibly shows the straight-line distance");
            checkRowsShown("discover-idea-1", DiscoveryPresentation.view(fx(() -> pane.result()).ideas().getFirst(), app.localization()));
            assertFalse(shown("discover-error"));
            snapshot("f02-results-narrow-zh");
            // The status line and the saved card's action use the page's word for an idea too.
            assertTrue(fx(() -> pane.feedbackText().contains("1 个可行、2 个未核实的灵感")), () -> pane.feedbackText());
            assertFalse(fx(() -> pane.feedbackText().contains("建议")), () -> pane.feedbackText());
            click("discover-tab-saved");
            assertEquals("在拍摄需求中继续已保存的灵感 1", accessible("discover-saved-continue-1"));
            click("discover-tab-results");
            action(() -> app.localization().useLanguage("en"));
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
