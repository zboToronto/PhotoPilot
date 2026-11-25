package com.photopilot.repository;

import com.google.gson.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow milestone 04 persistence: a recorded F09 environment check round-trips with every field, a record without
 * one keeps exactly the fields (and revision) earlier builds wrote and read, an explicit null is read as none, the check
 * changes only while plans are editable, and an invalid check never reaches or leaves the store.
 */
class EnvironmentCheckPersistenceTest {
    @TempDir Path root;
    static final LocalDate DAY = LocalDate.of(2026, 12, 5);
    static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    static final Instant ASSESSED = Instant.parse("2026-11-22T15:30:12.345Z");
    /** The v1 session fields every build since F07 writes and reads; a record without a check has exactly these. */
    static final Set<String> V1_FIELDS = Set.of("id", "title", "date", "location", "notes", "createdAt", "updatedAt", "state", "requirements", "plans",
        "reviews", "profile", "statistics", "savedIdeas", "selectedLoadout");
    static final Set<String> CHECK_FIELDS = Set.of("assessedAt", "date", "place", "availableFrom", "availableUntil", "windows", "basisLocation", "basisTimeWindow");

    JsonSessionRepository repository() { return new JsonSessionRepository(root.resolve("sessions")); }
    Path file() { return root.resolve("sessions").resolve("sessions.json"); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    JsonObject stored(UUID id) throws IOException {
        for (var entry : JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonArray("sessions"))
            if (entry.getAsJsonObject().get("id").getAsString().equals(id.toString())) return entry.getAsJsonObject();
        throw new AssertionError("not stored: " + id);
    }
    /** Edits one stored record in place, as an older or intermediate build would have written it. */
    void rewrite(UUID id, Consumer<JsonObject> change) throws IOException {
        var document = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        for (var entry : document.getAsJsonArray("sessions"))
            if (entry.getAsJsonObject().get("id").getAsString().equals(id.toString())) change.accept(entry.getAsJsonObject());
        Files.writeString(file(), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(document));
    }

    /** A device place with consent, the reviewed 15:00-19:30 Toronto availability on {@code date}, and the given basis. */
    static EnvironmentCheck check(LocalDate date, Optional<GeoLocation> basis, Optional<String> window, int windows) {
        return new EnvironmentCheck(ASSESSED, date, new GeoLocation("Riverside lookout", 43.6465, -79.4637, true, true, Optional.of(Instant.parse("2026-11-22T15:00:00Z"))),
            ZonedDateTime.of(date, LocalTime.of(15, 0), TORONTO), ZonedDateTime.of(date, LocalTime.of(19, 30), TORONTO), windows, basis, window);
    }
    static PhotographySession draft(Optional<EnvironmentCheck> check) {
        var at = Instant.parse("2026-11-20T10:00:00Z");
        return new PhotographySession(UUID.randomUUID(), "Riverside", DAY, Optional.empty(), "", at, at, new DraftState(), Optional.empty(), List.of(), List.of(),
            Optional.empty(), Optional.empty(), List.of(), Optional.empty(), check);
    }
    /** The caller-built copy of {@code session} with another check (bypassing the model's own guard). */
    static PhotographySession withCheck(PhotographySession s, Optional<EnvironmentCheck> check) {
        return new PhotographySession(s.id(), s.title(), s.date(), s.location(), s.notes(), s.createdAt(), s.updatedAt(), s.state(), s.requirements(), s.plans(),
            s.reviews(), s.profile(), s.statistics(), s.savedIdeas(), s.selectedLoadout(), check).withRevision(s.revision());
    }
    static void assertSamePlace(GeoLocation expected, GeoLocation actual) {
        assertEquals(List.of(expected.label(), expected.latitude(), expected.longitude(), expected.approximate(), expected.fromDevice(), expected.consentGrantedAt()),
            List.of(actual.label(), actual.latitude(), actual.longitude(), actual.approximate(), actual.fromDevice(), actual.consentGrantedAt()));
    }
    static void assertSameCheck(EnvironmentCheck expected, EnvironmentCheck actual) {
        assertEquals(expected.assessedAt(), actual.assessedAt()); assertEquals(expected.date(), actual.date());
        assertSamePlace(expected.place(), actual.place());
        assertEquals(expected.availableFrom(), actual.availableFrom()); assertEquals(expected.availableUntil(), actual.availableUntil());
        assertEquals(expected.availableFrom().getZone(), actual.availableFrom().getZone());
        assertEquals(expected.windows(), actual.windows());
        assertEquals(expected.basisLocation().isPresent(), actual.basisLocation().isPresent());
        expected.basisLocation().ifPresent(place -> assertSamePlace(place, actual.basisLocation().orElseThrow()));
        assertEquals(expected.basisTimeWindow(), actual.basisTimeWindow());
    }

    @Test void aRecordedCheckRoundTripsThroughTheStoreWithEveryField() throws Exception {
        var repository = repository();
        var first = check(DAY, Optional.of(GeoLocation.manual("Riverside")), Optional.of("late afternoon"), 3);
        var inserted = ok(repository.insert(draft(Optional.of(first))));
        assertSameCheck(first, inserted.environmentCheck().orElseThrow());
        var found = ok(repository.find(inserted.id()));
        assertSameCheck(first, found.environmentCheck().orElseThrow());
        assertEquals(inserted.revision(), found.revision());
        assertTrue(found.environmentCheck().orElseThrow().madeUnderRequirement());
        // Exact stored shape: the v1 fields plus the check, which holds identifying metadata only.
        var json = stored(inserted.id());
        var expected = new HashSet<>(V1_FIELDS); expected.add("environmentCheck");
        assertEquals(expected, json.keySet());
        var record = json.getAsJsonObject("environmentCheck");
        assertEquals(CHECK_FIELDS, record.keySet());
        assertEquals("2026-11-22T15:30:12.345Z", record.get("assessedAt").getAsString());
        assertEquals("2026-12-05", record.get("date").getAsString());
        assertEquals("2026-12-05T15:00-05:00[America/Toronto]", record.get("availableFrom").getAsString());
        assertEquals("2026-12-05T19:30-05:00[America/Toronto]", record.get("availableUntil").getAsString());
        assertEquals(3, record.get("windows").getAsInt());
        assertEquals("Riverside lookout", record.getAsJsonObject("place").get("label").getAsString());
        assertEquals("Riverside", record.getAsJsonObject("basisLocation").get("label").getAsString());
        assertEquals("late afternoon", record.get("basisTimeWindow").getAsString());
        // Replaced by a later record made without a confirmed requirement: the empty basis is stored as null.
        var second = check(DAY, Optional.empty(), Optional.empty(), 0);
        var saved = ok(repository.save(ok(found.attachEnvironmentCheck(second))));
        var reread = ok(repository.find(inserted.id()));
        assertSameCheck(second, reread.environmentCheck().orElseThrow());
        assertEquals(saved.revision(), reread.revision()); assertNotEquals(found.revision(), saved.revision());
        assertFalse(reread.environmentCheck().orElseThrow().madeUnderRequirement());
        var replaced = stored(inserted.id()).getAsJsonObject("environmentCheck");
        assertTrue(replaced.get("basisLocation").isJsonNull()); assertTrue(replaced.get("basisTimeWindow").isJsonNull());
        assertEquals(0, replaced.get("windows").getAsInt());
    }

    @Test void aRecordWithoutACheckOmitsTheKeyAndReadsItAbsentOrNull() throws Exception {
        var repository = repository();
        var plain = ok(repository.insert(draft(Optional.empty())));
        var json = stored(plain.id());
        assertEquals(V1_FIELDS, json.keySet(), "exactly the fields an earlier build reads");
        assertTrue(plain.environmentCheck().isEmpty());
        // Decoded with the key absent, or explicitly null; re-encoded without it either way.
        assertTrue(SessionJson.decode(json).environmentCheck().isEmpty());
        var withNull = json.deepCopy(); withNull.add("environmentCheck", JsonNull.INSTANCE);
        var decoded = SessionJson.decode(withNull);
        assertTrue(decoded.environmentCheck().isEmpty());
        assertEquals(V1_FIELDS, SessionJson.encode(decoded).keySet());
        // Through the store: an explicit null loads with the same revision as the record without the key ...
        rewrite(plain.id(), o -> o.add("environmentCheck", JsonNull.INSTANCE));
        var found = ok(repository.find(plain.id()));
        assertTrue(found.environmentCheck().isEmpty()); assertEquals(plain.revision(), found.revision());
        // ... and the next save writes the record without it again.
        ok(repository.save(found.edit("Riverside, renamed", found.date(), found.location(), "")));
        assertEquals(V1_FIELDS, stored(plain.id()).keySet());
        // Strictness is kept: an unknown field, or a check that is not an object, is refused, not dropped.
        rewrite(plain.id(), o -> o.addProperty("environmentChecks", "x"));
        assertTrue(repository.find(plain.id()).hasError("error.session.storage"));
        rewrite(plain.id(), o -> { o.remove("environmentChecks"); o.addProperty("environmentCheck", "x"); });
        assertTrue(repository.find(plain.id()).hasError("error.session.storage"));
    }

    @Test void anUnchangedOlderRecordKeepsItsRevisionWhenAnotherSessionIsSaved() throws Exception {
        var repository = repository();
        var older = ok(repository.insert(draft(Optional.empty())));
        var nulled = ok(repository.insert(draft(Optional.empty())));
        var other = ok(repository.insert(draft(Optional.empty())));
        // One record as an intermediate build wrote it, with an explicit null.
        rewrite(nulled.id(), o -> o.add("environmentCheck", JsonNull.INSTANCE));
        assertEquals(older.revision(), ok(repository.find(older.id())).revision());
        assertEquals(nulled.revision(), ok(repository.find(nulled.id())).revision());
        ok(repository.save(other.edit("Other, renamed", other.date(), other.location(), "")));
        // Rewritten while another session was saved, both keep their content, shape and revision.
        assertEquals(older.revision(), ok(repository.find(older.id())).revision());
        assertEquals(nulled.revision(), ok(repository.find(nulled.id())).revision());
        assertEquals(V1_FIELDS, stored(older.id()).keySet()); assertEquals(V1_FIELDS, stored(nulled.id()).keySet());
        // So the revisions read earlier are still current: neither save is rejected as stale.
        var recorded = ok(repository.save(ok(older.attachEnvironmentCheck(check(DAY, Optional.empty(), Optional.empty(), 1)))));
        assertEquals(1, recorded.environmentCheck().orElseThrow().windows());
        ok(repository.save(nulled.edit("Renamed", nulled.date(), nulled.location(), "")));
    }

    @Test void aCheckIsAddedOrReplacedOnlyWhilePlansAreEditable() throws Exception {
        var repository = repository();
        EnvironmentCheck first = check(DAY, Optional.empty(), Optional.empty(), 2), second = check(DAY, Optional.empty(), Optional.empty(), 1);
        // Draft and Planned sessions: added, then replaced.
        var draft = ok(repository.insert(draft(Optional.empty())));
        var replaced = ok(repository.save(ok(ok(repository.save(ok(draft.attachEnvironmentCheck(first)))).attachEnvironmentCheck(second))));
        assertEquals(1, ok(repository.find(replaced.id())).environmentCheck().orElseThrow().windows());
        var planned = ok(repository.insert(SessionFixtures.linked(new PlannedState(), List.of(), false)));
        var plannedReplaced = ok(repository.save(ok(ok(repository.save(ok(planned.attachEnvironmentCheck(first)))).attachEnvironmentCheck(second))));
        assertEquals(1, ok(repository.find(plannedReplaced.id())).environmentCheck().orElseThrow().windows());
        // Completed and Reviewed sessions: the model refuses, and so does the store for a caller-built copy.
        for (SessionState state : List.of(new CompletedState(), new ReviewedState())) {
            var historical = ok(repository.insert(SessionFixtures.linked(state, List.of(), state instanceof ReviewedState)));
            assertTrue(historical.attachEnvironmentCheck(first).hasError("env.error.historical"), historical.status());
            String before = Files.readString(file());
            assertTrue(repository.save(withCheck(historical, Optional.of(first))).hasError("env.error.historical"), historical.status());
            assertEquals(before, Files.readString(file()), "nothing was written");
        }
        // A historical record keeps the check it has: neither replaced nor removed, while its F01 details stay editable.
        var kept = ok(repository.insert(withCheck(SessionFixtures.linked(new CompletedState(), List.of(), false), Optional.of(first))));
        assertTrue(repository.save(withCheck(kept, Optional.of(second))).hasError("env.error.historical"));
        assertTrue(repository.save(withCheck(kept, Optional.empty())).hasError("env.error.historical"));
        var renamed = ok(repository.save(kept.edit("Linked shoot, renamed", kept.date(), kept.location(), kept.notes())));
        assertSameCheck(first, ok(repository.find(renamed.id())).environmentCheck().orElseThrow());
    }

    @Test void invalidChecksAreRefusedBeforeAndAfterStorage() throws Exception {
        var repository = repository();
        var place = new GeoLocation("Riverside lookout", 43.6465, -79.4637, false, false, Optional.empty());
        var from = ZonedDateTime.of(DAY, LocalTime.of(15, 0), TORONTO);
        assertTrue(new EnvironmentCheck(ASSESSED, DAY, place, from, from.plusHours(4), 2, Optional.empty(), Optional.empty()).validate().isSuccess());
        var invalid = new LinkedHashMap<String, EnvironmentCheck>();
        invalid.put("no coordinates", new EnvironmentCheck(ASSESSED, DAY, GeoLocation.manual("Riverside"), from, from.plusHours(4), 2, Optional.empty(), Optional.empty()));
        invalid.put("ends before it starts", new EnvironmentCheck(ASSESSED, DAY, place, from, from.minusMinutes(1), 2, Optional.empty(), Optional.empty()));
        invalid.put("empty availability", new EnvironmentCheck(ASSESSED, DAY, place, from, from, 2, Optional.empty(), Optional.empty()));
        invalid.put("two time zones", new EnvironmentCheck(ASSESSED, DAY, place, from, from.withZoneSameInstant(ZoneId.of("Europe/London")).plusHours(4), 2, Optional.empty(), Optional.empty()));
        invalid.put("negative windows", new EnvironmentCheck(ASSESSED, DAY, place, from, from.plusHours(4), -1, Optional.empty(), Optional.empty()));
        invalid.put("too many windows", new EnvironmentCheck(ASSESSED, DAY, place, from, from.plusHours(4), 101, Optional.empty(), Optional.empty()));
        invalid.put("invalid basis place", new EnvironmentCheck(ASSESSED, DAY, place, from, from.plusHours(4), 2,
            Optional.of(new GeoLocation(" ", 0, 0, true, false, Optional.empty(), false)), Optional.of("evening")));
        invalid.put("basis text too long", new EnvironmentCheck(ASSESSED, DAY, place, from, from.plusHours(4), 2, Optional.empty(), Optional.of("x".repeat(ShootRequirement.MAX_TEXT + 1))));
        var empty = draft(Optional.empty());
        for (var entry : invalid.entrySet()) {
            var check = entry.getValue();
            assertTrue(check.validate().hasError("env.record.error.invalid"), entry.getKey());
            assertTrue(empty.attachEnvironmentCheck(check).hasError("env.record.error.invalid"), entry.getKey());
            assertFalse(draft(Optional.of(check)).validate().isSuccess(), entry.getKey());
            assertFalse(repository.insert(draft(Optional.of(check))).isSuccess(), entry.getKey());
        }
        assertFalse(Files.exists(file()), "nothing was written");
        // A stored record whose availability was changed on disk to end before it starts is refused on read, not dropped.
        var stored = ok(repository.insert(draft(Optional.of(new EnvironmentCheck(ASSESSED, DAY, place, from, from.plusHours(4), 2, Optional.empty(), Optional.empty())))));
        rewrite(stored.id(), o -> o.getAsJsonObject("environmentCheck").addProperty("availableUntil", from.minusHours(1).toString()));
        assertTrue(repository.find(stored.id()).hasError("error.session.storage"));
    }
}
