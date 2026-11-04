package com.photopilot.model;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Stage 1 UML element 83 (zero-based): {weather values carry documented units; partial data allowed}
 * F09 factual evidence for one location and date range, exactly as the providers returned it. Missing values are
 * named in {@link #unavailableFields()} and never substituted. Every field group keeps its source and retrieval
 * timestamp so freshness can be checked later; the location time zone is present only when a provider verified it.
 */
public class EnvironmentContext {
    /** Provenance / freshness groups. */
    public static final String DAILY = "dailyForecast", HOURLY = "hourlyForecast", SUN = "sunEvents";
    /** Documented units of every weather key (daily summary and hourly values). */
    public static final Map<String, String> UNITS;
    static {
        var units = new LinkedHashMap<String, String>();
        units.put("temperatureC", "°C"); units.put("cloudCoverPct", "%"); units.put("precipitationProbabilityPct", "%");
        units.put("precipitationMm", "mm"); units.put("windSpeedKmh", "km/h"); units.put("windGustsKmh", "km/h");
        units.put("windDirectionDeg", "°"); units.put("visibilityM", "m"); units.put("weatherCode", "WMO code");
        units.put("temperatureMaxC", "°C"); units.put("temperatureMinC", "°C"); units.put("precipitationProbabilityMaxPct", "%");
        units.put("precipitationSumMm", "mm"); units.put("windSpeedMaxKmh", "km/h"); units.put("windGustsMaxKmh", "km/h");
        UNITS = Collections.unmodifiableMap(units);
    }
    /** Sun event kinds in daily order. */
    public static final List<String> SUN_KINDS = List.of("astronomicalDawn", "nauticalDawn", "civilDawn", "sunrise", "solarNoon",
        "sunset", "civilDusk", "nauticalDusk", "astronomicalDusk");

    /** One forecast hour: the applicable instant and only the values the provider supplied for it. */
    public record Hour(Instant time, Map<String, Double> values) {
        public Hour {
            Objects.requireNonNull(time, "time");
            var copy = new LinkedHashMap<String, Double>();
            values.forEach((key, value) -> {
                if (!UNITS.containsKey(key) || value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid hourly value " + key);
                copy.put(key, value);
            });
            values = Collections.unmodifiableMap(copy);
        }
        public Optional<Double> value(String key) { return Optional.ofNullable(values.get(key)); }
    }
    /** One provider-reported sun event on a local calendar date. */
    public record SunEvent(LocalDate date, String kind, Instant time, String source) {
        public SunEvent {
            Objects.requireNonNull(date, "date"); Objects.requireNonNull(time, "time"); Objects.requireNonNull(source, "source");
            if (!SUN_KINDS.contains(kind)) throw new IllegalArgumentException("Unknown sun event " + kind);
        }
    }

    private Map<String, Double> weather;
    private Optional<ZonedDateTime> sunrise;
    private Optional<ZonedDateTime> sunset;
    private Map<String, URI> sources;
    private Map<String, Instant> sourceTimestamps;
    private Set<String> unavailableFields;
    private Instant retrievedAt;
    private GeoLocation place;
    private LocalDate date;
    private LocalDate endDate;
    private Optional<ZoneId> zone = Optional.empty();
    private List<Hour> hourly = List.of();
    private List<SunEvent> sunEvents = List.of();
    private List<LocalizedMessage> notices = List.of();

    public EnvironmentContext(Map<String, Double> weather, Optional<ZonedDateTime> sunrise, Optional<ZonedDateTime> sunset, Map<String, URI> sources, Map<String, Instant> sourceTimestamps, Set<String> unavailableFields, Instant retrievedAt) {
        this.weather = Map.copyOf(weather);
        this.sunrise = Objects.requireNonNull(sunrise, "sunrise");
        this.sunset = Objects.requireNonNull(sunset, "sunset");
        this.sources = Map.copyOf(sources);
        this.sourceTimestamps = Map.copyOf(sourceTimestamps);
        this.unavailableFields = Set.copyOf(unavailableFields);
        this.retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
    }

    /**
     * F09 provider result. {@code weather} holds the provider's daily summary for {@code date}; {@code hourly} the
     * forecast hours from {@code date} through {@code endDate}. Sunrise/sunset accessors derive from the sun events.
     */
    public EnvironmentContext(GeoLocation place, LocalDate date, LocalDate endDate, Optional<ZoneId> zone, Map<String, Double> weather,
            List<Hour> hourly, List<SunEvent> sunEvents, Map<String, URI> sources, Map<String, Instant> sourceTimestamps,
            Set<String> unavailableFields, List<LocalizedMessage> notices, Instant retrievedAt) {
        this(weather, Optional.empty(), Optional.empty(), sources, sourceTimestamps, unavailableFields, retrievedAt);
        this.place = Objects.requireNonNull(place, "place");
        this.date = Objects.requireNonNull(date, "date");
        this.endDate = Objects.requireNonNull(endDate, "endDate");
        if (endDate.isBefore(date)) throw new IllegalArgumentException("Environment date range is reversed.");
        this.zone = Objects.requireNonNull(zone, "zone");
        weather.forEach((key, value) -> {
            if (!UNITS.containsKey(key) || value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid weather value " + key);
        });
        var hours = new ArrayList<>(hourly);
        hours.sort(Comparator.comparing(Hour::time));
        for (int i = 1; i < hours.size(); i++)
            if (!hours.get(i).time().isAfter(hours.get(i - 1).time())) throw new IllegalArgumentException("Duplicate forecast hour.");
        this.hourly = List.copyOf(hours);
        this.sunEvents = List.copyOf(sunEvents);
        this.notices = List.copyOf(notices);
        ZoneId display = zone.orElse(ZoneOffset.UTC);
        this.sunrise = sunEvent(date, "sunrise").map(time -> time.atZone(display));
        this.sunset = sunEvent(date, "sunset").map(time -> time.atZone(display));
    }

    /**
     * Fields whose source timestamp is older than {@code maxAge}, plus fields with an invalid (future) timestamp.
     * Callers apply type-specific limits: forecasts age much faster than astronomical predictions.
     */
    public Set<String> staleFields(Instant now, Duration maxAge) {
        Objects.requireNonNull(now, "now");
        if (maxAge == null || maxAge.isNegative()) throw new IllegalArgumentException("maxAge must be non-negative");
        var stale = new TreeSet<String>();
        Instant oldest = now.minus(maxAge), latest = now.plus(Duration.ofMinutes(5));
        sourceTimestamps.forEach((field, stamp) -> { if (stamp.isBefore(oldest) || stamp.isAfter(latest)) stale.add(field); });
        return Collections.unmodifiableSet(stale);
    }

    public Optional<Instant> sunEvent(LocalDate day, String kind) {
        return sunEvents.stream().filter(e -> e.date().equals(day) && e.kind().equals(kind)).map(SunEvent::time).findFirst();
    }
    /** Forecast hours whose applicable instant lies in [from, to]. */
    public List<Hour> hours(Instant from, Instant to) {
        return hourly.stream().filter(h -> !h.time().isBefore(from) && !h.time().isAfter(to)).toList();
    }
    public boolean hasWeather() { return !hourly.isEmpty() || !weather.isEmpty(); }
    public boolean hasSunTimes() { return sunrise.isPresent() && sunset.isPresent(); }

    public Map<String, Double> weather() { return weather; }
    public Optional<ZonedDateTime> sunrise() { return sunrise; }
    public Optional<ZonedDateTime> sunset() { return sunset; }
    public Map<String, URI> sources() { return sources; }
    public Map<String, Instant> sourceTimestamps() { return sourceTimestamps; }
    public Set<String> unavailableFields() { return unavailableFields; }
    public Instant retrievedAt() { return retrievedAt; }
    public Optional<GeoLocation> place() { return Optional.ofNullable(place); }
    public Optional<LocalDate> date() { return Optional.ofNullable(date); }
    public Optional<LocalDate> endDate() { return Optional.ofNullable(endDate); }
    public Optional<ZoneId> zone() { return zone; }
    public List<Hour> hourly() { return hourly; }
    public List<SunEvent> sunEvents() { return sunEvents; }
    /** Provider-level limitations (partial series, absent events, disagreements); never weather values. */
    public List<LocalizedMessage> notices() { return notices; }
}
