package com.photopilot.command;


import com.photopilot.common.Result;

/**
 * Stage 1 UML element 69 (zero-based): {Command contract; validated by invoker}
 * One controlled plan modification. Commands only change a copyForRevision() draft; PlanEditor validates the result.
 */
public interface PlanCommand {

    public Result<ShootPlan> applyTo(ShootPlan draft);
}
