package com.photopilot.ui.gui;

import javafx.scene.paint.Color;

/**
 * WCAG 2 colour arithmetic of the GUI: relative luminance of sRGB colours, the contrast ratio, source-over compositing as
 * JavaFX paints translucent fills, and the smallest colour move that reaches a ratio. Stateless and independent of every
 * other PhotoPilot class, so a class can use it while it is itself being initialized: {@link Theme.Accent}'s constants and
 * {@link Theme.Family}'s generator do, which keeps them free of {@link Theme}'s own static initialization (Theme.DEFAULT
 * needs Accent.BLUE, so Accent must never need Theme).
 */
final class ColorContrast {
    private ColorContrast() { }

    /** WCAG relative luminance of the colour's sRGB channels (its opacity is ignored). */
    static double luminance(Color c) { return .2126 * linear(c.getRed()) + .7152 * linear(c.getGreen()) + .0722 * linear(c.getBlue()); }
    /** WCAG contrast ratio, (lighter luminance + 0.05) / (darker luminance + 0.05): from 1 to 21. */
    static double contrast(Color a, Color b) { return ratio(luminance(a), luminance(b)); }
    /** The contrast ratio of two relative luminances. */
    static double ratio(double luminance, double other) { return (Math.max(luminance, other) + .05) / (Math.min(luminance, other) + .05); }
    /**
     * The colour unchanged when {@code against} already has the contrast ratio on it; otherwise moved towards
     * {@code towards} (a deeper or lighter tone of the same family) only as far as that ratio needs.
     */
    static Color readable(Color color, Color towards, Color against, double ratio) {
        if (contrast(color, against) >= ratio) return color;
        double low = 0, high = 1;
        for (int i = 0; i < 24; i++) { double mid = (low + high) / 2; if (contrast(color.interpolate(towards, mid), against) >= ratio) high = mid; else low = mid; }
        return color.interpolate(towards, high);
    }
    /** Source-over compositing in sRGB (as JavaFX paints a translucent fill): {@code top} over an opaque {@code bottom}. */
    static Color over(Color top, Color bottom) {
        double a = top.getOpacity();
        return Color.color(top.getRed() * a + bottom.getRed() * (1 - a), top.getGreen() * a + bottom.getGreen() * (1 - a),
            top.getBlue() * a + bottom.getBlue() * (1 - a));
    }
    /** sRGB channel to linear light (WCAG 2 formula). */
    private static double linear(double v) { return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
}
