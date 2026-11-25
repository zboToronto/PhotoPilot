package com.photopilot.ui.gui;

import com.photopilot.common.Result;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import javafx.application.Platform;
import javafx.concurrent.Task;

/**
 * One window's worker lanes, counted through JavaFX completion delivery rather than only disk execution.
 * {@link #execute} is the ordered local lane: storage reads/writes and quick work run one at a time in submission
 * order. {@link #executeBackground} is for operations that wait on an external service or long local work (Gemini,
 * weather, catalogues, metadata scans): they run on a small pool beside the local lane, so a slow provider never holds
 * up another page's local work. Both lanes count as busy, which profile switching and window closing rely on; repository
 * writers of this process queue for each other (AtomicFiles), so the lanes never fail each other with "busy".
 */
final class UiOperations extends AbstractExecutorService {
    /** At most this many external/long operations run at once; more wait in order (shown as queued). */
    static final int BACKGROUND_THREADS = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        var thread = new Thread(action, "photopilot-gui-operation"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger backgroundThreads = new AtomicInteger();
    private final ThreadPoolExecutor background = new ThreadPoolExecutor(BACKGROUND_THREADS, BACKGROUND_THREADS, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), action -> {
            var thread = new Thread(action, "photopilot-gui-background-" + backgroundThreads.incrementAndGet()); thread.setDaemon(true); return thread;
        });
    private final AtomicInteger pending = new AtomicInteger();

    UiOperations() { background.allowCoreThreadTimeOut(true); }

    boolean busy() { return pending.get() != 0; }
    @Override public void execute(Runnable action) { submitTo(worker, action); }
    /** Runs {@code action} beside the ordered local lane (see the class comment). */
    void executeBackground(Runnable action) { submitTo(background, action); }
    private void submitTo(ExecutorService lane, Runnable action) {
        pending.incrementAndGet();
        try { lane.execute(() -> {
            try { action.run(); }
            finally { Platform.runLater(pending::decrementAndGet); }
        }); } catch (RuntimeException failure) { pending.decrementAndGet(); throw failure; }
    }
    /** Background submission when {@code executor} is a window's lanes; any other executor (tests) runs it itself. */
    static void background(Executor executor, Runnable action) {
        if (executor instanceof UiOperations lanes) lanes.executeBackground(action); else executor.execute(action);
    }
    <T> void run(Supplier<Result<T>> operation, Consumer<Result<T>> completed) { execute(task(operation, completed)); }
    /** {@link #run} on the background lane, for work that waits on an external service. */
    <T> void runBackground(Supplier<Result<T>> operation, Consumer<Result<T>> completed) { executeBackground(task(operation, completed)); }
    private static <T> Task<Result<T>> task(Supplier<Result<T>> operation, Consumer<Result<T>> completed) {
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(event -> completed.accept(task.getValue()));
        task.setOnFailed(event -> completed.accept(Result.failureKey("error.profile.operation",
            Objects.toString(task.getException().getMessage(), task.getException().getClass().getSimpleName()))));
        return task;
    }
    @Override public void shutdown() { worker.shutdown(); background.shutdown(); }
    @Override public List<Runnable> shutdownNow() {
        var dropped = new ArrayList<>(worker.shutdownNow()); dropped.addAll(background.shutdownNow()); return dropped;
    }
    @Override public boolean isShutdown() { return worker.isShutdown() && background.isShutdown(); }
    @Override public boolean isTerminated() { return worker.isTerminated() && background.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        return worker.awaitTermination(timeout, unit) && background.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
}
