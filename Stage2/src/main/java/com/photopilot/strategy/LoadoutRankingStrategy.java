package com.photopilot.strategy;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 60 (zero-based): {Strategy; only verified-compatible candidates}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface LoadoutRankingStrategy {

    public Result<List<Loadout>> rank(List<Loadout> candidates, EquipmentRequirementProfile profile);
}
