package com.photopilot.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 1 UML element 63 (zero-based): {F07; immutable selected quantities and evidence}
 * Owned-gear combination built from inventory snapshots. Camera-to-lens assignments record the lens mounted on
 * one body of each camera record; other lenses travel in the bag.
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
        var ids = new java.util.HashSet<UUID>();
        for (GearSnapshot item : this.gear) {
            if (!ids.add(item.sourceGearId())) throw new IllegalArgumentException("Duplicate gear in loadout.");
        }
        if (!ids.containsAll(this.cameraLensAssignments.keySet()) || !ids.containsAll(this.cameraLensAssignments.values()))
            throw new IllegalArgumentException("Assignment refers to gear outside the loadout.");
    }

    /** Sum of recorded unit weights times selected quantities; unknown if any item has no recorded weight. */
    public Optional<Double> totalWeightGrams() {
        double total = 0;
        for (GearSnapshot item : gear) {
            String weight = item.specs().get("weightGrams");
            if (weight == null) return Optional.empty();
            try { total += Double.parseDouble(weight) * item.selectedQuantity(); }
            catch (NumberFormatException unreadable) { return Optional.empty(); }
        }
        return Double.isFinite(total) ? Optional.of(total) : Optional.empty();
    }

    /** Same combination with a fresh evaluation. */
    public Loadout withEvaluation(CompatibilityReport report, List<String> tradeOffs) {
        return new Loadout(id, gear, cameraLensAssignments, report, tradeOffs, report.unmetNeeds());
    }
    public List<GearSnapshot> ofType(EquipmentType type) { return gear.stream().filter(item -> item.type().equals(type.name())).toList(); }
    public int units(EquipmentType type) { return ofType(type).stream().mapToInt(GearSnapshot::selectedQuantity).sum(); }
    public UUID id() { return id; }
    public List<GearSnapshot> gear() { return List.copyOf(gear); }
    public Map<UUID, UUID> cameraLensAssignments() { return cameraLensAssignments; }
    public CompatibilityReport compatibility() { return compatibility; }
    public List<String> tradeOffs() { return tradeOffs; }
    public List<String> unmetNeeds() { return unmetNeeds; }
}
