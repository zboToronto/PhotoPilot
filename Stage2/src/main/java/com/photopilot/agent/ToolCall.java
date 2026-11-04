package com.photopilot.agent;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Objects;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 21 (zero-based): {untrusted until schema and permission checks}
 * One model-requested tool invocation. {@link #validateAgainst} checks the arguments against the tool's input
 * schema (the JSON Schema subset the registered tools use) before any tool code runs.
 */
public class ToolCall {
    public static final int MAX_ARGUMENTS = 16384;
    private String toolName;
    private String argumentsJson;

    public ToolCall(String toolName, String argumentsJson) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.argumentsJson = Objects.requireNonNull(argumentsJson, "argumentsJson");
    }

    public String toolName() { return toolName; }
    public String argumentsJson() { return argumentsJson; }
    /** Same tool and semantically identical arguments (key order and whitespace ignored); used to detect repeats. */
    public String fingerprint() {
        try { return toolName + ":" + canonical(JsonParser.parseString(argumentsJson)); }
        catch (RuntimeException unreadable) { return toolName + ":" + argumentsJson.strip(); }
    }

    /**
     * Strict single JSON object without duplicate keys, matching the schema's type, required, additionalProperties,
     * enum, minimum/maximum and maxProperties rules. Nothing is coerced or repaired.
     */
    public Result<ToolCall> validateAgainst(String schema) {
        if (argumentsJson.length() > MAX_ARGUMENTS) return Result.failureKey("ai.error.toolArguments", toolName, "too long");
        JsonObject rules;
        try { rules = JsonParser.parseString(Objects.requireNonNull(schema)).getAsJsonObject(); }
        catch (RuntimeException invalid) { return Result.failureKey("ai.error.toolArguments", toolName, "no input schema"); }
        try (JsonReader reader = new JsonReader(new StringReader(argumentsJson))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return Result.failureKey("ai.error.toolArguments", toolName, "a JSON object is required");
            JsonElement value = readUnique(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) return Result.failureKey("ai.error.toolArguments", toolName, "trailing content");
            String problem = check(value, rules, "arguments");
            return problem == null ? Result.success(this) : Result.failureKey("ai.error.toolArguments", toolName, problem);
        } catch (IOException | RuntimeException malformed) {
            return Result.failureKey("ai.error.toolArguments", toolName, Objects.toString(malformed.getMessage(), "malformed JSON"));
        }
    }

    private static String check(JsonElement value, JsonObject rules, String path) {
        String type = rules.has("type") ? rules.get("type").getAsString() : "";
        switch (type) {
            case "object" -> {
                if (!value.isJsonObject()) return path + " must be an object";
                JsonObject object = value.getAsJsonObject();
                JsonObject properties = rules.has("properties") ? rules.getAsJsonObject("properties") : new JsonObject();
                if (rules.has("maxProperties") && object.size() > rules.get("maxProperties").getAsInt()) return path + " has too many properties";
                if (rules.has("required"))
                    for (JsonElement name : rules.getAsJsonArray("required"))
                        if (!object.has(name.getAsString())) return path + "." + name.getAsString() + " is required";
                for (var entry : object.entrySet()) {
                    String child = path + "." + entry.getKey();
                    if (properties.has(entry.getKey())) {
                        String found = check(entry.getValue(), properties.getAsJsonObject(entry.getKey()), child);
                        if (found != null) return found;
                    } else if (rules.has("additionalProperties") && rules.get("additionalProperties").isJsonObject()) {
                        String found = check(entry.getValue(), rules.getAsJsonObject("additionalProperties"), child);
                        if (found != null) return found;
                    } else if (rules.has("additionalProperties") && !rules.get("additionalProperties").getAsBoolean()) {
                        return child + " is not allowed";
                    }
                }
                return null;
            }
            case "number", "integer" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return path + " must be a number";
                double number = value.getAsDouble();
                if (!Double.isFinite(number)) return path + " must be finite";
                if (type.equals("integer") && number != Math.rint(number)) return path + " must be an integer";
                if (rules.has("minimum") && number < rules.get("minimum").getAsDouble()) return path + " is below the minimum";
                if (rules.has("maximum") && number > rules.get("maximum").getAsDouble()) return path + " is above the maximum";
                return null;
            }
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return path + " must be a string";
                if (rules.has("enum")) {
                    for (JsonElement allowed : rules.getAsJsonArray("enum")) if (allowed.getAsString().equals(value.getAsString())) return null;
                    return path + " has an unsupported value";
                }
                return null;
            }
            case "boolean" -> { return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? null : path + " must be true or false"; }
            default -> { return null; }
        }
    }

    /** Reads one value, rejecting duplicate object keys and excessive nesting. */
    private static JsonElement readUnique(JsonReader reader, int depth) throws IOException {
        if (depth > 8) throw new IllegalArgumentException("nesting is too deep");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); var names = new HashSet<String>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) throw new IllegalArgumentException("duplicate key " + name);
                    object.add(name, readUnique(reader, depth + 1));
                }
                reader.endObject(); return object;
            }
            case BEGIN_ARRAY -> {
                var array = new com.google.gson.JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(readUnique(reader, depth + 1));
                reader.endArray(); return array;
            }
            case STRING -> { return new com.google.gson.JsonPrimitive(reader.nextString()); }
            case NUMBER -> { return new com.google.gson.JsonPrimitive(new java.math.BigDecimal(reader.nextString())); }
            case BOOLEAN -> { return new com.google.gson.JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return com.google.gson.JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("unexpected JSON token");
        }
    }

    private static String canonical(JsonElement value) {
        if (value.isJsonObject()) {
            var sorted = new java.util.TreeMap<String, String>();
            value.getAsJsonObject().entrySet().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted.toString();
        }
        return value.toString();
    }
}
