package com.photopilot.progress;

import com.photopilot.i18n.LocalizedMessage;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One stage the operation actually entered, in execution order. {@code key} names a localized label
 * ({@code progress.stage.<key>}); {@code kind} is the status the operation had while the stage ran.
 */
public record OperationStage(String key, List<Object> arguments, OperationStatus kind, State state, Duration startedAfter) {
    public enum State { COMPLETED, ACTIVE, FAILED, CANCELLED }

    public OperationStage {
        Objects.requireNonNull(key); arguments = List.copyOf(arguments); Objects.requireNonNull(kind); Objects.requireNonNull(state);
        Objects.requireNonNull(startedAfter);
    }

    public LocalizedMessage label() { return new LocalizedMessage("progress.stage." + key, arguments); }

    /**
     * Planned stages whose active label names what it works on ("Checking {0}" for a catalog source, the sun-times date,
     * the candidate count): before they start there is no argument, so they have their own queued label
     * ({@code progress.queued.<key>}). Stage2OptRe2 D02: the queued row showed the raw "Checking {0}".
     */
    public static final java.util.Set<String> PARAMETERIZED = java.util.Set.of("import.source", "env.sun", "match.rank");

    /** Label of a planned stage that has not started yet (never a template still waiting for its argument). */
    public static LocalizedMessage queued(String key) {
        return LocalizedMessage.of(PARAMETERIZED.contains(key) ? "progress.queued." + key : "progress.stage." + key);
    }

    OperationStage with(State next) { return new OperationStage(key, arguments, kind, next, startedAfter); }
}
