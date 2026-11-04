package com.photopilot.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.environment.EnvironmentFixtures;
import java.time.*;
import java.util.*;

/**
 * Stage 2 F02 acceptance fixtures: a photographer in downtown Toronto with about four hours (from four hours before
 * sunset), a 40 km straight-line travel limit, interests in landscape/aviation/architecture/documentary and the
 * isolated F07 airshow inventory. Dates are relative to today so the fake providers stay inside the forecast horizon.
 */
public final class DiscoveryFixtures {
    private DiscoveryFixtures() { }
    public static final String PLACE = "Downtown Toronto";
    public static final String LAT = "43.65320", LON = "-79.38320";

    public static LocalDate day() { return EnvironmentFixtures.day(); }
    /** Four hours: from three hours before sunset until one hour after it. */
    public static ZonedDateTime from(LocalDate day) { return EnvironmentFixtures.sunset(day).minusHours(3).withSecond(0).withNano(0); }
    public static ZonedDateTime until(LocalDate day) { return from(day).plusHours(4); }

    /** Form values exactly as a user would review them in the GUI or CLI. */
    public static Map<String, String> form(LocalDate day) {
        var values = new LinkedHashMap<String, String>();
        values.put("location", PLACE); values.put("latitude", LAT); values.put("longitude", LON);
        values.put("date", day.toString()); values.put("from", EnvironmentFixtures.clock(from(day).toLocalTime()));
        values.put("until", EnvironmentFixtures.clock(until(day).toLocalTime())); values.put("duration", "");
        values.put("distance", "40"); values.put("interests", "landscape; aviation; architecture; documentary");
        values.put("avoid", "portraits"); values.put("portability", "one bag"); values.put("notes", ""); values.put("history", "yes");
        return values;
    }

    /**
     * Gemini-shaped reply with six candidates: two feasible (at the place; island airport within 40 km), one unverified
     * (unknown destination, with an invented distance in its rationale), and three infeasible (800 mm reach and an
     * avoided subject, a destination hundreds of km away, a time outside the availability).
     */
    public static String acceptanceReply(LocalDate day) {
        var start = from(day);
        var sunset = EnvironmentFixtures.sunset(day);
        var ideas = new JsonArray();
        ideas.add(idea("S1", "Skyline reflections along the waterfront", "architecture", "atLocation", null, null, start.plusMinutes(30), 90,
            "daylight", true, false, "Glass towers mirror the afternoon light near you.", needs(), "G1", "G6"));
        ideas.add(idea("S2", "Aircraft departures at the island airport", "aviation", "destination", "Billy Bishop Toronto City Airport",
            "Ontario, Canada", sunset.minusMinutes(50), 45, "goldenHour", true, false, "Turboprops climb out in warm low light.",
            needs(need("focalLength", 100.0, 400.0)), "G1", "G4"));
        ideas.add(idea("S3", "Distant bird portraits", "wildlife", "atLocation", null, null, start.plusMinutes(60), 60, "daylight", true, false,
            "Shorebirds feed along the breakwater.", needs(need("focalLength", null, 800.0))));
        ideas.add(idea("S4", "Small-town main street", "documentary", "destination", "Toronto", "Ohio, United States", start.plusMinutes(60), 90,
            "daylight", true, false, "A classic main street.", needs()));
        ideas.add(idea("S5", "Night skyline from a rooftop", "architecture", "atLocation", null, null, until(day).plusMinutes(30), 60, "night", true, false,
            "City lights after dark.", needs()));
        ideas.add(idea("S6", "Hidden laneway murals", "street", "destination", "Nowhere Placeville", "Ontario, Canada", start.plusMinutes(60), 60,
            "daylight", true, false, "It is only 15 km away and a 20-minute drive.", needs()));
        return reply(ideas, "S2", "S1", "S6", "S3", "S4", "S5");
    }

    public static String reply(JsonArray ideas, String... ranking) {
        var root = new JsonObject(); root.add("suggestions", ideas);
        var order = new JsonArray(); for (String id : ranking) order.add(id); root.add("ranking", order);
        var missing = new JsonArray(); missing.add("Whether you prefer quieter places."); root.add("missingContext", missing);
        var adjustments = new JsonArray(); adjustments.add("A later end time would allow night photography."); root.add("adjustments", adjustments);
        root.addProperty("summary", "Waterfront architecture and island-airport aviation fit your afternoon.");
        return root.toString();
    }
    public static JsonObject idea(String id, String subject, String genre, String placeType, String destination, String region, ZonedDateTime start,
            int minutes, String light, boolean outdoor, boolean clearSky, String rationale, JsonArray needs, String... uses) {
        var o = new JsonObject();
        o.addProperty("id", id); o.addProperty("subject", subject); o.addProperty("genre", genre);
        o.addProperty("setting", "Setting for " + subject.toLowerCase(Locale.ROOT) + ".");
        o.addProperty("placeType", placeType); o.addProperty("destination", destination); o.addProperty("region", region);
        o.addProperty("start", start.toOffsetDateTime().withSecond(0).withNano(0).toString()); o.addProperty("durationMinutes", minutes);
        o.addProperty("light", light); o.addProperty("outdoor", outdoor); o.addProperty("needsClearSky", clearSky); o.addProperty("dependsOnEvent", false);
        o.add("gearNeeds", needs);
        var used = new JsonArray(); for (String label : uses) used.add(label); o.add("usesGear", used);
        o.addProperty("gearNotes", uses.length == 0 ? "" : "Keep the kit light.");
        o.addProperty("rationale", rationale);
        var limitations = new JsonArray(); limitations.add("Check access before going."); o.add("limitations", limitations);
        return o;
    }
    public static JsonArray needs(JsonObject... needs) { var array = new JsonArray(); for (var need : needs) array.add(need); return array; }
    public static JsonObject need(String capability, Double min, Double max) {
        var o = new JsonObject(); o.addProperty("capability", capability); o.addProperty("minimum", min); o.addProperty("maximum", max); return o;
    }
}
