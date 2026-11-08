package com.photopilot.ui.gui;

import java.util.stream.IntStream;
import javafx.scene.paint.Color;

/**
 * Procedural renderer of the flowing-wave background. Nothing is loaded from an image: every pixel is computed from
 * original vector geometry and the current {@link Theme.Lighting}, so the result is resolution-independent and
 * follows every accent, custom hue and mode. Layers, back to front:
 * <ol>
 * <li>a smooth diagonal base gradient;</li>
 * <li>large diffused radial light fields (a sky light, a side bloom in the family's companion hue and the ambient
 *     pool behind the content);</li>
 * <li>three wave surfaces whose crests are cubic Bézier curves in window fractions (a faint back fold, the broad
 *     primary wave and the secondary wave in front of it). Each surface has a soft occlusion shade just above its
 *     crest, a body that fades in below the crest and deepens with distance, and a luminous rim along the crest. All
 *     three are gradients of the distance to the curve (measured along its normal), so the waves keep recognisable
 *     silhouettes without any drawn outline, polygon seam or hard edge;</li>
 * <li>a glow in the companion hue over the lower right, lighting both waves.</li>
 * </ol>
 * Colours are composited in the perceptual OKLab space (no muddy midpoints between hues) and the result is dithered
 * below one 8-bit step, so the low-contrast gradients never band. Pure computation without a scene graph: it runs
 * off the JavaFX thread at reduced size, in parallel by rows, and can be checked numerically.
 */
final class WaveRenderer {
    private WaveRenderer() { }

    /** Width-to-height ratio the composition was drawn for; lengths scale with sqrt(width * height / this). */
    static final double DESIGN_ASPECT = 1.6;

    /**
     * Wave geometry: the crest of each surface as a cubic Bézier curve (x0, y0, x1, y1, x2, y2, x3, y3) in fractions
     * of the window, starting and ending beyond its edges so no surface ends inside the window. Lengths are in units
     * of sqrt(width * height / {@link #DESIGN_ASPECT}); "lit" is the stretch of the crest (as window fractions)
     * along which its rim light rises from nothing to full strength.
     */
    record Surface(double[] crest, double feather, double depth, double rimAt, double rimOut, double rimIn,
                   double shadeAt, double shadeOut, double shadeIn, double litFrom, double litTo) { }
    /** A faint upper fold that gives the calm upper area a little depth. */
    static final Surface BACK = new Surface(new double[] { -.10, .66, .30, .50, .62, .40, 1.12, .14 },
        .20, .55, .020, .030, .060, 0, 1, 1, .20, .95);
    /** The broad primary wave rising from the lower left towards the right edge. */
    static final Surface PRIMARY = new Surface(new double[] { -.06, .94, .34, .54, .66, .66, 1.08, .33 },
        .085, .34, .011, .0110, .032, -.032, .040, .013, .02, .55);
    /** The secondary wave in front of it, nearer the bottom right. */
    static final Surface SECONDARY = new Surface(new double[] { .14, 1.07, .52, .95, .80, .88, 1.10, .48 },
        .070, .26, .010, .0100, .028, -.030, .036, .012, .30, .70);

    /** Colour field in sRGB 0..1, three floats per pixel, row-major. */
    static float[] field(Theme.Lighting light, int width, int height) {
        float[] rgb = new float[width * height * 3];
        double unit = Math.sqrt(width * (double) height / DESIGN_ASPECT), diagonal = Math.hypot(width, height);
        double[] base0 = lab(light.base0()), base1 = lab(light.base1());
        Glow sky = new Glow(light.sky(), width, height, diagonal), bloom = new Glow(light.bloom(), width, height, diagonal);
        Glow ambient = new Glow(light.ambient(), width, height, diagonal), glow = new Glow(light.glow(), width, height, diagonal);
        Layer back = new Layer(BACK, light.back(), width, height, unit), primary = new Layer(PRIMARY, light.primary(), width, height, unit);
        Layer secondary = new Layer(SECONDARY, light.secondary(), width, height, unit);
        IntStream.range(0, height).parallel().forEach(y -> {
            double[] pixel = new double[3], tone = new double[4];
            double py = y + .5, v = py / height;
            for (int x = 0; x < width; x++) {
                double px = x + .5, u = px / width;
                lerp(base0, base1, smooth((u * width + v * height) / (width + height)), pixel);
                sky.apply(pixel, px, py); bloom.apply(pixel, px, py); ambient.apply(pixel, px, py);
                back.apply(pixel, tone, x, py, u);
                primary.apply(pixel, tone, x, py, u);
                secondary.apply(pixel, tone, x, py, u);
                glow.apply(pixel, px, py);
                srgb(pixel, rgb, (y * width + x) * 3);
            }
        });
        return rgb;
    }

