package com.photopilot.ui.cli;

import com.photopilot.application.NextSteps;
import com.photopilot.application.OperationKind;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.progress.*;
import java.io.PrintStream;
import java.util.function.Supplier;

/**
 * CLI presentation of the shared execution feedback: the same {@link OperationKind} operations and service-published
 * events the GUI shows, printed as short prefixed lines on the progress stream (standard error in the real CLI), so
 * standard output, its machine-readable text and the exit codes stay exactly as before. Every line is flushed at once.
 * <pre>
 * [F10] Loading requirements and profile
 * [F10] Waiting for Gemini
 * [F10] HTTP 503. Retry 1 of 2 in 1 s
 * [F10] Generating photography plan: Failed (31.2 s)
 * [NEXT] Retry; your inputs are unchanged.
 * </pre>
 */
final class CliProgress {
    /** The sink of the command running on this (the CLI's) thread; none for embedded CLIs that did not ask for it. */
    private static final ThreadLocal<CliProgress> SINK = new ThreadLocal<>();
    private final PrintStream stream;
    private final LocalizationService localization;
    /** The session's recommended next milestone from the shared workflow (the GUI sidebar's source). */
    private final java.util.function.Function<java.util.UUID, java.util.Optional<NextStep>> sessionNext;

    private CliProgress(PrintStream stream, LocalizationService localization, java.util.function.Function<java.util.UUID, java.util.Optional<NextStep>> sessionNext) {
        this.stream = stream; this.localization = localization; this.sessionNext = sessionNext;
    }

    /** Runs one CLI command with progress printed to {@code stream} (nothing when {@code stream} is null). */
    static <T> T within(PrintStream stream, LocalizationService localization, Supplier<T> command) {
        return within(stream, localization, id -> java.util.Optional.empty(), command);
    }
    /** As above; after a session milestone was saved, the [NEXT] line is {@code sessionNext} of that session. */
    static <T> T within(PrintStream stream, LocalizationService localization,
            java.util.function.Function<java.util.UUID, java.util.Optional<NextStep>> sessionNext, Supplier<T> command) {
        if (stream == null) return command.get();
        var previous = SINK.get();
        SINK.set(new CliProgress(stream, localization, sessionNext));
        try { return command.get(); }
        finally { if (previous == null) SINK.remove(); else SINK.set(previous); }
    }

    /**
     * Runs {@code work} as {@code kind} with the operation bound to this thread (services and adapters publish its
     * stages), then ends it with the shared deterministic next step. The result is returned unchanged.
     */
    static <T> Result<T> run(OperationKind kind, Supplier<Result<T>> work) { return run(kind, null, work); }

    /**
     * As {@link #run(OperationKind, Supplier)} for an operation on {@code session}: when it saved a session milestone
     * ({@link OperationKind#advancesSession()}), the next step is the session's own next milestone, so the CLI names the
     * same step as the GUI. Only computed when progress is printed; standard output is never affected.
     */
    static <T> Result<T> run(OperationKind kind, java.util.UUID session, Supplier<Result<T>> work) {
        var sink = SINK.get();
        var context = kind.start();
        if (sink != null) context.listen(sink.printer(kind));
        Result<T> result;
        try { result = context.run(work); }
        catch (RuntimeException failure) { context.crash(failure); throw failure; }
        var next = NextSteps.after(kind, result);
        if (sink != null && session != null && result.isSuccess() && kind.advancesSession() && context.persistence() == PersistenceStatus.SAVED) {
            var milestone = sink.sessionNext.apply(session);
            if (milestone.isPresent()) next = milestone;
        }
        context.finish(result, next);
        return result;
    }

    /** One operation's printer: a line per stage entered, per persistence outcome, the outcome and the next step. */
    private ExecutionProgressListener printer(OperationKind kind) {
        String prefix = kind.feature();
        return new ExecutionProgressListener() {
            /** Stages already printed, identified by key, start offset and arguments (events repeat earlier stages). */
            private final java.util.Set<String> printed = new java.util.HashSet<>();
            private PersistenceStatus printedPersistence = PersistenceStatus.NOT_APPLICABLE;
            @Override public void onProgress(ExecutionProgressEvent event) {
                for (var stage : event.stages())
                    if (printed.add(stage.key() + '|' + stage.startedAfter().toNanos() + '|' + stage.arguments()))
                        line(prefix, stage.label().render(localization));
                var saved = event.persistence();
                if (saved != printedPersistence && (saved == PersistenceStatus.SAVED || saved == PersistenceStatus.SAVE_FAILED
                        || saved == PersistenceStatus.CONFLICT || saved == PersistenceStatus.RETRY_REQUIRED)) {
                    line(prefix, localization.text("progress.persistence." + saved));
                }
                printedPersistence = saved;
                if (!event.status().finished()) return;
                stream.println(localization.text("progress.cli.done", prefix, localization.text(kind.titleKey()),
                    localization.text("progress.status." + event.status()), seconds(event.elapsed())));
                event.next().ifPresent(step -> stream.println(localization.text("progress.cli.next", step.message().render(localization))));
                stream.flush();
            }
        };
    }
    private void line(String prefix, String text) { stream.println(localization.text("progress.cli.stage", prefix, text)); stream.flush(); }
    private static String seconds(java.time.Duration elapsed) { return String.format(java.util.Locale.ROOT, "%.1f", elapsed.toMillis() / 1000.0); }
}
