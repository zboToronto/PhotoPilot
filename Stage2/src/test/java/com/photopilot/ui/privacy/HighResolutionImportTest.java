package com.photopilot.ui.privacy;

import com.photopilot.gearimport.ImportFixtures;
import com.photopilot.gearimport.LargeImageFixtures;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F04 high-resolution limits and bounded decoding: 100 MiB / 100 MP defaults and their overrides, header-only limit
 * checks for common camera resolutions, real subsampled decoding of a 100 MP PNG and a 61 MP JPEG into a working image of
 * at most 4096 px, redaction that survives subsampling (outward rounding, non-zero crop origin), full-detail crops, a
 * source file changed after loading, an oversized sanitized copy and an undecodable JPEG variant.
 */
class HighResolutionImportTest {
    @TempDir Path dir;
    private final ImagePrivacyBoundary privacy = new ImagePrivacyBoundary();
    private static final int WHITE = 0xFFFFFF, RED = 0xFF0000, BLACK = 0x000000;
    /** Red areas standing in for personal data, deliberately not aligned to the subsampling step. */
    private static final Rectangle SECRET = new Rectangle(4001, 1001, 998, 498), SECRET_IN_CROP = new Rectangle(5851, 1951, 100, 30);
    /** One-pixel black/white stripes that only survive a full-resolution decode. */
    private static final Rectangle STRIPES = new Rectangle(6000, 2000, 1000, 500);

