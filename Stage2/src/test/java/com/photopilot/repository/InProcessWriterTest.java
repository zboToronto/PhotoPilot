package com.photopilot.repository;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.progress.*;
import com.photopilot.service.SessionService;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence with two GUI lanes: writers of one process wait for each other instead of failing with "busy" (another
 * process still gets "busy", see PersistenceIntegrationTest), every write keeps its atomic replacement, and a failed
 * replacement is reported as not saved with the stored bytes unchanged.
 */
class InProcessWriterTest {
    @TempDir Path root;

    @Test void concurrentWritersOfOneProcessAllSucceedInsteadOfFailingBusy() throws Exception {
        var service = new SessionService(new JsonSessionRepository(root.resolve("sessions")));
        int threads = 4, each = 12;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            var results = new ArrayList<Future<List<Result<PhotographySession>>>>();
            for (int t = 0; t < threads; t++) {
                int lane = t;
                results.add(pool.submit(() -> {
                    start.await();
                    var made = new ArrayList<Result<PhotographySession>>();
                    for (int i = 0; i < each; i++) made.add(service.create("Lane " + lane + " #" + i, LocalDate.of(2026, 12, 1)));
                    return made;
                }));
            }
            start.countDown();
            for (var future : results) for (var made : future.get(60, TimeUnit.SECONDS))
                assertTrue(made.isSuccess(), () -> "no in-process writer fails with busy: " + made.errors());
        } finally { pool.shutdownNow(); }
        assertEquals(threads * each, service.search("").value().orElseThrow().size(), "every write is kept; none overwrote another");
    }

    @Test void aFailedAtomicReplacementIsNotSavedAndLeavesTheStoredBytesUnchanged() throws Exception {
        Path file = root.resolve("inventory.json");
        var working = new JsonInventoryRepository(file);
        var camera = com.photopilot.matching.GearFixtures.camera("Sony", "A7 IV", "E", 1, 650.0, Set.of(), Set.of(), 10.0, true);
        assertTrue(working.transact(inventory -> { inventory.add(camera); return Result.success(true); }).isSuccess());
        byte[] before = Files.readAllBytes(file);
        var failing = new JsonInventoryRepository(file) {
            @Override protected void replace(Path temporary) throws IOException { throw new IOException("disk full"); }
        };
        var operation = OperationContext.create("F05", "gearSave", List.of(), false);
        var lens = com.photopilot.matching.GearFixtures.lens("Sony", "FE 50mm F1.8", "E", 50, 50, 1.8, 49.0, 186.0, true);
        Result<Boolean> saved = operation.run(() -> failing.transact(inventory -> { inventory.add(lens); return Result.success(true); }));
        operation.finish(saved, Optional.empty());
        assertFalse(saved.isSuccess());
        assertEquals(PersistenceStatus.SAVE_FAILED, operation.persistence(), "never reported as saved");
        assertEquals(ErrorKind.STORAGE, operation.snapshot().error());
        assertArrayEquals(before, Files.readAllBytes(file), "the original file is untouched");
        try (var entries = Files.list(root)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temporary file is left behind");
        }
    }
}
