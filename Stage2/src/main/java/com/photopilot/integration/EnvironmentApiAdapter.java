package com.photopilot.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.EnvironmentContext.Hour;
import com.photopilot.model.EnvironmentContext.SunEvent;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 81 (zero-based): {Adapter; providers selected for F09}
 * Translates two free, key-less public providers into {@link EnvironmentContext}:
 * Open-Meteo forecast API (weather, location time zone; https://open-meteo.com/en/docs) and the sunrise-sunset.org
 * API (sunrise, sunset, solar noon, civil/nautical/astronomical twilight; https://sunrise-sunset.org/api).
 * All provider-specific request/response logic stays here. Missing, implausible or inconsistent values are dropped
 * and reported as unavailable; nothing is estimated or filled in. Only coordinates and dates leave the application.
 */
public class EnvironmentApiAdapter implements EnvironmentProvider {
    public static final URI DEFAULT_WEATHER_ENDPOINT = URI.create("https://api.open-meteo.com/v1/forecast");
    public static final URI DEFAULT_SUN_ENDPOINT = URI.create("https://api.sunrise-sunset.org/json");
    /** Open-Meteo forecast coverage: today plus 15 days. Dates beyond it get sun data only. */
    public static final int FORECAST_DAYS = 16;
    public static final String WEATHER_SOURCE = "Open-Meteo", SUN_SOURCE = "sunrise-sunset.org";
    private static final int MAX_BODY = 2_000_000, MAX_REASON = 200;
    /** Provider variable -> internal key (units in EnvironmentContext.UNITS). */
    private static final Map<String, String> HOURLY = ordered("temperature_2m", "temperatureC", "cloud_cover", "cloudCoverPct",
        "precipitation_probability", "precipitationProbabilityPct", "precipitation", "precipitationMm", "wind_speed_10m", "windSpeedKmh",
        "wind_gusts_10m", "windGustsKmh", "wind_direction_10m", "windDirectionDeg", "visibility", "visibilityM", "weather_code", "weatherCode");
    private static final Map<String, String> DAILY = ordered("temperature_2m_max", "temperatureMaxC", "temperature_2m_min", "temperatureMinC",
        "precipitation_probability_max", "precipitationProbabilityMaxPct", "precipitation_sum", "precipitationSumMm",
        "wind_speed_10m_max", "windSpeedMaxKmh", "wind_gusts_10m_max", "windGustsMaxKmh");
    private static final Map<String, String> SUN = ordered("astronomical_twilight_begin", "astronomicalDawn", "nautical_twilight_begin", "nauticalDawn",
        "civil_twilight_begin", "civilDawn", "sunrise", "sunrise", "solar_noon", "solarNoon", "sunset", "sunset",
        "civil_twilight_end", "civilDusk", "nautical_twilight_end", "nauticalDusk", "astronomical_twilight_end", "astronomicalDusk");
    /** Physically plausible ranges; values outside are treated as provider errors, not clamped. */
    private static final Map<String, double[]> PLAUSIBLE = Map.ofEntries(
        Map.entry("temperatureC", new double[]{-90, 60}), Map.entry("temperatureMaxC", new double[]{-90, 60}), Map.entry("temperatureMinC", new double[]{-90, 60}),
        Map.entry("cloudCoverPct", new double[]{0, 100}), Map.entry("precipitationProbabilityPct", new double[]{0, 100}),
        Map.entry("precipitationProbabilityMaxPct", new double[]{0, 100}), Map.entry("precipitationMm", new double[]{0, 500}),
        Map.entry("precipitationSumMm", new double[]{0, 2000}), Map.entry("windSpeedKmh", new double[]{0, 400}), Map.entry("windSpeedMaxKmh", new double[]{0, 400}),
        Map.entry("windGustsKmh", new double[]{0, 500}), Map.entry("windGustsMaxKmh", new double[]{0, 500}), Map.entry("windDirectionDeg", new double[]{0, 360}),
        Map.entry("visibilityM", new double[]{0, 1_000_000}), Map.entry("weatherCode", new double[]{0, 99}));

    private URI weatherEndpoint;
    private URI sunEndpoint;
    private HttpClient http;
    private final Duration timeout;
    private final Clock clock;

    public EnvironmentApiAdapter(URI weatherEndpoint, URI sunEndpoint, HttpClient http) {
        this(weatherEndpoint, sunEndpoint, http, Duration.ofSeconds(15), Clock.systemUTC());
    }

    public EnvironmentApiAdapter(URI weatherEndpoint, URI sunEndpoint, HttpClient http, Duration timeout, Clock clock) {
        this.weatherEndpoint = Objects.requireNonNull(weatherEndpoint, "weatherEndpoint");
        this.sunEndpoint = Objects.requireNonNull(sunEndpoint, "sunEndpoint");
        this.http = Objects.requireNonNull(http, "http");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result<EnvironmentContext> fetch(GeoLocation place, LocalDate date) {
        return fetch(place, date, date);
    }

    @Override
    public Result<EnvironmentContext> fetch(GeoLocation place, LocalDate from, LocalDate to) {
        if (place == null || place.latitude().isEmpty() || place.longitude().isEmpty() || !place.validate().isSuccess())
            return Result.failureKey("env.location.error.coordinates");
        if (from == null || to == null || to.isBefore(from) || to.isAfter(from.plusDays(1))) return Result.failureKey("env.error.range");
        double lat = place.latitude().get(), lon = place.longitude().get();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // UTC "today" can differ by one day from the location's; the exact local check follows once the zone is verified.
        if (to.isBefore(today.minusDays(1))) return Result.failureKey("env.error.pastDate", from.toString());
        LocalDate lastForecastDay = today.plusDays(FORECAST_DAYS - 1);
        Instant retrieved = clock.instant();
        var unavailable = new TreeSet<String>();
        var notices = new ArrayList<LocalizedMessage>();
        var sources = new LinkedHashMap<String, URI>();
        var stamps = new LinkedHashMap<String, Instant>();
        var weather = new Weather();
        Result<?> weatherFailure = null;

        if (from.isAfter(lastForecastDay)) {
            notices.add(LocalizedMessage.of("env.notice.beyondHorizon", FORECAST_DAYS, lastForecastDay.toString()));
            unavailable.add(EnvironmentContext.DAILY); unavailable.add(EnvironmentContext.HOURLY);
            // Coordinates-only time-zone resolution from the same provider; no weather values are used.
            var zoneOnly = get(weatherUri(lat, lon, null, null));
            if (zoneOnly.isSuccess()) weather.zone = zone(zoneOnly.value().orElseThrow(), notices);
            else weatherFailure = zoneOnly;
        } else {
            LocalDate end = to.isAfter(lastForecastDay) ? lastForecastDay : to;
            if (end.isBefore(to)) { notices.add(LocalizedMessage.of("env.notice.nextDayBeyondHorizon", to.toString())); unavailable.add(EnvironmentContext.HOURLY + "@" + to); }
            var body = get(weatherUri(lat, lon, from, end));
            var parsed = body.isSuccess() ? parseWeather(body.value().orElseThrow(), lat, lon, from, end, weather, unavailable, notices) : Result.failure(body);
            if (!parsed.isSuccess()) {
                weatherFailure = parsed;
                unavailable.add(EnvironmentContext.DAILY); unavailable.add(EnvironmentContext.HOURLY);
                notices.add(LocalizedMessage.of("env.notice.weatherUnavailable", parsed.messages().getFirst()));
            }
        }
        if (!weather.hours.isEmpty()) { sources.put(EnvironmentContext.HOURLY, weatherEndpoint); stamps.put(EnvironmentContext.HOURLY, retrieved); }
        if (!weather.daily.isEmpty()) { sources.put(EnvironmentContext.DAILY, weatherEndpoint); stamps.put(EnvironmentContext.DAILY, retrieved); }

        var events = new ArrayList<SunEvent>();
        Result<?> sunFailure = null;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            var body = get(sunUri(lat, lon, day, weather.zone));
            var parsed = body.isSuccess() ? parseSun(body.value().orElseThrow(), day, weather.zone, events, unavailable, notices) : Result.failure(body);
            if (!parsed.isSuccess()) {
                sunFailure = parsed;
                LocalDate failed = day;
                // Open-Meteo's own sunrise/sunset for that date remain usable, labelled with their source.
                var fallback = weather.sun.getOrDefault(failed, Map.of());
                fallback.forEach((kind, time) -> events.add(new SunEvent(failed, kind, time, WEATHER_SOURCE)));
                EnvironmentContext.SUN_KINDS.stream().filter(kind -> !fallback.containsKey(kind)).forEach(kind -> unavailable.add("sun." + kind + "@" + failed));
                notices.add(LocalizedMessage.of(fallback.isEmpty() ? "env.notice.sunUnavailable" : "env.notice.sunFallback", failed.toString(), parsed.messages().getFirst()));
            } else crossCheck(day, events, weather.sun.getOrDefault(day, Map.of()), notices);
        }
        if (!events.isEmpty()) {
            sources.put(EnvironmentContext.SUN, events.stream().allMatch(e -> e.source().equals(WEATHER_SOURCE)) ? weatherEndpoint : sunEndpoint);
            stamps.put(EnvironmentContext.SUN, retrieved);
        } else unavailable.add(EnvironmentContext.SUN);
        if (weather.hours.isEmpty() && weather.daily.isEmpty() && events.isEmpty()) {
            var reasons = new ArrayList<LocalizedMessage>();
            if (weatherFailure != null) reasons.addAll(weatherFailure.messages());
            if (sunFailure != null) reasons.addAll(sunFailure.messages());
            if (reasons.isEmpty()) reasons.add(LocalizedMessage.of("env.error.noData"));
            return Result.failures(reasons);
        }
        return Result.success(new EnvironmentContext(place, from, to, weather.zone, weather.daily, weather.hours, events, sources, stamps,
            unavailable, notices, retrieved));
    }

    private static final class Weather {
        Optional<ZoneId> zone = Optional.empty();
        Map<String, Double> daily = new LinkedHashMap<>();
        List<Hour> hours = new ArrayList<>();
        Map<LocalDate, Map<String, Instant>> sun = new HashMap<>();
    }

    private URI weatherUri(double lat, double lon, LocalDate from, LocalDate to) {
        String query = "latitude=" + coordinate(lat) + "&longitude=" + coordinate(lon) + "&timezone=auto&timeformat=unixtime";
        query += from == null ? "&daily=sunrise&forecast_days=1"
            : "&hourly=" + String.join(",", HOURLY.keySet()) + "&daily=sunrise,sunset," + String.join(",", DAILY.keySet())
                + "&wind_speed_unit=kmh&temperature_unit=celsius&precipitation_unit=mm&start_date=" + from + "&end_date=" + to;
        return URI.create(weatherEndpoint.toString().replaceAll("\\?.*$", "") + "?" + query);
    }
    private URI sunUri(double lat, double lon, LocalDate day, Optional<ZoneId> zone) {
        String query = "lat=" + coordinate(lat) + "&lng=" + coordinate(lon) + "&date=" + day + "&formatted=0"
            + zone.map(z -> "&tzid=" + z.getId()).orElse("");
        return URI.create(sunEndpoint.toString().replaceAll("\\?.*$", "") + "?" + query);
    }
    private static String coordinate(double value) { return String.format(Locale.ROOT, "%.5f", value); }

    /** One GET; provider failures become explicit localized errors that never contain request coordinates. */
    private Result<JsonObject> get(URI uri) {
        String host = Objects.toString(uri.getHost(), "provider");
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException timedOut) {
            return Result.failureKey("env.error.timeout", host, String.valueOf(timeout.toSeconds()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.failureKey("env.error.interrupted", host);
        } catch (IOException | SecurityException | IllegalArgumentException unreachable) {
            return Result.failureKey("env.error.network", host);
        }
        String body = Objects.toString(response.body(), "");
        int status = response.statusCode();
        if (status == 429) return Result.failureKey("env.error.rateLimited", host);
        if (status >= 500) return Result.failureKey("env.error.unavailable", host, String.valueOf(status));
        if (status != 200) return Result.failureKey("env.error.provider", host, String.valueOf(status), reason(body));
        if (body.length() > MAX_BODY) return Result.failureKey("env.error.format", host);
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return Result.failureKey("env.error.format", host);
            return Result.success(root.getAsJsonObject());
        } catch (RuntimeException malformed) {
            return Result.failureKey("env.error.format", host);
        }
    }
    private static String reason(String body) {
        try {
            var root = JsonParser.parseString(body).getAsJsonObject();
            String text = root.has("reason") ? root.get("reason").getAsString() : root.has("status") ? root.get("status").getAsString() : "";
            return text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON) + "...";
        } catch (RuntimeException unreadable) { return ""; }
    }

    /** Named regional zones only: Open-Meteo answers "GMT" where it cannot resolve one, which is not a verified local zone. */
    private static Optional<ZoneId> zone(JsonObject root, List<LocalizedMessage> notices) {
        try {
            if (root.has("timezone") && root.get("timezone").isJsonPrimitive()) {
                ZoneId zone = ZoneId.of(root.get("timezone").getAsString());
                if (zone.getId().contains("/") && !zone.getId().startsWith("Etc/")) return Optional.of(zone);
            }
        } catch (DateTimeException | UnsupportedOperationException invalid) { /* reported below */ }
        notices.add(LocalizedMessage.of("env.notice.zoneUnverified"));
        return Optional.empty();
    }

    private Result<Boolean> parseWeather(JsonObject root, double lat, double lon, LocalDate from, LocalDate to, Weather out,
            Set<String> unavailable, List<LocalizedMessage> notices) {
        String host = Objects.toString(weatherEndpoint.getHost(), WEATHER_SOURCE);
        try {
            if (root.has("error")) return Result.failureKey("env.error.provider", host, "200", reason(root.toString()));
            double gotLat = root.get("latitude").getAsDouble(), gotLon = root.get("longitude").getAsDouble();
            // Open-Meteo snaps to its model grid; a larger difference means the data is for another place.
            if (Math.abs(gotLat - lat) > 0.5 || Math.abs(gotLon - lon) > 0.5) return Result.failureKey("env.error.locationMismatch", host);
            out.zone = zone(root, notices);
            ZoneId zone = out.zone.orElse(ZoneOffset.ofTotalSeconds(root.get("utc_offset_seconds").getAsInt()));
            Instant earliest = from.atStartOfDay(zone).toInstant(), latest = to.plusDays(1).atStartOfDay(zone).toInstant();
            if (root.has("hourly") && root.get("hourly").isJsonObject()) {
                JsonObject hourly = root.getAsJsonObject("hourly");
                long[] times = epochs(hourly.getAsJsonArray("time"));
                var values = new ArrayList<Map<String, Double>>();
                for (int i = 0; i < times.length; i++) values.add(new LinkedHashMap<>());
                for (var variable : HOURLY.entrySet()) {
                    String key = variable.getValue();
                    if (!hourly.has(variable.getKey()) || !hourly.get(variable.getKey()).isJsonArray()) { unavailable.add("hourly." + key); continue; }
                    JsonArray series = hourly.getAsJsonArray(variable.getKey());
                    if (series.size() != times.length) return Result.failureKey("env.error.format", host);
                    int missing = 0, implausible = 0;
                    for (int i = 0; i < times.length; i++) {
                        var item = series.get(i);
                        if (item == null || item.isJsonNull()) { missing++; continue; }
                        double value = item.getAsDouble();
                        if (!plausible(key, value)) { implausible++; continue; }
                        values.get(i).put(key, value);
                    }
                    if (missing + implausible == times.length) unavailable.add("hourly." + key);
                    else if (missing > 0) notices.add(LocalizedMessage.of("env.notice.partialSeries", key, missing, times.length));
                    if (implausible > 0) notices.add(LocalizedMessage.of("env.notice.implausible", key, implausible));
                }
                for (int i = 0; i < times.length; i++) {
                    Instant time = Instant.ofEpochSecond(times[i]);
                    if (i > 0 && times[i] <= times[i - 1]) return Result.failureKey("env.error.format", host);
                    if (time.isBefore(earliest) || !time.isBefore(latest)) continue; // outside the requested local days
                    if (!values.get(i).isEmpty()) out.hours.add(new Hour(time, values.get(i)));
                }
            } else unavailable.add(EnvironmentContext.HOURLY);
            if (root.has("daily") && root.get("daily").isJsonObject()) {
                JsonObject daily = root.getAsJsonObject("daily");
                long[] days = epochs(daily.getAsJsonArray("time"));
                for (int i = 0; i < days.length; i++) {
                    LocalDate day = Instant.ofEpochSecond(days[i]).atZone(zone).toLocalDate();
                    var sun = new LinkedHashMap<String, Instant>();
                    for (String kind : List.of("sunrise", "sunset")) {
                        var series = daily.has(kind) && daily.get(kind).isJsonArray() ? daily.getAsJsonArray(kind) : null;
                        if (series != null && series.size() == days.length && !series.get(i).isJsonNull()) {
                            Instant time = Instant.ofEpochSecond(series.get(i).getAsLong());
                            if (time.atZone(zone).toLocalDate().equals(day)) sun.put(kind, time);
                        }
                    }
                    out.sun.put(day, sun);
                    if (!day.equals(from)) continue;
                    for (var variable : DAILY.entrySet()) {
                        var series = daily.has(variable.getKey()) && daily.get(variable.getKey()).isJsonArray() ? daily.getAsJsonArray(variable.getKey()) : null;
                        if (series == null || series.size() != days.length || series.get(i).isJsonNull()) { unavailable.add("daily." + variable.getValue()); continue; }
                        double value = series.get(i).getAsDouble();
                        if (plausible(variable.getValue(), value)) out.daily.put(variable.getValue(), value);
                        else { unavailable.add("daily." + variable.getValue()); notices.add(LocalizedMessage.of("env.notice.implausible", variable.getValue(), 1)); }
                    }
                }
                if (out.daily.isEmpty()) unavailable.add(EnvironmentContext.DAILY);
            } else unavailable.add(EnvironmentContext.DAILY);
            if (out.hours.isEmpty()) unavailable.add(EnvironmentContext.HOURLY);
            if (out.hours.isEmpty() && out.daily.isEmpty()) return Result.failureKey("env.error.noWeather", host);
            return Result.success(true);
        } catch (RuntimeException unexpected) {
            out.hours.clear(); out.daily.clear(); out.sun.clear();
            return Result.failureKey("env.error.format", host);
        }
    }
    private static long[] epochs(JsonArray array) {
        long[] values = new long[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsLong();
        return values;
    }
    private static boolean plausible(String key, double value) {
        double[] range = PLAUSIBLE.get(key);
        return Double.isFinite(value) && range != null && value >= range[0] && value <= range[1]
            && (!key.equals("weatherCode") || value == Math.rint(value));
    }

    private Result<Boolean> parseSun(JsonObject root, LocalDate day, Optional<ZoneId> zone, List<SunEvent> events,
            Set<String> unavailable, List<LocalizedMessage> notices) {
        String host = Objects.toString(sunEndpoint.getHost(), SUN_SOURCE);
        try {
            String status = root.has("status") ? root.get("status").getAsString() : "";
            if (!status.equals("OK")) return Result.failureKey("env.error.provider", host, "200", status);
            // The provider silently answers in UTC for an unknown time zone; only accept the zone we asked for.
            if (zone.isPresent() && root.has("tzid") && !zone.get().getId().equals(root.get("tzid").getAsString()))
                notices.add(LocalizedMessage.of("env.notice.sunZone", root.get("tzid").getAsString()));
            JsonObject results = root.getAsJsonObject("results");
            var absent = new ArrayList<String>();
            ZoneId local = zone.orElse(ZoneOffset.UTC);
            for (var field : SUN.entrySet()) {
                String kind = field.getValue();
                if (!results.has(field.getKey()) || !results.get(field.getKey()).isJsonPrimitive()) { unavailable.add("sun." + kind + "@" + day); continue; }
                Instant time = OffsetDateTime.parse(results.get(field.getKey()).getAsString()).toInstant();
                // The provider reports an event that does not occur on this date (polar day/night) as the 1970 epoch.
                if (time.atZone(ZoneOffset.UTC).getYear() == 1970) { absent.add(kind); unavailable.add("sun." + kind + "@" + day); continue; }
                LocalDate eventDay = time.atZone(local).toLocalDate();
                // Twilight ends may fall after local midnight at high latitude; everything else must be on the requested date.
                boolean ok = eventDay.equals(day) || (zone.isEmpty() && Math.abs(eventDay.toEpochDay() - day.toEpochDay()) <= 1)
                    || (kind.endsWith("Dusk") && eventDay.equals(day.plusDays(1))) || (kind.endsWith("Dawn") && eventDay.equals(day.minusDays(1)));
                if (!ok) { notices.add(LocalizedMessage.of("env.notice.sunDate", kind, day.toString())); unavailable.add("sun." + kind + "@" + day); continue; }
                events.add(new SunEvent(day, kind, time, SUN_SOURCE));
            }
            if (!absent.isEmpty()) notices.add(LocalizedMessage.of("env.notice.sunEventAbsent", String.join(", ", absent), day.toString()));
            return Result.success(true);
        } catch (RuntimeException unexpected) {
            events.removeIf(event -> event.date().equals(day) && event.source().equals(SUN_SOURCE));
            return Result.failureKey("env.error.format", host);
        }
    }

    /** Both providers report sunrise/sunset; a disagreement of more than five minutes is reported, never averaged. */
    private static void crossCheck(LocalDate day, List<SunEvent> events, Map<String, Instant> weatherSun, List<LocalizedMessage> notices) {
        for (var entry : weatherSun.entrySet()) {
            events.stream().filter(e -> e.date().equals(day) && e.kind().equals(entry.getKey())).findFirst().ifPresent(event -> {
                long minutes = Math.abs(Duration.between(event.time(), entry.getValue()).toMinutes());
                if (minutes > 5) notices.add(LocalizedMessage.of("env.notice.sunMismatch", entry.getKey(), day.toString(), minutes));
            });
        }
    }

    private static Map<String, String> ordered(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }
}
