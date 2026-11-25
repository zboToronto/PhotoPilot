package com.photopilot.progress;

import com.photopilot.common.Result;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Execution-feedback Observer core: events come only from published execution points, in order; persistence is SAVED
 * only after a confirmed write; cancellation is honest (interrupts waiting work, never a started write); bindings never
 * leak between operations on a reused worker thread.
 */
class OperationContextTest {
    private static List<String> keys(ExecutionProgressEvent event) { return event.stages().stream().map(OperationStage::key).toList(); }
    private static List<String> completed(ExecutionProgressEvent event) { return event.completed().stream().map(OperationStage::key).toList(); }

    @Test void acknowledgmentIsImmediateAndStagesAdvanceInExecutionOrder() {
        var events = new ArrayList<ExecutionProgressEvent>();
        var clock = new AtomicLong();
        var context = OperationContext.create("F10", "planGenerate", List.of("a", "b", "c", "d"), true, clock::get);
        context.listen(events::add);
        context.acknowledge();
        assertEquals(1, events.size(), "the start is acknowledged before any work runs");
        assertEquals(OperationStatus.PENDING, events.getFirst().status());
        assertEquals(List.of("a", "b", "c", "d"), events.getFirst().pending());
        assertTrue(events.getFirst().stages().isEmpty(), "no stage is claimed before the code reaches it");

        context.run(() -> {
            Progress.stage("a"); clock.addAndGet(1_000_000);
            Progress.stage("a");                       // re-entering the active stage is not a new stage
            Progress.waiting("c");                     // "b" was never entered: dropped, never shown as done
            Progress.stage("tool", "calculation");     // undeclared stages are inserted where they happen
            Progress.waiting("c");                     // a second model request after a tool call is a new row
            Progress.validating("d");
            return null;
        });
        var last = events.getLast();
        assertEquals(List.of("a", "c", "tool", "c", "d"), keys(last));
        assertEquals(List.of("a", "c", "tool", "c"), completed(last));
        assertEquals("d", last.current().orElseThrow().key());
        assertEquals(OperationStatus.VALIDATING, last.status());
        assertTrue(last.pending().isEmpty());
        assertEquals(OperationStatus.STARTED, events.get(1).status(), "the worker picking it up is its own event");
        assertEquals(List.of("calculation"), last.stages().get(2).arguments());
        assertEquals(1_000_000, last.stages().get(1).startedAfter().toNanos(), "stage times are real elapsed times");

        context.finish(Result.success("plan"), Optional.of(NextStep.to("F10", "next.after.plan")));
        var done = events.getLast();
        assertEquals(OperationStatus.COMPLETED, done.status());
        assertEquals(List.of("a", "c", "tool", "c", "d"), completed(done));
        assertEquals(PersistenceStatus.RESULT_AVAILABLE, done.persistence());
        assertEquals("next.after.plan", done.next().orElseThrow().message().key());
        clock.addAndGet(5_000_000_000L);
        assertEquals(done.elapsed(), context.elapsed(), "the elapsed time is frozen at completion");
        int published = events.size();
        Progress.stage("late");
        context.stage("late");
        assertEquals(published, events.size(), "a finished operation publishes nothing more");
    }

    @Test void persistenceIsSavedOnlyAfterTheRepositoryConfirmedTheWrite() {
        var events = new ArrayList<ExecutionProgressEvent>();
        var context = OperationContext.create("F03", "requirementConfirm", List.of(), false).listen(events::add);
        var seen = new ArrayList<PersistenceStatus>();
        var saved = context.run(() -> Progress.write(() -> { seen.add(events.getLast().persistence()); return Result.success(1); }));
        assertTrue(saved.isSuccess());
        assertEquals(List.of(PersistenceStatus.SAVING), seen, "while the write runs the operation says saving, not saved");
        assertEquals(PersistenceStatus.SAVED, events.getLast().persistence());
        context.finish(saved, Optional.empty());
        assertEquals(PersistenceStatus.SAVED, events.getLast().persistence());

        Map<String, PersistenceStatus> outcomes = Map.of("error.session.storage", PersistenceStatus.SAVE_FAILED,
            "error.session.changed", PersistenceStatus.CONFLICT, "error.inventory.busy", PersistenceStatus.RETRY_REQUIRED);
        outcomes.forEach((key, expected) -> {
            var failing = OperationContext.create("F01", "save", List.of(), false);
            Result<Object> result = failing.run(() -> Progress.write(() -> Result.failureKey(key, "x")));
            assertEquals(expected, failing.persistence(), key);
            failing.finish(result, Optional.empty());
            assertEquals(OperationStatus.FAILED, failing.status());
            assertEquals(expected, failing.snapshot().persistence(), "a failed save is never reported as saved: " + key);
        });

        var draft = OperationContext.create("F10", "planGenerate", List.of(), true).resultIs(PersistenceStatus.UNSAVED_DRAFT);
        draft.finish(Result.success("preview"), Optional.empty());
        assertEquals(PersistenceStatus.UNSAVED_DRAFT, draft.persistence(), "a generated draft is labelled unsaved");
    }

