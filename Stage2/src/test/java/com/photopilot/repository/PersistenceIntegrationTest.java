package com.photopilot.repository;

import com.photopilot.common.Result;
import com.photopilot.config.StorageConfiguration;
import com.photopilot.model.*;
import com.photopilot.service.SessionService;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.ReviewedState;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceIntegrationTest {
    @TempDir Path root;
    private final Map<Process, Path> outputs = new HashMap<>();
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), result.errors().toString()); return result.value().orElseThrow(); }
    JsonSessionRepository sessions() { return new JsonSessionRepository(root.resolve("sessions")); }
    PhotographySession create(String title) { return ok(new SessionService(sessions()).create(title, LocalDate.of(2026, 11, 1))); }
    Process child(String main, Path cwd, String... args) throws Exception {
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-Duser.home=" + root.resolve("home"), "-cp", System.getProperty("java.class.path"), main));
        command.addAll(List.of(args)); var builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().remove("PHOTOPILOT_DATA_DIR"); builder.environment().remove("PHOTOPILOT_CONFIG_DIR");
        Path output = Files.createTempFile(root, "process-", ".log");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start(); outputs.put(process, output); return process;
    }
    void ready(Process process, Path signal) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(signal), "Child JVM failed to reach checkpoint");
    }
    void stop(Process process) throws Exception {
        if (process.isAlive()) process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
    String cli(Path cwd, String... commands) throws Exception {
        var args = new ArrayList<>(List.of("--lang=en")); args.addAll(List.of(commands)); args.add("exit");
        Process process = child("com.photopilot.ui.cli.PhotoPilotCLI", cwd, args.toArray(String[]::new));
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); return Files.readString(outputs.get(process)); }
        finally { stop(process); }
    }
    @Test void defaultLocationIsStableAcrossLaunchDirectoriesAndOverridesRemainExplicit() throws Exception {
        Path home = root.resolve("home"), first = root.resolve("first"), second = root.resolve("second");
        var a = StorageConfiguration.resolve(Map.of(), home, first); var b = StorageConfiguration.resolve(Map.of(), home, second);
        assertEquals(a.scope(), b.scope()); assertEquals(home.resolve(".photopilot/data"), a.scope().directory());
        assertEquals(home.resolve(".photopilot"), a.configurationDirectory());
        var explicit = StorageConfiguration.resolve(Map.of("PHOTOPILOT_DATA_DIR", "chosen"), home, first);
        assertEquals(first.resolve("chosen"), explicit.scope().directory());
        assertThrows(IllegalArgumentException.class, () -> StorageConfiguration.resolve(Map.of("PHOTOPILOT_DATA_DIR", " "), home, first));
        assertFalse(Files.exists(home));
    }
    @Test void legacyDataTriggersNoticeWithoutBeingMovedOrOpened() throws Exception {
        Path work = root.resolve("work"); Files.createDirectories(work.resolve("data"));
        Files.writeString(work.resolve("data/inventory.json"), "legacy bytes");
        var config = StorageConfiguration.resolve(Map.of(), root.resolve("home"), work);
        assertEquals(work.resolve("data"), config.legacyDirectory().orElseThrow());
        assertEquals("legacy bytes", Files.readString(work.resolve("data/inventory.json")));
        assertFalse(Files.exists(config.scope().directory()));
        assertTrue(StorageConfiguration.resolve(Map.of("PHOTOPILOT_DATA_DIR", work.resolve("data").toString()), root, work).legacyDirectory().isEmpty());
    }
    @Test void actualCliLegacyNoticeUsesUtf8AndPreservesOldData() throws Exception {
        Path home = root.resolve("home/.photopilot");
        new JsonLocalePreferenceRepository(home.resolve("preferences.json"), home.resolve("settings.properties")).save("zh-CN");
        Files.createDirectories(root.resolve("data")); Path old = root.resolve("data/inventory.json"); Files.writeString(old, "old bytes");
        String output = cli(root, "help");
        assertTrue(output.contains("旧数据目录")); assertTrue(output.contains("PHOTOPILOT_DATA_DIR"));
        assertEquals("old bytes", Files.readString(old));
    }
    @Test void actualCliProcessesShareDefaultStorageAcrossDirectoriesAndPersistCrudAndLanguage() throws Exception {
        Path first = Files.createDirectory(root.resolve("first")), second = Files.createDirectory(root.resolve("second"));
        cli(first, "gear add camera brand=Test model=相机 mount=E", "session create title=夜景 date=2026-11-01 notes=保留", "lang zh-CN");
        Path home = root.resolve("home/.photopilot"); var inventory = new JsonInventoryRepository(home.resolve("data/inventory.json"));
        var repository = new JsonSessionRepository(home.resolve("data/sessions"));
        var gear = ok(inventory.load()).items().getFirst(); var session = ok(repository.search("")).getFirst();
        assertEquals("相机", gear.model()); assertEquals("夜景", session.title());
        cli(second, "gear edit " + gear.id() + " quantity=2", "session edit " + session.id() + " notes=更新");
        assertEquals(2, ok(inventory.load()).find(gear.id()).orElseThrow().quantity());
        assertEquals("更新", ok(repository.find(session.id())).notes());
        assertEquals("zh-CN", new JsonLocalePreferenceRepository(home.resolve("preferences.json"), home.resolve("settings.properties")).load());
        cli(first, "gear remove " + gear.id(), "session delete " + session.id() + " --confirm");
        assertTrue(ok(inventory.load()).items().isEmpty()); assertTrue(ok(repository.search("")).isEmpty());
        assertFalse(Files.exists(first.resolve("data"))); assertFalse(Files.exists(second.resolve("data")));
    }
    @Test void anotherProcessHoldingWriterLockIsRejectedAndRetrySucceeds() throws Exception {
        var session = create("Original"); byte[] original = Files.readAllBytes(root.resolve("sessions/sessions.json"));
        Path signal = root.resolve("ready");
        Process process = child(PersistenceProcess.class.getName(), root, "lock", root.resolve("sessions/sessions.lock").toString(), signal.toString());
        try { ready(process, signal); assertTrue(sessions().delete(session.id(), session.revision()).hasError("error.session.busy"));
            assertArrayEquals(original, Files.readAllBytes(root.resolve("sessions/sessions.json"))); }
        finally {
            // Release through the child try-with-resources; forced termination has its own test below.
            process.getOutputStream().close();
            try { assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
            finally { stop(process); }
        }
        assertTrue(ok(sessions().delete(session.id(), session.revision())));
    }
    @Test void terminatedWriterLeavesLastCommittedVersionReadableAndRetryable() throws Exception {
        var session = create("Committed"); Path file = root.resolve("sessions/sessions.json"); byte[] original = Files.readAllBytes(file);
        Path signal = root.resolve("ready");
        Process process = child(PersistenceProcess.class.getName(), root, "interrupt", root.resolve("sessions").toString(), signal.toString(), session.id().toString());
        try { ready(process, signal); assertArrayEquals(original, Files.readAllBytes(file)); }
        finally { stop(process); }
        assertEquals("Committed", ok(sessions().find(session.id())).title());
        assertTrue(sessions().save(session.edit("Retry", session.date(), session.location(), "")).isSuccess());
        assertEquals("Retry", ok(sessions().find(session.id())).title());
    }
    @Test void unrelatedSessionWriteInAnotherJvmDoesNotInvalidateUnchangedRecordRevision() throws Exception {
        var tree = SessionJson.encode(SessionFixtures.linked(new ReviewedState(), List.of(), true));
        var settings = tree.getAsJsonArray("plans").get(0).getAsJsonObject().getAsJsonObject("settings");
        for (int i = 0; i < 30; i++) settings.addProperty("key" + i, "value" + i);
        var first = ok(sessions().insert(SessionJson.decode(tree))); var second = create("Other");
        Process process = child(PersistenceProcess.class.getName(), root, "edit", root.resolve("sessions").toString(), root.resolve("unused").toString(), second.id().toString());
        try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { stop(process); }
        assertEquals(first.revision(), ok(sessions().find(first.id())).revision());
        assertTrue(sessions().save(first.edit("Valid edit", first.date(), first.location(), "")).isSuccess());
        assertTrue(sessions().save(second.edit("Stale edit", second.date(), second.location(), "")).hasError("error.session.changed"));
    }
    @Test void unsupportedAtomicMovePreservesExistingDataAndCleansTemporaryFile() throws Exception {
        var original = create("Keep"); Path file = root.resolve("sessions/sessions.json"); byte[] before = Files.readAllBytes(file);
        var broken = new JsonSessionRepository(root.resolve("sessions")) {
            protected void replace(Path temporary) throws java.io.IOException {
                throw new AtomicMoveNotSupportedException(temporary.toString(), file.toString(), "Injected unsupported filesystem");
            }
        };
        assertFalse(broken.save(original.edit("Changed", original.date(), original.location(), "")).isSuccess());
        assertArrayEquals(before, Files.readAllBytes(file));
        try (var entries = Files.list(file.getParent())) { assertFalse(entries.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void readOnlyDestinationRejectsWriteAndPreservesDataOnWindows() throws Exception {
        var session = create("Read only"); Path file = root.resolve("sessions/sessions.json");
        var attributes = Files.getFileAttributeView(file, DosFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && attributes != null);
        byte[] before = Files.readAllBytes(file); attributes.setReadOnly(true);
        try { assertTrue(sessions().find(session.id()).isSuccess());
            assertFalse(sessions().save(session.edit("Changed", session.date(), session.location(), "")).isSuccess());
            assertArrayEquals(before, Files.readAllBytes(file)); }
        finally { attributes.setReadOnly(false); }
    }
}
