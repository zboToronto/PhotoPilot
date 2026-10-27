package com.photopilot.command;


import com.photopilot.common.Result;

/**
 * Stage 1 UML element 69 (zero-based): {Command contract; validated by invoker}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface PlanCommand {

    public Result<ShootPlan> applyTo(ShootPlan draft);
}
