package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 1 UML element 49 (zero-based): {F05}
 * Immutable inventory value; optional specifications retain unknown values.
 */
public class Lens extends Equipment {
    private final String mount;
    private final double minFocalMm;
    private final double maxFocalMm;
    private final double maxAperture;
    private final Optional<Double> filterDiameterMm;
    private final Optional<Boolean> autofocus;
    private final Optional<Boolean> stabilization;

    public Lens(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt, String mount, double minFocalMm, double maxFocalMm, double maxAperture, Optional<Double> filterDiameterMm, Optional<Boolean> autofocus, Optional<Boolean> stabilization) {
        super(id, brand, model, quantity, weightGrams, verifiedFields, sources, verifiedAt);
        this.mount = Objects.requireNonNull(mount, "mount");
        this.minFocalMm = minFocalMm;
        this.maxFocalMm = maxFocalMm;
        this.maxAperture = maxAperture;
        this.filterDiameterMm = Objects.requireNonNull(filterDiameterMm, "filterDiameterMm");
        this.autofocus = Objects.requireNonNull(autofocus, "autofocus");
        this.stabilization = Objects.requireNonNull(stabilization, "stabilization");
    }
    public String mount() { return mount; }
    public double minFocalMm() { return minFocalMm; }
    public double maxFocalMm() { return maxFocalMm; }
    public double maxAperture() { return maxAperture; }
    public Optional<Double> filterDiameterMm() { return filterDiameterMm; }
    public Optional<Boolean> autofocus() { return autofocus; }
    public Optional<Boolean> stabilization() { return stabilization; }

    @Override public java.util.Map<String, String> specifications() {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("mount", String.valueOf(mount));
        values.put("minFocalMm", String.valueOf(minFocalMm));
        values.put("maxFocalMm", String.valueOf(maxFocalMm));
        values.put("maxAperture", String.valueOf(maxAperture));
        filterDiameterMm.ifPresent(value -> values.put("filterDiameterMm", value.toString()));
        autofocus.ifPresent(value -> values.put("autofocus", value.toString()));
        stabilization.ifPresent(value -> values.put("stabilization", value.toString()));
        return java.util.Map.copyOf(values);
    }
}
