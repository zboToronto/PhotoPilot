package com.photopilot.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 1 UML element 63 (zero-based): {F07; immutable selected quantities and evidence}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class Loadout {
    private final UUID id;
    private final List<GearSnapshot> gear;
    private final Map<UUID, UUID> cameraLensAssignments;
    private final CompatibilityReport compatibility;
    private final List<String> tradeOffs;
    private final List<String> unmetNeeds;

    public Loadout(UUID id, List<GearSnapshot> gear, Map<UUID, UUID> cameraLensAssignments, CompatibilityReport compatibility, List<String> tradeOffs, List<String> unmetNeeds) {
        this.id = Objects.requireNonNull(id, "id");
        this.gear = List.copyOf(gear);
        this.cameraLensAssignments = Map.copyOf(cameraLensAssignments);
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.tradeOffs = List.copyOf(tradeOffs);
        this.unmetNeeds = List.copyOf(unmetNeeds);
    }

    public Optional<Double> totalWeightGrams() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: Loadout.totalWeightGrams");
    }
}
