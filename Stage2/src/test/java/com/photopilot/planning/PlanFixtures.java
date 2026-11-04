package com.photopilot.planning;

import com.google.gson.*;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated F10 acceptance fixtures (Toronto-area airshow, three days ahead so provider fakes stay in range): two Sony
 * bodies, telephoto choices including a lighter 70-300 mm, one incompatible and one unverified lens. Gemini replies are
 * built from the labels in the actual request evidence, never from inventory identifiers.
 */
public final class PlanFixtures {
    private PlanFixtures() { }
    public static final String LIGHTER = "FE 70-300mm G", HEAVY_ZOOM = "FE 100-400mm GM", TELE = "FE 200-600mm G";

    /** Airshow inventory plus a lighter telephoto zoom that still reaches 70-300 mm. */
    public static final class Gear {
        public final GearFixtures.Airshow airshow = new GearFixtures.Airshow();
        public final Lens lighter = GearFixtures.lens("Sony", LIGHTER, "Sony E", 70, 300, 4.5, 72.0, 854.0, true);
        public List<Equipment> items() { var all = new ArrayList<>(airshow.items()); all.add(lighter); return all; }
    }

    public static LocalDate day() { return EnvironmentFixtures.day(); }
    /** Confirmed: fast aircraft, about three hours, two bodies, no lens changes, telephoto and portability matter. */
    public static ShootRequirement requirement(LocalDate day) {
        return new ShootRequirement("Airshow: two bodies, minimal lens changes, telephoto, portable", "Fast-moving aircraft at an airshow",
            List.of("Sharp aircraft in flight", "Close-up passes"), Optional.empty(), Optional.of(day), "afternoon to sunset", Optional.empty(),
            Optional.of(Duration.ofHours(3)), Optional.of(2), Optional.of(false), Optional.empty(), "keep the kit portable", List.of(),
            Map.of("telephoto reach", 1, "portability", 2), Map.of(), List.of(), true);
    }
    public static EquipmentRequirementProfile profile() { return GearFixtures.airshowProfile(); }

    public static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    /** Session with confirmed F03 requirements, an optional F06 profile and the fixture inventory (never real user data). */
    public static UUID session(ApplicationBootstrap app, Gear gear, boolean profile) {
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var id = ok(app.facade().sessions().create("Toronto airshow plan", day())).id();
        ok(app.facade().planning().confirm(id, requirement(day()).asDraft()));
        if (profile) ok(app.facade().planning().confirmProfile(id, profile().asDraft()));
        return id;
    }
    /** F09 assessment through the real service with the provider fakes; it is remembered for F10 in this process. */
    public static EnvironmentAssessment assess(ApplicationBootstrap app, com.photopilot.requirement.FakeGemini gemini, UUID id) {
        var day = day();
        var place = new GeoLocation(EnvironmentFixtures.PLACE, EnvironmentFixtures.LAT, EnvironmentFixtures.LON, false, false, Optional.empty());
        var request = new EnvironmentRequest(place, day, EnvironmentFixtures.from(day), EnvironmentFixtures.until(day), Optional.of(Duration.ofMinutes(30)),
            Optional.of(FakeEnvironmentProviders.TORONTO), "", List.of());
        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        return ok(app.facade().assessEnvironment(id, request));
    }

    /** The labelled evidence object the application sent in a Gemini request body. */
    public static JsonObject evidence(String requestBody) {
        var parts = JsonParser.parseString(requestBody).getAsJsonObject().getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts");
        for (var part : parts) {
            String text = part.getAsJsonObject().get("text").getAsString();
            if (text.startsWith("Application evidence")) return JsonParser.parseString(text.substring(text.indexOf('\n') + 1)).getAsJsonObject();
        }
        throw new AssertionError("no evidence in request");
    }
    /** Label of the offered candidate whose lenses are exactly the given models. */
    public static String candidate(JsonObject evidence, String... lenses) {
        var wanted = new TreeSet<>(List.of(lenses));
        for (var element : evidence.getAsJsonArray("candidates")) {
            var candidate = element.getAsJsonObject(); var found = new TreeSet<String>();
            for (var g : candidate.getAsJsonArray("gear")) if (g.getAsJsonObject().get("type").getAsString().equals("LENS"))
                found.add(g.getAsJsonObject().get("name").getAsString().replaceFirst("^Sony ", ""));
            if (found.equals(wanted)) return candidate.get("label").getAsString();
        }
        throw new AssertionError("no candidate with " + wanted + " in " + evidence.getAsJsonArray("candidates"));
    }
    public static String gear(JsonObject evidence, String model) {
        for (var element : evidence.getAsJsonArray("candidates"))
            for (var g : element.getAsJsonObject().getAsJsonArray("gear"))
                if (g.getAsJsonObject().get("name").getAsString().endsWith(model)) return g.getAsJsonObject().get("label").getAsString();
        throw new AssertionError("no gear " + model);
    }

