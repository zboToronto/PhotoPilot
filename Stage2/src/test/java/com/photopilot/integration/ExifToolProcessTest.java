package com.photopilot.integration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Process lifecycle against a real child process speaking the -stay_open protocol (FakeExifTool). */
class ExifToolProcessTest {
    private final List<ExifToolProcess> opened = new ArrayList<>();

    private static ExifToolRuntime.Command fake() throws ExifToolException {
        Path java = Path.of(System.getProperty("java.home"), "bin", ExifToolRuntime.isWindows() ? "java.exe" : "java");
        Path classes;
        try { classes = Path.of(FakeExifTool.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (java.net.URISyntaxException impossible) { throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "test classes not found", impossible); }
        return new ExifToolRuntime.Command(List.of(java.toString(), "-cp", classes.toString(), FakeExifTool.class.getName()), classes);
    }
    private ExifToolProcess process(Duration timeout) {
        var process = new ExifToolProcess(ExifToolProcessTest::fake, timeout); opened.add(process); return process;
    }
    @AfterEach void closeAll() { opened.forEach(ExifToolProcess::close); }

    private static void awaitExit(ProcessHandle handle) throws Exception {
        handle.onExit().get(10, TimeUnit.SECONDS);
        assertFalse(handle.isAlive());
    }

    @Test void startsLazilyThenReusesOneProcessWithSeparateResponses() throws Exception {
        var process = process(Duration.ofSeconds(10));
        assertFalse(process.running(), "nothing starts before the first request");
        assertEquals(0, process.starts());
        var first = process.execute(List.of("-json", "C:\\photos\\a b\\照片 ü.ARW"));
        var second = process.execute(List.of("-ver"));
        assertEquals(1, process.starts(), "the second request reuses the process");
        assertEquals("args: -json | C:\\photos\\a b\\照片 ü.ARW", first.stdout(), "UTF-8 arguments arrive intact, one per line");
        assertEquals("args: -ver", second.stdout());
        assertEquals(List.of("err " + first.id()), first.stderr(), "stderr ends at the request's -echo4 marker");
        assertEquals(List.of("err " + second.id()), second.stderr());
        assertNotEquals(first.id(), second.id());
    }

    @Test void largeOutputOnBothStreamsDoesNotDeadlock() throws Exception {
        var process = process(Duration.ofSeconds(20));
        var response = process.execute(List.of("-spam", "20000"));
        assertEquals(20001, response.stdout().lines().count());
        assertEquals(20001, response.stderr().size());
        assertEquals("args: -ver", process.execute(List.of("-ver")).stdout(), "nothing from the earlier request leaks into the next");
    }

    @Test void concurrentRequestsEachGetTheirOwnAnswer() throws Exception {
        var process = process(Duration.ofSeconds(20));
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String marker = "request-" + i;
                answers.add(pool.submit(() -> process.execute(List.of("-spam", "3", marker)).stdout()));
            }
            for (int i = 0; i < answers.size(); i++) {
                String stdout = answers.get(i).get(30, TimeUnit.SECONDS);
                assertTrue(stdout.endsWith("args: -spam | 3 | request-" + i), stdout);
                assertEquals(4, stdout.lines().count(), "only this request's lines");
            }
        } finally { pool.shutdownNow(); }
        assertEquals(1, process.starts());
    }

    @Test void timeoutStopsTheProcessAndTheNextRequestStartsAFreshOne() throws Exception {
        var process = process(Duration.ofSeconds(2));
        process.execute(List.of("-ver"));
        ProcessHandle first = process.handle();
        var failure = assertThrows(ExifToolException.class, () -> process.execute(List.of("-sleep")));
        assertEquals(ExifToolException.Kind.TIMEOUT, failure.kind());
        awaitExit(first);
        assertEquals("args: -ver", process.execute(List.of("-ver")).stdout(), "recovers after a timeout");
        assertEquals(2, process.starts());
    }

    @Test void crashDuringARequestIsReportedAndRecovered() throws Exception {
        var process = process(Duration.ofSeconds(10));
        var failure = assertThrows(ExifToolException.class, () -> process.execute(List.of("-die")));
        assertEquals(ExifToolException.Kind.TERMINATED, failure.kind());
        assertEquals("args: -ver", process.execute(List.of("-ver")).stdout());
        assertEquals(2, process.starts());
    }

    @Test void processThatDiedBetweenRequestsIsRestartedTransparently() throws Exception {
        var process = process(Duration.ofSeconds(10));
        process.execute(List.of("-ver"));
        ProcessHandle first = process.handle();
        first.destroyForcibly(); awaitExit(first);
        assertEquals("args: -ver", process.execute(List.of("-ver")).stdout());
        assertEquals(2, process.starts());
    }

    @Test void startupFailuresAreReported() {
        var missing = new ExifToolProcess(() -> new ExifToolRuntime.Command(List.of(Path.of("does-not-exist", "perl.exe").toAbsolutePath().toString(), "exiftool.pl"), Path.of(".").toAbsolutePath()), Duration.ofSeconds(5));
        opened.add(missing);
        assertEquals(ExifToolException.Kind.STARTUP, assertThrows(ExifToolException.class, () -> missing.execute(List.of("-ver"))).kind());
        var unavailable = new ExifToolProcess(() -> { throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "no bundle"); }, Duration.ofSeconds(5));
        opened.add(unavailable);
        assertEquals(ExifToolException.Kind.UNAVAILABLE, assertThrows(ExifToolException.class, () -> unavailable.execute(List.of("-ver"))).kind());
        assertFalse(missing.running()); assertFalse(unavailable.running());
    }

    @Test void closeEndsTheProcessGracefullyAndLaterRequestsAreRefused() throws Exception {
        var process = process(Duration.ofSeconds(10));
        process.close(); // closing before any request is harmless
        var other = process(Duration.ofSeconds(10));
        other.execute(List.of("-ver"));
        ProcessHandle handle = other.handle();
        other.close();
        awaitExit(handle);
        other.close(); // idempotent
        assertEquals(ExifToolException.Kind.CLOSED, assertThrows(ExifToolException.class, () -> other.execute(List.of("-ver"))).kind());
    }

    @Test void closeDuringALongRequestStopsIt() throws Exception {
        var process = process(Duration.ofSeconds(60));
        process.execute(List.of("-ver"));
        ProcessHandle handle = process.handle();
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<ExifToolException.Kind> pending = pool.submit(() -> {
                try { process.execute(List.of("-sleep")); return null; } catch (ExifToolException failure) { return failure.kind(); }
            });
            Thread.sleep(300);
            long started = System.nanoTime();
            process.close();
            assertEquals(ExifToolException.Kind.CLOSED, pending.get(15, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10, "shutdown does not wait for the request's own timeout");
            awaitExit(handle);
        } finally { pool.shutdownNow(); }
    }

    @Test void argumentsCannotInjectProtocolCommands() {
        var process = process(Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> process.execute(List.of("photo.jpg\n-execute9")));
        assertThrows(IllegalArgumentException.class, () -> process.execute(List.of("-stay_open")));
        assertThrows(IllegalArgumentException.class, () -> process.execute(List.of("-execute5")));
        assertThrows(IllegalArgumentException.class, () -> process.execute(List.of("-@")));
        assertFalse(process.running(), "rejected before anything starts");
    }
}
