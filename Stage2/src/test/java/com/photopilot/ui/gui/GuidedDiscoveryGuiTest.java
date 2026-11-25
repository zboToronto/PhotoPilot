package com.photopilot.ui.gui;

import com.photopilot.application.DiscoveryForm;
import com.photopilot.application.DiscoveryGuide.Stage;
import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.discovery.DiscoveryFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.model.EquipmentType;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guided Discovery presentation in real JavaFX windows against the loopback provider and Gemini fakes (no live
 * service): required inputs and inline errors per stage, step navigation and input preservation, chips plus free-text
 * interests reaching the request exactly, optional coordinates, overnight windows, distance boundaries and the slider,
 * the equipment summary (units versus records), the history preference, the loading state and duplicate prevention,
 * the structured cards (checks apart from AI text, open findings visible), error recovery, saved ideas without Gemini,
 * saving into an existing session, abandoned (stale) responses, the changed-conditions banner, both languages, layout at
 * common window sizes, dark mode and profile isolation.
 */
class GuidedDiscoveryGuiTest {
    private static final String CSS = PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm();
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app; ExecutorService executor;
    DiscoveryPane pane; PhotoPilotWorkspace workspace; javafx.stage.Stage stage;
    final List<UUID> handedOff = new CopyOnWriteArrayList<>();
    final LocalDate day = DiscoveryFixtures.day();

