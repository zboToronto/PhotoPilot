package com.photopilot.i18n;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import static java.nio.file.StandardOpenOption.*;

/** UTF-8 preferences, merged under a process lock and replaced atomically. */
public final class FileLocalePreferenceRepository implements LocalePreferenceRepository {
    private final Path file;
    public FileLocalePreferenceRepository(Path file) { this.file = file.toAbsolutePath().normalize(); }
    private Properties read() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (NoSuchFileException missing) { /* Fresh user; do not create files on read. */ }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed preferences", malformed); }
        return properties;
    }
    @Override public String load() throws IOException { return read().getProperty("language"); }
    @Override public void save(String tag) throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Preferences are busy");
            Properties properties = read();
            properties.setProperty("language", tag);
            Path temporary = Files.createTempFile(file.getParent(), "preferences-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    properties.store(writer, "PhotoPilot preferences");
                }
                try (FileChannel data = FileChannel.open(temporary, WRITE)) { data.force(true); }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }
}
