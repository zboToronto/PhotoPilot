package com.photopilot.session;

import com.google.gson.*;
import com.photopilot.application.*;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.repository.*;
import com.photopilot.service.SessionService;
import com.photopilot.state.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {
    @TempDir Path root;
    Path directory() { return root.resolve("sessions"); }
    Path file() { return directory().resolve("sessions.json"); }
    JsonSessionRepository repository() { return new JsonSessionRepository(directory()); }
    SessionService service() { return new SessionService(repository()); }
    PhotographySession create(String title) {
        var result = service().create(title, LocalDate.of(2026, 11, 1), Optional.of(GeoLocation.manual("公园")), "Goals\n第二行");
        assertTrue(result.isSuccess(), result.errors().toString()); return result.value().orElseThrow();
    }
    @Test void createPersistsUniqueIdsAndRestoresMetadataAfterRestart() {
        var first = create("Golden hour"); var second = create("Golden hour");
        assertNotEquals(first.id(), second.id()); assertTrue(Files.exists(file()));
        var restored = service().open(first.id()).value().orElseThrow();
        assertEquals(first.title(), restored.title()); assertEquals(first.notes(), restored.notes());
        assertEquals(first.createdAt(), restored.createdAt()); assertEquals(first.updatedAt(), restored.updatedAt());
        assertEquals("DRAFT", restored.status()); assertEquals("公园", restored.location().orElseThrow().label());
        assertTrue(restored.location().orElseThrow().latitude().isEmpty());
    }
    @Test void editingPreservesIdentityAndCreationTimeAndPersists() {
        var original = create("First");
        var saved = service().update(original, "New title", LocalDate.of(2026, 12, 2), Optional.empty(), "New goals");
        assertTrue(saved.isSuccess(), saved.errors().toString());
        var restored = service().open(original.id()).value().orElseThrow();
        assertEquals("New title", restored.title()); assertEquals("New goals", restored.notes()); assertTrue(restored.location().isEmpty());
        assertEquals(original.createdAt(), restored.createdAt()); assertTrue(restored.updatedAt().isAfter(original.updatedAt()));
    }
    @Test void deleteRequiresConfirmationAndChecksRevision() {
        var original = create("Delete"); assertFalse(service().delete(original, false).isSuccess());
        var changed = service().update(original, "New", original.date(), original.location(), "").value().orElseThrow();
        assertTrue(service().delete(original, true).hasError("error.session.changed"));
        assertTrue(service().delete(changed, true).isSuccess()); assertFalse(service().open(original.id()).isSuccess());
        assertFalse(service().delete(changed, true).isSuccess()); assertTrue(service().search("").value().orElseThrow().isEmpty());
    }
    @Test void searchCombinesTitleGoalsLocationDateAndExactStatusFilters() {
        create("Sunset"); create("Portrait");
        assertEquals(1, service().search("sUnSeT").value().orElseThrow().size());
        assertEquals(2, service().search("公园", "DRAFT", LocalDate.of(2026,11,1)).value().orElseThrow().size());
        assertEquals(2, service().search("第二行").value().orElseThrow().size());
        assertTrue(service().search("", "COMPLETED", null).value().orElseThrow().isEmpty());
        assertTrue(service().search("", "DRAFT", LocalDate.of(2025,1,1)).value().orElseThrow().isEmpty());
        assertFalse(service().search("", "CANCELLED", null).isSuccess());
    }
    @Test void invalidInputsAndNonexistentUpdatesDoNotWriteData() {
        assertFalse(service().create(" ", LocalDate.now()).isSuccess());
        assertFalse(service().create("Title", null).isSuccess()); assertFalse(service().create(null, LocalDate.now()).isSuccess());
        assertFalse(service().save(null).isSuccess()); assertFalse(service().open(null).isSuccess());
        assertFalse(service().open(UUID.randomUUID()).isSuccess());
        assertFalse(SessionForm.parse(Map.of("title", "Title", "date", "2026-02-30"), null).isSuccess());
        assertThrows(IllegalArgumentException.class, () -> SessionForm.id("1-1-1-1-1"));
        assertFalse(Files.exists(file()));
        var unpersisted = SessionFixtures.linked(new DraftState(), List.of(), false);
        assertFalse(service().save(unpersisted).isSuccess()); assertFalse(Files.exists(file()));
    }
    @Test void duplicateIdsCannotOverwriteExistingSession() throws Exception {
        var original = create("Keep"); String before = Files.readString(file());
        assertTrue(repository().insert(original.withRevision("")).hasError("error.session.duplicate"));
        assertEquals(before, Files.readString(file()));
    }
    @Test void staleEditsFailWithoutOverwritingOtherSessions() {
        var first = create("A"); var second = create("B");
        var stale = service().open(first.id()).value().orElseThrow();
        assertTrue(service().update(first, "A changed", first.date(), first.location(), "").isSuccess());
        assertTrue(service().update(stale, "Lost update", stale.date(), stale.location(), "").hasError("error.session.changed"));
        assertTrue(service().update(second, "B changed", second.date(), second.location(), "").isSuccess());
        assertEquals("A changed", service().open(first.id()).value().orElseThrow().title());
    }
    @Test void statePatternGuardsEventsAndPersistsValidTransitions() {
        var draft = create("Bare draft"); assertFalse(service().transition(draft.id(), SessionEvent.PLAN_APPROVED).isSuccess());
        assertFalse(service().transition(draft.id(), SessionEvent.SHOOT_COMPLETED).isSuccess());
        assertFalse(service().transition(draft.id(), null).isSuccess()); assertEquals("DRAFT", draft.status());
        var linked = repository().insert(SessionFixtures.linked(new DraftState(), List.of(), false)).value().orElseThrow();
        assertEquals("PLANNED", service().transition(linked.id(), SessionEvent.PLAN_APPROVED).value().orElseThrow().status());
        assertEquals("COMPLETED", service().transition(linked.id(), SessionEvent.SHOOT_COMPLETED).value().orElseThrow().status());
        assertFalse(service().transition(linked.id(), SessionEvent.REVIEW_SAVED).isSuccess());
        assertFalse(service().open(linked.id()).value().orElseThrow().state().canEditPlan());
        var reviewed = repository().insert(SessionFixtures.linked(new CompletedState(), List.of(), true)).value().orElseThrow();
        assertEquals("REVIEWED", service().transition(reviewed.id(), SessionEvent.REVIEW_SAVED).value().orElseThrow().status());
        for (var event : SessionEvent.values()) assertFalse(service().transition(reviewed.id(), event).isSuccess());
        assertFalse(new ReviewedState().canEditPlan()); assertTrue(new DraftState().canEditPlan()); assertTrue(new PlannedState().canEditPlan());
    }
    @Test void linkedRecordsAndCalculatorInfinitySurviveMetadataEditing() throws Exception {
        var inserted = repository().insert(SessionFixtures.linked(new CompletedState(), List.of(), true));
        assertTrue(inserted.isSuccess(), inserted.errors().toString());
        var session = inserted.value().orElseThrow();
        JsonObject before = JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonArray("sessions").get(0).getAsJsonObject();
        assertTrue(service().update(session, "Edited linked session", session.date(), session.location(), "Edited").isSuccess());
        JsonObject after = JsonParser.parseString(Files.readString(file())).getAsJsonObject().getAsJsonArray("sessions").get(0).getAsJsonObject();
        for (String key : List.of("requirements", "plans", "reviews", "profile", "statistics", "savedIdeas")) assertEquals(before.get(key), after.get(key), key);
        assertTrue(Files.readString(file()).contains("Infinity")); assertEquals(1, service().open(session.id()).value().orElseThrow().reviews().size());
    }
    @Test void missingStoreIsEmptyAndDoesNotCreateFilesOnRead() {
        assertTrue(service().search("").value().orElseThrow().isEmpty());
        assertTrue(repository().referencingGear(UUID.randomUUID()).value().orElseThrow().isEmpty()); assertFalse(Files.exists(directory()));
    }
    @Test void malformedUnknownDuplicateOrIncompleteDataIsPreserved() throws Exception {
        create("Preserve"); String valid = Files.readString(file());
        for (String invalid : List.of("", "{broken", valid + "{}", valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                valid.replace("\"title\": \"Preserve\"", "\"title\": \"Preserve\", \"title\": \"Duplicate\""),
                valid.replace("\"state\": \"DRAFT\"", "\"state\": \"CANCELLED\""),
                valid.replace("2026-11-01", "2026-02-30"), valid.replace("\"notes\":", "\"unknownNotes\":"))) {
            Files.writeString(file(), invalid); assertFalse(service().search("").isSuccess(), invalid);
            assertFalse(service().create("New", LocalDate.now()).isSuccess()); assertEquals(invalid, Files.readString(file()));
        }
    }
    @Test void atomicReplacementFailureLeavesBaseAndCallerUnchanged() throws Exception {
        var original = create("Base"); String before = Files.readString(file());
        var failing = new SessionService(new JsonSessionRepository(directory()) {
            @Override protected void replace(Path temporary) throws IOException { throw new IOException("Injected disk failure"); }
        });
        assertFalse(failing.update(original, "Unsaved", original.date(), original.location(), "Draft").isSuccess());
        assertFalse(failing.delete(original, true).isSuccess()); assertEquals(before, Files.readString(file())); assertEquals("Base", original.title());
        try (var entries = Files.list(directory())) { assertFalse(entries.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void lockedStorageReturnsBusyWithoutChangingData() throws Exception {
        var original = create("Locked"); String before = Files.readString(file());
        try (var channel = FileChannel.open(directory().resolve("sessions.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertTrue(service().delete(original, true).hasError("error.session.busy"));
        }
        assertEquals(before, Files.readString(file()));
    }
    @Test void unexpectedStoreAndNonDirectoryPathFailSafely() throws Exception {
        Files.createDirectories(directory()); Files.writeString(directory().resolve("legacy.json"), "old history");
        assertFalse(service().search("").isSuccess()); assertFalse(repository().referencingGear(UUID.randomUUID()).isSuccess());
        Path blocked = root.resolve("blocked"); Files.writeString(blocked, "keep");
        assertFalse(new SessionService(new JsonSessionRepository(blocked)).create("Title", LocalDate.now()).isSuccess());
        assertEquals("keep", Files.readString(blocked));
    }
    @Test void manualLocationsAndDeviceConsentAreValidated() {
        assertTrue(GeoLocation.manual("Park").validate().isSuccess());
        assertFalse(new GeoLocation("Park", 91, 0, false, false, Optional.empty()).validate().isSuccess());
        assertFalse(new GeoLocation("Park", 1, 1, false, true, Optional.empty()).validate().isSuccess());
        assertTrue(new GeoLocation("Park", 1, 1, false, true, Optional.of(Instant.now())).validate().isSuccess());
    }
    @Test void directSaveCannotSkipStateTransitionsOrRewriteHistoricalPlans() {
        var initial = repository().insert(SessionFixtures.linked(new DraftState(), List.of(), true)).value().orElseThrow();
        var forged = new PhotographySession(initial.id(), initial.title(), initial.date(), initial.location(), initial.notes(), initial.createdAt(), initial.updatedAt(),
            new ReviewedState(), initial.requirements(), initial.plans(), initial.reviews(), initial.profile(), initial.statistics(), initial.savedIdeas()).withRevision(initial.revision());
        assertFalse(service().save(forged).isSuccess()); assertEquals("DRAFT", service().open(initial.id()).value().orElseThrow().status());
        service().transition(initial.id(), SessionEvent.PLAN_APPROVED);
        var completed = service().transition(initial.id(), SessionEvent.SHOOT_COMPLETED).value().orElseThrow();
        var replacementPlans = SessionFixtures.linked(new DraftState(), List.of(), true).plans();
        var rewritten = new PhotographySession(completed.id(), completed.title(), completed.date(), completed.location(), completed.notes(), completed.createdAt(), completed.updatedAt(),
            completed.state(), completed.requirements(), replacementPlans, completed.reviews(), completed.profile(), completed.statistics(), completed.savedIdeas()).withRevision(completed.revision());
        assertTrue(service().save(rewritten).hasError("error.session.historicalPlans"));
    }
    @Test void duplicateStoredIdsAndInvalidGearSnapshotsFailWithoutDataLoss() throws Exception {
        var gear = new GearSnapshot(UUID.randomUUID(), "CAMERA", "Brand", "Model", 1, Instant.now(), Map.of(), Set.of(), Map.of());
        var inserted = repository().insert(SessionFixtures.linked(new PlannedState(), List.of(gear), false));
        assertTrue(inserted.isSuccess(), inserted.errors().toString());
        var restoredGear = service().open(inserted.value().orElseThrow().id()).value().orElseThrow().plans().getFirst().loadout().orElseThrow().gear().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> restoredGear.specs().put("mount", "X"));
        String valid = Files.readString(file());
        JsonObject document = JsonParser.parseString(valid).getAsJsonObject(); var array = document.getAsJsonArray("sessions"); array.add(array.get(0).deepCopy());
        Files.writeString(file(), document.toString()); assertFalse(service().search("").isSuccess());
        String invalid = valid.replace("\"selectedQuantity\": 1", "\"selectedQuantity\": 0"); Files.writeString(file(), invalid);
        assertFalse(repository().referencingGear(gear.sourceGearId()).isSuccess()); assertEquals(invalid, Files.readString(file()));
    }
    @Test void oversizedAndInvalidUtf8FilesAreRejectedWithoutReplacement() throws Exception {
        Files.createDirectories(directory()); byte[] bad = {(byte)0xc3, (byte)0x28}; Files.write(file(), bad);
        assertFalse(service().create("Valid", LocalDate.now()).isSuccess()); assertArrayEquals(bad, Files.readAllBytes(file()));
        try (var channel = FileChannel.open(file(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(20 * 1024 * 1024); channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        assertFalse(service().search("").isSuccess()); assertFalse(service().create("Valid", LocalDate.now()).isSuccess());
        assertEquals(20 * 1024 * 1024 + 1, Files.size(file()));
    }
    @Test void actualGearReferencesWarnAndConfirmedRemovalPreservesHistory() throws Exception {
        try (var bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()))) {
            var parsed = EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(), Map.of("brand", "Brand", "model", "Model", "mount", "E"), null);
            var gear = bootstrap.facade().inventory().add(parsed.value().orElseThrow(), false).value().orElseThrow();
            var session = repository().insert(SessionFixtures.linked(new PlannedState(), List.of(gear.snapshot()), false)).value().orElseThrow();
            assertEquals(List.of(session.id()), bootstrap.facade().inventory().affectedSessions(gear.id()).value().orElseThrow());
            String before = Files.readString(file()); assertFalse(bootstrap.facade().inventory().remove(gear.id(), false).isSuccess());
            assertTrue(bootstrap.facade().inventory().remove(gear.id(), true).isSuccess()); assertEquals(before, Files.readString(file()));
            assertEquals(gear.model(), service().open(session.id()).value().orElseThrow().plans().getFirst().loadout().orElseThrow().gear().getFirst().model());
        }
    }
}
