package com.photopilot.model;

import com.photopilot.i18n.*;
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
 * Immutable inventory value; optional specifications retain unknown values.
 */
public abstract class Equipment {
    private final UUID id;
    private final String brand;
    private final String model;
    private final int quantity;
    private final Optional<Double> weightGrams;
    private final Set<String> verifiedFields;
    private final Map<String, URI> sources;
    private final Optional<Instant> verifiedAt;

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
        try {
            if (brand.isBlank() || model.isBlank()) throw new LocalizedException("error.gear.brandModel");
            if (quantity <= 0) throw new LocalizedException("error.gear.quantity");
            weightGrams.ifPresent(value -> EquipmentType.positive(value, LocalizedMessage.of("gear.field.weightGrams")));
            type().validate(specifications());
            return Result.success(this);
        } catch (IllegalArgumentException error) {
            return Result.failure(error);
        }
    }

    public GearSnapshot snapshot() {
        var specs = new java.util.LinkedHashMap<>(specifications());
        weightGrams.ifPresent(value -> specs.put("weightGrams", value.toString()));
        return new GearSnapshot(id, type().name(), brand, model, quantity, Instant.now(), specs, verifiedFields, sources);
    }
    public UUID id() { return id; }
    public String brand() { return brand; }
    public String model() { return model; }
    public int quantity() { return quantity; }
    public Optional<Double> weightGrams() { return weightGrams; }
    public Set<String> verifiedFields() { return verifiedFields; }
    public Map<String, URI> sources() { return sources; }
    public Optional<Instant> verifiedAt() { return verifiedAt; }

    public EquipmentType type() { return EquipmentType.valueOf(getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT)); }
    public abstract Map<String, String> specifications();
}
