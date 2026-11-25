package com.photopilot.workflow;

import com.photopilot.i18n.LocalizedMessage;
import java.util.Objects;

/** One milestone's status with the short reason it was derived from (a message key; never model text). */
public record MilestoneState(Milestone milestone, MilestoneStatus status, LocalizedMessage detail) {
    public MilestoneState {
        Objects.requireNonNull(milestone, "milestone"); Objects.requireNonNull(status, "status"); Objects.requireNonNull(detail, "detail");
    }
}
