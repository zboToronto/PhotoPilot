package com.photopilot.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 84 (zero-based): {value; no operating-system assumption}
 * Validated location value; manual descriptions do not imply device access.
 */
public class GeoLocation {
    private String label;
    private double latitude;
    private double longitude;
    private boolean approximate;
    private boolean fromDevice;
    private Optional<Instant> consentGrantedAt;
    private boolean coordinatesPresent;

    public GeoLocation(String label, double latitude, double longitude, boolean approximate, boolean fromDevice, Optional<Instant> consentGrantedAt) {
        this.label = Objects.requireNonNull(label, "label");
        this.latitude = latitude;
        this.longitude = longitude;
        this.approximate = approximate;
        this.fromDevice = fromDevice;
        this.consentGrantedAt = Objects.requireNonNull(consentGrantedAt, "consentGrantedAt");
        this.coordinatesPresent = true;
    }

    /** A manual place description does not invent coordinates or request device access. */
    public static GeoLocation manual(String label) {
        var location = new GeoLocation(label, 0, 0, true, false, Optional.empty());
        location.coordinatesPresent = false;
        return location;
    }
    public String label() { return label; }
    public Optional<Double> latitude() { return coordinatesPresent ? Optional.of(latitude) : Optional.empty(); }
    public Optional<Double> longitude() { return coordinatesPresent ? Optional.of(longitude) : Optional.empty(); }
    public boolean approximate() { return approximate; }
    public boolean fromDevice() { return fromDevice; }
    public Optional<Instant> consentGrantedAt() { return consentGrantedAt; }

    public Result<GeoLocation> validate() {
        if (label == null || label.isBlank() || label.length() > 500 || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                || consentGrantedAt == null || (fromDevice && (!coordinatesPresent || consentGrantedAt.isEmpty()))
                || (!coordinatesPresent && (latitude != 0 || longitude != 0)))
            return Result.failureKey("error.session.location");
        return Result.success(this);
    }
}
