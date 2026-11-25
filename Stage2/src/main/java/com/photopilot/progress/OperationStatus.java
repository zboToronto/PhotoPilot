package com.photopilot.progress;

/**
 * Execution state of one user-started operation, as published in {@link ExecutionProgressEvent}s. Every state comes
 * from a real point in the execution (a service stage, a provider request, a retry wait, a repository write); none is
 * driven by a timer.
 */
public enum OperationStatus {
    /** Created and shown, not yet running on a worker (queued behind other work). */
    PENDING,
    /** Picked up by its worker. */
    STARTED,
    /** A local stage is running (reading storage, assembling evidence, deterministic checks). */
    RUNNING,
    /** A request to an external service (Gemini, weather, geocoding, catalogue) has been sent and has not returned. */
    WAITING_FOR_EXTERNAL_SERVICE,
    /** A transient provider failure was received; the bounded retry policy is waiting before the next attempt. */
    RETRYING,
    /** Returned data is being checked (schema, domain validation, evidence rules). */
    VALIDATING,
    /** A repository write has started and has not been confirmed. */
    SAVING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean finished() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
}
