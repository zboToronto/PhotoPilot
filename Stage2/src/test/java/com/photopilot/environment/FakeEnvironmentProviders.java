package com.photopilot.environment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.service.SolarCalculator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Offline loopback stand-in for Open-Meteo forecast/geocoding and sunrise-sunset.org, reproducing their documented
 * JSON shapes. Default replies are generated for whatever coordinates/dates are requested: fixed weather values and
 * sun times computed with the solar model (so tests stay consistent on any date). No live network calls.
 */
public final class FakeEnvironmentProviders implements AutoCloseable {
    public record Reply(int status, String body) { }
    public static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private final HttpServer server;
    public final List<URI> requests = new CopyOnWriteArrayList<>();
    public volatile Function<Map<String, String>, Reply> forecast = FakeEnvironmentProviders::defaultForecast;
    public volatile Function<Map<String, String>, Reply> sun = FakeEnvironmentProviders::defaultSun;
    public volatile Function<Map<String, String>, Reply> geocode = FakeEnvironmentProviders::defaultGeocode;

    public FakeEnvironmentProviders() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/forecast", exchange -> answer(exchange, forecast));
        server.createContext("/json", exchange -> answer(exchange, sun));
        server.createContext("/v1/search", exchange -> answer(exchange, geocode));
        server.start();
    }
    private void answer(HttpExchange exchange, Function<Map<String, String>, Reply> handler) throws java.io.IOException {
        requests.add(exchange.getRequestURI());
        Reply reply;
        try { reply = handler.apply(query(exchange.getRequestURI())); } catch (RuntimeException failure) { reply = new Reply(500, ""); }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    public static Map<String, String> query(URI uri) {
        var values = new LinkedHashMap<String, String>();
        if (uri.getRawQuery() == null) return values;
        for (String pair : uri.getRawQuery().split("&")) {
            int split = pair.indexOf('=');
            values.put(URLDecoder.decode(split < 0 ? pair : pair.substring(0, split), StandardCharsets.UTF_8),
                split < 0 ? "" : URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
    String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public URI weatherEndpoint() { return URI.create(base() + "/v1/forecast"); }
    public URI sunEndpoint() { return URI.create(base() + "/json"); }
    public URI geocodingEndpoint() { return URI.create(base() + "/v1/search"); }
    /** Bootstrap environment that sends every provider request to this server (plus any Gemini settings given). */
    public Map<String, String> environment(Map<String, String> base) {
        var environment = new HashMap<>(base);
        environment.put("PHOTOPILOT_WEATHER_ENDPOINT", weatherEndpoint().toString());
        environment.put("PHOTOPILOT_SUN_ENDPOINT", sunEndpoint().toString());
        environment.put("PHOTOPILOT_GEOCODING_ENDPOINT", geocodingEndpoint().toString());
        return environment;
    }
    public long count(String path) { return requests.stream().filter(uri -> uri.getPath().equals(path)).count(); }

    /** Open-Meteo style forecast: fixed plausible weather for every local hour of the requested days. */
    public static Reply defaultForecast(Map<String, String> q) {
        double lat = Double.parseDouble(q.get("latitude")), lon = Double.parseDouble(q.get("longitude"));
        var root = header(lat, lon);
        if (!q.containsKey("start_date")) {
            var daily = new JsonObject(); daily.add("time", array(LocalDate.now(TORONTO).atStartOfDay(TORONTO).toEpochSecond()));
            root.add("daily", daily);
            return new Reply(200, root.toString());
        }
        LocalDate from = LocalDate.parse(q.get("start_date")), to = LocalDate.parse(q.get("end_date"));
        var hourly = new JsonObject();
        var names = List.of("temperature_2m", "cloud_cover", "precipitation_probability", "precipitation", "wind_speed_10m",
            "wind_gusts_10m", "wind_direction_10m", "visibility", "weather_code");
        double[] values = {12.0, 40, 10, 0.0, 12.5, 20.0, 270, 24000, 2};
        var time = new JsonArray(); var series = new ArrayList<JsonArray>();
        names.forEach(name -> series.add(new JsonArray()));
        for (var t = from.atStartOfDay(TORONTO); t.isBefore(to.plusDays(1).atStartOfDay(TORONTO)); t = t.plusHours(1)) {
            time.add(t.toEpochSecond());
            for (int i = 0; i < names.size(); i++) series.get(i).add(values[i]);
        }
        hourly.add("time", time);
        for (int i = 0; i < names.size(); i++) if (q.get("hourly").contains(names.get(i))) hourly.add(names.get(i), series.get(i));
        root.add("hourly", hourly);
        var daily = new JsonObject(); var days = new JsonArray(); var rise = new JsonArray(); var set = new JsonArray();
        var tmax = new JsonArray(); var tmin = new JsonArray(); var ppmax = new JsonArray(); var psum = new JsonArray(); var wmax = new JsonArray(); var gmax = new JsonArray();
        for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
            days.add(day.atStartOfDay(TORONTO).toEpochSecond());
            rise.add(event(lat, lon, day, SolarCalculator.SUNRISE_ELEVATION, true).getEpochSecond());
            set.add(event(lat, lon, day, SolarCalculator.SUNRISE_ELEVATION, false).getEpochSecond());
            tmax.add(15.5); tmin.add(6.0); ppmax.add(20); psum.add(0.0); wmax.add(18.0); gmax.add(30.0);
        }
        daily.add("time", days); daily.add("sunrise", rise); daily.add("sunset", set); daily.add("temperature_2m_max", tmax);
        daily.add("temperature_2m_min", tmin); daily.add("precipitation_probability_max", ppmax); daily.add("precipitation_sum", psum);
        daily.add("wind_speed_10m_max", wmax); daily.add("wind_gusts_10m_max", gmax);
        root.add("daily", daily);
        return new Reply(200, root.toString());
    }
    static JsonObject header(double lat, double lon) {
        var root = new JsonObject();
        root.addProperty("latitude", Math.round(lat * 100) / 100.0); root.addProperty("longitude", Math.round(lon * 100) / 100.0);
        root.addProperty("generationtime_ms", 0.3); root.addProperty("utc_offset_seconds", TORONTO.getRules().getOffset(Instant.now()).getTotalSeconds());
        root.addProperty("timezone", TORONTO.getId()); root.addProperty("timezone_abbreviation", "GMT-5");
        return root;
    }
    private static JsonArray array(long value) { var a = new JsonArray(); a.add(value); return a; }

    /** sunrise-sunset.org style reply (formatted=0) with times computed by the solar model. */
    public static Reply defaultSun(Map<String, String> q) {
        double lat = Double.parseDouble(q.get("lat")), lon = Double.parseDouble(q.get("lng"));
        LocalDate day = LocalDate.parse(q.get("date"));
        ZoneId zone = q.containsKey("tzid") ? ZoneId.of(q.get("tzid")) : ZoneOffset.UTC;
        var results = new JsonObject();
        put(results, "sunrise", event(lat, lon, day, SolarCalculator.SUNRISE_ELEVATION, true), zone);
        put(results, "sunset", event(lat, lon, day, SolarCalculator.SUNRISE_ELEVATION, false), zone);
        put(results, "civil_twilight_begin", event(lat, lon, day, -6, true), zone);
        put(results, "civil_twilight_end", event(lat, lon, day, -6, false), zone);
        put(results, "nautical_twilight_begin", event(lat, lon, day, -12, true), zone);
        put(results, "nautical_twilight_end", event(lat, lon, day, -12, false), zone);
        put(results, "astronomical_twilight_begin", event(lat, lon, day, -18, true), zone);
        put(results, "astronomical_twilight_end", event(lat, lon, day, -18, false), zone);
        Instant rise = event(lat, lon, day, SolarCalculator.SUNRISE_ELEVATION, true), set = event(lat, lon, day, SolarCalculator.SUNRISE_ELEVATION, false);
        put(results, "solar_noon", rise.plus(Duration.between(rise, set).dividedBy(2)), zone);
        results.addProperty("day_length", Duration.between(rise, set).toSeconds());
        var root = new JsonObject(); root.add("results", results); root.addProperty("status", "OK"); root.addProperty("tzid", zone.getId());
        return new Reply(200, root.toString());
    }
    private static void put(JsonObject results, String key, Instant time, ZoneId zone) {
        results.addProperty(key, time.atZone(zone).toOffsetDateTime().withNano(0).toString());
    }
    /** Solar-model event on the local Toronto date (test fixtures use Toronto-area coordinates). */
    public static Instant event(double lat, double lon, LocalDate day, double elevation, boolean rising) {
        Instant from = day.atStartOfDay(TORONTO).toInstant(), to = day.plusDays(1).atStartOfDay(TORONTO).toInstant();
        return SolarCalculator.crossing(lat, lon, from, to, elevation, rising).orElseThrow();
    }

    /** Open-Meteo geocoding style: "Toronto" is ambiguous (three matches), unknown names return no results key. */
    public static Reply defaultGeocode(Map<String, String> q) {
        var root = new JsonObject(); root.addProperty("generationtime_ms", 0.1);
        if (q.get("name").equalsIgnoreCase("Toronto")) {
            var results = new JsonArray();
            results.add(place("Toronto", "Ontario", "Canada", 43.70643, -79.39864, "America/Toronto"));
            results.add(place("Toronto", "Ohio", "United States", 40.46423, -80.60091, "America/New_York"));
            results.add(place("Toronto", "Kansas", "United States", 37.79893, -95.94916, "America/Chicago"));
            root.add("results", results);
        } else if (q.get("name").equalsIgnoreCase("Billy Bishop Toronto City Airport")) {
            var results = new JsonArray(); results.add(place("Billy Bishop Toronto City Airport", "Ontario", "Canada", 43.62750, -79.39611, "America/Toronto"));
            root.add("results", results);
        }
        return new Reply(200, root.toString());
    }
    private static JsonObject place(String name, String admin, String country, double lat, double lon, String zone) {
        var item = new JsonObject(); item.addProperty("name", name); item.addProperty("latitude", lat); item.addProperty("longitude", lon);
        item.addProperty("timezone", zone); item.addProperty("admin1", admin); item.addProperty("country", country); return item;
    }

    @Override public void close() { server.stop(0); }
}
