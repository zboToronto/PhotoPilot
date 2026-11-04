package com.photopilot.ui.privacy;

import com.photopilot.gearimport.ImportFixtures;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F04 privacy boundary: content validation, real pixel redaction, metadata removal, approval and cleanup. */
class ImagePrivacyBoundaryTest {
    @TempDir Path directory;
    private final ImagePrivacyBoundary privacy = new ImagePrivacyBoundary();

    @Test void validImagesAreRecognizedByContentNotExtension() throws Exception {
        Path png = ImportFixtures.png(directory, "camera.jpg", 400, 300); // PNG content behind a misleading extension
        var opened = privacy.open(png, ImageReview.Kind.PHOTO);
        assertTrue(opened.isSuccess(), opened.errors().toString());
        assertEquals("PNG", opened.value().orElseThrow().format());
        assertEquals(400, opened.value().orElseThrow().width());
        var jpeg = privacy.open(ImportFixtures.jpeg(directory, "label.jpeg", 320, 240), ImageReview.Kind.LABEL);
        assertTrue(jpeg.isSuccess(), jpeg.errors().toString());
        assertEquals("JPEG", jpeg.value().orElseThrow().format());
    }

    @Test void unsupportedCorruptMissingAndOversizedInputsAreRejectedSafely() throws Exception {
        Path gif = directory.resolve("animated.png");
        ImageIO.write(ImportFixtures.image(64, 64), "gif", gif.toFile());
        assertTrue(privacy.open(gif, ImageReview.Kind.PHOTO).hasError("import.error.format"));
        Path text = Files.writeString(directory.resolve("notes.png"), "not an image");
        assertTrue(privacy.open(text, ImageReview.Kind.PHOTO).hasError("import.error.format"));
        byte[] valid = Files.readAllBytes(ImportFixtures.png(directory, "valid.png", 200, 200));
        byte[] truncated = java.util.Arrays.copyOf(valid, 40);
        Path corrupt = Files.write(directory.resolve("corrupt.png"), truncated);
        assertTrue(privacy.open(corrupt, ImageReview.Kind.PHOTO).hasError("import.error.corrupt"));
        assertTrue(privacy.open(directory.resolve("missing.png"), ImageReview.Kind.PHOTO).hasError("import.error.missing"));
        assertTrue(privacy.open(directory, ImageReview.Kind.PHOTO).hasError("import.error.notFile"));
        Path empty = Files.write(directory.resolve("empty.png"), new byte[0]);
        assertTrue(privacy.open(empty, ImageReview.Kind.PHOTO).hasError("import.error.corrupt"));
        assertTrue(new ImagePrivacyBoundary(1024, ImagePrivacyBoundary.DEFAULT_MAX_PIXELS)
            .open(ImportFixtures.png(directory, "big.png", 600, 600), ImageReview.Kind.PHOTO).hasError("import.error.tooLarge"));
        assertTrue(new ImagePrivacyBoundary(ImagePrivacyBoundary.DEFAULT_MAX_BYTES, 10_000)
            .open(ImportFixtures.png(directory, "pixels.png", 200, 200), ImageReview.Kind.PHOTO).hasError("import.error.dimensions"));
        assertTrue(privacy.open(ImportFixtures.png(directory, "tiny.png", 20, 20), ImageReview.Kind.PHOTO).hasError("import.error.tooSmall"));
        assertThrows(IllegalArgumentException.class, () -> ImagePrivacyBoundary.fromEnvironment(java.util.Map.of("PHOTOPILOT_IMPORT_MAX_MB", "0")));
        assertEquals(5L * 1024 * 1024, ImagePrivacyBoundary.fromEnvironment(java.util.Map.of("PHOTOPILOT_IMPORT_MAX_MB", "5")).maxBytes());
    }

