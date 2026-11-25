package com.photopilot.review;

import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.integration.ExifLibraryAdapter;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.MetadataScan.Status;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.progress.OperationContext;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.ExifAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static com.photopilot.review.ExifFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 X02 / Review2 #04: the photo records of one UC12 scan are bounded across all its files
 * ({@link ExifLibraryAdapter#MAX_RECORDS}), not only per file. Above the bound the scan is refused as a whole (never
 * truncated) and previously saved statistics stay as they were. Small limits stand in for large fixtures.
 */
class AggregateExifLimitTest {
    @TempDir Path dir;

    static ExifLibraryAdapter limited(int records) { return new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS, null, records); }
    /** An ExifTool export with {@code count} distinct photo entries. */
    Path export(String name, int count) throws Exception {
        var entries = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++)
            entries.add(entry("SourceFile", name + "/DSC" + i + ".ARW", "Make", "SONY", "Model", "ILCE-1", "FocalLength", "400.0 mm",
                "ExposureTime", "1/2000", "FNumber", 5.6, "ISO", 400, "DateTimeOriginal", "2026:11:06 14:" + String.format("%02d", i % 60) + ":00"));
        return text(dir, name + ".json", exiftool(entries));
    }
    static int records(Result<MetadataScan> scan) { return scan.value().orElseThrow().records().size(); }

    @Test void emptySingleAndMultipleSelectionsBelowTheBoundAreUnchanged() throws Exception {
        var adapter = limited(10);
        var empty = Files.createDirectories(dir.resolve("empty"));
        var none = adapter.scan(List.of(empty));
        assertTrue(none.isSuccess());
        assertEquals(List.of(Status.UNSUPPORTED), none.value().orElseThrow().files().stream().map(MetadataScan.FileOutcome::status).toList(), "an empty folder is reported");
        assertEquals(0, records(none));
        assertEquals(1, records(adapter.scan(List.of(jpeg(dir, "one.jpg", airshow(300, 2000, 400, "2026:11:06 14:00:00"))))));
        assertEquals(7, records(adapter.scan(List.of(export("a", 3), export("b", 4)))));
    }

    @Test void exactlyTheBoundIsAcceptedAndOneMoreRecordRefusesTheWholeScan() throws Exception {
        var adapter = limited(10);
        var exact = adapter.scan(List.of(export("six", 6), export("four", 4)));
        assertTrue(exact.isSuccess(), exact.errors()::toString);
        assertEquals(10, records(exact));
        var over = adapter.scan(List.of(export("six2", 6), export("five", 5)));
        assertFalse(over.isSuccess(), "individually valid files that together exceed the bound");
        assertTrue(over.hasError("review.error.tooManyRecords"), over.errors()::toString);
        assertEquals(List.of(10), over.messages().getFirst().arguments());
        assertTrue(over.value().isEmpty(), "no partial result is returned");
        var jpegs = new ArrayList<Path>();
        for (int i = 0; i < 11; i++) jpegs.add(jpeg(dir, "p" + i + ".jpg", airshow(100 + i, 1000 + i, 200 + i, "2026:11:06 14:0" + (i % 10) + ":00")));
        assertTrue(adapter.scan(jpegs.subList(0, 10)).isSuccess());
        assertTrue(adapter.scan(jpegs).hasError("review.error.tooManyRecords"), "the bound counts JPEG records too");
    }

    @Test void corruptedFilesDoNotCountAndNestedFoldersAreNotTraversed() throws Exception {
        var adapter = limited(5);
        var folder = Files.createDirectories(dir.resolve("shoot"));
        for (int i = 0; i < 5; i++) jpeg(folder, "p" + i + ".jpg", airshow(200, 1000, 100 + i, "2026:11:06 15:0" + i + ":00"));
        corruptJpeg(folder, "broken.jpg");
        text(folder, "broken.json", "{ not json");
        var nested = Files.createDirectories(folder.resolve("more"));
        for (int i = 0; i < 3; i++) jpeg(nested, "n" + i + ".jpg", airshow(50, 500, 800, "2026:11:06 16:0" + i + ":00"));
        var scan = adapter.scan(List.of(folder));
        assertTrue(scan.isSuccess(), scan.errors()::toString);
        assertEquals(5, records(scan), "subfolder photos are not read, corrupted files add no records");
        var statuses = scan.value().orElseThrow().files().stream().map(MetadataScan.FileOutcome::status).toList();
        assertEquals(2, statuses.stream().filter(s -> s == Status.CORRUPT).count(), statuses::toString);
        assertTrue(adapter.scan(List.of(folder, nested)).hasError("review.error.tooManyRecords"), "selecting the subfolder explicitly counts it");
    }

    @Test void aCancelledScanStopsBeforeReadingFurtherFiles() throws Exception {
        var files = List.of(export("first", 2), export("second", 2));
        var operation = OperationContext.create("F11", "reviewAnalyze", List.of(), true);
        assertTrue(operation.cancel());
        var outcome = operation.run(() -> limited(10).scan(files));
        Thread.interrupted();
        assertTrue(outcome.hasError("progress.cancelled"), outcome.errors()::toString);
        assertTrue(outcome.value().isEmpty());
    }

    @Test void theDefaultBoundIsCentralAndRefusingAnImportKeepsTheSavedStatistics() throws Exception {
        assertEquals(50_000, ExifLibraryAdapter.MAX_RECORDS);
        assertTrue(ExifLibraryAdapter.MAX_RECORDS > ExifLibraryAdapter.MAX_EXPORT_ENTRIES, "one maximal export always fits");
        assertThrows(IllegalArgumentException.class, () -> limited(0));
        Path root = Files.createDirectories(dir.resolve("profile")), photos = Files.createDirectories(dir.resolve("photos"));
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = ReviewFixtures.completed(app, gemini, new PlanFixtures.Gear());
            var saved = ok(app.facade().reviews().analysis(id, ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id)), false));
            assertEquals(ExifAnalysis.Status.SAVED, saved.status());
            var store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = Files.readAllBytes(store);
            // Three exports at the per-file maximum: each is valid alone, together they exceed the default bound.
            var exports = List.of(export("x1", ExifLibraryAdapter.MAX_EXPORT_ENTRIES), export("x2", ExifLibraryAdapter.MAX_EXPORT_ENTRIES),
                export("x3", ExifLibraryAdapter.MAX_EXPORT_ENTRIES));
            var refused = app.facade().reviews().analysis(id, exports, true);
            assertTrue(refused.hasError("review.error.tooManyRecords"), refused.errors()::toString);
            assertArrayEquals(before, Files.readAllBytes(store), "previously saved statistics are kept byte for byte");
            assertEquals(saved.statistics().orElseThrow().usableCount(), ok(app.facade().sessions().open(id)).statistics().orElseThrow().usableCount());
            app.localization().useLanguage("zh-CN");
            assertTrue(refused.messages().getFirst().render(app.localization()).contains("条照片记录"));
        }
    }
}
