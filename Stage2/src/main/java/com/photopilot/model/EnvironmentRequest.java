package com.photopilot.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;

/**
 * F09 inputs the user reviewed for one assessment: a location with coordinates, the shooting date and the period
 * the user can be on site, as local wall-clock times at that location (resolved only once the location time zone is
 * verified). An end time not after the start means the period continues past midnight. The optional preparation lead
 * time is the user's own assumption. {@code activity}/{@code goals} are a minimal F09-only context used when the
 * session has no confirmed F03 requirement; they are never saved into the requirement.
 *
 * <p>{@code zoneHint} is the fallback time zone when the weather provider confirms none: the chosen place-lookup
 * match's zone, or (Stage2OptRe1 C16) a regional IANA zone the user entered explicitly, marked by {@code zoneEntered}.
 * A provider-verified zone always takes precedence; the computer's own zone or UTC is never substituted.
 */
public record EnvironmentRequest(GeoLocation place, LocalDate date, LocalTime availableFrom, LocalTime availableUntil,
        Optional<Duration> leadTime, Optional<ZoneId> zoneHint, String activity, List<String> goals, boolean zoneEntered, Optional<PlaceCandidate> accepted) {
    public static final Duration MAX_LEAD = Duration.ofHours(6);

    public EnvironmentRequest {
        Objects.requireNonNull(accepted);
        Objects.requireNonNull(leadTime, "leadTime"); Objects.requireNonNull(zoneHint, "zoneHint");
        activity = Objects.requireNonNullElse(activity, "").strip();
        goals = goals == null ? List.of() : goals.stream().filter(Objects::nonNull).map(String::strip).filter(g -> !g.isEmpty()).toList();
        zoneEntered = zoneEntered && zoneHint.isPresent();
    }
    public EnvironmentRequest(GeoLocation place, LocalDate date, LocalTime availableFrom, LocalTime availableUntil,
            Optional<Duration> leadTime, Optional<ZoneId> zoneHint, String activity, List<String> goals, boolean zoneEntered) {
        this(place, date, availableFrom, availableUntil, leadTime, zoneHint, activity, goals, zoneEntered, Optional.empty());
    }

    public PlaceCandidate.Source locationSource() {
        return accepted.map(PlaceCandidate::source).orElseGet(() -> place.fromDevice()
            ? PlaceCandidate.Source.DEVICE_LOCATION : PlaceCandidate.Source.USER_PROVIDED);
    }
    public boolean aiLocation() { return accepted.filter(PlaceCandidate::estimated).isPresent(); }

    /** A request whose zone hint, if any, comes from a place lookup. */
    public EnvironmentRequest(GeoLocation place, LocalDate date, LocalTime availableFrom, LocalTime availableUntil,
            Optional<Duration> leadTime, Optional<ZoneId> zoneHint, String activity, List<String> goals) {
        this(place, date, availableFrom, availableUntil, leadTime, zoneHint, activity, goals, false);
    }

    public boolean crossesMidnight() { return availableFrom != null && availableUntil != null && availableUntil.isBefore(availableFrom); }
    /** Last local date covered by the availability period. */
    public LocalDate endDate() { return crossesMidnight() ? date.plusDays(1) : date; }

    /** All structural problems at once; does not contact any provider. */
    public Result<EnvironmentRequest> validate() {
        var found = new ArrayList<LocalizedMessage>();
        if (place == null) found.add(LocalizedMessage.of("env.location.error.missing"));
        else if (place.latitude().isEmpty() || place.longitude().isEmpty()) found.add(LocalizedMessage.of("env.location.error.coordinates"));
        else if (!place.validate().isSuccess()) found.add(LocalizedMessage.of("env.location.error.invalid"));
        if (accepted.isPresent()) {
            var c = accepted.get();
            if (place == null || !place.label().equals(c.displayName()) || !place.latitude().equals(Optional.of(c.latitude()))
                    || !place.longitude().equals(Optional.of(c.longitude())) || !place.approximate())
                found.add(LocalizedMessage.of("env.resolve.changed"));
        }
        if (date == null) found.add(LocalizedMessage.of("env.error.date"));
        if (availableFrom == null || availableUntil == null) found.add(LocalizedMessage.of("env.error.time"));
        else if (availableFrom.equals(availableUntil)) found.add(LocalizedMessage.of("env.error.emptyAvailability"));
        leadTime.ifPresent(lead -> { if (lead.isNegative() || lead.compareTo(MAX_LEAD) > 0) found.add(LocalizedMessage.of("env.error.lead", MAX_LEAD.toMinutes())); });
        if (activity.length() > 200) found.add(LocalizedMessage.of("env.error.activity"));
        if (goals.size() > 10 || goals.stream().anyMatch(goal -> goal.length() > ShootRequirement.MAX_TEXT)) found.add(LocalizedMessage.of("env.error.goals"));
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }
}
