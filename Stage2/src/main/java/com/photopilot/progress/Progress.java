package com.photopilot.progress;

import com.photopilot.common.Result;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Publication side of the execution feedback for code that runs inside an operation (services, the agent, adapters,
 * repositories). Each call reaches the {@link OperationContext} bound to the calling thread and does nothing when no
 * operation is bound (tests, tools, background reads), so business code never depends on a UI or a listener.
 * Stage keys name {@code progress.stage.<key>} labels; arguments must not contain prompts, model output or secrets.
 */
public final class Progress {
    private Progress() { }

    public static void stage(String key, Object... arguments) { OperationContext.current().ifPresent(c -> c.stage(key, arguments)); }
    public static void waiting(String key, Object... arguments) { OperationContext.current().ifPresent(c -> c.waiting(key, arguments)); }
    public static void validating(String key, Object... arguments) { OperationContext.current().ifPresent(c -> c.validating(key, arguments)); }
    public static void retrying(int failed, int maximum, String status, Duration delay) {
        OperationContext.current().ifPresent(c -> c.retrying(failed, maximum, status, delay));
    }
    public static void attempt(int current, int maximum) { OperationContext.current().ifPresent(c -> c.attempt(current, maximum)); }
    public static void count(int done, int total) { OperationContext.current().ifPresent(c -> c.count(done, total)); }
    public static void next(NextStep step) { OperationContext.current().ifPresent(c -> c.next(step)); }

    /** True when the bound operation was asked to stop; long loops and retry waits check it between steps. */
    public static boolean cancelled() { return OperationContext.current().map(OperationContext::cancelRequested).orElse(false); }

    /**
     * Brackets one repository write: announces it (persistence SAVING, no interrupt from here on), runs it and records
     * the repository's own outcome (SAVED only on success). A cancelled operation does not start the write.
     */
    public static <T> Result<T> write(Supplier<Result<T>> write) {
        var context = OperationContext.current();
        if (context.isEmpty()) return write.get();
        if (!context.get().writing()) return Result.failureKey("progress.cancelled");
        Result<T> outcome = null;
        try { outcome = write.get(); return outcome; }
        finally { context.get().written(outcome == null ? Result.failureKey("error.session.storage", "write failed") : outcome); }
    }
}
