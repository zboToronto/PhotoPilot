package com.photopilot.repository;

import com.google.gson.*;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceReadinessTest {
    @TempDir Path root;
    static <T> T ok(Result<T> result) {
        assertTrue(result.isSuccess(), result.errors().toString());
        return result.value().orElseThrow();
    }
    PhotographySession linked() { return SessionFixtures.linked(new ReviewedState(), List.of(), true); }
    JsonSessionRepository repository() { return new JsonSessionRepository(root.resolve("sessions")); }

    @Test void pristinePreviousVersionFileLoadsWithoutRewritingAndCanBeEdited() throws Exception {
        Path file = root.resolve("sessions/sessions.json"); Files.createDirectories(file.getParent());
        try (var input = getClass().getResourceAsStream("/persistence/legacy-v1-sessions.json")) {
            Files.copy(Objects.requireNonNull(input), file);
        }
        byte[] before = Files.readAllBytes(file);
        var session = ok(repository().search("")).getFirst();
        assertEquals("REVIEWED", session.status()); assertEquals(1, session.reviews().size());
        assertEquals(Double.POSITIVE_INFINITY, session.plans().getFirst().calculations().getFirst().values().get("far"));
        assertArrayEquals(before, Files.readAllBytes(file));
        var saved = ok(repository().save(session.edit("Edited legacy session", session.date(), session.location(), "Notes")));
        assertEquals(session.id(), saved.id()); assertEquals(session.createdAt(), saved.createdAt());
        assertEquals(saved.revision(), ok(repository().find(session.id())).revision());
        assertEquals(SessionJson.encode(session).get("plans"), SessionJson.encode(saved).get("plans"));
    }

    @Test void restoredCalculationEvidenceUsesDefensiveCopies() {
        var restored = SessionJson.decode(SessionJson.encode(linked()));
        var evidence = restored.plans().getFirst().calculations().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> evidence.values().put("far", 0.0));
        assertThrows(UnsupportedOperationException.class, () -> evidence.units().put("far", "mm"));
        assertThrows(UnsupportedOperationException.class, () -> evidence.input().parameters().put("focal", 1.0));
        assertThrows(UnsupportedOperationException.class, () -> evidence.localizedAssumptions().clear());
        assertThrows(UnsupportedOperationException.class, () -> restored.plans().clear());
    }

    @Test void savedRevisionMatchesStoredContentAfterConstructorCopiesReorderMaps() {
        var tree = SessionJson.encode(linked());
        JsonObject settings = tree.getAsJsonArray("plans").get(0).getAsJsonObject().getAsJsonObject("settings");
        for (int i = 0; i < 20; i++) settings.addProperty("key" + i, "value" + i);
        var saved = ok(repository().insert(SessionJson.decode(tree)));
        assertEquals(saved.revision(), ok(repository().find(saved.id())).revision());
        for (int i = 0; i < 3; i++) {
            saved = ok(repository().save(saved.edit("Edit " + i, saved.date(), saved.location(), saved.notes())));
            assertEquals(saved.revision(), ok(repository().find(saved.id())).revision());
        }
    }

    @Test void optionalValuesAndNonemptyNestedModelsRoundTripWithoutImplicitDefaults() {
        var tree = SessionJson.encode(linked());
        var needs = tree.getAsJsonObject("profile").getAsJsonArray("needs");
        needs.add(SessionJson.JSON.toJsonTree(new CapabilityNeed("focal", Optional.of(24.0), Optional.of(70.0), "mm", 1, true, "Range")));
        var requirement = tree.getAsJsonObject("requirements");
        requirement.addProperty("time", ZonedDateTime.parse("2026-11-01T10:00:00-05:00[America/Toronto]").toString());
        requirement.addProperty("maxWeightGrams", 1200.0);
        requirement.add("location", SessionJson.JSON.toJsonTree(new GeoLocation("Place", 43, -79, false, true,
            Optional.of(Instant.parse("2026-10-31T10:00:00Z")))));
        assertEquals(tree, SessionJson.encode(SessionJson.decode(tree)));
        var empty = new com.photopilot.service.SessionService(repository()).create("Empty", LocalDate.of(2026, 11, 1));
        assertTrue(empty.isSuccess()); assertTrue(empty.value().orElseThrow().location().isEmpty());
    }

    @Test void invalidStoredRelationshipsAndNumbersAreRejectedWithoutReplacingFile() throws Exception {
        List<Consumer<JsonObject>> corruptions = List.of(
            o -> o.getAsJsonArray("plans").add(o.getAsJsonArray("plans").get(0).deepCopy()),
            o -> o.getAsJsonArray("reviews").add(o.getAsJsonArray("reviews").get(0).deepCopy()),
            o -> o.getAsJsonArray("reviews").get(0).getAsJsonObject().addProperty("comparedPlanId", UUID.randomUUID().toString()),
            o -> o.getAsJsonArray("reviews").get(0).getAsJsonObject().addProperty("comparedRevision", 2),
            o -> o.getAsJsonArray("plans").get(0).getAsJsonObject().addProperty("revision", 0),
            o -> o.getAsJsonArray("plans").get(0).getAsJsonObject().getAsJsonArray("provisionalReasons").add("Unverified"),
            o -> o.getAsJsonObject("statistics").addProperty("usableCount", 2),
            o -> o.getAsJsonObject("statistics").getAsJsonObject("focalLengthCounts").addProperty("50.0", -1),
            o -> o.getAsJsonObject("statistics").getAsJsonObject("focalLengthCounts").addProperty("Infinity", 1),
            o -> o.getAsJsonObject("requirements").addProperty("cameraCount", 0),
            o -> o.getAsJsonObject("requirements").addProperty("duration", "PT-1H"),
            o -> o.getAsJsonArray("savedIdeas").get(0).getAsJsonObject().addProperty("estimatedTravelKm", -1),
            o -> o.remove("requirements"),
            o -> o.add("plans", JsonNull.INSTANCE),
            o -> o.addProperty("unknownFutureField", true));
        Path file = root.resolve("sessions/sessions.json"); Files.createDirectories(file.getParent());
        for (Consumer<JsonObject> corrupt : corruptions) {
            var session = SessionJson.encode(linked()); corrupt.accept(session);
            var document = new JsonObject(); document.addProperty("schemaVersion", 1);
            var sessions = new JsonArray(); sessions.add(session); document.add("sessions", sessions);
            String source = document.toString(); Files.writeString(file, source);
            assertFalse(repository().search("").isSuccess(), source);
            assertFalse(repository().insert(linked()).isSuccess());
            assertEquals(source, Files.readString(file));
        }
    }

    @Test void domainValidationRejectsDuplicateOrForeignReviewLinksBeforeStorage() {
        var session = linked(); var plans = new ArrayList<>(session.plans()); plans.add(plans.getFirst());
        var duplicate = new PhotographySession(session.id(), session.title(), session.date(), session.location(), session.notes(),
            session.createdAt(), session.updatedAt(), session.state(), session.requirements(), plans, session.reviews(),
            session.profile(), session.statistics(), session.savedIdeas());
        assertTrue(duplicate.validate().hasError("error.session.links"));
        var foreign = linked();
        var badReview = new PhotographySession(session.id(), session.title(), session.date(), session.location(), session.notes(),
            session.createdAt(), session.updatedAt(), session.state(), session.requirements(), session.plans(), foreign.reviews(),
            session.profile(), session.statistics(), session.savedIdeas());
        assertTrue(badReview.validate().hasError("error.session.links"));
        assertTrue(new com.photopilot.service.SessionService(repository()).save(badReview).hasError("error.session.links"));
        assertFalse(Files.exists(root.resolve("sessions")));
    }

    @Test void domainConstructorsRejectImpossibleStoredValues() {
        assertThrows(IllegalArgumentException.class, () -> new ShootPlan(UUID.randomUUID(), 0, false, List.of(),
            Optional.empty(), Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GearSnapshot(UUID.randomUUID(), "CAMERA", "Brand", "Model", 0,
            Instant.now(), Map.of(), Set.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExifStatistics(1, 2, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExifStatistics(2, 2, Map.of(), Map.of(50.0, 2, 24.0, 1), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityNeed("focal", Optional.of(70.0), Optional.of(24.0), "mm", 1, true, ""));
        var start = ZonedDateTime.parse("2026-11-01T10:00:00-05:00[America/Toronto]");
        assertThrows(IllegalArgumentException.class, () -> new ShootingWindow(start, start.minusHours(1), start, "", true));
        assertThrows(IllegalArgumentException.class, () -> new Loadout(UUID.randomUUID(), List.of(), Map.of(UUID.randomUUID(), UUID.randomUUID()),
            new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of()));
    }

    @Test void inventorySaveReturnsUsableNewRevisionAndRetainsStaleWriteProtection() {
        var repository = new JsonInventoryRepository(root.resolve("inventory.json"));
        var original = ok(repository.load());
        var saved = ok(repository.save(original));
        assertNotEquals(original.revision(), saved.revision());
        assertEquals(saved.revision(), ok(repository.load()).revision());
        assertTrue(repository.save(original).hasError("error.inventory.changed"));
        assertTrue(repository.save(saved).isSuccess());
    }

    @Test void failedInventoryTransactionDoesNotLeakMutatedAggregate() throws Exception {
        var repository = new JsonInventoryRepository(root.resolve("inventory.json"));
        var gear = ok(new EquipmentData("CAMERA", UUID.randomUUID().toString(), "Test", "Camera", 1,
            null, Set.of(), Map.of(), null, Map.of("mount", "E")).toEquipment());
        ok(repository.save(new GearInventory(List.of(gear))));
        byte[] before = Files.readAllBytes(root.resolve("inventory.json"));
        assertFalse(repository.transact(inventory -> {
            inventory.remove(gear.id()); return Result.failureKey("error.gear.required");
        }).isSuccess());
        assertArrayEquals(before, Files.readAllBytes(root.resolve("inventory.json")));
        assertEquals(1, ok(repository.load()).items().size());
    }
}
