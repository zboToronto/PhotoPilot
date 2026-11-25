package com.photopilot.ui.gui;

import com.google.gson.JsonParser;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.review.ReviewFixtures;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Stage2OptRe1 PP-03 in the real F11 page: a draft whose reply calls the planned
 * "Sony A7 IV" and the recorded "SONY ILCE-7M4" different equipment shows no such statement and says why; a review
 * saved before the check is shown as stored, with a note under that statement, and nothing is written.
 */
class ReviewIdentityGuiTest {
    @TempDir Path root;
    @TempDir Path photos;
    ReviewPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static final String CLAIM = "The recorded camera was SONY ILCE-7M4 for 2 of 6 photos with camera data (33.3%), differing from the planned Sony A7 IV.";

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do { Thread.sleep(30); if (System.nanoTime() > deadline) fail("F11 operation timed out"); } while (fx(() -> pane.busy()));
    }
    void click(String id) throws Exception { action(() -> ((ButtonBase) pane.lookup("#" + id)).fire()); ready(); }
    String label(String id) throws Exception { return fx(() -> ((Label) pane.lookup("#" + id)).getText()); }
    static String withClaim(String request) {
        var reply = JsonParser.parseString(ReviewFixtures.review(request, false)).getAsJsonObject();
        reply.getAsJsonObject("result").getAsJsonArray("equipmentObservations").add(CLAIM);
        return reply.toString();
    }

    @Test void theDraftDropsTheAliasClaimAndALegacySavedReviewIsShownWithANote() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = ReviewFixtures.completed(app, gemini, new PlanFixtures.Gear());
            ok(app.facade().reviews().analysis(id, ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id)), false));
            try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane = new ReviewPane(app.facade(), executor, app.localization()); workspace.connectReview(pane);
                stage = new Stage(); var scene = new Scene(workspace, 1120, 900);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                workspace.selectReview(); workspace.applyCss(); workspace.layout();
            }); ready();

            // Draft: the wrong statement never reaches the page, and the notes say how many identity claims were removed.
            gemini.replyWith(ReviewIdentityGuiTest::withClaim);
            click("review-generate");
            assertFalse(label("review-draft-equipment").contains("differing from the planned"), label("review-draft-equipment"));
            assertTrue(label("review-notes").contains("1 AI statement(s) were removed because they made equipment identity claims"), label("review-notes"));
            click("review-save");
            assertEquals("REVIEWED", app.facade().sessions().open(id).value().orElseThrow().status());
            assertFalse(label("review-saved-equipment").contains("Note: this saved statement"), "a validated review needs no note");

            // A review saved by an earlier build with the wrong statement, in a second reviewed session.
            var session = ok(app.facade().sessions().open(id)); var saved = session.reviews().getFirst();
            var equipment = new ArrayList<>(saved.equipmentObservations()); equipment.add(CLAIM);
            var legacy = new PostShootReview(UUID.randomUUID(), saved.createdAt(), saved.statistics(), saved.comparedPlanId(), saved.comparedRevision(),
                saved.planDifferences(), saved.lessons(), saved.limitations(), Optional.empty(), saved.summary(), saved.technicalObservations(), equipment,
                saved.exposureObservations(), saved.improvements(), saved.evidence(), PostShootReview.SAVED);
            var old = ok(new JsonSessionRepository(app.storageScope().sessionsDirectory()).insert(new PhotographySession(UUID.randomUUID(), "Legacy airshow",
                session.date(), session.location(), "", session.createdAt(), session.updatedAt(), session.state(), session.requirements(), session.plans(),
                List.of(legacy), session.profile(), session.statistics(), List.of(), session.selectedLoadout())));
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = Files.readAllBytes(store);
            action(() -> pane.prefer(old.id())); action(pane::refresh); ready();
            String shown = label("review-saved-equipment");
            assertTrue(shown.contains("- " + CLAIM), "stored text shown unchanged: " + shown);
            assertTrue(shown.contains("Note: this saved statement treats SONY ILCE-7M4 and Sony A7 IV as different equipment"), shown);
            assertTrue(shown.indexOf("Note: this saved statement") > shown.indexOf(CLAIM), "the note follows its statement");
            fx(() -> { CalculatorGuiTest.snapshot(workspace, "f11-legacy-identity-note"); return true; });
            action(() -> app.localization().useLanguage("zh-CN")); ready();
            assertTrue(label("review-saved-equipment").contains("注意：这条已保存的陈述把 SONY ILCE-7M4 与 Sony A7 IV 当作不同器材"), label("review-saved-equipment"));
            assertArrayEquals(before, Files.readAllBytes(store), "showing saved history writes nothing");
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }
}
