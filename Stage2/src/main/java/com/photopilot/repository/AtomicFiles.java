package com.photopilot.repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;
import static java.nio.file.StandardOpenOption.*;

/** Shared local-file mechanics. Callers own serialization and hold a lock across read/change/write. */
final class AtomicFiles {
    private AtomicFiles() { }
    @FunctionalInterface interface Operation<T> { T run() throws IOException; }
    @FunctionalInterface interface Replacement { void replace(Path temporary) throws IOException; }
    static final class Busy extends IOException {
        Busy() { super("Storage is busy; retry after the other writer finishes."); }
    }
    /** How long a writer of this process waits for another writer of this process on the same store. */
    static final long IN_PROCESS_WAIT_SECONDS = 10;
    private static final java.util.concurrent.ConcurrentHashMap<Path, java.util.concurrent.locks.ReentrantLock> WRITERS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Runs {@code operation} holding the store's writer lock. Writers of the same process (the GUI's local and external
     * operation lanes) queue for a bounded time on an in-process lock first, so they never fail each other with "busy";
     * another process holding the OS file lock still fails at once with {@link Busy}, as before.
     */
    static <T> T locked(Path lockFile, Operation<T> operation) throws IOException {
        Files.createDirectories(lockFile.getParent());
        var writer = WRITERS.computeIfAbsent(lockFile.toAbsolutePath().normalize(), path -> new java.util.concurrent.locks.ReentrantLock());
        try {
            if (!writer.tryLock(IN_PROCESS_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) throw new Busy();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new Busy(); }
        try (var channel = FileChannel.open(lockFile, CREATE, WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new Busy();
            return operation.run();
        } catch (OverlappingFileLockException busy) { throw new Busy(); }
        finally { writer.unlock(); }
    }
    static Optional<byte[]> read(Path file, int maximum) throws IOException {
        return read(file, maximum, false);
    }
    static Optional<byte[]> read(Path file, int maximum, boolean allowEmpty) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Storage exceeds " + maximum + " bytes: " + file);
            if (bytes.length == 0 && !allowEmpty) throw new IOException("Storage file is empty: " + file);
            return Optional.of(bytes);
        } catch (NoSuchFileException missing) { return Optional.empty(); }
    }
    static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
    static void write(Path file, String prefix, byte[] bytes, int maximum, Replacement replacement) throws IOException {
        if (bytes.length == 0 || bytes.length > maximum) throw new IOException("Invalid serialized storage size.");
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), prefix, ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            replacement.replace(temporary);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void replace(Path temporary, Path file) throws IOException {
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            // A non-atomic overwrite cannot guarantee crash safety. Keep the original and report failure.
            throw new IOException("Atomic replacement is unavailable; original data was preserved. Use a local filesystem supporting atomic moves.", unsupported);
        }
    }
}
