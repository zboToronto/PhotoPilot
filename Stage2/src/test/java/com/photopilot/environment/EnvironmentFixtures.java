package com.photopilot.environment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.model.ShootRequirement;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Stage 2 F09 acceptance scenario fixtures (Toronto-area airshow), relative to the real current date so the fake
 * providers stay inside their forecast horizon: shooting date = today in Toronto + 3 days, availability from four
 * hours before sunset until 90 minutes after it.
 */
public final class EnvironmentFixtures {
    private EnvironmentFixtures() { }
    public static final double LAT = 43.6275, LON = -79.3961;
    public static final String PLACE = "Billy Bishop Toronto City Airport";

    public static LocalDate day() { return LocalDate.now(FakeEnvironmentProviders.TORONTO).plusDays(3); }
    public static ZonedDateTime sunset(LocalDate day) { return FakeEnvironmentProviders.event(LAT, LON, day, -0.833, false).atZone(FakeEnvironmentProviders.TORONTO); }
    public static LocalTime from(LocalDate day) { return sunset(day).minusHours(4).toLocalTime().truncatedTo(ChronoUnit.HOURS); }
    public static LocalTime until(LocalDate day) { return sunset(day).plusMinutes(90).toLocalTime().truncatedTo(ChronoUnit.MINUTES); }
    public static String clock(LocalTime time) { return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute()); }

    public static ShootRequirement airshowRequirement(LocalDate day) {
        return new ShootRequirement("Airshow afternoon and evening", "Aviation / Airshow photography", List.of("Photograph aircraft with suitable natural lighting"),
            Optional.empty(), Optional.of(day), "afternoon and evening", Optional.empty(), Optional.of(Duration.ofHours(3)), Optional.of(2), Optional.of(false),
            Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), true);
    }

    /** Gemini-shaped reply: a daylight and a golden-hour window (both valid) plus one window outside the availability. */
    public static String airshowInterpretation(LocalDate day) {
        var set = sunset(day);
        ZonedDateTime start = ZonedDateTime.of(day, from(day), FakeEnvironmentProviders.TORONTO);
        String firstHour = "h-" + start.plusHours(1).toLocalDateTime();
        var windows = new JsonArray();
        windows.add(window("Afternoon flying display", start.plusMinutes(30), start.plusMinutes(120), "daylight",
            "Partly cloudy with cloud cover 40% and wind 12.5 km/h from the west.", firstHour, "sunset"));
        windows.add(window("Golden-hour flybys", set.minusMinutes(50), set.minusMinutes(5), "goldenHour",
            "Low, warm side light before sunset.", "goldenEvening", "sunset"));
        windows.add(window("After-dark static display", set.plusMinutes(120), set.plusMinutes(180), "night", "Dark sky.", "civilDusk"));
        var root = new JsonObject(); root.add("windows", windows);
        var considerations = new JsonArray(); considerations.add("Wind 12.5 km/h from the west: aircraft may approach into the wind.");
        root.add("considerations", considerations);
        var alternatives = new JsonArray(); alternatives.add("If cloud thickens, favour the afternoon display for sharper silhouettes.");
        root.add("alternatives", alternatives);
        root.addProperty("summary", "Afternoon daylight and the golden hour before sunset both suit aircraft photography.");
        return root.toString();
    }
    static JsonObject window(String label, ZonedDateTime start, ZonedDateTime end, String light, String conditions, String... evidence) {
        var w = new JsonObject(); w.addProperty("label", label);
        w.addProperty("start", start.toOffsetDateTime().withSecond(0).withNano(0).toString());
        w.addProperty("end", end.toOffsetDateTime().withSecond(0).withNano(0).toString());
        w.addProperty("light", light); w.addProperty("conditions", conditions); w.addProperty("rationale", "Matches the aviation goals.");
        var ids = new JsonArray(); for (String id : evidence) ids.add(id); w.add("evidence", ids); return w;
    }
    /** Form values exactly as a user would review them in the GUI or CLI. */
    public static Map<String, String> form(LocalDate day) {
        var values = new LinkedHashMap<String, String>();
        values.put("location", PLACE); values.put("latitude", "43.62750"); values.put("longitude", "-79.39610");
        values.put("date", day.toString()); values.put("from", clock(from(day))); values.put("until", clock(until(day))); values.put("lead", "30");
        return values;
    }
}
