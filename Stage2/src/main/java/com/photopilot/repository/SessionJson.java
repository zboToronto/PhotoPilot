package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.*;
import com.photopilot.model.*;
import com.photopilot.state.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Version-one aggregate codec. No class names from disk are instantiated. */
final class SessionJson {
    private SessionJson() { }
    static final Gson JSON = SessionDomainAdapters.register(new GsonBuilder().disableJdkUnsafe()).serializeNulls().setPrettyPrinting().setStrictness(Strictness.STRICT)
        .registerTypeAdapter(Instant.class, text(Instant::parse))
        .registerTypeAdapter(LocalDate.class, text(LocalDate::parse))
        .registerTypeAdapter(ZonedDateTime.class, text(ZonedDateTime::parse))
        .registerTypeAdapter(Duration.class, text(Duration::parse))
        .registerTypeAdapter(SessionState.class, new TypeAdapter<SessionState>() {
            public void write(JsonWriter out, SessionState value) throws IOException {
                out.value(value.getClass().getSimpleName().replace("State", "").toUpperCase(Locale.ROOT));
            }
            public SessionState read(JsonReader in) throws IOException {
                return switch (in.nextString()) {
                    case "DRAFT" -> new DraftState(); case "PLANNED" -> new PlannedState();
                    case "COMPLETED" -> new CompletedState(); case "REVIEWED" -> new ReviewedState();
                    default -> throw new IOException("Unknown session state.");
                };
            }
        })
        // F08 has legitimate infinite optical distances; keep valid JSON and exact semantics.
        .registerTypeAdapter(Double.class, new TypeAdapter<Double>() {
            public void write(JsonWriter out, Double value) throws IOException {
                if (value == null || Double.isNaN(value)) throw new IOException("Invalid numerical evidence.");
                if (value.isInfinite()) out.value(value > 0 ? "Infinity" : "-Infinity"); else out.value(value);
            }
            public Double read(JsonReader in) throws IOException {
                if (in.peek() == JsonToken.STRING) {
                    String text = in.nextString();
                    if (text.equals("Infinity")) return Double.POSITIVE_INFINITY;
                    if (text.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
                    // JSON object keys (e.g. EXIF focal-length histograms) are necessarily strings.
                    double key = Double.parseDouble(text);
                    if (!Double.isFinite(key)) throw new IOException("Invalid numerical evidence.");
                    return key;
                }
                double value = in.nextDouble();
                if (!Double.isFinite(value)) throw new IOException("Nonfinite JSON number.");
                return value;
            }
        })
        .registerTypeAdapterFactory(new TypeAdapterFactory() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                if (type.getRawType() != Optional.class) return gson.getDelegateAdapter(this, type);
                Type item = ((ParameterizedType) type.getType()).getActualTypeArguments()[0];
                TypeAdapter delegate = gson.getAdapter(TypeToken.get(item));
                return (TypeAdapter<T>) new TypeAdapter<Optional<?>>() {
                    public void write(JsonWriter out, Optional<?> value) throws IOException {
                        if (value.isEmpty()) out.nullValue(); else delegate.write(out, value.get());
                    }
                    public Optional<?> read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) { in.nextNull(); return Optional.empty(); }
                        return Optional.of(delegate.read(in));
                    }
                };
            }
        }).create();

    private static <T> TypeAdapter<T> text(Function<String, T> parser) {
        return new TypeAdapter<>() {
            public void write(JsonWriter out, T value) throws IOException { out.value(value.toString()); }
            public T read(JsonReader in) throws IOException {
                if (in.peek() != JsonToken.STRING) throw new IOException("Expected an ISO date/time string.");
                return parser.apply(in.nextString());
            }
        };
    }
    static JsonObject encode(PhotographySession session) {
        validateGraph(session);
        var valid = session.validate();
        if (!valid.isSuccess()) throw new IllegalArgumentException(String.join("; ", valid.errors()));
        return JSON.toJsonTree(session).getAsJsonObject();
    }
    static PhotographySession decode(JsonObject object) {
        JsonObject source = withRequirementAdditions(object);
        PhotographySession session = JSON.fromJson(source, PhotographySession.class);
        // Reject unknown/missing fields and coercions instead of silently dropping future data.
        if (!source.equals(encode(session))) throw new IllegalArgumentException("Unsupported or incomplete session fields.");
        return session;
    }
    /**
     * F03 added three optional v1 requirement fields. Records written before them omit the fields, which
     * means "not specified"; only those exact absences are filled, so every other strictness check remains.
     */
    private static JsonObject withRequirementAdditions(JsonObject object) {
        // F07 added an optional v1 selected loadout; older records simply have none.
        if (!object.has("selectedLoadout")) { object = object.deepCopy(); object.add("selectedLoadout", JsonNull.INSTANCE); }
        object = withPlanAdditions(object);
        object = withIdeaAdditions(object);
        object = withReviewAdditions(object);
        if (!(object.get("requirements") instanceof JsonObject requirement)
                || requirement.has("date") && requirement.has("timeWindow") && requirement.has("portability")) return object;
        JsonObject copy = object.deepCopy(); JsonObject target = copy.getAsJsonObject("requirements");
        if (!target.has("date")) target.add("date", JsonNull.INSTANCE);
        if (!target.has("timeWindow")) target.addProperty("timeWindow", "");
        if (!target.has("portability")) target.addProperty("portability", "");
        return copy;
    }
    /** F10 added structured plan sections to v1 plans; plans written before them have none (empty, never invented). */
    private static JsonObject withPlanAdditions(JsonObject object) {
        if (!(object.get("plans") instanceof JsonArray plans)) return object;
        var defaults = new LinkedHashMap<String, JsonElement>();
        defaults.put("summary", new JsonPrimitive("")); defaults.put("sessionInfo", new JsonObject()); defaults.put("strategy", new JsonObject());
        defaults.put("equipmentNotes", new JsonArray()); defaults.put("tradeOffs", new JsonArray()); defaults.put("limitations", new JsonArray());
        defaults.put("changeSummary", new JsonPrimitive(""));
        boolean complete = true;
        for (JsonElement plan : plans) if (plan instanceof JsonObject o && !defaults.keySet().stream().allMatch(o::has)) complete = false;
        if (complete) return object;
        JsonObject copy = object.deepCopy();
        for (JsonElement plan : copy.getAsJsonArray("plans"))
            if (plan instanceof JsonObject o) defaults.forEach((key, value) -> { if (!o.has(key)) o.add(key, value.deepCopy()); });
        return copy;
    }
    /**
     * F02 added identity, place, timing, light, gear and feasibility fields to v1 saved ideas. Ideas written before
     * them get a content-derived stable id, empty values and status UNVERIFIED (they were never checked); nothing else
     * is filled, so the remaining strictness checks still apply.
     */
    private static JsonObject withIdeaAdditions(JsonObject object) {
        if (!(object.get("savedIdeas") instanceof JsonArray ideas)) return object;
        var defaults = new LinkedHashMap<String, JsonElement>();
        defaults.put("genre", new JsonPrimitive("")); defaults.put("place", new JsonPrimitive("")); defaults.put("atUserLocation", new JsonPrimitive(false));
        defaults.put("start", JsonNull.INSTANCE); defaults.put("light", new JsonPrimitive("")); defaults.put("gear", new JsonArray());
        defaults.put("status", new JsonPrimitive("UNVERIFIED")); defaults.put("suggestedAt", JsonNull.INSTANCE);
        boolean complete = true;
        for (JsonElement idea : ideas) if (idea instanceof JsonObject o && (!o.has("id") || !defaults.keySet().stream().allMatch(o::has))) complete = false;
        if (complete) return object;
        JsonObject copy = object.deepCopy();
        int index = 0;
        for (JsonElement idea : copy.getAsJsonArray("savedIdeas")) {
            index++;
            if (!(idea instanceof JsonObject o)) continue;
            // Content and position make the id stable across loads and distinct for identical legacy entries.
            if (!o.has("id")) o.addProperty("id", UUID.nameUUIDFromBytes((index + ":" + o).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            defaults.forEach((key, value) -> { if (!o.has(key)) o.add(key, value.deepCopy()); });
        }
        return copy;
    }
    /**
     * F11 added import context to v1 statistics and structured sections to v1 reviews. Records written before them get
     * empty values: no capture span, no fingerprint, no recorded owner id, empty sections and status SAVED (a stored
     * review was always a saved one). Nothing is derived from other data, so the remaining strictness checks apply.
     */
    private static JsonObject withReviewAdditions(JsonObject object) {
        var statistics = new LinkedHashMap<String, JsonElement>();
        statistics.put("invalidCounts", new JsonObject()); statistics.put("compensationCounts", new JsonObject());
        statistics.put("failedFileCount", new JsonPrimitive(0)); statistics.put("timedCount", new JsonPrimitive(0)); statistics.put("zonedCount", new JsonPrimitive(0));
        statistics.put("firstCapture", JsonNull.INSTANCE); statistics.put("lastCapture", JsonNull.INSTANCE); statistics.put("captureOffset", JsonNull.INSTANCE);
        statistics.put("analyzedAt", JsonNull.INSTANCE); statistics.put("sourceFingerprint", new JsonPrimitive(""));
        var review = new LinkedHashMap<String, JsonElement>();
        review.put("sessionId", JsonNull.INSTANCE); review.put("summary", new JsonPrimitive(""));
        for (String list : List.of("technicalObservations", "equipmentObservations", "exposureObservations", "improvements", "evidence")) review.put(list, new JsonArray());
        review.put("status", new JsonPrimitive("SAVED"));
        var targets = new ArrayList<JsonObject>();
        if (object.get("statistics") instanceof JsonObject stats) targets.add(stats);
        if (object.get("reviews") instanceof JsonArray reviews)
            for (JsonElement item : reviews) if (item instanceof JsonObject o) { targets.add(o); if (o.get("statistics") instanceof JsonObject s) targets.add(s); }
        boolean complete = targets.stream().allMatch(t -> (t.has("importedCount") ? statistics : review).keySet().stream().allMatch(t::has));
        if (complete) return object;
        JsonObject copy = object.deepCopy();
        if (copy.get("statistics") instanceof JsonObject stats) fill(stats, statistics);
        if (copy.get("reviews") instanceof JsonArray reviews)
            for (JsonElement item : reviews) if (item instanceof JsonObject o) { fill(o, review); if (o.get("statistics") instanceof JsonObject s) fill(s, statistics); }
        return copy;
    }
    private static void fill(JsonObject target, Map<String, JsonElement> defaults) {
        defaults.forEach((key, value) -> { if (!target.has(key)) target.add(key, value.deepCopy()); });
    }
    private static void validateGraph(Object value) {
        Objects.requireNonNull(value, "Missing required session/linked-record value");
        if (value instanceof Optional<?> optional) { optional.ifPresent(SessionJson::validateGraph); return; }
        if (value instanceof Collection<?> collection) { collection.forEach(SessionJson::validateGraph); return; }
        if (value instanceof Map<?, ?> map) { map.forEach((k, v) -> { validateGraph(k); validateGraph(v); }); return; }
        if (value instanceof GeoLocation location && !location.validate().isSuccess()) throw new IllegalArgumentException("Invalid saved location.");
        if (value instanceof GearSnapshot gear && (gear.selectedQuantity() < 1 || gear.brand().isBlank() || gear.model().isBlank()))
            throw new IllegalArgumentException("Invalid gear snapshot.");
        Class<?> type = value.getClass();
        if (!type.getPackageName().startsWith("com.photopilot") || type.isEnum()) return;
        try {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                field.setAccessible(true); validateGraph(field.get(value));
            }
        } catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
    }
}
