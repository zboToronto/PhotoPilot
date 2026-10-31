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
    static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().setStrictness(Strictness.STRICT)
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
        PhotographySession session = JSON.fromJson(object, PhotographySession.class);
        // Reject unknown/missing fields and coercions instead of silently dropping future data.
        if (!object.equals(encode(session))) throw new IllegalArgumentException("Unsupported or incomplete session fields.");
        return session;
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
