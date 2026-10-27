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
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class StorageMedia extends Equipment {
    private String format;
    private double capacityGB;
    private Optional<Double> writeSpeedMBps;

    public StorageMedia(UUID id, String brand, String model, int quantity, Optional<Double> weightGrams, Set<String> verifiedFields, Map<String, URI> sources, Optional<Instant> verifiedAt, String format, double capacityGB, Optional<Double> writeSpeedMBps) {
        super(id, brand, model, quantity, weightGrams, verifiedFields, sources, verifiedAt);
        this.format = Objects.requireNonNull(format, "format");
        this.capacityGB = capacityGB;
        this.writeSpeedMBps = Objects.requireNonNull(writeSpeedMBps, "writeSpeedMBps");
    }
}
