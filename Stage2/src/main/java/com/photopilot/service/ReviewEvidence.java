package com.photopilot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.ExifValues;
import com.photopilot.model.GearSnapshot;
import com.photopilot.model.ShootRequirement;
import com.photopilot.validation.CompatibilityValidator;

/**
 * F11 UC13 boundary between verified data and Gemini. {@link #json} builds the only evidence the model receives:
 * aggregate statistics with their denominators, the approved plan (gear names, settings, strategy, priorities,
 * schedule times), confirmed subject/goals and the deterministic comparison. Never sent: file names, paths, session
 * notes, location, coordinates, GPS or camera serial numbers (the adapter does not read them), identifiers or images.
 * {@link #check} then removes every returned statement that cites a figure absent from that evidence, claims to have
 * seen the photographs, judges image quality or artistic success, or gives a score.
 */
final class ReviewEvidence {
    static final String TASK = """
        You interpret verified post-shoot evidence for one photography session (PhotoPilot F11, UC13). The evidence was \
        calculated by the application from the photographer's EXIF metadata: aggregate statistics with the number of \
        photos behind each figure, the approved shoot plan, the confirmed subject and goals, deterministic \
        plan-versus-actual comparisons, evidence limits and, when present, related history of earlier sessions.
        Rules:
        1. Use only the supplied evidence. Never invent EXIF values, photographs, image content, shooting conditions, \
        weather or measurements. Every number you write must appear in the evidence (counts, percentages, focal \
        lengths, shutter speeds, apertures, ISO values, times). Do not compute new figures.
        2. You received metadata only, never images. Do not claim to have seen photographs. Do not describe sharpness, \
        blur, noise, focus accuracy, composition, subject content, or artistic success or failure.
        3. Never assign scores, ratings or grades. Do not claim a setting or focal length was objectively better, or \
        that high ISO necessarily produced poor images.
        4. EXIF cannot show whether equipment was carried, when lenses were changed or whether the whole schedule was \
        followed. State such limits, and say explicitly when missing metadata or small counts limit a conclusion.
        5. Compare recorded choices with the approved plan and the stated goals. Explain practical technical and \
        workflow implications as possibilities, not as facts about the results.
        6. relatedHistory (if present) describes earlier sessions' plans and, in savedReviews, their EXIF facts and the \
        advice saved then; use it only for comparison and never treat it as facts about this session. When you compare \
        with an earlier session, name it as earlier and cite figures only from its savedReviews facts, never from pastAdvice.
        7. Recommendations for future sessions may suggest settings or workflow changes; phrase them as suggestions.
        8. Do not change the plan or the statistics and never claim that anything was saved.
        Return summary (2-4 sentences) and at most 6 short items per list: technicalObservations, \
        equipmentObservations, exposureObservations, lessons, improvements, limitations.""";
    static final int MAX_ITEMS = 6, MAX_ITEM = 600, MAX_DISTRIBUTION = 15;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ReviewEvidence() { }

    static String json(ExifStatistics stats, ShootPlan plan, ShootRequirement requirement, List<LocalizedMessage> differences, List<LocalizedMessage> limits) {
        var root = new JsonObject();
        var goals = new JsonObject();
        goals.addProperty("subject", requirement.subject());
        goals.add("goals", strings(requirement.goals()));
        root.add("sessionGoals", goals);
        root.add("approvedPlan", plan(plan));
        root.add("statistics", statistics(stats));
        root.add("planComparison", strings(differences.stream().map(StatisticsCalculator::english).toList()));
        root.add("evidenceLimits", strings(limits.stream().map(StatisticsCalculator::english).toList()));
        return root.toString();
    }

    private static JsonObject plan(ShootPlan plan) {
        var o = new JsonObject();
        o.addProperty("revision", plan.revision());
        var gear = new JsonArray(); var names = new HashMap<UUID, String>();
        plan.loadout().ifPresent(loadout -> loadout.gear().forEach(item -> {
            names.put(item.sourceGearId(), CompatibilityValidator.name(item));
            var g = new JsonObject(); g.addProperty("type", item.type()); g.addProperty("name", CompatibilityValidator.name(item)); g.addProperty("quantity", item.selectedQuantity());
            spec(item, "minFocalMm").ifPresent(min -> spec(item, "maxFocalMm").ifPresent(max -> g.addProperty("focalRange", min + "-" + max + " mm")));
            gear.add(g);
        }));
        o.add("equipment", gear);
        var mounts = new JsonArray();
        plan.loadout().ifPresent(loadout -> new TreeMap<>(loadout.cameraLensAssignments()).forEach((camera, lens) -> {
            var m = new JsonObject(); m.addProperty("camera", names.getOrDefault(camera, "?")); m.addProperty("lens", names.getOrDefault(lens, "?")); mounts.add(m);
        }));
        o.add("mounts", mounts);
        var settings = new JsonArray();
        new TreeMap<>(plan.settings()).forEach((key, value) -> {
            var s = new JsonObject(); s.addProperty("setting", ShootPlan.settingName(key)); s.addProperty("value", value);
            s.addProperty("camera", ShootPlan.settingCamera(key).map(id -> names.getOrDefault(id, "?")).orElse("all")); settings.add(s);
        });
        o.add("settings", settings);
        var strategy = new JsonObject(); new TreeMap<>(plan.strategy()).forEach(strategy::addProperty);
        o.add("strategy", strategy);
        o.add("shotPriorities", strings(plan.shotPriorities()));
        var schedule = new JsonArray();
        plan.windows().forEach(w -> {
            var s = new JsonObject(); s.addProperty("start", MINUTE.format(w.start())); s.addProperty("end", MINUTE.format(w.end()));
            s.addProperty("zone", w.start().getZone().getId()); s.addProperty("conditions", w.conditions()); schedule.add(s);
        });
        o.add("schedule", schedule);
        return o;
    }