    /** A soft radial light field (wider than tall), with a Gaussian falloff so it never shows a boundary. */
    private static final class Glow {
        final double[] color; final double x, y, radius;
        Glow(Theme.Light light, int width, int height, double diagonal) {
            color = lab(light.color()); x = light.x() * width; y = light.y() * height; radius = light.radius() * diagonal;
        }
        void apply(double[] pixel, double px, double py) {
            if (color[3] <= 0) return;
            double dx = (px - x) / 1.35, dy = py - y, d2 = (dx * dx + dy * dy) / (radius * radius);
            if (d2 < 4) blend(pixel, color, color[3] * Math.exp(-2.6 * d2));
        }
    }

    /** One wave surface at a given size: its crest sampled per column, with the normal-distance factor and rim light. */
    private static final class Layer {
        final Surface shape; final double unit;
        final double[] crestY, normal, lit, near, deep, rim, shade;
        Layer(Surface shape, Theme.Wave colours, int width, int height, double unit) {
            this.shape = shape; this.unit = unit;
            near = lab(colours.near()); deep = lab(colours.deep()); rim = lab(colours.rim()); shade = lab(colours.shade());
            crestY = crest(shape.crest(), width, height);
            normal = new double[width]; lit = new double[width];
            for (int x = 0; x < width; x++) {
                // Slope of the crest in pixels: vertical distance times cos(angle) is the distance along the normal,
                // so rims and shades keep one width on steep and flat stretches alike.
                double slope = (crestY[Math.min(width - 1, x + 1)] - crestY[Math.max(0, x - 1)]) / (x == 0 || x == width - 1 ? 1 : 2);
                normal[x] = 1 / Math.sqrt(1 + slope * slope);
                // The rim rises along the crest and then varies gently, like light catching a fold unevenly.
                double u = (x + .5) / width;
                lit[x] = smooth((u - shape.litFrom()) / (shape.litTo() - shape.litFrom())) * (.84 + .16 * Math.cos(5.2 * (u - .78)));
            }
        }
        void apply(double[] pixel, double[] tone, int x, double py, double u) {
            // Signed distance below the crest, along its normal, in composition units.
            double d = (py - crestY[x]) * normal[x] / unit;
            if (shade[3] > 0) blend(pixel, shade, shade[3] * lit[x] * bump(d, shape.shadeAt(), shape.shadeOut(), shape.shadeIn()));
            // The body fades in from just above the crest and deepens with distance and towards the right.
            double cover = smoother((d + .004) / shape.feather());
            if (cover > 0) {
                double t = clamp(.72 * d / shape.depth() + .38 * (u - .35));
                lerp(near, deep, t, tone);
                blend(pixel, tone, tone[3] * cover);
            }
            if (rim[3] > 0) blend(pixel, rim, rim[3] * lit[x] * bump(d, shape.rimAt(), shape.rimOut(), shape.rimIn()));
        }
    }

    /** The crest's y (pixels) at every column centre, from the Bézier curve (its x is monotonic). */
    static double[] crest(double[] p, int width, int height) {
        int samples = Math.max(256, width * 2);
        double[] xs = new double[samples + 1], ys = new double[samples + 1];
        for (int i = 0; i <= samples; i++) {
            double t = i / (double) samples, s = 1 - t;
            double a = s * s * s, b = 3 * s * s * t, c = 3 * s * t * t, e = t * t * t;
            xs[i] = (a * p[0] + b * p[2] + c * p[4] + e * p[6]) * width;
            ys[i] = (a * p[1] + b * p[3] + c * p[5] + e * p[7]) * height;
        }
        double[] y = new double[width];
        int j = 0;
        for (int x = 0; x < width; x++) {
            double cx = x + .5;
            while (j < samples - 1 && xs[j + 1] < cx) j++;
            double f = clamp((cx - xs[j]) / (xs[j + 1] - xs[j]));
            y[x] = ys[j] + (ys[j + 1] - ys[j]) * f;
        }
        return y;
    }

