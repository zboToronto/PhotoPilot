package com.photopilot.progress;

import com.photopilot.i18n.LocalizedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable snapshot of one operation, published to {@link ExecutionProgressListener}s whenever its state changes.
 * {@code stages} are the stages actually entered, oldest first (the last one is ACTIVE while the operation runs);
 * {@code pending} are declared stages not reached yet. {@code done}/{@code total} are real work units (files) or 0/0.
 * {@code cancellable} says whether a cancel request can still be honoured now; {@code cancelling} that one was made.
 * Events carry localization keys and arguments only: no prompts, model text, credentials or file contents.
 */
public record ExecutionProgressEvent(UUID operationId, String feature, String operation, OperationStatus status,
        List<OperationStage> stages, List<String> pending, Instant startedAt, Duration elapsed,
        int attempt, int maxAttempts, ErrorKind error, List<LocalizedMessage> errors, PersistenceStatus persistence,
        int done, int total, Optional<NextStep> next, boolean cancellable, boolean cancelling) {

    public ExecutionProgressEvent {
        Objects.requireNonNull(operationId); Objects.requireNonNull(feature); Objects.requireNonNull(operation); Objects.requireNonNull(status);
        stages = List.copyOf(stages); pending = List.copyOf(pending); Objects.requireNonNull(startedAt); Objects.requireNonNull(elapsed);
        Objects.requireNonNull(error); errors = List.copyOf(errors); Objects.requireNonNull(persistence); Objects.requireNonNull(next);
    }

    /** The stage running now, if any. */
    public Optional<OperationStage> current() {
        return stages.isEmpty() || stages.getLast().state() != OperationStage.State.ACTIVE ? Optional.empty() : Optional.of(stages.getLast());
    }
    public List<OperationStage> completed() {
        return stages.stream().filter(stage -> stage.state() == OperationStage.State.COMPLETED).toList();
    }
    /** The next declared stage, when known. */
    public Optional<String> upcoming() { return pending.isEmpty() ? Optional.empty() : Optional.of(pending.getFirst()); }
    public boolean measurable() { return total > 0; }
}
