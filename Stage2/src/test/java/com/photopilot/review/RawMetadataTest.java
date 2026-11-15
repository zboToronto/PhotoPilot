package com.photopilot.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.integration.ExifLibraryAdapter;
import com.photopilot.integration.ExifToolException;
import com.photopilot.model.ExifRecord;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.MetadataScan.FileOutcome;
import com.photopilot.model.MetadataScan.Status;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Post-shoot review of camera RAW files (ARW, RAF, CR3, NEF, DNG) through the metadata adapter, with ExifTool's answer
 * supplied by the test: format-specific headers, confirmation from ExifTool's answer, mapping identical to an imported
 * ExifTool JSON export, missing values, per-file failures, the 256 MB limit, streamed duplicate detection and folder
 * discovery. The real bundled ExifTool is exercised in {@link RawExifToolIntegrationTest}.
 */
class RawMetadataTest {
    @TempDir Path dir;
    private final Map<String, String> answers = new HashMap<>();
    private final List<Path> requested = new ArrayList<>();
    private ExifToolException failure;
    private final ExifLibraryAdapter adapter = new ExifLibraryAdapter(ExifLibraryAdapter.ALL_FORMATS, file -> {
        requested.add(file);
        if (failure != null) throw failure;
        return answers.getOrDefault(file.getFileName().toString(), "");
    });

    // ------------------------------------------------------------------ fixtures (synthetic, never user photographs)

