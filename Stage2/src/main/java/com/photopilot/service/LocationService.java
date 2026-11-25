package com.photopilot.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;
import com.photopilot.integration.DeviceLocationProvider;
import com.photopilot.integration.PlaceLookupProvider;
import com.photopilot.model.GeoLocation;
import com.photopilot.model.PlaceCandidate;

/**
 * Stage 1 UML element 82 (zero-based): {F01/F02/F09; no call without consent}
 * Manual locations are validated without any device access. The device provider is called only from
 * {@link #current(boolean)} with explicit consent, never automatically; a recorded denial is respected for the
 * rest of the application session. Place-name lookup returns all matches for the user to choose from.
 * Nothing here is persisted.
 */
public class LocationService {
    public static final int MAX_LABEL = 200;
    private Optional<DeviceLocationProvider> provider;
    private final Optional<PlaceLookupProvider> places;
    private final Optional<PlaceLookupProvider> estimates;
    private final Clock clock;
    private volatile boolean consentDenied;

    public LocationService(Optional<DeviceLocationProvider> provider) {
        this(provider, Optional.empty(), Clock.systemUTC());
    }

    public LocationService(Optional<DeviceLocationProvider> provider, Optional<PlaceLookupProvider> places, Clock clock) {
        this(provider, places, clock, Optional.empty());
    }

