package com.photopilot.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Locates the ExifTool package bundled with PhotoPilot (third-party/exiftool, copied by Maven to {@code exiftool/} on
 * the class path). Never uses a globally installed ExifTool or Perl.
 *
 * <ul>
 *   <li>Classes on disk ({@code javafx:run}, tests, IDE): the package is used where it is, after checking that every file
 *       in {@code MANIFEST.sha256} is present with the listed size.</li>
 *   <li>Inside a JAR: the files are extracted once to {@code <cache>/<version>-<manifest hash>}, each verified against its
 *       SHA-256, then reused after the same SHA-256 check against the bundled manifest (once per runtime); a damaged copy
 *       is replaced. The cache defaults to {@code %LOCALAPPDATA%\PhotoPilot\exiftool}.</li>
 * </ul>
 * The command is {@code exiftool_files\perl.exe exiftool.pl} run inside {@code exiftool_files}: what the package's
 * launcher does, without the launcher's name-dependent pause, and without the install path on the Perl command line
 * (so folders with spaces or any Unicode characters work).
 */
public final class ExifToolRuntime {
    public static final String VERSION = "13.62";
    public static final String PACKAGE = "exiftool-13.62_64";
    static final String RESOURCE_ROOT = "exiftool/";
    static final String MANIFEST = "MANIFEST.sha256";

    /** A ready-to-run command: program and script, in their working directory. */
    public record Command(List<String> command, Path workingDirectory) {
        public Command { command = List.copyOf(command); Objects.requireNonNull(workingDirectory); }
    }
    record Entry(String sha256, long size, String path) { }

    private final Path cacheRoot;
    private final ClassLoader loader;
    private final boolean windows;
    private Command resolved;

