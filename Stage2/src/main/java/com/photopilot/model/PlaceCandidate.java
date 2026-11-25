package com.photopilot.model;

import java.time.ZoneId;
import java.util.*;

/** An unconfirmed F09 lookup option. Selection never changes the origin of its coordinates. */
public record PlaceCandidate(String name, String region, String country, double latitude, double longitude,
        Optional<ZoneId> zone, String city, Source source, Scope scope, String query, boolean broaderArea) {
    public enum Source { OPEN_METEO_GEOCODING, GEMINI_ESTIMATED, USER_PROVIDED, DEVICE_LOCATION }
    public enum Scope { LANDMARK_VICINITY, NEIGHBORHOOD, CITY_LEVEL, REGION_LEVEL, UNKNOWN }

    public PlaceCandidate {
        name = Objects.requireNonNull(name).strip();
        city = Objects.requireNonNullElse(city, "").strip();
        region = Objects.requireNonNullElse(region, "").strip();
        country = Objects.requireNonNullElse(country, "").strip();
        Objects.requireNonNull(zone); Objects.requireNonNull(source); Objects.requireNonNull(scope);
        Objects.requireNonNull(query);
    }
    public boolean validPoint() {
        return !name.isBlank() && Double.isFinite(latitude) && Math.abs(latitude) <= 90
            && Double.isFinite(longitude) && Math.abs(longitude) <= 180;
    }
    public PlaceCandidate(String name, String region, String country, double latitude, double longitude, Optional<ZoneId> zone) {
        this(name, region, country, latitude, longitude, zone, "", Source.OPEN_METEO_GEOCODING, Scope.UNKNOWN, name);
    }
    public PlaceCandidate(String name, String region, String country, double latitude, double longitude, Optional<ZoneId> zone,
            String city, Source source, Scope scope, String query) {
        this(name, region, country, latitude, longitude, zone, city, source, scope, query, false);
    }
    public PlaceCandidate broaderFor(String original) {
        return new PlaceCandidate(name, region, country, latitude, longitude, zone, city, source, scope, original, true);
    }
    public PlaceCandidate forQuery(String original) {
        return new PlaceCandidate(name, region, country, latitude, longitude, zone, city, source, scope, original);
    }
    public boolean estimated() { return source == Source.GEMINI_ESTIMATED; }
    public String displayName() {
        return String.join(", ", java.util.stream.Stream.of(name, city, region, country).filter(s -> !s.isBlank()).distinct().toList());
    }
    public GeoLocation location() { return new GeoLocation(displayName(), latitude, longitude, true, false, Optional.empty()); }
}