    public LocationService(Optional<DeviceLocationProvider> provider, Optional<PlaceLookupProvider> places, Clock clock,
            Optional<PlaceLookupProvider> estimates) {
        this.estimates = Objects.requireNonNull(estimates);
        this.provider = Objects.requireNonNull(provider, "provider");
        this.places = Objects.requireNonNull(places, "places");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Manually entered coordinates; the label is the user's own description. */
    public Result<GeoLocation> manual(String label, double lat, double lon) {
        if (label == null || label.isBlank()) return Result.failureKey("env.location.error.label");
        if (label.strip().length() > MAX_LABEL) return Result.failureKey("env.location.error.labelLength", MAX_LABEL);
        if (!Double.isFinite(lat) || lat < -90 || lat > 90) return Result.failureKey("env.location.error.latitude");
        if (!Double.isFinite(lon) || lon < -180 || lon > 180) return Result.failureKey("env.location.error.longitude");
        return new GeoLocation(label.strip(), lat, lon, false, false, Optional.empty()).validate();
    }

    /**
     * Precise device location. Without explicit consent, after a recorded denial, or without a supported device
     * provider this fails and nothing is requested; manual entry always remains available.
     */
    public Result<GeoLocation> current(boolean explicitConsent) {
        if (!explicitConsent) return Result.failureKey("env.location.error.consent");
        if (consentDenied) return Result.failureKey("env.location.error.denied");
        if (provider.isEmpty()) return Result.failureKey("env.location.error.deviceUnsupported");
        Result<GeoLocation> found;
        try { found = provider.get().current(); }
        catch (RuntimeException failure) { return Result.failureKey("env.location.error.deviceFailed"); }
        if (found == null || !found.isSuccess()) return found == null ? Result.failureKey("env.location.error.deviceFailed") : Result.failure(found);
        GeoLocation reported = found.value().orElseThrow();
        if (reported.latitude().isEmpty() || reported.longitude().isEmpty()) return Result.failureKey("env.location.error.deviceFailed");
        Instant consent = clock.instant();
        String label = reported.label().isBlank() ? "Device location" : reported.label();
        var stamped = new GeoLocation(label, reported.latitude().get(), reported.longitude().get(), reported.approximate(), true, Optional.of(consent));
        var valid = stamped.validate();
        return valid.isSuccess() ? valid : Result.failureKey("env.location.error.deviceFailed");
    }

    public boolean deviceSupported() { return provider.isPresent(); }
    /** The user declined device location; it is not requested again in this application session. */
    public void recordDenial() { consentDenied = true; }
    public boolean consentDenied() { return consentDenied; }

    /** F09-only hybrid search. Other features retain their database-only search contract. */
    public Result<List<PlaceCandidate>> resolve(String input) {
        String name = DestinationQuery.normalize(input);
        if (name.isEmpty()) return Result.failureKey("env.location.error.label");
        if (name.length() > MAX_LABEL) return Result.failureKey("env.location.error.labelLength", MAX_LABEL);
        if (DestinationQuery.privateInput(name)) return Result.failureKey("env.resolve.private");
        var query = DestinationQuery.of(name);
        if (query.entity().isBlank()) return Result.failureKey("env.location.error.label");
        Result<List<PlaceCandidate>> primary = Result.failureKey("env.location.error.notFound", name);
        var attempts = query.attempts();
        var broader = new java.util.LinkedHashSet<PlaceCandidate>();
        for (String term : attempts) {
            if (cancelled()) return Result.failureKey("progress.cancelled");
            var database = database(term);
            if (database.isSuccess()) {
                var matches = database.value().orElseThrow().stream().filter(query::matches).map(p -> p.forQuery(name)).distinct().toList();
                if (!matches.isEmpty()) return Result.success(matches);
                database.value().orElseThrow().stream().filter(query::broaderArea).map(p -> p.broaderFor(name)).forEach(broader::add);
            } else if (!database.hasError("env.location.error.notFound")) {
                primary = database;
                if (database.hasError("env.error.interrupted") || database.hasError("env.error.provider")) return primary;
                break;
            }
        }
        if (estimates.isEmpty()) return broader.isEmpty() ? primary : Result.success(List.copyOf(broader));
        if (cancelled()) return Result.failureKey("progress.cancelled");
        com.photopilot.progress.Progress.waiting("env.locationAi");
        var fallback = estimates.get().search(name);
        if (cancelled()) return Result.failureKey("progress.cancelled");
        if (!fallback.isSuccess()) {
            if (!broader.isEmpty()) return Result.success(List.copyOf(broader));
            var reasons = new java.util.ArrayList<>(primary.messages()); reasons.addAll(fallback.messages());
            return Result.failures(reasons);
        }
        var found = fallback.value().orElseThrow();
        if (found.isEmpty() || found.stream().anyMatch(p -> !query.plausibleEstimate(p))) return Result.failureKey("env.resolve.conflict");
        // Only a distinct normalized name earns another database call; never retry a failing endpoint.
        if (found.size() == 1 && primary.hasError("env.location.error.notFound")) {
            var estimate = found.getFirst();
            if (!cancelled() && attempts.stream().noneMatch(estimate.name()::equalsIgnoreCase)) {
                var retry = database(estimate.name());
                var verified = retry.value().orElse(List.of()).stream().filter(p -> query.normalizedMatch(estimate, p))
                    .map(p -> p.forQuery(name)).distinct().toList();
                if (!verified.isEmpty()) return Result.success(verified);
            }
        }
        return cancelled() ? Result.failureKey("progress.cancelled") : fallback;
    }
    private static boolean cancelled() {
        return Thread.currentThread().isInterrupted() || com.photopilot.progress.Progress.cancelled();
    }

    /** Candidate places for a name; ambiguous names return several and the user must choose. */
    public Result<List<PlaceCandidate>> search(String name) {
        var result = database(name);
        if (!result.isSuccess()) return result;
        // Existing F02 callers use the legacy name/region/country identity. F09 retains the extra hierarchy.
        return Result.success(result.value().orElseThrow().stream().map(p -> new PlaceCandidate(p.name(), p.region(),
            p.country(), p.latitude(), p.longitude(), p.zone())).toList());
    }
    private Result<List<PlaceCandidate>> database(String name) {
        if (name == null || name.isBlank()) return Result.failureKey("env.location.error.label");
        if (name.strip().length() > MAX_LABEL) return Result.failureKey("env.location.error.labelLength", MAX_LABEL);
        if (places.isEmpty()) return Result.failureKey("env.location.error.lookupUnavailable");
        var found = places.get().search(name.strip());
        return found == null ? Result.failureKey("env.location.error.lookupUnavailable") : found;
    }
}
