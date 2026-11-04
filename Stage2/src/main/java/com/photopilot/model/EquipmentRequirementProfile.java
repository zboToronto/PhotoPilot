package com.photopilot.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 37 (zero-based): {F06; capabilities only, no product identities}
 * Deterministic validation and explicit confirmation are implemented (minimal F06 path used by F07).
 * AI derivation of a profile (RequirementService.deriveProfile / PlanningService.analyzeNeeds) is NOT IMPLEMENTED.
 */
public class EquipmentRequirementProfile {
    public static final int MAX_NEEDS = 20;
    private List<CapabilityNeed> needs;
    private boolean reviewed;

    public EquipmentRequirementProfile(List<CapabilityNeed> needs, boolean reviewed) {
        this.needs = List.copyOf(needs);
        this.reviewed = reviewed;
    }

    /** Structural findings of the needs themselves, independent of any requirement or AI reasoning. */
    public List<LocalizedMessage> problems() {
        var found = new ArrayList<LocalizedMessage>();
        if (needs.isEmpty()) found.add(LocalizedMessage.of("needs.error.empty"));
        if (needs.size() > MAX_NEEDS) found.add(LocalizedMessage.of("needs.error.tooMany", MAX_NEEDS));
        var names = new HashSet<String>();
        for (CapabilityNeed need : needs) {
            if (!names.add(need.capability())) found.add(LocalizedMessage.of("needs.error.duplicate", LocalizedMessage.of("needs.capability." + need.capability())));
            found.addAll(need.problems());
        }
        return found;
    }

    /** Checks the needs and their consistency with the confirmed shooting requirement they belong to. */
    public Result<EquipmentRequirementProfile> validate(ShootRequirement req) {
        if (req == null || !req.confirmed()) return Result.failureKey("needs.error.requirement");
        var found = problems();
        need("cameraCount").ifPresent(need -> req.cameraCount().ifPresent(count -> {
            if (need.minimum().filter(min -> count < min).isPresent() || need.maximum().filter(max -> count > max).isPresent())
                found.add(LocalizedMessage.of("needs.error.cameraConflict", count));
        }));
        need("totalWeight").flatMap(CapabilityNeed::maximum).ifPresent(max -> req.maxWeightGrams().ifPresent(limit -> {
            if (max > limit) found.add(LocalizedMessage.of("needs.error.weightConflict", limit));
        }));
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }

    /** Explicitly confirmed (reviewed) copy; never produced by AI output alone. */
    public Result<EquipmentRequirementProfile> confirm() {
        var found = problems();
        return found.isEmpty() ? Result.success(new EquipmentRequirementProfile(needs, true)) : Result.failures(found);
    }

    /** Unreviewed copy for editing; the stored confirmed profile stays unchanged until a new confirmation saves. */
    public EquipmentRequirementProfile asDraft() { return new EquipmentRequirementProfile(needs, false); }

    public Optional<CapabilityNeed> need(String capability) {
        return needs.stream().filter(need -> need.capability().equals(capability)).findFirst();
    }
    public List<CapabilityNeed> needs() { return needs; }
    public boolean reviewed() { return reviewed; }
}
