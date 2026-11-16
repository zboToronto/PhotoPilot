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
import com.photopilot.common.BoundedImages;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 41 (zero-based): {presentation helper; user crops/redacts first}
 * F04 privacy boundary shared by GUI and CLI. A selected image is validated by content and decoded locally
 * ({@link #open}); the user crops and redacts it ({@link ImageReview}); {@link #sanitize} writes a separate,
 * application-created copy whose redacted pixels are replaced, which is downscaled and re-encoded as PNG so source
 * metadata is not carried over; only that unchanged copy, explicitly approved, becomes a {@link SanitizedImage}.
 * The user's original file is only ever read. Image content is never logged or placed in error messages.
 *
 * <p>Memory is bounded independently of the original's size: dimensions are checked from the header before any pixel
 * is decoded, the review holds a working image subsampled to at most {@link #WORKING_MAX_EDGE} px per side, and a crop
 * of a subsampled image is decoded again from the file (that region only, also at most {@link #WORKING_MAX_EDGE} px)
 * after checking that the file is still the one the user reviewed. Nothing is upscaled and no disk cache is created.
 */
public class ImagePrivacyBoundary {
    public static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;
    public static final int DEFAULT_MAX_PIXELS = 100_000_000;
    /** Highest limits the constructor and PHOTOPILOT_IMPORT_MAX_MB / PHOTOPILOT_IMPORT_MAX_MEGAPIXELS accept. */
    public static final long MAX_CONFIGURABLE_BYTES = 100L * 1024 * 1024;
    public static final int MAX_CONFIGURABLE_PIXELS = 100_000_000;
    public static final int MAX_EDGE = 16_000, MIN_EDGE = 32;
    /** Longest side of the decoded working image (preview and uncropped processing); larger originals are subsampled. */
    public static final int WORKING_MAX_EDGE = 4096;
    /** Longest edge of the outgoing copy: enough to read model markings, without sending full-resolution photos. */
    public static final int OUTGOING_MAX_EDGE = 2048;
    public static final String MEDIA_TYPE = "image/png";
    private static final long MIB = 1024L * 1024;

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
        if (maxBytes > MAX_CONFIGURABLE_BYTES || maxPixels > MAX_CONFIGURABLE_PIXELS)
            throw new IllegalArgumentException("Image limits cannot exceed 100 MB and 100 megapixels");
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
    }

    /** Limits from PHOTOPILOT_IMPORT_MAX_MB (1-100) and PHOTOPILOT_IMPORT_MAX_MEGAPIXELS (1-100); defaults otherwise. */
    public static ImagePrivacyBoundary fromEnvironment(Map<String, String> environment) {
        long bytes = DEFAULT_MAX_BYTES; int pixels = DEFAULT_MAX_PIXELS;
        String mb = environment.getOrDefault("PHOTOPILOT_IMPORT_MAX_MB", "").strip();
        String mp = environment.getOrDefault("PHOTOPILOT_IMPORT_MAX_MEGAPIXELS", "").strip();
        if (!mb.isEmpty()) bytes = bounded("PHOTOPILOT_IMPORT_MAX_MB", mb) * MIB;
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
    public int maxPixels() { return maxPixels; }

    /**
     * Validates the selected file by its content (PNG or JPEG), size and header dimensions, then decodes a bounded
     * working image locally (see the class description).
     */
    public Result<ImageReview> open(Path original, ImageReview.Kind kind) {
        if (original == null || kind == null) return Result.failureKey("import.error.noFile");
        String name = original.getFileName() == null ? original.toString() : original.getFileName().toString();
        try {
            if (!Files.exists(original)) return Result.failureKey("import.error.missing", name);
            Path file = original.toRealPath();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Result.failureKey("import.error.notFile", name);
            long size = Files.size(file);
            if (size == 0) return Result.failureKey("import.error.corrupt", name);
            if (size > maxBytes) return Result.failureKey("import.error.tooLarge", name, maxBytes / MIB);
            var format = BoundedImages.format(file);
            if (format.isEmpty()) return Result.failureKey("import.error.format", name);
            var input = BoundedImages.file(file);
            var header = BoundedImages.header(input, format.get());
            int w = header.width(), h = header.height();
            if (w < MIN_EDGE || h < MIN_EDGE) return Result.failureKey("import.error.tooSmall", name, MIN_EDGE);
            if (w > MAX_EDGE || h > MAX_EDGE || header.pixels() > maxPixels)
                return Result.failureKey("import.error.dimensions", name, w, h, maxPixels / 1_000_000);
            int step = BoundedImages.step(w, h, WORKING_MAX_EDGE);
            // Only a subsampled review reads the file again (for a crop); the fingerprint proves it is the same content.
            ImageReview.Fingerprint fingerprint = step == 1 ? null
                : new ImageReview.Fingerprint(size, Files.getLastModifiedTime(file), sha256(file));
            BufferedImage working = opaque(BoundedImages.decode(input, header, null, step, BufferedImage.TYPE_INT_RGB));
            return Result.success(new ImageReview(file, header, kind, step, working, fingerprint));
        } catch (BoundedImages.DecodeException failed) {
            return decodeFailure(failed, name);
        } catch (IOException | SecurityException unreadable) {
            return Result.failureKey("import.error.unreadable", name);
        } catch (OutOfMemoryError exhausted) {
            return Result.failureKey("import.error.memory", name);
        }
    }

    private static <T> Result<T> decodeFailure(BoundedImages.DecodeException failed, String name) {
        return switch (failed.failure()) {
            case UNSUPPORTED_DECODER -> Result.failureKey("import.error.decoder", name);
            case RESOURCES -> Result.failureKey("import.error.memory", name);
            case CHANGED -> Result.failureKey("import.error.sourceChanged", name);
            case CORRUPT -> Result.failureKey("import.error.corrupt", name);
        };
    }

    /**
     * Writes the sanitized copy for the user's final review: crop, solid black redactions (pixels replaced before any
     * scaling), downscale to {@link #OUTGOING_MAX_EDGE}, PNG re-encoding without metadata. A previous copy of the same
     * review is deleted, and so is a copy whose write failed.
     */
    public Result<java.nio.file.Path> sanitize(ImageReview review) {
        if (review == null || review.released()) return Result.failureKey("import.error.noImage");
        BufferedImage outgoing;
        try {
            outgoing = ImageReview.scaled(redacted(review), OUTGOING_MAX_EDGE);
        } catch (BoundedImages.DecodeException failed) {
            return decodeFailure(failed, review.fileName());
        } catch (IOException | SecurityException unreadable) {
            return Result.failureKey("import.error.unreadable", review.fileName());
        } catch (IllegalStateException released) {
            return Result.failureKey("import.error.noImage");
        } catch (OutOfMemoryError exhausted) {
            return Result.failureKey("import.error.memory", review.fileName());
        }
        byte[] encoded;
        try {
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(outgoing, "png", out)) return Result.failureKey("import.error.sanitize");
            encoded = out.toByteArray();
        } catch (IOException | RuntimeException failed) {
            return Result.failureKey("import.error.sanitize");
        } catch (OutOfMemoryError exhausted) {
            return Result.failureKey("import.error.memory", review.fileName());
        }
        // The copy must be approvable under the same size limit (see approveRedacted); say so instead of "changed".
        if (encoded.length > maxBytes) {
            Arrays.fill(encoded, (byte) 0);
            return Result.failureKey("import.error.sanitizedTooLarge", maxBytes / MIB);
        }
        Path copy = null;
        try {
            review.copy().ifPresent(this::discard);
            copy = Files.createTempFile(workDirectory(), "sanitized-", ".png");
            copy.toFile().deleteOnExit(); // registered before writing: even a partly written copy holds photo pixels
            write(copy, encoded);
            copies.put(copy.toAbsolutePath().normalize(), sha256(encoded));
            review.copy(copy);
            return Result.success(copy);
        } catch (IOException | SecurityException failed) {
            // An untracked copy would never be discarded; delete what was written now (best effort, else at exit).
            if (copy != null) try { Files.deleteIfExists(copy); } catch (IOException | SecurityException ignored) { /* deleteOnExit remains */ }
            return Result.failureKey("import.error.sanitize");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    /** Writes the encoded copy; package-private only so a test can make the write fail part-way (e.g. a full disk). */
    void write(Path copy, byte[] encoded) throws IOException { Files.write(copy, encoded); }

    /**
     * The approved area with every redaction filled black, at the most detail the bounded decoder allows.
     * <ul>
     *   <li>Working image at full resolution (original at most {@link #WORKING_MAX_EDGE} px per side): processed exactly
     *       as before, from the original pixels.</li>
     *   <li>Subsampled image without a crop: the working image.</li>
     *   <li>Subsampled image with a crop: only the crop is decoded again from the file, subsampled to at most
     *       {@link #WORKING_MAX_EDGE} px, so a small crop of a large photo keeps its detail (e.g. model markings).</li>
     * </ul>
     * In subsampled pixels each redaction is rounded outward (start down, end up), so every decoded pixel sampled from a
     * redacted original pixel is black before any scaling; interpolation afterwards can only mix black with unredacted
     * neighbours.
     */
    private BufferedImage redacted(ImageReview review) throws IOException {
        Rectangle full = new Rectangle(0, 0, review.width(), review.height());
        Rectangle area = review.cropRegion().orElse(full);
        BufferedImage target;
        int originX = 0, originY = 0, step;
        if (review.step() == 1 || area.equals(full)) {
            BufferedImage working = review.pixels();
            step = review.step();
            // Working pixel (i, j) is original (i·step, j·step); with step 1 the window is the crop itself.
            Rectangle window = step == 1 ? area : new Rectangle(0, 0, working.getWidth(), working.getHeight());
            target = new BufferedImage(window.width, window.height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try { graphics.drawImage(working, -window.x, -window.y, null); } finally { graphics.dispose(); }
            originX = window.x * step; originY = window.y * step;
        } else {
            step = BoundedImages.step(area.width, area.height, WORKING_MAX_EDGE);
            ImageReview.Fingerprint fingerprint = review.fingerprint().orElseThrow();
            Path file = review.file();
            if (Files.size(file) != fingerprint.size() || !Files.getLastModifiedTime(file).equals(fingerprint.modified()))
                throw new BoundedImages.DecodeException(BoundedImages.Failure.CHANGED, null);
            try {
                target = opaque(BoundedImages.decode(BoundedImages.file(file), review.header(), area, step, BufferedImage.TYPE_INT_RGB));
            } catch (BoundedImages.DecodeException failed) {
                // A file that changed since loading is reported as changed, not as damaged.
                if (!unchanged(file, fingerprint)) throw new BoundedImages.DecodeException(BoundedImages.Failure.CHANGED, failed);
                throw failed;
            }
            // Checked after decoding as well, so content swapped while it was read is never used.
            if (!unchanged(file, fingerprint)) throw new BoundedImages.DecodeException(BoundedImages.Failure.CHANGED, null);
            originX = area.x; originY = area.y;
        }
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            for (Rectangle region : review.redactions()) {
                Rectangle inside = region.intersection(area);
                if (inside.isEmpty()) continue;
                int x0 = Math.floorDiv(inside.x - originX, step), y0 = Math.floorDiv(inside.y - originY, step);
                int x1 = Math.ceilDiv(inside.x + inside.width - originX, step), y1 = Math.ceilDiv(inside.y + inside.height - originY, step);
                graphics.fillRect(x0, y0, x1 - x0, y1 - y0);
            }
        } finally { graphics.dispose(); }
        return target;
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

    /** The image itself when already opaque RGB; otherwise one RGB copy on white, after which the input can be dropped. */
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
    private static boolean unchanged(Path file, ImageReview.Fingerprint fingerprint) throws IOException {
        return Files.size(file) == fingerprint.size() && MessageDigest.isEqual(fingerprint.sha256(), sha256(file));
    }
    /** Streaming SHA-256 of a file (64 KiB buffer): memory does not grow with the file. */
    private static byte[] sha256(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }
}
