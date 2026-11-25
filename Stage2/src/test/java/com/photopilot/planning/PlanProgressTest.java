package com.photopilot.planning;

import com.photopilot.application.NextSteps;
import com.photopilot.application.OperationKind;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.progress.*;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F10 execution feedback through the real Facade -> PlanningService -> agent tool loop -> GeminiAdapter -> JSON
 * repository chain (loopback Gemini and provider fakes): the stages come from the code in its actual order, a draft is
 * labelled unsaved, a save is SAVED only after the repository confirmed it, and failures carry their class and the
 * deterministic next step.
 */
class PlanProgressTest {
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }

    private record Run<T>(Result<T> result, List<ExecutionProgressEvent> events, OperationContext context) {
        ExecutionProgressEvent last() { return events.getLast(); }
        List<String> stages() { return last().stages().stream().map(OperationStage::key).toList(); }
    }
    private <T> Run<T> tracked(OperationKind kind, Supplier<Result<T>> work) {
        var events = new ArrayList<ExecutionProgressEvent>();
        var context = kind.start(events::add);
        context.acknowledge();
        var result = context.run(work);
        context.finish(result, NextSteps.after(kind, result));
        return new Run<>(result, events, context);
    }
    /** {@code actual} contains {@code expected} in this order (other stages, e.g. tool calls, may sit between). */
    private static void inOrder(List<String> expected, List<String> actual) {
        int at = 0;
        for (String stage : actual) if (at < expected.size() && stage.equals(expected.get(at))) at++;
        assertEquals(expected.size(), at, () -> "expected " + expected + " in order within " + actual);
    }

    @Test void generationReportsItsRealStagesThenApprovalReportsTheConfirmedSave() {
        var id = session(app, gear, true); assess(app, gemini, id);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
        var generated = tracked(OperationKind.PLAN_GENERATE, () -> app.facade().planning().generatePreview(id));
        assertTrue(generated.result().isSuccess(), () -> generated.result().errors().toString());
        assertEquals(OperationStatus.PENDING, generated.events().getFirst().status());
        assertEquals(OperationKind.PLAN_GENERATE.stages(), generated.events().getFirst().pending(), "the whole plan is shown before work starts");
        inOrder(List.of("plan.inputs", "plan.offers", "plan.environment", "agent.history", "ai.wait", "agent.tool", "ai.wait", "ai.check", "plan.validate", "plan.ready"),
            generated.stages());
        assertTrue(generated.events().stream().anyMatch(e -> e.status() == OperationStatus.WAITING_FOR_EXTERNAL_SERVICE), "the Gemini wait is its own state");
        assertTrue(generated.events().stream().anyMatch(e -> e.status() == OperationStatus.VALIDATING));
        assertEquals(List.of("calculation"), generated.last().stages().stream().filter(s -> s.key().equals("agent.tool")).findFirst().orElseThrow().arguments(),
            "the model's actual tool call is shown by name");
        assertEquals(OperationStatus.COMPLETED, generated.last().status());
        assertEquals(PersistenceStatus.UNSAVED_DRAFT, generated.last().persistence(), "a generated plan is an unsaved draft until approved");
        assertEquals("next.after.plan", generated.last().next().orElseThrow().message().key());
        assertTrue(app.facade().planning().plans(id).value().orElseThrow().isEmpty(), "nothing was saved by generating");

        var preview = generated.result().value().orElseThrow();
        var approved = tracked(OperationKind.PLAN_APPROVE, () -> app.facade().planning().approve(id, preview.plan()));
        assertTrue(approved.result().isSuccess(), () -> approved.result().errors().toString());
        inOrder(List.of("plan.inputs", "plan.validate", "plan.save"), approved.stages());
        var statuses = approved.events().stream().map(ExecutionProgressEvent::persistence).distinct().toList();
        assertTrue(statuses.indexOf(PersistenceStatus.SAVING) >= 0 && statuses.indexOf(PersistenceStatus.SAVING) < statuses.indexOf(PersistenceStatus.SAVED),
            "saving is announced before the confirmed save: " + statuses);
        assertEquals(PersistenceStatus.SAVED, approved.last().persistence());
        assertEquals(Optional.of("F01"), approved.last().next().orElseThrow().target());
        assertEquals(1, app.facade().planning().plans(id).value().orElseThrow().size(), "SAVED matches what the repository holds");
    }

    @Test void aProviderFailureIsClassifiedSavesNothingAndOffersTheNextStep() {
        var id = session(app, gear, true);
        gemini.status(503, "");
        var failed = tracked(OperationKind.PLAN_GENERATE, () -> app.facade().planning().generatePreview(id));
        assertFalse(failed.result().isSuccess());
        assertEquals(OperationStatus.FAILED, failed.last().status());
        assertEquals(ErrorKind.SERVICE_UNAVAILABLE, failed.last().error());
        assertEquals("ai.wait", failed.last().stages().getLast().key(), "the stage that failed is the Gemini wait");
        assertEquals(OperationStage.State.FAILED, failed.last().stages().getLast().state());
        assertEquals(PersistenceStatus.NOT_APPLICABLE, failed.last().persistence(), "no write was attempted, so none is claimed");
        assertEquals("next.manual.plan", failed.last().next().orElseThrow().message().key());

        var missing = ok(app.facade().sessions().create("No requirement", day())).id();
        var blocked = tracked(OperationKind.PLAN_GENERATE, () -> app.facade().planning().generatePreview(missing));
        assertEquals(ErrorKind.REJECTED, blocked.last().error());
        assertEquals(Optional.of("F03"), blocked.last().next().orElseThrow().target(), "the missing prerequisite is resolved in F03");
        assertFalse(blocked.stages().contains("ai.wait"), "no Gemini wait is claimed when nothing was sent");
    }

    @Test void aConcurrentChangeIsReportedAsAConflictAndNothingIsOverwritten() {
        var session = ok(app.facade().sessions().create("Conflict", day()));
        ok(app.facade().sessions().update(session, "Changed elsewhere", session.date(), session.location(), session.notes()));
        var stale = tracked(OperationKind.REQUIREMENT_CONFIRM, () -> app.facade().planning().confirm(session, requirement(day()).asDraft()));
        assertFalse(stale.result().isSuccess());
        assertEquals(PersistenceStatus.CONFLICT, stale.last().persistence());
        assertEquals(ErrorKind.CONFLICT, stale.last().error());
        assertEquals("next.failed.conflict", stale.last().next().orElseThrow().message().key());
        assertEquals("Changed elsewhere", ok(app.facade().sessions().open(session.id())).title(), "the other change was kept");
    }
}
