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
 * Immutable inventory value; optional specifications retain unknown values.
 */
public class Camera extends Equipment {
    private final String mount;
    private final Optional<Double> sensorWidthMm;
    private final Optional<Double> sensorHeightMm;
    private final Set<String> batteryTypes;
    private final Set<String> storageFormats;
    private final Optional<Double> resolutionMP;
    private final Optional<Double> burstFps;
    private final Optional<Boolean> stabilization;
    private final Set<String> autofocusModes;

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
    public String mount() { return mount; }
    public Optional<Double> sensorWidthMm() { return sensorWidthMm; }
    public Optional<Double> sensorHeightMm() { return sensorHeightMm; }
    public Set<String> batteryTypes() { return batteryTypes; }
    public Set<String> storageFormats() { return storageFormats; }
    public Optional<Double> resolutionMP() { return resolutionMP; }
    public Optional<Double> burstFps() { return burstFps; }
    public Optional<Boolean> stabilization() { return stabilization; }
    public Set<String> autofocusModes() { return autofocusModes; }

    @Override public java.util.Map<String, String> specifications() {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("mount", String.valueOf(mount));
        sensorWidthMm.ifPresent(value -> values.put("sensorWidthMm", value.toString()));
        sensorHeightMm.ifPresent(value -> values.put("sensorHeightMm", value.toString()));
        if (!batteryTypes.isEmpty()) values.put("batteryTypes", String.join(",", new java.util.TreeSet<>(batteryTypes)));
        if (!storageFormats.isEmpty()) values.put("storageFormats", String.join(",", new java.util.TreeSet<>(storageFormats)));
        resolutionMP.ifPresent(value -> values.put("resolutionMP", value.toString()));
        burstFps.ifPresent(value -> values.put("burstFps", value.toString()));
        stabilization.ifPresent(value -> values.put("stabilization", value.toString()));
        if (!autofocusModes.isEmpty()) values.put("autofocusModes", String.join(",", new java.util.TreeSet<>(autofocusModes)));
        return java.util.Map.copyOf(values);
    }
}