    /** Separable Gaussian blur with edge clamping (no darkened borders); used for the backdrop behind translucent panels. */
    static float[] blur(float[] rgb, int width, int height, double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3));
        float[] kernel = new float[radius * 2 + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) { kernel[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma)); sum += kernel[i + radius]; }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= sum;
        float[] pass = new float[rgb.length], out = new float[rgb.length];
        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) for (int c = 0; c < 3; c++) {
                float total = 0;
                for (int k = -radius; k <= radius; k++) total += kernel[k + radius] * rgb[(y * width + Math.min(width - 1, Math.max(0, x + k))) * 3 + c];
                pass[(y * width + x) * 3 + c] = total;
            }
        });
        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) for (int c = 0; c < 3; c++) {
                float total = 0;
                for (int k = -radius; k <= radius; k++) total += kernel[k + radius] * pass[(Math.min(height - 1, Math.max(0, y + k)) * width + x) * 3 + c];
                out[(y * width + x) * 3 + c] = total;
            }
        });
        return out;
    }

    /** Opaque ARGB pixels, with triangular dither below one 8-bit step so smooth gradients never show bands. */
    static int[] pack(float[] rgb, int width, int height) {
        int[] argb = new int[width * height];
        for (int i = 0; i < argb.length; i++) {
            int value = 0xFF000000;
            for (int c = 0; c < 3; c++) {
                double dither = (noise(i * 3 + c) + noise(i * 3 + c + 0x9E3779B9) - 1) * .5;
                value |= Math.max(0, Math.min(255, (int) Math.round(rgb[i * 3 + c] * 255 + dither))) << (16 - 8 * c);
            }
            argb[i] = value;
        }
        return argb;
    }

    /** Asymmetric Gaussian profile around a centre: a soft light or shade band with no edge of its own. */
    private static double bump(double d, double at, double outside, double inside) {
        double z = (d - at) / (d < at ? outside : inside);
        return z * z > 16 ? 0 : Math.exp(-.5 * z * z);
    }
    /** Quintic smootherstep on [0, 1]: its first and second derivatives are zero at both ends, so it shows no edge. */
    private static double smoother(double t) { t = clamp(t); return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double smooth(double t) { t = clamp(t); return t * t * (3 - 2 * t); }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double[] lerp(double[] a, double[] b, double t, double[] out) {
        for (int i = 0; i < out.length; i++) out[i] = a[i] + (b[i] - a[i]) * t;
        return out;
    }
    private static void blend(double[] pixel, double[] color, double alpha) {
        if (alpha <= 0) return;
        for (int i = 0; i < 3; i++) pixel[i] += (color[i] - pixel[i]) * alpha;
    }

    /** OKLab coordinates (L, a, b) of a colour, plus its opacity. */
    static double[] lab(Color color) {
        double r = linear(color.getRed()), g = linear(color.getGreen()), b = linear(color.getBlue());
        double l = Math.cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b);
        double m = Math.cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b);
        double s = Math.cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
        return new double[] { .2104542553 * l + .7936177850 * m - .0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
            .0259040371 * l + .7827717662 * m - .8086757660 * s, color.getOpacity() };
    }
    /** OKLab back to sRGB 0..1 (clamped to the gamut), written at {@code at}. */
    private static void srgb(double[] lab, float[] out, int at) {
        double l = lab[0] + .3963377774 * lab[1] + .2158037573 * lab[2];
        double m = lab[0] - .1055613458 * lab[1] - .0638541728 * lab[2];
        double s = lab[0] - .0894841775 * lab[1] - 1.2914855480 * lab[2];
        l = l * l * l; m = m * m * m; s = s * s * s;
        out[at] = (float) encode(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s);
        out[at + 1] = (float) encode(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s);
        out[at + 2] = (float) encode(-.0041960863 * l - .7034186147 * m + 1.7076147010 * s);
    }
    private static double linear(double v) { return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
    /** sRGB transfer from a table with linear interpolation (exact to well under one 8-bit step). */
    private static final double[] ENCODE = new double[4097];
    static { for (int i = 0; i < ENCODE.length; i++) { double v = i / 4096.0; ENCODE[i] = v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055; } }
    private static double encode(double v) {
        if (v <= 0) return 0;
        if (v >= 1) return 1;
        // The curve is steep near black, so the darkest part is computed exactly.
        if (v < .02) return v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055;
        double f = v * 4096; int i = (int) f;
        return ENCODE[i] + (ENCODE[Math.min(4096, i + 1)] - ENCODE[i]) * (f - i);
    }
    /** Deterministic hash noise in [0, 1), so renders are reproducible. */
    private static double noise(long seed) {
        long z = seed * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL; z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
