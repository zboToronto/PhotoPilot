package com.photopilot.agent;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.photopilot.common.Result;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.GeoLocation;
import com.photopilot.service.EnvironmentService;

/**
 * Stage 1 UML element 96 (zero-based): {AgentTool; invokes retrieve(), not assess()}
 * Bounded JSON adapter for the future F10 tool loop: factual provider evidence only, with sources, timestamps and
 * unavailable fields. No model call and no AI interpretation.
 */
public class EnvironmentTool implements AgentTool {
    private EnvironmentService environment;

    public EnvironmentTool(EnvironmentService environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public String name() {
        return "environment";
    }

    public String inputSchema() {
        return """
            {"type":"object","additionalProperties":false,"required":["latitude","longitude","date"],
             "properties":{"latitude":{"type":"number","minimum":-90,"maximum":90},"longitude":{"type":"number","minimum":-180,"maximum":180},
             "date":{"type":"string","description":"Local shooting date YYYY-MM-DD"},
             "nextDay":{"type":"boolean","description":"Also retrieve the following day (availability past midnight)."}}}
            """;
    }

    public Result<String> execute(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.length() > 2048) return Result.failure("Environment JSON is required and limited to 2048 characters.");
        Double lat = null, lon = null; String date = null; boolean nextDay = false;
        try (JsonReader reader = new JsonReader(new StringReader(argumentsJson))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            var keys = new HashSet<String>();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!keys.add(key)) throw new IllegalArgumentException("Duplicate key: " + key);
                switch (key) {
                    case "latitude" -> { require(reader, JsonToken.NUMBER); lat = reader.nextDouble(); }
                    case "longitude" -> { require(reader, JsonToken.NUMBER); lon = reader.nextDouble(); }
                    case "date" -> { require(reader, JsonToken.STRING); date = reader.nextString(); }
                    case "nextDay" -> { require(reader, JsonToken.BOOLEAN); nextDay = reader.nextBoolean(); }
                    default -> throw new IllegalArgumentException("Unsupported JSON key: " + key);
                }
            }
            reader.endObject(); require(reader, JsonToken.END_DOCUMENT);
            if (lat == null || lon == null || date == null) throw new IllegalArgumentException("latitude, longitude and date are required.");
            var day = LocalDate.parse(date);
            var place = new GeoLocation("Requested coordinates", lat, lon, false, false, Optional.empty());
            if (!place.validate().isSuccess()) throw new IllegalArgumentException("Coordinates are out of range.");
            var result = environment.retrieve(place, day, nextDay ? day.plusDays(1) : day);
            if (!result.isSuccess()) return Result.failure(String.join("; ", result.errors()));
            return Result.success(json(result.value().orElseThrow(), environment.staleFields(result.value().orElseThrow())));
        } catch (IOException | RuntimeException invalid) {
            return Result.failure("Invalid environment request: " + invalid.getMessage());
        }
    }

    private static String json(EnvironmentContext context, Set<String> stale) {
        var zone = context.zone().orElse(ZoneOffset.UTC);
        var root = new JsonObject();
        root.addProperty("timeZone", context.zone().map(Object::toString).orElse("unverified (times in UTC)"));
        root.addProperty("retrievedAt", context.retrievedAt().toString());
        var daily = new JsonObject(); context.weather().forEach(daily::addProperty); root.add("daily", daily);
        var hours = new JsonArray();
        for (var hour : context.hourly()) {
            var item = new JsonObject(); item.addProperty("time", hour.time().atZone(zone).toOffsetDateTime().toString());
            hour.values().forEach(item::addProperty); hours.add(item);
        }
        root.add("hourly", hours);
        var sun = new JsonArray();
        for (var event : context.sunEvents()) {
            var item = new JsonObject(); item.addProperty("date", event.date().toString()); item.addProperty("kind", event.kind());
            item.addProperty("time", event.time().atZone(zone).toOffsetDateTime().toString()); item.addProperty("source", event.source()); sun.add(item);
        }
        root.add("sunEvents", sun);
        var units = new JsonObject(); EnvironmentContext.UNITS.forEach(units::addProperty); root.add("units", units);
        var sources = new JsonObject(); context.sources().forEach((key, uri) -> sources.addProperty(key, uri.toString())); root.add("sources", sources);
        var unavailable = new JsonArray(); context.unavailableFields().forEach(unavailable::add); root.add("unavailable", unavailable);
        var staleItems = new JsonArray(); stale.forEach(staleItems::add); root.add("stale", staleItems);
        return root.toString();
    }
    private static void require(JsonReader reader, JsonToken token) throws IOException {
        if (reader.peek() != token) throw new IllegalArgumentException("Expected JSON " + token);
    }
}
