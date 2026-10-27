package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 1 UML element 52 (zero-based): {F05}
 * Immutable inventory value; optional specifications retain unknown values.
 */
public class StorageMedia extends Equipment {
    private final String format;
    private final double capacityGB;
    private final Optional<Double> writeSpeedMBps;

    public StorageMedia(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt, String format, double capacityGB, Optional<Double> writeSpeedMBps) {
        super(id, brand, model, quantity, weightGrams, verifiedFields, sources, verifiedAt);
        this.format = Objects.requireNonNull(format, "format");
        this.capacityGB = capacityGB;
        this.writeSpeedMBps = Objects.requireNonNull(writeSpeedMBps, "writeSpeedMBps");
    }
    public String format() { return format; }
    public double capacityGB() { return capacityGB; }
    public Optional<Double> writeSpeedMBps() { return writeSpeedMBps; }

    @Override public java.util.Map<String, String> specifications() {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put("format", String.valueOf(format));
        values.put("capacityGB", String.valueOf(capacityGB));
        writeSpeedMBps.ifPresent(value -> values.put("writeSpeedMBps", value.toString()));
        return java.util.Map.copyOf(values);
    }
}
