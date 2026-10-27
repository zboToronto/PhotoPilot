package com.photopilot.strategy;

import java.util.List;
import java.util.Objects;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 62 (zero-based): {AI Concrete Strategy; prioritize focal coverage}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CoverageRanking implements LoadoutRankingStrategy {
    private PhotoPilotAgent agent;

    public CoverageRanking(PhotoPilotAgent agent) {
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    public Result<List<Loadout>> rank(List<Loadout> candidates, EquipmentRequirementProfile profile) {
        return Result.failureKey("error.notImplemented", "CoverageRanking.rank");
    }
}