    private static JsonObject statistics(ExifStatistics stats) {
        var o = new JsonObject();
        o.addProperty("photosImported", stats.importedCount());
        o.addProperty("photosWithUsableMetadata", stats.usableCount());
        o.addProperty("filesThatFailed", stats.failedFileCount());
        o.add("cameras", equipment(stats, "camera", stats.cameraCounts()));
        o.add("lenses", equipment(stats, "lens", stats.lensCounts()));
        for (String field : ExifStatistics.NUMERIC) o.add(field, numeric(stats, field));
        var capture = new JsonObject();
        capture.addProperty("photosWithTimestamp", stats.timedCount());
        capture.addProperty("photosWithConfirmedUtcOffset", stats.zonedCount());
        stats.firstCapture().ifPresent(v -> capture.addProperty("firstCameraClockTime", v.replace('T', ' ')));
        stats.lastCapture().ifPresent(v -> capture.addProperty("lastCameraClockTime", v.replace('T', ' ')));
        stats.captureOffset().ifPresent(v -> capture.addProperty("utcOffset", v));
        o.add("captureTimes", capture);
        return o;
    }
    private static JsonObject equipment(ExifStatistics stats, String field, Map<String, Integer> counts) {
        var o = new JsonObject();
        int n = stats.count(field);
        o.addProperty("photosWithData", n); o.addProperty("photosMissing", stats.missing(field));
        var items = new JsonArray();
        new TreeMap<>(counts).forEach((name, photos) -> {
            var item = new JsonObject(); item.addProperty("name", name); item.addProperty("photos", photos); item.addProperty("percent", ExifValues.percent(photos, n)); items.add(item);
        });
        o.add("counts", items);
        return o;
    }
    private static JsonObject numeric(ExifStatistics stats, String field) {
        var o = new JsonObject();
        o.addProperty("photosWithData", stats.count(field)); o.addProperty("photosMissing", stats.missing(field)); o.addProperty("invalidValues", stats.invalid(field));
        stats.summary(field).ifPresent(s -> {
            o.addProperty("min", ExifValues.format(field, s.min())); o.addProperty("max", ExifValues.format(field, s.max()));
            o.addProperty("median", ExifValues.format(field, s.median()));
            if (!field.equals("shutter")) o.addProperty("mean", ExifValues.format(field, s.mean()));
            var histogram = stats.histogram(field);
            var top = histogram.entrySet().stream().sorted(Map.Entry.<Double, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_DISTRIBUTION).map(Map.Entry::getKey).sorted().toList();
            var items = new JsonArray();
            for (double value : top) {
                var item = new JsonObject(); item.addProperty("value", ExifValues.format(field, value)); item.addProperty("photos", histogram.get(value));
                item.addProperty("percent", ExifValues.percent(histogram.get(value), s.count())); items.add(item);
            }
            o.add("mostFrequentValues", items);
            if (histogram.size() > top.size()) o.addProperty("otherDistinctValues", histogram.size() - top.size());
        });
        return o;
    }
    private static Optional<String> spec(GearSnapshot item, String key) {
        String value = Objects.toString(item.specs().get(key), "").strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
    private static JsonArray strings(List<String> values) { var array = new JsonArray(); values.forEach(array::add); return array; }

    // ---------------------------------------------------------------- claim validation

    /** A model reply after deterministic checks: kept statements and the number removed. */
    record Checked(String summary, List<String> technical, List<String> equipment, List<String> exposure, List<String> lessons,
            List<String> improvements, List<String> limitations, int removed) { }

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    /** Image content, quality, viewing and artistic-outcome claims; only allowed nowhere in observations. */
    private static final Pattern OUTCOME = Pattern.compile("(?iu)\\b(sharp(?:er|est|ness)?|blurr?(?:y|ed|ing)?|motion blur|out of focus|in focus|"
        + "noisy|noise|grain(?:y)?|well[- ]composed|composition (?:was|is|were)|beautiful|stunning|artistic(?:ally)?|aesthetic(?:ally)?|"
        + "masterpiece|keepers?|keeper rate|successful (?:photos|photographs|images|shots)|failed (?:photos|photographs|images|shots))\\b"
        + "|清晰|模糊|锐利|噪点|构图|美感|成功的照片|失败的照片");
    /** Scores and claims of having looked at the images; forbidden in every section. */
    private static final Pattern FORBIDDEN = Pattern.compile("(?iu)\\b(score[sd]?|rating|rated|grade[sd]?|stars?)\\b|\\b\\d+(?:\\.\\d+)?\\s*/\\s*(?:5|10|100)\\b"
        + "|\\bout of (?:5|10|100|five|ten)\\b|\\b(?:i|we) (?:can )?(?:see|saw|viewed|looked at|notice[d]?)\\b|\\blooking at (?:the|your) (?:photos|photographs|images|pictures)\\b"
        + "|\\b(?:the|your) (?:photos|photographs|images|pictures|shots) (?:show|shows|look|looked|appear|appeared)\\b|评分|打分|分数|我看到|照片中可以看到|从照片中");

    /**
     * Observations may cite figures of this session's evidence and, for comparisons with earlier sessions (F11 "compare
     * with previous sessions"), the deterministic EXIF facts of the related history the agent added. Figures that appear
     * only in earlier AI advice (pastAdvice) or elsewhere in the history are still removed.
     */
    static Checked check(ReviewInterpretation reply, String evidenceJson, String historyJson) {
        var allowed = numbers(evidenceJson);
        allowed.addAll(numbers(historyFacts(historyJson)));
        int[] removed = {0};
        String summary = Objects.toString(reply.summary(), "").strip();
        var summaryKept = summary.isEmpty() ? List.<String>of() : keep(List.of(summary), allowed, true, removed, Integer.MAX_VALUE);
        return new Checked(summaryKept.isEmpty() ? "" : summaryKept.getFirst(),
            keep(reply.technicalObservations(), allowed, true, removed, MAX_ITEMS),
            keep(reply.equipmentObservations(), allowed, true, removed, MAX_ITEMS),
            keep(reply.exposureObservations(), allowed, true, removed, MAX_ITEMS),
            keep(reply.lessons(), allowed, false, removed, MAX_ITEMS),
            keep(reply.improvements(), allowed, false, removed, MAX_ITEMS),
            keep(reply.limitations(), allowed, false, removed, MAX_ITEMS), removed[0]);
    }

    /**
     * Statements are kept only if bounded and free of forbidden claims. Observations (claims about this session) must
     * also cite only figures present in the evidence; lessons and suggestions may propose values but never scores.
     */
    private static List<String> keep(List<String> items, List<Double> allowed, boolean observation, int[] removed, int limit) {
        if (items == null) return List.of();
        var kept = new ArrayList<String>();
        for (String raw : items) {
            String text = ExifRecord.clean(raw);
            if (text.isEmpty()) continue;
            boolean ok = text.length() <= (limit == Integer.MAX_VALUE ? MAX_ITEM * 2 : MAX_ITEM) && !FORBIDDEN.matcher(text).find()
                && (!observation || !OUTCOME.matcher(text).find() && cited(text, allowed));
            if (ok && kept.size() < limit) kept.add(text); else removed[0]++;
        }
        return kept;
    }
    /** Every number in the statement equals a number of the evidence, allowing the statement's own rounding. */
    private static boolean cited(String text, List<Double> allowed) {
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String token = m.group().replace(',', '.');
            double value = Double.parseDouble(token);
            int decimals = token.contains(".") ? token.length() - token.indexOf('.') - 1 : 0;
            boolean found = allowed.stream().anyMatch(v -> BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue() == value);
            if (!found) return false;
        }
        return true;
    }
    /** Text of every relatedHistory[].savedReviews[].facts object; "" when there is none or the JSON is unreadable. */
    static String historyFacts(String historyJson) {
        if (historyJson == null || historyJson.isBlank()) return "";
        try {
            var facts = new StringBuilder();
            var related = JsonParser.parseString(historyJson).getAsJsonObject().getAsJsonArray("relatedHistory");
            if (related == null) return "";
            for (var session : related) {
                var reviews = session.getAsJsonObject().getAsJsonArray("savedReviews");
                if (reviews == null) continue;
                for (var review : reviews) { var item = review.getAsJsonObject().get("facts"); if (item != null) facts.append(item).append('\n'); }
            }
            return facts.toString();
        } catch (RuntimeException unreadable) { return ""; }
    }
    private static List<Double> numbers(String evidence) {
        var values = new ArrayList<Double>();
        Matcher m = NUMBER.matcher(evidence);
        while (m.find()) values.add(Double.parseDouble(m.group().replace(',', '.')));
        return values;
    }
}
