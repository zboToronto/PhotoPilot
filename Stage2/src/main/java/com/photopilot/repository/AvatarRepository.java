package com.photopilot.repository;

import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.photopilot.common.BoundedImages;
import com.photopilot.i18n.LocalizedMessage;

/**
 * Bounded local avatar codec and atomic storage, serialized with the user registry lifecycle. A selected PNG/JPEG is
 * checked by content, file size and header dimensions before any pixel is decoded; only its center square is decoded,
 * subsampled to at most {@link #WORKING_EDGE} px per side, then resized (bicubic) to a {@value #SIZE} × {@value #SIZE}
 * PNG. PNG transparency is kept; orientation metadata is ignored, as before.
 */
public final class AvatarRepository {
    /** Largest selected image accepted for a new avatar. */
    public static final long MAX_INPUT_BYTES = 25L * 1024 * 1024;
    public static final long MAX_INPUT_PIXELS = 80_000_000L;
    /** Largest side accepted (the JPEG maximum); it also bounds a decoder's per-row buffers for very long PNGs. */
    public static final int MAX_INPUT_EDGE = 65_535;
    /** The center square is decoded at most this many pixels per side before the final resize. */
    static final int WORKING_EDGE = 2048;
    public static final int SIZE = 256;
    /** Stored avatar.png files (always 256 × 256 PNG) are read and written with this bound. */
    private static final int STORED_MAX_BYTES = 10 * 1024 * 1024;
    private final JsonProfileRepository profiles;
    public AvatarRepository(JsonProfileRepository profiles) { this.profiles = profiles; }

    /** A selected image that cannot become an avatar; {@link #reason()} is the user-facing explanation. */
    public static final class RejectedImage extends IOException {
        private final LocalizedMessage reason;
        RejectedImage(LocalizedMessage reason, String detail) { super(detail); this.reason = reason; }
        public LocalizedMessage reason() { return reason; }
    }

    public static byte[] prepare(Path source) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Select a PNG or JPEG file.");
        long size = Files.size(source);
        if (size > MAX_INPUT_BYTES) throw new RejectedImage(LocalizedMessage.of("avatar.error.tooLarge"), "Image is too large.");
        if (size == 0) throw new RejectedImage(LocalizedMessage.of("avatar.error.corrupt"), "Image is empty.");
        var format = BoundedImages.format(source)
            .orElseThrow(() -> new RejectedImage(LocalizedMessage.of("avatar.error.format"), "Select a PNG or JPEG file."));
        // Streamed from the file: the selected image is never read into a byte array.
        return render(BoundedImages.file(source), format);
    }
    private static byte[] normalize(byte[] bytes) throws IOException {
        if (bytes.length > MAX_INPUT_BYTES) throw new RejectedImage(LocalizedMessage.of("avatar.error.tooLarge"), "Image is too large.");
        var format = BoundedImages.format(bytes)
            .orElseThrow(() -> new RejectedImage(LocalizedMessage.of("avatar.error.format"), "Unsupported image."));
        return render(BoundedImages.memory(bytes), format);
    }
    /** Center square → bounded decode → bicubic resize to SIZE × SIZE ARGB → PNG. */
    private static byte[] render(BoundedImages.Input input, BoundedImages.Format format) throws IOException {
        try {
            var header = BoundedImages.header(input, format);
            int width = header.width(), height = header.height();
            if (width < 1 || height < 1 || width > MAX_INPUT_EDGE || height > MAX_INPUT_EDGE || header.pixels() > MAX_INPUT_PIXELS)
                throw new RejectedImage(LocalizedMessage.of("avatar.error.dimensions"), "Image dimensions exceed the limit.");
            int side = Math.min(width, height);
            var square = new Rectangle((width - side) / 2, (height - side) / 2, side, side);
            BufferedImage decoded = BoundedImages.decode(input, header, square, BoundedImages.step(side, side, WORKING_EDGE), BufferedImage.TYPE_CUSTOM);
            var output = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            var graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(decoded, 0, 0, SIZE, SIZE, null);
            } finally { graphics.dispose(); }
            var encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", encoded)) throw new IOException("PNG encoding unavailable.");
            return encoded.toByteArray();
        } catch (BoundedImages.DecodeException failed) {
            throw switch (failed.failure()) {
                case UNSUPPORTED_DECODER -> new RejectedImage(LocalizedMessage.of("avatar.error.decoder"), "Unsupported image variant.");
                case RESOURCES -> new RejectedImage(LocalizedMessage.of("avatar.error.memory"), "Not enough memory to decode the image.");
                case CORRUPT, CHANGED -> new RejectedImage(LocalizedMessage.of("avatar.error.corrupt"), "Image is damaged.");
            };
        } catch (OutOfMemoryError exhausted) {
            throw new RejectedImage(LocalizedMessage.of("avatar.error.memory"), "Not enough memory to decode the image.");
        }
    }
    private Path file(UUID id) throws IOException {
        Path file = StorageScope.forProfile(profiles.root(), id).directory().resolve("avatar.png");
        ProfilePaths.safe(file); return file;
    }
    public Optional<byte[]> read(UUID id) throws IOException {
        return profiles.withProfile(id, () -> {
            var bytes = AtomicFiles.read(file(id), STORED_MAX_BYTES);
            return bytes.isEmpty() ? Optional.empty() : Optional.of(normalize(bytes.orElseThrow()));
        });
    }
    public void save(UUID id, byte[] bytes) throws IOException {
        byte[] normalized = normalize(bytes);
        profiles.withProfile(id, () -> {
            Path target = file(id);
            AtomicFiles.write(target, "avatar-", normalized, STORED_MAX_BYTES, temporary -> AtomicFiles.replace(temporary, target));
            return true;
        });
    }
    public void remove(UUID id) throws IOException {
        profiles.withProfile(id, () -> { Files.deleteIfExists(file(id)); return true; });
    }
}
