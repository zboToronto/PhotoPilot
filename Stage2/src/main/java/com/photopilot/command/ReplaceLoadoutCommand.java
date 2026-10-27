package com.photopilot.command;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 70 (zero-based): {Concrete Command; lighter or different gear}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ReplaceLoadoutCommand implements PlanCommand {
    private Loadout replacement;

    public ReplaceLoadoutCommand(Loadout replacement) {
        this.replacement = Objects.requireNonNull(replacement, "replacement");
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        return Result.failure("NOT IMPLEMENTED: ReplaceLoadoutCommand.applyTo");
    }
}
