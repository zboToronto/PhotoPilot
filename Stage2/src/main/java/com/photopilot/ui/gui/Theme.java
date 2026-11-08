package com.photopilot.ui.gui;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javafx.scene.paint.Color;

/**
 * Central semantic palette of the GUI: one accent family (a preset, or one generated from the user's custom hue), the
 * effective Light/Dark lighting and the surface opacity chosen with the Transparency slider. Every themed colour in
 * photopilot.css is a looked-up token produced here, and
 * the wave background reads its lighting here, so one change recolours background, highlights and controls together.
 * Presentation only: photographs, previews and exports are never tinted, faded or blurred.
 */
record Theme(Accent accent, Mode mode, boolean dark, int opacity, int customHue) {
    /** customHue of a preset theme; a custom theme carries its hue (0-359) and ignores the preset accent. */
    static final int PRESET = -1, DEFAULT_OPACITY = 62, DEFAULT_CUSTOM_HUE = 262;
    static final Theme DEFAULT = new Theme(Accent.BLUE, Mode.SYSTEM, false, DEFAULT_OPACITY);
    /** Track of the custom hue slider: the generated swatch colour of every hue, so the slider shows what each hue gives. */
    static final String HUE_SPECTRUM = spectrum();

    Theme {
        Objects.requireNonNull(accent); Objects.requireNonNull(mode);
        opacity = Math.max(0, Math.min(100, opacity));
        customHue = customHue < 0 ? PRESET : Math.floorMod(customHue, 360);
    }
    Theme(Accent accent, Mode mode, boolean dark, int opacity) { this(accent, mode, dark, opacity, PRESET); }
    static Theme custom(int hue, Mode mode, boolean dark, int opacity) { return new Theme(Accent.BLUE, mode, dark, opacity, Math.floorMod(hue, 360)); }
    boolean custom() { return customHue != PRESET; }
    /** The colour family every token and the background derive from: the preset's hand-tuned one or the generated one. */
    Family family() { return custom() ? Family.custom(customHue) : accent.family; }

    /** Appearance mode as saved; SYSTEM resolves to the operating system's current choice (Light when unknown). */
    enum Mode {
        LIGHT("light", "settings.mode.light"), DARK("dark", "settings.mode.dark"), SYSTEM("system", "settings.mode.system");
        final String id, key;
        Mode(String id, String key) { this.id = id; this.key = key; }
        static Optional<Mode> parse(String id) { return Arrays.stream(values()).filter(mode -> mode.id.equals(id)).findFirst(); }
    }

    /**
     * Five low-saturation families, each with a primary and a nearby secondary hue in the same family (the secondary hue
     * lights the background waves, so it must never drift into a neighbouring family). The hand-tuned control colours
     * keep at least 4.5:1 text contrast (fill/on-fill and accent text on surfaces) in both modes.
     */
    enum Accent {
        BLUE("blue", "settings.theme.blue", 224, 212, 0.92, "#7f9ade", "#4a68cc", "#ffffff", "#344ea6", "#8ea5ec", "#0f1630", "#a9bcf5"),
        SAGE("sage", "settings.theme.sage", 140, 124, 0.50, "#97b892", "#4f7a58", "#ffffff", "#3e6346", "#a3c49c", "#13230f", "#b3d1ac"),
        PEACH("peach", "settings.theme.peach", 22, 36, 0.80, "#eeac8c", "#e8a283", "#3d2116", "#8c4930", "#f0b598", "#3a1e12", "#f4c3a9"),
        ROSE("rose", "settings.theme.rose", 345, 330, 0.64, "#d497a8", "#a65a6e", "#ffffff", "#8a4356", "#e3a3b4", "#35141f", "#ecb5c3"),
        TEAL("teal", "settings.theme.teal", 186, 196, 0.72, "#82bcbe", "#3f7a7c", "#ffffff", "#2a5c5e", "#93c9ca", "#0d2324", "#a6d5d5");
        /** Stored id of the user-configurable Custom choice; it is not a preset and never appears in values(). */
        static final String CUSTOM_ID = "custom";
        final String id, key;
        final double hue, secondHue, chroma;
        final Color swatch, lightFill, lightOnFill, lightText, darkFill, darkOnFill, darkText;
        final Family family;
        Accent(String id, String key, double hue, double secondHue, double chroma, String swatch,
               String lightFill, String lightOnFill, String lightText, String darkFill, String darkOnFill, String darkText) {
            this.id = id; this.key = key; this.hue = hue; this.secondHue = secondHue; this.chroma = chroma;
            this.swatch = Color.web(swatch);
            this.lightFill = Color.web(lightFill); this.lightOnFill = Color.web(lightOnFill); this.lightText = Color.web(lightText);
            this.darkFill = Color.web(darkFill); this.darkOnFill = Color.web(darkOnFill); this.darkText = Color.web(darkText);
            family = new Family(hue, secondHue, chroma, this.swatch, this.lightFill, this.lightOnFill, this.lightText, this.darkFill, this.darkOnFill, this.darkText);
        }
        /** Stored ids; "lavender" was merged into Cool Blue on 2026-11-07, so a saved Lavender choice opens as Cool Blue. */
        static Optional<Accent> parse(String id) {
            if ("lavender".equals(id)) return Optional.of(BLUE);
            return Arrays.stream(values()).filter(accent -> accent.id.equals(id)).findFirst();
        }
    }

