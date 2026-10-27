package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 1 UML element 51 (zero-based): {F05}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class Battery extends Equipment {
    private String batteryType;
    private double capacityMah;
    private Optional<Integer> ratedShots;

    public Battery(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt, String batteryType, double capacityMah, Optional<Integer> ratedShots) {
        super(id, brand, model, quantity, weightGrams, verifiedFields, sources, verifiedAt);
        this.batteryType = Objects.requireNonNull(batteryType, "batteryType");
        this.capacityMah = capacityMah;
        this.ratedShots = Objects.requireNonNull(ratedShots, "ratedShots");
    }
}
