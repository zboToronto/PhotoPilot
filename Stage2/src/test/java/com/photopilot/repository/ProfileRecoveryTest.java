package com.photopilot.repository;

import com.google.gson.JsonParser;
import com.photopilot.application.ProfileManager;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.service.SessionService;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.ReviewedState;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.repository.ProfileManagerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileRecoveryTest {
    @TempDir Path root;
    JsonProfileRepository registry() { return new JsonProfileRepository(root); }
    Map<String, String> env() { return Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }
    Path registryFile() { return root.resolve("profiles.json"); }
    UUID pendingId() throws Exception { return UUID.fromString(JsonParser.parseString(Files.readString(registryFile())).getAsJsonObject().get("defaultProfileId").getAsString()); }
    private Process child(String mode, Path target, Path signal) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
            PersistenceProcess.class.getName(), mode, target.toString(), signal.toString()).redirectErrorStream(true)
            .redirectOutput(root.resolve("child-" + mode + ".log").toFile()).start();
    }
    private void finish(Process process) throws Exception {
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    }
    @Test void separateJvmRestartsRestoreMultipleProfileIdentitiesAndIsolation() throws Exception {
        Path manifest = root.resolve("test-manifest.txt");
        finish(child("profile-seed", root, manifest)); finish(child("profile-check", root, manifest));
    }
    @Test void actualTerminatedMigrationRecoversTheDurableIdentityAndOriginalData() throws Exception {
        ok(new JsonInventoryRepository(root.resolve("inventory.json")).transact(inv -> inv.add(camera("Before crash"))));
        byte[] original = Files.readAllBytes(root.resolve("inventory.json")); Path signal = root.resolve("migration-ready");
        Process process = child("profile-interrupt", root, signal);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(signal));
        } finally { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        UUID id = pendingId(); assertEquals(id, registry().initializeDefault().id());
        assertArrayEquals(original, Files.readAllBytes(root.resolve("inventory.json")));
        assertArrayEquals(original, Files.readAllBytes(StorageScope.forProfile(root, id).inventoryFile()));
    }
    @Test void anotherProcessHoldingRegistryLockRejectsWritesThenReleases() throws Exception {
        registry().initializeDefault(); Path signal = root.resolve("lock-ready");
        Process process = child("lock", root.resolve("profiles.lock"), signal);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(signal)); assertThrows(IOException.class, () -> registry().create("Blocked"));
        } finally { process.getOutputStream().close(); finish(process); }
        assertEquals("Retry", registry().create("Retry").displayName());
    }
    @Test void migrationCopiesExactLegacyBytesAndRelationshipsOnlyOnce() throws Exception {
        var gear = camera("旧器材"); var legacyInventory = new JsonInventoryRepository(root.resolve("inventory.json"));
        ok(legacyInventory.transact(inventory -> inventory.add(gear)));
        var legacySessions = new JsonSessionRepository(root.resolve("sessions"));
        var linked = ok(legacySessions.insert(SessionFixtures.linked(new ReviewedState(), List.of(gear.snapshot()), true)));
        byte[] inventoryBytes = Files.readAllBytes(root.resolve("inventory.json")), sessionBytes = Files.readAllBytes(root.resolve("sessions/sessions.json"));
        Files.writeString(root.resolve("unrelated.txt"), "Never import");
        UUID defaultId;
        try (var app = new ApplicationBootstrap(env())) {
            defaultId = app.storageScope().profileId().orElseThrow();
            assertArrayEquals(inventoryBytes, Files.readAllBytes(app.storageScope().inventoryFile()));
            assertArrayEquals(sessionBytes, Files.readAllBytes(app.storageScope().sessionsDirectory().resolve("sessions.json")));
            assertEquals(gear.id(), ok(app.facade().inventory().list("")).items().getFirst().id());
            assertEquals(linked.id(), ok(app.facade().sessions().open(linked.id())).id());
            assertEquals(List.of(linked.id()), ok(app.facade().inventory().affectedSessions(gear.id())));
            assertFalse(Files.exists(app.storageScope().directory().resolve("unrelated.txt")));
            var saved = ok(app.facade().sessions().open(linked.id()));
            ok(app.facade().sessions().update(saved, "New profile title", saved.date(), saved.location(), saved.notes()));
        }
        // Old versions changing their old files must never overwrite newer profile records on another launch.
        ok(new SessionService(legacySessions).create("Old writer", LocalDate.now()));
        try (var app = new ApplicationBootstrap(env())) {
            assertEquals(defaultId, app.storageScope().profileId().orElseThrow());
            assertEquals(1, ok(app.facade().sessions().search("")).size());
            assertEquals("New profile title", ok(app.facade().sessions().open(linked.id())).title());
        }
        assertArrayEquals(inventoryBytes, Files.readAllBytes(root.resolve("inventory.json")));
        assertEquals(linked.title(), ok(legacySessions.find(linked.id())).title());
        assertEquals("Never import", Files.readString(root.resolve("unrelated.txt")));
    }
    @Test void corruptLegacyDataFailsBeforeRegisteringOrOverwriting() throws Exception {
        Files.writeString(root.resolve("inventory.json"), "broken");
        assertThrows(IOException.class, () -> registry().initializeDefault());
        assertFalse(Files.exists(registryFile())); assertEquals("broken", Files.readString(root.resolve("inventory.json")));
    }
    @Test void interruptedDirectoryPublicationResumesSameUuidAndCopies() throws Exception {
        ok(new JsonInventoryRepository(root.resolve("inventory.json")).transact(inv -> inv.add(camera("Preserve"))));
        var broken = new JsonProfileRepository(root) {
            protected void movePrepared(Path staging, Path destination) throws IOException { throw new IOException("Interrupted before publication"); }
        };
        assertThrows(IOException.class, broken::initializeDefault); UUID id = pendingId();
        assertTrue(Files.exists(root.resolve("profile-staging").resolve(id.toString()).resolve("inventory.json")));
        assertEquals(id, registry().initializeDefault().id()); assertEquals(1, registry().list().size());
        assertEquals(id, registry().initializeDefault().id());
        assertArrayEquals(Files.readAllBytes(root.resolve("inventory.json")), Files.readAllBytes(StorageScope.forProfile(root, id).inventoryFile()));
    }
    @Test void registryCommitFailureAfterDirectoryMoveRecoversWithoutOverwriting() throws Exception {
        var broken = new JsonProfileRepository(root) {
            int writes;
            protected void replace(Path temporary) throws IOException { if (++writes == 2) throw new IOException("Final registry commit failed"); super.replace(temporary); }
        };
        assertThrows(IOException.class, broken::initializeDefault); UUID id = pendingId();
        assertTrue(Files.isDirectory(StorageScope.forProfile(root, id).directory()));
        assertEquals(id, registry().initializeDefault().id()); assertEquals(1, registry().list().size());
    }
    @Test void changedSourceDuringPendingMigrationFailsWithoutGuessing() throws Exception {
        var inventory = new JsonInventoryRepository(root.resolve("inventory.json")); ok(inventory.transact(inv -> inv.add(camera("Original"))));
        byte[] original = Files.readAllBytes(root.resolve("inventory.json"));
        var broken = new JsonProfileRepository(root) { protected void movePrepared(Path source, Path target) throws IOException { throw new IOException("Interrupted"); } };
        assertThrows(IOException.class, broken::initializeDefault); UUID id = pendingId();
        ok(inventory.transact(inv -> inv.add(camera("New old-format write"))));
        byte[] changed = Files.readAllBytes(root.resolve("inventory.json"));
        assertThrows(IOException.class, () -> registry().initializeDefault()); assertEquals(id, pendingId());
        assertArrayEquals(changed, Files.readAllBytes(root.resolve("inventory.json")));
        Files.write(root.resolve("inventory.json"), original); assertEquals(id, registry().initializeDefault().id());
    }
    @Test void conflictingDestinationIsPreservedInsteadOfReplaced() throws Exception {
        var broken = new JsonProfileRepository(root) { int writes; protected void replace(Path temp) throws IOException { if (++writes == 2) throw new IOException("Interrupted"); super.replace(temp); } };
        assertThrows(IOException.class, broken::initializeDefault);
        Path destination = StorageScope.forProfile(root, pendingId()).directory(); Files.writeString(destination.resolve("inventory.json"), "Newer user data");
        assertThrows(IOException.class, () -> registry().initializeDefault()); assertEquals("Newer user data", Files.readString(destination.resolve("inventory.json")));
    }
    @Test void creationFailureIsNotListedUntilPreparedAndRecoveryUsesSameId() throws Exception {
        registry().initializeDefault();
        var broken = new JsonProfileRepository(root) { protected void movePrepared(Path source, Path target) throws IOException { throw new IOException("Cannot initialize new directory"); } };
        assertThrows(IOException.class, () -> broken.create("Pending"));
        assertEquals(1, registry().list().size());
        var pending = JsonParser.parseString(Files.readString(registryFile())).getAsJsonObject().getAsJsonObject("pending").getAsJsonObject("profile");
        UUID id = UUID.fromString(pending.get("id").getAsString());
        assertThrows(IOException.class, () -> registry().open(id));
        registry().initializeDefault(); assertEquals("Pending", registry().find(id).displayName()); assertEquals(2, registry().list().size());
    }
    @Test void failedRenameAndArchivePreserveRegistryAndData() throws Exception {
        registry().initializeDefault(); var profile = registry().create("B"); byte[] before = Files.readAllBytes(registryFile());
        var broken = new JsonProfileRepository(root) { protected void replace(Path temp) throws IOException { throw new IOException("Injected registry failure"); } };
        assertThrows(IOException.class, () -> broken.rename(profile.id(), "Changed"));
        assertThrows(IOException.class, () -> broken.archive(profile.id(), true));
        assertArrayEquals(before, Files.readAllBytes(registryFile())); assertEquals("B", registry().find(profile.id()).displayName());
        assertTrue(Files.isDirectory(StorageScope.forProfile(root, profile.id()).directory()));
    }
    @Test void metadataEditsPreserveAnotherProfilesPendingInitialization() throws Exception {
        registry().initializeDefault(); var existing = registry().create("Existing");
        var broken = new JsonProfileRepository(root) { protected void movePrepared(Path source, Path target) throws IOException { throw new IOException("Interrupted creation"); } };
        assertThrows(IOException.class, () -> broken.create("Pending"));
        var pending = JsonParser.parseString(Files.readString(registryFile())).getAsJsonObject().get("pending");
        registry().rename(existing.id(), "Renamed"); registry().archive(existing.id(), true);
        assertEquals(pending, JsonParser.parseString(Files.readString(registryFile())).getAsJsonObject().get("pending"));
        registry().initializeDefault(); assertEquals(2, registry().list().size());
        assertEquals("Pending", registry().find(UUID.fromString(pending.getAsJsonObject().getAsJsonObject("profile").get("id").getAsString())).displayName());
    }
    @Test void malformedRegistryUnknownFieldsDuplicateIdsAndTraversalArePreserved() throws Exception {
        registry().initializeDefault(); String valid = Files.readString(registryFile());
        var tree = JsonParser.parseString(valid).getAsJsonObject(); var entries = tree.getAsJsonArray("profiles"); entries.add(entries.get(0).deepCopy());
        for (String invalid : List.of("", "null", "{", valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                valid.replace("\"schemaVersion\": 1", "\"unexpected\": 1, \"schemaVersion\": 1"), tree.toString(), valid.replace(pendingId().toString(), "../outside"))) {
            Files.writeString(registryFile(), invalid); assertThrows(IOException.class, () -> registry().initializeDefault());
            assertThrows(IOException.class, () -> registry().create("New")); assertEquals(invalid, Files.readString(registryFile()));
        }
        Files.writeString(registryFile(), valid);
        byte[] bad = {(byte)0xc3, (byte)0x28}; Files.write(registryFile(), bad); assertThrows(IOException.class, () -> registry().list()); assertArrayEquals(bad, Files.readAllBytes(registryFile()));
    }
    @Test void missingRegistryOrRegisteredDirectoryDoesNotGenerateReplacementIdentities() throws Exception {
        var profile = registry().initializeDefault(); String registry = Files.readString(registryFile());
        Files.delete(registryFile()); assertThrows(IOException.class, () -> registry().initializeDefault());
        Files.writeString(registryFile(), registry);
        Path directory = StorageScope.forProfile(root, profile.id()).directory(); assertTrue(directory.startsWith(root)); Files.delete(directory);
        assertThrows(IOException.class, () -> registry().open(profile.id())); assertFalse(Files.exists(directory));
    }
    @Test void registryLockContentionReportsFailureAndRetrySucceeds() throws Exception {
        registry().initializeDefault();
        try (var channel = FileChannel.open(root.resolve("profiles.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(IOException.class, () -> registry().create("Busy"));
        }
        assertEquals("Retry", registry().create("Retry").displayName());
    }
    @Test void linkedDescendantOutsideDataRootCannotEscape() throws Exception {
        var store = new JsonProfileRepository(root.resolve("data"));
        store.initializeDefault(); var profile = store.create("B"); Path directory = StorageScope.forProfile(store.root(), profile.id()).directory();
        Path outside = Files.createDirectory(root.resolve("unrelated")); Files.writeString(outside.resolve("keep.txt"), "Keep");
        Path link = directory.resolve("sessions");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } else Files.createSymbolicLink(link, outside);
        try {
            assertThrows(IOException.class, () -> store.open(profile.id()));
            assertThrows(IOException.class, () -> store.archive(profile.id(), true));
            assertEquals("Keep", Files.readString(outside.resolve("keep.txt"))); assertEquals(profile, store.find(profile.id()));
        } finally { assertTrue(link.normalize().startsWith(root)); Files.delete(link); }
    }
}
