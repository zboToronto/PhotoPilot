import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Derives every PhotoPilot icon asset from the one source logo: the runtime PNG set on the classpath, a
 * multi-resolution Windows ICO and a macOS ICNS for later packaging. Lanczos-3 resampling in premultiplied
 * alpha; a non-square source is centred on a transparent square, never stretched or cropped. The source is
 * only read.
 * Usage from Stage2: java tools/GenerateAppIcons.java "../../Glossy AI Camera Flower Logo.png"
 */
public class GenerateAppIcons {
    private static final String RESOURCES = "src/main/resources/com/photopilot/ui/gui/icons/";
    private static final int[] PNG_SIZES = { 16, 32, 48, 64, 128, 256, 512 };
    private static final int[] ICO_SIZES = { 16, 24, 32, 48, 64, 128, 256 };
    // type -> pixel size; Retina "@2x" types repeat a pixel size, as iconutil writes them.
    private static final String[][] ICNS_TYPES = { {"icp4", "16"}, {"icp5", "32"}, {"ic11", "32"}, {"ic12", "64"}, {"ic07", "128"},
        {"ic13", "256"}, {"ic08", "256"}, {"ic14", "512"}, {"ic09", "512"}, {"ic10", "1024"} };

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java tools/GenerateAppIcons.java <source logo>");
        var source = ImageIO.read(new File(args[0]));
        if (source == null) throw new IOException("Unreadable image: " + args[0]);
        if (!source.getColorModel().hasAlpha()) System.err.println("Warning: source has no alpha channel.");
        var sizes = new TreeMap<Integer, BufferedImage>();
        for (int size : PNG_SIZES) sizes.put(size, resize(source, size));
        for (int size : ICO_SIZES) sizes.computeIfAbsent(size, size1 -> resize(source, size1));
        for (String[] type : ICNS_TYPES) sizes.computeIfAbsent(Integer.parseInt(type[1]), size -> resize(source, size));

