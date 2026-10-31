package com.photopilot.repository;

import com.google.gson.*;
import com.google.gson.stream.*;
import com.photopilot.common.Result;
import com.photopilot.model.PhotographySession;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** Atomic aggregate store with per-record optimistic revisions and a cross-process writer lock. */
public class JsonSessionRepository implements SessionRepository {
    private final Path directory;
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    public JsonSessionRepository(Path directory) { this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize(); }
    private Path file() { return directory.resolve("sessions.json"); }

    private List<PhotographySession> read() throws IOException {
        if (Files.notExists(directory)) return new ArrayList<>();
        // An older/unrecognized store must not be mistaken for empty history.
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!Set.of("sessions.json", "sessions.lock").contains(name) && !name.matches("sessions-.*\\.tmp"))
                    throw new IOException("Unrecognized session storage entry: " + name);
            }
        }
        var stored = AtomicFiles.read(file(), MAX_BYTES);
        if (stored.isEmpty()) return new ArrayList<>();
        String source = AtomicFiles.utf8(stored.get());
        try (JsonReader reader = new JsonReader(new StringReader(source))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject root = StrictJson.readElement(reader, 0).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.keySet().equals(Set.of("schemaVersion", "sessions"))
                    || !root.get("schemaVersion").toString().equals("1")) throw new IOException("Unsupported session schema.");
            List<PhotographySession> sessions = new ArrayList<>(); Set<UUID> ids = new HashSet<>();
            for (JsonElement entry : root.getAsJsonArray("sessions")) {
                var session = SessionJson.decode(entry.getAsJsonObject());
                if (!ids.add(session.id())) throw new IOException("Duplicate session ID.");
                sessions.add(session.withRevision(digest(entry)));
            }
            return sessions;
        }
    }
    public Result<PhotographySession> find(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        try { return read().stream().filter(s -> s.id().equals(id)).findFirst()
            .map(Result::success).orElseGet(() -> Result.failureKey("error.session.missing", id)); }
        catch (IOException | RuntimeException error) { return failure(error); }
    }
    public Result<List<PhotographySession>> search(String query) {
        String term = Objects.toString(query, "").strip().toLowerCase(Locale.ROOT);
        try { return Result.success(read().stream().filter(s -> (s.title() + " " + s.date() + " " + s.notes() + " "
                + s.status() + " " + s.location().map(l -> l.label()).orElse("") + " " + s.id()).toLowerCase(Locale.ROOT).contains(term))
            .sorted(Comparator.comparing(PhotographySession::date).reversed().thenComparing(s -> s.id().toString())).toList()); }
        catch (IOException | RuntimeException error) { return failure(error); }
    }
    public Result<PhotographySession> insert(PhotographySession session) {
        if (session == null) return Result.failureKey("error.session.details");
        return change(items -> {
            if (items.stream().anyMatch(s -> s.id().equals(session.id()))) return Result.failureKey("error.session.duplicate", session.id());
            if (!session.revision().isEmpty()) return Result.failureKey("error.session.changed");
            var stored = validatedCopy(session); items.add(stored); return Result.success(stored);
        });
    }
    public Result<PhotographySession> save(PhotographySession session) {
        if (session == null) return Result.failureKey("error.session.details");
        return change(items -> {
            int index = index(items, session.id());
            if (index < 0) return Result.failureKey("error.session.missing", session.id());
            var current = items.get(index);
            if (!current.revision().equals(session.revision())) return Result.failureKey("error.session.changed");
            if (!current.createdAt().equals(session.createdAt()) || session.updatedAt().isBefore(current.updatedAt()))
                return Result.failureKey("error.session.details");
            if (!current.status().equals(session.status())) {
                boolean allowed = Arrays.stream(com.photopilot.state.SessionEvent.values()).anyMatch(event -> {
                    var next = current.state().next(session, event);
                    return next.isSuccess() && next.value().orElseThrow().getClass().equals(session.state().getClass());
                });
                if (!allowed) return Result.failureKey("error.session.transition", current.status(), session.status());
            }
            if (!current.state().canEditPlan() && !SessionJson.encode(current).get("plans").equals(SessionJson.encode(session).get("plans")))
                return Result.failureKey("error.session.historicalPlans");
            var stored = validatedCopy(session); items.set(index, stored); return Result.success(stored);
        });
    }
    public Result<Boolean> delete(UUID id, String revision) {
        if (id == null) return Result.failureKey("error.session.id");
        return change(items -> {
            int index = index(items, id);
            if (index < 0) return Result.failureKey("error.session.missing", id);
            if (!items.get(index).revision().equals(revision)) return Result.failureKey("error.session.changed");
            items.remove(index); return Result.success(true);
        });
    }
    public Result<List<UUID>> referencingGear(UUID id) {
        if (id == null) return Result.failureKey("error.gear.idRequired");
        var sessions = search(""); if (!sessions.isSuccess()) return Result.failure(sessions);
        return Result.success(sessions.value().orElseThrow().stream().filter(s -> s.plans().stream()
            .flatMap(p -> p.loadout().stream()).flatMap(l -> l.gear().stream()).anyMatch(g -> g.sourceGearId().equals(id)))
            .map(PhotographySession::id).toList());
    }
    private static int index(List<PhotographySession> items, UUID id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id().equals(id)) return i;
        return -1;
    }
    private static PhotographySession validatedCopy(PhotographySession session) {
        JsonObject tree = SessionJson.encode(session);
        var restored = SessionJson.decode(tree);
        return restored.withRevision(digest(SessionJson.encode(restored)));
    }
    private <T> Result<T> change(Function<List<PhotographySession>, Result<T>> operation) {
        try {
            return AtomicFiles.locked(directory.resolve("sessions.lock"), () -> {
                List<PhotographySession> items = read();
                var result = operation.apply(items);
                if (result.isSuccess()) write(items);
                return result;
            });
        } catch (AtomicFiles.Busy busy) { return Result.failureKey("error.session.busy"); }
        catch (IOException | RuntimeException error) { return failure(error); }
    }
    private void write(List<PhotographySession> items) throws IOException {
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 1); JsonArray sessions = new JsonArray();
        items.forEach(s -> sessions.add(SessionJson.encode(s))); root.add("sessions", sessions);
        byte[] bytes = SessionJson.JSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(file(), "sessions-", bytes, MAX_BYTES, this::replace);
    }
    /** Fault-injection seam. Unsupported atomic replacement fails without a destructive fallback. */
    protected void replace(Path temporary) throws IOException {
        AtomicFiles.replace(temporary, file());
    }
    private static String digest(JsonElement tree) {
        String text = StrictJson.canonical(tree).toString();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static <T> Result<T> failure(Exception error) {
        return Result.failureKey("error.session.storage", Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
    }
}
