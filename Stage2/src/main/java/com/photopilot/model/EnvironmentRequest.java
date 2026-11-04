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
 */
public record EnvironmentRequest(GeoLocation place, LocalDate date, LocalTime availableFrom, LocalTime availableUntil,
        Optional<Duration> leadTime, Optional<ZoneId> zoneHint, String activity, List<String> goals) {
    public static final Duration MAX_LEAD = Duration.ofHours(6);

    public EnvironmentRequest {
        Objects.requireNonNull(leadTime, "leadTime"); Objects.requireNonNull(zoneHint, "zoneHint");
        activity = Objects.requireNonNullElse(activity, "").strip();
        goals = goals == null ? List.of() : goals.stream().filter(Objects::nonNull).map(String::strip).filter(g -> !g.isEmpty()).toList();
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
        if (date == null) found.add(LocalizedMessage.of("env.error.date"));
        if (availableFrom == null || availableUntil == null) found.add(LocalizedMessage.of("env.error.time"));
        else if (availableFrom.equals(availableUntil)) found.add(LocalizedMessage.of("env.error.emptyAvailability"));
        leadTime.ifPresent(lead -> { if (lead.isNegative() || lead.compareTo(MAX_LEAD) > 0) found.add(LocalizedMessage.of("env.error.lead", MAX_LEAD.toMinutes())); });
        if (activity.length() > 200) found.add(LocalizedMessage.of("env.error.activity"));
        if (goals.size() > 10 || goals.stream().anyMatch(goal -> goal.length() > ShootRequirement.MAX_TEXT)) found.add(LocalizedMessage.of("env.error.goals"));
        return found.isEmpty() ? Result.success(this) : Result.failures(found);
    }
}
