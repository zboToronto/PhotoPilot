package com.photopilot.repository;

import com.google.gson.*;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.service.SessionService;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Session store integrity: records load in every JVM run whatever order a set was written in, a returned revision is
 * the one the next read computes, and the stored plan/review history and historical records cannot be rewritten by a
 * caller-built session object (only {@code append} grows history, by one approved plan or one saved review).
 */
class SessionHistoryIntegrityTest {
    @TempDir Path root;
    JsonSessionRepository repository() { return new JsonSessionRepository(root.resolve("sessions")); }
    Path file() { return root.resolve("sessions").resolve("sessions.json"); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static PhotographySession with(PhotographySession s, Optional<ShootRequirement> requirement, List<ShootPlan> plans, List<PostShootReview> reviews, Optional<ExifStatistics> statistics) {
        return new PhotographySession(s.id(), s.title(), s.date(), s.location(), s.notes(), s.createdAt(), s.updatedAt(), s.state(), requirement, plans, reviews,
            s.profile(), statistics, s.savedIdeas(), s.selectedLoadout()).withRevision(s.revision());
    }
    static ShootPlan approvedPlan() {
        return new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.empty(), Map.of("iso", "200"), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }
    static PostShootReview review(UUID id, PhotographySession session, List<String> lessons) {
        return new PostShootReview(id, Instant.parse("2026-11-02T10:00:00Z"), session.statistics().orElseThrow(), session.plans().getFirst().id(), 1, List.of(), lessons,
            List.of("Fixture"), Optional.empty(), "Summary", List.of(), List.of(), List.of(), List.of("Arrive earlier"), List.of(), PostShootReview.SAVED);
    }

    @Test void verifiedFieldsLoadInEveryStoredOrderAndAreWrittenSorted() throws Exception {
        var gear = new GearSnapshot(UUID.randomUUID(), "CAMERA", "Sony", "A1", 1, Instant.parse("2026-11-01T10:00:00Z"), Map.of("mount", "Sony E"),
            Set.of("weightGrams", "mount", "model"), Map.of());
        var repository = repository();
        var inserted = ok(repository.insert(SessionFixtures.linked(new PlannedState(), List.of(gear), false)));
        var root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        var gearJson = root.getAsJsonArray("sessions").get(0).getAsJsonObject().getAsJsonArray("plans").get(0).getAsJsonObject()
            .getAsJsonObject("loadout").getAsJsonArray("gear").get(0).getAsJsonObject();
        assertEquals("[\"model\",\"mount\",\"weightGrams\"]", gearJson.get("verifiedFields").toString(), "written in one order in every run");
        String revision = null;
        // Files written before the fix hold the writing JVM's arbitrary set order; every order must load.
        for (var order : List.of(List.of("model", "mount", "weightGrams"), List.of("model", "weightGrams", "mount"), List.of("mount", "model", "weightGrams"),
                List.of("mount", "weightGrams", "model"), List.of("weightGrams", "model", "mount"), List.of("weightGrams", "mount", "model"))) {
            var array = new JsonArray(); order.forEach(array::add); gearJson.add("verifiedFields", array);
            Files.writeString(file(), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(root));
            var found = repository.find(inserted.id());
            assertTrue(found.isSuccess(), () -> order + " " + found.errors());
            assertEquals(Set.of("model", "mount", "weightGrams"), found.value().orElseThrow().plans().getFirst().loadout().orElseThrow().gear().getFirst().verifiedFields());
            if (revision == null) revision = found.value().orElseThrow().revision();
            else assertEquals(revision, found.value().orElseThrow().revision(), "set order is not content");
        }
        // Saving a record that was stored unsorted keeps working and rewrites the array sorted.
        var service = new SessionService(repository);
        ok(service.update(ok(service.open(inserted.id())), "Renamed", inserted.date(), inserted.location(), inserted.notes()));
        assertTrue(Files.readString(file()).replaceAll("\\s", "").contains("\"verifiedFields\":[\"model\",\"mount\",\"weightGrams\"]"));
    }

    @Test void anOlderRecordKeepsItsRevisionWhenAnotherSessionIsSaved() throws Exception {
        var repository = repository(); var service = new SessionService(repository);
        var older = ok(service.create("Older record", LocalDate.of(2026, 11, 20)));
        var other = ok(service.create("Other record", LocalDate.of(2026, 11, 21)));
        // A record written before F07 has no selectedLoadout field; decode fills it in, and any later write stores it.
        var root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        for (var entry : root.getAsJsonArray("sessions"))
            if (entry.getAsJsonObject().get("id").getAsString().equals(older.id().toString())) entry.getAsJsonObject().remove("selectedLoadout");
        Files.writeString(file(), new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(root));
        var opened = ok(service.open(older.id()));
        ok(service.update(ok(service.open(other.id())), "Other record, renamed", other.date(), other.location(), ""));
        var rewritten = JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonArray("sessions").asList().stream()
            .map(JsonElement::getAsJsonObject).filter(o -> o.get("id").getAsString().equals(older.id().toString())).findFirst().orElseThrow();
        assertTrue(rewritten.has("selectedLoadout"), "the older record was rewritten with the field");
        assertEquals(opened.revision(), ok(service.open(older.id())).revision(), "rewriting unchanged content keeps the revision");
        ok(service.update(opened, "Older record, renamed", opened.date(), opened.location(), ""));
    }

    @Test void theRevisionReturnedBySaveIsTheOneTheNextReadComputes() {
        var service = new SessionService(repository());
        // -0.0005 is written as -5.0E-4: decoded from disk it is a BigDecimal, encoded a Double.
        var place = Optional.of(new GeoLocation("Greenwich", 51.4779, -0.0005, false, false, Optional.empty()));
        var created = ok(service.create("Meridian", LocalDate.of(2026, 11, 20), place, ""));
        assertEquals(ok(service.open(created.id())).revision(), created.revision());
        var edited = ok(service.update(created, "Meridian line", created.date(), created.location(), ""));
        assertEquals(ok(service.open(created.id())).revision(), edited.revision());
        var again = ok(service.update(edited, "Meridian line again", edited.date(), edited.location(), "Reusing the returned session"));
        assertEquals("Meridian line again", ok(service.open(created.id())).title(), "a returned session can be saved again");
        assertEquals(ok(service.open(created.id())).revision(), again.revision());
    }

    @Test void plainSavesNeverChangeThePlanHistory() throws Exception {
        var repository = repository(); var service = new SessionService(repository);
        var planned = ok(repository.insert(SessionFixtures.linked(new PlannedState(), List.of(), false)));
        String before = Files.readString(file());
        var appended = ok(planned.appendApprovedPlan(approvedPlan()));
        assertTrue(repository.save(appended).hasError("error.session.history"));
        assertTrue(service.save(appended).hasError("error.session.history"));
        assertTrue(service.update(appended, "Forged", planned.date(), planned.location(), "").hasError("error.session.history"), "an expected session carries no new plans");
        assertTrue(service.save(with(planned, planned.requirements(), List.of(approvedPlan()), List.of(), planned.statistics())).hasError("error.session.history"), "earlier revisions cannot be replaced");
        assertEquals(before, Files.readString(file()), "nothing was written");
        // A Draft session cannot reach Planned with a plan that never went through approval.
        var draft = ok(service.create("Draft", LocalDate.of(2026, 11, 20)));
        var promoted = ok(ok(draft.appendApprovedPlan(approvedPlan())).apply(com.photopilot.state.SessionEvent.PLAN_APPROVED));
        assertTrue(service.save(promoted).hasError("error.session.history"));
        assertEquals("DRAFT", ok(service.open(draft.id())).status());
        // The sanctioned path grows the history by exactly one approved plan.
        assertEquals(2, ok(repository.append(appended)).plans().size());
        var current = ok(service.open(planned.id()));
        var two = ok(ok(current.appendApprovedPlan(approvedPlan())).appendApprovedPlan(approvedPlan()));
        assertTrue(repository.append(two).hasError("error.session.history"), "one record per append");
        assertTrue(repository.append(current).hasError("error.session.history"), "an append must add a record");
    }

    @Test void reviewsOnlyGrowThroughAppendAndHistoricalRecordsStayFixed() throws Exception {
        var repository = repository(); var service = new SessionService(repository);
        var completed = ok(repository.insert(SessionFixtures.linked(new CompletedState(), List.of(), false)));
        var reviewed = ok(ok(completed.appendReview(review(UUID.randomUUID(), completed, List.of("Lesson")))).apply(com.photopilot.state.SessionEvent.REVIEW_SAVED));
        assertTrue(service.save(reviewed).hasError("error.session.history"), "a saved review comes only from F11 review saving");
        var landscape = Optional.of(new ShootRequirement("Brief", "Landscape", List.of("Light"), Optional.empty(), Optional.empty(), Duration.ofHours(1), 1, true,
            Optional.empty(), List.of(), Map.of(), Map.of(), List.of(), true));
        assertTrue(service.save(with(completed, landscape, completed.plans(), List.of(), completed.statistics())).hasError("requirement.error.historical"));
        var stored = ok(repository.append(reviewed));
        assertEquals("REVIEWED", stored.status());
        String before = Files.readString(file());
        var replaced = review(stored.reviews().getFirst().id(), stored, List.of("A different lesson"));
        assertTrue(service.save(with(stored, stored.requirements(), stored.plans(), List.of(replaced), stored.statistics())).hasError("error.session.history"));
        var otherStats = new ExifStatistics(2, 2, Map.of(), Map.of(85.0, 2), Map.of(), Map.of(), Map.of(200, 2), Map.of(), Map.of(), List.of("Other evidence"));
        assertTrue(service.save(with(stored, stored.requirements(), stored.plans(), stored.reviews(), Optional.of(otherStats))).hasError("review.error.reviewedStatistics"));
        assertEquals(before, Files.readString(file()), "a reviewed session keeps its history and evidence");
        // F01 details stay editable.
        assertEquals("Renamed shoot", ok(service.update(stored, "Renamed shoot", stored.date(), stored.location(), stored.notes())).title());
    }
}
