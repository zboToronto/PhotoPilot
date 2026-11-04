package com.photopilot.model;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentAssessment.LightPhase;

/**
 * Stage 1 UML element 85 (zero-based): {F02; permitted user context}
 * Validated F02 context assembled by DiscoveryService from the reviewed request: the user's place (coordinates only
 * when the user supplied or consented to them), the availability resolved in a named time zone, constraints and
 * interests, the confirmed F05 inventory with its UC14-verified loadouts, optional F01 history and the F09 evidence
 * retrieved for it. Missing optional parts stay explicitly absent and are listed in {@link #notices()}.
 */
public class DiscoveryContext {
    /** How the time zone was established; only "provider" is verified for the location. */
    public enum ZoneSource { PROVIDER, LOOKUP, COMPUTER }

    private Optional<GeoLocation> location;
    private ZonedDateTime start;
    private Duration availableTime;
    private double maxDistanceKm;
    private List<String> preferences;
    private GearInventory gear;
    private List<PhotographySession> history;
    private Optional<EnvironmentContext> environment;
    private List<String> avoid = List.of();
    private String portability = "", notes = "";
    private Optional<Duration> maxActivity = Optional.empty();
    private ZoneSource zoneSource = ZoneSource.COMPUTER;
    private boolean inventoryReadable = true;
    private List<Loadout> usableLoadouts = List.of();
    private List<LightPhase> lightPhases = List.of();
    private Set<String> staleFields = Set.of();
    private List<LocalizedMessage> notices = List.of();

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

    /** Full F02 context; the Stage 1 constructor remains for callers that only have the original fields. */
    public DiscoveryContext(Optional<GeoLocation> location, ZonedDateTime start, Duration availableTime, double maxDistanceKm, List<String> preferences,
            GearInventory gear, List<PhotographySession> history, Optional<EnvironmentContext> environment, List<String> avoid, String portability,
            String notes, Optional<Duration> maxActivity, ZoneSource zoneSource, boolean inventoryReadable, List<Loadout> usableLoadouts,
            List<LightPhase> lightPhases, Set<String> staleFields, List<LocalizedMessage> notices) {
        this(location, start, availableTime, maxDistanceKm, preferences, gear, history, environment);
        this.avoid = List.copyOf(avoid);
        this.portability = Objects.requireNonNull(portability, "portability");
        this.notes = Objects.requireNonNull(notes, "notes");
        this.maxActivity = Objects.requireNonNull(maxActivity, "maxActivity");
        this.zoneSource = Objects.requireNonNull(zoneSource, "zoneSource");
        this.inventoryReadable = inventoryReadable;
        this.usableLoadouts = List.copyOf(usableLoadouts);
        this.lightPhases = List.copyOf(lightPhases);
        this.staleFields = Set.copyOf(staleFields);
        this.notices = List.copyOf(notices);
    }

    /** Deterministic input gates shared by every entry point; nothing is contacted. */
    public Result<DiscoveryContext> validate() {
        var found = new ArrayList<LocalizedMessage>();
        if (location.isEmpty() || location.get().label().isBlank()) found.add(LocalizedMessage.of("discover.error.location"));
        else if (!location.get().validate().isSuccess()) found.add(LocalizedMessage.of("env.location.error.invalid"));
        if (availableTime.compareTo(DiscoveryRequest.MIN_ACTIVITY) < 0 || availableTime.compareTo(Duration.ofDays(1)) > 0)
            found.add(LocalizedMessage.of("discover.error.shortWindow", DiscoveryRequest.MIN_ACTIVITY.toMinutes()));
        maxActivity.filter(limit -> limit.compareTo(DiscoveryRequest.MIN_ACTIVITY) < 0 || limit.compareTo(availableTime) > 0)
            .ifPresent(limit -> found.add(LocalizedMessage.of("discover.error.durationWindow", limit.toMinutes(), availableTime.toMinutes())));
        if (!Double.isFinite(maxDistanceKm) || maxDistanceKm < 0 || maxDistanceKm > DiscoveryRequest.MAX_DISTANCE_KM)
            found.add(LocalizedMessage.of("discover.error.distance", (int) DiscoveryRequest.MAX_DISTANCE_KM));
        if (preferences.size() > DiscoveryRequest.MAX_ITEMS || avoid.size() > DiscoveryRequest.MAX_ITEMS)
            found.add(LocalizedMessage.of("discover.error.list", DiscoveryRequest.MAX_ITEMS, DiscoveryRequest.MAX_ITEM));
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }

    public Optional<GeoLocation> location() { return location; }
    public boolean hasCoordinates() { return location.flatMap(GeoLocation::latitude).isPresent() && location.flatMap(GeoLocation::longitude).isPresent(); }
    public ZonedDateTime start() { return start; }
    public ZonedDateTime end() { return start.plus(availableTime); }
    public Availability availability() { return new Availability(start, end()); }
    public Duration availableTime() { return availableTime; }
    /** Longest single activity: the user's maximum shooting duration, otherwise the whole availability. */
    public Duration maxActivity() { return maxActivity.orElse(availableTime); }
    public boolean maxActivityStated() { return maxActivity.isPresent(); }
    public double maxDistanceKm() { return maxDistanceKm; }
    public List<String> preferences() { return preferences; }
    public List<String> avoid() { return avoid; }
    public String portability() { return portability; }
    public String notes() { return notes; }
    public GearInventory gear() { return gear; }
    public boolean inventoryReadable() { return inventoryReadable; }
    /** Owned camera/lens combinations UC14 verified as usable (F07 deterministic generation, no ranking). */
    public List<Loadout> usableLoadouts() { return usableLoadouts; }
    public List<PhotographySession> history() { return history; }
    public Optional<EnvironmentContext> environment() { return environment; }
    public List<LightPhase> lightPhases() { return lightPhases; }
    public Set<String> staleFields() { return staleFields; }
    public ZoneSource zoneSource() { return zoneSource; }
    public List<LocalizedMessage> notices() { return notices; }
}