    @Test void retriesAndCountsAreReportedFromTheRealValues() {
        var events = new ArrayList<ExecutionProgressEvent>();
        var context = OperationContext.create("F10", "planGenerate", List.of("ai.wait"), true).listen(events::add);
        context.run(() -> {
            Progress.attempt(1, 3); Progress.waiting("ai.wait");
            Progress.retrying(1, 3, "503", Duration.ofMillis(1500));
            Progress.attempt(2, 3); Progress.waiting("ai.wait");
            Progress.count(4, 12);
            return null;
        });
        var retry = events.stream().filter(e -> e.status() == OperationStatus.RETRYING).findFirst().orElseThrow();
        assertEquals(List.of("503", 1, 2, "2"), retry.current().orElseThrow().arguments(), "HTTP 503, retry 1 of 2, 2 s (rounded up)");
        var last = events.getLast();
        assertEquals(2, last.attempt()); assertEquals(3, last.maxAttempts());
        assertEquals(4, last.done()); assertEquals(12, last.total()); assertTrue(last.measurable());
        assertEquals(OperationStatus.WAITING_FOR_EXTERNAL_SERVICE, last.status());
    }

    @Test void cancellationInterruptsWaitingWorkAndTheOutcomeIsCancelled() throws Exception {
        var context = OperationContext.create("F10", "planGenerate", List.of("ai.wait"), true);
        var waiting = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Result<String>> work = pool.submit(() -> context.run(() -> {
                Progress.waiting("ai.wait"); waiting.countDown();
                try { Thread.sleep(10_000); return Result.success("late"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.failureKey("ai.error.interrupted"); }
            }));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertTrue(context.snapshot().cancellable());
            long started = System.nanoTime();
            assertTrue(context.cancel());
            assertTrue(context.snapshot().cancelling());
            var result = work.get(5, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "the pending wait ends at once");
            context.finish(result, Optional.empty());
            assertEquals(OperationStatus.CANCELLED, context.status());
            assertEquals(ErrorKind.CANCELLED, context.snapshot().error());
            assertEquals(OperationStage.State.CANCELLED, context.snapshot().stages().getLast().state());
            assertFalse(context.cancel(), "a finished operation cannot be cancelled again");
            // The worker thread goes back to its pool without our interrupt, and without the binding.
            assertFalse(pool.submit(() -> Thread.currentThread().isInterrupted()).get());
            assertTrue(pool.submit(() -> OperationContext.current().isEmpty()).get());
        } finally { pool.shutdownNow(); }
    }

    @Test void aStartedWriteIsNeverInterruptedAndACancelledOperationNeverStartsOne() throws Exception {
        var context = OperationContext.create("F11", "reviewSave", List.of(), true);
        var inWrite = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Result<Integer>> work = pool.submit(() -> context.run(() -> Progress.write(() -> {
                inWrite.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { return Result.failureKey("error.session.storage", "interrupted"); }
                return Result.success(1);
            })));
            assertTrue(inWrite.await(5, TimeUnit.SECONDS));
            assertFalse(context.snapshot().cancellable(), "the cancel control is withdrawn once a write started");
            assertFalse(context.cancel(), "cancellation is refused instead of corrupting the write");
            release.countDown();
            var saved = work.get(5, TimeUnit.SECONDS);
            assertTrue(saved.isSuccess(), "the write completed undisturbed");
            context.finish(saved, Optional.empty());
            assertEquals(OperationStatus.COMPLETED, context.status());
            assertEquals(PersistenceStatus.SAVED, context.persistence());
        } finally { pool.shutdownNow(); }

