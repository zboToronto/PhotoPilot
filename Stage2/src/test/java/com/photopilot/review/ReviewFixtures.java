package com.photopilot.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.command.ShootPlan;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.PlanningService;
import com.photopilot.state.SessionEvent;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.photopilot.planning.PlanFixtures.ok;

/**
 * F11 acceptance fixtures: the F10 airshow session approved through the real planner (A1 + FE 200-600mm G, A7 IV +
 * FE 100-400mm GM; 1/1600-1/3200 s, f/6.3-f/8 on the A1, ISO 100-3200) and then marked completed, plus synthetic
 * airshow JPEGs timestamped inside the plan's first window. Gemini replies are built from the evidence actually sent.
 */
public final class ReviewFixtures {
    private ReviewFixtures() { }
    private static final DateTimeFormatter EXIF = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    public static UUID completed(ApplicationBootstrap app, FakeGemini gemini, PlanFixtures.Gear gear) {
        var id = PlanFixtures.session(app, gear, true);
        PlanFixtures.assess(app, gemini, id);
        gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true));
        var preview = ok(app.facade().planning().generatePreview(id));
        ok(app.facade().planning().approve(id, preview.plan()));
        ok(app.facade().sessions().transition(id, SessionEvent.SHOOT_COMPLETED));
        return id;
    }
    public static ShootPlan plan(ApplicationBootstrap app, UUID id) {
        return PlanningService.latest(ok(app.facade().sessions().open(id)).plans()).orElseThrow();
    }

    /**
     * Seven files: three A1 + planned 200-600 mm frames, one A1 frame without lens metadata, two A7 IV frames recorded
     * as "SONY ILCE-7M4" with the 100-400 mm, and one JPEG whose metadata was stripped.
     */
    public static List<Path> photos(Path dir, ShootPlan plan) throws Exception {
        ZonedDateTime start = plan.windows().getFirst().start();
        var files = new ArrayList<Path>();
        files.add(ExifFixtures.jpeg(dir, "air-01.jpg", at(ExifFixtures.airshow(400, 2500, 800, null), start.plusMinutes(5))));
        files.add(ExifFixtures.jpeg(dir, "air-02.jpg", at(ExifFixtures.airshow(600, 3200, 1600, null), start.plusMinutes(9))));
        files.add(ExifFixtures.jpeg(dir, "air-03.jpg", at(ExifFixtures.airshow(500, 2000, 1250, null), start.plusMinutes(14))));
        files.add(ExifFixtures.jpeg(dir, "air-04.jpg", at(ExifFixtures.airshow(300, 1000, 400, null).withLens(null, null), start.plusMinutes(20))));
        files.add(ExifFixtures.jpeg(dir, "air-05.jpg", at(ExifFixtures.airshow(180, 1600, 640, null).withCamera("SONY", "ILCE-7M4").withLens("Sony", "FE 100-400mm GM"), start.plusMinutes(31))));
        files.add(ExifFixtures.jpeg(dir, "air-06.jpg", at(ExifFixtures.airshow(100, 800, 320, null).withCamera("SONY", "ILCE-7M4").withLens("Sony", "FE 100-400mm GM"), start.plusMinutes(40))));
        files.add(ExifFixtures.plainJpeg(dir, "air-07-exported.jpg"));
        return files;
    }
    private static ExifFixtures.Photo at(ExifFixtures.Photo photo, ZonedDateTime time) {
        return photo.withTime(EXIF.format(time), time.getOffset().getId());
    }

    /** A grounded review built only from strings and figures of the evidence; optionally with unsupported claims. */
    public static String review(String request, boolean unsupported) {
        var evidence = PlanFixtures.evidence(request);
        var stats = evidence.getAsJsonObject("statistics");
        var comparison = evidence.getAsJsonArray("planComparison");
        var result = new JsonObject();
        result.addProperty("summary", "Of the " + stats.get("photosImported").getAsInt() + " photos imported, "
            + stats.get("photosWithUsableMetadata").getAsInt() + " had usable metadata; recorded settings are compared with approved plan revision "
            + evidence.getAsJsonObject("approvedPlan").get("revision").getAsInt() + ".");
        result.add("technicalObservations", strings(line(comparison, "Shutter speed:"), unsupported ? "The photos look sharp and well exposed." : null));
        result.add("equipmentObservations", strings(line(comparison, "Planned camera Sony A1"), unsupported ? "The A1 was used for 97% of all photos." : null));
        result.add("exposureObservations", strings("Median ISO was " + stats.getAsJsonObject("iso").get("median").getAsString() + "."));
        result.add("lessons", strings("Keeping a fast shutter-speed plan suited the goal of fast-moving aircraft; check it against these counts next time.",
            unsupported ? "Overall score: 8/10." : null));
        result.add("improvements", strings("Make sure every body records lens metadata so lens use can be compared."));
        result.add("limitations", strings("Only metadata was available; image content was not reviewed."));
        var root = new JsonObject(); root.addProperty("step", "final"); root.add("result", result);
        return root.toString();
    }
    /** A reply in which every observation cites invented figures or claims image quality. */
    public static String unsupportedOnly() {
        var result = new JsonObject();
        result.addProperty("summary", "I can see that 93% of the photos are sharp.");
        result.add("technicalObservations", strings("Motion blur was avoided in 41 frames."));
        result.add("equipmentObservations", strings("The photos show a well-composed sky."));
        result.add("exposureObservations", strings("Exposure scored 9/10."));
        for (String key : List.of("lessons", "improvements", "limitations")) result.add(key, new JsonArray());
        var root = new JsonObject(); root.addProperty("step", "final"); root.add("result", result);
        return root.toString();
    }
    private static String line(JsonArray lines, String prefix) {
        for (var line : lines) if (line.getAsString().startsWith(prefix)) return line.getAsString();
        throw new AssertionError("no comparison line " + prefix + " in " + lines);
    }
    private static JsonArray strings(String... values) {
        var array = new JsonArray(); for (String v : values) if (v != null) array.add(v); return array;
    }
}
