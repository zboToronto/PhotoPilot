package com.photopilot.strategy;

import java.util.Comparator;
import com.photopilot.agent.PhotoPilotAgent;

/**
 * Stage 1 UML element 61 (zero-based): {AI Concrete Strategy; prioritize carrying weight}
 * After suitability: lowest verified total weight (unknown weight last), then fewest items and lenses.
 */
public class PortabilityRanking extends AiAssistedRanking {

    public PortabilityRanking(PhotoPilotAgent agent) {
        super(agent);
    }

    public String name() { return "portability"; }

    Comparator<Metrics> priorityOrder() {
        return Comparator.comparingDouble(Metrics::weightOrMax).thenComparingInt(Metrics::itemUnits)
            .thenComparingInt(Metrics::lensUnits).thenComparing(Comparator.comparingDouble(Metrics::focalSpan).reversed());
    }

    String priorityInstruction() {
        return "portability: minimize verified carried weight, number of items and lens changes while meeting mandatory needs";
    }
}
