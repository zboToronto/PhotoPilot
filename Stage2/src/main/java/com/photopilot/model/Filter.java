package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 1 UML element 50 (zero-based): {F05}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class Filter extends Equipment {
    private double diameterMm;
    private Optional<Double> ndStops;

    public Filter(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt, double diameterMm, Optional<Double> ndStops) {
        super(id, brand, model, quantity, weightGrams, verifiedFields, sources, verifiedAt);
        this.diameterMm = diameterMm;
        this.ndStops = Objects.requireNonNull(ndStops, "ndStops");
    }
}
