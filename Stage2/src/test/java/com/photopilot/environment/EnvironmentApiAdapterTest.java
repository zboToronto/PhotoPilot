package com.photopilot.environment;

import com.photopilot.common.Result;
import com.photopilot.integration.EnvironmentApiAdapter;
import com.photopilot.integration.GeocodingAdapter;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.GeoLocation;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static com.photopilot.environment.FakeEnvironmentProviders.*;
import static org.junit.jupiter.api.Assertions.*;

/** EnvironmentApiAdapter / GeocodingAdapter against the loopback provider fake with a fixed clock; no live calls. */
class EnvironmentApiAdapterTest {
    static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    static final GeoLocation AIRPORT = new GeoLocation("Billy Bishop Toronto City Airport", 43.6275, -79.3961, false, false, Optional.empty());
    FakeEnvironmentProviders providers;
    HttpClient http;
    EnvironmentApiAdapter adapter;

    @BeforeEach void start() throws Exception {
        providers = new FakeEnvironmentProviders(); http = HttpClient.newHttpClient();
        adapter = new EnvironmentApiAdapter(providers.weatherEndpoint(), providers.sunEndpoint(), http, Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void stop() { providers.close(); http.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    @Test void weatherAndSunAreRetrievedWithUnitsZoneSourcesAndTimestamps() {
        var context = ok(adapter.fetch(AIRPORT, DAY));
        assertEquals(Optional.of(TORONTO), context.zone(), "zone verified by the provider, not the computer's zone");
        assertEquals(24, context.hourly().size(), "one value per local hour of the shooting date");
        assertEquals(DAY.atStartOfDay(TORONTO).toInstant(), context.hourly().getFirst().time(), "unix timestamps map to local midnight");
        var hour = context.hourly().get(15);
        assertEquals(40.0, hour.value("cloudCoverPct").orElseThrow());
        assertEquals(12.5, hour.value("windSpeedKmh").orElseThrow());
        assertEquals(270.0, hour.value("windDirectionDeg").orElseThrow());
        assertEquals(24000.0, hour.value("visibilityM").orElseThrow());
        assertEquals("km/h", EnvironmentContext.UNITS.get("windSpeedKmh"));
        assertEquals(15.5, context.weather().get("temperatureMaxC"));
        // Sun data: provider events in the local date, with sunrise before sunset and twilight around them.
        var sunrise = context.sunrise().orElseThrow(); var sunset = context.sunset().orElseThrow();
        assertEquals(DAY, sunrise.toLocalDate()); assertEquals(TORONTO, sunrise.getZone());
        assertTrue(sunrise.isBefore(sunset));
        assertTrue(context.sunEvent(DAY, "civilDusk").orElseThrow().isAfter(sunset.toInstant()));
        assertTrue(context.sunEvents().stream().allMatch(e -> e.source().equals(EnvironmentApiAdapter.SUN_SOURCE)));
        // Provenance: endpoint per group (no coordinates), one retrieval timestamp, nothing unavailable.
        assertEquals(providers.weatherEndpoint(), context.sources().get(EnvironmentContext.HOURLY));
        assertEquals(providers.sunEndpoint(), context.sources().get(EnvironmentContext.SUN));
        assertEquals(Set.of(NOW), Set.copyOf(context.sourceTimestamps().values()));
        assertTrue(context.unavailableFields().isEmpty(), context.unavailableFields().toString());
        // Requests carry coordinates and dates only (never the user's place label) and ask for UTC timestamps.
        var weather = providers.requests.stream().filter(u -> u.getPath().equals("/v1/forecast")).findFirst().orElseThrow();
        var q = query(weather);
        assertEquals("unixtime", q.get("timeformat")); assertEquals("auto", q.get("timezone"));
        assertEquals("2026-11-07", q.get("start_date")); assertEquals("2026-11-07", q.get("end_date"));
        assertTrue(providers.requests.stream().noneMatch(u -> u.toString().contains("Bishop")));
        assertEquals("America/Toronto", query(providers.requests.stream().filter(u -> u.getPath().equals("/json")).findFirst().orElseThrow()).get("tzid"));
    }

    @Test void partialResponsesKeepWhatExistsAndNameWhatIsMissing() {
        providers.forecast = q -> {
            var body = com.google.gson.JsonParser.parseString(defaultForecast(q).body()).getAsJsonObject();
            var hourly = body.getAsJsonObject("hourly");
            hourly.remove("visibility");
            var clouds = hourly.getAsJsonArray("cloud_cover");
            for (int i = 0; i < 5; i++) clouds.set(i, com.google.gson.JsonNull.INSTANCE);
            hourly.getAsJsonArray("wind_speed_10m").set(6, new com.google.gson.JsonPrimitive(9999));
            return new Reply(200, body.toString());
        };
        var context = ok(adapter.fetch(AIRPORT, DAY));
        assertTrue(context.unavailableFields().contains("hourly.visibilityM"));
        assertTrue(context.hourly().stream().noneMatch(h -> h.value("visibilityM").isPresent()), "never substituted");
        assertTrue(context.hourly().getFirst().value("cloudCoverPct").isEmpty());
        assertTrue(context.hourly().get(6).value("windSpeedKmh").isEmpty(), "implausible values are discarded, not clamped");
        assertTrue(context.notices().stream().anyMatch(n -> n.key().equals("env.notice.partialSeries")));
        assertTrue(context.notices().stream().anyMatch(n -> n.key().equals("env.notice.implausible")));
        assertEquals(40.0, context.hourly().get(10).value("cloudCoverPct").orElseThrow());
    }

    @Test void providerAndNetworkFailuresAreExplicitAndSunDataSurvivesAWeatherOutage() throws Exception {
        providers.forecast = q -> new Reply(503, "");
        var sunOnly = ok(adapter.fetch(AIRPORT, DAY));
        assertTrue(sunOnly.hourly().isEmpty() && sunOnly.weather().isEmpty());
        assertTrue(sunOnly.unavailableFields().containsAll(Set.of(EnvironmentContext.HOURLY, EnvironmentContext.DAILY)));
        assertTrue(sunOnly.notices().stream().anyMatch(n -> n.key().equals("env.notice.weatherUnavailable")));
        assertTrue(sunOnly.sunrise().isPresent());
        assertTrue(sunOnly.zone().isEmpty(), "no verified zone without the weather provider");

        providers.sun = q -> new Reply(429, "");
        var nothing = adapter.fetch(AIRPORT, DAY);
        assertFalse(nothing.isSuccess());
        assertTrue(nothing.hasError("env.error.unavailable") && nothing.hasError("env.error.rateLimited"), nothing.errors().toString());
        providers.close();
        var offline = adapter.fetch(AIRPORT, DAY);
        assertTrue(offline.hasError("env.error.network"), offline.errors().toString());
    }

    @Test void datesOutsideTheForecastHorizonGetSunDataOnlyAndPastDatesAreRejected() {
        LocalDate far = LocalDate.of(2026, 11, 30);
        var context = ok(adapter.fetch(AIRPORT, far));
        assertTrue(context.hourly().isEmpty() && context.weather().isEmpty(), "weather is never extrapolated");
        assertEquals(Optional.of(TORONTO), context.zone(), "zone still verified from coordinates");
        assertTrue(context.notices().stream().anyMatch(n -> n.key().equals("env.notice.beyondHorizon")));
        assertTrue(context.sunrise().isPresent());
        assertTrue(providers.requests.stream().anyMatch(u -> u.getPath().equals("/v1/forecast") && !query(u).containsKey("hourly")));
        // The last day inside the horizon keeps weather; the following day is reported unavailable.
        var edge = ok(adapter.fetch(AIRPORT, LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 20)));
        assertFalse(edge.hourly().isEmpty());
        assertTrue(edge.unavailableFields().contains(EnvironmentContext.HOURLY + "@2026-11-20"));
        assertTrue(adapter.fetch(AIRPORT, LocalDate.of(2026, 10, 1)).hasError("env.error.pastDate"));
        assertTrue(adapter.fetch(AIRPORT, DAY, DAY.plusDays(2)).hasError("env.error.range"));
    }

    @Test void polarSunDataUnexpectedFormatsAndForeignLocationsAreNotUsed() {
        providers.sun = q -> new Reply(200, """
            {"results":{"sunrise":"1970-01-01T00:00:00+00:00","sunset":"1970-01-01T00:00:00+00:00","solar_noon":"2026-11-07T12:01:00-05:00",
            "day_length":0,"civil_twilight_begin":"1970-01-01T00:00:00+00:00","civil_twilight_end":"1970-01-01T00:00:00+00:00",
            "nautical_twilight_begin":"2026-11-07T10:00:00-05:00","nautical_twilight_end":"2026-11-07T14:00:00-05:00",
            "astronomical_twilight_begin":"2026-11-07T08:00:00-05:00","astronomical_twilight_end":"2026-11-07T16:00:00-05:00"},
            "status":"OK","tzid":"America/Toronto"}""");
        var polar = ok(adapter.fetch(AIRPORT, DAY));
        assertTrue(polar.sunrise().isEmpty() && polar.sunset().isEmpty(), "absent events are never invented");
        assertTrue(polar.unavailableFields().contains("sun.sunrise@" + DAY));
        assertTrue(polar.notices().stream().anyMatch(n -> n.key().equals("env.notice.sunEventAbsent")));
        assertTrue(polar.sunEvent(DAY, "solarNoon").isPresent());

        providers.sun = q -> new Reply(200, "{\"results\": [], \"status\": \"OK\"}");
        var fallback = ok(adapter.fetch(AIRPORT, DAY));
        assertTrue(fallback.sunEvents().stream().allMatch(e -> e.source().equals(EnvironmentApiAdapter.WEATHER_SOURCE)),
            "Open-Meteo's own sunrise/sunset are used and labelled when the sun service format is unexpected");
        assertTrue(fallback.sunrise().isPresent() && fallback.sunEvent(DAY, "civilDusk").isEmpty());
        assertTrue(fallback.notices().stream().anyMatch(n -> n.key().equals("env.notice.sunFallback")));

        providers.sun = FakeEnvironmentProviders::defaultSun;
        providers.forecast = q -> new Reply(200, "{\"latitude\": 10.0, \"longitude\": 10.0, \"utc_offset_seconds\": 0, \"timezone\": \"Africa/Lagos\"}");
        var moved = ok(adapter.fetch(AIRPORT, DAY));
        assertTrue(moved.hourly().isEmpty());
        assertTrue(moved.notices().stream().anyMatch(n -> n.key().equals("env.notice.weatherUnavailable")
            && n.arguments().getFirst() instanceof com.photopilot.i18n.LocalizedMessage reason && reason.key().equals("env.error.locationMismatch")));
        providers.forecast = q -> new Reply(200, "{not json");
        assertTrue(ok(adapter.fetch(AIRPORT, DAY)).hourly().isEmpty());
        assertTrue(adapter.fetch(GeoLocation.manual("Toronto"), DAY).hasError("env.location.error.coordinates"), "a label alone is never geocoded implicitly");
    }

    @Test void geocodingReturnsEveryMatchAndReportsUnknownNames() {
        var geocoder = new GeocodingAdapter(providers.geocodingEndpoint(), http, Duration.ofSeconds(5));
        var toronto = ok(geocoder.search("Toronto"));
        assertEquals(3, toronto.size(), "ambiguous names are not resolved automatically");
        assertEquals("Toronto, Ontario, Canada", toronto.getFirst().displayName());
        assertEquals(Optional.of(TORONTO), toronto.getFirst().zone());
        assertTrue(toronto.getFirst().location().approximate());
        assertTrue(geocoder.search("Nowhere Placeville").hasError("env.location.error.notFound"));
        providers.geocode = q -> new Reply(500, "");
        assertTrue(geocoder.search("Toronto").hasError("env.error.unavailable"));
        assertTrue(new GeocodingAdapter(URI.create("http://127.0.0.1:1/v1/search"), http, Duration.ofSeconds(2)).search("Toronto").hasError("env.error.network"));
    }
}
