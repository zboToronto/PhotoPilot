package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.stream.*;
import com.photopilot.i18n.Language;
import com.photopilot.i18n.LocalePreferenceRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Versioned JSON preferences; legacy properties are copied only on the first write (a saved choice or a new profile's
 * initial values), never on read, and are never modified.
 * {@link #forProfile} gives one local profile its own record in the same file: its keys are stored as
 * {@code profile.<uuid>.<key>}, so a profile's language and GUI appearance follow its stable id (never its name) and
 * no two profiles share them.
 */
public class JsonLocalePreferenceRepository implements LocalePreferenceRepository {
    private static final int MAX_BYTES = 64 * 1024;
    private static final String PROFILES = "profile.";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Path legacy;
    // Empty for the shared (pre-profile) values; otherwise the owner of the "profile.<uuid>." keys.
    private final Optional<UUID> profile;
    private final String prefix;
    private final boolean inheritsShared;
    public JsonLocalePreferenceRepository(Path file, Path legacy) { this(file, legacy, Optional.empty(), false); }
    private JsonLocalePreferenceRepository(Path file, Path legacy, Optional<UUID> profile, boolean inheritsShared) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.legacy = Objects.requireNonNull(legacy).toAbsolutePath().normalize();
        if (this.file.equals(this.legacy)) throw new IllegalArgumentException("JSON and legacy settings must have distinct paths.");
        this.profile = profile; this.inheritsShared = inheritsShared;
        prefix = profile.map(id -> PROFILES + id + ".").orElse("");
    }
    /**
     * The same file, format and locks, scoped to one profile. Creating this view writes nothing; a new profile's record is
     * first written with its initial values (config.UserPreferenceDefaults), an older profile's when it first changes a value.
     * {@code inheritsShared}: the stable default profile still reads the values saved before preferences were per
     * profile (key by key, until it saves its own), so existing settings are kept without being copied to other users.
     */
    public JsonLocalePreferenceRepository forProfile(UUID id, boolean inheritsShared) {
        return new JsonLocalePreferenceRepository(file, legacy, Optional.of(Objects.requireNonNull(id)), inheritsShared);
    }
    /** The profile whose record this is; empty for the shared values. */
    public Optional<UUID> profile() { return profile; }
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
    @Override public String load() throws IOException { return values().get("language"); }
    /**
     * Every stored string value, read the same way as the language; never writes. Used for GUI appearance preferences.
     * A profile's view has its own keys without the prefix (over the shared ones when it inherits them).
     */
    public Map<String, String> values() throws IOException {
        var stored = read();
        if (profile.isEmpty()) return Collections.unmodifiableMap(stored);
        Map<String, String> values = new LinkedHashMap<>();
        if (inheritsShared) stored.forEach((key, value) -> { if (!key.startsWith(PROFILES)) values.put(key, value); });
        stored.forEach((key, value) -> { if (key.startsWith(prefix)) values.put(key.substring(prefix.length()), value); });
        return Collections.unmodifiableMap(values);
    }
    /**
     * Additively stores other string preferences (GUI appearance) in the same schema-1 file, under the same locks.
     * Existing values, including the language and every other profile's record, are preserved; the language itself
     * is only written by save(tag).
     */
    public void merge(Map<String, String> updates) throws IOException {
        Map<String, String> additions = new LinkedHashMap<>();
        for (var entry : Objects.requireNonNull(updates).entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().equals("language") || entry.getValue() == null)
                throw new IOException("Unsupported preference value.");
            additions.put(prefix + entry.getKey(), entry.getValue());
        }
        store(additions);
    }
    @Override public void save(String tag) throws IOException {
        if (!Language.supports(tag)) throw new IOException("Unsupported language preference.");
        store(Map.of(prefix + "language", tag));
    }
    private void store(Map<String, String> additions) throws IOException {
        AtomicFiles.locked(file.resolveSibling(file.getFileName() + ".lock"), () -> {
            var stored = readJson();
            if (stored.isPresent()) writeValues(withAdditions(stored.orElseThrow(), additions));
            else {
                // Only the first copy depends on the legacy writer. JSON remains authoritative thereafter.
                AtomicFiles.locked(legacy.resolveSibling(legacy.getFileName() + ".lock"), () -> {
                    writeValues(withAdditions(readLegacy(), additions)); return true;
                });
            }
            return true;
        });
    }
    private static Map<String, String> withAdditions(Map<String, String> values, Map<String, String> additions) {
        values.putAll(additions); return values;
    }
    private void writeValues(Map<String, String> values) throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1); root.add("values", JSON.toJsonTree(values));
        AtomicFiles.write(file, "preferences-", JSON.toJson(root).getBytes(StandardCharsets.UTF_8), MAX_BYTES, this::replace);
    }
    protected void replace(Path temporary) throws IOException { AtomicFiles.replace(temporary, file); }
}
