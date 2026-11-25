package com.photopilot.service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.ExifValues;
import com.photopilot.model.GearSnapshot;
import com.photopilot.model.ShootRequirement;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.PlanValidator;
import com.photopilot.service.ReviewClaims.Source;

/**
 * F11 UC13 boundary between verified data and Gemini. {@link #prepare} builds the only evidence the model receives:
 * aggregate statistics with their denominators, the approved plan (gear names, settings, strategy, priorities,
 * schedule times), confirmed subject/goals and the deterministic comparison. Never sent: file names, paths, session
 * notes, location, coordinates, GPS or camera serial numbers (the adapter does not read them), identifiers or images.
 * Every evidence record carries an application id (S = this session's EXIF statistics, P = the approved plan) naming the
 * typed facts it holds ({@link ReviewClaims}). {@link #check} then removes every returned statement with a figure that no
 * fact of the same metric, statistic, source and subject supports, that claims to have seen the photographs, judges
 * image quality or artistic success, or gives a score, and (Stage2OptRe1 PP-03) every statement whose equipment identity
 * claim the recorded and planned names do not support ({@link EquipmentClaims}): one camera model written two ways is
 * never "different equipment", and no model name identifies an individual body.
 */
final class ReviewEvidence {
    static final String TASK = """
        You interpret verified post-shoot evidence for one photography session (PhotoPilot F11, UC13). The evidence was \
        calculated by the application from the photographer's EXIF metadata: aggregate statistics with the number of \
        photos behind each figure, the approved shoot plan, the confirmed subject and goals, deterministic \
        plan-versus-actual comparisons, evidence limits and, when present, related history of earlier sessions.
        Rules:
        1. Use only the supplied evidence. Never invent EXIF values, photographs, image content, shooting conditions, \
        weather or measurements, and do not compute new figures. Every figure you write (counts, percentages, focal \
        lengths, shutter speeds, apertures, ISO values, exposure compensation, times) must be a value of the evidence, \
        written with its unit as shown there, and must describe exactly what that value describes: the same setting, the \
        same statistic (minimum, maximum, median, mean, one recorded value or the most frequent one) and the same source. \
        Put the id of the evidence record a figure comes from in square brackets right after it, for example \
        "median ISO 720 [S6]". Introduce figures of the approved plan as planned; never present a planned value as used \
        or recorded, or a recorded value as planned. Write equipment names exactly as listed.
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
        with an earlier session, say in the same clause that a figure is from the earlier session, cite figures only from \
        its savedReviews facts, never from pastAdvice, and give them no id.
        7. Recommendations for future sessions may suggest settings or workflow changes; phrase them as suggestions.
        8. Do not change the plan or the statistics and never claim that anything was saved.
        9. equipmentIdentity states, for each recorded and planned camera or lens name, whether the application \
        established the same model, a different model or no determination. Names listed as the same model are one model \
        written two ways: never describe them as different equipment. Do not claim a different or the same model where \
        it says not determined, and never claim which individual camera body or lens was used.
        Return summary (2-4 sentences) and at most 6 short items per list: technicalObservations, \
        equipmentObservations, exposureObservations, lessons, improvements, limitations.""";
    static final int MAX_ITEMS = 6, MAX_ITEM = 600, MAX_DISTRIBUTION = 15;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ReviewEvidence() { }

    /** The evidence JSON sent to the model and the typed facts its record ids name, built together so they cannot disagree. */
    record Prepared(String json, ReviewClaims.Evidence claims) { }

    static Prepared prepare(ExifStatistics stats, ShootPlan plan, ShootRequirement requirement, List<LocalizedMessage> differences, List<LocalizedMessage> limits) {
        var facts = new ReviewClaims.Builder();
        var root = new JsonObject();
        var goals = new JsonObject();
        goals.addProperty("subject", requirement.subject());
        goals.add("goals", strings(requirement.goals()));
        root.add("sessionGoals", goals);
        root.add("approvedPlan", plan(plan, facts));
        root.add("statistics", statistics(stats, facts));
        root.add("planComparison", strings(differences.stream().map(StatisticsCalculator::english).toList()));
        root.add("evidenceLimits", strings(limits.stream().map(StatisticsCalculator::english).toList()));
        var identity = EquipmentClaims.Evidence.of(stats, Optional.of(plan));
        root.add("equipmentIdentity", identity(identity, facts));
        facts.identity(identity);
        differences.forEach(facts::line); limits.forEach(facts::line);
        return new Prepared(root.toString(), facts.build());
    }

