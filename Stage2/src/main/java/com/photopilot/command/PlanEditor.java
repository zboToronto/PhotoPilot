package com.photopilot.command;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.validation.PlanValidator;

/**
 * Stage 1 UML element 68 (zero-based): {F10; Command invoker; no mutation of saved base}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PlanEditor {
    private PlanValidator validator;

    public PlanEditor(PlanValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Result<ShootPlan> preview(ShootPlan base, PlanCommand change) {
        return Result.failure("NOT IMPLEMENTED: PlanEditor.preview");
    }

    public Result<ShootPlan> applyApproved(ShootPlan base, PlanCommand change) {
        return Result.failure("NOT IMPLEMENTED: PlanEditor.applyApproved");
    }
}
