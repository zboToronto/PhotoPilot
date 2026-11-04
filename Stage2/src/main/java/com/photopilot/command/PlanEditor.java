package com.photopilot.command;

import java.util.List;
import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.validation.PlanValidator;

/**
 * Stage 1 UML element 68 (zero-based): {F10; Command invoker; no mutation of saved base}
 * preview: copyForRevision() -> each command's applyTo(draft) -> PlanValidator.validateChange(base, draft).
 * The saved base is never touched; a failed command or validation discards the draft.
 */
public class PlanEditor {
    private PlanValidator validator;

    public PlanEditor(PlanValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Result<ShootPlan> preview(ShootPlan base, PlanCommand change) {
        return change == null ? Result.failureKey("plan.error.noChange") : preview(base, List.of(change));
    }

    /** Several commands from one interpreted request (e.g. lighter loadout and a new schedule) on the same draft. */
    public Result<ShootPlan> preview(ShootPlan base, List<PlanCommand> changes) {
        if (base == null) return Result.failureKey("plan.error.noSavedPlan");
        if (changes == null || changes.isEmpty() || changes.stream().anyMatch(Objects::isNull)) return Result.failureKey("plan.error.noChange");
        ShootPlan draft = base.copyForRevision();
        for (PlanCommand change : changes) {
            var applied = change.applyTo(draft);
            if (!applied.isSuccess()) return applied;
            draft = applied.value().orElseThrow();
        }
        return validator.validateChange(base, draft);
    }

    /** Applies and validates a change, then returns its approved copy. PlanningService.approve never reapplies commands. */
    public Result<ShootPlan> applyApproved(ShootPlan base, PlanCommand change) {
        var previewed = preview(base, change);
        return previewed.isSuccess() ? previewed.value().orElseThrow().approvedCopy() : previewed;
    }
}
