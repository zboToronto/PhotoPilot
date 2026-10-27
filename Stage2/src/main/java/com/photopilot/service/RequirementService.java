package com.photopilot.service;

import java.util.Objects;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.ShootRequirement;

/**
 * Stage 1 UML element 35 (zero-based): {F03, F06; AI interpretation, deterministic gates}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class RequirementService {
    private PhotoPilotAgent agent;

    public RequirementService(PhotoPilotAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    public Result<ShootRequirement> parse(String text, AgentContext context) {
        return Result.failureKey("error.notImplemented", "RequirementService.parse");
    }

    public Result<ShootRequirement> clarify(ShootRequirement draft, String answers) {
        return Result.failureKey("error.notImplemented", "RequirementService.clarify");
    }

    public Result<ShootRequirement> confirm(ShootRequirement edited) {
        return Result.failureKey("error.notImplemented", "RequirementService.confirm");
    }

    public Result<EquipmentRequirementProfile> deriveProfile(ShootRequirement req) {
        return Result.failureKey("error.notImplemented", "RequirementService.deriveProfile");
    }

    public Result<EquipmentRequirementProfile> validateProfile(EquipmentRequirementProfile profile) {
        return Result.failureKey("error.notImplemented", "RequirementService.validateProfile");
    }
}
