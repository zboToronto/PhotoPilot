package com.photopilot.workflow;

/**
 * Persistent business state of one milestone, derived from stored records only. A running request or an unsaved draft
 * never produces a status here; operation-level progress is {@link com.photopilot.progress.OperationStatus}.
 */
public enum MilestoneStatus {
    /** Nothing recorded yet, and nothing earlier stands in the way. */
    NOT_STARTED,
    /** Part of the milestone is stored (e.g. photos analysed, review not saved). */
    IN_PROGRESS,
    /** Confirmed, saved and still valid for the session's current state. */
    COMPLETED,
    /** Stored state asks for the user's action now (e.g. the shoot date has passed; never completed automatically). */
    ACTION_REQUIRED,
    /** An earlier milestone must be completed first. */
    BLOCKED,
    /** Completed earlier, but an upstream change means it should be confirmed again; the old record is kept. */
    NEEDS_REVIEW,
    /** Optional and not used in this session, so it is left out of the progress count. */
    NOT_APPLICABLE;

    /** Counted in the denominator of session progress. */
    public boolean applicable() { return this != NOT_APPLICABLE; }
    /** Can be recommended as the next step. */
    public boolean actionable() { return this == NOT_STARTED || this == IN_PROGRESS || this == ACTION_REQUIRED || this == NEEDS_REVIEW; }
    /** Asks for attention beyond simply being the next step. */
    public boolean attention() { return this == NEEDS_REVIEW || this == ACTION_REQUIRED; }
    public String labelKey() { return "workflow.status." + name(); }
}