    @Test void redactionReplacesPixelsCropAndDownscaleApplyAndMetadataIsDropped() throws Exception {
        byte[] original = ImportFixtures.withTextChunk(Files.readAllBytes(ImportFixtures.png(directory, "plain.png", 3000, 1500)),
            "Author", "SECRET-OWNER-NAME");
        Path source = Files.write(directory.resolve("order.png"), original);
        var review = privacy.open(source, ImageReview.Kind.SCREENSHOT).value().orElseThrow();
        assertTrue(review.redact(ImportFixtures.PERSONAL.x, ImportFixtures.PERSONAL.y, ImportFixtures.PERSONAL.width, ImportFixtures.PERSONAL.height));
        assertFalse(review.redact(5000, 5000, 10, 10), "areas outside the image are rejected");
        Path copy = privacy.sanitize(review).value().orElseThrow();
        byte[] sanitized = Files.readAllBytes(copy);
        assertFalse(new String(sanitized, StandardCharsets.ISO_8859_1).contains("SECRET-OWNER-NAME"), "source metadata must not be copied");
        BufferedImage out = ImageIO.read(copy.toFile());
        assertEquals(ImagePrivacyBoundary.OUTGOING_MAX_EDGE, out.getWidth(), "long edge is downscaled for transmission");
        assertEquals(0, countRed(out), "redacted pixels must be replaced, not overlaid");
        double factor = 3000.0 / out.getWidth();
        assertEquals(0x000000, out.getRGB((int) (40 / factor), (int) (20 / factor)) & 0xFFFFFF, "the redacted area is solid black");
        assertArrayEquals(original, Files.readAllBytes(source), "the original file is never modified");

        assertTrue(review.crop(1000, 500, 400, 300));
        Path cropped = privacy.sanitize(review).value().orElseThrow();
        assertFalse(Files.exists(copy), "a new copy replaces the previous one");
        BufferedImage crop = ImageIO.read(cropped.toFile());
        assertEquals(400, crop.getWidth()); assertEquals(300, crop.getHeight());
    }

    @Test void approvalIsExplicitLimitedToUnchangedApplicationCopiesAndCleanupKeepsOriginal() throws Exception {
        Path source = ImportFixtures.png(directory, "lens.png", 500, 400);
        byte[] before = sha(Files.readAllBytes(source));
        var review = privacy.open(source, ImageReview.Kind.PHOTO).value().orElseThrow();
        Path copy = privacy.sanitize(review).value().orElseThrow();
        assertTrue(privacy.approveRedacted(copy, false).hasError("import.error.approvalRequired"));
        assertTrue(privacy.approveRedacted(source, true).hasError("import.error.notSanitized"), "an original can never be approved as sanitized");
        var approved = privacy.approveRedacted(copy, true);
        assertTrue(approved.isSuccess(), approved.errors().toString());
        var image = approved.value().orElseThrow();
        assertTrue(image.userApproved());
        assertArrayEquals(Files.readAllBytes(copy), image.approvedBytes().orElseThrow());
        privacy.discard(image);
        assertTrue(image.cleared()); assertTrue(image.approvedBytes().isEmpty()); assertFalse(Files.exists(copy));
        assertFalse(privacy.discard(source), "only application-created copies are deleted");
        assertTrue(Files.exists(source)); assertArrayEquals(before, sha(Files.readAllBytes(source)));

        Path second = privacy.sanitize(review).value().orElseThrow();
        Files.write(second, new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        assertTrue(privacy.approveRedacted(second, true).hasError("import.error.copyChanged"), "a copy altered after review is refused");
        assertFalse(Files.exists(second));
        Path third = privacy.sanitize(review).value().orElseThrow();
        privacy.discard(review);
        assertTrue(review.released()); assertFalse(Files.exists(third)); assertTrue(Files.exists(source));
        assertTrue(privacy.sanitize(review).hasError("import.error.noImage"));
    }

    private static int countRed(BufferedImage image) {
        int red = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            if (((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60) red++;
        }
        return red;
    }
    private static byte[] sha(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
}
