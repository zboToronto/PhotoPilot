package com.photopilot.review;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.integration.ExifLibraryAdapter;
import com.photopilot.integration.ExifToolAdapter;
import com.photopilot.integration.ExifToolProcess;
import com.photopilot.integration.ExifToolRuntime;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.MetadataScan.FileOutcome;
import com.photopilot.model.MetadataScan.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RAW metadata through the real bundled ExifTool (not a mock and not a global install). The files are synthetic
 * TIFF-based containers carrying known EXIF values, so they test the request, ExifTool's real answers and the mapping, but
 * they are not camera RAW files. A real RAW photograph can be checked with PHOTOPILOT_RAW_SAMPLE (path to the file, kept
 * outside the repository); that check is skipped when it is not set.
 */
class RawExifToolIntegrationTest {
    @TempDir Path temp;
    private ExifToolProcess process;
    private ExifLibraryAdapter adapter;

    @BeforeEach void start() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"), "the bundled package is the Windows build");
        process = new ExifToolProcess(new ExifToolRuntime(temp.resolve("cache"))::resolve, Duration.ofSeconds(60));
        adapter = new ExifLibraryAdapter(ExifLibraryAdapter.ALL_FORMATS, new ExifToolAdapter(process)::reviewJson);
    }
    @AfterEach void stop() { if (process != null) process.close(); }

    private static FileOutcome outcome(MetadataScan scan, String name) {
        return scan.files().stream().filter(file -> file.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " in " + scan.files()));
    }
    private static ExifRecord record(MetadataScan scan, String name) {
        return scan.records().stream().filter(r -> r.sourceName().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name)).normalized();
    }

    @Test void tiffBasedRawFilesGiveTheSameValuesAsTheJpegReaderAndAreConfirmedByContent() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("照片 测试 ü (raw)"));
        var photo = ExifFixtures.airshow(400, 2500, 800, "2026:11:06 14:32:10").withCamera("SONY", "ILCE-7RM5");
        Path arw = Files.write(folder.resolve("索尼 DSC 0001.ARW"), ExifFixtures.tiff(photo));
        Path jpeg = ExifFixtures.jpeg(folder, "same values.jpg", photo);
        Path dngWithoutVersion = Files.write(folder.resolve("plain.DNG"), ExifFixtures.tiff(photo.withIso(1600)));
        Path canonAsNef = Files.write(folder.resolve("canon.NEF"), ExifFixtures.tiff(photo.withCamera("Canon", "Canon EOS R5")));
        byte[] garbage = new byte[300]; garbage[0] = 'I'; garbage[1] = 'I'; garbage[2] = 42;
        for (int i = 4; i < garbage.length; i++) garbage[i] = (byte) (i * 37);
        Path broken = Files.write(folder.resolve("broken.ARW"), garbage);
        byte[] before = Files.readAllBytes(arw); var modified = Files.getLastModifiedTime(arw);

        var result = adapter.scan(List.of(arw, jpeg, dngWithoutVersion, canonAsNef, broken));
        assertTrue(result.isSuccess(), result.errors().toString());
        var scan = result.value().orElseThrow();
        scan.files().forEach(file -> System.out.println("  " + file.name() + " [" + file.kind() + "] " + file.status() + " " + file.notes()));

        var raw = outcome(scan, "索尼 DSC 0001.ARW");
        assertEquals("ARW", raw.kind());
        assertTrue(Set.of(Status.READ, Status.PARTIAL).contains(raw.status()), raw.toString());
        var fromRaw = record(scan, "索尼 DSC 0001.ARW");
        var fromJpeg = record(scan, "same values.jpg");
        assertEquals(java.util.Optional.of("SONY ILCE-7RM5"), fromRaw.cameraModel());
        assertEquals(fromJpeg.cameraModel(), fromRaw.cameraModel());
        assertEquals(fromJpeg.lensModel(), fromRaw.lensModel());
        assertEquals(fromJpeg.focalLengthMm(), fromRaw.focalLengthMm());
        assertEquals(fromJpeg.shutterSeconds(), fromRaw.shutterSeconds());
        assertEquals(fromJpeg.aperture(), fromRaw.aperture());
        assertEquals(fromJpeg.iso(), fromRaw.iso());
        assertEquals(fromJpeg.exposureCompensation(), fromRaw.exposureCompensation());
        assertEquals(fromJpeg.localCaptureTime(), fromRaw.localCaptureTime());
        assertEquals(fromJpeg.captureOffset(), fromRaw.captureOffset());

        for (String name : List.of("plain.DNG", "canon.NEF")) {
            assertEquals(Status.UNSUPPORTED, outcome(scan, name).status(), name);
            assertEquals("review.file.rawMismatch", outcome(scan, name).notes().getFirst().key(), name);
        }
        assertEquals(Status.CORRUPT, outcome(scan, "broken.ARW").status());

        assertArrayEquals(before, Files.readAllBytes(arw), "read-only: content unchanged");
        assertEquals(modified, Files.getLastModifiedTime(arw), "read-only: modification time unchanged");
        try (var siblings = Files.list(folder)) { assertTrue(siblings.noneMatch(p -> p.getFileName().toString().endsWith("_original")), "no backup written"); }
    }

    /** Opt-in: a real camera RAW file through the application's own composition (bootstrap, review service, ExifTool). */
    @Test void aRealRawPhotographWhenConfigured() throws Exception {
        String sample = System.getenv("PHOTOPILOT_RAW_SAMPLE");
        assumeTrue(sample != null && !sample.isBlank(), "real RAW sample not configured");
        Path photo = Path.of(sample);
        assumeTrue(Files.isRegularFile(photo), "real RAW sample not present");
        byte[] before = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(photo));
        var modified = Files.getLastModifiedTime(photo);
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", temp.resolve("data").toString(),
                "PHOTOPILOT_CONFIG_DIR", temp.resolve("config").toString(), "LOCALAPPDATA", temp.resolve("local").toString()))) {
            var result = app.facade().reviews().metadata().scan(List.of(photo));
            assertTrue(result.isSuccess(), result.errors().toString());
            var scan = result.value().orElseThrow();
            var file = scan.files().getFirst();
            String name = photo.getFileName().toString();
            assertEquals(name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT), file.kind());
            assertTrue(Set.of(Status.READ, Status.PARTIAL).contains(file.status()), file.toString());
            var record = scan.records().getFirst().normalized();
            System.out.printf("Real RAW %s [%s] %s: camera %s, lens %s, focal %s, shutter %s, f %s, ISO %s, missing %s%n", file.name(), file.kind(),
                file.status(), record.cameraModel().orElse("-"), record.lensModel().orElse("-"), record.focalLengthMm().map(String::valueOf).orElse("-"),
                record.shutterSeconds().map(String::valueOf).orElse("-"), record.aperture().map(String::valueOf).orElse("-"),
                record.iso().map(String::valueOf).orElse("-"), record.missingFields());
            assertTrue(record.cameraModel().isPresent(), "camera identity read from the RAW file");
            assertTrue(record.shutterSeconds().isPresent() && record.aperture().isPresent() && record.iso().isPresent(), record.missingFields().toString());
        }
        assertArrayEquals(before, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(photo)), "the photograph is never modified");
        assertEquals(modified, Files.getLastModifiedTime(photo));
    }
}