        var cancelled = OperationContext.create("F04", "importConfirm", List.of(), true);
        assertTrue(cancelled.cancel());
        var wrote = new java.util.concurrent.atomic.AtomicBoolean();
        var outcome = cancelled.run(() -> Progress.write(() -> { wrote.set(true); return Result.success(1); }));
        Thread.interrupted();
        assertFalse(wrote.get(), "no write starts after a cancel request");
        assertTrue(outcome.hasError("progress.cancelled"));
        cancelled.finish(outcome, Optional.empty());
        assertEquals(OperationStatus.CANCELLED, cancelled.status());
        assertNotEquals(PersistenceStatus.SAVED, cancelled.persistence());
    }

    @Test void publicationOutsideAnOperationAndFaultyListenersAreHarmless() {
        assertTrue(OperationContext.current().isEmpty());
        Progress.stage("x"); Progress.waiting("y"); Progress.count(1, 2);
        assertFalse(Progress.cancelled());
        assertTrue(Progress.write(() -> Result.success(3)).isSuccess(), "repositories work unchanged without an operation");

        var context = OperationContext.create("F01", "save", List.of(), false);
        var after = new ArrayList<ExecutionProgressEvent>();
        context.listen(event -> { throw new IllegalStateException("broken view"); }).listen(after::add);
        context.run(() -> { Progress.stage("x"); return null; });
        assertEquals("x", after.getLast().current().orElseThrow().key(), "a failing listener breaks neither the work nor other listeners");

        var outer = OperationContext.create("F10", "outer", List.of(), false);
        outer.run(() -> {
            var inner = OperationContext.create("F08", "inner", List.of(), false);
            inner.run(() -> { Progress.stage("inner"); return null; });
            assertSame(outer, OperationContext.current().orElseThrow(), "the outer binding is restored");
            Progress.stage("outer");
            return null;
        });
        assertEquals(List.of("outer"), keys(outer.snapshot()));
        assertTrue(OperationContext.current().isEmpty());
    }

    @Test void errorClassificationFollowsTheExistingFailureKeys() {
        assertEquals(ErrorKind.SERVICE_UNAVAILABLE, ErrorKind.of("ai.error.serviceUnavailable"));
        assertEquals(ErrorKind.SERVICE_UNAVAILABLE, ErrorKind.of("env.error.unavailable"));
        assertEquals(ErrorKind.RATE_LIMITED, ErrorKind.of("ai.error.rateLimited"));
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.of("ai.error.timeout"));
        assertEquals(ErrorKind.TIMEOUT, ErrorKind.of("import.error.catalogTimeout"));
        assertEquals(ErrorKind.NETWORK, ErrorKind.of("env.error.network"));
        assertEquals(ErrorKind.CREDENTIALS, ErrorKind.of("ai.error.keyMissing"));
        assertEquals(ErrorKind.CREDENTIALS, ErrorKind.of("home.ai.error.notConfigured"));
        assertEquals(ErrorKind.INVALID_RESPONSE, ErrorKind.of("plan.error.malformed"));
        assertEquals(ErrorKind.CONFLICT, ErrorKind.of("error.session.changed"));
        assertEquals(ErrorKind.STORAGE_BUSY, ErrorKind.of("error.session.busy"));
        assertEquals(ErrorKind.STORAGE, ErrorKind.of("error.inventory.write"));
        assertEquals(ErrorKind.CANCELLED, ErrorKind.of("ai.error.interrupted"));
        assertEquals(ErrorKind.REJECTED, ErrorKind.of("plan.missing.requirement"));
        assertEquals(ErrorKind.REJECTED, ErrorKind.of("error.profile.unavailable"), "a local capability, not a provider outage");
        assertTrue(ErrorKind.TIMEOUT.external() && !ErrorKind.CONFLICT.external());
    }
}
