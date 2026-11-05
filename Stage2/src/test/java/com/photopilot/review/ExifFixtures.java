package com.photopilot.review;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Deterministic, synthetic F11 fixtures: real baseline JPEG files (ImageIO) carrying an APP1 EXIF segment with known
 * little-endian TIFF values, and ExifTool-style JSON exports. Test-only writers; production code only reads EXIF
 * through metadata-extractor. Never user photographs.
 */
public final class ExifFixtures {
    private ExifFixtures() { }

    /** One photograph's recorded values; null leaves a tag out. Rationals are {numerator, denominator}. */
    public record Photo(String make, String model, String lensMake, String lensModel, long[] focal, long[] exposure, long[] fNumber,
            Integer iso, long[] bias, String dateTime, String offset) {
        public Photo withLens(String make, String model) { return new Photo(this.make, this.model, make, model, focal, exposure, fNumber, iso, bias, dateTime, offset); }
        public Photo withCamera(String make, String model) { return new Photo(make, model, lensMake, lensModel, focal, exposure, fNumber, iso, bias, dateTime, offset); }
        public Photo withFocal(long[] value) { return new Photo(make, model, lensMake, lensModel, value, exposure, fNumber, iso, bias, dateTime, offset); }
        public Photo withExposure(long[] value) { return new Photo(make, model, lensMake, lensModel, focal, value, fNumber, iso, bias, dateTime, offset); }
        public Photo withFNumber(long[] value) { return new Photo(make, model, lensMake, lensModel, focal, exposure, value, iso, bias, dateTime, offset); }
        public Photo withIso(Integer value) { return new Photo(make, model, lensMake, lensModel, focal, exposure, fNumber, value, bias, dateTime, offset); }
        public Photo withTime(String time, String zone) { return new Photo(make, model, lensMake, lensModel, focal, exposure, fNumber, iso, bias, time, zone); }
    }
    public static long[] r(long numerator, long denominator) { return new long[]{numerator, denominator}; }

    /** Airshow body A1 with the planned 200-600 mm lens, 1/2500 s, f/8, ISO 800, -1/3 EV, zoned timestamp. */
    public static Photo airshow(int focal, long shutterDenominator, int iso, String time) {
        return new Photo("Sony", "A1", "Sony", "FE 200-600mm G", r(focal * 10L, 10), r(1, shutterDenominator), r(80, 10), iso, r(-1, 3), time, "-05:00");
    }