    /** First reply: request one depth-of-field calculation through the tool envelope. */
    public static String depthOfFieldRequest() {
        var arguments = "{\"operation\":\"DEPTH_OF_FIELD\",\"parameters\":{\"focalLength\":600,\"aperture\":8,\"coc\":0.03,\"distance\":300},"
            + "\"units\":{\"focalLength\":\"mm\",\"aperture\":\"f-number\",\"coc\":\"mm\",\"distance\":\"m\"}}";
        var tool = new JsonObject(); tool.addProperty("name", "calculation"); tool.addProperty("arguments", arguments);
        var root = new JsonObject(); root.addProperty("step", "tool"); root.add("tool", tool);
        return root.toString();
    }
    static JsonObject mount(String camera, String lens, String role) { var m = new JsonObject(); m.addProperty("camera", camera); m.addProperty("lens", lens); m.addProperty("role", role); return m; }
    static JsonObject setting(String name, String value, String camera) { var s = new JsonObject(); s.addProperty("setting", name); s.addProperty("value", value); s.addProperty("camera", camera); return s; }
    static JsonArray strings(String... values) { var array = new JsonArray(); for (String v : values) array.add(v); return array; }
    static JsonObject strategy() {
        var s = new JsonObject();
        s.addProperty("approach", "Track aircraft along the display line from the crowd line.");
        s.addProperty("technical", "Keep shutter speeds fast for jets and slower only for propeller blur.");
        s.addProperty("autofocus", "Continuous AF with subject tracking for aircraft.");
        s.addProperty("exposure", "Expose for the aircraft against bright sky; check highlights.");
        s.addProperty("composition", "Leave space ahead of the aircraft in the direction of travel.");
        return s;
    }
    /** Two windows inside the F09 availability, citing evidence ids from the request (a daylight and a golden-hour window). */
    static JsonArray schedule(JsonObject evidence) {
        var day = day(); var set = EnvironmentFixtures.sunset(day);
        ZonedDateTime start = ZonedDateTime.of(day, EnvironmentFixtures.from(day), FakeEnvironmentProviders.TORONTO);
        var windows = new JsonArray();
        windows.add(window("Afternoon flying display", start.plusMinutes(30), start.plusMinutes(120), start.plusMinutes(15), "daylight",
            "Main flying display.", "h-" + start.plusHours(1).toLocalDateTime(), "sunset"));
        windows.add(window("Golden-hour flybys", set.minusMinutes(50), set.minusMinutes(5), set.minusMinutes(50), "goldenHour",
            "Warm side light on the flybys.", "goldenEvening", "sunset"));
        return windows;
    }
    static JsonObject window(String label, ZonedDateTime start, ZonedDateTime end, ZonedDateTime arrival, String light, String conditions, String... evidence) {
        var w = new JsonObject(); w.addProperty("label", label);
        w.addProperty("start", start.toLocalDateTime().withSecond(0).withNano(0).toString()); w.addProperty("end", end.toLocalDateTime().withSecond(0).withNano(0).toString());
        w.addProperty("arrival", arrival.toLocalDateTime().withSecond(0).withNano(0).toString()); w.addProperty("light", light); w.addProperty("conditions", conditions);
        w.add("evidence", strings(evidence)); return w;
    }
    static JsonArray priorities() {
        var array = new JsonArray();
        for (String[] p : List.of(new String[]{"primary", "Sharp fast jets in flight", "Fast shutter costs ISO in low light."},
                new String[]{"primary", "Close-up passes at long focal length", "Long lens is heavier to track."},
                new String[]{"secondary", "Golden-hour flybys", "Light fades quickly."})) {
            var o = new JsonObject(); o.addProperty("priority", p[0]); o.addProperty("description", p[1]); o.addProperty("tradeOff", p[2]); array.add(o);
        }
        return array;
    }

