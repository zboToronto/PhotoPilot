package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 1 UML element 48 (zero-based): {F05}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class Camera extends Equipment {
    private String mount;
    private Optional<Double> sensorWidthMm;
    private Optional<Double> sensorHeightMm;
    private Set<String> batteryTypes;
    private Set<String> storageFormats;
    private Optional<Double> resolutionMP;
    private Optional<Double> burstFps;
    private Optional<Boolean> stabilization;
    private Set<String> autofocusModes;

    public Camera(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt, String mount, Optional<Double> sensorWidthMm, Optional<Double> sensorHeightMm, Set<String> batteryTypes, Set<String> storageFormats, Optional<Double> resolutionMP, Optional<Double> burstFps, Optional<Boolean> stabilization, Set<String> autofocusModes) {
        super(id, brand, model, quantity, weightGrams, verifiedFields, sources, verifiedAt);
        this.mount = Objects.requireNonNull(mount, "mount");
        this.sensorWidthMm = Objects.requireNonNull(sensorWidthMm, "sensorWidthMm");
        this.sensorHeightMm = Objects.requireNonNull(sensorHeightMm, "sensorHeightMm");
        this.batteryTypes = Set.copyOf(batteryTypes);
        this.storageFormats = Set.copyOf(storageFormats);
        this.resolutionMP = Objects.requireNonNull(resolutionMP, "resolutionMP");
        this.burstFps = Objects.requireNonNull(burstFps, "burstFps");
        this.stabilization = Objects.requireNonNull(stabilization, "stabilization");
        this.autofocusModes = Set.copyOf(autofocusModes);
    }
}
