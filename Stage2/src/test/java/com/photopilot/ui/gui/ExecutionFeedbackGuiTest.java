package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.progress.OperationStatus;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F10 execution feedback in real JavaFX controls with the window's real two-lane UiOperations and loopback Gemini
 * that can hold its answer: the click is acknowledged in the same pulse, the shown stages are the ones the service
 * reached, other pages stay usable while Gemini is pending, cancel discards the late result, duplicates are ignored,
 * a retry is visible, SAVED appears only after approval saved, and the next step opens its page. A provider outage
 * leaves the stored records and the session's workflow progress unchanged and offers the manual path.
 */
class ExecutionFeedbackGuiTest {
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    UiOperations lanes; PhotoPilotWorkspace workspace; PlanPane plan; SessionPane sessions; Stage stage;
    final PlanFixtures.Gear gear = new PlanFixtures.Gear();
    final CountDownLatch release = new CountDownLatch(1);

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void until(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!fx(condition)) { if (System.nanoTime() > deadline) fail("timed out waiting for " + what); Thread.sleep(20); }
    }
    void idle() throws Exception { until("F10 idle", () -> !plan.busy()); }
    String text(String id) throws Exception { return fx(() -> ((Labeled) plan.lookup("#" + id)).getText()); }
    boolean shown(Node node) { for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible()) return false; return true; }
    /** Stage rows in {@code state}; call on the FX thread (inside fx or until conditions). */
    List<String> rowsNow(String state) {
        return ((VBox) plan.lookup("#progress-stages")).getChildren().stream().map(row -> (Label) ((javafx.scene.layout.HBox) row).getChildren().get(1))
            .filter(label -> label.getStyleClass().contains("progress-stage-" + state)).map(Label::getText).toList();
    }
    List<String> rows(String state) throws Exception { return fx(() -> rowsNow(state)); }
    String t(String key, Object... args) { return app.localization().text(key, args); }

    void launch(Map<String, String> extra) throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        var environment = new HashMap<>(providers.environment(gemini.environment(root))); environment.putAll(extra);
        app = new ApplicationBootstrap(environment);
        var id = PlanFixtures.session(app, gear, true);
        PlanFixtures.assess(app, gemini, id);
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        action(() -> {
            lanes = new UiOperations();
            workspace = new PhotoPilotWorkspace(app.localization());
            plan = new PlanPane(app.facade(), lanes, app.localization()); workspace.connectPlanning(plan);
            sessions = new SessionPane(app.facade(), lanes, app.localization()); workspace.connectSessions(sessions);
            stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            workspace.selectPlanning();
        });
        idle();
    }
    @AfterEach void close() throws Exception {
        release.countDown();
        if (stage != null) action(() -> { stage.close(); plan.dispose(); sessions.dispose(); });
        if (lanes != null) { lanes.shutdownNow(); lanes.awaitTermination(10, TimeUnit.SECONDS); }
        if (app != null) app.close();
        if (gemini != null) gemini.close();
        if (providers != null) providers.close();
    }
    /** The plan request is answered only after {@link #release}; the tool step before it answers at once. */
    void holdPlanReply() {
        gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> {
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            return PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true);
        });
    }

    @Test void generationIsAcknowledgedAtOnceShowsRealStagesKeepsOtherPagesResponsiveAndEndsWithTheNextStep() throws Exception {
        launch(Map.of());
        holdPlanReply();
        // One FX call: the click and what the user sees in the same pulse, before any worker ran.
        long[] acknowledged = new long[1];
        assertTrue(fx(() -> {
            long clicked = System.nanoTime();
            ((Button) plan.lookup("#plan-generate")).fire();
            var view = plan.lookup("#operation-progress");
            acknowledged[0] = System.nanoTime() - clicked;
            return shown(view) && ((Labeled) plan.lookup("#progress-title")).getText().equals(t("progress.op.planGenerate"))
                && shown(plan.lookup("#progress-cancel"));
        }), "the operation, its title and Cancel are visible in the click's own pulse");
        assertTrue(acknowledged[0] < TimeUnit.MILLISECONDS.toNanos(100), "acknowledged in " + acknowledged[0] / 1e6 + " ms");
        System.out.printf("[measure] F10 click -> visible acknowledgment: %.2f ms%n", acknowledged[0] / 1e6);
        assertTrue(rows("pending").size() >= 6, "declared stages are listed as pending before they run: " + rows("pending"));

        until("the Gemini wait to be shown", () -> rowsNow("active").equals(List.of(t("progress.stage.ai.wait"))) && gemini.requests.size() == 3);
        var done = rows("completed");
        assertTrue(done.containsAll(List.of(t("progress.stage.plan.inputs"), t("progress.stage.plan.offers"), t("progress.stage.agent.history"),
            t("progress.stage.agent.tool", "calculation"))), "completed stages are the ones the service reached: " + done);
        assertTrue(rows("pending").contains(t("progress.stage.plan.validate")));
        String first = text("progress-elapsed"); Thread.sleep(350);
        assertNotEquals(first, text("progress-elapsed"), "the clock shows real elapsed time while waiting");

        // Another page's local work does not wait for Gemini (before this change it queued behind the request).
        long started = System.nanoTime();
        action(() -> sessions.refresh());
        until("the session list", () -> !sessions.busy());
        long local = System.nanoTime() - started;
        System.out.printf("[measure] F01 refresh while F10 waits on Gemini: %.1f ms%n", local / 1e6);
        assertTrue(local < TimeUnit.SECONDS.toNanos(3), "local work completes while the request is pending: " + local / 1e6 + " ms");
        assertTrue(fx(plan::busy), "the plan request is still pending");

        // Repeated clicks do not start another request.
        action(() -> ((Button) plan.lookup("#plan-generate")).fire());
        Thread.sleep(150);
        assertEquals(3, gemini.requests.size(), "no duplicate request (F09 interpretation, tool step, plan)");

        release.countDown(); idle();
        assertNotNull(fx(plan::preview));
        assertEquals(t("progress.op.planGenerate") + " · " + t("progress.status.COMPLETED"), text("progress-title"));
        assertTrue(rows("pending").isEmpty() && rows("active").isEmpty());
        assertEquals(t("progress.persistence.UNSAVED_DRAFT"), text("progress-persistence"), "a preview is labelled unsaved");
        assertEquals(t("progress.next", t("next.after.plan")), text("progress-next"));
        assertFalse(fx(() -> shown(plan.lookup("#progress-cancel"))));

        // Approval: SAVED only after the repository saved; the next step opens Sessions & History.
        action(() -> ((Button) plan.lookup("#plan-approve")).fire()); idle();
        assertEquals(t("progress.persistence.SAVED"), text("progress-persistence"));
        var id = PlanFixtures.ok(app.facade().sessions().search("")).getFirst().id();
        assertEquals(1, app.facade().planning().plans(id).value().orElseThrow().size(), "SAVED matches the stored plan history");
        // A completed operation continues the workflow ("Continue to ..."); failures keep "Open ..." for the alternative.
        assertEquals(t("progress.continue", t("feature.F01.title")), text("progress-open"));
        action(() -> ((Button) plan.lookup("#progress-open")).fire());
        assertTrue(fx(() -> shown(sessions)), "the next-step button opens the page through the normal navigation");
    }

    @Test void cancellingAPendingRequestRestoresThePageAndTheLateAnswerIsNeverApplied() throws Exception {
        launch(Map.of());
        holdPlanReply();
        action(() -> ((Button) plan.lookup("#plan-generate")).fire());
        until("the Gemini wait", () -> rowsNow("active").equals(List.of(t("progress.stage.ai.wait"))) && gemini.requests.size() == 3);
        long started = System.nanoTime();
        action(() -> ((Button) plan.lookup("#progress-cancel")).fire());
        idle();
        System.out.printf("[measure] F10 cancel -> page usable: %.1f ms%n", (System.nanoTime() - started) / 1e6);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "the interrupted request ends at once");
        assertEquals(t("progress.cancelled"), fx(plan::feedbackText));
        assertEquals(t("progress.op.planGenerate") + " · " + t("progress.status.CANCELLED"), text("progress-title"));
        assertEquals(t("progress.next", t("next.cancelled")), text("progress-next"));
        assertEquals(OperationStatus.CANCELLED, fx(() -> plan.tracked().current().status()));
        assertFalse(fx(() -> plan.lookup("#plan-generate").isDisabled()), "the page is usable again");
        release.countDown(); Thread.sleep(300);
        assertNull(fx(plan::preview), "the late answer is discarded");
        var id = PlanFixtures.ok(app.facade().sessions().search("")).getFirst().id();
        assertTrue(app.facade().planning().plans(id).value().orElseThrow().isEmpty(), "nothing was saved");
    }

    @Test void aGeminiOutageKeepsTheSessionsProgressAndConfirmedRecordsAndOffersTheManualPath() throws Exception {
        launch(Map.of());   // retries off; the fake answers 503 once nothing is queued
        var id = PlanFixtures.ok(app.facade().sessions().search("")).getFirst().id();
        var store = app.storageScope().sessionsDirectory().resolve("sessions.json");
        byte[] stored = java.nio.file.Files.readAllBytes(store);
        var progress = app.facade().workflow().of(id).value().orElseThrow();
        int sent = gemini.requests.size();
        action(() -> ((Button) plan.lookup("#plan-generate")).fire()); idle();
        assertTrue(gemini.requests.size() > sent, "the request reached the provider");
        assertEquals(OperationStatus.FAILED, fx(() -> plan.tracked().current().status()));
        assertEquals(t("progress.op.planGenerate") + " · " + t("progress.status.FAILED"), text("progress-title"));
        assertEquals(t("progress.next", t("next.manual.plan")), text("progress-next"), "an actionable next step");
        assertNull(fx(plan::preview), "no plan was produced");
        // Nothing was written: the confirmed records and the session's workflow progress are exactly as before.
        assertArrayEquals(stored, java.nio.file.Files.readAllBytes(store), "the session store is byte-for-byte unchanged");
        var after = app.facade().sessions().open(id).value().orElseThrow();
        assertTrue(after.requirements().isPresent() && after.profile().isPresent(), "the confirmed requirement and profile are still there");
        assertTrue(after.plans().isEmpty());
        var again = app.facade().workflow().of(id).value().orElseThrow();
        assertEquals(progress.milestones(), again.milestones(), "the session's progress is unchanged");
        assertEquals(progress.percent(), again.percent());
        assertEquals(progress.next(), again.next());
    }

    @Test void aTransientProviderErrorIsRetriedVisiblyAndTheTextFollowsTheLanguage() throws Exception {
        launch(Map.of("GEMINI_RETRIES", "1"));
        gemini.status(503, "").reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true));
        action(() -> ((Button) plan.lookup("#plan-generate")).fire()); idle();
        assertNotNull(fx(plan::preview), "the retry succeeded");
        assertTrue(rows("completed").contains(t("progress.stage.retry", "503", 1, 1, "1")), "the retry is listed: " + rows("completed"));
        action(() -> app.localization().useLanguage("zh-CN"));
        assertTrue(text("progress-title").startsWith(app.localization().text("progress.op.planGenerate")) && text("progress-title").contains("完成"), text("progress-title"));
        assertTrue(rows("completed").contains(app.localization().text("progress.stage.retry", "503", 1, 1, "1")));
    }
}
