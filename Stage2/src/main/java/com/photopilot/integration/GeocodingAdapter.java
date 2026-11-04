package com.photopilot.integration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.common.Result;
import com.photopilot.model.PlaceCandidate;

/**
 * F09 Adapter over the free, key-less Open-Meteo geocoding API (https://open-meteo.com/en/docs/geocoding-api).
 * Returns up to {@link #MAX_RESULTS} gazetteer matches with their time zones; the caller lets the user choose.
 */
public class GeocodingAdapter implements PlaceLookupProvider {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://geocoding-api.open-meteo.com/v1/search");
    public static final int MAX_RESULTS = 5;
    private final URI endpoint;
    private final HttpClient http;
    private final Duration timeout;

    public GeocodingAdapter(URI endpoint, HttpClient http, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.http = Objects.requireNonNull(http, "http");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public Result<List<PlaceCandidate>> search(String name) {
        if (name == null || name.isBlank()) return Result.failureKey("env.location.error.label");
        String host = Objects.toString(endpoint.getHost(), "geocoding");
        URI uri = URI.create(endpoint.toString().replaceAll("\\?.*$", "") + "?name=" + URLEncoder.encode(name.strip(), StandardCharsets.UTF_8)
            + "&count=" + MAX_RESULTS + "&language=en&format=json");
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
        if (response.statusCode() == 429) return Result.failureKey("env.error.rateLimited", host);
        if (response.statusCode() >= 500) return Result.failureKey("env.error.unavailable", host, String.valueOf(response.statusCode()));
        if (response.statusCode() != 200) return Result.failureKey("env.error.provider", host, String.valueOf(response.statusCode()), "");
        try {
            JsonObject root = JsonParser.parseString(Objects.toString(response.body(), "")).getAsJsonObject();
            var found = new ArrayList<PlaceCandidate>();
            if (root.has("results") && root.get("results").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("results")) {
                    JsonObject item = element.getAsJsonObject();
                    double lat = item.get("latitude").getAsDouble(), lon = item.get("longitude").getAsDouble();
                    if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
                    Optional<ZoneId> zone = Optional.empty();
                    try { if (item.has("timezone")) zone = Optional.of(ZoneId.of(item.get("timezone").getAsString())); } catch (DateTimeException ignored) { }
                    found.add(new PlaceCandidate(text(item, "name"), text(item, "admin1"), text(item, "country"), lat, lon, zone));
                    if (found.size() == MAX_RESULTS) break;
                }
            }
            return found.isEmpty() ? Result.failureKey("env.location.error.notFound", name.strip()) : Result.success(List.copyOf(found));
        } catch (RuntimeException malformed) {
            return Result.failureKey("env.error.format", host);
        }
    }
    private static String text(JsonObject item, String key) {
        return item.has(key) && item.get(key).isJsonPrimitive() ? item.get(key).getAsString() : "";
    }
}
