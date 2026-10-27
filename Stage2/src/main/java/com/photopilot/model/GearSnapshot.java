package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 1 UML element 64 (zero-based): {immutable historical value; no live inventory dependency}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GearSnapshot {
    private final UUID sourceGearId;
    private final String type;
    private final String brand;
    private final String model;
    private final int selectedQuantity;
    private final Instant capturedAt;
    private final Map<String, String> specs;
    private final Set<String> verifiedFields;
    private final Map<String, URI> sources;

    public GearSnapshot(UUID sourceGearId, String type, String brand, String model, int selectedQuantity, Instant capturedAt, Map<String, String> specs, Set<String> verifiedFields, Map<String, URI> sources) {
        this.sourceGearId = Objects.requireNonNull(sourceGearId, "sourceGearId");
        this.type = Objects.requireNonNull(type, "type");
        this.brand = Objects.requireNonNull(brand, "brand");
        this.model = Objects.requireNonNull(model, "model");
        this.selectedQuantity = selectedQuantity;
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.specs = Map.copyOf(specs);
        this.verifiedFields = Set.copyOf(verifiedFields);
        this.sources = Map.copyOf(sources);
    }
}
