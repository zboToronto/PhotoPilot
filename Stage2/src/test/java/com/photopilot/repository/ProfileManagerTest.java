package com.photopilot.repository;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.ReviewedState;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProfileManagerTest {
    @TempDir Path root;
    Map<String, String> env() { return Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }
    JsonProfileRepository registry() { return new JsonProfileRepository(root.resolve("data")); }
    static <T> T ok(Result<T> value) { assertTrue(value.isSuccess(), value.errors().toString()); return value.value().orElseThrow(); }
    static Equipment camera(String name) {
        return ok(EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(), Map.of("brand", "Test", "model", name, "mount", "E"), null));
    }
    static Object field(Object object, String name) throws Exception { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    @Test void createDuplicateNamesRenameAndRestartPreserveStableIdentities() throws Exception {
        UUID defaultId, first, second;
        try (var manager = new ProfileManager(env())) {
            defaultId = ok(manager.activeProfile()).id();
            first = ok(manager.createProfile(" 摄影 ")).id(); second = ok(manager.createProfile("摄影")).id();
            assertNotEquals(first, second); assertNotEquals(defaultId, first);
            var old = ok(manager.getProfile(first)); var renamed = ok(manager.renameProfile(first, "New name"));
            assertEquals(first, renamed.id()); assertEquals(old.createdAt(), renamed.createdAt()); assertTrue(renamed.updatedAt().isAfter(old.updatedAt()));
            assertEquals(3, ok(manager.listProfiles()).size());
            assertEquals(defaultId, ok(manager.activeProfile()).id());
            assertTrue(Files.isDirectory(StorageScope.forProfile(registry().root(), first).directory()));
        }
        try (var manager = new ProfileManager(env())) {
            assertEquals(defaultId, ok(manager.activeProfile()).id()); assertEquals("New name", ok(manager.getProfile(first)).displayName());
            assertEquals(second, ok(manager.getProfile(second)).id()); assertEquals(ok(manager.listProfiles()), registry().list());
        }
    }
    @Test void invalidNamesIdsAndClosedManagerNeverMutateRegistry() throws Exception {
        var manager = new ProfileManager(env()); UUID id = ok(manager.activeProfile()).id();
        byte[] original = Files.readAllBytes(registry().root().resolve("profiles.json"));
        for (String name : Arrays.asList(null, "", "  ", "bad\nname", "x".repeat(81))) {
            assertFalse(manager.createProfile(name).isSuccess()); assertFalse(manager.renameProfile(id, name).isSuccess());
        }
        assertFalse(manager.getProfile(UUID.randomUUID()).isSuccess()); assertFalse(manager.getProfile(null).isSuccess());
        assertArrayEquals(original, Files.readAllBytes(registry().root().resolve("profiles.json")));
        manager.close(); assertFalse(manager.listProfiles().isSuccess()); assertThrows(IllegalStateException.class, manager::activeContext);
    }
    @Test void displayNamesAreNeverFilesystemFragments() {
        try (var manager = new ProfileManager(env())) {
            var profile = ok(manager.createProfile("../Outside/name"));
            assertTrue(Files.isDirectory(StorageScope.forProfile(registry().root(), profile.id()).directory()));
            assertFalse(Files.exists(root.resolve("Outside")));
        }
    }
    @Test void servicesIsolateCrudLinkedAggregatesAndRestart() throws Exception {
        UUID a, b, sessionId; Equipment gear = camera("Only A");
        try (var manager = new ProfileManager(env())) {
            a = ok(manager.activeProfile()).id(); b = ok(manager.createProfile("B")).id();
            ok(manager.activeContext().facade().inventory().add(gear, false));
            ok(manager.selectProfile(b, true));
            assertTrue(ok(manager.activeContext().facade().inventory().list("")).items().isEmpty());
            var scopeB = manager.activeContext().storageScope();
            var fixture = SessionFixtures.linked(new ReviewedState(), List.of(), true);
            // Controlled fixture for already-saved AI-related aggregates; no producer/LLM execution.
            try (var access = registry().open(b)) { sessionId = ok(access.sessions().insert(fixture)).id(); }
            var originalB = Files.readAllBytes(scopeB.sessionsDirectory().resolve("sessions.json"));
            ok(manager.selectProfile(a, true)); assertFalse(manager.activeContext().facade().sessions().open(sessionId).isSuccess());
            var edited = ok(EquipmentForm.parse(gear.type(), gear.id(), Map.of("quantity", "2"), gear));
            ok(manager.activeContext().facade().inventory().update(edited, false));
            assertArrayEquals(originalB, Files.readAllBytes(scopeB.sessionsDirectory().resolve("sessions.json")));
            ok(manager.renameProfile(a, "Renamed default")); assertEquals(2, ok(manager.activeContext().facade().inventory().list("")).items().getFirst().quantity());
            ok(manager.activeContext().facade().inventory().remove(gear.id(), false));
            assertArrayEquals(originalB, Files.readAllBytes(scopeB.sessionsDirectory().resolve("sessions.json")));
        }
        try (var manager = new ProfileManager(env())) {
            assertEquals(a, ok(manager.activeProfile()).id()); assertTrue(ok(manager.activeContext().facade().sessions().search("")).isEmpty());
            ok(manager.selectProfile(b, true)); var restored = ok(manager.activeContext().facade().sessions().open(sessionId));
            assertEquals("REVIEWED", restored.status()); assertFalse(restored.plans().isEmpty()); assertFalse(restored.reviews().isEmpty()); assertTrue(restored.statistics().isPresent());
            assertTrue(ok(manager.activeContext().facade().inventory().list("")).items().isEmpty());
            ok(manager.activeContext().facade().sessions().delete(restored, true));
        }
    }
    @Test void switchingRebuildsAgentGraphAndRevokesRetainedServices() throws Exception {
        try (var manager = new ProfileManager(env())) {
            var previous = manager.activeContext(); var oldFacade = previous.facade();
            var oldAgent = field(oldFacade.planning(), "agent"); var oldHistory = (com.photopilot.agent.HistoryRetriever) field(oldAgent, "history");
            var id = ok(manager.createProfile("B")).id();
            assertFalse(manager.selectProfile(id, false).isSuccess()); assertSame(previous, manager.activeContext());
            ok(manager.selectProfile(id, true));
            assertNotSame(previous, manager.activeContext()); assertNotSame(oldAgent, field(manager.activeContext().facade().planning(), "agent"));
            assertTrue(oldFacade.sessions().create("Stale", LocalDate.now()).hasError("error.profile.closed"));
            assertTrue(oldFacade.inventory().list("").hasError("error.profile.closed"));
            assertFalse(oldHistory.retrieve("").isSuccess());
            assertTrue(ok(manager.activeContext().facade().sessions().search("")).isEmpty());
            var currentRepository = field(manager.activeContext().facade().sessions(), "repository");
            assertSame(currentRepository, field(manager.activeContext().facade().planning(), "sessions"));
            assertSame(currentRepository, field(manager.activeContext().facade().reviews(), "sessions"));
        }
    }
    @Test void invalidOrCorruptTargetKeepsPreviousValidContext() throws Exception {
        try (var manager = new ProfileManager(env())) {
            var previous = manager.activeContext(); var b = ok(manager.createProfile("B"));
            Path file = StorageScope.forProfile(registry().root(), b.id()).inventoryFile(); Files.writeString(file, "broken");
            assertFalse(manager.selectProfile(UUID.randomUUID(), true).isSuccess()); assertFalse(manager.selectProfile(b.id(), true).isSuccess());
            assertSame(previous, manager.activeContext()); assertEquals("broken", Files.readString(file));
            ok(previous.facade().sessions().create("Still A", LocalDate.now()));
        }
    }
    @Test void foreignSessionAndInventoryRevisionsCannotUpdateAnotherProfile() throws Exception {
        try (var manager = new ProfileManager(env())) {
            UUID a = ok(manager.activeProfile()).id(), b = ok(manager.createProfile("B")).id();
            try (var first = registry().open(a); var second = registry().open(b)) {
                var saved = ok(first.sessions().insert(SessionFixtures.linked(new ReviewedState(), List.of(), true)));
                assertFalse(second.sessions().insert(saved).isSuccess());
                ok(second.sessions().insert(saved.withRevision(""))); // Deliberate test fixture with the same record UUID.
                assertFalse(second.sessions().save(saved).isSuccess()); assertFalse(second.sessions().delete(saved.id(), saved.revision()).isSuccess());
                assertFalse(second.inventory().save(ok(first.inventory().load())).isSuccess());
                assertEquals(saved.title(), ok(second.sessions().find(saved.id())).title());
            }
        }
    }
    @Test void archiveRequiresConfirmationProtectsActiveDefaultAndRetainsData() throws Exception {
        UUID b; Path file; byte[] saved;
        try (var manager = new ProfileManager(env())) {
            UUID a = ok(manager.activeProfile()).id(); b = ok(manager.createProfile("B")).id();
            ok(manager.selectProfile(b, true)); ok(manager.activeContext().facade().inventory().add(camera("Archive"), false));
            assertFalse(manager.deleteProfile(a, true).isSuccess(), "Default identity stays protected even while inactive");
            file = manager.activeContext().storageScope().inventoryFile(); saved = Files.readAllBytes(file);
            assertFalse(manager.deleteProfile(b, true).isSuccess()); ok(manager.selectProfile(a, true));
            assertFalse(manager.deleteProfile(b, false).isSuccess()); assertFalse(manager.deleteProfile(a, true).isSuccess());
            assertFalse(manager.deleteProfile(UUID.randomUUID(), true).isSuccess()); ok(manager.deleteProfile(b, true));
            assertFalse(manager.getProfile(b).isSuccess()); assertFalse(manager.selectProfile(b, true).isSuccess());
            assertEquals(1, ok(manager.listProfiles()).size()); assertArrayEquals(saved, Files.readAllBytes(file));
        }
        try (var manager = new ProfileManager(env())) { assertFalse(manager.getProfile(b).isSuccess()); assertArrayEquals(saved, Files.readAllBytes(file)); }
    }
    @Test void anotherManagersArchiveRevokesThePeersNextOperation() {
        try (var first = new ProfileManager(env()); var second = new ProfileManager(env())) {
            UUID b = ok(first.createProfile("B")).id(); ok(second.selectProfile(b, true));
            ok(first.deleteProfile(b, true));
            assertFalse(second.activeContext().facade().sessions().create("No resurrection", LocalDate.now()).isSuccess());
            assertFalse(second.activeContext().facade().inventory().list("").isSuccess());
        }
    }
    @Test void globalLanguagePolicyIsPreservedWithoutSharingBusinessData() {
        try (var manager = new ProfileManager(env())) {
            assertTrue(manager.activeContext().localization().selectLanguage("zh-CN"));
            ok(manager.selectProfile(ok(manager.createProfile("B")).id(), true));
            assertEquals("zh-CN", manager.activeContext().localization().language().tag());
            assertTrue(ok(manager.activeContext().facade().sessions().search("")).isEmpty());
        }
    }
}
