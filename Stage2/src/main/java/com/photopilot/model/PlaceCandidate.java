package com.photopilot.model;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/**
 * F09 place-name lookup match. A name alone never selects a location: the user chooses one candidate, and the
 * resulting coordinates are marked approximate (a gazetteer point, not the exact shooting spot).
 */
public record PlaceCandidate(String name, String region, String country, double latitude, double longitude, Optional<ZoneId> zone) {
    public PlaceCandidate {
        Objects.requireNonNull(name, "name"); region = Objects.requireNonNullElse(region, ""); country = Objects.requireNonNullElse(country, "");
        Objects.requireNonNull(zone, "zone");
    }
    public String displayName() {
        var parts = new java.util.ArrayList<String>(); parts.add(name);
        if (!region.isBlank()) parts.add(region);
        if (!country.isBlank()) parts.add(country);
        return String.join(", ", parts);
    }
    public GeoLocation location() { return new GeoLocation(displayName(), latitude, longitude, true, false, Optional.empty()); }
}