    static final Color LIGHT = Color.rgb(242, 245, 254);
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void close() throws Exception {
        action(() -> { if (stage != null) stage.close(); if (pane != null) pane.dispose(); ThemeManager.get().select(Theme.Mode.SYSTEM); });
        if (executor != null) { executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
        if (app != null) app.close();
        if (providers != null) providers.close();
        if (gemini != null) gemini.close();
    }

    // ------------------------------------------------------------------ harness

    void boot(boolean gear) throws Exception {
        executor = Executors.newSingleThreadExecutor();
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        app = new ApplicationBootstrap(providers.environment(gemini.environment(root)));
        if (gear) for (var item : new GearFixtures.Airshow().items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
    }
    void show(int width, int height) throws Exception {
        action(() -> {
            workspace = new PhotoPilotWorkspace(app.localization());
            pane = new DiscoveryPane(app.facade(), executor, app.localization(), (id, draft) -> handedOff.add(id));
            workspace.connectDiscovery(pane);
            stage = new javafx.stage.Stage(); var scene = new Scene(workspace, width, height);
            scene.getStylesheets().add(CSS); stage.setScene(scene); stage.show();
            workspace.selectDiscovery(); workspace.applyCss(); workspace.layout();
        });
        ready();
    }
    void open(int width, int height, boolean gear) throws Exception { boot(gear); show(width, height); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("Guided Discovery operation timed out"); } while (fx(() -> pane.busy()));
    }
    static void waitFor(Callable<Boolean> condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.call()) { if (System.nanoTime() > deadline) fail("Timed out waiting for " + what); Thread.sleep(30); }
    }
    /** FX thread only. Nodes inside closed disclosures exist once their skins do, so lookups apply CSS first. */
    Node node(String id) { pane.applyCss(); return pane.lookup("#" + id); }
    Node find(String id) throws Exception { return fx(() -> node(id)); }
    void click(String id) throws Exception { fire(id); ready(); }
    void fire(String id) throws Exception { var node = find(id); assertNotNull(node, id); action(() -> ((ButtonBase) node).fire()); }
    void text(String id, String value) throws Exception { var node = find(id); assertNotNull(node, id); action(() -> ((TextField) node).setText(value)); }
    String field(String id) throws Exception { return fx(() -> ((TextField) node(id)).getText()); }
    String label(String id) throws Exception { return fx(() -> ((Labeled) node(id)).getText()); }
    String accessible(String id) throws Exception { return fx(() -> node(id).getAccessibleText()); }
    boolean shown(String id) throws Exception { return fx(() -> { var node = node(id); return node != null && CalculatorGuiTest.visible(node); }); }
    boolean disabled(String id) throws Exception { return fx(() -> node(id).isDisabled()); }
    Stage stage() throws Exception { return fx(() -> pane.stage()); }
    Map<String, String> values() throws Exception { return fx(() -> pane.values()); }
    void date(String value) throws Exception {
        action(() -> { var picker = (DatePicker) node("discover-date"); picker.getEditor().setText(value);
            try { picker.setValue(LocalDate.parse(value)); } catch (RuntimeException invalid) { picker.setValue(null); } });
    }
    /** Typing into the date editor and committing it, as Enter or leaving the field does (through the picker's converter). */
    void typeDate(String value) throws Exception {
        action(() -> { var picker = (DatePicker) node("discover-date"); picker.getEditor().setText(value); picker.setValue(picker.getConverter().fromString(value)); });
    }
    void chip(String term, boolean selected) throws Exception { action(() -> ((ToggleButton) node("discover-chip-" + term)).setSelected(selected)); }
    /** The acceptance form (stage 1 and 2 values) entered the way a user would. */
    void fill(Map<String, String> form) throws Exception {
        for (var entry : form.entrySet()) switch (entry.getKey()) {
            case "date" -> date(entry.getValue());
            case "history" -> action(() -> ((CheckBox) node("discover-history")).setSelected(!"no".equals(entry.getValue())));
            default -> text("discover-" + entry.getKey(), entry.getValue());
        }
    }
    void fillStageOne() throws Exception {
        var form = DiscoveryFixtures.form(day);
        for (String key : List.of("location", "latitude", "longitude", "date", "from", "until", "distance")) fill(Map.of(key, form.get(key)));
    }
    void discover() throws Exception {
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        fill(DiscoveryFixtures.form(day));
        click("discover-generate");
        assertNotNull(fx(() -> pane.result()), says(() -> "no result: " + fx(() -> pane.feedbackText())));
    }
    static void settle() throws Exception { AppearanceGuiTest.settle(); }
    void snapshot(String name) throws Exception { settle(); fx(() -> { CalculatorGuiTest.snapshot(workspace, name); return true; }); }
    /** The whole page (not only the viewport), on the backdrop colour, for design review. */
    void picture(String name, Color backdrop) throws Exception {
        settle();
        fx(() -> {
            var page = (Region) pane.getParent(); page.applyCss(); page.layout();
            var parameters = new SnapshotParameters(); parameters.setFill(backdrop);
            CalculatorGuiTest.write(page.snapshot(parameters, null), name); return true;
        });
    }
    /** A failure message computed on demand from FX state. */
    static java.util.function.Supplier<String> says(Callable<String> text) { return () -> { try { return text.call(); } catch (Exception failure) { return failure.toString(); } }; }
    static boolean insideDisclosure(Node node) { for (Node n = node.getParent(); n != null; n = n.getParent()) if (n instanceof TitledPane) return true; return false; }
    /** The id of the scene's focus owner (keyboard focus), or null. */
    String focusOwner() throws Exception { return fx(() -> { var owner = workspace.getScene().getFocusOwner(); return owner == null ? null : owner.getId(); }); }
    boolean focusInside(String id) throws Exception {
        return fx(() -> { var target = node(id); for (Node n = workspace.getScene().getFocusOwner(); n != null; n = n.getParent()) if (n == target) return true; return false; });
    }
    /** The node lies within the visible part of the page's scroll viewport. */
    boolean inViewport(String id) throws Exception {
        return fx(() -> {
            workspace.applyCss(); workspace.layout();
            var scroll = (ScrollPane) workspace.lookup(".content-scroll"); var node = node(id);
            var view = scroll.localToScene(scroll.getLayoutBounds()); var bounds = node.localToScene(node.getBoundsInLocal());
            return bounds.getMinY() >= view.getMinY() - 1 && bounds.getMaxY() <= view.getMaxY() + 1;
        });
    }
    /** Resizes the shown window (frame included, as the application's own 880 x 600 minimum) and waits for the layout. */
    void resize(double width, double height) throws Exception {
        double frame = fx(() -> stage.getWidth() - stage.getScene().getWidth());
        action(() -> { stage.setWidth(width); stage.setHeight(height); });
        waitFor(() -> fx(() -> Math.abs(stage.getScene().getWidth() - (width - frame)) <= 1.5 && Math.abs(workspace.getWidth() - stage.getScene().getWidth()) <= 0.5),
            "the window size " + width + "x" + height);
        settle();
    }
    /** The footer's buttons lie inside it, do not overlap and keep their whole caption. */
    static void footerFits(Region footer, String where) {
        var buttons = footer.getChildrenUnmodifiable().stream().filter(n -> n.isManaged() && n.isVisible()).toList();
        var area = footer.localToScene(footer.getLayoutBounds());
        for (int i = 0; i < buttons.size(); i++) {
            var button = (ButtonBase) buttons.get(i); var b = button.localToScene(button.getBoundsInLocal());
            assertTrue(b.getMinX() >= area.getMinX() - 0.5 && b.getMaxX() <= area.getMaxX() + 0.5, where + ": " + button.getId() + " " + b + " outside its footer " + area);
            for (Node child : button.getChildrenUnmodifiable()) if (child instanceof javafx.scene.text.Text shown)
                assertEquals(button.getText(), shown.getText(), where + ": " + button.getId() + " is cut short");
            for (int j = i + 1; j < buttons.size(); j++) {
                var other = buttons.get(j).localToScene(buttons.get(j).getBoundsInLocal());
                assertFalse(b.intersects(other.getMinX() + 0.5, other.getMinY() + 0.5, other.getWidth() - 1, other.getHeight() - 1),
                    where + ": " + button.getId() + " overlaps " + buttons.get(j).getId());
            }
        }
    }
    /** The open date-picker popup's root, or null. */
    static Parent datePopup() {
        for (var window : javafx.stage.Window.getWindows())
            if (window instanceof javafx.stage.PopupWindow && window.isShowing() && window.getScene() != null && window.getScene().getRoot().lookup(".date-picker-popup") != null)
                return window.getScene().getRoot();
        return null;
    }
    static List<Labeled> labels(Node root) {
        var found = new ArrayList<Labeled>();
        if (root instanceof Labeled labeled) found.add(labeled);
        if (root instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) found.addAll(labels(child));
        return found;
    }
    /** FX thread only: the texts a user can see under {@code root} (shown, and not inside any disclosure). */
    static List<String> visibleTexts(Node root) {
        return labels(root).stream().filter(l -> CalculatorGuiTest.visible(l) && !insideDisclosure(l) && l.getText() != null).map(Labeled::getText).toList();
    }
    static boolean within(Node node, Node ancestor) { for (Node n = node; n != null; n = n.getParent()) if (n == ancestor) return true; return false; }
    String t(String key, Object... arguments) { return app.localization().text(key, arguments); }
    /** The stepper's two connector lines (1 to 2, 2 to 3): drawn as done or not. */
    List<Boolean> connectors() throws Exception { return fx(() -> List.of(pane.stepper().lineDone(Stage.LOCATION_TIME), pane.stepper().lineDone(Stage.PREFERENCES))); }
    boolean invalid(String id) throws Exception { return fx(() -> node(id).getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("invalid"))); }
    boolean expanded(String id) throws Exception { return fx(() -> ((TitledPane) node(id)).isExpanded()); }
    void expand(String id) throws Exception { action(() -> ((TitledPane) node(id)).setExpanded(true)); }
    /** The default loopback forecast with hourly series set to a constant and/or left out, as a provider could send it. */
    static FakeEnvironmentProviders.Reply forecast(Map<String, String> query, Map<String, Double> constant, Set<String> omitted) {
        var reply = FakeEnvironmentProviders.defaultForecast(query);
        var root = com.google.gson.JsonParser.parseString(reply.body()).getAsJsonObject();
        if (root.has("hourly")) {
            var hourly = root.getAsJsonObject("hourly");
            constant.forEach((name, value) -> {
                if (!hourly.has(name)) return;
                var series = new com.google.gson.JsonArray();
                for (int i = 0; i < hourly.getAsJsonArray(name).size(); i++) series.add(value);
                hourly.add(name, series);
            });
            omitted.forEach(hourly::remove);
        }
        return new FakeEnvironmentProviders.Reply(reply.status(), root.toString());
    }

    // ------------------------------------------------------------------ stages, validation and navigation

    @Test void requiredInputsKeepTheFirstStageWithMessagesNextToTheirFields() throws Exception {
        open(1280, 720, true);
        assertEquals(Stage.LOCATION_TIME, stage());
        assertEquals(List.of(false, false), connectors(), "no step is done yet");
        assertTrue(disabled("discover-step-2") && disabled("discover-step-3"), "later stages cannot be opened before stage 1 is valid");
        assertEquals(DiscoveryStepper.State.CURRENT, fx(() -> pane.stepper().state(Stage.LOCATION_TIME)));
        assertTrue(accessible("discover-step-1").contains("Step 1 of 3: Location & Time, current step"), () -> "step state in words");
        assertTrue(accessible("discover-step-2").contains("not started"));

        click("discover-next");
        assertEquals(Stage.LOCATION_TIME, stage(), "Next validates the stage first");
        for (String slot : List.of("location", "time", "distance")) assertTrue(shown("discover-error-" + slot), slot + " message shown next to its input");
        assertFalse(shown("discover-error-date"), "today's date is valid");
        assertEquals("Enter a place or area, for example a city or neighbourhood.", label("discover-error-location"));
        assertTrue(fx(() -> node("discover-location").getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("invalid"))));
        assertEquals("Enter a place or area, for example a city or neighbourhood.", fx(() -> node("discover-location").getAccessibleHelp()), "the message is available to assistive technology");
        assertEquals("discover-location", focusOwner(), "keyboard focus moves to the first input with a message");
        assertTrue(gemini.requests.isEmpty() && providers.requests.isEmpty(), "validation sends nothing");
        picture("f02-guided-page1-errors-en", LIGHT);

        // A typed date that does not exist is reported next to the date, in the form's own words.
        fillStageOne();
        typeDate("2026-02-30");
        click("discover-next");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(shown("discover-error-date"));
        assertEquals(app.localization().text("env.error.date"), label("discover-error-date"));
        assertEquals("2026-02-30", values().get("date"), "the typed text is what the form checks");
        date(day.toString());
        assertFalse(shown("discover-error-date"), "correcting the date clears its message");

        assertFalse(shown("discover-error-location"), "editing an input clears its message");
        assertFalse(disabled("discover-step-2"), "a valid stage 1 makes step 2 available");
        click("discover-step-2");
        assertEquals(Stage.PREFERENCES, stage());
        assertTrue(focusInside("discover-stage-2"), says(() -> "focus moves into the opened stage, not " + focusOwner()));
        assertEquals("discover-chip-landscape", focusOwner());
        assertEquals(DiscoveryStepper.State.COMPLETED, fx(() -> pane.stepper().state(Stage.LOCATION_TIME)));
        assertEquals(List.of(true, false), connectors(), "the line after the completed step 1 is done, the next one is not");
        assertTrue(disabled("discover-step-3"), "nothing to show on step 3 yet, and clicking it never sends a request");
        click("discover-back");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertEquals("discover-location", focusOwner(), "Back moves focus into step 1");
        assertEquals(DiscoveryFixtures.PLACE, field("discover-location"));
        assertEquals(List.of(true, false), connectors(), "back on the validated step 1, its line stays done (no grey 1-2 before an accent 2-3)");
        snapshot("f02-guided-stepper-back-en");

        // An invalid edit keeps step 2 available (it was reached); opening it re-validates step 1 and says why it cannot open.
        text("discover-from", "25:00");
        assertFalse(disabled("discover-step-2"));
        click("discover-step-2");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(label("discover-error-time").contains("HH:MM"), says(() -> "time error: " + label("discover-error-time")));
        assertEquals("discover-from", focusOwner());
        click("discover-next");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(label("discover-error-time").contains("HH:MM"), says(() -> "time error: " + label("discover-error-time")));
        assertTrue(gemini.requests.isEmpty());
    }

    @Test void savedIdeasStayReachableFromStepThreeWithoutCheckingAnEmptyForm() throws Exception {
        open(1366, 768, false);
        click("discover-open-saved");
        assertEquals(Stage.REVIEW, stage());
        assertEquals("discover-tab-saved", focusOwner(), "focus moves to the open tab");
        click("discover-step-1");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertFalse(disabled("discover-step-3"), "the saved ideas stay one click away");
        assertTrue(disabled("discover-step-2"), "browsing saved ideas does not unlock the inputs' next step");
        click("discover-step-3");
        assertEquals(Stage.REVIEW, stage(), "no request is involved, so nothing is validated");
        assertTrue(shown("discover-saved-view"));
        for (String slot : List.of("location", "date", "time", "distance"))
            assertFalse(fx(() -> node("discover-error-" + slot).isVisible()), slot + ": no message about a form the user never meant to fill");
        assertTrue(gemini.requests.isEmpty() && providers.requests.isEmpty());
    }

    @Test void placeLookupOffersEveryMatchAndAFailedLookupKeepsManualEntry() throws Exception {
        open(1366, 768, false);
        text("discover-location", "Toronto");
        click("discover-find");
        assertTrue(shown("discover-places"), "the matches are offered after a search");
        @SuppressWarnings("unchecked") var places = (ComboBox<com.photopilot.model.PlaceCandidate>) find("discover-places");
        assertEquals(3, fx(() -> places.getItems().size()), "an ambiguous name is never resolved automatically");
        var ontario = fx(() -> places.getItems().getFirst());
        action(() -> places.setValue(ontario));
        assertEquals(com.photopilot.application.EnvironmentForm.coordinate(ontario.latitude()), field("discover-latitude"));
        assertEquals(com.photopilot.application.EnvironmentForm.coordinate(ontario.longitude()), field("discover-longitude"));
        assertTrue(label("discover-location-status").startsWith("Selected from place lookup"), says(() -> label("discover-location-status")));
        text("discover-latitude", "43.7");
        assertTrue(label("discover-location-status").startsWith("Manual coordinates"), "edited coordinates are no longer the looked-up place");
        assertTrue(gemini.requests.isEmpty(), "a place lookup never involves Gemini");

        providers.geocode = q -> new FakeEnvironmentProviders.Reply(503, "");
        click("discover-find");
        assertTrue(shown("discover-error-location"), "a failed lookup is reported next to the location");
        assertTrue(label("discover-error-location").contains(app.localization().text("discover.place.manualFallback")), says(() -> label("discover-error-location")));
        assertFalse(shown("discover-places"));
        assertTrue(fx(() -> node("discover-feedback").getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("error"))),
            "a failure message has its own look as well as its words");
        assertEquals("43.7", field("discover-latitude"), "manual entry stays available and untouched");
    }

    @Test void everyInputSurvivesNavigationAndChipsSupplementTheTypedInterestsExactly() throws Exception {
        open(1366, 768, true);
        var form = new LinkedHashMap<>(DiscoveryFixtures.form(day));
        form.put("interests", "waterfalls; markets"); form.put("avoid", "crowds"); form.put("notes", "no tripod"); form.put("duration", "90"); form.put("history", "no");
        fill(form);
        chip("aviation", true); chip("night", true);
        var before = values();
        assertEquals("aviation; night; waterfalls; markets", before.get("interests"), "presets in display order, then the typed entries as written");
        assertEquals("no", before.get("history"));
        assertTrue(accessible("discover-chip-aviation").endsWith("selected") && accessible("discover-chip-street").endsWith("not selected"));

        click("discover-next"); click("discover-back"); click("discover-next"); click("discover-step-1"); click("discover-step-2");
        assertEquals(Stage.PREFERENCES, stage());
        assertEquals(before, values(), "navigation never changes an input");
        assertEquals("waterfalls; markets", field("discover-interests"), "chips never rewrite the typed interests");
        assertEquals(40.0, fx(() -> ((Slider) node("discover-distance-slider")).getValue()), 0.001);

        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        click("discover-generate");
        var result = fx(() -> pane.result());
        assertNotNull(result, says(() -> fx(() -> pane.feedbackText())));
        assertEquals(List.of("aviation", "night", "waterfalls", "markets"), result.context().preferences(), "the request receives exactly the composed interests");
        assertTrue(result.notices().stream().anyMatch(n -> n.key().equals("discover.notice.historyOff")), "the history preference reaches the request");
        assertTrue(fx(() -> ((Labeled) ((javafx.scene.layout.Pane) node("discover-condition-history")).getChildren().get(1)).getText()).equals("Not used"));

        click("discover-edit-conditions");
        assertEquals(Stage.LOCATION_TIME, stage(), "Edit conditions returns to the inputs");
        assertEquals(before, values(), "and keeps every one of them");
        assertFalse(shown("discover-feedback"), "the results message stays with the results, not on the inputs");
        assertTrue(fx(() -> pane.feedbackText()).contains("feasible"), "it is kept for step 3");
        assertTrue(fx(() -> ((ToggleButton) node("discover-chip-aviation")).isSelected() && !((ToggleButton) node("discover-chip-street")).isSelected()));
        assertEquals("90", field("discover-duration"));
    }

    @Test void coordinatesTimeWindowsAndDistanceBoundariesUseTheFormsOwnRules() throws Exception {
        open(1280, 720, false);
        fillStageOne();
        text("discover-latitude", ""); text("discover-longitude", "");
        assertTrue(label("discover-location-status").startsWith("Name only"), says(() -> label("discover-location-status")));
        click("discover-next");
        assertEquals(Stage.PREFERENCES, stage(), "a place name without coordinates is valid");
        click("discover-back");

        text("discover-latitude", "43.6");
        click("discover-next");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(shown("discover-error-coordinates"), "one coordinate alone is reported next to the coordinates");
        assertTrue(fx(() -> ((TitledPane) node("discover-advanced")).isExpanded()), "the advanced section opens to show it");
        text("discover-longitude", "-79.4");
        assertTrue(label("discover-location-status").startsWith("Manual coordinates"), says(() -> label("discover-location-status")));
        click("discover-next");
        assertEquals(Stage.PREFERENCES, stage());
        click("discover-back");

        // An equal start and end is the empty window the request rejects: no "24 h" note contradicts the message.
        text("discover-from", "08:00"); text("discover-until", "08:00");
        assertFalse(shown("discover-window-note"));
        click("discover-next");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertEquals(app.localization().text("env.error.emptyAvailability"), label("discover-error-time"));

        text("discover-from", "22:00"); text("discover-until", "02:00");
        assertTrue(shown("discover-window-note"));
        assertEquals("240 min available, ends the next day", label("discover-window-note"));
        text("discover-until", "23:30");
        assertEquals("90 min available", label("discover-window-note"));

        for (String km : List.of("0", "1000")) {
            text("discover-distance", km);
            click("discover-next");
            assertEquals(Stage.PREFERENCES, stage(), km + " km is accepted");
            click("discover-back");
        }
        // The large value never shows a distance the request would refuse.
        for (String rejected : List.of("5000", "40.12345", "far", "1000.5")) {
            text("discover-distance", rejected);
            assertEquals("— km", label("discover-distance-value"), rejected + " is not shown as accepted");
        }
        text("discover-distance", "12.5");
        assertEquals("12.5 km", label("discover-distance-value"));
        text("discover-distance", "1000.5");
        click("discover-next");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(label("discover-error-distance").contains("maximum travel distance must be"), says(() -> label("discover-error-distance")));

        text("discover-distance", "500");
        assertEquals("500", field("discover-distance"), "typed values beyond the slider range are kept");
        assertEquals(200.0, fx(() -> ((Slider) node("discover-distance-slider")).getValue()), 0.001, "the slider shows its end");
        assertEquals("500 km", label("discover-distance-value"));
        action(() -> ((Slider) node("discover-distance-slider")).setValue(37.4));
        assertEquals("37", field("discover-distance"), "dragging the slider sets whole kilometres");
        assertEquals("37 km", label("discover-distance-value"));
        text("discover-distance", "");
        assertEquals("— km", label("discover-distance-value"));
        assertTrue(gemini.requests.isEmpty() && providers.requests.isEmpty());
    }

    // ------------------------------------------------------------------ equipment and history

    @Test void equipmentSummaryCountsUnitsAndRecordsFromTheRealInventoryOnly() throws Exception {
        open(1366, 768, false);
        assertTrue(fx(() -> node("discover-gear-empty").isVisible()), "an empty inventory says so");
        assertEquals(app.localization().text("discover.gear.empty"), label("discover-gear-empty"));
        assertEquals(t("discover.guided.gearEmpty"), label("discover-gear-empty"), "the equipment card's own wording (the same English as the terminal)");
        assertNull(find("discover-gear-CAMERA"), "no demo gear");
        // The page's own wording of the shared feedback keeps the terminal's English; only the Chinese word for an idea differs.
        var own = new LinkedHashMap<String, String>();
        for (String key : List.of("done", "doneNoneFeasible", "continueSaved", "running", "saveFailed", "handoffFailed")) own.put("discover.guided." + key, "discover." + key);
        own.put("discover.guided.failedText", "discover.failedInputsKept"); own.put("discover.guided.gearEmpty", "discover.gear.empty");
        own.forEach((key, shared) -> assertEquals(t(shared, 1, 2), t(key, 1, 2), key));
        // In Chinese the step-2 equipment card's read-only note and its empty-inventory line use the same word for an idea (灵感).
        action(() -> app.localization().useLanguage("zh-CN"));
        try {
            fillStageOne(); click("discover-next");
            assertEquals(Stage.PREFERENCES, stage());
            var card = fx(() -> visibleTexts(node("discover-gear")));
            assertTrue(card.contains(t("discover.guided.gearReadOnly")) && card.contains(t("discover.guided.gearEmpty")), card::toString);
            assertTrue(card.stream().noneMatch(text -> text.contains("建议")), "建议 is never used for an idea in the equipment card: " + card);
            for (String key : own.keySet()) { String zh = t(key, 1, 2); assertTrue(zh.contains("灵感") && !zh.contains("建议"), key + " = " + zh); }
            picture("f02-guided-gear-empty-zh", LIGHT);
        } finally { action(() -> app.localization().useLanguage("en")); }

        var gear = new GearFixtures.Airshow();
        for (var item : gear.items()) if (item != gear.cpl) assertTrue(app.facade().inventory().add(item, true).isSuccess());
        click("discover-gear-refresh");
        assertFalse(fx(() -> node("discover-gear-empty").isVisible()));
        assertEquals("Camera bodies: 2 unit(s), 2 inventory record(s)", accessible("discover-gear-CAMERA"));
        assertEquals("Lenses: 5 unit(s), 5 inventory record(s)", accessible("discover-gear-LENS"));
        assertEquals("Batteries: 6 unit(s), 2 inventory record(s)", accessible("discover-gear-BATTERY"), "units are summed quantities, records are entries");
        assertEquals("Memory cards: 2 unit(s), 1 inventory record(s)", accessible("discover-gear-STORAGEMEDIA"));
        assertNull(find("discover-gear-FILTER"), "filters appear only when recorded");

        assertTrue(app.facade().inventory().add(gear.cpl, true).isSuccess());
        click("discover-gear-refresh");
        assertEquals("Filters: 1 unit(s), 1 inventory record(s)", accessible("discover-gear-FILTER"));
        assertEquals("View equipment (11 inventory record(s))", fx(() -> ((TitledPane) node("discover-gear-details")).getText()));
        var groups = DiscoveryPresentation.gearGroups(app.facade().inventory().list("").value().orElseThrow(), app.localization());
        for (var type : EquipmentType.values()) {
            var group = find("discover-gear-group-" + type.name());
            assertNotNull(group, type.name());
            var shown = fx(() -> labels(group).stream().map(Labeled::getText).toList());
            assertTrue(shown.containsAll(groups.get(type)), type + " lists every record: " + shown);
        }
        assertTrue(label("discover-gear-list").contains("11 item(s) recorded"), "the complete shared listing stays available");
        assertTrue(fx(() -> ((CheckBox) node("discover-history")).isSelected()), "history is used by default, as before");
        assertTrue(label("discover-history-note").contains("Gemini"), "the history sentence says where it goes");
        assertEquals("Up to " + com.photopilot.service.DiscoveryService.MAX_HISTORY + " saved sessions are sent to Gemini as a hint (their subjects, saved ideas, "
            + "dates and status, never notes or locations), and nothing in them is changed.", label("discover-history-note"), "one sentence, the same as the terminal's");
        assertTrue(gemini.requests.isEmpty() && providers.requests.isEmpty(), "reading the inventory contacts nothing");
    }

    // ------------------------------------------------------------------ the request

    @Test void theLoadingStateIsShownAndASecondClickSendsNoSecondRequest() throws Exception {
        open(1366, 768, true);
        fill(DiscoveryFixtures.form(day));
        click("discover-next");
        var release = new CountDownLatch(1);
        gemini.replyWith(body -> { try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return DiscoveryFixtures.acceptanceReply(day); });
        fire("discover-generate");
        waitFor(() -> gemini.requests.size() == 1, "the Gemini request");
        assertTrue(fx(() -> pane.busy()));
        assertEquals(Stage.REVIEW, stage());
        assertTrue(shown("discover-loading"), "a dedicated loading state");
        assertTrue(fx(() -> ((ProgressIndicator) pane.lookup(".discover-spinner")).isIndeterminate()), "no invented percentage");
        assertTrue(fx(() -> pane.lookup("#discover-loading").lookupAll(".progress-bar").stream().noneMatch(n -> n.isVisible() && n instanceof ProgressBar)));
        assertTrue(disabled("discover-generate"));
        fire("discover-generate"); fire("discover-retry"); fire("discover-rediscover");
        Thread.sleep(400);
        assertEquals(1, gemini.requests.size(), "no duplicate concurrent request");
        picture("f02-guided-loading-en", LIGHT);
        // The inputs can still be reviewed (read only) while the request runs.
        fire("discover-step-1");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(disabled("discover-location"));
        fire("discover-step-3");
        assertEquals(Stage.REVIEW, stage());
        release.countDown();
        ready();
        assertNotNull(fx(() -> pane.result()));
        assertFalse(shown("discover-loading"));
        assertFalse(disabled("discover-location"));
        assertEquals(1, gemini.requests.size());
    }

    @Test void cardsSeparateCheckedFactsFromAiSuggestionsAndKeepEveryWarningVisible() throws Exception {
        open(1366, 768, true);
        discover();
        var result = fx(() -> pane.result());
        assertTrue(inViewport("discover-counts"), "arriving results bring their counts and first cards into view");
        assertTrue(shown("discover-facts-source-1"), "the key facts say they are Gemini's proposal");
        assertEquals("1 feasible", fx(() -> labels(node("discover-count-FEASIBLE")).get(0).getText()), "counts carry a shape and words");
        assertEquals("2 unverified", fx(() -> labels(node("discover-count-UNVERIFIED")).get(0).getText()));
        assertEquals("3 not suitable", fx(() -> labels(node("discover-count-INFEASIBLE")).get(0).getText()));
        int number = 0, noted = 0;
        for (var idea : result.ideas()) {
            number++;
            int n = number;
            var view = DiscoveryPresentation.view(idea, app.localization());
            assertEquals(String.join("\n", view.lines()), accessible("discover-idea-" + n), "card " + n + " carries the complete shared text");
            var checked = find("discover-checked-" + n); var suggested = find("discover-suggested-" + n);
            assertNotNull(checked); assertNotNull(suggested);
            var checkedText = fx(() -> labels(checked).stream().map(Labeled::getText).toList());
            var suggestedText = fx(() -> labels(suggested).stream().map(Labeled::getText).toList());
            assertTrue(checkedText.contains(DiscoveryPresentation.verification(view, app.localization())), checkedText::toString);
            assertTrue(suggestedText.contains(view.rationale()), "the AI rationale is under the AI heading");
            assertFalse(checkedText.contains(view.rationale()), "and never under the checks");
            for (var limitation : view.limitations()) assertTrue(suggestedText.contains(limitation), "AI limitations are visible: " + limitation);
            for (var note : view.notes()) {
                noted++;
                assertTrue(checkedText.contains(note), "PhotoPilot's notes stay visible with its checks: " + note);
                assertFalse(suggestedText.contains(note), "and never under the AI heading: " + note);
            }
            for (var check : view.open()) {
                if (check.findings().isEmpty()) continue;
                var findings = find("discover-findings-" + n + "-" + check.criterion());
                assertNotNull(findings, "open findings of idea " + n + " " + check.criterion());
                assertTrue(fx(() -> CalculatorGuiTest.visible(findings) && !insideDisclosure(findings)), "warnings are never collapsed");
                var lines = fx(() -> labels(findings).stream().map(Labeled::getText).toList());
                assertTrue(lines.containsAll(check.findings()), lines::toString);
            }
            // Every finding that must stay in view (all of a check that did not pass, the cautions of a passing one) is
            // visible under its check row; the closed Details hold exactly the rest, so nothing is repeated or dropped.
            var details = find("discover-idea-details-" + n);
            var detailTexts = details == null ? List.<String>of() : fx(() -> labels(((TitledPane) details).getContent()).stream().map(Labeled::getText).toList());
            for (var check : view.checks()) {
                if (!check.shown().isEmpty()) {
                    var findings = find("discover-findings-" + n + "-" + check.criterion());
                    assertNotNull(findings, "shown findings of idea " + n + " " + check.criterion());
                    assertTrue(fx(() -> CalculatorGuiTest.visible(findings) && !insideDisclosure(findings)), "idea " + n + " " + check.criterion() + ": shown findings are never collapsed");
                    var visible = fx(() -> visibleTexts(findings));
                    assertTrue(visible.containsAll(check.shown()), "idea " + n + " " + check.criterion() + ": " + visible + " lacks " + check.shown());
                    for (String line : check.shown()) assertFalse(detailTexts.contains(line), "shown, so not repeated in Details: " + line);
                }
                assertTrue(detailTexts.containsAll(check.details()), "idea " + n + " " + check.criterion() + ": Details " + detailTexts + " lacks " + check.details());
                if (!check.details().isEmpty()) assertTrue(detailTexts.contains(check.line()), "Details name the check of its evidence: " + check.line());
            }
            if (idea.feasibility() == Feasibility.INFEASIBLE) { assertNull(find("discover-continue-" + n)); assertNull(find("discover-save-" + n)); }
            else { assertNotNull(find("discover-continue-" + n)); assertNotNull(find("discover-save-" + n)); }
            assertNotNull(find("discover-dismiss-" + n));
            if (details != null) assertFalse(fx(() -> ((TitledPane) details).isExpanded()), "details start closed");
        }
        assertTrue(noted > 0, "the acceptance reply yields at least one PhotoPilot note, so its placement is checked");
        assertTrue(fx(() -> ((Labeled) node("discover-group-FEASIBLE")).getText()).equals("Feasible ideas"));
        assertTrue(label("discover-summary").contains("1 feasible, 2 unverified, 3 not suitable"));
        assertTrue(shown("discover-request-summary"));
        assertTrue(fx(() -> ((Labeled) ((javafx.scene.layout.Pane) node("discover-condition-distance")).getChildren().get(1)).getText()).equals("40 km straight-line"));
        assertEquals(DiscoveryPresentation.gaps(result, app.localization()).isEmpty(), find("discover-gaps") == null, "evidence gaps are listed exactly when there are some");
        fx(() -> { LanguageLayoutGuiTest.check(pane, "results 1366x768"); return true; });
    }

    @Test void forecastWarningsOfAPassingCheckStayVisibleOnTheFeasibleCard() throws Exception {
        open(1366, 768, true);
        providers.forecast = q -> forecast(q, Map.of("weather_code", 95.0, "precipitation_probability", 90.0, "wind_gusts_10m", 85.0), Set.of());
        discover();
        var result = fx(() -> pane.result());
        assertFalse(result.feasible().isEmpty(), "forecast warnings lower the ranking but do not decide feasibility");
        var idea = result.feasible().getFirst();
        int n = result.ideas().indexOf(idea) + 1;
        var view = DiscoveryPresentation.view(idea, app.localization());
        var weather = view.checks().stream().filter(c -> c.shown().contains(t("discover.check.env.warn.thunder"))).findFirst()
            .orElseThrow(() -> new AssertionError("no thunderstorm warning in " + view.lines()));
        assertEquals(Feasibility.FEASIBLE, weather.status(), "the check itself still passes");
        var warnings = List.of(t("discover.check.env.warn.thunder"),
            weather.shown().stream().filter(s -> s.contains("precipitation probability up to 90")).findFirst().orElseThrow(() -> new AssertionError(weather.shown().toString())),
            weather.shown().stream().filter(s -> s.contains("wind gusts up to 85")).findFirst().orElseThrow(() -> new AssertionError(weather.shown().toString())));
        var card = find("discover-idea-" + n);
        var visible = fx(() -> visibleTexts(card));
        assertTrue(visible.contains(weather.name()) && visible.contains(weather.statusText()), "the passing check row: " + visible);
        for (String warning : warnings) {
            assertTrue(visible.contains(warning), "visible on the feasible card, outside any collapsed section: " + warning + " in " + visible);
            assertTrue(fx(() -> labels(card).stream().filter(l -> warning.equals(l.getText())).allMatch(l ->
                    !insideDisclosure(l) && l.getParent().getStyleClass().contains("discover-caution") && within(l, node("discover-checked-" + n)))),
                "with a warning shape, under its check in Checked by PhotoPilot: " + warning);
        }
        var details = find("discover-idea-details-" + n);
        if (details != null) {
            var hidden = fx(() -> labels(((TitledPane) details).getContent()).stream().map(Labeled::getText).toList());
            for (String warning : warnings) assertFalse(hidden.contains(warning), "not repeated in the closed Details: " + warning);
        }
        fx(() -> { var region = (Region) card; region.applyCss(); region.layout(); var parameters = new SnapshotParameters(); parameters.setFill(LIGHT);
            CalculatorGuiTest.write(region.snapshot(parameters, null), "f02-guided-warnings-en"); return true; });
        fx(() -> { LanguageLayoutGuiTest.check(pane, "stormy results 1366x768"); return true; });
    }

    @Test void evidenceTheProvidersDidNotSupplyStaysVisibleWithTheResults() throws Exception {
        open(1366, 768, true);
        providers.forecast = q -> forecast(q, Map.of(), Set.of("precipitation_probability", "visibility", "wind_gusts_10m"));
        discover();
        var gaps = DiscoveryPresentation.gaps(fx(() -> pane.result()), app.localization());
        assertEquals(1, gaps.size(), gaps::toString);
        assertTrue(gaps.getFirst().startsWith("Not supplied by the providers:"), gaps::toString);
        assertTrue(shown("discover-gaps"), "the gap is listed with the results");
        assertTrue(fx(() -> !insideDisclosure(node("discover-gaps")) && within(node("discover-gaps"), node("discover-about"))), "in About these results, not in a closed section");
        assertTrue(fx(() -> visibleTexts(node("discover-about"))).containsAll(gaps));
        assertTrue(fx(() -> node("discover-gaps").lookupAll(".discover-caution").size()) == gaps.size(), "each gap has the warning shape");
        assertTrue(label("discover-context").contains(gaps.getFirst()), "the closed Context used keeps it too");
    }

    @Test void openedSectionsStayOpenAcrossSavesTabSwitchesAndReturns() throws Exception {
        boot(true);
        assertTrue(app.facade().sessions().create("Lakeside evening", day).isSuccess());
        show(1366, 768);
        discover();
        var opened = List.of("discover-idea-details-1", "discover-rejected-details", "discover-context-details", "discover-gear-details");
        for (String id : opened) expand(id);
        click("discover-step-1");
        assertEquals(List.of(true, true), connectors(), "with results both lines are done, also on step 1");
        click("discover-step-3");

        // Save idea 2 from the keyboard: the sessions reload and the cards are re-rendered.
        action(() -> node("discover-save-2").requestFocus());
        assertEquals("discover-save-2", focusOwner());
        click("discover-save-2");
        assertTrue(disabled("discover-save-2"), "the card says it is saved");
        for (String id : opened) assertTrue(expanded(id), id + " stays open after Save");
        assertEquals("discover-continue-2", focusOwner(), "keyboard focus returns to the saved card's next action, not the sidebar");

        click("discover-tab-saved"); click("discover-tab-results");
        for (String id : opened) assertTrue(expanded(id), id + " stays open after switching tabs");
        action(() -> pane.activate(true)); ready();
        for (String id : opened) assertTrue(expanded(id), id + " stays open when the page is opened again");
        action(() -> ((CheckBox) node("discover-new-session")).setSelected(true));
        for (String id : opened) assertTrue(expanded(id), id + " stays open when the target changes");
        action(() -> ((TitledPane) node("discover-context-details")).setExpanded(false));
        click("discover-tab-saved"); click("discover-tab-results");
        assertFalse(expanded("discover-context-details"), "a section the user closed stays closed");

        // New results start with their sections closed; the equipment list is not part of them.
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        click("discover-rediscover");
        assertNotNull(fx(() -> pane.result()));
        for (String id : List.of("discover-idea-details-1", "discover-rejected-details")) assertFalse(expanded(id), id + " starts closed for new results");
        assertTrue(expanded("discover-gear-details"));
    }

    @Test void aListProblemIsShownAndFocusedAtTheListToCorrect() throws Exception {
        open(1366, 768, false);
        var form = new LinkedHashMap<>(DiscoveryFixtures.form(day));
        form.put("interests", "");
        form.put("avoid", String.join("; ", java.util.stream.IntStream.rangeClosed(1, 13).mapToObj(i -> "subject " + i).toList()));
        fill(form);
        click("discover-next");
        assertEquals(Stage.PREFERENCES, stage());
        click("discover-generate");
        assertEquals(Stage.PREFERENCES, stage(), "the problem is on step 2");
        assertTrue(gemini.requests.isEmpty(), "nothing is sent");
        String message = label("discover-error-lists-avoid");
        assertTrue(message.startsWith("Interests and avoided subjects: at most 12 entries"), message);
        assertTrue(shown("discover-error-lists-avoid"), "the message is under the Avoid field");
        assertFalse(shown("discover-error-lists"), "not under the untouched interests");
        assertTrue(invalid("discover-avoid"));
        assertFalse(invalid("discover-interests"), "the interests are not marked");
        assertEquals("discover-avoid", focusOwner(), "focus goes to the list to correct");
        assertEquals(message, fx(() -> node("discover-avoid").getAccessibleHelp()));
        assertNull(fx(() -> node("discover-interests").getAccessibleHelp()));
        assertTrue(fx(() -> { var input = node("discover-avoid").localToScene(node("discover-avoid").getBoundsInLocal());
            var text = node("discover-error-lists-avoid").localToScene(node("discover-error-lists-avoid").getBoundsInLocal());
            return text.getMinY() >= input.getMaxY() - 0.5 && text.getMinY() - input.getMaxY() < 40; }), "directly under the Avoid field");
        snapshot("f02-guided-avoid-error-en");
        text("discover-avoid", "crowds");
        assertFalse(shown("discover-error-lists-avoid"), "editing the list clears its message");
        assertFalse(invalid("discover-avoid"));

        // Too many interests: the message and focus are at the interests instead.
        text("discover-interests", String.join("; ", java.util.stream.IntStream.rangeClosed(1, 13).mapToObj(i -> "theme " + i).toList()));
        click("discover-generate");
        assertTrue(shown("discover-error-lists"));
        assertFalse(shown("discover-error-lists-avoid"));
        assertTrue(invalid("discover-interests"));
        assertFalse(invalid("discover-avoid"));
        assertEquals("discover-interests", focusOwner());
        assertTrue(gemini.requests.isEmpty());
    }

    @Test void aFailedRequestHasItsOwnStateAndTryAgainRecovers() throws Exception {
        open(1366, 768, true);
        fill(DiscoveryFixtures.form(day));
        gemini.status(503, "");
        click("discover-generate");
        assertEquals(Stage.REVIEW, stage());
        assertTrue(shown("discover-error"));
        assertTrue(label("discover-error-text").contains("No ideas were generated and none were invented"), says(() -> label("discover-error-text")));
        assertNull(fx(() -> pane.result()));
        assertEquals(DiscoveryFixtures.PLACE, field("discover-location"), "inputs are kept");
        // The request's finished progress stays in the failure card with its fallback: the manual path in Shooting Requirements.
        var progress = fx(() -> node("discover-error").lookup("#operation-progress"));
        assertNotNull(progress, "the request's progress view is in the failure card");
        assertTrue(fx(() -> CalculatorGuiTest.visible(progress)));
        var next = fx(() -> progress.lookup("#progress-next")); var openRequirements = fx(() -> progress.lookup("#progress-open"));
        assertTrue(fx(() -> CalculatorGuiTest.visible(next) && CalculatorGuiTest.visible(openRequirements)), "the next step and its Open action are visible");
        assertEquals(t("progress.next", t("next.manual.discover")), fx(() -> ((Labeled) next).getText()));
        assertEquals("Next: Retry, or describe the shoot in Shooting Requirements.", fx(() -> ((Labeled) next).getText()));
        assertEquals(t("progress.open", t("feature.F03.title")), fx(() -> ((Labeled) openRequirements).getText()));
        assertEquals(0L, fx(() -> pane.lookupAll(".progress-indicator").stream().filter(CalculatorGuiTest::visible).count()), "no spinner is left running once the request failed");
        picture("f02-guided-error-en", LIGHT);
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        click("discover-retry");
        assertNotNull(fx(() -> pane.result()));
        assertFalse(shown("discover-error"));
        assertTrue(shown("discover-idea-1"));

        // A problem the service finds in an input (a past date) is also shown next to that input.
        int sent = gemini.requests.size();
        date(day.minusDays(30).toString());
        click("discover-generate");
        assertTrue(shown("discover-error"));
        assertEquals(sent, gemini.requests.size(), "a past date never reaches Gemini");
        assertEquals("discover-retry", focusOwner(), "focus moves to Try again when the request fails");
        click("discover-error-edit");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(shown("discover-error-date"), "the message is next to the date");
        assertEquals("discover-date", fx(() -> { var owner = workspace.getScene().getFocusOwner(); return owner.getParent() != null && owner.getParent().getId() != null ? owner.getParent().getId() : owner.getId(); }),
            "and the date has keyboard focus");
        // Only the service can see that the date is past, so its message stays next to the unchanged date until it is edited.
        click("discover-next");
        assertEquals(Stage.PREFERENCES, stage(), "the form itself accepts the date");
        click("discover-back");
        assertTrue(shown("discover-error-date"), "the service's message is not cleared by navigation");
        date(day.toString());
        assertFalse(shown("discover-error-date"), "editing the date clears it");
    }

    @Test void aCancelledRequestHasItsOwnStateWithTryAgain() throws Exception {
        open(1366, 768, true);
        fill(DiscoveryFixtures.form(day));
        var release = new CountDownLatch(1);
        gemini.replyWith(body -> { try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return DiscoveryFixtures.acceptanceReply(day); });
        fire("discover-generate");
        waitFor(() -> gemini.requests.size() == 1, "the Gemini request");
        assertTrue(fx(() -> pane.tracked().cancel()), "the request can be cancelled while it waits for Gemini");
        release.countDown();
        ready();
        assertEquals(Stage.REVIEW, stage());
        assertTrue(shown("discover-error"), "a cancelled request has its own state");
        assertEquals("Request cancelled", label("discover-error-title"));
        assertTrue(label("discover-error-text").contains("none were invented"), says(() -> label("discover-error-text")));
        assertFalse(shown("discover-results-empty"), "not the 'no recommendations yet' start state");
        assertNull(fx(() -> pane.result())); assertNull(find("discover-idea-1"));
        assertTrue(shown("discover-retry") && shown("discover-error-edit"));
        assertEquals("discover-retry", focusOwner());
        assertEquals(DiscoveryFixtures.PLACE, field("discover-location"), "inputs are kept");
        var next = fx(() -> node("discover-error").lookup("#progress-next"));
        assertNotNull(next, "the cancelled request's progress stays in its card");
        assertTrue(fx(() -> CalculatorGuiTest.visible(next)));
        assertEquals(t("progress.next", t("next.cancelled")), fx(() -> ((Labeled) next).getText()));
        picture("f02-guided-cancelled-en", LIGHT);
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        click("discover-retry");
        assertNotNull(fx(() -> pane.result()), says(() -> fx(() -> pane.feedbackText())));
        assertFalse(shown("discover-error"));
        assertTrue(shown("discover-idea-1"));
    }

    @Test void savedIdeasCanBeBrowsedBeforeAnyRequestWithoutContactingGemini() throws Exception {
        boot(true);
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var request = DiscoveryForm.parse(DiscoveryFixtures.form(day), app.facade().discovery().location(), Optional.empty()).value().orElseThrow();
        var found = app.facade().discover(request).value().orElseThrow();
        var stored = app.facade().discovery().saveInNewSession(found.ideas().getFirst()).value().orElseThrow();
        assertTrue(app.facade().sessions().create("Empty session", day).isSuccess());
        int geminiBefore = gemini.requests.size(), providersBefore = providers.requests.size();
        show(1366, 768);

        click("discover-open-saved");
        assertEquals(Stage.REVIEW, stage());
        assertTrue(shown("discover-saved-view") && !shown("discover-results-view"));
        action(() -> { var box = (ComboBox<com.photopilot.model.PhotographySession>) node("discover-saved-session");
            box.setValue(box.getItems().stream().filter(s -> s.id().equals(stored.id())).findFirst().orElseThrow()); });
        assertTrue(label("discover-saved").contains("Aircraft departures at the island airport (Feasible when suggested"), says(() -> label("discover-saved")));
        assertTrue(shown("discover-saved-idea-1"));
        var savedTexts = fx(() -> labels(node("discover-saved-idea-1")).stream().map(Labeled::getText).toList());
        assertTrue(savedTexts.containsAll(found.ideas().getFirst().limitations()), "every stored limitation is visible");
        picture("f02-guided-saved-en", LIGHT);
        click("discover-saved-continue-1");
        assertEquals(List.of(stored.id()), handedOff, "a saved idea continues in Requirements for its session");

        action(() -> { var box = (ComboBox<com.photopilot.model.PhotographySession>) node("discover-saved-session");
            box.setValue(box.getItems().stream().filter(s -> s.title().equals("Empty session")).findFirst().orElseThrow()); });
        assertTrue(shown("discover-saved-empty"));
        assertEquals(app.localization().text("discover.saved.none"), label("discover-saved"));
        assertEquals(geminiBefore, gemini.requests.size(), "browsing saved ideas never asks Gemini");
        assertEquals(providersBefore, providers.requests.size(), "nor any provider");
    }

    @Test void anIdeaCanBeSavedIntoAnExistingSessionOrANewOne() throws Exception {
        boot(true);
        var lakeside = app.facade().sessions().create("Lakeside evening", day).value().orElseThrow();
        show(1366, 768);
        discover();
        assertFalse(fx(() -> ((CheckBox) node("discover-new-session")).isSelected()), "an editable session exists, so it is the target");
        assertEquals(lakeside.id(), fx(() -> ((ComboBox<com.photopilot.model.PhotographySession>) node("discover-session")).getValue().id()));
        click("discover-save-1");
        assertTrue(fx(() -> pane.feedbackText().contains("was saved in Lakeside evening")), says(() -> fx(() -> pane.feedbackText())));
        var first = fx(() -> pane.result()).ideas().getFirst();
        assertEquals(first.id(), app.facade().sessions().open(lakeside.id()).value().orElseThrow().savedIdeas().getFirst().id());
        assertTrue(disabled("discover-save-1"), "the card says it is saved");
        click("discover-tab-saved");
        assertTrue(label("discover-saved").contains(first.subject()), "the saved tab shows the session it was saved in");
        click("discover-tab-results");
        action(() -> ((CheckBox) node("discover-new-session")).setSelected(true));
        assertTrue(disabled("discover-session"));
        click("discover-save-2");
        assertEquals(2, app.facade().sessions().search("").value().orElseThrow().size(), "a new draft session was created for the idea");
    }

    @Test void aResetOrDisposedRequestNeverShowsItsLateResult() throws Exception {
        open(1366, 768, true);
        fill(DiscoveryFixtures.form(day));
        var release = new CountDownLatch(1);
        gemini.replyWith(body -> { try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return DiscoveryFixtures.acceptanceReply(day); });
        fire("discover-generate");
        waitFor(() -> gemini.requests.size() == 1, "the Gemini request");
        var running = fx(() -> pane.tracked().current());
        assertFalse(disabled("discover-reset"), "a running request can be abandoned by Reset");
        // Reset abandons the request. Until it has ended, Discover says why it is unavailable: in words next to it and to
        // assistive technology. Checked in the same UI pulse as the clicks (the cancelled call can end at once): step 1
        // filled in again, Next, the state of step 2, then Reset once more to return to the reset inputs.
        var form = DiscoveryFixtures.form(day);
        var finishing = fx(() -> {
            ((ButtonBase) node("discover-reset")).fire();
            var problems = new ArrayList<String>();
            if (!node("discover-abandon-status").isVisible()) problems.add("no status after Reset");
            for (String key : List.of("location", "latitude", "longitude", "from", "until", "distance")) ((TextField) node("discover-" + key)).setText(form.get(key));
            var picker = (DatePicker) node("discover-date"); picker.getEditor().setText(day.toString()); picker.setValue(day);
            ((ButtonBase) node("discover-next")).fire();
            if (pane.stage() != Stage.PREFERENCES) problems.add("not on step 2: " + pane.stage());
            if (!node("discover-generate").isDisabled()) problems.add("Discover is enabled while the abandoned request is still ending");
            if (!CalculatorGuiTest.visible(node("discover-abandon-status"))) problems.add("the status is not shown on step 2");
            if (!t("discover.guided.abandoning").equals(((Labeled) node("discover-abandon-text")).getText())) problems.add("status text: " + ((Labeled) node("discover-abandon-text")).getText());
            if (!t("discover.guided.abandoning").equals(node("discover-generate").getAccessibleHelp())) problems.add("accessible help: " + node("discover-generate").getAccessibleHelp());
            var page = (Region) pane.getParent(); page.applyCss(); page.layout();
            var status = node("discover-abandon-status").localToScene(node("discover-abandon-status").getBoundsInLocal());
            var footer = node("discover-footer-2").localToScene(node("discover-footer-2").getBoundsInLocal());
            if (status.getMaxY() > footer.getMinY() + 0.5 || footer.getMinY() - status.getMaxY() > 40) problems.add("not directly above the Discover footer: " + status + " / " + footer);
            var parameters = new SnapshotParameters(); parameters.setFill(LIGHT);
            CalculatorGuiTest.write(page.snapshot(parameters, null), "f02-guided-abandoning-en");
            ((ButtonBase) node("discover-reset")).fire();
            return problems;
        });
        ready();
        assertEquals(List.of(), finishing, "while the abandoned request is still ending");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertEquals("", field("discover-location"));
        assertEquals("Inputs reset.", fx(() -> pane.feedbackText()));
        release.countDown();
        waitFor(() -> fx(running::finished), "the abandoned request to end");
        settle();
        waitFor(() -> fx(() -> !node("discover-abandon-status").isVisible()), "the status to clear when the abandoned request ended");
        assertFalse(disabled("discover-generate"), "Discover is available again");
        assertNull(fx(() -> node("discover-generate").getAccessibleHelp()));
        assertNull(fx(() -> pane.result()), "the late result is not attached to the reset page");
        assertNull(find("discover-idea-1"));
        assertEquals(Stage.LOCATION_TIME, stage());
        assertEquals("Inputs reset.", fx(() -> pane.feedbackText()));
        assertTrue(disabled("discover-step-3"));

        fill(DiscoveryFixtures.form(day));
        var second = new CountDownLatch(1);
        gemini.replyWith(body -> { try { second.await(20, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return DiscoveryFixtures.acceptanceReply(day); });
        fire("discover-generate");
        waitFor(() -> gemini.requests.size() == 2, "the second Gemini request");
        var disposed = fx(() -> pane.tracked().current());
        action(() -> pane.dispose());
        second.countDown();
        waitFor(() -> fx(disposed::finished), "the disposed page's request to end");
        settle();
        assertNull(fx(() -> pane.result()), "a disposed page ignores every completion");
    }

    @Test void changedConditionsAreFlaggedAndCanBeDiscoveredAgain() throws Exception {
        open(1366, 768, true);
        discover();
        assertFalse(shown("discover-stale"));
        click("discover-edit-conditions");
        text("discover-distance", "30");
        click("discover-step-3");
        assertEquals(Stage.REVIEW, stage(), "results stay reachable while the inputs are valid");
        assertTrue(shown("discover-stale"), "results for older conditions are labelled as such");
        assertTrue(inViewport("discover-stale"), "the banner is seen without scrolling");
        assertFalse(shown("discover-feedback"), "the older results' success message is not repeated above it");
        snapshot("f02-guided-stale-en");
        assertEquals("40 km straight-line", fx(() -> ((Labeled) ((javafx.scene.layout.Pane) node("discover-condition-distance")).getChildren().get(1)).getText()),
            "the summary shows the conditions of the shown results, not the edit");
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        click("discover-rediscover");
        assertEquals(30.0, fx(() -> pane.result()).context().maxDistanceKm(), 0.001);
        assertFalse(shown("discover-stale"));
        assertEquals("30 km straight-line", fx(() -> ((Labeled) ((javafx.scene.layout.Pane) node("discover-condition-distance")).getChildren().get(1)).getText()));

        // Opening a later step re-validates the inputs being left.
        click("discover-step-1");
        text("discover-distance", "far");
        click("discover-step-3");
        assertEquals(Stage.LOCATION_TIME, stage());
        assertTrue(shown("discover-error-distance"));
    }

    // ------------------------------------------------------------------ languages, layout, appearance, profiles

    @Test void bothLanguagesAndCommonWindowSizesKeepEveryStageInsideTheWindow() throws Exception {
        open(1366, 768, true);
        fill(DiscoveryFixtures.form(day));
        chip("aviation", true); chip("landscape", true); text("discover-interests", "waterfalls");
        // Stage pictures for review: stage 1, stage 2 and the results, in English and Chinese.
        for (String language : List.of("en", "zh-CN")) {
            action(() -> app.localization().useLanguage(language));
            String tag = language.equals("en") ? "en" : "zh";
            click("discover-step-1"); snapshot("f02-guided-stage1-" + tag + "-1366"); picture("f02-guided-page1-" + tag, Color.rgb(242, 245, 254));
            click("discover-next"); snapshot("f02-guided-stage2-" + tag + "-1366"); picture("f02-guided-page2-" + tag, Color.rgb(242, 245, 254));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            click("discover-generate");
            assertNotNull(fx(() -> pane.result()));
            snapshot("f02-guided-stage3-" + tag + "-1366"); picture("f02-guided-page3-" + tag, Color.rgb(242, 245, 254));
            if (tag.equals("zh")) {
                // The request summary names the chosen categories as their chips do, never the request's English terms.
                String interests = fx(() -> ((Labeled) ((javafx.scene.layout.Pane) node("discover-condition-interests")).getChildren().get(1)).getText());
                assertTrue(interests.contains("风光") && interests.contains("航空") && interests.contains("waterfalls"), interests);
                assertFalse(interests.toLowerCase(Locale.ROOT).contains("landscape") || interests.toLowerCase(Locale.ROOT).contains("aviation"), interests);
                fx(() -> { var summary = (Region) node("discover-request-summary"); summary.applyCss(); summary.layout();
                    var parameters = new SnapshotParameters(); parameters.setFill(LIGHT);
                    CalculatorGuiTest.write(summary.snapshot(parameters, null), "f02-guided-summary-zh"); return true; });
            }
        }
        // Chinese headings.
        assertEquals("今天，去拍点什么？", fx(() -> labels(pane).stream().filter(l -> l.getStyleClass().contains("discover-title")).findFirst().orElseThrow().getText()));
        var stageTitles = fx(() -> labels(pane).stream().filter(l -> l.getStyleClass().contains("discover-step-title")).map(Labeled::getText).toList());
        assertEquals(List.of("地点与时间", "拍摄偏好", "灵感推荐"), stageTitles);
        assertTrue(accessible("discover-idea-1").contains("直线距离"));

        for (String language : List.of("zh-CN", "en")) {
            action(() -> app.localization().useLanguage(language));
            settle();
            // The four common sizes, plus the application's minimum window (880 x 600).
            for (int[] size : new int[][] { {1280, 720}, {1366, 768}, {1920, 1080}, {2560, 1440}, {880, 600} }) {
                for (String step : List.of("discover-step-1", "discover-step-2", "discover-step-3")) {
                    fire(step);
                    String where = language + " " + size[0] + "x" + size[1] + " " + step;
                    offscreen(size[0], size[1], where, !language.equals("en") ? null : size[0] == 1920 && step.endsWith("3") ? "f02-guided-stage3-en-1920"
                        : size[0] == 880 ? "f02-guided-stage" + step.substring(step.length() - 1) + "-en-880" : size[0] == 2560 && step.endsWith("1") ? "f02-guided-stage1-en-2560" : null);
                }
            }
        }
        // A high-density rendering of the results (2x), as on a 200 % display.
        fx(() -> { workspace.applyCss(); workspace.layout(); var parameters = new SnapshotParameters(); parameters.setTransform(Transform.scale(2, 2));
            CalculatorGuiTest.write(workspace.snapshot(parameters, null), "f02-guided-stage3-en-1366-2x"); return true; });

        // Dark mode: the results and the first stage.
        action(() -> ThemeManager.get().select(Theme.Mode.DARK));
        settle();
        fire("discover-step-3"); snapshot("f02-guided-stage3-dark"); picture("f02-guided-page3-dark", Color.rgb(24, 26, 33));
        fire("discover-step-1"); snapshot("f02-guided-stage1-dark");
        fire("discover-step-2"); snapshot("f02-guided-stage2-dark");
    }
    /** Lays the workspace out in an unshown scene of exactly {@code width} x {@code height} (no screen limit) and checks it. */
    void offscreen(int width, int height, String where, String snapshot) throws Exception {
        fx(() -> {
            var shownScene = workspace.getScene();
            shownScene.setRoot(new Region());
            try {
                for (int tall : new int[] { height, 6000 }) {
                    var scene = new Scene(workspace, width, tall); scene.getStylesheets().add(CSS); ThemeManager.get().style(scene.getStylesheets());
                    for (int pass = 0; pass < 3; pass++) { workspace.applyCss(); workspace.layout(); }
                    var content = (ScrollPane) workspace.lookup(".content-scroll");
                    var surface = content.getContent();
                    assertTrue(surface.getLayoutBounds().getWidth() <= content.getViewportBounds().getWidth() + 0.5,
                        where + ": content " + surface.getLayoutBounds().getWidth() + " wider than the viewport " + content.getViewportBounds().getWidth());
                    assertTrue(pane.getWidth() <= 1180 + 0.5, where + ": bounded reading width");
                    LanguageLayoutGuiTest.check(pane, where);
                    for (var input : pane.lookupAll(".text-field")) if (CalculatorGuiTest.visible(input) && input.getParent() instanceof javafx.scene.layout.Pane)
                        assertTrue(((Region) input).getWidth() >= 60, where + ": " + input.getId() + " is " + ((Region) input).getWidth() + " px wide");
                    for (var text : labels(pane)) if (CalculatorGuiTest.visible(text) && text.getText() != null) assertFalse(text.getText().contains("[!"), where + ": " + text.getText());
                    if (snapshot != null && tall == height) CalculatorGuiTest.snapshot(workspace, snapshot);
                    scene.setRoot(new Region());
                }
            } finally { shownScene.setRoot(workspace); }
            return true;
        });
    }

    @Test void theRealMinimumWindowWithLargeBoldTextKeepsEveryActionWhole() throws Exception {
        open(1366, 768, true);
        discover();
        resize(880, 600);
        var appearance = ThemeManager.get();
        int originalSize = fx(appearance::fontSize); boolean originalBold = fx(appearance::boldText);
        try {
            action(() -> { appearance.previewFontSize(125); appearance.selectBoldText(true); });
            settle();
            for (String language : List.of("en", "zh-CN")) {
                action(() -> app.localization().useLanguage(language));
                settle();
                for (String step : List.of("discover-step-1", "discover-step-2", "discover-step-3")) {
                    fire(step); settle();
                    String where = language + " 880x600 window, Font Size 125% and Bold Text, " + step;
                    fx(() -> {
                        workspace.applyCss(); workspace.layout();
                        var content = (ScrollPane) workspace.lookup(".content-scroll");
                        assertTrue(content.getContent().getLayoutBounds().getWidth() <= content.getViewportBounds().getWidth() + 0.5,
                            where + ": content " + content.getContent().getLayoutBounds().getWidth() + " wider than the viewport " + content.getViewportBounds().getWidth());
                        LanguageLayoutGuiTest.check(pane, where);
                        for (var footer : pane.lookupAll(".discover-footer")) if (CalculatorGuiTest.visible(footer)) footerFits((Region) footer, where);
                        return true;
                    });
                }
                if (language.equals("en")) {
                    fire("discover-step-2"); picture("f02-guided-page2-en-min-large", LIGHT);
                    fire("discover-step-1"); picture("f02-guided-page1-en-min-large", LIGHT);
                }
            }
        } finally { action(() -> { appearance.previewFontSize(originalSize); appearance.selectBoldText(originalBold); app.localization().useLanguage("en"); }); }
    }

    @Test void theDatePopupFollowsTheAppLanguageAndRestoresTheSystemFormat() throws Exception {
        open(1366, 768, false);
        var original = Locale.getDefault(Locale.Category.FORMAT);
        action(() -> { app.localization().useLanguage("zh-CN"); ThemeManager.get().select(Theme.Mode.DARK); });
        settle();
        action(() -> ((DatePicker) node("discover-date")).show());
        settle();
        var chinese = fx(() -> labels(datePopup()).stream().map(Labeled::getText).filter(Objects::nonNull).toList());
        assertTrue(chinese.stream().anyMatch(t -> t.contains("月")), "month names in Chinese: " + chinese);
        assertTrue(chinese.stream().noneMatch(t -> t.matches(".*[A-Za-z]{3,}.*")), "no English month or weekday names: " + chinese);
        fx(() -> { var popup = datePopup(); popup.applyCss(); popup.layout(); CalculatorGuiTest.write(popup.snapshot(null, null), "f02-guided-date-popup-zh-dark"); return true; });
        // A flat grid: day cells draw no border of their own, only today keeps its accent ring.
        var bordered = fx(() -> datePopup().lookupAll(".day-cell").stream().filter(cell -> !cell.getStyleClass().contains("today")).map(cell -> ((Region) cell).getBorder())
            .filter(border -> border != null && border.getStrokes().stream().anyMatch(stroke -> java.util.stream.Stream.of(stroke.getTopStroke(), stroke.getRightStroke(), stroke.getBottomStroke(), stroke.getLeftStroke())
                .anyMatch(paint -> !(paint instanceof Color color) || color.getOpacity() > 0))).count());
        assertEquals(0L, bordered, "no day cell draws a grid line in Dark mode");
        assertTrue(fx(() -> datePopup().lookupAll(".today").stream().anyMatch(cell -> ((Region) cell).getBorder() != null && !((Region) cell).getBorder().getStrokes().isEmpty())),
            "today keeps its accent ring");
        action(() -> ((DatePicker) node("discover-date")).hide());
        assertEquals(original, Locale.getDefault(Locale.Category.FORMAT), "the system format locale is restored when the popup closes");

        action(() -> { app.localization().useLanguage("en"); ThemeManager.get().select(Theme.Mode.LIGHT); });
        settle();
        action(() -> ((DatePicker) node("discover-date")).show());
        settle();
        var english = fx(() -> labels(datePopup()).stream().map(Labeled::getText).filter(Objects::nonNull).toList());
        String month = LocalDate.now().getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
        assertTrue(english.stream().anyMatch(t -> t.equalsIgnoreCase(month)), "rebuilt in English after the language changed: " + english);
        assertTrue(english.stream().noneMatch(t -> t.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)), english::toString);
        fx(() -> { var popup = datePopup(); popup.applyCss(); popup.layout(); CalculatorGuiTest.write(popup.snapshot(null, null), "f02-guided-date-popup-en-light"); return true; });
        action(() -> ((DatePicker) node("discover-date")).hide());
        assertEquals(original, Locale.getDefault(Locale.Category.FORMAT));
    }

    @Test void eachProfileShowsOnlyItsOwnInventoryAndResults() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        app = new ApplicationBootstrap(providers.environment(gemini.environment(root.resolve("a"))));
        try (var other = new ApplicationBootstrap(providers.environment(gemini.environment(root.resolve("b"))))) {
            for (var item : new GearFixtures.Airshow().items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            DiscoveryPane[] panes = new DiscoveryPane[2];
            action(() -> {
                panes[0] = new DiscoveryPane(app.facade(), executor, app.localization(), (id, draft) -> { });
                panes[1] = new DiscoveryPane(other.facade(), executor, other.localization(), (id, draft) -> { });
                for (var p : panes) { var scene = new Scene(p, 1200, 900); scene.getStylesheets().add(CSS); p.applyCss(); p.activate(true); }
            });
            waitFor(() -> fx(() -> !panes[0].busy() && !panes[1].busy()), "both inventories");
            Thread.sleep(100);
            waitFor(() -> fx(() -> !panes[0].busy() && !panes[1].busy()), "both session lists");
            String a = fx(() -> { panes[0].applyCss(); return ((Labeled) panes[0].lookup("#discover-gear-list")).getText(); });
            String b = fx(() -> { panes[1].applyCss(); return ((Labeled) panes[1].lookup("#discover-gear-list")).getText(); });
            assertTrue(a.contains("11 item(s) recorded"), a);
            assertEquals(other.localization().text("discover.gear.empty"), b);
            assertNotNull(fx(() -> panes[0].lookup("#discover-gear-CAMERA")));
            assertNull(fx(() -> panes[1].lookup("#discover-gear-CAMERA")), "the other profile's gear never appears");
            pane = panes[0];
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            fill(DiscoveryFixtures.form(day));
            click("discover-generate");
            assertNotNull(fx(() -> panes[0].result()));
            assertNull(fx(() -> panes[1].result()), "results stay with the profile that asked");
            action(() -> panes[1].dispose());
        }
    }
}
