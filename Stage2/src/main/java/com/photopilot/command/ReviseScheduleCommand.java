package com.photopilot.command;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.ShootingWindow;

/**
 * Stage 1 UML element 71 (zero-based): {Concrete Command; timing change}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ReviseScheduleCommand implements PlanCommand {
    private List<ShootingWindow> windows;

    public ReviseScheduleCommand(List<ShootingWindow> windows) {
        this.windows = List.copyOf(windows);
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        return Result.failureKey("error.notImplemented", "ReviseScheduleCommand.applyTo");
    }
}
