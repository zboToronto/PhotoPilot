package com.photopilot.progress;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Subject of the execution-feedback Observer: one user-started operation, its declared stage plan, the stages it
 * actually entered, its persistence outcome and its cancellation request. Services, adapters and repositories publish
 * through {@link Progress} while the operation is bound to their thread by {@link #run}; they never see listeners.
 *
 * <p>Thread use: the initiating (UI or CLI) thread creates the context and calls {@link #acknowledge()}; one worker
 * thread runs it; {@link #cancel()} may come from any thread. State changes and listener calls are serialized on this
 * object, so listeners see events in order.
 *
 * <p>Cancellation is cooperative and honest: a request interrupts the worker (which cancels an in-flight HTTP
 * exchange) unless a repository write has started; a started write always completes, and an operation whose write
 * succeeded is reported as completed, never as cancelled.
 */
public final class OperationContext {
    private static final ThreadLocal<OperationContext> CURRENT = new ThreadLocal<>();
    /** A tool loop can enter many stages; older ones are folded so an event stays small. */
    static final int MAX_STAGES = 24;

    private final UUID id = UUID.randomUUID();
    private final String feature, operation;
    private final boolean cancellable;
    private final LongSupplier nanos;
    private final long startNanos;
    private final Instant startedAt;
    private final List<ExecutionProgressListener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> pending;
    private final List<OperationStage> stages = new ArrayList<>();
    private OperationStatus status = OperationStatus.PENDING;
    private int attempt, maxAttempts, done, total;
    private ErrorKind error = ErrorKind.NONE;
    private List<LocalizedMessage> errors = List.of();
    private PersistenceStatus persistence = PersistenceStatus.NOT_APPLICABLE;
    private PersistenceStatus resultKind = PersistenceStatus.RESULT_AVAILABLE;
    private NextStep next;
    private Thread worker;
    private boolean writing, cancelRequested;
    private Duration finishedAfter;

    private OperationContext(String feature, String operation, List<String> plan, boolean cancellable, LongSupplier nanos, Instant startedAt) {
        this.feature = Objects.requireNonNull(feature); this.operation = Objects.requireNonNull(operation);
        this.pending = new ArrayList<>(plan); this.cancellable = cancellable; this.nanos = nanos;
        this.startNanos = nanos.getAsLong(); this.startedAt = startedAt;
    }

    /** {@code plan}: the stage keys this operation is expected to enter, in order (shown as pending until reached). */
    public static OperationContext create(String feature, String operation, List<String> plan, boolean cancellable) {
        return new OperationContext(feature, operation, plan, cancellable, System::nanoTime, Instant.now());
    }
    /** Test seam: a controllable clock for elapsed times. */
    static OperationContext create(String feature, String operation, List<String> plan, boolean cancellable, LongSupplier nanos) {
        return new OperationContext(feature, operation, plan, cancellable, nanos, Instant.now());
    }

    /** The operation bound to the calling thread, if any. */
    public static Optional<OperationContext> current() { return Optional.ofNullable(CURRENT.get()); }

    public UUID id() { return id; }
    public String feature() { return feature; }
    public String operation() { return operation; }
    public OperationContext listen(ExecutionProgressListener listener) { listeners.add(Objects.requireNonNull(listener)); return this; }
    /** What a successful result is when nothing was written: a draft awaiting confirmation, or just an available result. */
    public synchronized OperationContext resultIs(PersistenceStatus kind) { resultKind = Objects.requireNonNull(kind); return this; }

    /** Immediate acknowledgment on the initiating thread, before any work is queued: status PENDING with the plan. */
    public synchronized void acknowledge() { publish(); }

    /**
     * Runs {@code work} on the calling thread with this operation bound, so {@link Progress} calls inside it reach this
     * operation. The previous binding (normally none) is restored afterwards; a cancellation interrupt never leaks out.
     */
    public <T> T run(Supplier<T> work) {
        var previous = CURRENT.get();
        synchronized (this) {
            worker = Thread.currentThread();
            if (status == OperationStatus.PENDING) { status = OperationStatus.STARTED; publish(); }
        }
        CURRENT.set(this);
        try { return work.get(); }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            boolean interrupted;
            synchronized (this) { worker = null; interrupted = cancelRequested; }
            // The interrupt was ours (a cancel request); the worker thread goes back to its pool uninterrupted.
            if (interrupted) Thread.interrupted();
        }
    }

    // ---------------------------------------------------------------- stages published by the running code

    public void stage(String key, Object... arguments) { enter(key, arguments, OperationStatus.RUNNING); }
    public void waiting(String key, Object... arguments) { enter(key, arguments, OperationStatus.WAITING_FOR_EXTERNAL_SERVICE); }
    public void validating(String key, Object... arguments) { enter(key, arguments, OperationStatus.VALIDATING); }

    /**
     * A transient provider answer ({@code status}, e.g. "503") ended attempt {@code failed} of at most {@code maximum};
     * the next attempt starts after {@code delay}. Shown as "HTTP 503. Retry 1 of 2 in 1 s".
     */
    public void retrying(int failed, int maximum, String status, Duration delay) {
        synchronized (this) { attempt = failed + 1; maxAttempts = maximum; }
        enter("retry", new Object[]{status, failed, maximum - 1, String.valueOf(Math.max(1, (delay.toMillis() + 999) / 1000))}, OperationStatus.RETRYING);
    }
    /** The provider request currently sent is attempt {@code current} of at most {@code maximum}. */
    public synchronized void attempt(int current, int maximum) {
        if (finished() || attempt == current && maxAttempts == maximum) return;
        attempt = current; maxAttempts = maximum; publish();
    }
    /** Real units of work, e.g. 3 of 12 files read. */
    public synchronized void count(int completed, int all) {
        if (finished() || done == completed && total == all) return;
        done = completed; total = all; publish();
    }

    /**
     * A repository write is about to start; from here until {@link #written} a cancel request no longer interrupts.
     * Returns false, and starts nothing, when cancellation was already requested: a cancelled operation never writes.
     */
    public synchronized boolean writing() {
        if (cancelRequested) return false;
        if (finished()) return true;
        writing = true; persistence = PersistenceStatus.SAVING; status = OperationStatus.SAVING; publish();
        return true;
    }
    /** The repository's own outcome of the write that {@link #writing} announced. */
    public synchronized void written(Result<?> outcome) {
        if (finished()) return;
        writing = false;
        if (outcome.isSuccess()) persistence = PersistenceStatus.SAVED;
        else persistence = ErrorKind.of(outcome.messages()).persistence();
        status = stages.isEmpty() || stages.getLast().state() != OperationStage.State.ACTIVE ? OperationStatus.RUNNING : stages.getLast().kind();
        publish();
    }
    /** Guidance decided by the running code (e.g. a CLI command) for when it finishes. */
    public synchronized void next(NextStep step) { next = step; }

    private synchronized void enter(String key, Object[] arguments, OperationStatus kind) {
        if (finished()) return;
        var args = List.of(arguments);
        if (!stages.isEmpty() && stages.getLast().state() == OperationStage.State.ACTIVE) {
            var last = stages.getLast();
            if (last.key().equals(key) && last.arguments().equals(args) && last.kind() == kind) return;
            stages.set(stages.size() - 1, last.with(OperationStage.State.COMPLETED));
        }
        int declared = pending.indexOf(key);
        if (declared >= 0) pending.subList(0, declared + 1).clear();
        stages.add(new OperationStage(key, args, kind, OperationStage.State.ACTIVE, elapsed()));
        while (stages.size() > MAX_STAGES) stages.removeFirst();
        status = writing ? OperationStatus.SAVING : kind;
        publish();
    }

    // ---------------------------------------------------------------- outcome

    /**
     * Ends the operation from its result. A cancel request wins unless data was already saved; a success that saved
     * nothing keeps its declared result kind (draft or available); a failure is classified from its message keys.
     */
    public synchronized void finish(Result<?> result, Optional<NextStep> guidance) {
        if (finished()) return;
        guidance.ifPresent(step -> next = step);
        if (cancelRequested && persistence != PersistenceStatus.SAVED) { end(OperationStatus.CANCELLED, OperationStage.State.CANCELLED); return; }
        if (result.isSuccess()) {
            if (persistence == PersistenceStatus.NOT_APPLICABLE) persistence = resultKind;
            end(OperationStatus.COMPLETED, OperationStage.State.COMPLETED);
        } else {
            errors = result.messages(); error = ErrorKind.of(errors);
            if (persistence == PersistenceStatus.SAVING) persistence = error.persistence();
            end(OperationStatus.FAILED, OperationStage.State.FAILED);
        }
    }
    /** The worker threw instead of returning a result (a programming error); reported as a failure. */
    public synchronized void crash(Throwable failure) {
        finish(Result.failure(failure), Optional.empty());
    }
    private void end(OperationStatus outcome, OperationStage.State last) {
        if (!stages.isEmpty() && stages.getLast().state() == OperationStage.State.ACTIVE) stages.set(stages.size() - 1, stages.getLast().with(last));
        pending.clear();
        if (outcome == OperationStatus.CANCELLED) error = ErrorKind.CANCELLED;
        finishedAfter = elapsed(); writing = false; status = outcome;
        publish();
    }

    /**
     * Requests cancellation. Returns false when it cannot be honoured: not cancellable, already finished, or a
     * repository write has started (which completes). Otherwise the worker is interrupted, which cancels a pending
     * provider request; the result is then discarded and the operation ends CANCELLED.
     */
    public synchronized boolean cancel() {
        if (!cancellable || finished() || writing || cancelRequested) return false;
        cancelRequested = true;
        if (worker != null) worker.interrupt();
        publish();
        return true;
    }
    public synchronized boolean cancelRequested() { return cancelRequested; }
    public synchronized boolean finished() { return status.finished(); }
    public synchronized OperationStatus status() { return status; }
    public synchronized PersistenceStatus persistence() { return persistence; }

    /** Real time since creation, frozen when the operation finished (a UI may poll it to show a running clock). */
    public synchronized Duration elapsed() { return finishedAfter != null ? finishedAfter : Duration.ofNanos(Math.max(0, nanos.getAsLong() - startNanos)); }

    /** A snapshot of the current state (also what listeners receive). */
    public synchronized ExecutionProgressEvent snapshot() {
        return new ExecutionProgressEvent(id, feature, operation, status, stages, pending, startedAt, elapsed(), attempt, maxAttempts,
            error, errors, persistence, done, total, Optional.ofNullable(next), cancellable && !writing && !cancelRequested && !finished(), cancelRequested);
    }
    private void publish() {
        var event = snapshot();
        for (var listener : listeners) {
            try { listener.onProgress(event); }
            catch (RuntimeException ignored) { /* feedback must never break the operation it describes */ }
        }
    }
}
