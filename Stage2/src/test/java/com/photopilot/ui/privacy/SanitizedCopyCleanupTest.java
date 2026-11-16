package com.photopilot.ui.privacy;

import com.photopilot.common.Result;
import com.photopilot.gearimport.ImportFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F04 privacy boundary: a sanitized copy whose write fails (here: half written, then the disk is full) must not stay
 * in the temporary directory, because it holds part of the user's redacted photo.
 */
class SanitizedCopyCleanupTest {
    @TempDir Path directory;

    @Test void failedWriteLeavesNoSanitizedFileBehind() throws Exception {
        var attempted = new ArrayList<Path>();
        var full = new AtomicBoolean();
        var privacy = new ImagePrivacyBoundary() {
            @Override void write(Path copy, byte[] encoded) throws IOException {
                attempted.add(copy);
                if (!full.get()) { super.write(copy, encoded); return; }
                Files.write(copy, Arrays.copyOf(encoded, encoded.length / 2));
                throw new IOException("No space left on device");
            }
        };
        var review = privacy.open(ImportFixtures.png(directory, "lens.png", 500, 400), ImageReview.Kind.PHOTO).value().orElseThrow();
        assertTrue(review.redact(10, 10, 60, 20));
        Path previous = privacy.sanitize(review).value().orElseThrow();
        Path work = previous.getParent();
        try {
            full.set(true);
            Result<Path> failed = privacy.sanitize(review);
            assertTrue(failed.hasError("import.error.sanitize"), failed.errors().toString());
            assertEquals(2, attempted.size());
            Path partial = attempted.getLast();
            assertFalse(privacy.owns(partial), "a failed copy is never approvable");
            assertFalse(Files.exists(previous), "the previous copy was replaced");
            assertFalse(Files.exists(partial), "the partly written copy is deleted");
            assertEquals(List.of(), sanitized(work), "no sanitized copy remains in the work directory");

            full.set(false);
            Path retry = privacy.sanitize(review).value().orElseThrow();
            assertTrue(privacy.owns(retry), "a retry writes a new approvable copy");
            var approved = privacy.approveRedacted(retry, true);
            assertTrue(approved.isSuccess(), approved.errors().toString());
            privacy.discard(approved.value().orElseThrow());
        } finally {
            privacy.discard(review);
            for (Path left : sanitized(work)) Files.deleteIfExists(left); // never leave photo data in %TEMP% when this fails
        }
    }

    private static List<Path> sanitized(Path work) throws IOException {
        try (Stream<Path> files = Files.list(work)) {
            return files.filter(file -> file.getFileName().toString().startsWith("sanitized-")).toList();
        }
    }
}