    public static Path jpeg(Path directory, String name, Photo photo) throws Exception {
        return write(directory, name, withExif(baseline(), tiff(photo)));
    }
    /** Valid JPEG without any EXIF segment (e.g. metadata stripped on export). */
    public static Path plainJpeg(Path directory, String name) throws Exception { return write(directory, name, baseline()); }
    /** JPEG signature followed by garbage: the type is detected but the file is corrupt. */
    public static Path corruptJpeg(Path directory, String name) throws Exception {
        var bytes = new byte[200]; bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF; bytes[3] = (byte) 0xE1; bytes[4] = (byte) 0x7F; bytes[5] = (byte) 0xFF;
        for (int i = 6; i < bytes.length; i++) bytes[i] = (byte) (i * 37);
        return write(directory, name, bytes);
    }
    public static Path png(Path directory, String name) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
        return write(directory, name, out.toByteArray());
    }
    public static Path text(Path directory, String name, String content) throws Exception {
        return write(directory, name, content.getBytes(StandardCharsets.UTF_8));
    }
    private static Path write(Path directory, String name, byte[] bytes) throws Exception {
        Files.createDirectories(directory); var file = directory.resolve(name); Files.write(file, bytes); return file;
    }

    private static byte[] baseline() throws Exception {
        var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) image.setRGB(x, y, (x * 16) << 16 | (y * 16) << 8 | 90);
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "jpg", out); return out.toByteArray();
    }
    /** SOI, APP1 "Exif\0\0" + TIFF, then the rest of the baseline JPEG. */
    private static byte[] withExif(byte[] jpeg, byte[] tiff) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        int length = 2 + 6 + tiff.length;
        out.write(0xFF); out.write(0xE1); out.write(length >> 8); out.write(length & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)); out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private record Entry(int tag, int type, int count, byte[] data) { }
    private static final int ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, SRATIONAL = 10;

    static byte[] tiff(Photo p) {
        var ifd0 = new ArrayList<Entry>(); var exif = new ArrayList<Entry>();
        ascii(ifd0, 0x010F, p.make()); ascii(ifd0, 0x0110, p.model());
        rational(exif, 0x829A, RATIONAL, p.exposure()); rational(exif, 0x829D, RATIONAL, p.fNumber());
        if (p.iso() != null) exif.add(new Entry(0x8827, SHORT, 1, le(2, p.iso())));
        ascii(exif, 0x9003, p.dateTime()); ascii(exif, 0x9011, p.offset());
        rational(exif, 0x9204, SRATIONAL, p.bias()); rational(exif, 0x920A, RATIONAL, p.focal());
        ascii(exif, 0xA433, p.lensMake()); ascii(exif, 0xA434, p.lensModel());
        int n0 = ifd0.size() + 1;
        int ifd0Data = 8 + 2 + 12 * n0 + 4;
        int exifStart = ifd0Data + dataSize(ifd0);
        int exifData = exifStart + 2 + 12 * exif.size() + 4;
        var buffer = ByteBuffer.allocate(exifData + dataSize(exif)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        var all0 = new ArrayList<>(ifd0); all0.add(new Entry(0x8769, LONG, 1, le(4, exifStart)));
        all0.sort(Comparator.comparingInt(Entry::tag)); exif.sort(Comparator.comparingInt(Entry::tag));
        ifd(buffer, 8, all0, ifd0Data); ifd(buffer, exifStart, exif, exifData);
        return buffer.array();
    }
    private static void ifd(ByteBuffer buffer, int start, List<Entry> entries, int dataStart) {
        buffer.position(start); buffer.putShort((short) entries.size());
        int data = dataStart;
        for (Entry e : entries) {
            buffer.putShort((short) e.tag()).putShort((short) e.type()).putInt(e.count());
            if (e.data().length <= 4) { var value = Arrays.copyOf(e.data(), 4); buffer.put(value); }
            else {
                buffer.putInt(data);
                int here = buffer.position(); buffer.position(data); buffer.put(e.data()); buffer.position(here);
                data += e.data().length + (e.data().length % 2);
            }
        }
        buffer.putInt(0);
    }
    private static int dataSize(List<Entry> entries) {
        return entries.stream().mapToInt(e -> e.data().length <= 4 ? 0 : e.data().length + e.data().length % 2).sum();
    }
    private static void ascii(List<Entry> entries, int tag, String value) {
        if (value == null) return;
        var bytes = (value + "\0").getBytes(StandardCharsets.US_ASCII);
        entries.add(new Entry(tag, ASCII, bytes.length, bytes));
    }
    private static void rational(List<Entry> entries, int tag, int type, long[] value) {
        if (value == null) return;
        var b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value[0]).putInt((int) value[1]);
        entries.add(new Entry(tag, type, 1, b.array()));
    }
    private static byte[] le(int size, long value) {
        var b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        if (size == 2) b.putShort((short) value); else b.putInt((int) value);
        return Arrays.copyOf(b.array(), size);
    }

    /** ExifTool -json style export (formatted values, as ExifTool prints them without -n). */
    public static String exiftool(List<Map<String, Object>> entries) {
        var array = new com.google.gson.JsonArray();
        for (var entry : entries) {
            var o = new com.google.gson.JsonObject();
            entry.forEach((k, v) -> { if (v instanceof Number n) o.addProperty(k, n); else o.addProperty(k, String.valueOf(v)); });
            array.add(o);
        }
        return array.toString();
    }
    public static Map<String, Object> entry(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}
