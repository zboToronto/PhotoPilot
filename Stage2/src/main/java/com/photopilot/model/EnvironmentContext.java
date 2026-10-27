package com.photopilot.model;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 1 UML element 83 (zero-based): {weather values carry documented units; partial data allowed}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EnvironmentContext {
    private Map<String, Double> weather;
    private Optional<ZonedDateTime> sunrise;
    private Optional<ZonedDateTime> sunset;
    private Map<String, URI> sources;
    private Map<String, Instant> sourceTimestamps;
    private Set<String> unavailableFields;
    private Instant retrievedAt;

    public EnvironmentContext(Map<String, Double> weather, Optional<ZonedDateTime> sunrise, Optional<ZonedDateTime> sunset, Map<String, URI> sources, Map<String, Instant> sourceTimestamps, Set<String> unavailableFields, Instant retrievedAt) {
        this.weather = Map.copyOf(weather);
        this.sunrise = Objects.requireNonNull(sunrise, "sunrise");
        this.sunset = Objects.requireNonNull(sunset, "sunset");
        this.sources = Map.copyOf(sources);
        this.sourceTimestamps = Map.copyOf(sourceTimestamps);
        this.unavailableFields = Set.copyOf(unavailableFields);
        this.retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    public Set<String> staleFields(Instant now, Duration maxAge) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: EnvironmentContext.staleFields");
    }
}