    /** Final generation reply for the request whose body is {@code request}: chosen lenses, mounts, settings, schedule. */
    public static String plan(String request, String bodyALens, String bodyBLens, boolean withSchedule) {
        var evidence = evidence(request);
        var result = new JsonObject();
        result.addProperty("summary", "Two bodies with telephoto coverage and no lens changes.");
        result.addProperty("loadout", candidate(evidence, bodyALens, bodyBLens));
        var mounts = new JsonArray();
        mounts.add(mount(gear(evidence, "A1"), gear(evidence, bodyALens), "long telephoto for distant passes"));
        mounts.add(mount(gear(evidence, "A7 IV"), gear(evidence, bodyBLens), "shorter zoom for close passes"));
        result.add("mounts", mounts);
        result.add("equipmentNotes", strings("Both bodies keep a lens mounted for the whole show."));
        var settings = new JsonArray();
        settings.add(setting("shutterSpeed", "1/1600-1/3200 s", "all"));
        settings.add(setting("aperture", "f/6.3-f/8", gear(evidence, "A1")));
        settings.add(setting("iso", "100-3200", "all"));
        settings.add(setting("autofocusMode", "AF-C with subject tracking", "all"));
        result.add("settings", settings);
        result.add("strategy", strategy());
        result.add("schedule", withSchedule ? schedule(evidence) : new JsonArray());
        result.add("preparation", strings("Charge all batteries the evening before.", "Format the cards in camera."));
        result.add("checklist", strings("Hearing protection", "Water and sunscreen", "Spare lens cloth"));
        result.add("shotPriorities", priorities());
        result.add("limitations", strings("Display times depend on the organizer schedule."));
        result.add("questions", new JsonArray());
        var root = new JsonObject(); root.addProperty("step", "final"); root.add("result", result);
        return root.toString();
    }

    /** UC11 reply that swaps to the candidate with the given lenses and changes only the loadout. */
    public static String lighter(String request, String bodyALens, String bodyBLens) {
        var evidence = evidence(request);
        var result = change("change", "Use the lighter 70-300 mm zoom; bodies, mounts and everything else stay the same.");
        result.add("changes", strings("loadout"));
        result.addProperty("loadout", candidate(evidence, bodyALens, bodyBLens));
        var mounts = new JsonArray();
        mounts.add(mount(gear(evidence, "A1"), gear(evidence, bodyALens), "long telephoto"));
        mounts.add(mount(gear(evidence, "A7 IV"), gear(evidence, bodyBLens), "lighter close-pass zoom"));
        result.add("mounts", mounts);
        result.add("equipmentNotes", strings("The lighter zoom saves weight while both bodies keep a lens mounted."));
        return finalEnvelope(result);
    }
    public static String conflict() {
        var result = change("conflict", "One body cannot keep both focal ranges mounted.");
        result.addProperty("conflict", "The confirmed requirement fixes two camera bodies and no lens changes.");
        result.add("changes", new JsonArray());
        return finalEnvelope(result);
    }
    public static String clarify() {
        var result = change("clarify", "The request is unclear.");
        result.add("questions", strings("Should the schedule move later, or should the equipment change?"));
        result.add("changes", new JsonArray());
        return finalEnvelope(result);
    }
    private static JsonObject change(String status, String summary) {
        var result = new JsonObject(); result.addProperty("status", status); result.addProperty("summary", summary);
        result.add("questions", new JsonArray()); result.addProperty("conflict", ""); return result;
    }
    private static String finalEnvelope(JsonObject result) {
        var root = new JsonObject(); root.addProperty("step", "final"); root.add("result", result); return root.toString();
    }
}
