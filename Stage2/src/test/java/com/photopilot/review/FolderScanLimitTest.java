package com.photopilot.review;

import com.photopilot.integration.ExifLibraryAdapter;
import com.photopilot.model.MetadataScan;
import com.photopilot.model.MetadataScan.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.review.ExifFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** UC12 folder selections around {@link ExifLibraryAdapter#MAX_FILES}: the same error above it, unchanged results at it. */
class FolderScanLimitTest {
    @TempDir Path dir;
    final ExifLibraryAdapter adapter = new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS);

    /** {@code candidates} empty .jpg files (cheap: unsupported content), a text file and a subfolder named like a photo. */
    private Path folder(String name, int candidates) throws Exception {
        Path folder = Files.createDirectories(dir.resolve(name));
        for (int i = candidates - 1; i >= 0; i--) Files.createFile(folder.resolve(String.format("p%05d.jpg", i)));
        Files.writeString(folder.resolve("readme.txt"), "ignored");
        Files.createDirectories(folder.resolve("nested.jpg"));
        return folder;
    }

    @Test void aFolderAboveTheLimitFailsWithTheSameError() throws Exception {
        var result = adapter.scan(List.of(folder("big", ExifLibraryAdapter.MAX_FILES + 25)));
        assertFalse(result.isSuccess());
        assertTrue(result.hasError("review.error.tooManyFiles"), result.errors().toString());
        assertEquals(List.of(ExifLibraryAdapter.MAX_FILES), result.messages().getFirst().arguments());
    }

    @Test void aFolderAtTheLimitIsScannedInNameOrderAndOneMoreSelectedFileExceedsIt() throws Exception {
        var full = folder("full", ExifLibraryAdapter.MAX_FILES);
        var photo = jpeg(dir, "extra.jpg", airshow(300, 2000, 400, "2026:11:06 14:00:00"));
        var scan = adapter.scan(List.of(full));
        assertTrue(scan.isSuccess(), scan.errors().toString());
        var names = scan.value().orElseThrow().files().stream().map(MetadataScan.FileOutcome::name).toList();
        assertEquals(ExifLibraryAdapter.MAX_FILES, names.size(), "only the folder's own files; the subfolder and text file are not candidates");
        assertEquals(names.stream().sorted().toList(), names, "sorted by name");
        assertTrue(scan.value().orElseThrow().files().stream().allMatch(file -> file.status() == Status.UNSUPPORTED));
        assertTrue(adapter.scan(List.of(full, photo)).hasError("review.error.tooManyFiles"));
        assertTrue(adapter.scan(List.of(photo, full)).hasError("review.error.tooManyFiles"));
        var both = adapter.scan(List.of(photo, folder("small", 3)));
        assertEquals(List.of("extra.jpg", "p00000.jpg", "p00001.jpg", "p00002.jpg"),
            both.value().orElseThrow().files().stream().map(MetadataScan.FileOutcome::name).toList());
    }
}
