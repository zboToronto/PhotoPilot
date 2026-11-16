package com.photopilot.command;

import java.util.Map;
import com.photopilot.common.Result;

/**
 * Concrete Command added in Stage 2: replaces the draft's session facts (subject, date, place, time, ...) with the ones
 * derived from the current confirmed requirement. PlanningService adds it to a revision when the shooting date or place
 * changed since the base was approved, so an approved revision never shows the facts of an earlier shoot. Like every
 * command it only changes a copyForRevision() draft; the facts come from application data, never from the model.
 */
public class ReviseSessionInfoCommand implements PlanCommand {
    private final Map<String, String> sessionInfo;

    public ReviseSessionInfoCommand(Map<String, String> sessionInfo) {
        this.sessionInfo = Map.copyOf(sessionInfo);
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        if (draft == null) return Result.failureKey("plan.error.noDraft");
        if (draft.approved()) return Result.failureKey("plan.error.immutable");
        draft.reviseSessionInfo(sessionInfo);
        return Result.success(draft);
    }
}
