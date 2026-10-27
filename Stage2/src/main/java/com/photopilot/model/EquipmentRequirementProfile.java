package com.photopilot.model;

import java.util.List;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 37 (zero-based): {F06; capabilities only, no product identities}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EquipmentRequirementProfile {
    private List<CapabilityNeed> needs;
    private boolean reviewed;

    public EquipmentRequirementProfile(List<CapabilityNeed> needs, boolean reviewed) {
        this.needs = List.copyOf(needs);
        this.reviewed = reviewed;
    }

    public Result<EquipmentRequirementProfile> validate(ShootRequirement req) {
        return Result.failureKey("error.notImplemented", "EquipmentRequirementProfile.validate");
    }

    public Result<EquipmentRequirementProfile> confirm() {
        return Result.failureKey("error.notImplemented", "EquipmentRequirementProfile.confirm");
    }
}
