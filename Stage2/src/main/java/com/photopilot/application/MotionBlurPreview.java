package com.photopilot.application;

import java.util.List;
import java.util.OptionalInt;

/**
 * Geometry of the motion blur preview, computed from the calculated blur only: the scale of the magnified detail view,
 * the exposure-averaged coverage of a moving edge, and the smear of a reference figure across the frame. No JavaFX.
 * <p>
 * During the exposure an opaque subject moving at constant speed covers each image point for a share of the time; that
 * share is the subject's outline averaged along the motion over the blur length (a box filter). The preview draws
 * exactly that, so its smear is the calculated blur at the stated scale and nothing else: no camera shake, no rolling
 * shutter, no defocus. It illustrates the blur length; it does not predict a photograph.
 */
public final class MotionBlurPreview {
    private MotionBlurPreview() { }

    /** Height of the figure drawn for scale in the frame view. */
    public static final double REFERENCE_HEIGHT_METRES = 1.7;
    /** The blur spans at most this share of the detail view, so both of its ends stay visible. */
    static final double DETAIL_FILL = 0.42;
    private static final double[] PIXEL_SCALES = { 24, 16, 12, 8, 6, 4, 3, 2, 1 };

    /**
     * Scale of the detail view. Its unit is one image pixel when the image width is known, otherwise one micrometre
     * on the sensor; {@code screenPerUnit} says how many screen pixels draw one unit (below 1 the view is reduced).
     * Screen pixels of the preview are never image pixels: the scale is always stated beside the view.
     */
    public record Detail(boolean imagePixels, double screenPerUnit, double unitsAcross, double blurUnits) { }

    public static Detail detail(double blurMm, double sensorWidthMm, OptionalInt imageWidthPx, double viewWidth) {
        boolean pixels = imageWidthPx.isPresent();
        double blur = pixels ? blurMm * imageWidthPx.getAsInt() / sensorWidthMm : blurMm * 1000;
        double scale = pixels ? PIXEL_SCALES[0] : 20;
        if (blur > 0) {
            double fit = DETAIL_FILL * viewWidth / blur;
            if (pixels) {
                scale = PIXEL_SCALES[PIXEL_SCALES.length - 1];
                for (double candidate : PIXEL_SCALES) if (candidate <= fit) { scale = candidate; break; }
                // Longer blurs reduce the view by whole powers of two (1 screen pixel = 2, 4, 8 ... image pixels).
                while (scale > fit) scale /= 2;
            } else {
                // 1-2-5 ladder of screen pixels per micrometre.
                double decade = Math.pow(10, Math.floor(Math.log10(fit)));
                scale = fit >= 5 * decade ? 5 * decade : fit >= 2 * decade ? 2 * decade : decade;
                scale = Math.min(scale, 20);
            }
        }
        return new Detail(pixels, scale, viewWidth / scale, blur);
    }

    /**
     * Exposure-averaged coverage (0 to 1) at position x of an opaque block [start, start + width] that moves a total of
     * {@code blur} during the exposure, centred on that position. Without motion it is the sharp block itself.
     */
    public static double coverage(double x, double start, double width, double blur) {
        if (!(blur > 0)) return x >= start && x <= start + width ? 1 : 0;
        double from = Math.max(x - blur / 2, start), to = Math.min(x + blur / 2, start + width);
        return Math.max(0, to - from) / blur;
    }

    /**
     * Each row of a coverage mask averaged over a horizontal window of {@code blur} pixels (centred, fractional lengths
     * handled exactly for the mask's pixel grid). A blur of zero returns the mask unchanged.
     */
    public static float[] smear(float[] mask, int width, int height, double blur) {
        if (!(blur > 1e-6)) return mask.clone();
        float[] out = new float[mask.length];
        double[] sum = new double[width + 1];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            boolean empty = true;
            for (int x = 0; x < width; x++) { sum[x + 1] = sum[x] + mask[row + x]; if (mask[row + x] != 0) empty = false; }
            if (empty) continue;
            for (int x = 0; x < width; x++) {
                // The pixel's own extent [x, x + 1] is part of the average, so a blur below one pixel leaves it almost unchanged.
                double centre = x + 0.5, value = (integral(sum, width, centre + blur / 2) - integral(sum, width, centre - blur / 2)) / blur;
                out[row + x] = (float) Math.max(0, Math.min(1, value));
            }
        }
        return out;
    }
    /** Integral of the piecewise-constant row from 0 to position. */
    private static double integral(double[] sum, int width, double position) {
        if (position <= 0) return 0;
        if (position >= width) return sum[width];
        int whole = (int) position;
        return sum[whole] + (sum[whole + 1] - sum[whole]) * (position - whole);
    }

    /**
     * A plain standing figure for scale. Coordinates are in figure heights: x from the figure's centre line, y up from
     * the ground. Each part is a rounded rectangle {x, y, width, height, corner radius}; the head is a circle {x, y, radius}.
     */
    public static final class Figure {
        private Figure() { }
        public static final double[] HEAD = { 0, 0.895, 0.105 };
        public static final List<double[]> PARTS = List.of(
            new double[] { -0.145, 0.45, 0.29, 0.33, 0.05 },      // torso
            new double[] { -0.225, 0.46, 0.055, 0.31, 0.027 },    // arms
            new double[] { 0.17, 0.46, 0.055, 0.31, 0.027 },
            new double[] { -0.135, 0, 0.115, 0.47, 0.03 },        // legs
            new double[] { 0.02, 0, 0.115, 0.47, 0.03 });
        /** Half of the figure's width, in figure heights. */
        public static final double HALF_WIDTH = 0.225;

        public static boolean contains(double x, double y) {
            if (Math.hypot(x - HEAD[0], y - HEAD[1]) <= HEAD[2]) return true;
            for (double[] part : PARTS) {
                double r = part[4], dx = Math.max(Math.max(part[0] + r - x, 0), x - (part[0] + part[2] - r)), dy = Math.max(Math.max(part[1] + r - y, 0), y - (part[1] + part[3] - r));
                if (dx * dx + dy * dy <= r * r) return true;
            }
            return false;
        }
        /**
         * Coverage mask of the figure in a width x height pixel frame: feet at {@code groundY} (pixels from the top),
         * centred at {@code centreX}, {@code figureHeight} pixels tall. 3 x 3 samples per pixel; parts outside the
         * frame are cut off.
         */
        public static float[] mask(int width, int height, double centreX, double groundY, double figureHeight) {
            float[] mask = new float[width * height];
            if (!(figureHeight > 0)) return mask;
            int left = (int) Math.max(0, Math.floor(centreX - HALF_WIDTH * figureHeight)), right = (int) Math.min(width - 1, Math.ceil(centreX + HALF_WIDTH * figureHeight));
            int top = (int) Math.max(0, Math.floor(groundY - figureHeight)), bottom = (int) Math.min(height - 1, Math.ceil(groundY));
            for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
                int inside = 0;
                for (int sy = 0; sy < 3; sy++) for (int sx = 0; sx < 3; sx++)
                    if (contains((x + (sx + 0.5) / 3 - centreX) / figureHeight, (groundY - (y + (sy + 0.5) / 3)) / figureHeight)) inside++;
                mask[y * width + x] = inside / 9f;
            }
            return mask;
        }
    }
}
