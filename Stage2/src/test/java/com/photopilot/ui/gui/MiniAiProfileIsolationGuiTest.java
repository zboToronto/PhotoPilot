package com.photopilot.ui.gui;

import com.google.gson.JsonParser;
import com.photopilot.application.ProfileManager;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Stage2OptRe1 X04 (not verified in the audit): a Mini AI answer or conversation of one
 * profile must never reach another. Controlled loopback replies (FakeGemini) only. A pending request keeps the window
 * busy, so a switch is refused until it ends; after a switch the other profile's Home starts with no conversation and
 * its first request carries no earlier exchange; switching back does not revive the old conversation either.
 */
class MiniAiProfileIsolationGuiTest {
    @TempDir Path root;
    FakeGemini gemini; ProfileManager manager; GuiProfileController controller; Stage stage;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void close() throws Exception {
        action(() -> { if (controller != null) controller.close(); if (stage != null) stage.close(); });
        if (manager != null) manager.close();
        if (gemini != null) gemini.close();
    }
    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    Node node(String id) { return controller.workspace().lookup("#" + id); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) node(id)).getText()); }
    boolean shown(String id) throws Exception { return fx(() -> node(id) != null && node(id).isVisible() && node(id).isManaged()); }
    void ask(String question) throws Exception {
        action(() -> { var prompt = (TextArea) node("home-ai-prompt"); prompt.setText(question); prompt.end(); ((Button) node("home-ai-generate")).fire(); });
    }
    UUID active() { return manager.activeContext().storageScope().profileId().orElseThrow(); }
    /** The questions of earlier exchanges a request carried as context (none when it starts a conversation). */
    static List<String> context(String body) {
        var parts = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts");
        var questions = new ArrayList<String>();
        for (var part : parts) {
            String text = part.getAsJsonObject().get("text").getAsString();
            int start = text.indexOf('{');
            if (start < 0) continue;
            try {
                var evidence = JsonParser.parseString(text.substring(start)).getAsJsonObject();
                if (evidence.has("conversation")) evidence.getAsJsonArray("conversation").forEach(e -> questions.add(e.getAsJsonObject().get("user").getAsString()));
            } catch (RuntimeException notEvidence) { /* the question itself, not the evidence part */ }
        }
        return questions;
    }
    void awaitRequests(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (gemini.requests.size() < count) { if (System.nanoTime() > deadline) fail("request not sent"); Thread.sleep(20); }
    }

    @Test void aMiniAiAnswerOrConversationNeverCrossesProfiles() throws Exception {
        gemini = new FakeGemini();
        manager = new ProfileManager(gemini.environment(root));
        var first = manager.activeProfile().value().orElseThrow();
        var second = manager.createProfile("Second photographer").value().orElseThrow();
        action(() -> { stage = new Stage(); controller = new GuiProfileController(stage, manager, first, context -> { }); });
        idle();
        action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle();
        action(() -> manager.activeContext().localization().selectLanguage("en")); idle();

        // Profile A: one completed exchange, then a second question whose reply is held open.
        gemini.reply("Try long exposures of the harbour lights.");
        ask("What should I shoot tonight?"); awaitRequests(1); idle();
        assertEquals("Try long exposures of the harbour lights.", text("home-ai-answer"));
        var release = new CountDownLatch(1);
        gemini.replyWith(body -> { try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); } return "Profile A only: frame the bridge."; });
        ask("And where exactly?"); awaitRequests(2);
        assertEquals(List.of("What should I shoot tonight?"), context(gemini.requests.get(1)), "A's follow-up carries A's exchange");
        try {
            assertTrue(fx(controller::busy), "a pending request keeps the window busy");
            action(() -> controller.requestSwitch(second.id())); AppearanceGuiTest.settle();
            assertEquals(first.id(), active(), "the switch is refused while A's request is pending");
        } finally { release.countDown(); }
        idle();
        assertEquals("Profile A only: frame the bridge.", text("home-ai-answer"), "the reply reaches the profile that asked");

        // Profile B: nothing of A's conversation is shown or sent.
        action(() -> controller.requestSwitch(second.id())); idle();
        assertEquals(second.id(), active());
        assertTrue(fx(() -> controller.workspace().atHome()));
        assertFalse(shown("home-ai-answer") && !text("home-ai-answer").isBlank(), "B's Home shows no answer of A");
        gemini.reply("A quiet street portrait at dusk.");
        ask("Any idea for a portrait?"); awaitRequests(3); idle();
        assertEquals(List.of(), context(gemini.requests.get(2)), "B's first request carries no earlier exchange");
        assertFalse(gemini.requests.get(2).contains("harbour") || gemini.requests.get(2).contains("bridge"), "nothing of A's exchanges is sent");
        assertEquals("A quiet street portrait at dusk.", text("home-ai-answer"));

        // Back to A: the conversation lived only in A's previous window content; it is not revived with B's.
        action(() -> controller.requestSwitch(first.id())); idle();
        assertEquals(first.id(), active());
        gemini.reply("Use a tripod.");
        ask("Gear tip?"); awaitRequests(4); idle();
        assertFalse(context(gemini.requests.get(3)).contains("Any idea for a portrait?"), "B's exchange never reaches A");
    }
}
