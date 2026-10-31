package com.photopilot.ui.gui;

import com.photopilot.common.Result;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import javafx.application.Platform;
import javafx.concurrent.Task;

/** One window's worker queue, counted through JavaFX completion delivery rather than only disk execution. */
final class UiOperations extends AbstractExecutorService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        var thread = new Thread(action, "photopilot-gui-operation"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger pending = new AtomicInteger();
    boolean busy() { return pending.get() != 0; }
    @Override public void execute(Runnable action) {
        pending.incrementAndGet();
        try { worker.execute(() -> {
            try { action.run(); }
            finally { Platform.runLater(pending::decrementAndGet); }
        }); } catch (RuntimeException failure) { pending.decrementAndGet(); throw failure; }
    }
    <T> void run(Supplier<Result<T>> operation, Consumer<Result<T>> completed) {
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(event -> completed.accept(task.getValue()));
        task.setOnFailed(event -> completed.accept(Result.failureKey("error.profile.operation",
            Objects.toString(task.getException().getMessage(), task.getException().getClass().getSimpleName()))));
        execute(task);
    }
    @Override public void shutdown() { worker.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return worker.shutdownNow(); }
    @Override public boolean isShutdown() { return worker.isShutdown(); }
    @Override public boolean isTerminated() { return worker.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return worker.awaitTermination(timeout, unit); }
}
