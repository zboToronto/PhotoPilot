package com.photopilot.review;

import com.photopilot.integration.ExifLibraryAdapter;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.MetadataScan.Status;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.review.ExifFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** UC12 extraction through the real metadata-extractor library and ExifTool JSON reader, over synthetic fixtures. */
class ExifExtractionTest {
    @TempDir Path dir;
    final ExifLibraryAdapter adapter = new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS);

    MetadataScan scan(Path... files) {
        var result = adapter.scan(List.of(files));
        assertTrue(result.isSuccess(), () -> result.errors().toString());
        return result.value().orElseThrow();
    }
    static ExifRecord only(MetadataScan scan) { assertEquals(1, scan.records().size(), scan.files().toString()); return scan.records().getFirst().normalized(); }
    static byte[] sha(Path file) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)); }

    @Test void readsJpegExifWithExactValuesAndLeavesTheOriginalUntouched() throws Exception {
        var file = jpeg(dir, "jet.jpg", airshow(400, 2500, 800, "2026:11:06 14:32:10"));
        byte[] before = sha(file); var modified = Files.getLastModifiedTime(file);
        assertTrue(adapter.supports(file));
        var scan = scan(file);
        var record = only(scan);
        assertEquals(Status.READ, scan.files().getFirst().status());
        assertEquals("JPEG", scan.files().getFirst().kind());
        assertEquals("jet.jpg", record.sourceName(), "file name only, never the folder");
        assertEquals(Optional.of("Sony A1"), record.cameraModel(), "make + model without duplicating the make");
        assertEquals(Optional.of("Sony FE 200-600mm G"), record.lensModel());
        assertEquals(Optional.of(400.0), record.focalLengthMm());
        assertEquals(0.0004, record.shutterSeconds().orElseThrow(), 1e-15, "1/2500 s keeps its precision");
        assertEquals("1/2500 s", com.photopilot.model.ExifValues.shutter(record.shutterSeconds().orElseThrow()));
        assertEquals(Optional.of(8.0), record.aperture());
        assertEquals(Optional.of(800), record.iso());
        assertEquals(Optional.of(-0.33), record.exposureCompensation());
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 6, 14, 32, 10)), record.localCaptureTime());
        assertEquals(Optional.of(ZoneOffset.ofHours(-5)), record.captureOffset());
        assertEquals(Optional.of(Instant.parse("2026-11-06T19:32:10Z")), record.capturedAt());
        assertTrue(record.missingFields().isEmpty() && record.invalidFields().isEmpty(), record.missingFields().toString());
        assertArrayEquals(before, sha(file)); assertEquals(modified, Files.getLastModifiedTime(file));
        assertFalse(scan.fingerprint().isEmpty());
    }

    @Test void canonStyleModelAlreadyContainingTheMakeIsNotDuplicated() {
        assertEquals(Optional.of("Canon EOS R5"), ExifRecord.equipmentName("Canon", "Canon EOS R5"));
        assertEquals(Optional.of("NIKON Z 9"), ExifRecord.equipmentName("NIKON CORPORATION", "NIKON Z 9"));
        assertEquals(Optional.of("SONY ILCE-1"), ExifRecord.equipmentName("SONY", "ILCE-1"));
        assertEquals(Optional.empty(), ExifRecord.equipmentName("Canon", ""), "a make alone is no camera identity");
    }

    @Test void readsExifToolJsonExportsFormattedAndNumeric() throws Exception {
        var export = text(dir, "export.json", exiftool(List.of(
            entry("SourceFile", "C:/Photos/Airshow/DSC0001.JPG", "Make", "Canon", "Model", "Canon EOS R5", "LensModel", "RF100-500mm F4.5-7.1 L IS USM",
                "FocalLength", "500.0 mm (35 mm equivalent: 500.0 mm)", "ExposureTime", "1/3200", "FNumber", 7.1, "ISO", 1600,
                "ExposureCompensation", "-2/3", "DateTimeOriginal", "2026:11:06 15:01:02", "OffsetTimeOriginal", "-05:00", "GPSLatitude", "43 deg 39' 12.00\" N"),
            entry("SourceFile", "/photos/DSC0002.JPG", "Make", "Canon", "Model", "Canon EOS R5", "FocalLength", 300, "ExposureTime", 0.0005,
                "FNumber", "6.3", "ISO", "3200", "ExposureCompensation", 0, "DateTimeOriginal", "2026:11:06 15:05:00"))));
        var scan = scan(export);
        assertEquals(Status.READ, scan.files().getFirst().status()); assertEquals(2, scan.files().getFirst().records());
        var first = scan.records().get(0).normalized(); var second = scan.records().get(1).normalized();
        assertEquals("DSC0001.JPG", first.sourceName(), "only the exported file name is kept");
        assertEquals(Optional.of(500.0), first.focalLengthMm(), "actual focal length, not the 35 mm equivalent");
        assertEquals(1.0 / 3200, first.shutterSeconds().orElseThrow(), 1e-15);
        assertEquals(Optional.of(7.1), first.aperture()); assertEquals(Optional.of(1600), first.iso());
        assertEquals(Optional.of(-0.67), first.exposureCompensation());
        assertEquals(Optional.of(ZoneOffset.ofHours(-5)), first.captureOffset());
        assertEquals(Optional.of("Canon EOS R5"), second.cameraModel()); assertEquals(Optional.empty(), second.lensModel());
        assertTrue(second.missingFields().contains("lens") && !second.invalidFields().contains("lens"));
        assertEquals(Optional.of(0.0005), second.shutterSeconds()); assertEquals(Optional.of(0.0), second.exposureCompensation());
        assertEquals(Optional.empty(), second.captureOffset(), "no offset recorded: no invented zone");
        assertTrue(second.localCaptureTime().isPresent() && second.capturedAt().isEmpty());
    }

    @Test void unsupportedCorruptEmptyMissingAndNoExifInputsAreReportedPerFile() throws Exception {
        var good = jpeg(dir, "good.jpg", airshow(300, 2000, 400, "2026:11:06 14:00:00"));
        var renamedPng = png(dir, "fake.jpg");
        var textFile = text(dir, "notes.txt", "not metadata");
        var corrupt = corruptJpeg(dir, "broken.jpg");
        var stripped = plainJpeg(dir, "stripped.jpg");
        var sessions = text(dir, "other.json", "{\"schemaVersion\":1,\"sessions\":[]}");
        var malformed = text(dir, "bad.json", "[{\"SourceFile\": ");
        var empty = text(dir, "empty.jpg", "");
        var missing = dir.resolve("gone.jpg");
        var scan = scan(good, renamedPng, textFile, corrupt, stripped, sessions, malformed, empty, missing, good);
        var byName = new LinkedHashMap<String, MetadataScan.FileOutcome>();
        scan.files().forEach(f -> byName.putIfAbsent(f.name() + "#" + f.status(), f));
        assertEquals(Status.READ, scan.files().get(0).status());
        assertEquals(Status.UNSUPPORTED, scan.files().get(1).status(), "content type is detected from bytes, not the .jpg extension");
        assertTrue(scan.files().get(1).notes().getFirst().arguments().contains("PNG"), scan.files().get(1).notes().toString());
        assertEquals(Status.UNSUPPORTED, scan.files().get(2).status());
        assertEquals(Status.CORRUPT, scan.files().get(3).status());
        assertEquals(Status.NO_METADATA, scan.files().get(4).status());
        assertEquals(Status.INVALID_STRUCTURE, scan.files().get(5).status(), "arbitrary JSON is not an ExifTool export");
        assertEquals(Status.CORRUPT, scan.files().get(6).status());
        assertEquals(Status.UNSUPPORTED, scan.files().get(7).status(), "an empty file has no recognisable content");
        assertEquals(Status.MISSING, scan.files().get(8).status());
        assertEquals(Status.DUPLICATE, scan.files().get(9).status(), "the same path twice is read once");
        assertEquals(2, scan.records().size(), "the good photo plus the JPEG without EXIF");
        assertEquals(7, scan.failedFiles());
        assertFalse(adapter.supports(renamedPng)); assertFalse(adapter.supports(textFile)); assertFalse(adapter.supports(missing));
        var none = scan.records().get(1).normalized();
        assertTrue(none.missingFields().containsAll(ExifRecord.FIELDS)); assertFalse(none.validate().isSuccess());
    }

    @Test void emptySelectionIsRejectedAndFoldersContributeTheirOwnFiles() throws Exception {
        assertTrue(adapter.scan(List.of()).hasError("review.error.noFiles"));
        assertTrue(adapter.scan(null).hasError("review.error.noFiles"));
        var folder = dir.resolve("card");
        jpeg(folder, "a.jpg", airshow(200, 1600, 400, "2026:11:06 14:00:00"));
        jpeg(folder, "b.JPEG", airshow(600, 3200, 1600, "2026:11:06 14:10:00"));
        text(folder, "readme.txt", "ignored");
        Files.createDirectories(folder.resolve("nested")); jpeg(folder.resolve("nested"), "c.jpg", airshow(300, 2000, 400, "2026:11:06 14:20:00"));
        var scan = scan(folder);
        assertEquals(List.of("a.jpg", "b.JPEG"), scan.files().stream().map(MetadataScan.FileOutcome::name).toList(), "subfolders are not searched");
        var emptyFolder = Files.createDirectories(dir.resolve("blank"));
        assertEquals(Status.UNSUPPORTED, scan(emptyFolder).files().getFirst().status());
    }

    @Test void identicalContentUnderAnotherNameCountsOnceAndOversizedExportsAreRejected() throws Exception {
        var a = jpeg(dir, "a.jpg", airshow(200, 1600, 400, "2026:11:06 14:00:00"));
        var copy = Files.copy(a, dir.resolve("copy-of-a.jpg"));
        var scan = scan(a, copy);
        assertEquals(1, scan.records().size()); assertEquals(Status.DUPLICATE, scan.files().get(1).status());
        assertTrue(scan.files().get(1).notes().getFirst().arguments().contains("a.jpg"));
        var big = dir.resolve("huge.json");
        Files.writeString(big, "[");
        try (var file = new RandomAccessFile(big.toFile(), "rw")) { file.setLength(ExifLibraryAdapter.MAX_EXPORT_BYTES + 1); }
        assertEquals(Status.TOO_LARGE, scan(big).files().getFirst().status());
    }

    @Test void missingOptionalFieldsAreHandledIndividually() throws Exception {
        var noCamera = jpeg(dir, "no-camera.jpg", airshow(300, 2000, 400, "2026:11:06 14:00:00").withCamera("Sony", null));
        var noLens = jpeg(dir, "no-lens.jpg", airshow(300, 2000, 400, "2026:11:06 14:01:00").withLens(null, null));
        var noFocal = jpeg(dir, "no-focal.jpg", airshow(300, 2000, 400, "2026:11:06 14:02:00").withFocal(null));
        var noExposure = jpeg(dir, "no-exposure.jpg", airshow(300, 2000, 400, null).withExposure(null).withFNumber(null).withIso(null));
        var scan = scan(noCamera, noLens, noFocal, noExposure);
        var records = scan.records().stream().map(ExifRecord::normalized).toList();
        assertEquals(Optional.empty(), records.get(0).cameraModel(), "a make without a model is not a camera");
        assertEquals(Optional.of(300.0), records.get(0).focalLengthMm(), "the rest of the record is kept");
        assertEquals(Optional.empty(), records.get(1).lensModel()); assertTrue(records.get(1).validate().isSuccess());
        assertEquals(Optional.empty(), records.get(2).focalLengthMm()); assertEquals(Optional.of(400), records.get(2).iso());
        assertTrue(records.get(3).missingFields().containsAll(Set.of("shutter", "aperture", "iso", "captureTime")));
        assertTrue(records.get(3).validate().isSuccess(), "camera and lens still make the record usable");
        assertTrue(records.stream().allMatch(r -> r.invalidFields().isEmpty()), "absent values are missing, not invalid");
    }

    @Test void invalidNumericalMetadataIsExcludedAndNamed() throws Exception {
        var zeroDenominator = jpeg(dir, "zero.jpg", airshow(300, 2000, 400, "2026:11:06 14:00:00").withFocal(r(3000, 0)));
        var saturated = jpeg(dir, "saturated.jpg", airshow(300, 2000, 65535, "2026:11:06 14:00:01"));
        var impossible = jpeg(dir, "impossible.jpg", airshow(300, 2000, 400, "2026:13:40 25:00:00").withFNumber(r(1, 10)));
        var json = text(dir, "bad-values.json", exiftool(List.of(entry("SourceFile", "x.jpg", "Make", "Canon", "Model", "EOS R6", "FocalLength", "abc",
            "ExposureTime", "1/0", "ISO", "-5", "FNumber", "2.8"))));
        var records = scan(zeroDenominator, saturated, impossible, json).records().stream().map(ExifRecord::normalized).toList();
        assertEquals(Set.of("focalLength"), records.get(0).invalidFields());
        assertEquals(Set.of("iso"), records.get(1).invalidFields(), "65535 means 65535 or more; not used as a value");
        assertEquals(Set.of("aperture", "captureTime"), records.get(2).invalidFields(), "f/0.1 and an impossible date are rejected, not clamped");
        assertEquals(Set.of("focalLength", "shutter", "iso"), records.get(3).invalidFields());
        assertEquals(Optional.of(2.8), records.get(3).aperture());
        assertTrue(records.stream().allMatch(r -> r.missingFields().containsAll(r.invalidFields())));
    }
}
