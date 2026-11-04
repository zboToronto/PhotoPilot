package com.photopilot.model;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * F04 external specification evidence for one catalogued product: the source, a link to the exact retrieved
 * content, the product identity it describes, the field values it states (F05 keys; absent = not stated) and when
 * it was retrieved. Transient: shown during review and reduced to F05 verifiedFields/sources/verifiedAt on import.
 */
public record SpecificationEvidence(String source, URI url, EquipmentType type, String brand, String model,
        Map<String, String> values, Instant retrievedAt) {
    public SpecificationEvidence {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(url, "url"); Objects.requireNonNull(type, "type");
        Objects.requireNonNull(brand, "brand"); Objects.requireNonNull(model, "model"); Objects.requireNonNull(retrievedAt, "retrievedAt");
        values = Map.copyOf(values);
    }
    public String product() { return model.toLowerCase(java.util.Locale.ROOT).startsWith(brand.toLowerCase(java.util.Locale.ROOT)) || brand.isBlank() ? model : brand + " " + model; }
}
