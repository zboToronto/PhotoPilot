package com.photopilot.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;

/**
 * The record that an F09 environment assessment was reviewed and added to a session (workflow milestone 04). Only what
 * identifies the assessment is kept: when it was made, the shooting date, the place with coordinates, the reviewed
 * availability in that place's time zone, how many shooting windows passed validation, and the confirmed requirement's
 * location and time-window text it was made under (empty when no requirement was confirmed then). No provider facts,
 * weather values or AI text are stored; F06 and F10 keep using the in-process assessment, which this record never
 * replaces.
 */
public class EnvironmentCheck {
    private final Optional<PlaceCandidate.Source> lookupSource;
    private Instant assessedAt;
    private LocalDate date;
    private GeoLocation place;
    private ZonedDateTime availableFrom;
    private ZonedDateTime availableUntil;
    private int windows;
    private Optional<GeoLocation> basisLocation;
    private Optional<String> basisTimeWindow;

    public EnvironmentCheck(Instant assessedAt, LocalDate date, GeoLocation place, ZonedDateTime availableFrom, ZonedDateTime availableUntil,
            int windows, Optional<GeoLocation> basisLocation, Optional<String> basisTimeWindow) {
        this(assessedAt, date, place, availableFrom, availableUntil, windows, basisLocation, basisTimeWindow, Optional.empty());
    }

    public EnvironmentCheck(Instant assessedAt, LocalDate date, GeoLocation place, ZonedDateTime availableFrom, ZonedDateTime availableUntil,
            int windows, Optional<GeoLocation> basisLocation, Optional<String> basisTimeWindow, Optional<PlaceCandidate.Source> lookupSource) {
        this.lookupSource = Objects.requireNonNull(lookupSource);
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt");
        this.date = Objects.requireNonNull(date, "date");
        this.place = Objects.requireNonNull(place, "place");
        this.availableFrom = Objects.requireNonNull(availableFrom, "availableFrom");
        this.availableUntil = Objects.requireNonNull(availableUntil, "availableUntil");
        this.windows = windows;
        this.basisLocation = Objects.requireNonNull(basisLocation, "basisLocation");
        this.basisTimeWindow = Objects.requireNonNull(basisTimeWindow, "basisTimeWindow");
    }

    public Optional<PlaceCandidate.Source> lookupSource() { return lookupSource; }
    public Instant assessedAt() { return assessedAt; }
    public LocalDate date() { return date; }
    public GeoLocation place() { return place; }
    public ZonedDateTime availableFrom() { return availableFrom; }
    public ZonedDateTime availableUntil() { return availableUntil; }
    public int windows() { return windows; }
    public Optional<GeoLocation> basisLocation() { return basisLocation; }
    public Optional<String> basisTimeWindow() { return basisTimeWindow; }
    /** Whether a confirmed requirement was in force when the check was recorded. */
    public boolean madeUnderRequirement() { return basisTimeWindow.isPresent(); }

    public Result<EnvironmentCheck> validate() {
        if (lookupSource.filter(s -> s != PlaceCandidate.Source.OPEN_METEO_GEOCODING && s != PlaceCandidate.Source.GEMINI_ESTIMATED).isPresent()
                || assessedAt == null || date == null || place == null || availableFrom == null || availableUntil == null
                || basisLocation == null || basisTimeWindow == null || windows < 0 || windows > 100
                || place.latitude().isEmpty() || place.longitude().isEmpty() || !place.validate().isSuccess()
                || !availableUntil.isAfter(availableFrom) || !availableFrom.getZone().getRules().equals(availableUntil.getZone().getRules())
                || basisLocation.filter(location -> !location.validate().isSuccess()).isPresent()
                || basisTimeWindow.filter(text -> text.length() > ShootRequirement.MAX_TEXT).isPresent())
            return Result.failureKey("env.record.error.invalid");
        return Result.success(this);
    }
}
