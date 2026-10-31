package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.IOException;

/** Shared bounded parser that rejects duplicate keys before domain decoding. */
final class StrictJson {
    private StrictJson() { }
    /** Object order is not content; array order remains significant for history and evidence. */
    static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var object = new JsonObject();
            for (String key : new java.util.TreeSet<>(value.getAsJsonObject().keySet()))
                object.add(key, canonical(value.getAsJsonObject().get(key)));
            return object;
        }
        if (value.isJsonArray()) {
            var array = new JsonArray(); value.getAsJsonArray().forEach(item -> array.add(canonical(item))); return array;
        }
        return value;
    }
    static JsonElement readElement(JsonReader reader, int depth) throws IOException {
        if (depth > 20) throw new IOException("JSON nesting is too deep.");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON field: " + key);
                    object.add(key, readElement(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(readElement(reader, depth + 1));
                reader.endArray(); return array;
            }
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Malformed JSON.");
        }
    }
}
