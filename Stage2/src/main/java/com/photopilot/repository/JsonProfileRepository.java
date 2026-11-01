package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.stream.*;
import com.photopilot.model.LocalProfile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Atomic registry and recoverable initialization journal. Business codecs remain in their repositories. */
public class JsonProfileRepository {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    // Only lock ownership, never profile/application data. Nested F05 -> history reads share the same OS lock.
    private static final ThreadLocal<Set<Path>> HELD = ThreadLocal.withInitial(HashSet::new);
    private final Path root;
    private record Entry(LocalProfile profile, boolean archived) { }
    private record Pending(LocalProfile profile, Map<String, String> files) { }
    private record Registry(UUID defaultId, List<Entry> entries, Pending pending) { }

    public JsonProfileRepository(Path root) { this.root = Objects.requireNonNull(root).toAbsolutePath().normalize(); }
    public Path root() { return root; }
    private Path file() { return root.resolve("profiles.json"); }
    private StorageScope scope(UUID id) { return StorageScope.forProfile(root, id); }
    @FunctionalInterface interface Operation<T> { T run() throws IOException; }
    private <T> T locked(Operation<T> action) throws IOException {
        Path lock = root.resolve("profiles.lock"); ProfilePaths.safe(lock); ProfilePaths.safe(file());
        Set<Path> held = HELD.get();
        if (held.contains(lock)) return action.run();
        try {
            return AtomicFiles.locked(lock, () -> {
                held.add(lock);
                try { return action.run(); } finally { held.remove(lock); }
            });
        } finally { if (held.isEmpty()) HELD.remove(); }
    }
    public LocalProfile initializeDefault() throws IOException {
        return locked(() -> {
            Optional<Registry> stored = read(); Registry state;
            if (stored.isEmpty()) {
                // Snapshot only known files under both existing writer locks. Preserve all source bytes.
                for (String directory : List.of("profiles", "profile-staging")) {
                    Path existing = root.resolve(directory); ProfilePaths.safe(existing);
                    if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) try (var children = Files.list(existing)) {
                        if (children.findAny().isPresent()) throw new IOException("Profile directories exist without a registry; restore profiles.json instead of initializing new identities.");
                    }
                }
                Map<String, byte[]> legacy = legacySnapshot();
                LocalProfile profile = fresh("Default");
                Map<String, String> hashes = new TreeMap<>(); legacy.forEach((key, value) -> hashes.put(key, digest(value)));
                state = new Registry(profile.id(), List.of(), new Pending(profile, hashes));
                write(state); // Stable UUID and source fingerprints precede any destination creation.
            } else state = stored.orElseThrow();
            state = recover(state);
            return active(state, state.defaultId()).profile();
        });
    }
    public List<LocalProfile> list() throws IOException {
        return locked(() -> ready().entries().stream().filter(e -> !e.archived()).map(Entry::profile)
            .sorted(Comparator.comparing(LocalProfile::createdAt).thenComparing(p -> p.id().toString())).toList());
    }
    public LocalProfile find(UUID id) throws IOException { return locked(() -> active(ready(), id).profile()); }
    public LocalProfile create(String name) throws IOException {
        String valid = LocalProfile.validName(name);
        return locked(() -> {
            Registry state = recover(ready()); LocalProfile profile = fresh(valid);
            if (state.entries().stream().anyMatch(e -> e.profile().id().equals(profile.id())) || Files.exists(scope(profile.id()).directory(), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Profile UUID collision; retry creation.");
            var pending = new Registry(state.defaultId(), state.entries(), new Pending(profile, Map.of()));
            write(pending); recover(pending); return profile;
        });
    }
    public LocalProfile rename(UUID id, String name) throws IOException {
        String valid = LocalProfile.validName(name);
        return locked(() -> {
            Registry state = ready(); var renamed = active(state, id).profile().renamed(valid);
            var entries = state.entries().stream().map(e -> e.profile().id().equals(id) ? new Entry(renamed, false) : e).toList();
            write(new Registry(state.defaultId(), entries, state.pending())); return renamed;
        });
    }
    /** Recoverable deletion: atomically tombstone metadata; retain every data byte in its UUID directory. */
    public void archive(UUID id, boolean confirmed) throws IOException {
        if (!confirmed) throw new IOException("Profile deletion requires explicit confirmation.");
        locked(() -> {
            Registry state = ready(); active(state, id);
            if (state.defaultId().equals(id)) throw new IOException("The stable default profile cannot be deleted.");
            ProfilePaths.business(scope(id), true); ProfilePaths.tree(scope(id).directory());
            var entries = state.entries().stream().map(e -> e.profile().id().equals(id) ? new Entry(e.profile(), true) : e).toList();
            write(new Registry(state.defaultId(), entries, state.pending())); return true;
        });
    }
    public ProfileAccess open(UUID id) throws IOException {
        return withProfile(id, () -> {
            validateBusiness(scope(id));
            return new ProfileAccess(this, scope(id));
        });
    }
    <T> T withProfile(UUID id, Operation<T> action) throws IOException {
        return locked(() -> {
            active(ready(), id); ProfilePaths.business(scope(id), true); return action.run();
        });
    }
    private Registry ready() throws IOException {
        return read().orElseThrow(() -> new IOException("Profile registry is not initialized; initialize the default profile first."));
    }
    private Entry active(Registry state, UUID id) throws IOException {
        if (id == null) throw new IOException("A profile UUID is required.");
        return state.entries().stream().filter(e -> e.profile().id().equals(id) && !e.archived()).findFirst()
            .orElseThrow(() -> new IOException("Unknown or archived profile: " + id));
    }
    private LocalProfile fresh(String name) { Instant now = Instant.now(); return new LocalProfile(UUID.randomUUID(), name, now, now); }

    private Registry recover(Registry state) throws IOException {
        Pending pending = state.pending(); if (pending == null) return state;
        Path destination = scope(pending.profile().id()).directory();
        Path staging = root.resolve("profile-staging").resolve(pending.profile().id().toString());
        ProfilePaths.safe(destination); ProfilePaths.safe(staging);
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            // An empty default snapshot still needs change detection after an interrupted migration.
            boolean migratingDefault = pending.profile().id().equals(state.defaultId());
            Map<String, byte[]> source = migratingDefault ? legacySnapshot() : Map.of();
            Map<String, String> hashes = new TreeMap<>(); source.forEach((key, bytes) -> hashes.put(key, digest(bytes)));
            if (!hashes.equals(pending.files())) throw new IOException("Legacy data changed during initialization; originals and pending profile preserved. Restore the recorded source before retrying.");
            Files.createDirectories(staging);
            for (var entry : source.entrySet()) {
                Path target = staging.resolve(entry.getKey()); ProfilePaths.safe(target);
                var existing = AtomicFiles.read(target, limit(entry.getKey()));
                if (existing.isPresent()) {
                    if (!digest(existing.get()).equals(pending.files().get(entry.getKey()))) throw new IOException("Conflicting staged migration data; no files overwritten.");
                } else AtomicFiles.write(target, (entry.getKey().startsWith("sessions/") ? "sessions-" : "inventory-"), entry.getValue(), limit(entry.getKey()), temp -> AtomicFiles.replace(temp, target));
            }
            verifyCopy(staging, pending.files());
            Files.createDirectories(destination.getParent());
            movePrepared(staging, destination);
        }
        verifyCopy(destination, pending.files());
        validateBusiness(scope(pending.profile().id()));
        var entries = new ArrayList<>(state.entries()); entries.add(new Entry(pending.profile(), false));
        Registry completed = new Registry(state.defaultId(), List.copyOf(entries), null);
        write(completed); return completed;
    }
    /** Fault seam: the same-volume directory move publishes a prepared, verified context. */
    protected void movePrepared(Path staging, Path destination) throws IOException {
        Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
    }
    private void verifyCopy(Path directory, Map<String, String> expected) throws IOException {
        ProfilePaths.tree(directory); Set<String> found = new HashSet<>();
        try (var files = Files.walk(directory)) {
            for (Path path : files.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                // Crash leftovers from our atomic staging writes are never promoted as business records.
                if ((relative.startsWith("sessions/sessions-") || relative.startsWith("inventory-")) && relative.endsWith(".tmp")) continue;
                if (!expected.containsKey(relative)) throw new IOException("Unexpected data in pending profile: " + relative);
                byte[] bytes = AtomicFiles.read(path, limit(relative)).orElseThrow();
                if (!digest(bytes).equals(expected.get(relative))) throw new IOException("Pending profile differs from migration snapshot; no overwrite.");
                found.add(relative);
            }
        }
        if (!found.equals(expected.keySet())) throw new IOException("Migration verification found missing files.");
    }
    private Map<String, byte[]> legacySnapshot() throws IOException {
        StorageScope legacy = StorageScope.legacy(root); ProfilePaths.business(legacy, false);
        return AtomicFiles.locked(root.resolve("inventory.json.lock"), () -> AtomicFiles.locked(root.resolve("sessions/sessions.lock"), () -> {
            validateBusiness(legacy); Map<String, byte[]> files = new TreeMap<>();
            for (String name : List.of("inventory.json", "sessions/sessions.json")) {
                var bytes = AtomicFiles.read(root.resolve(name), limit(name)); if (bytes.isPresent()) files.put(name, bytes.get());
            }
            return files;
        }));
    }
    private static int limit(String name) { return name.equals("inventory.json") ? 10 * 1024 * 1024 : 20 * 1024 * 1024; }
    private static void validateBusiness(StorageScope scope) throws IOException {
        var inventory = new JsonInventoryRepository(scope.inventoryFile()).load();
        var sessions = new JsonSessionRepository(scope.sessionsDirectory()).search("");
        if (!inventory.isSuccess()) throw new IOException(String.join("; ", inventory.errors()));
        if (!sessions.isSuccess()) throw new IOException(String.join("; ", sessions.errors()));
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Optional<Registry> read() throws IOException {
        var bytes = AtomicFiles.read(file(), MAX_BYTES); if (bytes.isEmpty()) return Optional.empty();
        try (var reader = new JsonReader(new StringReader(AtomicFiles.utf8(bytes.get())))) {
            reader.setStrictness(Strictness.STRICT); JsonObject document = StrictJson.readElement(reader, 0).getAsJsonObject();
            keys(document, Set.of("schemaVersion", "defaultProfileId", "profiles", "pending"));
            if (reader.peek() != JsonToken.END_DOCUMENT || !document.get("schemaVersion").toString().equals("1")) throw new IOException("Unsupported profile registry schema.");
            UUID defaultId = uuid(string(document, "defaultProfileId")); Set<UUID> ids = new HashSet<>(); List<Entry> entries = new ArrayList<>();
            for (var element : document.getAsJsonArray("profiles")) {
                var entry = element.getAsJsonObject(); keys(entry, Set.of("profile", "archived"));
                var value = entry.get("archived");
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid archive flag.");
                LocalProfile profile = decode(entry.getAsJsonObject("profile"));
                if (!ids.add(profile.id())) throw new IOException("Duplicate profile UUID.");
                entries.add(new Entry(profile, value.getAsBoolean()));
            }
            Pending pending = null;
            if (!document.get("pending").isJsonNull()) {
                var item = document.getAsJsonObject("pending"); keys(item, Set.of("profile", "files"));
                LocalProfile profile = decode(item.getAsJsonObject("profile"));
                if (ids.contains(profile.id())) throw new IOException("Pending profile is already registered.");
                Map<String, String> files = new TreeMap<>();
                for (var key : item.getAsJsonObject("files").keySet()) {
                    String hash = string(item.getAsJsonObject("files"), key);
                    if (!Set.of("inventory.json", "sessions/sessions.json").contains(key) || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid migration manifest.");
                    files.put(key, hash);
                }
                if (!files.isEmpty() && !profile.id().equals(defaultId)) throw new IOException("Only the default profile can import legacy data.");
                pending = new Pending(profile, files);
            }
            Registry state = new Registry(defaultId, List.copyOf(entries), pending);
            if (pending == null || !pending.profile().id().equals(defaultId)) active(state, defaultId);
            else if (!entries.isEmpty()) throw new IOException("Invalid default initialization state.");
            return Optional.of(state);
        } catch (RuntimeException invalid) { throw new IOException("Invalid profile registry; original preserved.", invalid); }
    }
    private void write(Registry state) throws IOException {
        JsonObject document = new JsonObject(); document.addProperty("schemaVersion", 1); document.addProperty("defaultProfileId", state.defaultId().toString());
        JsonArray entries = new JsonArray();
        for (var entry : state.entries()) { JsonObject item = new JsonObject(); item.add("profile", encode(entry.profile())); item.addProperty("archived", entry.archived()); entries.add(item); }
        document.add("profiles", entries); document.add("pending", JsonNull.INSTANCE);
        if (state.pending() != null) {
            JsonObject pending = new JsonObject(); pending.add("profile", encode(state.pending().profile())); pending.add("files", JSON.toJsonTree(state.pending().files())); document.add("pending", pending);
        }
        AtomicFiles.write(file(), "profiles-", JSON.toJson(document).getBytes(StandardCharsets.UTF_8), MAX_BYTES, this::replace);
    }
    protected void replace(Path temporary) throws IOException { AtomicFiles.replace(temporary, file()); }
    private static JsonObject encode(LocalProfile profile) {
        JsonObject value = new JsonObject(); value.addProperty("id", profile.id().toString()); value.addProperty("displayName", profile.displayName());
        value.addProperty("createdAt", profile.createdAt().toString()); value.addProperty("updatedAt", profile.updatedAt().toString()); return value;
    }
    private static LocalProfile decode(JsonObject value) throws IOException {
        keys(value, Set.of("id", "displayName", "createdAt", "updatedAt"));
        return new LocalProfile(uuid(string(value, "id")), string(value, "displayName"), Instant.parse(string(value, "createdAt")), Instant.parse(string(value, "updatedAt")));
    }
    private static void keys(JsonObject value, Set<String> keys) throws IOException { if (!value.keySet().equals(keys)) throw new IOException("Unexpected registry fields."); }
    private static String string(JsonObject value, String key) throws IOException {
        var field = value.get(key); if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw new IOException("Invalid registry string: " + key);
        return field.getAsString();
    }
    private static UUID uuid(String value) throws IOException {
        UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IOException("Noncanonical profile UUID."); return id;
    }
}