    /**
     * PP-03: the deterministic model identity of every recorded/planned camera and lens pair, so the model is told what
     * the application established; the reply is checked against the same relation (EquipmentClaims) either way.
     */
    private static JsonObject identity(EquipmentClaims.Evidence identity, ReviewClaims.Builder facts) {
        var o = new JsonObject(); var pairs = new JsonArray();
        for (var kind : EquipmentIdentity.Kind.values()) {
            var names = kind == EquipmentIdentity.Kind.CAMERA ? identity.cameras() : identity.lenses();
            for (String recorded : names.recorded()) for (String planned : names.planned()) {
                var comparison = EquipmentIdentity.compare(kind, recorded, planned);
                var pair = new JsonObject();
                pair.addProperty("kind", kind == EquipmentIdentity.Kind.CAMERA ? "camera" : "lens");
                pair.addProperty("recorded", recorded); pair.addProperty("planned", planned);
                pair.addProperty("relation", switch (comparison.relation()) {
                    case SAME_MODEL -> "same model"; case DIFFERENT_MODEL -> "different model"; case UNKNOWN -> "not determined"; });
                comparison.second().or(comparison::first).filter(m -> comparison.alias()).ifPresent(model -> {
                    String label = model.brand().label() + " " + model.label();
                    pair.addProperty("catalogModel", label); facts.name(label); facts.name(model.label()); facts.name(model.id());
                });
                pairs.add(pair);
            }
        }
        o.add("pairs", pairs);
        o.addProperty("note", "Model names identify models only, never an individual camera body or lens.");
        return o;
    }

    static String json(ExifStatistics stats, ShootPlan plan, ShootRequirement requirement, List<LocalizedMessage> differences, List<LocalizedMessage> limits) {
        return prepare(stats, plan, requirement, differences, limits).json();
    }

    private static JsonObject plan(ShootPlan plan, ReviewClaims.Builder facts) {
        var o = new JsonObject();
        String id = facts.record(Source.PLAN);
        o.addProperty("id", id); facts.add(id, Source.PLAN, ReviewClaims.REVISION, "revision", plan.revision(), "");
        o.addProperty("revision", plan.revision());
        var gear = new JsonArray(); var names = new HashMap<UUID, String>();
        plan.loadout().ifPresent(loadout -> loadout.gear().forEach(item -> {
            String name = CompatibilityValidator.name(item), itemId = facts.record(Source.PLAN);
            names.put(item.sourceGearId(), name); facts.name(name);
            var g = new JsonObject(); g.addProperty("id", itemId); g.addProperty("type", item.type()); g.addProperty("name", name); g.addProperty("quantity", item.selectedQuantity());
            facts.add(itemId, Source.PLAN, ReviewClaims.COUNT, "quantity", item.selectedQuantity(), ReviewClaims.item(name));
            spec(item, "minFocalMm").ifPresent(min -> spec(item, "maxFocalMm").ifPresent(max -> {
                g.addProperty("focalRange", min + "-" + max + " mm");
                for (String bound : List.of(min, max)) number(bound).ifPresent(v -> facts.add(itemId, Source.PLAN, ReviewClaims.FOCAL, "lensRange", v, ReviewClaims.item(name)));
            }));
            gear.add(g);
        }));
        // Planned units per kind ("the plan included 2 camera bodies"), components of the plan record.
        plan.loadout().ifPresent(loadout -> {
            for (String type : List.of("CAMERA", "LENS"))
                facts.add(id, Source.PLAN, ReviewClaims.COUNT, "quantity", loadout.gear().stream().filter(item -> item.type().equals(type)).mapToLong(item -> item.selectedQuantity()).sum(),
                    "field:" + (type.equals("CAMERA") ? "camera" : "lens"));
        });
        o.add("equipment", gear);
        var mounts = new JsonArray();
        plan.loadout().ifPresent(loadout -> new TreeMap<>(loadout.cameraLensAssignments()).forEach((camera, lens) -> {
            var m = new JsonObject(); m.addProperty("camera", names.getOrDefault(camera, "?")); m.addProperty("lens", names.getOrDefault(lens, "?")); mounts.add(m);
        }));
        o.add("mounts", mounts);
        var settings = new JsonArray();
        new TreeMap<>(plan.settings()).forEach((key, value) -> {
            String name = ShootPlan.settingName(key), settingId = facts.record(Source.PLAN);
            var s = new JsonObject(); s.addProperty("id", settingId); s.addProperty("setting", name); s.addProperty("value", value);
            s.addProperty("camera", ShootPlan.settingCamera(key).map(camera -> names.getOrDefault(camera, "?")).orElse("all")); settings.add(s);
            String metric = switch (name) {
                case "shutterSpeed" -> ReviewClaims.SHUTTER; case "aperture" -> ReviewClaims.APERTURE; case "iso" -> ReviewClaims.ISO;
                case "focalLength" -> ReviewClaims.FOCAL; case "exposureCompensation" -> ReviewClaims.EV; default -> "";
            };
            if (!metric.isEmpty()) for (double v : PlanValidator.settingNumbers(name, value)) facts.add(settingId, Source.PLAN, metric, "planned", v, "");
        });
        o.add("settings", settings);
        var strategy = new JsonObject(); new TreeMap<>(plan.strategy()).forEach(strategy::addProperty);
        o.add("strategy", strategy);
        o.add("shotPriorities", strings(plan.shotPriorities()));
        var schedule = new JsonArray();
        plan.windows().forEach(w -> {
            String windowId = facts.record(Source.PLAN);
            var s = new JsonObject(); s.addProperty("id", windowId); s.addProperty("start", MINUTE.format(w.start())); s.addProperty("end", MINUTE.format(w.end()));
            s.addProperty("zone", w.start().getZone().getId()); s.addProperty("conditions", w.conditions()); schedule.add(s);
            for (var bound : List.of(Map.entry("start", w.start()), Map.entry("end", w.end()))) {
                facts.add(windowId, Source.PLAN, ReviewClaims.TIME, bound.getKey(), bound.getValue().toLocalTime().withSecond(0).toSecondOfDay(), "");
                facts.add(windowId, Source.PLAN, ReviewClaims.DATE, bound.getKey(), bound.getValue().toLocalDate().toEpochDay(), "");
            }
        });
        o.add("schedule", schedule);
        return o;
    }

