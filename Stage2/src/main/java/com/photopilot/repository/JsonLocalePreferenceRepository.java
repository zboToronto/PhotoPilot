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
        var bytes = AtomicFiles.read(file, MAX_BYTES);
        if (bytes.isEmpty()) return readLegacy();
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
            return values;
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
    @Override public void save(String tag) throws IOException {
        if (!Language.supports(tag)) throw new IOException("Unsupported language preference.");
        AtomicFiles.locked(file.resolveSibling(file.getFileName() + ".lock"), () -> {
            // Coordinate the first copy with the existing properties writer too.
            return AtomicFiles.locked(legacy.resolveSibling(legacy.getFileName() + ".lock"), () -> {
                var values = read(); values.put("language", tag);
                JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1); root.add("values", JSON.toJsonTree(values));
                AtomicFiles.write(file, "preferences-", JSON.toJson(root).getBytes(StandardCharsets.UTF_8), MAX_BYTES, this::replace);
                return true;
            });
        });
    }
    protected void replace(Path temporary) throws IOException { AtomicFiles.replace(temporary, file); }
}
