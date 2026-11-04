package com.photopilot.ui.privacy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 41 (zero-based): {presentation helper; user crops/redacts first}
 * F04 privacy boundary shared by GUI and CLI. A selected image is validated by content and decoded locally
 * ({@link #open}); the user crops and redacts it ({@link ImageReview}); {@link #sanitize} writes a separate,
 * application-created copy whose redacted pixels are replaced, which is downscaled and re-encoded as PNG so source
 * metadata is not carried over; only that unchanged copy, explicitly approved, becomes a {@link SanitizedImage}.
 * The user's original file is only ever read. Image content is never logged or placed in error messages.
 */
public class ImagePrivacyBoundary {
    public static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    public static final int DEFAULT_MAX_PIXELS = 50_000_000;
    public static final int MAX_EDGE = 16_000, MIN_EDGE = 32;
    /** Longest edge of the outgoing copy: enough to read model markings, without sending full-resolution photos. */
    public static final int OUTGOING_MAX_EDGE = 2048;
    public static final String MEDIA_TYPE = "image/png";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final long maxBytes;
    private final int maxPixels;
    /** Application-created sanitized copies and the SHA-256 of what was written; only these can be approved. */
    private final Map<Path, byte[]> copies = new ConcurrentHashMap<>();
    private Path workDirectory;

    public ImagePrivacyBoundary() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_PIXELS);
    }

    public ImagePrivacyBoundary(long maxBytes, int maxPixels) {
        if (maxBytes <= 0 || maxPixels <= 0) throw new IllegalArgumentException("Image limits must be positive");
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
    }

    /** Limits from PHOTOPILOT_IMPORT_MAX_MB (1-100) and PHOTOPILOT_IMPORT_MAX_MEGAPIXELS (1-100); defaults otherwise. */
    public static ImagePrivacyBoundary fromEnvironment(Map<String, String> environment) {
        long bytes = DEFAULT_MAX_BYTES; int pixels = DEFAULT_MAX_PIXELS;
        String mb = environment.getOrDefault("PHOTOPILOT_IMPORT_MAX_MB", "").strip();
        String mp = environment.getOrDefault("PHOTOPILOT_IMPORT_MAX_MEGAPIXELS", "").strip();
        if (!mb.isEmpty()) bytes = bounded("PHOTOPILOT_IMPORT_MAX_MB", mb) * 1024L * 1024;
        if (!mp.isEmpty()) pixels = bounded("PHOTOPILOT_IMPORT_MAX_MEGAPIXELS", mp) * 1_000_000;
        return new ImagePrivacyBoundary(bytes, pixels);
    }
    private static int bounded(String name, String value) {
        try {
            int number = Integer.parseInt(value);
            if (number >= 1 && number <= 100) return number;
        } catch (NumberFormatException invalid) { /* reported below */ }
        throw new IllegalArgumentException(name + " must be a whole number from 1 to 100");
    }

    public long maxBytes() { return maxBytes; }

    /** Validates the selected file by its content (PNG or JPEG), size and dimensions, then decodes it locally. */
    public Result<ImageReview> open(Path original, ImageReview.Kind kind) {
        if (original == null || kind == null) return Result.failureKey("import.error.noFile");
        String name = original.getFileName() == null ? original.toString() : original.getFileName().toString();
        Path file;
        try {
            if (!Files.exists(original)) return Result.failureKey("import.error.missing", name);
            file = original.toRealPath();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Result.failureKey("import.error.notFile", name);
            long size = Files.size(file);
            if (size == 0) return Result.failureKey("import.error.corrupt", name);
            if (size > maxBytes) return Result.failureKey("import.error.tooLarge", name, maxBytes / (1024 * 1024));
            String format = format(file);
            if (format == null) return Result.failureKey("import.error.format", name);
            return decode(file, format, kind, name);
        } catch (IOException | SecurityException unreadable) {
            return Result.failureKey("import.error.unreadable", name);
        }
    }

    private Result<ImageReview> decode(Path file, String format, ImageReview.Kind kind, String name) throws IOException {
        try (var stream = ImageIO.createImageInputStream(file.toFile())) {
            if (stream == null) return Result.failureKey("import.error.unreadable", name);
            var readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) return Result.failureKey("import.error.format", name);
            ImageReader reader = readers.next();
            try {
                // Metadata is ignored on read and never written: the outgoing copy is encoded from pixels only.
                reader.setInput(stream, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < MIN_EDGE || h < MIN_EDGE) return Result.failureKey("import.error.tooSmall", name, MIN_EDGE);
                if (w > MAX_EDGE || h > MAX_EDGE || (long) w * h > maxPixels)
                    return Result.failureKey("import.error.dimensions", name, w, h, maxPixels / 1_000_000);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) return Result.failureKey("import.error.corrupt", name);
                return Result.success(new ImageReview(file, format.toUpperCase(java.util.Locale.ROOT), kind, opaque(decoded)));
            } catch (IOException | RuntimeException corrupt) {
                return Result.failureKey("import.error.corrupt", name);
            } catch (OutOfMemoryError tooLarge) {
                return Result.failureKey("import.error.dimensions", name, 0, 0, maxPixels / 1_000_000);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Detects PNG/JPEG from the file signature; the extension is never trusted. */
    private static String format(Path file) throws IOException {
        byte[] head = new byte[PNG.length];
        int read;
        try (InputStream in = Files.newInputStream(file)) { read = in.readNBytes(head, 0, head.length); }
        if (read >= PNG.length && Arrays.equals(head, PNG)) return "png";
        if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "jpeg";
        return null;
    }

    /**
     * Writes the sanitized copy for the user's final review: crop, solid black redactions (pixels replaced at full
     * resolution before any scaling), downscale to {@link #OUTGOING_MAX_EDGE}, PNG re-encoding without metadata.
     * A previous copy of the same review is deleted.
     */
    public Result<java.nio.file.Path> sanitize(ImageReview review) {
        if (review == null || review.released()) return Result.failureKey("import.error.noImage");
        BufferedImage source = review.pixels();
        Rectangle area = review.cropRegion().orElse(new Rectangle(0, 0, review.width(), review.height()));
        var redacted = new BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = redacted.createGraphics();
        try {
            graphics.drawImage(source, -area.x, -area.y, null);
            graphics.setColor(Color.BLACK);
            for (Rectangle region : review.redactions()) {
                var inside = region.intersection(area);
                if (!inside.isEmpty()) graphics.fillRect(inside.x - area.x, inside.y - area.y, inside.width, inside.height);
            }
        } finally { graphics.dispose(); }
        byte[] encoded;
        try {
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(ImageReview.scaled(redacted, OUTGOING_MAX_EDGE), "png", out)) return Result.failureKey("import.error.sanitize");
            encoded = out.toByteArray();
        } catch (IOException | RuntimeException failed) {
            return Result.failureKey("import.error.sanitize");
        }
        try {
            review.copy().ifPresent(this::discard);
            Path copy = Files.createTempFile(workDirectory(), "sanitized-", ".png");
            Files.write(copy, encoded);
            copy.toFile().deleteOnExit();
            copies.put(copy.toAbsolutePath().normalize(), sha256(encoded));
            review.copy(copy);
            return Result.success(copy);
        } catch (IOException | SecurityException failed) {
            return Result.failureKey("import.error.sanitize");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    /**
     * Stage 1 approval: only an unchanged copy written by {@link #sanitize} and explicitly confirmed by the user
     * becomes a transmissible {@link SanitizedImage}. Nothing is read without confirmation.
     */
    public Result<SanitizedImage> approveRedacted(Path copy, boolean confirmed) {
        if (!confirmed) return Result.failureKey("import.error.approvalRequired");
        if (copy == null) return Result.failureKey("import.error.notSanitized");
        Path key = copy.toAbsolutePath().normalize();
        byte[] expected = copies.get(key);
        if (expected == null) return Result.failureKey("import.error.notSanitized");
        try {
            if (Files.size(key) > maxBytes) { discard(key); return Result.failureKey("import.error.copyChanged"); }
            byte[] bytes = Files.readAllBytes(key);
            try {
                if (!MessageDigest.isEqual(expected, sha256(bytes))) { discard(key); return Result.failureKey("import.error.copyChanged"); }
                return Result.success(new SanitizedImage(bytes, MEDIA_TYPE, true, key));
            } finally { Arrays.fill(bytes, (byte) 0); }
        } catch (IOException | SecurityException unreadable) {
            discard(key);
            return Result.failureKey("import.error.copyChanged");
        }
    }

    /** Releases an approved image: bytes zeroed and its application-created copy deleted. */
    public void discard(SanitizedImage image) {
        if (image == null) return;
        image.clear();
        image.copy().ifPresent(this::discard);
    }

    /** Ends a review (completion or cancellation): pixels dropped, the sanitized copy deleted, the original kept. */
    public void discard(ImageReview review) {
        if (review == null) return;
        review.copy().ifPresent(this::discard);
        review.copy(null);
        review.release();
    }

    /** Deletes a copy only when this boundary created it; any other path, including originals, is left untouched. */
    public boolean discard(Path copy) {
        if (copy == null) return false;
        Path key = copy.toAbsolutePath().normalize();
        if (copies.remove(key) == null) return false;
        try { return Files.deleteIfExists(key); }
        catch (IOException | SecurityException ignored) { return false; }
    }

    /** True while an application-created copy is still registered (diagnostics and tests). */
    public boolean owns(Path copy) { return copy != null && copies.containsKey(copy.toAbsolutePath().normalize()); }

    private synchronized Path workDirectory() throws IOException {
        if (workDirectory == null || !Files.isDirectory(workDirectory)) {
            workDirectory = Files.createTempDirectory("photopilot-import-");
            workDirectory.toFile().deleteOnExit();
        }
        return workDirectory;
    }

    private static BufferedImage opaque(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) return image;
        var target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally { graphics.dispose(); }
        return target;
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
