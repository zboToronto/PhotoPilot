package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.stream.*;
import com.photopilot.i18n.Language;
import com.photopilot.i18n.LocalePreferenceRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Versioned JSON preferences; legacy properties are copied only on explicit save and never modified. */
public class JsonLocalePreferenceRepository implements LocalePreferenceRepository {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Path legacy;
    public JsonLocalePreferenceRepository(Path file, Path legacy) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.legacy = Objects.requireNonNull(legacy).toAbsolutePath().normalize();
        if (this.file.equals(this.legacy)) throw new IllegalArgumentException("JSON and legacy settings must have distinct paths.");
    }
    private Map<String, String> read() throws IOException {
        var stored = readJson();
        return stored.isPresent() ? stored.orElseThrow() : readLegacy();
    }
    private Optional<Map<String, String>> readJson() throws IOException {
        var bytes = AtomicFiles.read(file, MAX_BYTES);
        if (bytes.isEmpty()) return Optional.empty();
        try (var reader = new JsonReader(new StringReader(AtomicFiles.utf8(bytes.get())))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = StrictJson.readElement(reader, 0).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.keySet().equals(Set.of("schemaVersion", "values"))
                    || !root.get("schemaVersion").toString().equals("1")) throw new IOException("Unsupported preferences schema.");
            Map<String, String> values = new LinkedHashMap<>();
            for (var entry : root.getAsJsonObject("values").entrySet()) {
                var value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Preference values must be strings.");
                values.put(entry.getKey(), value.getAsString());
            }
            if (values.containsKey("language") && !Language.supports(values.get("language"))) throw new IOException("Unsupported saved language.");
            return Optional.of(values);
        } catch (RuntimeException malformed) { throw new IOException("Invalid preferences JSON; original preserved.", malformed); }
    }
    private Map<String, String> readLegacy() throws IOException {
        var bytes = AtomicFiles.read(legacy, MAX_BYTES, true);
        Map<String, String> values = new LinkedHashMap<>();
        if (bytes.isEmpty()) return values;
        Properties properties = new Properties();
        try { properties.load(new StringReader(AtomicFiles.utf8(bytes.get()))); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed legacy preferences; original preserved.", malformed); }
        for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
        return values;
    }
    @Override public String load() throws IOException { return read().get("language"); }
    /** Every stored string value, read the same way as the language; never writes. Used for GUI appearance preferences. */
    public Map<String, String> values() throws IOException { return Collections.unmodifiableMap(read()); }
    /**
     * Additively stores other string preferences (GUI appearance) in the same schema-1 file, under the same locks.
     * Existing values, including the language, are preserved; the language itself is only written by save(tag).
     */
    public void merge(Map<String, String> updates) throws IOException {
        Map<String, String> additions = new LinkedHashMap<>();
        for (var entry : Objects.requireNonNull(updates).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().equals("language") || entry.getValue() == null)
                throw new IOException("Unsupported preference value.");
            additions.put(entry.getKey(), entry.getValue());
        }
        AtomicFiles.locked(file.resolveSibling(file.getFileName() + ".lock"), () -> {
            var stored = readJson();
            if (stored.isPresent()) writeValues(withAdditions(stored.orElseThrow(), additions));
            else AtomicFiles.locked(legacy.resolveSibling(legacy.getFileName() + ".lock"), () -> {
                writeValues(withAdditions(readLegacy(), additions)); return true;
            });
            return true;
        });
    }
    private static Map<String, String> withAdditions(Map<String, String> values, Map<String, String> additions) {
        values.putAll(additions); return values;
    }
    @Override public void save(String tag) throws IOException {
        if (!Language.supports(tag)) throw new IOException("Unsupported language preference.");
        AtomicFiles.locked(file.resolveSibling(file.getFileName() + ".lock"), () -> {
            var stored = readJson();
            if (stored.isPresent()) write(stored.orElseThrow(), tag);
            else {
                // Only the first copy depends on the legacy writer. JSON remains authoritative thereafter.
                AtomicFiles.locked(legacy.resolveSibling(legacy.getFileName() + ".lock"), () -> {
                    write(readLegacy(), tag); return true;
                });
            }
            return true;
        });
    }
    private void write(Map<String, String> values, String tag) throws IOException {
        values.put("language", tag);
        writeValues(values);
    }
    private void writeValues(Map<String, String> values) throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1); root.add("values", JSON.toJsonTree(values));
        AtomicFiles.write(file, "preferences-", JSON.toJson(root).getBytes(StandardCharsets.UTF_8), MAX_BYTES, this::replace);
    }
    protected void replace(Path temporary) throws IOException { AtomicFiles.replace(temporary, file); }
}
