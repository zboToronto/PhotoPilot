package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.GearInventory;
import com.photopilot.model.Loadout;
import com.photopilot.strategy.LoadoutRankingStrategy;
import com.photopilot.validation.CompatibilityValidator;

/**
 * Stage 1 UML element 57 (zero-based): {F07; Strategy context; postvalidates ranked IDs}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GearMatchingService {
    private CompatibilityValidator validator;
    private LoadoutRankingStrategy ranking;

    public GearMatchingService(CompatibilityValidator validator, LoadoutRankingStrategy ranking) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.ranking = Objects.requireNonNull(ranking, "ranking");
    }

    public Result<List<Loadout>> match(EquipmentRequirementProfile profile, GearInventory gear) {
        return Result.failure("NOT IMPLEMENTED: GearMatchingService.match");
    }

    public void setRanking(LoadoutRankingStrategy strategy) {
        this.ranking = Objects.requireNonNull(strategy, "strategy");
    }

    public Result<List<Loadout>> compatibleCandidates(GearInventory gear) {
        return Result.failure("NOT IMPLEMENTED: GearMatchingService.compatibleCandidates");
    }
}