    /**
     * One colour family: a primary hue and a nearby secondary hue (which lights the background waves), a chroma scale for
     * the low-saturation tints, and the control colours of both modes (fill, text on the fill, accent text).
     */
    record Family(double hue, double secondHue, double chroma, Color swatch, Color lightFill, Color lightOnFill, Color lightText,
                  Color darkFill, Color darkOnFill, Color darkText) {
        private static final Map<Integer, Family> GENERATED = new java.util.concurrent.ConcurrentHashMap<>();
        /** Chroma anchors taken from the presets: yellow-green hues read as more saturated, so they get less. */
        private static final double[][] CHROMA = { { 0, .66 }, { 22, .80 }, { 60, .56 }, { 100, .50 }, { 140, .52 },
            { 186, .72 }, { 224, .90 }, { 270, .82 }, { 315, .70 }, { 360, .66 } };

        /**
         * A pastel family generated from any hue, so a custom colour never reaches the interface at full strength:
         * each role has a fixed, moderate saturation and its brightness is solved for a target luminance. That keeps
         * the presets' contrast for every hue (white text on the light-mode fill at least 4.6:1, dark text on the
         * dark-mode fill, accent text on surfaces) instead of letting yellow come out too light or blue too dark.
         */
        static Family custom(int hue) { return GENERATED.computeIfAbsent(Math.floorMod(hue, 360), Family::generate); }
        private static Family generate(int hue) {
            // Hues that read as more saturated (greens, yellows) get a little less saturation, as in the presets.
            double chroma = chroma(hue);
            return new Family(hue, hue - 12, chroma, Color.hsb(hue, .36, .88),
                ofLuminance(hue, .30 + .18 * chroma, .165), Color.WHITE, ofLuminance(hue, .38 + .18 * chroma, .085),
                ofLuminance(hue, .24 + .14 * chroma, .37), Color.hsb(hue, .60, .17), ofLuminance(hue, .20 + .10 * chroma, .50));
        }
        private static double chroma(double hue) {
            for (int i = 1; i < CHROMA.length; i++) if (hue <= CHROMA[i][0]) {
                double[] a = CHROMA[i - 1], b = CHROMA[i];
                return a[1] + (b[1] - a[1]) * (hue - a[0]) / (b[0] - a[0]);
            }
            return CHROMA[0][1];
        }
        /** The colour of this hue whose relative luminance is the target; desaturated only if full brightness is too dark. */
        static Color ofLuminance(double hue, double saturation, double target) {
            if (luminance(Color.hsb(hue, saturation, 1)) < target) {
                double low = 0, high = saturation;
                for (int i = 0; i < 32; i++) { double mid = (low + high) / 2; if (luminance(Color.hsb(hue, mid, 1)) >= target) low = mid; else high = mid; }
                return Color.hsb(hue, low, 1);
            }
            double low = 0, high = 1;
            for (int i = 0; i < 32; i++) { double mid = (low + high) / 2; if (luminance(Color.hsb(hue, saturation, mid)) < target) low = mid; else high = mid; }
            return Color.hsb(hue, saturation, high);
        }
    }
    private static String spectrum() {
        var stops = new StringJoiner(", ", "linear-gradient(to right, ", ")");
        for (int hue = 0; hue <= 360; hue += 30)
            stops.add(css(Family.custom(hue).swatch()) + String.format(Locale.ROOT, " %.1f%%", hue / 3.6));
        return stops.toString();
    }
    /** WCAG relative luminance. */
    static double luminance(Color c) { return .2126 * linear(c.getRed()) + .7152 * linear(c.getGreen()) + .0722 * linear(c.getBlue()); }
    private static double linear(double v) { return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }

