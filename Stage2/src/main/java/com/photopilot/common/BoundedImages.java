package com.photopilot.common;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Bounded local PNG/JPEG decoding shared by AI Gear Import ({@code ImagePrivacyBoundary}) and the user avatar
 * ({@code AvatarRepository}). The format comes from the content signature, never the extension; width and height are
 * read from the image header before any pixel is decoded; pixels are then decoded with ImageIO source subsampling,
 * optionally of one region only, so a decoded image never has more than {@link #MAX_DECODED_PIXELS} pixels whatever the
 * size of the original. Nothing is upscaled, cached on disk or logged, and readers and streams are always closed.
 */
public final class BoundedImages {
    /** Hard ceiling for one decoded image: 4096 × 4096 pixels, 64 MiB at 4 bytes per pixel. */
    public static final long MAX_DECODED_PIXELS = 4096L * 4096;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    public enum Format {
        PNG("png"), JPEG("jpeg");
        private final String reader;
        Format(String reader) { this.reader = reader; }
    }

    /** Why a file whose signature matched could still not be decoded. */
    public enum Failure {
        /** No decoder on this computer reads this PNG/JPEG variant (for example a CMYK JPEG or lossless JPEG). */
        UNSUPPORTED_DECODER,
        /** Damaged, truncated or inconsistent image data. */
        CORRUPT,
        /** The file no longer matches the dimensions or content it was validated with. */
        CHANGED,
        /** Not enough memory, or another decoding resource failed. */
        RESOURCES
    }

    /** A decoding failure with its {@link Failure} category; carries no file content or path. */
    public static final class DecodeException extends IOException {
        private final Failure failure;
        public DecodeException(Failure failure, Throwable cause) { super(failure.name(), cause); this.failure = Objects.requireNonNull(failure); }
        public Failure failure() { return failure; }
    }

    /** Dimensions read from the header, before any pixel is decoded. */
    public record Header(Format format, int width, int height) {
        public Header { Objects.requireNonNull(format); }
        /** width × height without int overflow. */
        public long pixels() { return (long) width * height; }
        public Rectangle bounds() { return new Rectangle(0, 0, width, height); }
    }

    /** Where the encoded image is read from: a local file, or bytes already in memory (stored avatars). */
    public interface Input { ImageInputStream open() throws IOException; }

    private BoundedImages() { }

    /** A file read through ImageIO's file stream: random access, no cache file, nothing kept in memory. */
    public static Input file(Path file) {
        Objects.requireNonNull(file);
        return () -> {
            ImageInputStream stream = ImageIO.createImageInputStream(file.toFile());
            if (stream == null) throw new IOException("No image input stream for this file");
            return stream;
        };
    }
    /** Bytes already in memory (they are not copied). */
    public static Input memory(byte[] bytes) {
        Objects.requireNonNull(bytes);
        return () -> new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes));
    }

    /** PNG or JPEG from the file signature; empty for anything else. */
    public static Optional<Format> format(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) { return format(in.readNBytes(PNG_SIGNATURE.length)); }
    }
    public static Optional<Format> format(byte[] head) {
        if (head.length >= PNG_SIGNATURE.length && Arrays.equals(head, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length))
            return Optional.of(Format.PNG);
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return Optional.of(Format.JPEG);
        return Optional.empty();
    }

    /** Width and height from the header only; no pixel data is decoded. */
    public static Header header(Input input, Format format) throws IOException {
        return read(input, format, reader -> new Header(format, reader.getWidth(0), reader.getHeight(0)));
    }

    /** Smallest whole subsampling step that brings the longest side to at most {@code maxEdge} (1 = full resolution). */
    public static int step(long width, long height, int maxEdge) {
        if (width < 1 || height < 1 || maxEdge < 1) throw new IllegalArgumentException("Dimensions must be positive");
        return (int) Math.max(1, Math.ceilDiv(Math.max(width, height), (long) maxEdge));
    }

    /**
     * Decodes {@code region} (the whole image when null), keeping every {@code step}-th column and row: decoded pixel
     * (i, j) is source pixel (region.x + i·step, region.y + j·step), so the result is ⌈w/step⌉ × ⌈h/step⌉. The header is
     * checked again first, so a file replaced after validation cannot decode a larger image than the caller allowed.
     *
     * @param preferredType a {@code BufferedImage.TYPE_*} to decode into directly when the decoder offers it (saving a
     *                      conversion copy), or {@code TYPE_CUSTOM} for the decoder's own type (keeps PNG transparency)
     */
    public static BufferedImage decode(Input input, Header expected, Rectangle region, int step, int preferredType) throws IOException {
        Rectangle area = region == null ? expected.bounds() : new Rectangle(region);
        if (step < 1 || area.isEmpty() || !expected.bounds().contains(area)) throw new IllegalArgumentException("Invalid decode region or step");
        if (Math.ceilDiv((long) area.width, step) * Math.ceilDiv((long) area.height, step) > MAX_DECODED_PIXELS)
            throw new IllegalArgumentException("Decoded image would exceed " + MAX_DECODED_PIXELS + " pixels");
        return read(input, expected.format(), reader -> {
            if (reader.getWidth(0) != expected.width() || reader.getHeight(0) != expected.height()) throw new DecodeException(Failure.CHANGED, null);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(area);
            param.setSourceSubsampling(step, step, 0, 0);
            if (preferredType != BufferedImage.TYPE_CUSTOM) {
                for (Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0); types.hasNext(); ) {
                    ImageTypeSpecifier type = types.next();
                    if (type.getBufferedImageType() == preferredType) { param.setDestinationType(type); break; }
                }
            }
            BufferedImage image = reader.read(0, param);
            if (image == null) throw new DecodeException(Failure.CORRUPT, null);
            return image;
        });
    }

    @FunctionalInterface private interface Operation<T> { T apply(ImageReader reader) throws IOException; }

    /** Opens the stream and a reader for the detected format, runs the operation, and always closes both. */
    private static <T> T read(Input input, Format format, Operation<T> operation) throws IOException {
        try (ImageInputStream stream = input.open()) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.reader);
            if (!readers.hasNext()) throw new DecodeException(Failure.UNSUPPORTED_DECODER, null);
            ImageReader reader = readers.next();
            try {
                // Metadata is ignored on read: only pixels are ever used.
                reader.setInput(stream, true, true);
                return operation.apply(reader);
            } catch (DecodeException known) {
                throw known;
            } catch (IIOException failed) {
                throw new DecodeException(classify(failed), failed);
            } catch (IOException | RuntimeException failed) {
                throw new DecodeException(Failure.CORRUPT, failed);
            } catch (OutOfMemoryError exhausted) {
                // Java allocations are bounded above; this is the last resort for decoder-internal buffers.
                throw new DecodeException(Failure.RESOURCES, null);
            } finally {
                reader.dispose();
            }
        }
    }

    /** ImageIO reports an unsupported variant and damaged data with the same exception type; the message tells them apart. */
    private static Failure classify(IIOException failed) {
        String message = Objects.toString(failed.getMessage(), "").toLowerCase(Locale.ROOT);
        if (message.contains("unsupported image type") || message.contains("unsupported jpeg process")) return Failure.UNSUPPORTED_DECODER;
        if (message.contains("memory")) return Failure.RESOURCES;
        return Failure.CORRUPT;
    }
}
