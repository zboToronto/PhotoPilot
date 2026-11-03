import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.*;
import javax.imageio.stream.FileImageOutputStream;

/**
 * Derives the main workspace background from the startup wallpaper: a true Gaussian blur plus a neutral
 * overlay, precomputed so the application never blurs at runtime.
 * Usage from Stage2: java tools/BlurWorkspaceWallpaper.java [sigma] [overlay]
 */
public class BlurWorkspaceWallpaper {
    private static final String RESOURCES = "src/main/resources/com/photopilot/ui/gui/";

    public static void main(String[] args) throws Exception {
        double sigma = args.length > 0 ? Double.parseDouble(args[0]) : 34, overlay = args.length > 1 ? Double.parseDouble(args[1]) : .12;
        var in = ImageIO.read(new File(RESOURCES + "user-selection-wallpaper.jpg"));
        int w = in.getWidth(), h = in.getHeight();
        float[][] channels = new float[3][w * h];
        for (int i = 0; i < w * h; i++) {
            int pixel = in.getRGB(i % w, i / w);
            channels[0][i] = pixel >> 16 & 255; channels[1][i] = pixel >> 8 & 255; channels[2][i] = pixel & 255;
        }
        float[] kernel = kernel(sigma);
        var out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var noise = new Random(3311);
        for (int k = 0; k < 3; k++) channels[k] = vertical(horizontal(channels[k], w, h, kernel), w, h, kernel);
        for (int i = 0; i < w * h; i++) {
            double dither = noise.nextGaussian() * .8;
            int rgb = 0;
            for (int k = 0; k < 3; k++) rgb = rgb << 8 | (int) Math.max(0, Math.min(255, Math.round(channels[k][i] * (1 - overlay) + 255 * overlay + dither)));
            out.setRGB(i % w, i / w, rgb);
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var parameters = writer.getDefaultWriteParam();
        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(.9f);
        try (var stream = new FileImageOutputStream(new File(RESOURCES + "workspace-wallpaper.jpg"))) {
            writer.setOutput(stream); writer.write(null, new IIOImage(out, null, null), parameters);
        }
        writer.dispose();
    }
    private static float[] kernel(double sigma) {
        int radius = (int) Math.ceil(sigma * 3);
        float[] values = new float[radius * 2 + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) { values[i + radius] = (float) Math.exp(-i * i / (2 * sigma * sigma)); sum += values[i + radius]; }
        for (int i = 0; i < values.length; i++) values[i] /= (float) sum;
        return values;
    }
    private static float[] horizontal(float[] source, int w, int h, float[] kernel) {
        int radius = kernel.length / 2; float[] result = new float[source.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int i = -radius; i <= radius; i++) sum += kernel[i + radius] * source[Math.max(0, Math.min(w - 1, x + i)) + y * w];
            result[x + y * w] = sum;
        }
        return result;
    }
    private static float[] vertical(float[] source, int w, int h, float[] kernel) {
        int radius = kernel.length / 2; float[] result = new float[source.length];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int i = -radius; i <= radius; i++) sum += kernel[i + radius] * source[x + Math.max(0, Math.min(h - 1, y + i)) * w];
            result[x + y * w] = sum;
        }
        return result;
    }
}