        Files.createDirectories(Path.of(RESOURCES));
        for (int size : PNG_SIZES) Files.write(Path.of(RESOURCES + "photopilot-" + size + ".png"), png(sizes.get(size)));
        write(Path.of("packaging/windows/photopilot.ico"), ico(sizes));
        write(Path.of("packaging/macos/photopilot.icns"), icns(sizes));
    }

    private static void write(Path file, byte[] bytes) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, bytes); }

    /** Fits the whole source into size x size, preserving aspect ratio. */
    static BufferedImage resize(BufferedImage source, int size) {
        int sw = source.getWidth(), sh = source.getHeight();
        double scale = (double) size / Math.max(sw, sh);
        int w = Math.max(1, (int) Math.round(sw * scale)), h = Math.max(1, (int) Math.round(sh * scale));
        // Premultiplied planes, so fully transparent pixels contribute no colour to their neighbours.
        double[][] planes = new double[4][sw * sh];
        for (int i = 0; i < sw * sh; i++) {
            int argb = source.getRGB(i % sw, i / sw); double a = (argb >>> 24) / 255.0;
            planes[0][i] = argb >>> 24; planes[1][i] = (argb >> 16 & 255) * a; planes[2][i] = (argb >> 8 & 255) * a; planes[3][i] = (argb & 255) * a;
        }
        double[][] across = new double[4][], result = new double[4][];
        for (int k = 0; k < 4; k++) { across[k] = pass(planes[k], sw, sh, w, true); result[k] = pass(across[k], w, sh, h, false); }
        var out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int left = (size - w) / 2, top = (size - h) / 2;
        for (int i = 0; i < w * h; i++) {
            double a = clamp(result[0][i]);
            int argb = (int) Math.round(a) << 24;
            for (int k = 1; k < 4; k++) argb |= (a == 0 ? 0 : (int) Math.round(clamp(Math.min(result[k][i], a) * 255 / a))) << (8 * (3 - k));
            out.setRGB(left + i % w, top + i / w, argb);
        }
        return out;
    }
    private static double clamp(double value) { return Math.max(0, Math.min(255, value)); }

    /** One separable Lanczos-3 pass along x (horizontal) or y, from length {@code from} to {@code to}. */
    private static double[] pass(double[] source, int width, int height, int to, boolean horizontal) {
        int from = horizontal ? width : height, lines = horizontal ? height : width;
        double ratio = (double) from / to, support = 3 * Math.max(1, ratio);
        double[] result = new double[horizontal ? to * height : width * to];
        for (int target = 0; target < to; target++) {
            double centre = (target + .5) * ratio - .5;
            int first = (int) Math.ceil(centre - support), last = (int) Math.floor(centre + support);
            double[] weights = new double[last - first + 1]; double total = 0;
            for (int s = first; s <= last; s++) total += weights[s - first] = lanczos((s - centre) / Math.max(1, ratio));
            for (int line = 0; line < lines; line++) {
                double sum = 0;
                for (int s = first; s <= last; s++) {
                    int p = Math.max(0, Math.min(from - 1, s));
                    sum += weights[s - first] * source[horizontal ? p + line * width : line + p * width];
                }
                result[horizontal ? target + line * to : line + target * width] = sum / total;
            }
        }
        return result;
    }
    private static double lanczos(double x) {
        if (x == 0) return 1;
        if (Math.abs(x) >= 3) return 0;
        double pi = Math.PI * x;
        return 3 * Math.sin(pi) * Math.sin(pi / 3) / (pi * pi);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
    }

    /** 32-bit BMP entries below 256 px for compatibility with older readers; the 256 px entry is PNG. */
    private static byte[] ico(Map<Integer, BufferedImage> sizes) throws IOException {
        var entries = new ArrayList<byte[]>();
        for (int size : ICO_SIZES) entries.add(size >= 256 ? png(sizes.get(size)) : dib(sizes.get(size)));
        var header = ByteBuffer.allocate(6 + 16 * ICO_SIZES.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) ICO_SIZES.length);
        int offset = header.capacity();
        for (int i = 0; i < ICO_SIZES.length; i++) {
            int size = ICO_SIZES[i];
            header.put((byte) (size >= 256 ? 0 : size)).put((byte) (size >= 256 ? 0 : size)).put((byte) 0).put((byte) 0)
                .putShort((short) 1).putShort((short) 32).putInt(entries.get(i).length).putInt(offset);
            offset += entries.get(i).length;
        }
        var out = new ByteArrayOutputStream(); out.write(header.array());
        for (byte[] entry : entries) out.write(entry);
        return out.toByteArray();
    }
    private static byte[] dib(BufferedImage image) {
        int size = image.getWidth(), maskRow = (size + 31) / 32 * 4;
        var buffer = ByteBuffer.allocate(40 + size * size * 4 + maskRow * size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(40).putInt(size).putInt(size * 2).putShort((short) 1).putShort((short) 32).putInt(0)
            .putInt(size * size * 4 + maskRow * size).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = size - 1; y >= 0; y--) for (int x = 0; x < size; x++) {
            int argb = image.getRGB(x, y);
            buffer.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
        }
        // AND mask for readers that ignore alpha: set where fully transparent.
        for (int y = size - 1; y >= 0; y--) {
            byte[] row = new byte[maskRow];
            for (int x = 0; x < size; x++) if (image.getRGB(x, y) >>> 24 == 0) row[x / 8] |= (byte) (0x80 >> (x % 8));
            buffer.put(row);
        }
        return buffer.array();
    }

    private static byte[] icns(Map<Integer, BufferedImage> sizes) throws IOException {
        var body = new ByteArrayOutputStream();
        for (String[] type : ICNS_TYPES) {
            byte[] data = png(sizes.get(Integer.parseInt(type[1])));
            body.write(type[0].getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            body.write(ByteBuffer.allocate(4).putInt(data.length + 8).array()); body.write(data);
        }
        var out = new ByteArrayOutputStream();
        out.write("icns".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(ByteBuffer.allocate(4).putInt(body.size() + 8).array()); body.writeTo(out);
        return out.toByteArray();
    }
}