    public ExifToolRuntime(Path cacheRoot) { this(cacheRoot, ExifToolRuntime.class.getClassLoader(), isWindows()); }
    ExifToolRuntime(Path cacheRoot, ClassLoader loader, boolean windows) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot); this.loader = Objects.requireNonNull(loader); this.windows = windows;
    }

    /** Per-user cache for the JAR case: %LOCALAPPDATA%\PhotoPilot\exiftool, else ~/.photopilot/cache/exiftool. */
    public static ExifToolRuntime forEnvironment(Map<String, String> environment) {
        String local = environment.getOrDefault("LOCALAPPDATA", "");
        Path root = !local.isBlank() ? Path.of(local, "PhotoPilot", "exiftool") : Path.of(System.getProperty("user.home"), ".photopilot", "cache", "exiftool");
        return new ExifToolRuntime(root);
    }
    static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"); }

    public synchronized Command resolve() throws ExifToolException {
        if (resolved != null && Files.isRegularFile(Path.of(resolved.command().getFirst()))) return resolved;
        if (!windows) throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The bundled ExifTool package is for Windows only");
        URL manifestUrl = loader.getResource(RESOURCE_ROOT + MANIFEST);
        if (manifestUrl == null) throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The ExifTool bundle is missing from the application");
        try {
            byte[] manifest;
            try (InputStream in = manifestUrl.openStream()) { manifest = in.readAllBytes(); }
            List<Entry> entries = entries(manifest);
            Path base = "file".equals(manifestUrl.getProtocol()) ? inPlace(Path.of(manifestUrl.toURI()).getParent(), entries) : extracted(manifest, entries);
            Path files = base.resolve(PACKAGE).resolve("exiftool_files");
            Path perl = files.resolve("perl.exe");
            if (!Files.isRegularFile(perl) || !Files.isRegularFile(files.resolve("exiftool.pl")))
                throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The ExifTool bundle has no Perl runtime or script");
            return resolved = new Command(List.of(perl.toString(), "exiftool.pl"), files);
        } catch (IOException | UncheckedIOException | URISyntaxException | InvalidPathException error) {
            throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The ExifTool bundle cannot be prepared: " + error.getClass().getSimpleName(), error);
        }
    }

    static List<Entry> entries(byte[] manifest) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : new String(manifest, StandardCharsets.UTF_8).split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split(" ", 3);
            if (parts.length != 3 || !parts[0].matches("[0-9a-f]{64}") || !parts[1].matches("\\d+")) throw new IOException("Malformed manifest line");
            String path = parts[2];
            // Only plain relative paths inside the package: never absolute, never "..".
            if (!path.startsWith(PACKAGE + "/") || path.contains("..") || path.contains("\\") || path.contains(":")) throw new IOException("Unexpected manifest path");
            entries.add(new Entry(parts[0], Long.parseLong(parts[1]), path));
        }
        if (entries.isEmpty()) throw new IOException("Empty manifest");
        return entries;
    }

    /** Development layout: verify presence and sizes (fast); full hashes are checked by the build's tests. */
    private static Path inPlace(Path base, List<Entry> entries) throws IOException, ExifToolException {
        for (Entry entry : entries) {
            Path file = base.resolve(entry.path());
            if (!Files.isRegularFile(file) || Files.size(file) != entry.size())
                throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The ExifTool bundle is incomplete");
        }
        return base;
    }

    /** JAR layout: extract to a fresh folder, verify every hash, then publish the folder with one atomic move. */
    private Path extracted(byte[] manifest, List<Entry> entries) throws IOException, ExifToolException {
        String id = VERSION + "-" + HexFormat.of().formatHex(sha256().digest(manifest)).substring(0, 16);
        Path target = cacheRoot.resolve(id);
        if (complete(target, id, entries)) return target;
        Files.createDirectories(cacheRoot);
        Path staging = Files.createTempDirectory(cacheRoot, ".extract-");
        try {
            for (Entry entry : entries) {
                Path file = staging.resolve(entry.path());
                Files.createDirectories(file.getParent());
                try (InputStream raw = loader.getResourceAsStream(RESOURCE_ROOT + entry.path())) {
                    if (raw == null) throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The ExifTool bundle is incomplete");
                    var digest = sha256();
                    try (var in = new DigestInputStream(raw, digest)) { Files.copy(in, file); }
                    if (Files.size(file) != entry.size() || !HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))
                        throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "The ExifTool bundle failed its integrity check");
                }
            }
            Files.writeString(staging.resolve(".complete"), id, StandardCharsets.UTF_8);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException | AccessDeniedException raced) {
                // Another PhotoPilot window extracted it at the same time; use theirs if it is complete.
                if (!complete(target, id, entries)) {
                    // Otherwise it is an earlier copy damaged later (a file deleted or truncated), which is never complete
                    // again and would block every new copy: move it aside, then publish once more under the same rule.
                    discard(target);
                    try {
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileAlreadyExistsException | DirectoryNotEmptyException | AccessDeniedException again) {
                        if (!complete(target, id, entries)) throw again;
                    }
                }
            }
            return target;
        } finally {
            if (Files.exists(staging)) deleteTree(staging);
        }
    }
    /**
     * Moves an incomplete copy to a unique sibling name, so the published name is free at once, and deletes it there
     * (best effort). Nothing to do when it is gone already, e.g. another PhotoPilot window moved it first.
     */
    private static void discard(Path target) throws IOException {
        Path aside = target.resolveSibling(".damaged-" + UUID.randomUUID());
        try {
            Files.move(target, aside, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchFileException alreadyMoved) {
            return;
        }
        deleteTree(aside);
    }
    /**
     * Whether the published copy is exactly the bundled package: marker, presence and size first (cheap), then every
     * file's SHA-256 against {@code entries}, which come from the manifest inside the application, never from the cache,
     * so a damaged or replaced cached file cannot vouch for itself (Stage2OptRe2: same-length corruption). This runs once
     * per runtime instance; later calls reuse the resolved command.
     */
    private static boolean complete(Path target, String id, List<Entry> entries) throws IOException {
        Path marker = target.resolve(".complete");
        try {
            if (!Files.isRegularFile(marker) || !Files.readString(marker, StandardCharsets.UTF_8).equals(id)) return false;
        } catch (IOException unreadable) { return false; } // a damaged marker is a damaged copy, repaired like any other
        for (Entry entry : entries) {
            Path file = target.resolve(entry.path());
            if (!Files.isRegularFile(file) || Files.size(file) != entry.size()) return false;
        }
        for (Entry entry : entries) if (!hash(target.resolve(entry.path())).equals(Optional.of(entry.sha256()))) return false;
        return true;
    }
    /** SHA-256 of a cached file; empty when it cannot be read (a damaged copy, repaired like any other). */
    private static Optional<String> hash(Path file) {
        var digest = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException | SecurityException unreadable) { return Optional.empty(); }
        return Optional.of(HexFormat.of().formatHex(digest.digest()));
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