    private static JsonObject statistics(ExifStatistics stats, ReviewClaims.Builder facts) {
        var o = new JsonObject();
        String id = facts.record(Source.SESSION);
        o.addProperty("id", id);
        o.addProperty("photosImported", stats.importedCount());
        o.addProperty("photosWithUsableMetadata", stats.usableCount());
        o.addProperty("filesThatFailed", stats.failedFileCount());
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "imported", stats.importedCount(), "");
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "usable", stats.usableCount(), "");
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "failed", stats.failedFileCount(), "");
        o.add("cameras", equipment(stats, "camera", stats.cameraCounts(), facts));
        o.add("lenses", equipment(stats, "lens", stats.lensCounts(), facts));
        for (String field : ExifStatistics.NUMERIC) o.add(field, numeric(stats, field, facts));
        var capture = new JsonObject();
        String captureId = facts.record(Source.SESSION);
        capture.addProperty("id", captureId);
        capture.addProperty("photosWithTimestamp", stats.timedCount());
        capture.addProperty("photosWithConfirmedUtcOffset", stats.zonedCount());
        facts.add(captureId, Source.SESSION, ReviewClaims.COUNT, "withTimestamp", stats.timedCount(), "field:captureTime");
        facts.add(captureId, Source.SESSION, ReviewClaims.COUNT, "withOffset", stats.zonedCount(), "field:captureTime");
        stats.firstCapture().ifPresent(v -> { capture.addProperty("firstCameraClockTime", v.replace('T', ' ')); captureFacts(captureId, "first", v, facts); });
        stats.lastCapture().ifPresent(v -> { capture.addProperty("lastCameraClockTime", v.replace('T', ' ')); captureFacts(captureId, "last", v, facts); });
        stats.captureOffset().ifPresent(v -> { capture.addProperty("utcOffset", v); facts.add(captureId, Source.SESSION, ReviewClaims.OFFSET, "offset", ReviewClaims.offsetMinutes(v), ""); });
        o.add("captureTimes", capture);
        return o;
    }
    private static void captureFacts(String id, String which, String localDateTime, ReviewClaims.Builder facts) {
        facts.add(id, Source.SESSION, ReviewClaims.TIME, which, ReviewClaims.secondOfDay(localDateTime), "");
        facts.add(id, Source.SESSION, ReviewClaims.DATE, which, ReviewClaims.epochDay(localDateTime), "");
    }
    private static JsonObject equipment(ExifStatistics stats, String field, Map<String, Integer> counts, ReviewClaims.Builder facts) {
        var o = new JsonObject();
        String id = facts.record(Source.SESSION);
        int n = stats.count(field);
        o.addProperty("id", id); o.addProperty("photosWithData", n); o.addProperty("photosMissing", stats.missing(field));
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "withData", n, "field:" + field);
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "missing", stats.missing(field), "field:" + field);
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "distinct", counts.size(), "field:" + field);
        var items = new JsonArray();
        new TreeMap<>(counts).forEach((name, photos) -> {
            String itemId = facts.record(Source.SESSION); facts.name(name);
            var item = new JsonObject(); item.addProperty("id", itemId); item.addProperty("name", name); item.addProperty("photos", photos); item.addProperty("percent", ExifValues.percent(photos, n)); items.add(item);
            facts.add(itemId, Source.SESSION, ReviewClaims.COUNT, "photos", photos, ReviewClaims.item(name));
            facts.add(itemId, Source.SESSION, ReviewClaims.PERCENT, "share", photos * 100.0 / n, ReviewClaims.item(name));
        });
        o.add("counts", items);
        return o;
    }
    private static JsonObject numeric(ExifStatistics stats, String field, ReviewClaims.Builder facts) {
        var o = new JsonObject();
        String id = facts.record(Source.SESSION);
        o.addProperty("id", id);
        o.addProperty("photosWithData", stats.count(field)); o.addProperty("photosMissing", stats.missing(field)); o.addProperty("invalidValues", stats.invalid(field));
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "withData", stats.count(field), "field:" + field);
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "missing", stats.missing(field), "field:" + field);
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "invalid", stats.invalid(field), "field:" + field);
        facts.add(id, Source.SESSION, ReviewClaims.COUNT, "distinct", stats.histogram(field).size(), "field:" + field);
        stats.summary(field).ifPresent(s -> {
            o.addProperty("min", ExifValues.format(field, s.min())); o.addProperty("max", ExifValues.format(field, s.max()));
            o.addProperty("median", ExifValues.format(field, s.median()));
            facts.add(id, Source.SESSION, field, "min", s.min(), ""); facts.add(id, Source.SESSION, field, "max", s.max(), "");
            facts.add(id, Source.SESSION, field, "median", s.median(), "");
            if (!field.equals("shutter")) { o.addProperty("mean", ExifValues.format(field, s.mean())); facts.add(id, Source.SESSION, field, "mean", s.mean(), ""); }
            var histogram = stats.histogram(field);
            int most = histogram.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            var top = histogram.entrySet().stream().sorted(Map.Entry.<Double, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_DISTRIBUTION).map(Map.Entry::getKey).sorted().toList();
            var items = new JsonArray();
            for (double value : top) {
                String valueId = facts.record(Source.SESSION); int photos = histogram.get(value);
                var item = new JsonObject(); item.addProperty("id", valueId); item.addProperty("value", ExifValues.format(field, value)); item.addProperty("photos", photos);
                item.addProperty("percent", ExifValues.percent(photos, s.count())); items.add(item);
                facts.add(valueId, Source.SESSION, field, "value", value, "");
                if (photos == most) facts.add(valueId, Source.SESSION, field, "mode", value, "");
                facts.add(valueId, Source.SESSION, ReviewClaims.COUNT, "photos", photos, ReviewClaims.value(field, value));
                facts.add(valueId, Source.SESSION, ReviewClaims.PERCENT, "share", photos * 100.0 / s.count(), ReviewClaims.value(field, value));
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
    private static Optional<Double> number(String text) {
        try { double v = Double.parseDouble(text); return Double.isFinite(v) ? Optional.of(v) : Optional.empty(); } catch (NumberFormatException unreadable) { return Optional.empty(); }
    }
    private static JsonArray strings(List<String> values) { var array = new JsonArray(); values.forEach(array::add); return array; }

    // ---------------------------------------------------------------- claim validation

    /**
     * A model reply after deterministic checks: kept statements (citation markers removed), the number removed and, of
     * those, the number removed for an unsupported or contradicted equipment-identity claim (PP-03).
     */
    record Checked(String summary, List<String> technical, List<String> equipment, List<String> exposure, List<String> lessons,
            List<String> improvements, List<String> limitations, int removed, int identityRemoved) { }

    /** Image content, quality, viewing and artistic-outcome claims; only allowed nowhere in observations. */
    private static final Pattern OUTCOME = Pattern.compile("(?iu)\\b(sharp(?:er|est|ness)?|blurr?(?:y|ed|ing)?|motion blur|out of focus|in focus|"
        + "noisy|noise|grain(?:y)?|well[- ]composed|composition (?:was|is|were)|beautiful|stunning|artistic(?:ally)?|aesthetic(?:ally)?|"
        + "masterpiece|keepers?|keeper rate|successful (?:photos|photographs|images|shots)|failed (?:photos|photographs|images|shots))\\b"
        + "|清晰|模糊|锐利|噪点|构图|美感|成功的照片|失败的照片");
    /** Scores and claims of having looked at the images; forbidden in every section. A shutter speed such as 1/100 s is no score. */
    private static final Pattern FORBIDDEN = Pattern.compile("(?iu)\\b(score[sd]?|rating|rated|grade[sd]?|stars?)\\b"
        + "|\\b\\d+(?:\\.\\d+)?\\s*/\\s*(?:5|10|100)\\b(?!\\s*(?:s|secs?|seconds?|秒)(?!\\p{L}))"
        + "|\\bout of (?:5|10|100|five|ten)\\b|\\b(?:i|we) (?:can )?(?:see|saw|viewed|looked at|notice[d]?)\\b|\\blooking at (?:the|your) (?:photos|photographs|images|pictures)\\b"
        + "|\\b(?:the|your) (?:photos|photographs|images|pictures|shots) (?:show|shows|look|looked|appear|appeared)\\b|评分|打分|分数|我看到|照片中可以看到|从照片中");

    /**
     * Every statement is checked against the typed facts of this request ({@link ReviewClaims}); earlier sessions' saved EXIF
     * facts count only for figures introduced as earlier. Statements with an unsupported figure are removed, never repaired.
     */
    static Checked check(ReviewInterpretation reply, ReviewClaims.Evidence evidence) {
        int[] removed = {0, 0};
        String summary = Objects.toString(reply.summary(), "").strip();
        var summaryKept = summary.isEmpty() ? List.<String>of() : keep(List.of(summary), evidence, true, removed, Integer.MAX_VALUE);
        return new Checked(summaryKept.isEmpty() ? "" : summaryKept.getFirst(),
            keep(reply.technicalObservations(), evidence, true, removed, MAX_ITEMS),
            keep(reply.equipmentObservations(), evidence, true, removed, MAX_ITEMS),
            keep(reply.exposureObservations(), evidence, true, removed, MAX_ITEMS),
            keep(reply.lessons(), evidence, false, removed, MAX_ITEMS),
            keep(reply.improvements(), evidence, false, removed, MAX_ITEMS),
            keep(reply.limitations(), evidence, false, removed, MAX_ITEMS), removed[0], removed[1]);
    }

    /**
     * Statements are kept only if bounded, free of forbidden claims, with every figure supported and with no equipment
     * identity claim the recorded and planned names cannot support (correct figures do not make such a claim true).
     * Observations (claims about the session) are checked throughout; lessons and suggestions may also propose values in
     * suggesting clauses. removed[0] counts every removal, removed[1] the identity ones.
     */
    private static List<String> keep(List<String> items, ReviewClaims.Evidence evidence, boolean observation, int[] removed, int limit) {
        if (items == null) return List.of();
        var kept = new ArrayList<String>();
        for (String raw : items) {
            String text = ExifRecord.clean(raw);
            if (text.isEmpty()) continue;
            String shown = ReviewClaims.display(text);
            boolean ok = !shown.isEmpty() && shown.length() <= (limit == Integer.MAX_VALUE ? MAX_ITEM * 2 : MAX_ITEM) && !FORBIDDEN.matcher(shown).find()
                && (!observation || !OUTCOME.matcher(shown).find()) && ReviewClaims.supported(text, evidence, !observation);
            boolean identity = ok && EquipmentClaims.check(shown, evidence.identity()).rejected();
            if (ok && !identity && kept.size() < limit) kept.add(shown);
            else { removed[0]++; if (identity) removed[1]++; }
        }
        return kept;
    }
}
