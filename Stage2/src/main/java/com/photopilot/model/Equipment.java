package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 47 (zero-based): {shared gear identity; missing data explicit}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public abstract class Equipment {
    private UUID id;
    private String brand;
    private String model;
    private int quantity;
    private Optional<Double> weightGrams;
    private Set<String> verifiedFields;
    private Map<String, URI> sources;
    private Optional<Instant> verifiedAt;

    protected Equipment(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.brand = Objects.requireNonNull(brand, "brand");
        this.model = Objects.requireNonNull(model, "model");
        this.quantity = quantity;
        this.weightGrams = Objects.requireNonNull(weightGrams, "weightGrams");
        this.verifiedFields = Set.copyOf(verifiedFields);
        this.sources = Map.copyOf(sources);
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
    }

    public Result<Equipment> validate() {
        return Result.failure("NOT IMPLEMENTED: Equipment.validate");
    }

    public GearSnapshot snapshot() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: Equipment.snapshot");
    }
}
