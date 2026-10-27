package com.photopilot.validation;

import java.util.Objects;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.ShootRequirement;
import com.photopilot.service.TechnicalCalculator;

/**
 * Stage 1 UML element 74 (zero-based): {deterministic; recompute numbers, reject fixed-constraint changes}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PlanValidator {
    private CompatibilityValidator compatibility;
    private TechnicalCalculator calculator;

    public PlanValidator(CompatibilityValidator compatibility, TechnicalCalculator calculator) {
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    public Result<ShootPlan> validate(ShootPlan plan, ShootRequirement req) {
        return Result.failureKey("error.notImplemented", "PlanValidator.validate");
    }

    public Result<ShootPlan> validateChange(ShootPlan base, ShootPlan next) {
        return Result.failureKey("error.notImplemented", "PlanValidator.validateChange");
    }
}