    private static int pattern(int x, int y) {
        if (SECRET.contains(x, y) || SECRET_IN_CROP.contains(x, y)) return RED;
        if (STRIPES.contains(x, y)) return x % 2 == 0 ? BLACK : WHITE;
        return WHITE;
    }
    private Path wide() throws Exception { return LargeImageFixtures.png(dir.resolve("wide 9000x3000.png"), 9000, 3000, true, HighResolutionImportTest::pattern); }
    private ImageReview open(ImagePrivacyBoundary boundary, Path file) {
        var opened = boundary.open(file, ImageReview.Kind.PHOTO);
        assertTrue(opened.isSuccess(), () -> opened.errors().toString());
        return opened.value().orElseThrow();
    }
    private BufferedImage sanitized(ImageReview review) throws Exception {
        var copy = privacy.sanitize(review);
        assertTrue(copy.isSuccess(), () -> copy.errors().toString());
        return ImageIO.read(copy.value().orElseThrow().toFile());
    }
    private static int red(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            if (((rgb >> 16) & 0xFF) > 150 && ((rgb >> 8) & 0xFF) < 100 && (rgb & 0xFF) < 100) count++;
        }
        return count;
    }

    @Test void defaultsAreOneHundredMegabytesAndMegapixelsAndLowerOverridesStillApply() throws Exception {
        assertEquals(100L * 1024 * 1024, ImagePrivacyBoundary.DEFAULT_MAX_BYTES);
        assertEquals(100_000_000, ImagePrivacyBoundary.DEFAULT_MAX_PIXELS);
        var defaults = ImagePrivacyBoundary.fromEnvironment(Map.of());
        assertEquals(100L * 1024 * 1024, defaults.maxBytes()); assertEquals(100_000_000, defaults.maxPixels());
        var lower = ImagePrivacyBoundary.fromEnvironment(Map.of("PHOTOPILOT_IMPORT_MAX_MB", "5", "PHOTOPILOT_IMPORT_MAX_MEGAPIXELS", "24"));
        assertEquals(5L * 1024 * 1024, lower.maxBytes()); assertEquals(24_000_000, lower.maxPixels());
        assertEquals(100L * 1024 * 1024, ImagePrivacyBoundary.fromEnvironment(Map.of("PHOTOPILOT_IMPORT_MAX_MB", "100")).maxBytes());
        for (String invalid : List.of("0", "101", "1000", "x", "2.5"))
            for (String name : List.of("PHOTOPILOT_IMPORT_MAX_MB", "PHOTOPILOT_IMPORT_MAX_MEGAPIXELS"))
                assertThrows(IllegalArgumentException.class, () -> ImagePrivacyBoundary.fromEnvironment(Map.of(name, invalid)), name + "=" + invalid);
        assertThrows(IllegalArgumentException.class, () -> new ImagePrivacyBoundary(100L * 1024 * 1024 + 1, 1_000_000), "never above 100 MB");
        assertThrows(IllegalArgumentException.class, () -> new ImagePrivacyBoundary(1024, 100_000_001), "never above 100 MP");

        // A configured lower megapixel limit is enforced from the header, exactly at its boundary.
        assertTrue(lower.open(LargeImageFixtures.headerOnlyPng(dir.resolve("24.png"), 6000, 4000), ImageReview.Kind.PHOTO).hasError("import.error.corrupt"),
            "24,000,000 px passes the 24 MP limit (and then fails only for its missing pixel data)");
        assertTrue(lower.open(LargeImageFixtures.headerOnlyPng(dir.resolve("24+.png"), 6000, 4001), ImageReview.Kind.PHOTO).hasError("import.error.dimensions"));
    }

    @Test void commonCameraResolutionsPassValidationAndLimitsHoldAtTheirBoundaries() throws Exception {
        // Header-only files: no pixel data, so only the size/dimension checks can accept or reject them.
        record Size(String name, int w, int h) { }
        for (var size : List.of(new Size("24MP", 6000, 4000), new Size("33MP", 7008, 4672), new Size("45MP", 8256, 5504),
                new Size("50MP", 8640, 5760), new Size("61MP", 9504, 6336), new Size("100MP exact", 10_000, 10_000),
                new Size("16000 px side, 100MP exact", 16_000, 6250), new Size("minimum", 32, 32))) {
            var result = privacy.open(LargeImageFixtures.headerOnlyPng(dir.resolve(size.name() + ".png"), size.w(), size.h()), ImageReview.Kind.PHOTO);
            assertTrue(result.hasError("import.error.corrupt"), size.name() + " must pass the limits: " + result.errors());
        }
        assertTrue(privacy.open(LargeImageFixtures.headerOnlyPng(dir.resolve("over.png"), 10_000, 10_001), ImageReview.Kind.PHOTO).hasError("import.error.dimensions"),
            "100,010,000 px exceeds 100 MP");
        assertTrue(privacy.open(LargeImageFixtures.headerOnlyPng(dir.resolve("side.png"), 16_001, 100), ImageReview.Kind.PHOTO).hasError("import.error.dimensions"),
            "more than 16,000 px on one side");
        assertTrue(privacy.open(LargeImageFixtures.headerOnlyPng(dir.resolve("small.png"), 31, 5000), ImageReview.Kind.PHOTO).hasError("import.error.tooSmall"));
        assertTrue(privacy.open(LargeImageFixtures.headerOnlyPng(dir.resolve("max.png"), Integer.MAX_VALUE, Integer.MAX_VALUE), ImageReview.Kind.PHOTO)
            .hasError("import.error.dimensions"), "width × height is computed without int overflow");

        // File size: exactly the limit is accepted, one byte more is rejected before anything is decoded.
        var limited = new ImagePrivacyBoundary(1024 * 1024, ImagePrivacyBoundary.DEFAULT_MAX_PIXELS);
        Path small = ImportFixtures.png(dir, "small-source.png", 200, 150);
        Path exact = LargeImageFixtures.padded(small, dir.resolve("exact.png"), 1024 * 1024);
        assertEquals(1024 * 1024, Files.size(exact));
        assertTrue(limited.open(exact, ImageReview.Kind.PHOTO).isSuccess());
        Path over = LargeImageFixtures.padded(small, dir.resolve("over-size.png"), 1024 * 1024 + 1);
        assertTrue(limited.open(over, ImageReview.Kind.PHOTO).hasError("import.error.tooLarge"));
        Path sparse = dir.resolve("sparse.png");
        try (var file = new RandomAccessFile(sparse.toFile(), "rw")) { file.write(Files.readAllBytes(small)); file.setLength(ImagePrivacyBoundary.DEFAULT_MAX_BYTES + 1); }
        assertTrue(privacy.open(sparse, ImageReview.Kind.PHOTO).hasError("import.error.tooLarge"), "default limit is 100 MB");
    }

    @Test void a100MegapixelPngDecodesIntoABoundedWorkingImageAndAt2048OnTheWayOut() throws Exception {
        Path file = LargeImageFixtures.png(dir.resolve("100mp.png"), 10_000, 10_000, false, (x, y) -> y < 5000 ? 0x30 : 0xD0);
        var review = open(privacy, file);
        assertEquals(10_000, review.width()); assertEquals(10_000, review.height(), "coordinates stay in original pixels");
        assertEquals(3, review.step());
        BufferedImage working = review.pixels();
        assertEquals(3334, working.getWidth()); assertEquals(3334, working.getHeight());
        assertTrue(Math.max(working.getWidth(), working.getHeight()) <= ImagePrivacyBoundary.WORKING_MAX_EDGE, "no full-resolution buffer is kept");
        assertTrue((working.getRGB(10, 10) & 0xFF) < 0x80, "dark upper half"); assertTrue((working.getRGB(10, 3300) & 0xFF) > 0x80, "light lower half");
        var preview = review.preview(620).orElseThrow();
        assertEquals(620, preview.getWidth(), "the GUI preview size is unchanged");
        var out = sanitized(review);
        assertEquals(ImagePrivacyBoundary.OUTGOING_MAX_EDGE, out.getWidth()); assertEquals(ImagePrivacyBoundary.OUTGOING_MAX_EDGE, out.getHeight());
        privacy.discard(review);
        assertTrue(review.released());
    }

    @Test void a61MegapixelJpegDecodesSubsampled() throws Exception {
        var image = new BufferedImage(9504, 6336, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics();
        try { graphics.setColor(java.awt.Color.DARK_GRAY); graphics.fillRect(0, 0, 9504, 3168); graphics.setColor(java.awt.Color.LIGHT_GRAY); graphics.fillRect(0, 3168, 9504, 3168); }
        finally { graphics.dispose(); }
        Path file = dir.resolve("61mp.jpg");
        ImageIO.write(image, "jpeg", file.toFile());
        image = null;
        var review = open(privacy, file);
        assertEquals("JPEG", review.format());
        assertEquals(9504, review.width()); assertEquals(6336, review.height());
        assertEquals(3, review.step());
        assertEquals(3168, review.pixels().getWidth()); assertEquals(2112, review.pixels().getHeight());
        var out = sanitized(review);
        assertEquals(2048, out.getWidth()); assertEquals(1365, out.getHeight());
        privacy.discard(review);
    }

    @Test void redactionsOfASubsampledImageLeaveNoSensitivePixelWithOrWithoutCrop() throws Exception {
        var review = open(privacy, wide());
        assertEquals(3, review.step(), "9000 px wide is decoded at every third pixel");
        assertTrue(red(sanitized(review)) > 0, "fixture check: without redaction the red area is visible");

        assertTrue(review.redact(SECRET.x, SECRET.y, SECRET.width, SECRET.height));
        assertTrue(review.redact(SECRET_IN_CROP.x, SECRET_IN_CROP.y, SECRET_IN_CROP.width, SECRET_IN_CROP.height));
        var whole = sanitized(review);
        assertEquals(2048, whole.getWidth());
        assertEquals(0, red(whole), "every sampled red pixel is blacked out before downscaling");

        // A crop that still needs subsampling (step 3) and starts at an odd origin: outward rounding still covers the area.
        assertTrue(review.crop(1, 1, 8998, 2998));
        assertEquals(0, red(sanitized(review)));
        review.clearRegions();
        assertTrue(review.crop(1, 1, 8998, 2998));
        assertTrue(red(sanitized(review)) > 0, "fixture check: the same crop without redaction shows red");
        privacy.discard(review);
    }

    @Test void aCropOfASubsampledImageIsDecodedAgainAtFullDetailWithRedactionsApplied() throws Exception {
        Path file = wide();
        byte[] before = Files.readAllBytes(file);
        var review = open(privacy, file);
        assertTrue(review.crop(5800, 1900, 1400, 700));
        var withSecret = sanitized(review);
        assertEquals(1400, withSecret.getWidth(), "the crop is decoded at full resolution, not taken from the 1/3 working image");
        assertEquals(700, withSecret.getHeight());
        for (int x = 200; x < 220; x++) assertEquals(x % 2 == 0 ? BLACK : WHITE, withSecret.getRGB(x, 150) & 0xFFFFFF, "1-px stripes survive at x=" + x);
        assertTrue(red(withSecret) > 0, "fixture check: the second red area is inside the crop");

        assertTrue(review.redact(SECRET_IN_CROP.x, SECRET_IN_CROP.y, SECRET_IN_CROP.width, SECRET_IN_CROP.height));
        assertTrue(review.redact(SECRET.x, SECRET.y, SECRET.width, SECRET.height), "an area outside the crop is harmless");
        var redacted = sanitized(review);
        assertEquals(0, red(redacted));
        assertEquals(BLACK, redacted.getRGB(SECRET_IN_CROP.x - 5800, SECRET_IN_CROP.y - 1900) & 0xFFFFFF, "redaction starts exactly at its first pixel");
        assertEquals(WHITE, redacted.getRGB(SECRET_IN_CROP.x - 5800 - 1, SECRET_IN_CROP.y - 1900) & 0xFFFFFF, "and nothing before it at full resolution");
        assertArrayEquals(before, Files.readAllBytes(file), "the original is only read");
        privacy.discard(review);
    }

    @Test void aSourceFileChangedAfterLoadingIsNeverUsedForACrop() throws Exception {
        Path file = wide();
        var review = open(privacy, file);
        assertTrue(review.crop(5800, 1900, 1400, 700));
        // Same size and modification time, different content (the IEND checksum): only the content hash can tell.
        var modified = Files.getLastModifiedTime(file);
        try (var raf = new RandomAccessFile(file.toFile(), "rw")) { raf.seek(raf.length() - 1); int last = raf.read(); raf.seek(raf.length() - 1); raf.write(last ^ 0xFF); }
        Files.setLastModifiedTime(file, modified);
        var changed = privacy.sanitize(review);
        assertTrue(changed.hasError("import.error.sourceChanged"), changed.errors().toString());
        assertTrue(review.copy().isEmpty(), "no copy was written");
        // A replaced file of another size is refused as well.
        LargeImageFixtures.png(file, 9000, 3000, true, (x, y) -> WHITE);
        assertTrue(privacy.sanitize(review).hasError("import.error.sourceChanged"));
        privacy.discard(review);
    }

    @Test void anOversizedSanitizedCopyIsReportedAsSuchAndAnUndecodableVariantIsNotCalledCorrupt() throws Exception {
        var noise = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        var random = new Random(7);
        for (int y = 0; y < 1600; y++) for (int x = 0; x < 1600; x++) noise.setRGB(x, y, random.nextInt(0x1000000));
        Path jpeg = dir.resolve("noise.jpg");
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); param.setCompressionQuality(0.3f);
        try (var out = ImageIO.createImageOutputStream(jpeg.toFile())) { writer.setOutput(out); writer.write(null, new IIOImage(noise, null, null), param); }
        finally { writer.dispose(); }
        assertTrue(Files.size(jpeg) < 1024 * 1024, "fixture check: the input is within 1 MB");
        var limited = new ImagePrivacyBoundary(1024 * 1024, ImagePrivacyBoundary.DEFAULT_MAX_PIXELS);
        var review = open(limited, jpeg);
        var result = limited.sanitize(review);
        assertTrue(result.hasError("import.error.sanitizedTooLarge"), result.errors().toString());
        assertTrue(review.copy().isEmpty());
        limited.discard(review);

        Path lossless = LargeImageFixtures.losslessProcessJpeg(dir.resolve("lossless.jpg"));
        var variant = privacy.open(lossless, ImageReview.Kind.PHOTO);
        assertTrue(variant.hasError("import.error.decoder"), variant.errors().toString());
    }
}