    /** A diffused radial light field: colour (alpha = strength), centre as window fractions, radius as a fraction of the diagonal. */
    record Light(Color color, double x, double y, double radius) { }
    /**
     * Colours of one wave surface: its body just below the crest and where it is deepest (alpha = body strength), the
     * luminous rim along the crest and the soft shade just above it (alpha = strength; transparent for none).
     */
    record Wave(Color near, Color deep, Color rim, Color shade) { }
    /**
     * Lighting of the procedural wave composition (see {@link WaveRenderer}, which owns the geometry): a diagonal base
     * gradient, three light fields under the waves (sky light, side bloom, ambient pool), a glow over the lower right
     * of both waves, and the colours of the back, primary and secondary wave surfaces. Dark mode relights the
     * same geometry with its own colours and light positions; it is never the Light palette darkened or inverted.
     */
    record Lighting(Color base0, Color base1, Light sky, Light bloom, Light ambient, Light glow, Wave back, Wave primary, Wave secondary) { }

    /**
     * Companion hue of a family: a neighbour about 40 degrees away that tints the soft light fields (lavender beside
     * blue, mint-blue beside teal, pink beside peach), like light from the side of the scene. It moves continuously
     * with the hue (towards blue-violet for greens, cyans and blues, towards pink for reds and oranges, none near
     * yellow-green and violet), so dragging the custom hue never makes the background jump.
     */
    static double bloomHue(double hue) { return Math.floorMod((int) Math.round(hue - 40 * Math.cos(Math.toRadians(hue))), 360); }

