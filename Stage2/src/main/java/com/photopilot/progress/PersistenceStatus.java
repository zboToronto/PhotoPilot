package com.photopilot.progress;

/**
 * What the user's data looks like after (or during) an operation. {@link #SAVED} is only ever set after a repository
 * reported a completed write; a result that was produced but not written is {@link #UNSAVED_DRAFT}.
 */
public enum PersistenceStatus {
    /** The operation does not produce or store data (lookups, calculations). */
    NOT_APPLICABLE,
    /** A result was produced and is shown; nothing needs saving (e.g. an environment assessment kept in memory). */
    RESULT_AVAILABLE,
    /** A result was produced that is only kept after an explicit user confirmation (approve, confirm, save). */
    UNSAVED_DRAFT,
    /** A repository write is in progress. */
    SAVING,
    /** The repository confirmed the write. */
    SAVED,
    /** The write failed; the stored data is unchanged and the user's input is kept. */
    SAVE_FAILED,
    /** Another view or process changed the record first; nothing was overwritten. */
    CONFLICT,
    /** Storage was busy (another writer); nothing was written and the action can be retried. */
    RETRY_REQUIRED
}
