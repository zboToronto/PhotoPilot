package com.photopilot.strategy;

import java.util.Comparator;
import com.photopilot.agent.PhotoPilotAgent;

/**
 * Stage 1 UML element 62 (zero-based): {AI Concrete Strategy; prioritize focal coverage}
 * After suitability: widest verified focal coverage (logarithmic span), then longest reach, then lower weight.
 */
public class CoverageRanking extends AiAssistedRanking {

    public CoverageRanking(PhotoPilotAgent agent) {
        super(agent);
    }

    public String name() { return "coverage"; }

    Comparator<Metrics> priorityOrder() {
        return Comparator.comparingDouble(Metrics::focalSpan).reversed().thenComparing(Comparator.comparingDouble(Metrics::maxFocal).reversed())
            .thenComparingDouble(Metrics::weightOrMax).thenComparingInt(Metrics::itemUnits);
    }

    String priorityInstruction() {
        return "coverage: maximize verified focal-length coverage and reach while meeting mandatory needs";
    }
}
