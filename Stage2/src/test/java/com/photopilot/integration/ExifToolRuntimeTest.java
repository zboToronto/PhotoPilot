package com.photopilot.integration;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The vendored official ExifTool package: integrity, location from class files on disk, and extraction from a JAR. */
class ExifToolRuntimeTest {
    @TempDir Path temp;
    private static final Path VENDORED = Path.of("third-party", "exiftool");

    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) { in.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)); }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Test void vendoredPackageMatchesItsManifestByteForByte() throws Exception {
        var entries = ExifToolRuntime.entries(Files.readAllBytes(VENDORED.resolve(ExifToolRuntime.MANIFEST)));
        assertEquals(511, entries.size(), "every file of exiftool-13.62_64.zip");
        Set<String> listed = new TreeSet<>();
        for (var entry : entries) {
            Path file = VENDORED.resolve(entry.path());
            assertTrue(Files.isRegularFile(file), entry.path());
            assertEquals(entry.size(), Files.size(file), entry.path());
            assertEquals(entry.sha256(), sha256(file), entry.path() + " changed (line endings converted?)");
            listed.add(entry.path());
        }
        Set<String> present = new TreeSet<>();
        try (var walk = Files.walk(VENDORED.resolve(ExifToolRuntime.PACKAGE))) {
            walk.filter(Files::isRegularFile).forEach(file -> present.add(VENDORED.relativize(file).toString().replace('\\', '/')));
        }
        assertEquals(listed, present, "no file missing from or added to the official package");
        for (String required : List.of("exiftool(-k).exe", "exiftool_files/perl.exe", "exiftool_files/perl532.dll", "exiftool_files/exiftool.pl",
            "exiftool_files/lib/Image/ExifTool/Sony.pm", "exiftool_files/lib/Image/ExifTool/Nikon.pm", "exiftool_files/LICENSE", "exiftool_files/Licenses_Strawberry_Perl.zip"))
            assertTrue(listed.contains(ExifToolRuntime.PACKAGE + "/" + required), required);
        assertTrue(Files.readString(VENDORED.resolve("NOTICE.md")).contains("9c79e97a9444ba1f893da1dcbcae378fba0c0a0112afd8bf426b396c75f884ce"), "notice records the official checksum");
        String header = Files.readString(VENDORED.resolve(ExifToolRuntime.PACKAGE).resolve("exiftool_files/lib/Image/ExifTool.pm"), StandardCharsets.ISO_8859_1);
        assertTrue(header.contains("Copyright (c) 2003-2026, Phil Harvey") && header.contains("same terms as Perl itself"), "original attribution kept");
    }

    @Test void buildOutputCarriesTheBundleOnTheClassPath() throws Exception {
        URL manifest = getClass().getClassLoader().getResource("exiftool/MANIFEST.sha256");
        assertNotNull(manifest, "Maven copies third-party/exiftool to exiftool/");
        assertEquals("file", manifest.getProtocol());
        try (InputStream in = manifest.openStream()) { assertArrayEquals(Files.readAllBytes(VENDORED.resolve(ExifToolRuntime.MANIFEST)), in.readAllBytes()); }
        assertNotNull(getClass().getClassLoader().getResource("exiftool/NOTICE.md"));
    }

    @Test void resolvesInPlaceFromClassFilesOnDisk() throws Exception {
        assumeTrue(ExifToolRuntime.isWindows(), "the bundled package is the Windows build");
        var command = new ExifToolRuntime(temp.resolve("unused-cache")).resolve();
        assertTrue(command.command().getFirst().endsWith("perl.exe"));
        assertTrue(Files.isRegularFile(Path.of(command.command().getFirst())));
        assertEquals("exiftool.pl", command.command().get(1), "the script is relative, so the install path never reaches Perl's command line");
        assertEquals("exiftool_files", command.workingDirectory().getFileName().toString());
        assertFalse(Files.exists(temp.resolve("unused-cache")), "nothing is extracted when running from class files");
    }

    /** A JAR with the same exiftool/ entries as the packaged application; optionally one file's bytes altered. */
    private Path jar(String name, boolean tamper) throws Exception {
        Path classes = Path.of(getClass().getClassLoader().getResource("exiftool/MANIFEST.sha256").toURI()).getParent();
        Path jar = temp.resolve(name);
        try (var out = new ZipOutputStream(Files.newOutputStream(jar)); var walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String entry = "exiftool/" + classes.relativize(file).toString().replace('\\', '/');
                out.putNextEntry(new ZipEntry(entry));
                byte[] bytes = Files.readAllBytes(file);
                if (tamper && entry.endsWith("/README.txt")) bytes[0] ^= 1;
                out.write(bytes);
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test void extractsFromAJarIntoAUnicodePathVerifiesAndRuns() throws Exception {
        assumeTrue(ExifToolRuntime.isWindows(), "the bundled package is the Windows build");
        Path cache = temp.resolve("Program Files 程序 ü").resolve("PhotoPilot cache");
        try (var loader = new URLClassLoader(new URL[] { jar("photopilot app.jar", false).toUri().toURL() }, null)) {
            var runtime = new ExifToolRuntime(cache, loader, true);
            var command = runtime.resolve();
            Path folder = command.workingDirectory().getParent().getParent();
            assertTrue(folder.startsWith(cache), "extracted under the per-user cache");
            assertTrue(folder.getFileName().toString().startsWith(ExifToolRuntime.VERSION + "-"));
            assertTrue(Files.isRegularFile(folder.resolve(".complete")));
            try (var listing = Files.list(cache)) { assertEquals(1, listing.count(), "no staging folder is left behind"); }
            long extractedAt = Files.getLastModifiedTime(folder.resolve(".complete")).toMillis();
            // A second runtime (next application start) reuses the verified copy.
            assertEquals(command, new ExifToolRuntime(cache, loader, true).resolve());
            assertEquals(extractedAt, Files.getLastModifiedTime(folder.resolve(".complete")).toMillis());
            // The extracted package really runs.
            try (var process = new ExifToolProcess(runtime::resolve, Duration.ofSeconds(60))) {
                assertEquals(ExifToolRuntime.VERSION, new ExifToolAdapter(process).version());
            }
        }
    }

    @Test void aTamperedJarIsRejectedAndLeavesNothingBehind() throws Exception {
        Path cache = temp.resolve("cache");
        try (var loader = new URLClassLoader(new URL[] { jar("tampered.jar", true).toUri().toURL() }, null)) {
            var failure = assertThrows(ExifToolException.class, () -> new ExifToolRuntime(cache, loader, true).resolve());
            assertEquals(ExifToolException.Kind.UNAVAILABLE, failure.kind());
            assertTrue(failure.getMessage().contains("integrity"), failure.getMessage());
        }
        try (var listing = Files.list(cache)) { assertEquals(0, listing.count(), "no partial or unverified copy"); }
    }

    @Test void aDamagedPublishedCacheIsReplacedByAFreshVerifiedCopy() throws Exception {
        assumeTrue(ExifToolRuntime.isWindows(), "the bundled package is the Windows build");
        Path cache = temp.resolve("cache");
        var sizes = new HashMap<String, Long>();
        ExifToolRuntime.entries(Files.readAllBytes(VENDORED.resolve(ExifToolRuntime.MANIFEST))).forEach(entry -> sizes.put(entry.path(), entry.size()));
        String script = ExifToolRuntime.PACKAGE + "/exiftool_files/exiftool.pl", readme = ExifToolRuntime.PACKAGE + "/README.txt";
        try (var loader = new URLClassLoader(new URL[] { jar("app.jar", false).toUri().toURL() }, null)) {
            var command = new ExifToolRuntime(cache, loader, true).resolve();
            Path folder = command.workingDirectory().getParent().getParent();
            // A truncated file (interrupted sync, disk cleaner), then a deleted one: each next start repairs the cache.
            Files.write(folder.resolve(script), new byte[0]);
            assertEquals(command, assertDoesNotThrow(() -> new ExifToolRuntime(cache, loader, true).resolve()));
            assertEquals(sizes.get(script), Files.size(folder.resolve(script)));
            Files.delete(folder.resolve(readme));
            assertEquals(command, assertDoesNotThrow(() -> new ExifToolRuntime(cache, loader, true).resolve()));
            assertEquals(sizes.get(readme), Files.size(folder.resolve(readme)));
            // An unreadable completion marker (not UTF-8) is a damaged copy too.
            Files.write(folder.resolve(".complete"), new byte[] { (byte) 0xFF, (byte) 0xFE, (byte) 0xC3 });
            assertEquals(command, assertDoesNotThrow(() -> new ExifToolRuntime(cache, loader, true).resolve()));
            try (var listing = Files.list(cache)) { assertEquals(List.of(folder.getFileName()), listing.map(Path::getFileName).toList(), "no damaged or staging copy is left"); }
            long extractedAt = Files.getLastModifiedTime(folder.resolve(".complete")).toMillis();
            assertEquals(command, new ExifToolRuntime(cache, loader, true).resolve());
            assertEquals(extractedAt, Files.getLastModifiedTime(folder.resolve(".complete")).toMillis(), "the repaired copy is reused, not extracted again");
        }
    }

    @Test void missingBundleAndOtherSystemsAreReportedAsUnavailable() throws Exception {
        try (var empty = new URLClassLoader(new URL[0], null)) {
            assertEquals(ExifToolException.Kind.UNAVAILABLE, assertThrows(ExifToolException.class, () -> new ExifToolRuntime(temp, empty, true).resolve()).kind());
        }
        assertEquals(ExifToolException.Kind.UNAVAILABLE, assertThrows(ExifToolException.class,
            () -> new ExifToolRuntime(temp, getClass().getClassLoader(), false).resolve()).kind());
    }

    @Test void manifestPathsCannotEscapeThePackage() {
        String sha = "0".repeat(64);
        for (String path : List.of("../evil.exe", "C:/Windows/evil.exe", "exiftool-13.62_64/../../evil", "other/evil.exe", "exiftool-13.62_64\\evil.exe"))
            assertThrows(java.io.IOException.class, () -> ExifToolRuntime.entries((sha + " 1 " + path).getBytes(StandardCharsets.UTF_8)), path);
    }
}
