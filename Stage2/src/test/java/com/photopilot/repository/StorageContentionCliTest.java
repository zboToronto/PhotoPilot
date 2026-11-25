package com.photopilot.repository;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static java.nio.file.StandardOpenOption.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 C31: a CLI started while another PhotoPilot process wrote the profile store printed "PhotoPilot
 * configuration error: Cannot open local profile: Storage is busy…". Contention is normal and temporary: the message
 * now says so, the exit code stays 2, nothing is written, and the same command succeeds once the writer has finished
 * (C43). Real child JVM; the other writer is this test holding the store's OS lock.
 */
class StorageContentionCliTest {
    @TempDir Path root;

    Process cli(Path output, String... commands) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-Duser.home=" + root.resolve("home"), "-cp", System.getProperty("java.class.path"), "com.photopilot.ui.cli.PhotoPilotCLI", "--lang=en"));
        command.addAll(List.of(commands));
        var builder = new ProcessBuilder(command).directory(root.toFile());
        builder.environment().remove("PHOTOPILOT_DATA_DIR"); builder.environment().remove("PHOTOPILOT_CONFIG_DIR");
        builder.environment().put("PHOTOPILOT_PROGRESS", "0");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        return builder.start();
    }

    @Test void aBusyStoreIsReportedAsTemporaryContentionAndTheRetrySucceeds() throws Exception {
        Path data = Files.createDirectories(root.resolve("home/.photopilot/data"));
        Path busyOutput = root.resolve("busy.log"), retryOutput = root.resolve("retry.log");
        try (var channel = FileChannel.open(data.resolve("profiles.lock"), CREATE, WRITE); var lock = channel.lock()) {
            assertNotNull(lock);
            Process busy = cli(busyOutput, "status", "exit");
            assertTrue(busy.waitFor(30, TimeUnit.SECONDS));
            String text = Files.readString(busyOutput, StandardCharsets.UTF_8);
            assertEquals(2, busy.exitValue(), text);
            assertTrue(text.contains("another PhotoPilot window or command is writing to the same storage"), text);
            assertTrue(text.contains("Nothing was changed; retry when it has finished."), text);
            assertFalse(text.contains("configuration error"), "contention is not a configuration problem: " + text);
            try (var files = Files.list(data)) { assertEquals(List.of("profiles.lock"), files.map(p -> p.getFileName().toString()).toList(), "nothing was written"); }
        }
        // The other writer finished: the same command now works.
        Process retry = cli(retryOutput, "status", "exit");
        assertTrue(retry.waitFor(30, TimeUnit.SECONDS));
        String text = Files.readString(retryOutput, StandardCharsets.UTF_8);
        assertEquals(0, retry.exitValue(), text);
        assertTrue(text.contains("F01 Photography Session and History Management - IMPLEMENTED"), text);
        assertTrue(StorageBusy.causedBy(new IllegalArgumentException("wrapped", new AtomicFiles.Busy())));
        assertFalse(StorageBusy.causedBy(new IllegalArgumentException("GEMINI_TIMEOUT_SECONDS must be a whole number from 5 to 300")));
    }
}
