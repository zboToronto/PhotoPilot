package com.photopilot.strategy;

import java.util.List;
import java.util.Objects;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 61 (zero-based): {AI Concrete Strategy; prioritize carrying weight}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PortabilityRanking implements LoadoutRankingStrategy {
    private PhotoPilotAgent agent;

    public PortabilityRanking(PhotoPilotAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    public Result<List<Loadout>> rank(List<Loadout> candidates, EquipmentRequirementProfile profile) {
        return Result.failureKey("error.notImplemented", "PortabilityRanking.rank");
    }
}
