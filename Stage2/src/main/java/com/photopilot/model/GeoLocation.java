package com.photopilot.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 84 (zero-based): {value; no operating-system assumption}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GeoLocation {
    private String label;
    private double latitude;
    private double longitude;
    private boolean approximate;
    private boolean fromDevice;
    private Optional<Instant> consentGrantedAt;

    public GeoLocation(String label, double latitude, double longitude, boolean approximate, boolean fromDevice, Optional<Instant> consentGrantedAt) {
        this.label = Objects.requireNonNull(label, "label");
        this.latitude = latitude;
        this.longitude = longitude;
        this.approximate = approximate;
        this.fromDevice = fromDevice;
        this.consentGrantedAt = Objects.requireNonNull(consentGrantedAt, "consentGrantedAt");
    }

    public Result<GeoLocation> validate() {
        return Result.failureKey("error.notImplemented", "GeoLocation.validate");
    }
}
