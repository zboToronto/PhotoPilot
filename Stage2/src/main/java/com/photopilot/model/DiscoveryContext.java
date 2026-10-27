package com.photopilot.model;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 UML element 85 (zero-based): {F02; permitted user context}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class DiscoveryContext {
    private Optional<GeoLocation> location;
    private ZonedDateTime start;
    private Duration availableTime;
    private double maxDistanceKm;
    private List<String> preferences;
    private GearInventory gear;
    private List<PhotographySession> history;
    private Optional<EnvironmentContext> environment;

    public DiscoveryContext(Optional<GeoLocation> location, ZonedDateTime start, Duration availableTime, double maxDistanceKm, List<String> preferences, GearInventory gear, List<PhotographySession> history, Optional<EnvironmentContext> environment) {
        this.location = Objects.requireNonNull(location, "location");
        this.start = Objects.requireNonNull(start, "start");
        this.availableTime = Objects.requireNonNull(availableTime, "availableTime");
        this.maxDistanceKm = maxDistanceKm;
        this.preferences = List.copyOf(preferences);
        this.gear = Objects.requireNonNull(gear, "gear");
        this.history = List.copyOf(history);
        this.environment = Objects.requireNonNull(environment, "environment");
    }
}
