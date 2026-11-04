package com.photopilot.model;

import java.time.*;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * F02 inputs the user reviewed before asking for ideas. The place is a label (manual, approximate or regional) with
 * optional coordinates from manual entry, place lookup or consented device location. Times are local wall-clock
 * times at that place; an end not after the start continues past midnight. The maximum travel distance is compared
 * with straight-line distance, the only distance PhotoPilot can compute. Interests and history are optional.
 */
public record DiscoveryRequest(GeoLocation place, LocalDate date, LocalTime availableFrom, LocalTime availableUntil,
        Optional<Duration> maxActivity, double maxDistanceKm, List<String> interests, List<String> avoid, String portability,
        String notes, boolean useHistory, Optional<ZoneId> zoneHint) {
    public static final int MAX_ITEMS = 12, MAX_ITEM = 100, MAX_NOTES = 500, MAX_DAYS_AHEAD = 365;
    public static final double MAX_DISTANCE_KM = 1000;
    public static final Duration MIN_ACTIVITY = Duration.ofMinutes(15);

    public DiscoveryRequest {
        Objects.requireNonNull(maxActivity, "maxActivity"); Objects.requireNonNull(zoneHint, "zoneHint");
        interests = clean(interests); avoid = clean(avoid);
        portability = Objects.requireNonNullElse(portability, "").strip(); notes = Objects.requireNonNullElse(notes, "").strip();
    }
    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty()).distinct().toList();
    }

    public boolean crossesMidnight() { return availableFrom != null && availableUntil != null && !availableUntil.isAfter(availableFrom); }
    public LocalDate endDate() { return crossesMidnight() ? date.plusDays(1) : date; }
    /** Length of the stated availability window. */
    public Duration window() {
        if (availableFrom == null || availableUntil == null) return Duration.ZERO;
        var length = Duration.between(availableFrom, availableUntil);
        return crossesMidnight() ? length.plusDays(1) : length;
    }
    public boolean hasCoordinates() { return place != null && place.latitude().isPresent() && place.longitude().isPresent(); }

    /** All structural problems at once; contacts nothing. Dates in the past are checked against the location zone later. */
    public Result<DiscoveryRequest> validate() {
        var found = new ArrayList<LocalizedMessage>();
        if (place == null || place.label().isBlank()) found.add(LocalizedMessage.of("discover.error.location"));
        else if (place.label().length() > 200 || !place.validate().isSuccess()) found.add(LocalizedMessage.of("env.location.error.invalid"));
        if (date == null) found.add(LocalizedMessage.of("env.error.date"));
        if (availableFrom == null || availableUntil == null) found.add(LocalizedMessage.of("env.error.time"));
        else if (availableFrom.equals(availableUntil)) found.add(LocalizedMessage.of("env.error.emptyAvailability"));
        else if (window().compareTo(MIN_ACTIVITY) < 0) found.add(LocalizedMessage.of("discover.error.shortWindow", MIN_ACTIVITY.toMinutes()));
        maxActivity.ifPresent(limit -> {
            if (limit.compareTo(MIN_ACTIVITY) < 0 || limit.compareTo(Duration.ofDays(1)) > 0) found.add(LocalizedMessage.of("discover.error.duration", MIN_ACTIVITY.toMinutes()));
            else if (availableFrom != null && availableUntil != null && !availableFrom.equals(availableUntil) && limit.compareTo(window()) > 0)
                found.add(LocalizedMessage.of("discover.error.durationWindow", limit.toMinutes(), window().toMinutes()));
        });
        if (!Double.isFinite(maxDistanceKm) || maxDistanceKm < 0 || maxDistanceKm > MAX_DISTANCE_KM)
            found.add(LocalizedMessage.of("discover.error.distance", (int) MAX_DISTANCE_KM));
        if (interests.size() > MAX_ITEMS || avoid.size() > MAX_ITEMS || interests.stream().anyMatch(v -> v.length() > MAX_ITEM)
                || avoid.stream().anyMatch(v -> v.length() > MAX_ITEM)) found.add(LocalizedMessage.of("discover.error.list", MAX_ITEMS, MAX_ITEM));
        if (portability.length() > MAX_ITEM * 2 || notes.length() > MAX_NOTES) found.add(LocalizedMessage.of("discover.error.notes", MAX_NOTES));
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }
}
