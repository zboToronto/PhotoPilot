package com.photopilot.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 70 (zero-based): {Concrete Command; lighter or different gear}
 * Replaces the draft's loadout (and, when given, its equipment notes). The replacement comes from the verified
 * F07 candidates; PlanValidator re-runs UC14 and the requirement checks on the result.
 */
public class ReplaceLoadoutCommand implements PlanCommand {
    private Loadout replacement;
    private Optional<List<String>> equipmentNotes = Optional.empty();

    public ReplaceLoadoutCommand(Loadout replacement) {
        this.replacement = Objects.requireNonNull(replacement, "replacement");
    }

    public ReplaceLoadoutCommand(Loadout replacement, List<String> equipmentNotes) {
        this(replacement);
        this.equipmentNotes = Optional.of(List.copyOf(equipmentNotes));
    }

    public Result<ShootPlan> applyTo(ShootPlan draft) {
        if (draft == null) return Result.failureKey("plan.error.noDraft");
        if (draft.approved()) return Result.failureKey("plan.error.immutable");
        draft.replaceLoadout(replacement);
        equipmentNotes.ifPresent(draft::reviseEquipmentNotes);
        return Result.success(draft);
    }
}
