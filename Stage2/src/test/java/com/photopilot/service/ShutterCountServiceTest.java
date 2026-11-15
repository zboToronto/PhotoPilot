package com.photopilot.service;

import com.photopilot.integration.CameraMetadataReader;
import com.photopilot.integration.ExifToolException;
import com.photopilot.model.CameraMetadata;
import com.photopilot.model.ShutterCountResult.ModelCheck;
import com.photopilot.model.ShutterCountResult.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.model.CameraModel.Brand.NIKON;
import static com.photopilot.model.CameraModel.Brand.SONY;
import static org.junit.jupiter.api.Assertions.*;

/** Input validation, failure mapping and model comparison, with a controlled metadata reader. */
class ShutterCountServiceTest {
    @TempDir Path temp;
    private final CameraModelCatalog catalog = CameraModelCatalog.bundled();
    private final List<Path> reads = new ArrayList<>();
    private final AtomicReference<CameraMetadata> answer = new AtomicReference<>();
    private final CameraMetadataReader reader = file -> { reads.add(file); return answer.get(); };
    private final ShutterCountService service = new ShutterCountService(reader, catalog);

    private Path tiff(String name) throws Exception { return Files.write(temp.resolve(name), new byte[] { 'I', 'I', 42, 0, 8, 0, 0, 0 }); }
    private static CameraMetadata.Field f(String directory, String tag, Object value) { return new CameraMetadata.Field(directory, tag, String.valueOf(value)); }
    private static CameraMetadata a7rv(String type) {
        String mn = "TIFF-IFD0-ExifIFD-MakerNotes";
        return new CameraMetadata(List.of(f("TIFF-IFD0", "Make", "SONY"), f("TIFF-IFD0", "Model", "ILCE-7RM5"), f("TIFF", "FileType", type),
            f("TIFF-IFD0-ExifIFD", "DateTimeOriginal", "2026:01:02 03:04:05"), f(mn + "-Tag9050c", "ShutterCount", 4321)), Optional.empty(), List.of());
    }

    @Test void invalidInputsAreRejectedBeforeExifToolRuns() throws Exception {
        assertTrue(service.check(null).hasError("shutter.error.noFile"));
        assertTrue(service.check(Files.writeString(temp.resolve("notes.txt"), "x")).hasError("shutter.error.unsupportedFormat"));
        assertTrue(service.check(temp.resolve("noextension")).hasError("shutter.error.unsupportedFormat"));
        assertTrue(service.check(temp.resolve("missing.ARW")).hasError("shutter.error.notFound"));
        assertTrue(service.check(Files.createDirectory(temp.resolve("folder.jpg"))).hasError("shutter.error.notFile"));
        assertTrue(service.check(Files.write(temp.resolve("empty.NEF"), new byte[0])).hasError("shutter.error.empty"));
        assertTrue(service.check(Files.writeString(temp.resolve("text.ARW"), "not a raw file")).hasError("shutter.error.contentMismatch"));
        assertTrue(service.check(tiff("tiff-named.jpg")).hasError("shutter.error.contentMismatch"));
        assertTrue(reads.isEmpty(), "nothing reached ExifTool");
    }

    @Test void readerFailuresMapToClearMessages() throws Exception {
        Path photo = tiff("a.ARW");
        for (var kind : ExifToolException.Kind.values()) {
            var failing = new ShutterCountService(file -> { throw new ExifToolException(kind, "x"); }, catalog);
            var result = failing.check(photo);
            assertFalse(result.isSuccess());
            String expected = switch (kind) {
                case UNAVAILABLE -> "shutter.error.exiftoolMissing"; case STARTUP -> "shutter.error.exiftoolStart"; case TIMEOUT -> "shutter.error.timeout";
                case TERMINATED -> "shutter.error.exiftoolStopped"; case CLOSED -> "shutter.error.closed";
            };
            assertTrue(result.hasError(expected), kind.name());
        }
        answer.set(new CameraMetadata(List.of(), Optional.of("File format error"), List.of()));
        assertTrue(service.check(photo).hasError("shutter.error.corrupt"));
        answer.set(new CameraMetadata(List.of(f("TIFF", "FileType", "ARW")), Optional.empty(), List.of("Bad IFD0 directory")));
        assertTrue(service.check(photo).hasError("shutter.error.corrupt"), "damaged files are not reported as 'no camera data'");
        answer.set(new CameraMetadata(List.of(f("TIFF", "FileType", "TIFF")), Optional.empty(), List.of()));
        assertTrue(service.check(tiff("scan.DNG")).hasError("shutter.error.unsupportedDetected"));
    }

    @Test void successKeepsOnlyTheFileNameAndComparesWithTheSelection() throws Exception {
        Path photo = tiff("DSC00001.ARW");
        answer.set(a7rv("ARW"));
        var result = service.check(photo).value().orElseThrow();
        assertEquals(photo.toAbsolutePath().normalize(), reads.getLast(), "ExifTool gets an absolute path");
        assertEquals(Status.SUCCESS, result.status());
        assertEquals("DSC00001.ARW", result.fileName());
        assertEquals(8, result.fileSize());
        assertEquals(4321, result.counter().orElseThrow().value());
        assertEquals("ILCE-7RM5", result.catalogModel().orElseThrow().id());
        assertEquals(ModelCheck.MATCH, service.compare(result, Optional.of(SONY), catalog.resolve(SONY, "ILCE-7RM5")));
        assertEquals(ModelCheck.MISMATCH, service.compare(result, Optional.of(SONY), catalog.resolve(SONY, "ILCE-7M4")));
        assertEquals(ModelCheck.BRAND_MISMATCH, service.compare(result, Optional.of(NIKON), catalog.resolve(NIKON, "D850")));
        assertEquals(ModelCheck.BRAND_MISMATCH, service.compare(result, Optional.of(NIKON), Optional.empty()));
        assertEquals(ModelCheck.NOT_SELECTED, service.compare(result, Optional.of(SONY), Optional.empty()));
    }

    @Test void extensionAndContentDisagreementIsNoted() throws Exception {
        answer.set(a7rv("NEF"));
        var result = service.check(tiff("renamed.ARW")).value().orElseThrow();
        assertTrue(result.notes().stream().anyMatch(note -> note.key().equals("shutter.note.extensionMismatch")));
    }

    @Test void modelsMissingFromTheCatalogCannotBeConfirmed() throws Exception {
        String mn = "TIFF-IFD0-ExifIFD-MakerNotes";
        answer.set(new CameraMetadata(List.of(f("TIFF-IFD0", "Make", "SONY"), f("TIFF-IFD0", "Model", "ILCE-9X"), f("TIFF", "FileType", "ARW"),
            f(mn, "SonyModelID", 1)), Optional.empty(), List.of()));
        var result = service.check(tiff("future.ARW")).value().orElseThrow();
        assertTrue(result.catalogModel().isEmpty());
        assertTrue(result.notes().stream().anyMatch(note -> note.key().equals("shutter.note.notInCatalog")));
        assertEquals(ModelCheck.UNKNOWN, service.compare(result, Optional.of(SONY), catalog.resolve(SONY, "ILCE-7RM5")));
    }
}
