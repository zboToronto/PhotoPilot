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
                // Digest the record as decode validated it (equal to its re-encoding), so rewriting an older record with its
                // additions filled in and sets sorted, e.g. while another session is saved, keeps its revision.
                sessions.add(session.withRevision(digest(SessionJson.normalized(entry.getAsJsonObject()))));
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
    public Result<PhotographySession> save(PhotographySession session) { return store(session, false); }
    @Override public Result<PhotographySession> append(PhotographySession session) { return store(session, true); }
    private Result<PhotographySession> store(PhotographySession session, boolean appending) {
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
            var history = history(current, session, appending);
            if (!history.isSuccess()) return Result.failure(history);
            var stored = validatedCopy(session); items.set(index, stored); return Result.success(stored);
        });
    }
    /**
     * The model's state rules checked against what is actually stored (a caller can build any session object): plan and
     * review history only grows, by one approved plan of an editable session or one saved review of a completed session
     * per append; requirement, profile, selected loadout, environment check and saved ideas change only while plans are editable; statistics
     * only while the session is completed.
     */
    private static Result<Boolean> history(PhotographySession current, PhotographySession session, boolean appending) {
        JsonObject before = SessionJson.encode(current), after = SessionJson.encode(session);
        boolean editable = current.state().canEditPlan(), completed = current.state() instanceof com.photopilot.state.CompletedState;
        JsonArray plans = before.getAsJsonArray("plans"), newPlans = after.getAsJsonArray("plans");
        JsonArray reviews = before.getAsJsonArray("reviews"), newReviews = after.getAsJsonArray("reviews");
        if (!editable && !plans.equals(newPlans)) return Result.failureKey("error.session.historicalPlans");
        int added = newPlans.size() - plans.size() + newReviews.size() - reviews.size();
        if (!prefix(plans, newPlans) || !prefix(reviews, newReviews) || added != (appending ? 1 : 0)) return Result.failureKey("error.session.history");
        if (newPlans.size() > plans.size() && !session.plans().getLast().approved()) return Result.failureKey("error.session.history");
        if (newReviews.size() > reviews.size() && (!completed || !session.reviews().getLast().status().equals(com.photopilot.model.PostShootReview.SAVED)))
            return Result.failureKey("error.session.history");
        if (!editable) {
            var historical = new LinkedHashMap<String, String>();
            historical.put("requirements", "requirement.error.historical"); historical.put("profile", "needs.error.historical");
            historical.put("selectedLoadout", "match.error.historical"); historical.put("savedIdeas", "discover.error.historical");
            historical.put("environmentCheck", "env.error.historical");
            for (var field : historical.entrySet()) if (!Objects.equals(before.get(field.getKey()), after.get(field.getKey()))) return Result.failureKey(field.getValue());
        }
        if (!completed && !before.get("statistics").equals(after.get("statistics")))
            return current.state() instanceof com.photopilot.state.ReviewedState ? Result.failureKey("review.error.reviewedStatistics") : Result.failureKey("review.error.notCompleted", current.status());
        return Result.success(true);
    }
    private static boolean prefix(JsonArray stored, JsonArray next) {
        if (next.size() < stored.size()) return false;
        for (int i = 0; i < stored.size(); i++) if (!stored.get(i).equals(next.get(i))) return false;
        return true;
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
        return Result.success(sessions.value().orElseThrow().stream().filter(s -> java.util.stream.Stream.concat(s.plans().stream()
            .flatMap(p -> p.loadout().stream()), s.selectedLoadout().stream()).flatMap(l -> l.gear().stream()).anyMatch(g -> g.sourceGearId().equals(id)))
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
    /** Every write path; announced to the running operation (if any), which records SAVED only on a confirmed write. */
    private <T> Result<T> change(Function<List<PhotographySession>, Result<T>> operation) {
        return com.photopilot.progress.Progress.write(() -> locked(operation));
    }
    private <T> Result<T> locked(Function<List<PhotographySession>, Result<T>> operation) {
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
