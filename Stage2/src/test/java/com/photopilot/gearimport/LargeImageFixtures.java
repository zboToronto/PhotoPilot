package com.photopilot.gearimport;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

/**
 * Synthetic large-image fixtures that never need a full-size image in memory: PNGs are written row by row through a
 * streaming deflater, so a 100-megapixel test image costs one row of memory to create (and a few hundred KB on disk for
 * flat content). Also header-only PNGs (dimensions without pixel data) for pure limit checks, PNGs padded to an exact
 * file size, and a JPEG variant (lossless process) that the JDK decoder does not support.
 */
public final class LargeImageFixtures {
    private LargeImageFixtures() { }
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Colour of pixel (x, y) as 0xRRGGBB; grayscale PNGs use the low byte. */
    @FunctionalInterface public interface Pixels { int rgb(int x, int y); }

    /** 8-bit RGB (color) or grayscale PNG, written one row at a time. */
    public static Path png(Path file, int width, int height, boolean color, Pixels pixels) throws IOException {
        int channels = color ? 3 : 1;
        Files.createDirectories(file.getParent());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(SIGNATURE);
            chunk(out, "IHDR", ihdr(width, height, color ? 2 : 0));
            var deflater = new Deflater(Deflater.BEST_SPEED);
            try (var data = new DeflaterOutputStream(new IdatStream(out), deflater, 1 << 16)) {
                byte[] row = new byte[1 + width * channels];
                for (int y = 0; y < height; y++) {
                    row[0] = 0; // filter: none
                    for (int x = 0, i = 1; x < width; x++) {
                        int rgb = pixels.rgb(x, y);
                        if (color) { row[i++] = (byte) (rgb >> 16); row[i++] = (byte) (rgb >> 8); row[i++] = (byte) rgb; }
                        else row[i++] = (byte) rgb;
                    }
                    data.write(row);
                }
            } finally { deflater.end(); }
            chunk(out, "IEND", new byte[0]);
        }
        return file;
    }

    /** A PNG whose header declares the given dimensions but which has no pixel data: limits apply, decoding fails. */
    public static Path headerOnlyPng(Path file, int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        chunk(out, "IHDR", ihdr(width, height, 2));
        chunk(out, "IEND", new byte[0]);
        Files.createDirectories(file.getParent());
        return Files.write(file, out.toByteArray());
    }

    /** Copy of a PNG padded with a private ancillary chunk (ignored by decoders) to exactly {@code size} bytes. */
    public static Path padded(Path source, Path target, long size) throws IOException {
        byte[] png = Files.readAllBytes(source);
        long padding = size - png.length - 12;
        if (padding < 0 || padding > Integer.MAX_VALUE) throw new IllegalArgumentException("Cannot pad to " + size);
        int afterHeader = SIGNATURE.length + 25;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
            out.write(png, 0, afterHeader);
            byte[] type = "prVt".getBytes(StandardCharsets.US_ASCII);
            var crc = new CRC32(); crc.update(type);
            out.write(ByteBuffer.allocate(4).putInt((int) padding).array()); out.write(type);
            byte[] zeros = new byte[1 << 16];
            for (long left = padding; left > 0; ) { int n = (int) Math.min(zeros.length, left); out.write(zeros, 0, n); crc.update(zeros, 0, n); left -= n; }
            out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
            out.write(png, afterHeader, png.length - afterHeader);
        }
        return target;
    }

    /**
     * A JPEG that declares the lossless process (SOF3 instead of the baseline SOF0 marker): a JPEG signature and header,
     * but a coding process the JDK decoder does not implement ("Unsupported JPEG process").
     */
    public static Path losslessProcessJpeg(Path file) throws IOException {
        var image = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        byte[] jpeg = out.toByteArray();
        for (int i = 2; i + 3 < jpeg.length && (jpeg[i] & 0xFF) == 0xFF; ) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xC0) { jpeg[i + 1] = (byte) 0xC3; Files.createDirectories(file.getParent()); return Files.write(file, jpeg); }
            if (marker == 0xDA) break;
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        throw new IllegalStateException("No baseline SOF0 marker found");
    }

    private static byte[] ihdr(int width, int height, int colorType) {
        return ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) colorType).put((byte) 0).put((byte) 0).put((byte) 0).array();
    }
    private static void chunk(OutputStream out, String type, byte[] data) throws IOException { chunk(out, type, data, data.length); }
    private static void chunk(OutputStream out, String type, byte[] data, int length) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32(); crc.update(name); crc.update(data, 0, length);
        out.write(ByteBuffer.allocate(4).putInt(length).array()); out.write(name); out.write(data, 0, length);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** Emits the zlib stream as consecutive IDAT chunks of at most 64 KiB; closing flushes the last one only. */
    private static final class IdatStream extends OutputStream {
        private final OutputStream out;
        private final byte[] buffer = new byte[1 << 16];
        private int size;
        IdatStream(OutputStream out) { this.out = out; }
        @Override public void write(int b) throws IOException { if (size == buffer.length) flushChunk(); buffer[size++] = (byte) b; }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (size == buffer.length) flushChunk();
                int n = Math.min(len, buffer.length - size);
                System.arraycopy(b, off, buffer, size, n); size += n; off += n; len -= n;
            }
        }
        private void flushChunk() throws IOException { if (size > 0) { chunk(out, "IDAT", buffer, size); size = 0; } }
        @Override public void close() throws IOException { flushChunk(); }
    }
}
