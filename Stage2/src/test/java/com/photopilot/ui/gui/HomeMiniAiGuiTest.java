package com.photopilot.ui.gui;

import com.google.gson.JsonParser;
import com.photopilot.application.ProfileManager;
import com.photopilot.matching.GearFixtures;
import com.photopilot.requirement.FakeGemini;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Home Mini AI end to end through the real workspace, MiniAiService, shared model port and
 * GeminiAdapter, against a loopback stand-in for Gemini (FakeGemini) with a fake key: nothing reaches a live provider.
 */
class HomeMiniAiGuiTest {
    @TempDir Path root;
    FakeGemini gemini; ProfileManager manager; GuiProfileController controller; Stage stage;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @BeforeEach void server() throws Exception { gemini = new FakeGemini(); }
    /** Opens the workspace for {@code environment}, in English at the reference window size. */
    void launch(Map<String, String> environment) throws Exception {
        manager = new ProfileManager(environment);
        var initial = manager.activeProfile().value().orElseThrow();
        action(() -> { ThemeManager.get().select(Theme.Accent.BLUE); ThemeManager.get().select(Theme.Mode.LIGHT);
            stage = new Stage(); controller = new GuiProfileController(stage, manager, initial, context -> { }); });
        idle();
        action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle();
        action(() -> manager.activeContext().localization().selectLanguage("en"));
        size(1430, 1055);
        assertTrue(fx(() -> controller.workspace().atHome()));
    }
    void launch() throws Exception { launch(gemini.environment(root)); }
    @AfterEach void close() throws Exception {
        action(() -> { ThemeManager.get().select(Theme.Accent.BLUE); ThemeManager.get().select(Theme.Mode.SYSTEM);
            if (controller != null) controller.close(); if (stage != null) stage.close(); });
        if (manager != null) manager.close();
        gemini.close();
    }

    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    void size(double width, double height) throws Exception { action(() -> { stage.setWidth(width); stage.setHeight(height); }); idle(); }
    /** Direct lookup; call it on the FX thread (inside fx/action). */
    Node node(String id) { return controller.workspace().lookup("#" + id); }
    TextArea prompt() { return (TextArea) node("home-ai-prompt"); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) node(id)).getText()); }
    boolean shown(String id) throws Exception { return fx(() -> node(id).isVisible() && node(id).isManaged()); }
    void type(String value) throws Exception { action(() -> { prompt().setText(value); prompt().end(); }); }
    void click(String id) throws Exception { action(() -> ((Button) node(id)).fire()); }
    void key(KeyCode code, boolean shift) throws Exception {
        action(() -> Event.fireEvent(prompt(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false)));
    }
    String t(String key, Object... args) { return manager.activeContext().localization().text(key, args); }
    /** The user-message parts of a recorded request: evidence first (when sent), then the question. */
    static List<String> parts(String body) {
        return JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts")
            .asList().stream().map(part -> part.getAsJsonObject().get("text").getAsString()).toList();
    }
    void awaitRequests(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (gemini.requests.size() < count) { if (System.nanoTime() > deadline) fail("no request arrived"); Thread.sleep(20); }
    }

    @Test void answersFollowUpsAndClearingUseTheConfiguredModelWithBoundedContext() throws Exception {
        launch();
        assertFalse(shown("home-ai-response") || shown("home-ai-status"), "no empty conversation panel before the first question");

        // Enter sends the question; the answer appears below the controls and the input is cleared for a follow-up.
        gemini.reply("Try photographing city lights and reflections.");
        type("What should I shoot tonight?"); key(KeyCode.ENTER, false); idle();
        assertEquals(1, gemini.requests.size());
        var first = parts(gemini.requests.getFirst());
        assertEquals("What should I shoot tonight?", first.getLast());
        assertFalse(first.getFirst().contains("\"conversation\""), "the first question has no earlier context");
        assertTrue(gemini.requests.getFirst().contains("PhotoPilot Mini AI") && gemini.requests.getFirst().contains("\"maxOutputTokens\":1024"));
        assertTrue(shown("home-ai-response")); assertFalse(shown("home-ai-status"));
        assertEquals(t("home.ai.response.title"), text("home-ai-response-title"));
        assertEquals("Try photographing city lights and reflections.", text("home-ai-answer"));
        assertEquals("What should I shoot tonight?", text("home-ai-asked"), "the question as a context line, no chat-style prefix");
        assertEquals("", fx(() -> prompt().getText()));
        assertFalse(shown("home-ai-history-toggle"), "nothing earlier to show yet");

        // A follow-up carries the earlier exchange; the latest answer replaces the shown one and the earlier one is collapsed.
        gemini.reply("A 50mm lens is great for isolating lit storefronts and reflections. Focus on strong compositions rather than the whole skyline.");
        type("What if I only have a 50mm lens?"); click("home-ai-generate"); idle();
        var second = parts(gemini.requests.get(1));
        assertTrue(second.getFirst().contains("What should I shoot tonight?") && second.getFirst().contains("Try photographing city lights and reflections."),
            "the follow-up includes the previous exchange: " + second.getFirst());
        assertEquals("What if I only have a 50mm lens?", second.getLast());
        assertTrue(text("home-ai-answer").startsWith("A 50mm lens"));
        assertTrue(shown("home-ai-history-toggle")); assertFalse(shown("home-ai-history"));
        assertEquals(t("home.ai.history.show", 1), text("home-ai-history-toggle"));
        click("home-ai-history-toggle");
        assertTrue(shown("home-ai-history"));
        assertEquals(1, (int) fx(() -> ((VBox) node("home-ai-history")).getChildren().size()));
        assertEquals(t("home.ai.history.hide"), text("home-ai-history-toggle"));
        snapshot("mini-ai-answer-en-1430");
        // The answer area fits the card without overlap or clipping in both languages, at the reference and minimum sizes.
        for (String tag : List.of("en", "zh-CN")) for (double[] window : new double[][] { { 1430, 1055 }, { 880, 600 } }) {
            action(() -> manager.activeContext().localization().selectLanguage(tag)); size(window[0], window[1]);
            fx(() -> { LanguageLayoutGuiTest.check(controller.workspace(), "mini ai " + tag + " " + (int) window[0]); return true; });
            assertEquals(64.0, fx(() -> prompt().getHeight()), .5, "the answer never changes the input's height");
            snapshot("mini-ai-answer-" + tag + "-" + (int) window[0]);
        }
        action(() -> manager.activeContext().localization().selectLanguage("en")); size(1430, 1055);

        // Shift+Enter starts a new line and sends nothing.
        type("Night"); key(KeyCode.ENTER, true);
        assertEquals("Night\n", fx(() -> prompt().getText()));
        assertEquals(2, gemini.requests.size());

        // A new answer folds the opened earlier exchanges away again; they stay in the context.
        gemini.reply("Try a slow shutter on passing trams.");
        type("One more idea?"); click("home-ai-generate"); idle();
        assertTrue(parts(gemini.requests.get(2)).getFirst().contains("What if I only have a 50mm lens?"));
        assertFalse(shown("home-ai-history"));
        assertEquals(t("home.ai.history.show", 2), text("home-ai-history-toggle"));

        // Clearing forgets the conversation only: the next question starts without context.
        click("home-ai-clear");
        assertFalse(shown("home-ai-response"));
        gemini.reply("Look for neon signs reflected in puddles.");
        type("Something different?"); click("home-ai-generate"); idle();
        assertFalse(parts(gemini.requests.get(3)).getFirst().contains("\"conversation\""), "a cleared conversation sends no context");
        assertFalse(shown("home-ai-history-toggle"));

        // "Advanced inspiration" opens the existing Inspiration page through the sidebar navigation.
        click("home-ai-advanced"); idle();
        assertTrue(fx(() -> ((ToggleButton) node("nav-F02")).isSelected()));
        assertFalse(fx(() -> controller.workspace().atHome()));
    }

    @Test void aPendingRequestKeepsTheWindowResponsiveAndBlocksDuplicates() throws Exception {
        launch();
        var release = new CountDownLatch(1);
        gemini.replyWith(body -> { try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } return "Shoot neon reflections after the rain."; });
        type("What should I shoot tonight?"); click("home-ai-generate");
        awaitRequests(1);
        AppearanceGuiTest.settle();
        try {
            long started = System.nanoTime();
            assertTrue(fx(() -> true));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "the FX thread is not blocked by the request");
            assertTrue(fx(controller::busy));
            assertTrue(fx(() -> node("home-ai-generate").isDisabled()));
            assertTrue(shown("home-ai-status") && shown("home-ai-progress"));
            assertEquals(t("home.ai.generating"), text("home-ai-status-text"));
            assertTrue(fx(() -> node("home-ai-clear").isDisabled()));
            // Duplicates are ignored, and the input stays usable: a chip still fills it.
            click("home-ai-generate"); key(KeyCode.ENTER, false); key(KeyCode.ENTER, false);
            action(() -> ((Button) node("home-chip-nearby")).fire());
            assertEquals(t("home.ai.chip.nearby.prompt"), fx(() -> prompt().getText()));
            Thread.sleep(200);
            assertEquals(1, gemini.requests.size(), "one request at a time");
        } finally { release.countDown(); }
        idle();
        assertEquals(1, gemini.requests.size());
        assertEquals("Shoot neon reflections after the rain.", text("home-ai-answer"));
        assertEquals("What should I shoot tonight?", text("home-ai-asked"));
        assertEquals(t("home.ai.chip.nearby.prompt"), fx(() -> prompt().getText()), "text typed while waiting is not cleared");
        assertFalse(fx(() -> node("home-ai-generate").isDisabled()));
        assertFalse(shown("home-ai-status"));
    }

    @Test void validationAndFailuresKeepTheInputAndNeverShowAnAnswer() throws Exception {
        launch();
        type("   \n "); click("home-ai-generate");
        assertEquals(t("home.ai.error.empty"), text("home-ai-status-text"));
        assertTrue(fx(() -> node("home-ai-status-text").getStyleClass().contains("home-ai-error")));
        assertFalse(shown("home-ai-progress"));
        type("x".repeat(301)); key(KeyCode.ENTER, false);
        assertEquals(t("home.ai.error.tooLong", 300, 301), text("home-ai-status-text"));
        assertEquals(301, (int) fx(() -> prompt().getText().length()), "the input is kept");
        assertTrue(gemini.requests.isEmpty(), "invalid input is never sent");

        gemini.status(503, "");
        type("Night ideas?"); click("home-ai-generate"); idle();
        assertEquals(t("home.ai.error.unavailable"), text("home-ai-status-text"));
        assertEquals("Night ideas?", fx(() -> prompt().getText()));
        assertFalse(shown("home-ai-response"), "a failure shows no answer");
        // Retrying the same input works; a later failure keeps the last answer and adds nothing to the conversation.
        gemini.reply("Try long exposures of traffic from a footbridge.");
        click("home-ai-generate"); idle();
        assertFalse(shown("home-ai-status"));
        assertEquals("Try long exposures of traffic from a footbridge.", text("home-ai-answer"));
        gemini.status(200, "not json");
        type("And after that?"); click("home-ai-generate"); idle();
        assertEquals(t("home.ai.error.emptyAnswer"), text("home-ai-status-text"));
        assertEquals("Try long exposures of traffic from a footbridge.", text("home-ai-answer"));
        assertEquals("And after that?", fx(() -> prompt().getText()));
        gemini.reply("Then switch to the skyline at blue hour.");
        click("home-ai-generate"); idle();
        var context = parts(gemini.requests.getLast()).getFirst();
        assertEquals(1, JsonParser.parseString(context.substring(context.indexOf('{'))).getAsJsonObject().getAsJsonArray("conversation").size(),
            "failed requests are not part of the conversation");
        assertEquals(t("home.ai.history.show", 1), text("home-ai-history-toggle"));

        // While an input method composes (e.g. pinyin), Enter belongs to it and sends nothing; after the commit it sends.
        int before = gemini.requests.size();
        action(() -> { prompt().clear(); Event.fireEvent(prompt(), new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            List.of(new InputMethodTextRun("yejing", InputMethodHighlight.UNSELECTED_RAW)), "", 0)); });
        key(KeyCode.ENTER, false); AppearanceGuiTest.settle();
        assertEquals(before, gemini.requests.size());
        assertFalse(fx(controller::busy));
        gemini.reply("拍城市夜景时，可以找有倒影的水面，用三脚架和慢门拍出车流光轨。");
        action(() -> Event.fireEvent(prompt(), new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, List.of(), "夜景怎么拍", 0)));
        type("夜景怎么拍"); // the committed text, independent of how the skin merged the composition
        key(KeyCode.ENTER, false); idle();
        assertEquals(before + 1, gemini.requests.size());
        assertTrue(parts(gemini.requests.getLast()).getLast().contains("夜景怎么拍"));
        assertEquals("拍城市夜景时，可以找有倒影的水面，用三脚架和慢门拍出车流光轨。", text("home-ai-answer"));

        // At the minimum window size the page scrolls just far enough to show a new answer.
        size(880, 600);
        action(() -> ((ScrollPane) stage.getScene().getRoot().lookup(".content-scroll")).setVvalue(0));
        gemini.reply("Use the glow of shop windows to light passers-by.");
        type("Street portraits at night?"); click("home-ai-generate"); idle();
        assertTrue(fx(() -> {
            var view = stage.getScene().getRoot().lookup(".content-scroll"); var shown = node("home-ai-answer");
            var answer = shown.localToScene(shown.getBoundsInLocal()); var viewport = view.localToScene(view.getBoundsInLocal());
            return answer.getMinY() >= viewport.getMinY() && answer.getMaxY() <= viewport.getMaxY();
        }), "the new answer is in view");
    }

    @Test void missingConfigurationShowsAShortMessageAndSendsNothing() throws Exception {
        var environment = new HashMap<>(gemini.environment(root));
        environment.put("LLM_API_KEY", "YOUR_LOCAL_API_KEY_HERE");
        launch(environment);
        type("What should I shoot tonight?"); click("home-ai-generate"); idle();
        assertEquals(t("home.ai.error.notConfigured"), text("home-ai-status-text"));
        assertEquals("What should I shoot tonight?", fx(() -> prompt().getText()));
        assertFalse(shown("home-ai-response"));
        assertTrue(gemini.requests.isEmpty());
    }

    @Test void quickPromptsFillEditableTextFromRealDataAndSendNothing() throws Exception {
        launch();
        action(() -> ((Button) node("home-chip-gear")).fire()); idle();
        assertEquals(t("home.ai.chip.gear.none"), fx(() -> prompt().getText()), "an empty inventory is said plainly; no gear is invented");
        var facade = manager.activeContext().facade();
        assertTrue(facade.saveGear(GearFixtures.camera("Sony", "A7 IV", "Sony E", 1, 658.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true)).isSuccess());
        assertTrue(facade.saveGear(GearFixtures.lens("Sony", "FE 24-105mm G", "Sony E", 24, 105, 4, 77.0, 663.0, true)).isSuccess());
        assertTrue(facade.saveGear(GearFixtures.battery("NP-FZ100", "NP-FZ100", 2, 83.0)).isSuccess());
        action(() -> ((Button) node("home-chip-gear")).fire()); idle();
        assertEquals(t("home.ai.chip.gear.prompt", "Sony A7 IV, Sony FE 24-105mm G"), fx(() -> prompt().getText()), "owned cameras and lenses only");
        action(() -> ((Button) node("home-chip-tonight")).fire());
        assertEquals(t("home.ai.chip.tonight.prompt"), fx(() -> prompt().getText()));
        var ideas = new LinkedHashSet<String>();
        String previous = "";
        for (int i = 0; i < 6; i++) {
            action(() -> ((Button) node("home-chip-random")).fire());
            String idea = fx(() -> prompt().getText());
            assertNotEquals(previous, idea, "Random idea never repeats the previous theme");
            previous = idea; ideas.add(idea);
        }
        assertTrue(ideas.size() >= 3, ideas.toString());
        // A filled prompt stays editable before it is sent.
        action(() -> { prompt().appendText(" With a 50mm lens."); });
        assertTrue(fx(() -> prompt().getText().endsWith("With a 50mm lens.")));
        assertTrue(gemini.requests.isEmpty(), "quick prompts never call the model");
        assertFalse(shown("home-ai-response"));
    }

    void snapshot(String name) throws Exception {
        AppearanceGuiTest.settle();
        fx(() -> {
            var image = stage.getScene().snapshot(null);
            var buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Files.createDirectories(Path.of("target", "home"));
            javax.imageio.ImageIO.write(buffered, "png", Path.of("target", "home", name + ".png").toFile());
            return true;
        });
    }
}
