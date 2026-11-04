package com.photopilot.strategy;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.model.Loadout;

/**
 * Stage 1 UML element 60 (zero-based): {Strategy; only verified-compatible candidates}
 * Orders already validated candidates for one photography priority. Implementations may consult Gemini but can
 * only reorder the given candidates; fully suitable loadouts always stay ahead of partial matches.
 */
public interface LoadoutRankingStrategy {

    public Result<List<Loadout>> rank(List<Loadout> candidates, EquipmentRequirementProfile profile);

    /** Stable identifier used by GUI/CLI selection, e.g. "portability". */
    String name();

    /** Detailed ranking of evaluated candidates; {@code shootContext} is short non-identifying shoot context for AI. */
    Result<RankingOutcome> rankCandidates(List<Candidate> candidates, EquipmentRequirementProfile profile, String shootContext);
}
