package com.photopilot.ui.gui;

import com.photopilot.application.NextSteps;
import com.photopilot.application.OperationKind;
import com.photopilot.common.Result;
import com.photopilot.progress.OperationContext;
import com.photopilot.progress.OperationStatus;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.concurrent.Task;

/**
 * One page's tracked operations: creates the {@link OperationContext} (the Observer subject), shows it in the page's
 * {@link OperationProgressView} in the same UI pulse as the click, runs the work on the right lane (background for
 * external/long work, the ordered local lane otherwise) with the operation bound to the worker thread, then ends it with
 * the deterministic next step. Results of an operation that was cancelled, or replaced by a newer one of the same page,
 * are never delivered to the page. The page keeps its own busy flag and control locking, as before.
 */
final class OperationRunner {
    private final Executor executor;
    private final OperationProgressView view;
    private long generation;
    private OperationContext current;
    /** The session the page's operation works on, and that session's recommended next milestone (window workflow model). */
    private Supplier<java.util.Optional<java.util.UUID>> session = java.util.Optional::empty;
    private java.util.function.Function<java.util.UUID, java.util.Optional<com.photopilot.progress.NextStep>> sessionNext = id -> java.util.Optional.empty();

    OperationRunner(Executor executor, OperationProgressView view) { this.executor = executor; this.view = view; }

    OperationProgressView view() { return view; }
    /**
     * After an operation that saved a session milestone ({@link OperationKind#advancesSession()}) the guidance is the
     * session's own next milestone from {@code next} (the workflow model, which already holds the save: it is applied
     * when the store announces it, before this task's completion runs), so the card, the sidebar and the session pages
     * name the same step. Other outcomes keep {@link NextSteps#after}. {@code session} is the session the operation
     * worked on; the card's Continue opens the next page with that session, even if another one has become current.
     */
    void guidance(Supplier<java.util.Optional<java.util.UUID>> session,
            java.util.function.Function<java.util.UUID, java.util.Optional<com.photopilot.progress.NextStep>> next) {
        this.session = java.util.Objects.requireNonNull(session); sessionNext = java.util.Objects.requireNonNull(next);
    }
    /** The running or last operation of this page (tests and accessibility). */
    OperationContext current() { return current; }

    /**
     * Runs {@code work} as {@code kind}. {@code completed} receives the result on the FX thread when the operation
     * completed or failed; {@code cancelled} runs instead when the user cancelled it before anything was saved.
     */
    <T> void run(OperationKind kind, Supplier<Result<T>> work, Consumer<Result<T>> completed, Runnable cancelled) {
        long token = ++generation;
        var context = kind.start();
        context.listen(view.listener(context));
        current = context;
        view.track(kind, context);
        context.acknowledge();
        Task<Result<T>> task = new Task<>() {
            @Override protected Result<T> call() {
                // Cancelled while queued: nothing ran, nothing to deliver.
                if (context.cancelRequested()) return Result.failureKey("progress.cancelled");
                return context.run(work);
            }
        };
        task.setOnSucceeded(event -> finish(token, kind, context, task.getValue(), completed, cancelled));
        task.setOnFailed(event -> {
            context.crash(task.getException());
            if (token == generation) completed.accept(Result.failure(task.getException()));
        });
        if (kind.background()) UiOperations.background(executor, task); else executor.execute(task);
    }

    private <T> void finish(long token, OperationKind kind, OperationContext context, Result<T> result, Consumer<Result<T>> completed, Runnable cancelled) {
        // Cancel requests come from this (FX) thread too, so the check and the outcome below cannot disagree.
        boolean discarded = context.cancelRequested() && context.persistence() != com.photopilot.progress.PersistenceStatus.SAVED;
        var next = discarded ? java.util.Optional.of(com.photopilot.progress.NextStep.of("next.cancelled")) : NextSteps.after(kind, result);
        if (!discarded && result.isSuccess() && kind.advancesSession() && context.persistence() == com.photopilot.progress.PersistenceStatus.SAVED) {
            var id = session.get();
            var milestone = id.flatMap(sessionNext);
            // A complete session has no next milestone: the static step (e.g. back to the session) stays.
            if (milestone.isPresent()) { next = milestone; view.continueFor(id.orElseThrow()); }
        }
        context.finish(result, next);
        if (token != generation) return;
        if (context.status() == OperationStatus.CANCELLED) cancelled.run(); else completed.accept(result);
    }

    /** Cancels the running operation if it can still be cancelled safely; returns whether the request was accepted. */
    boolean cancel() { return current != null && current.cancel(); }
}
