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
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class Lens extends Equipment {
    private String mount;
    private double minFocalMm;
    private double maxFocalMm;
    private double maxAperture;
    private Optional<Double> filterDiameterMm;
    private Optional<Boolean> autofocus;
    private Optional<Boolean> stabilization;

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
}
