package com.photopilot.integration;

import com.photopilot.model.ShutterCountResult.Status;
import com.photopilot.review.ExifFixtures;
import com.photopilot.service.CameraModelCatalog;
import com.photopilot.service.ShutterCountService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The real bundled ExifTool (not a mock and not a global install) behind the shutter count service. */
class ExifToolIntegrationTest {
    @TempDir Path temp;
    private ExifToolProcess process;
    private ExifToolAdapter adapter;
    private ShutterCountService service;
    private Path folder;

    @BeforeEach void start() throws Exception {
        assumeTrue(ExifToolRuntime.isWindows(), "the bundled package is the Windows build");
        process = new ExifToolProcess(new ExifToolRuntime(temp.resolve("cache"))::resolve, Duration.ofSeconds(60));
        adapter = new ExifToolAdapter(process);
        service = new ShutterCountService(adapter, CameraModelCatalog.bundled());
        folder = temp.resolve("照片 测试 ü (copy)");
    }
    @AfterEach void stop() { if (process != null) process.close(); }

    private record Snapshot(byte[] bytes, FileTime modified) { }
    private static Snapshot snapshot(Path file) throws Exception { return new Snapshot(Files.readAllBytes(file), Files.getLastModifiedTime(file)); }
    private static void unchanged(Snapshot before, Path file) throws Exception {
        assertArrayEquals(before.bytes(), Files.readAllBytes(file), "read-only: content unchanged");
        assertEquals(before.modified(), Files.getLastModifiedTime(file), "read-only: modification time unchanged");
        try (var siblings = Files.list(file.getParent())) {
            assertTrue(siblings.noneMatch(path -> path.getFileName().toString().endsWith("_original")), "ExifTool wrote no backup file");
        }
    }

    @Test void bundledVersionAnswersAndOneProcessServesSequentialChecks() throws Exception {
        assertFalse(process.running(), "not started before the first request");
        assertEquals("13.62", adapter.version());
        Path sony = ExifFixtures.jpeg(folder, "索尼 A7R V.jpg", ExifFixtures.airshow(400, 2500, 800, "2026:09:08 11:26:03").withCamera("SONY", "ILCE-7RM5"));
        Path canon = ExifFixtures.jpeg(folder, "canon.JPG", ExifFixtures.airshow(400, 2500, 800, "2026:09:08 11:26:03").withCamera("Canon", "Canon EOS R5"));
        Path plain = ExifFixtures.plainJpeg(folder, "plain.jpeg");
        var before = snapshot(sony);

        var noMakerNotes = service.check(sony);
        assertTrue(noMakerNotes.isSuccess(), noMakerNotes.errors().toString());
        var result = noMakerNotes.value().orElseThrow();
        assertEquals(Status.MAKERNOTES_MISSING, result.status(), "EXIF Make/Model alone carry no counter");
        assertEquals("shutter.reason.noMakerNotesJpeg", result.reason().orElseThrow().key());
        assertEquals("索尼 A7R V.jpg", result.fileName(), "Unicode file names and folders work; only the name is kept");
        assertEquals("JPEG", result.fileType());
        assertEquals("ILCE-7RM5", result.model().orElseThrow());
        assertEquals("ILCE-7RM5", result.catalogModel().orElseThrow().id());
        assertEquals("2026:09:08 11:26:03", result.captureDate().orElseThrow());
        assertTrue(result.counter().isEmpty(), "never a substituted zero");
        unchanged(before, sony);

        assertEquals(Status.UNSUPPORTED_BRAND, service.check(canon).value().orElseThrow().status());
        assertEquals(Status.NO_CAMERA, service.check(plain).value().orElseThrow().status());
        assertEquals(1, process.starts(), "one ExifTool process reused for every request");
    }

    @Test void damagedAndUnsupportedFilesAreReportedNotGuessed() throws Exception {
        byte[] tiff = new byte[300]; tiff[0] = 'I'; tiff[1] = 'I'; tiff[2] = 42;
        for (int i = 4; i < tiff.length; i++) tiff[i] = (byte) (i * 37);
        Files.createDirectories(folder);
        Path brokenRaw = Files.write(folder.resolve("broken.ARW"), tiff);
        Path brokenJpeg = ExifFixtures.corruptJpeg(folder, "broken.jpg");
        Path png = ExifFixtures.png(folder, "image.png");
        Path renamed = Files.write(folder.resolve("renamed.NEF"), Files.readAllBytes(ExifFixtures.plainJpeg(folder, "source.jpg")));
        assertTrue(service.check(brokenRaw).hasError("shutter.error.corrupt"));
        assertTrue(service.check(brokenJpeg).hasError("shutter.error.corrupt"));
        assertTrue(service.check(png).hasError("shutter.error.unsupportedFormat"));
        assertTrue(service.check(renamed).hasError("shutter.error.contentMismatch"));
        assertTrue(service.check(folder.resolve("missing.ARW")).hasError("shutter.error.notFound"));
        assertArrayEquals(tiff, Files.readAllBytes(brokenRaw));
    }

    @Test void adapterKeepsTheMetadataPathOfEveryField() throws Exception {
        Path sony = ExifFixtures.jpeg(folder, "a.jpg", ExifFixtures.airshow(400, 2500, 800, "2026:09:08 11:26:03").withCamera("SONY", "ILCE-7RM5"));
        var metadata = adapter.read(sony);
        assertTrue(metadata.error().isEmpty());
        var model = metadata.all("Model").getFirst();
        assertTrue(model.directory().contains("IFD0"), model.directory());
        assertFalse(metadata.makerNotesPresent());
        assertTrue(metadata.fields().stream().noneMatch(field -> field.value().contains(folder.toString().replace('\\', '/'))), "no file path is kept");
        assertTrue(Arrays.stream(new String[] { "SerialNumber", "GPSLatitude", "OwnerName" }).noneMatch(tag -> !metadata.all(tag).isEmpty()));
    }
}