    private static final Map<String, byte[]> HEADERS = Map.of(
        "ARW", new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0},
        "NEF", new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8},
        "DNG", new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0},
        "RAF", "FUJIFILMCCD-RAW 0201FF383501".getBytes(StandardCharsets.US_ASCII),
        "CR3", new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'c', 'r', 'x', ' ', 0, 0, 0, 1});

    /** A small file with the format's header and unique filler (so files never share content by accident). */
    private Path raw(Path folder, String name, String type) throws Exception {
        var out = new ByteArrayOutputStream();
        out.writeBytes(HEADERS.get(type)); out.writeBytes(("synthetic " + name).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(folder);
        return Files.write(folder.resolve(name), out.toByteArray());
    }
    /** ExifTool -json -n answer: one entry, echoing a full path as SourceFile like ExifTool does. */
    private void answer(String name, Object... pairs) {
        var entry = new JsonObject();
        entry.addProperty("SourceFile", "C:/Users/someone/private folder/" + name);
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] instanceof Number number) entry.addProperty((String) pairs[i], number);
            else entry.addProperty((String) pairs[i], String.valueOf(pairs[i + 1]));
        }
        var array = new JsonArray(); array.add(entry);
        answers.put(name, array.toString());
    }
    private static Object[] sony() {
        return new Object[]{"FileType", "ARW", "Make", "SONY", "Model", "ILCE-7RM5", "LensModel", "FE 24-70mm F2.8 GM II",
            "FocalLength", 35, "ExposureTime", 0.004, "FNumber", 2.8, "ISO", 100, "ExposureCompensation", -0.3,
            "DateTimeOriginal", "2026:11:06 14:00:00", "OffsetTimeOriginal", "-05:00"};
    }
    private MetadataScan scan(ExifLibraryAdapter extractor, Path... files) {
        var result = extractor.scan(List.of(files));
        assertTrue(result.isSuccess(), () -> result.errors().toString());
        return result.value().orElseThrow();
    }
    private static FileOutcome outcome(MetadataScan scan, String name) {
        return scan.files().stream().filter(file -> file.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " in " + scan.files()));
    }
    private static ExifRecord record(MetadataScan scan, String name) {
        return scan.records().stream().filter(r -> r.sourceName().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name)).normalized();
    }
    private static void assertNote(FileOutcome outcome, String key) {
        assertTrue(outcome.notes().stream().anyMatch(note -> note.key().equals(key)), outcome.toString());
    }

    // ------------------------------------------------------------------ tests

    @Test void allFiveRawFormatsAreReadThroughExifToolAndMappedLikeAJsonExport() throws Exception {
        Path arw = raw(dir, "a7r5.ARW", "ARW"), raf = raw(dir, "xt5.raf", "RAF"), cr3 = raw(dir, "r5.CR3", "CR3"),
            nef = raw(dir, "z8.nef", "NEF"), dng = raw(dir, "q3.dng", "DNG");
        answer("a7r5.ARW", sony());
        answer("xt5.raf", "FileType", "RAF", "Make", "FUJIFILM", "Model", "X-T5", "LensModel", "XF23mmF1.4 R LM WR", "FocalLength", 23,
            "ExposureTime", 0.0005, "FNumber", 1.4, "ISO", 125, "ExposureCompensation", 0, "DateTimeOriginal", "2026:11:06 14:01:00");
        answer("r5.CR3", "FileType", "CR3", "Make", "Canon", "Model", "Canon EOS R5", "LensModel", "RF100-500mm F4.5-7.1 L IS USM",
            "FocalLength", 500, "ExposureTime", 0.0003125, "FNumber", 7.1, "ISO", 3200, "DateTimeOriginal", "2026:11:06 14:02:00");
        answer("z8.nef", "FileType", "NEF", "Make", "NIKON CORPORATION", "Model", "NIKON Z 8", "LensModel", "NIKKOR Z 24-120mm f/4 S",
            "FocalLength", 120, "ExposureTime", 0.002, "FNumber", 4, "ISO", 640, "DateTimeOriginal", "2026:11:06 14:03:00");
        answer("q3.dng", "FileType", "DNG", "DNGVersion", "1 6 0 0", "Make", "LEICA CAMERA AG", "Model", "LEICA Q3",
            "FocalLength", 28, "ExposureTime", 0.01, "FNumber", 1.7, "ISO", 200, "DateTimeOriginal", "2026:11:06 14:04:00");
        for (Path file : List.of(arw, raf, cr3, nef, dng)) assertTrue(adapter.supports(file), file.toString());

        var scan = scan(adapter, arw, raf, cr3, nef, dng);
        assertEquals(5, scan.records().size(), scan.files().toString());
        assertEquals(List.of("ARW", "RAF", "CR3", "NEF", "DNG"), scan.files().stream().map(FileOutcome::kind).toList());
        assertTrue(scan.files().stream().allMatch(file -> file.status() == Status.READ), scan.files().toString());
        assertEquals(List.of(arw, raf, cr3, nef, dng).stream().map(p -> p.toAbsolutePath().normalize()).toList(),
            requested.stream().map(p -> p.toAbsolutePath().normalize()).toList(), "one ExifTool request per file, in order");

        var sony = record(scan, "a7r5.ARW");
        assertEquals("a7r5.ARW", sony.sourceName(), "file name only, never ExifTool's SourceFile path");
        assertEquals(Optional.of("SONY ILCE-7RM5"), sony.cameraModel());
        assertEquals(Optional.of("FE 24-70mm F2.8 GM II"), sony.lensModel());
        assertEquals(Optional.of(35.0), sony.focalLengthMm());
        assertEquals(0.004, sony.shutterSeconds().orElseThrow(), 1e-15);
        assertEquals(Optional.of(2.8), sony.aperture()); assertEquals(Optional.of(100), sony.iso());
        assertEquals(Optional.of(-0.3), sony.exposureCompensation());
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 6, 14, 0)), sony.localCaptureTime());
        assertEquals(Optional.of(ZoneOffset.ofHours(-5)), sony.captureOffset());
        assertEquals(Optional.of("Canon EOS R5"), record(scan, "r5.CR3").cameraModel());
        assertEquals(Optional.of(500.0), record(scan, "r5.CR3").focalLengthMm());
        assertEquals(Optional.of("NIKON Z 8"), record(scan, "z8.nef").cameraModel());
        assertEquals(Optional.of(1.7), record(scan, "q3.dng").aperture());
        assertEquals(Optional.of(125), record(scan, "xt5.raf").iso());
        assertFalse(scan.fingerprint().isEmpty());

        // The same ExifTool entry imported as a JSON export gives the identical record.
        Path export = Files.writeString(dir.resolve("export.json"), answers.get("a7r5.ARW"));
        var exported = scan(new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS), export).records().getFirst().normalized();
        for (var field : List.<java.util.function.Function<ExifRecord, Object>>of(ExifRecord::cameraModel, ExifRecord::lensModel, ExifRecord::focalLengthMm,
                ExifRecord::shutterSeconds, ExifRecord::aperture, ExifRecord::iso, ExifRecord::exposureCompensation, ExifRecord::localCaptureTime, ExifRecord::captureOffset))
            assertEquals(field.apply(exported), field.apply(sony));
    }

    @Test void foldersContributeRawFilesCaseInsensitivelyNextToJpegAndJsonButNotSubfolders() throws Exception {
        Path folder = dir.resolve("shoot");
        raw(folder, "A.ARW", "ARW"); raw(folder, "b.Raf", "RAF"); raw(folder, "c.cr3", "CR3"); raw(folder, "d.NEF", "NEF"); raw(folder, "e.DnG", "DNG");
        ExifFixtures.jpeg(folder, "f.jpg", ExifFixtures.airshow(400, 2500, 800, "2026:11:06 14:32:10"));
        Files.writeString(folder.resolve("g.json"), ExifFixtures.exiftool(List.of(ExifFixtures.entry("SourceFile", "x.jpg", "Model", "A1", "ISO", 400))));
        Files.writeString(folder.resolve("h.tif"), "ignored");
        raw(folder.resolve("sub"), "i.arw", "ARW");
        answer("A.ARW", sony());
        answer("b.Raf", "FileType", "RAF", "Make", "FUJIFILM", "Model", "X-H2", "ISO", 160);
        answer("c.cr3", "FileType", "CR3", "Make", "Canon", "Model", "Canon EOS R6", "ISO", 800);
        answer("d.NEF", "FileType", "NEF", "Make", "NIKON CORPORATION", "Model", "NIKON Z 6_3", "ISO", 400);
        answer("e.DnG", "FileType", "DNG", "DNGVersion", "1 4 0 0", "Make", "Ricoh", "Model", "GR III", "ISO", 200);

        var scan = scan(adapter, folder);
        assertEquals(List.of("A.ARW", "b.Raf", "c.cr3", "d.NEF", "e.DnG", "f.jpg", "g.json"), scan.files().stream().map(FileOutcome::name).toList());
        assertEquals(7, scan.records().size());
        assertTrue(requested.stream().noneMatch(p -> p.getFileName().toString().equals("i.arw")), "subfolders are not searched");

        // Without RAW support the folder contributes exactly what it did before.
        requested.clear();
        var jpegJsonOnly = scan(new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS), folder);
        assertEquals(List.of("f.jpg", "g.json"), jpegJsonOnly.files().stream().map(FileOutcome::name).toList());
        var explicit = scan(new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS), folder.resolve("A.ARW"));
        assertEquals(Status.UNSUPPORTED, explicit.files().getFirst().status());
        assertThrows(IllegalArgumentException.class, () -> new ExifLibraryAdapter(ExifLibraryAdapter.ALL_FORMATS), "RAW needs an ExifTool reader");
    }

    @Test void theContentNotTheExtensionDecidesAndExifToolConfirmsTheRawFormat() throws Exception {
        Path tiffAsCr3 = raw(dir, "tiff.CR3", "ARW");
        Path textAsRaf = Files.writeString(dir.resolve("notes.RAF"), "not a raw file");
        Path jpegAsNef = Files.copy(ExifFixtures.jpeg(dir, "source.jpg", ExifFixtures.airshow(200, 1000, 400, "2026:11:06 14:00:00")), dir.resolve("photo.NEF"));
        Path dngAsArw = raw(dir, "converted.ARW", "ARW");
        Path canonAsArw = raw(dir, "canon.ARW", "ARW");
        Path tiffAsDng = raw(dir, "plain.DNG", "DNG");
        Path anonymousNef = raw(dir, "nomake.NEF", "NEF");
        answer("converted.ARW", "FileType", "DNG", "DNGVersion", "1 6 0 0", "Make", "SONY", "Model", "ILCE-7M4");
        answer("canon.ARW", "FileType", "ARW", "Make", "Canon", "Model", "Canon EOS R5");
        answer("plain.DNG", "FileType", "DNG", "Make", "SONY", "Model", "ILCE-7M4");
        answer("nomake.NEF", "FileType", "NEF", "ISO", 100);

        var scan = scan(adapter, tiffAsCr3, textAsRaf, jpegAsNef, dngAsArw, canonAsArw, tiffAsDng, anonymousNef);
        for (String name : List.of("tiff.CR3", "notes.RAF", "converted.ARW", "canon.ARW", "plain.DNG", "nomake.NEF")) {
            var outcome = outcome(scan, name);
            assertEquals(Status.UNSUPPORTED, outcome.status(), name);
            assertNote(outcome, "review.file.rawMismatch");
            assertEquals(0, outcome.records());
        }
        assertEquals("CR3", String.valueOf(outcome(scan, "tiff.CR3").notes().getFirst().arguments().getFirst()), "the expected format is named");
        assertEquals("DNG", String.valueOf(outcome(scan, "converted.ARW").notes().getFirst().arguments().get(1)), "what ExifTool found is named");
        assertTrue(requested.stream().noneMatch(p -> p.getFileName().toString().equals("tiff.CR3") || p.getFileName().toString().equals("notes.RAF")),
            "a file without the format's header is never sent to ExifTool");
        assertEquals("JPEG", outcome(scan, "photo.NEF").kind(), "JPEG content is read as JPEG whatever the extension");
        assertEquals(1, scan.records().size());
    }

    @Test void exifToolErrorsAreReportedPerFileAndAnUnavailableOrHangingExifToolIsNotAskedAgain() throws Exception {
        Path broken = raw(dir, "broken.ARW", "ARW"), locked = raw(dir, "locked.ARW", "ARW"), silent = raw(dir, "silent.ARW", "ARW");
        answers.put("broken.ARW", "[{\"SourceFile\":\"x\",\"Error\":\"File format error\"}]");
        answers.put("locked.ARW", "[{\"SourceFile\":\"x\",\"Error\":\"Error opening file\"}]");
        var scan = scan(adapter, broken, locked, silent);
        assertEquals(Status.CORRUPT, outcome(scan, "broken.ARW").status()); assertNote(outcome(scan, "broken.ARW"), "review.file.corruptRaw");
        assertEquals(Status.UNREADABLE, outcome(scan, "locked.ARW").status()); assertNote(outcome(scan, "locked.ARW"), "review.file.unreadable");
        assertEquals(Status.CORRUPT, outcome(scan, "silent.ARW").status(), "no answer is not metadata");
        assertTrue(scan.records().isEmpty());

        requested.clear();
        failure = new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "missing");
        Path first = raw(dir, "first.ARW", "ARW"), second = raw(dir, "second.NEF", "NEF");
        Path jpeg = ExifFixtures.jpeg(dir, "still-read.jpg", ExifFixtures.airshow(400, 2500, 800, "2026:11:06 14:32:10"));
        var unavailable = scan(adapter, first, second, jpeg);
        assertEquals(1, requested.size(), "after ExifTool was unavailable once, it is not started again for this scan");
        for (String name : List.of("first.ARW", "second.NEF")) {
            assertEquals(Status.UNREADABLE, outcome(unavailable, name).status());
            assertNote(outcome(unavailable, name), "review.file.exiftoolUnavailable");
        }
        assertEquals(Status.READ, outcome(unavailable, "still-read.jpg").status(), "JPEG files do not need ExifTool");
        assertEquals(1, unavailable.records().size());

        requested.clear();
        failure = new ExifToolException(ExifToolException.Kind.TIMEOUT, "slow");
        var slow = new ArrayList<Path>();
        for (int i = 0; i < 5; i++) slow.add(raw(dir, "slow" + i + ".ARW", "ARW"));
        var timeouts = scan(adapter, slow.toArray(Path[]::new));
        assertEquals(3, requested.size(), "three timeouts in a row stop further requests (each would wait the full timeout)");
        timeouts.files().forEach(file -> { assertEquals(Status.UNREADABLE, file.status()); assertNote(file, "review.file.exiftoolTimeout"); });

        failure = new ExifToolException(ExifToolException.Kind.TERMINATED, "crashed");
        var crashed = scan(adapter, raw(dir, "crash.ARW", "ARW"));
        assertNote(crashed.files().getFirst(), "review.file.exiftoolFailed");
    }

    @Test void missingValuesStayMissingAndWarningsMarkAPartialRead() throws Exception {
        Path sparse = raw(dir, "sparse.ARW", "ARW"), bare = raw(dir, "bare.DNG", "DNG"), warned = raw(dir, "warned.NEF", "NEF");
        answer("sparse.ARW", "FileType", "ARW", "Make", "SONY", "ExposureTime", 0.01);
        answer("bare.DNG", "FileType", "DNG", "DNGVersion", "1 6 0 0");
        answer("warned.NEF", "FileType", "NEF", "Make", "NIKON CORPORATION", "Model", "NIKON Z f", "ISO", 6400, "Warning", "[minor] Bad MakerNotes offset");
        var scan = scan(adapter, sparse, bare, warned);
        var record = record(scan, "sparse.ARW");
        assertEquals(Status.READ, outcome(scan, "sparse.ARW").status());
        assertEquals(0.01, record.shutterSeconds().orElseThrow(), 1e-15);
        assertTrue(record.cameraModel().isEmpty(), "a make without a model is no camera identity");
        assertTrue(record.missingFields().containsAll(List.of("camera", "lens", "focalLength", "aperture", "iso", "exposureCompensation", "captureTime")));
        assertEquals(Status.NO_METADATA, outcome(scan, "bare.DNG").status());
        assertNote(outcome(scan, "bare.DNG"), "review.file.noExif");
        assertEquals(1, outcome(scan, "bare.DNG").records(), "counted as a photo with missing metadata, like a JPEG without EXIF");
        assertEquals(Status.PARTIAL, outcome(scan, "warned.NEF").status());
        assertNote(outcome(scan, "warned.NEF"), "review.file.libraryWarnings");
        assertEquals(Optional.of(6400), record(scan, "warned.NEF").iso());
    }

    @Test void rawFilesUpTo256MegabytesAreHashedWhileStreamingAndLargerOnesAreNotRead() throws Exception {
        Path big = dir.resolve("big.ARW");
        try (var file = new RandomAccessFile(big.toFile(), "rw")) { file.write(HEADERS.get("ARW")); file.setLength(ExifLibraryAdapter.MAX_RAW_BYTES); }
        answer("big.ARW", sony());
        var exact = scan(adapter, big);
        assertEquals(Status.READ, exact.files().getFirst().status(), "exactly 256 MB is accepted");
        assertEquals(1, requested.size());

        requested.clear();
        try (var file = new RandomAccessFile(big.toFile(), "rw")) { file.setLength(ExifLibraryAdapter.MAX_RAW_BYTES + 1); }
        var tooLarge = scan(adapter, big);
        assertEquals(Status.TOO_LARGE, tooLarge.files().getFirst().status());
        assertEquals(List.of(256L), tooLarge.files().getFirst().notes().getFirst().arguments().stream().map(a -> ((Number) a).longValue()).toList());
        assertTrue(requested.isEmpty(), "a file over the limit is never sent to ExifTool");
        Files.delete(big);

        Path original = raw(dir, "same.ARW", "ARW");
        Path copy = Files.copy(original, dir.resolve("same copy.ARW"));
        answer("same.ARW", sony()); answer("same copy.ARW", sony());
        requested.clear();
        var duplicates = scan(adapter, original, copy);
        assertEquals(Status.DUPLICATE, outcome(duplicates, "same copy.ARW").status());
        assertEquals(1, requested.size(), "identical content is read once");
        assertEquals(1, duplicates.records().size());
    }

    @Test void unicodeAndSpacesInRawPathsReachExifToolAsOneArgument() throws Exception {
        Path folder = dir.resolve("照片 测试 ü (raw)");
        Path file = raw(folder, "索尼 A7R V 001.ARW", "ARW");
        answer("索尼 A7R V 001.ARW", sony());
        var scan = scan(adapter, file);
        assertEquals(file.toAbsolutePath().normalize(), requested.getFirst().toAbsolutePath().normalize());
        assertEquals("索尼 A7R V 001.ARW", scan.records().getFirst().sourceName());
        var folderScan = scan(adapter, folder);
        assertEquals(Status.READ, folderScan.files().getFirst().status());
    }
}
