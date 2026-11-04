package com.photopilot.command;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.ShootingWindow;

/**
 * Stage 1 UML element 71 (zero-based): {Concrete Command; timing change}
 * Replaces the draft's schedule windows; availability, time-zone and evidence checks belong to PlanValidator.
 */
public class ReviseScheduleCommand implements PlanCommand {
    private List<ShootingWindow> windows;

    public ReviseScheduleCommand(List<ShootingWindow> windows) {
        this.windows = List.copyOf(windows);
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        if (draft == null) return Result.failureKey("plan.error.noDraft");
        if (draft.approved()) return Result.failureKey("plan.error.immutable");
        draft.reviseSchedule(windows);
        return Result.success(draft);
    }
}