    /**
     * The whole background palette is derived from the family's hues (primary, secondary and companion), so presets
     * and any custom hue share one generator. Light: an airy pastel base and white sky light, with wave bodies whose
     * deepest tones are solved for a fixed luminance, so every hue gets the same gentle depth and text over them keeps
     * its contrast. The ambient pool is a broad, low-saturation pool of the theme colour behind the content panels;
     * it gives translucent panels something to reveal. Dark: a navy/charcoal base with coloured, restrained light,
     * slightly lifted wave bodies and luminous crests in the theme colour.
     */
    Lighting lighting() {
        var family = family();
        double h = family.hue(), h2 = family.secondHue(), hb = bloomHue(h);
        Color none = Color.TRANSPARENT;
        if (!dark) return new Lighting(
            hsb(h, .05, .995), hsb(h2, .13, .965),
            new Light(Color.rgb(255, 255, 255, .55), .10, -.06, .50),
            new Light(hsb(hb, .17, 1.0, .55), .00, .42, .36),
            // Same soft saturation for every family (not scaled by chroma), so transparency reads equally in each theme.
            new Light(Color.hsb(h, .17, .95, .72), .60, .34, .60),
            new Light(hsb(hb, .26, .99, .42), .95, .80, .40),
            new Wave(hsb(h, .08, .975, .40), hsb(h2, .14, .955, .50), Color.rgb(255, 255, 255, .22), none),
            new Wave(hsb(h2, .15, .975, .78), tone(h, .30, .655, .92), Color.rgb(255, 255, 255, .62), tone(h2, .22, .72, .26)),
            new Wave(hsb(h, .20, .97, .80), tone(h, .32, .625, .94), Color.rgb(255, 255, 255, .70), tone(h2, .26, .67, .30)));
        Color navy = Color.hsb(226, .34, .15), deep = Color.hsb(230, .36, .085);
        return new Lighting(
            mix(hsb(h, .40, .15), navy, .60), mix(hsb(h2, .40, .085), deep, .62),
            // Light and wave tones are solved for a luminance, so a yellow and a blue hue glow equally (and gently).
            // The companion-hue lights share a little of the navy, so warm families get a soft plum light, never brown.
            new Light(mix(tone(hb, .50, .085, 1), navy, .30, .50), .14, .14, .34),
            new Light(tone(h, .40, .035, .16), .55, -.02, .42),
            new Light(mix(hsb(h, .45, .36), navy, .40, .30), .60, .34, .58),
            new Light(mix(tone(hb, .55, .100, 1), navy, .25, .36), .84, .84, .34),
            // Wave bodies stay navy/charcoal with a share of the theme colour; the crests carry the colour as light.
            new Wave(mix(tone(h, .36, .022, 1), navy, .45, .40), mix(tone(h2, .40, .015, 1), deep, .45, .50), tone(h, .40, .060, .12), none),
            new Wave(mix(tone(h, .45, .044, 1), navy, .40, .70), mix(tone(h2, .50, .026, 1), deep, .40, .80), tone(h, .45, .200, .44), alpha(deep, .20)),
            new Wave(mix(tone(h, .50, .050, 1), navy, .35, .74), mix(tone(h2, .55, .030, 1), deep, .35, .84), tone(h, .45, .220, .48), alpha(deep, .22)));
    }
    /** A tint of the hue (saturation scaled by the family's chroma) whose relative luminance is the target. */
    private Color tone(double hue, double saturation, double luminance, double alpha) {
        return alpha(Family.ofLuminance(Math.floorMod((int) Math.round(hue), 360), clamp(saturation * family().chroma()), luminance), alpha);
    }
    private static Color mix(Color accentTone, Color neutral, double neutralShare) { return accentTone.interpolate(neutral, neutralShare); }
    private static Color mix(Color accentTone, Color neutral, double neutralShare, double alpha) {
        var c = mix(accentTone, neutral, neutralShare); return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** Looked-up colour tokens, in a stable order. Opacity-dependent surfaces keep a readable minimum at 0%. */
    Map<String, String> tokens() {
        var family = family();
        double h = family.hue(), h2 = family.secondHue(), o = opacity / 100.0;
        Color fill = dark ? family.darkFill() : family.lightFill(), onFill = dark ? family.darkOnFill() : family.lightOnFill();
        Color accentText = dark ? family.darkText() : family.lightText();
        Map<String, String> t = new LinkedHashMap<>();
        Color text, muted, subtle;
        if (!dark) {
            text = hsb(h, .30, .17); muted = hsb(h, .20, .38); subtle = hsb(h, .12, .52);
            put(t, "-pp-text", text); put(t, "-pp-text-muted", muted); put(t, "-pp-text-subtle", subtle);
            put(t, "-pp-window-text", text);
            // Panel backgrounds only (never node opacity): close to linear from clearly see-through to fully opaque.
            put(t, "-pp-surface-1", alpha(hsb(h, .02, 1.0), lerp(.12, 1, o)));
            put(t, "-pp-surface-header", alpha(hsb(h, .02, 1.0), lerp(.18, 1, o)));
            put(t, "-pp-surface-2", alpha(Color.WHITE, lerp(.22, 1, o)));
            put(t, "-pp-surface-2-hover", alpha(Color.WHITE, lerp(.42, 1, o)));
            put(t, "-pp-card", alpha(Color.WHITE, lerp(.22, 1, o)));
            put(t, "-pp-control", alpha(Color.WHITE, lerp(.52, 1, o)));
            put(t, "-pp-input", alpha(Color.WHITE, lerp(.84, 1, o)));
            put(t, "-pp-table-header", alpha(hsb(h, .03, .99), lerp(.60, 1, o)));
            put(t, "-pp-hover", alpha(text, .06)); put(t, "-pp-pressed", alpha(text, .11));
            // Glass rim: a bright edge while see-through, settling into a faint neutral hairline on an opaque panel.
            put(t, "-pp-border", Color.rgb(255, 255, 255, .88).interpolate(alpha(text, .08), o));
            put(t, "-pp-border-strong", alpha(hsb(h, .26, .84), .80));
            put(t, "-pp-hairline", alpha(text, .11)); put(t, "-pp-shadow", hsb(h, .50, .32, lerp(.05, .11, o)));
            t.put("-pp-glass-sheen", sheen(lerp(.40, 0, o)));
            put(t, "-pp-badge", alpha(text, .07)); put(t, "-pp-scroll-thumb", alpha(text, .26)); put(t, "-pp-track", alpha(text, .14));
            put(t, "-pp-thumb", Color.WHITE); put(t, "-pp-segment-track", alpha(text, .06)); put(t, "-pp-avatar-ring", alpha(Color.WHITE, .72));
            put(t, "-pp-danger", Color.web("#a32b1c")); put(t, "-pp-danger-border", Color.web("#a32b1c", .45));
            put(t, "-pp-dialog", hsb(h, .025, .995)); put(t, "-pp-dialog-border", alpha(text, .12));
            put(t, "-pp-dialog-text", text); put(t, "-pp-dialog-muted", muted);
            put(t, "-pp-dialog-input", Color.WHITE); put(t, "-pp-dialog-input-border", alpha(text, .18));
            put(t, "-pp-photo-mat", Color.WHITE);
        } else {
            text = hsb(h, .06, .95); muted = hsb(h, .10, .75); subtle = hsb(h, .10, .58);
            put(t, "-pp-text", text); put(t, "-pp-text-muted", muted); put(t, "-pp-text-subtle", subtle);
            put(t, "-pp-window-text", text);
            // Dark surfaces are charcoal-navy with only a trace of the accent, matching the background base.
            put(t, "-pp-surface-1", alpha(darkTone(.14), lerp(.30, 1, o)));
            put(t, "-pp-surface-header", alpha(darkTone(.13), lerp(.38, 1, o)));
            put(t, "-pp-surface-2", alpha(darkTone(.19), lerp(.40, 1, o)));
            put(t, "-pp-surface-2-hover", alpha(darkTone(.23), lerp(.56, 1, o)));
            put(t, "-pp-card", alpha(darkTone(.20), lerp(.34, 1, o)));
            put(t, "-pp-control", alpha(darkTone(.24), lerp(.58, 1, o)));
            put(t, "-pp-input", alpha(darkTone(.12), lerp(.84, 1, o)));
            put(t, "-pp-table-header", alpha(darkTone(.17), lerp(.66, 1, o)));
            put(t, "-pp-hover", alpha(Color.WHITE, .07)); put(t, "-pp-pressed", alpha(Color.WHITE, .12));
            put(t, "-pp-border", alpha(Color.WHITE, lerp(.15, .07, o))); put(t, "-pp-border-strong", alpha(hsb(h, .30, .72), .45));
            put(t, "-pp-hairline", alpha(Color.WHITE, .10)); put(t, "-pp-shadow", Color.rgb(0, 0, 0, lerp(.22, .38, o)));
            t.put("-pp-glass-sheen", sheen(lerp(.07, 0, o)));
            put(t, "-pp-badge", alpha(Color.WHITE, .08)); put(t, "-pp-scroll-thumb", alpha(Color.WHITE, .24)); put(t, "-pp-track", alpha(Color.WHITE, .16));
            put(t, "-pp-thumb", hsb(h, .06, .95)); put(t, "-pp-segment-track", alpha(Color.WHITE, .06)); put(t, "-pp-avatar-ring", alpha(Color.WHITE, .14));
            put(t, "-pp-danger", Color.web("#f08a7e")); put(t, "-pp-danger-border", Color.web("#f08a7e", .50));
            put(t, "-pp-dialog", darkTone(.17)); put(t, "-pp-dialog-border", alpha(Color.WHITE, .10));
            put(t, "-pp-dialog-text", text); put(t, "-pp-dialog-muted", muted);
            put(t, "-pp-dialog-input", darkTone(.12)); put(t, "-pp-dialog-input-border", alpha(Color.WHITE, .16));
            put(t, "-pp-photo-mat", Color.web("#151515"));
        }
        // Accent family: fills, text, selection and focus.
        put(t, "-pp-accent", fill); put(t, "-pp-on-accent", onFill); put(t, "-pp-accent-text", accentText);
        put(t, "-pp-accent-hover", dark ? brightness(fill, 1.07) : brightness(fill, .93));
        put(t, "-pp-accent-pressed", brightness(fill, dark ? .92 : .86));
        put(t, "-pp-accent-soft", alpha(fill, dark ? .20 : .15));
        put(t, "-pp-accent-border", alpha(dark ? accentText : fill, .72));
        put(t, "-pp-focus-ring", accentText);
        put(t, "-pp-selected", fill); put(t, "-pp-selected-soft", alpha(fill, dark ? .20 : .15));
        put(t, "-pp-accent-glow", alpha(fill, dark ? .22 : .32));
        t.put("-pp-primary-fill", gradient(brightness(fill, dark ? 1.04 : 1.07), fill));
        t.put("-pp-primary-hover", gradient(brightness(fill, dark ? 1.09 : 1.03), brightness(fill, dark ? 1.04 : .95)));
        t.put("-pp-primary-pressed", gradient(brightness(fill, .90), brightness(fill, .88)));
        put(t, "-pp-primary-text", onFill); put(t, "-pp-primary-border", alpha(brightness(fill, .78), dark ? .20 : .30));
        double dim = dark ? .90 : 1;
        t.put("-pp-avatar-0", diagonal(hsb(h - 10, .50, .90 * dim), hsb(h2, .62, .74 * dim)));
        t.put("-pp-avatar-1", diagonal(hsb(h2, .44, .88 * dim), hsb(h, .62, .70 * dim)));
        t.put("-pp-avatar-2", diagonal(hsb(h + 18, .42, .86 * dim), hsb(h - 16, .58, .68 * dim)));
        put(t, "-pp-avatar-text", Color.rgb(255, 255, 255, .96));
        put(t, "-pp-avatar-soft", alpha(fill, dark ? .26 : .20));
        put(t, "-pp-backdrop-base", lighting().base0());
        t.put("-pp-hue-spectrum", HUE_SPECTRUM);
        // Standard JavaFX (modena) controls follow the same mode and accent.
        put(t, "-fx-base", dark ? darkTone(.25) : hsb(h, .05, .95));
        put(t, "-fx-background", dark ? darkTone(.14) : hsb(h, .03, .975));
        put(t, "-fx-control-inner-background", dark ? darkTone(.15) : Color.WHITE);
        put(t, "-fx-accent", fill); put(t, "-fx-default-button", fill);
        put(t, "-fx-focus-color", accentText); t.put("-fx-faint-focus-color", "transparent");
        put(t, "-fx-selection-bar", fill);
        put(t, "-fx-selection-bar-non-focused", fill.interpolate(dark ? darkTone(.15) : Color.WHITE, dark ? .55 : .70));
        return t;
    }

    /** The full token sheet; applied after photopilot.css so it overrides that file's Soft Blue defaults. */
    String stylesheet() {
        var css = new StringBuilder(".root {\n");
        tokens().forEach((key, value) -> css.append("    ").append(key).append(": ").append(value).append(";\n"));
        return css.append("}\n").toString();
    }
    String stylesheetUrl() {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(stylesheet().getBytes(StandardCharsets.UTF_8));
    }
    Color backdropBase() { return lighting().base0(); }

    /** Dark-mode surface tone: charcoal-navy with only a trace of the accent hue. */
    private Color darkTone(double level) { return mix(hsb(family().hue(), .30, level), Color.hsb(226, .24, level), .62); }
    private Color hsb(double hue, double saturation, double brightness) { return hsb(hue, saturation, brightness, 1); }
    private Color hsb(double hue, double saturation, double brightness, double alpha) {
        return Color.hsb(Math.floorMod((int) Math.round(hue), 360), clamp(saturation * family().chroma()), clamp(brightness), clamp(alpha));
    }
    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private static double lerp(double from, double to, double amount) { return from + (to - from) * amount; }
    private static Color alpha(Color color, double alpha) { return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha)); }
    private static Color brightness(Color color, double factor) { return color.deriveColor(0, 1, factor, 1); }
    private static void put(Map<String, String> tokens, String key, Color color) { tokens.put(key, css(color)); }
    private static String gradient(Color top, Color bottom) { return "linear-gradient(to bottom, " + css(top) + " 0%, " + css(bottom) + " 100%)"; }
    /** Restrained glass highlight along a panel's top edge; fully transparent (no effect) on an opaque panel. */
    private static String sheen(double strength) {
        return "linear-gradient(to bottom, " + css(Color.rgb(255, 255, 255, clamp(strength))) + " 0%, rgba(255,255,255,0.000) 42%)";
    }
    private static String diagonal(Color from, Color to) { return "linear-gradient(to bottom right, " + css(from) + " 0%, " + css(to) + " 100%)"; }
    static String css(Color color) {
        return String.format(Locale.ROOT, "rgba(%d,%d,%d,%.3f)", Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
            Math.round(color.getBlue() * 255), color.getOpacity());
    }
}
