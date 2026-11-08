package com.photopilot.ui.gui;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The central palette: complete token coverage, readable contrast in every combination and distinct dark lighting. */
class ThemeTest {
    private static final Pattern RGBA = Pattern.compile("rgba\\((\\d+),(\\d+),(\\d+),([0-9.]+)\\)");
    private static final int[] OPACITIES = { 0, 25, 50, 75, 100 };

    /** Every preset and a custom hue every 15 degrees, in both modes and at every Transparency step. */
    static List<Theme> combinations() {
        List<Theme> all = new ArrayList<>();
        for (boolean dark : new boolean[] { false, true }) for (int opacity : OPACITIES) {
            Theme.Mode mode = dark ? Theme.Mode.DARK : Theme.Mode.LIGHT;
            for (Theme.Accent accent : Theme.Accent.values()) all.add(new Theme(accent, mode, dark, opacity));
            for (int hue = 0; hue < 360; hue += 15) all.add(Theme.custom(hue, mode, dark, opacity));
        }
        return all;
    }

    @Test void anyCustomHueBecomesASoftReadablePaletteInBothModes() {
        for (int hue = 0; hue < 360; hue++) {
            var family = Theme.Family.custom(hue);
            // Pastel, never neon: moderate saturation in every role, and the swatch is a light tint.
            for (Color c : List.of(family.lightFill(), family.darkFill(), family.lightText(), family.darkText()))
                assertTrue(c.getSaturation() <= .56, "hue " + hue + " saturation " + c.getSaturation());
            assertTrue(family.swatch().getSaturation() < .5 && family.swatch().getBrightness() > .8, "hue " + hue + " swatch");
            // Contrast is solved per hue, so yellow is never too light and blue never too dark for its text.
            assertTrue(contrast(family.lightOnFill(), family.lightFill()) >= 4.5, "hue " + hue + " light fill text");
            assertTrue(contrast(family.darkOnFill(), family.darkFill()) >= 4.5, "hue " + hue + " dark fill text");
            for (boolean dark : new boolean[] { false, true }) {
                var theme = Theme.custom(hue, dark ? Theme.Mode.DARK : Theme.Mode.LIGHT, dark, Theme.DEFAULT_OPACITY);
                var t = theme.tokens();
                assertSameColour(dark ? family.darkFill() : family.lightFill(), color(t.get("-pp-accent")), "hue " + hue + " accent token");
                var light = theme.lighting();
                double ambient = light.ambient().color().getSaturation();
                if (!dark) assertTrue(ambient > .08 && ambient < .22 && light.secondary().deep().getSaturation() < .3, "hue " + hue + " soft background");
                else assertTrue(Math.abs(light.base0().getBrightness() - .15) < .03, "hue " + hue + " dark base stays navy/charcoal");
            }
        }
        // The hue sets the identity: cyan stays a gentle cyan, red becomes a soft rose rather than a primary red.
        assertHue(Theme.Family.custom(185).lightFill(), 185); assertHue(Theme.Family.custom(0).lightFill(), 0);
        assertTrue(Theme.Family.custom(0).lightFill().getSaturation() < .5 && Theme.Family.custom(185).darkFill().getSaturation() < .4);
        // Distinct hues give distinct backgrounds; the same hue in Dark is relit, not the Light palette darkened.
        assertNotEquals(Theme.custom(0, Theme.Mode.LIGHT, false, 62).lighting(), Theme.custom(180, Theme.Mode.LIGHT, false, 62).lighting());
        var light = Theme.custom(120, Theme.Mode.LIGHT, false, 62).lighting(); var dark = Theme.custom(120, Theme.Mode.DARK, true, 62).lighting();
        assertNotEquals(light.sky().x(), dark.sky().x()); assertTrue(dark.sky().color().getSaturation() > .1 && light.sky().color().getSaturation() < .01);
        // The companion hue of the light fields moves continuously with the hue: no jump anywhere on the slider.
        for (int hue = 0; hue < 360; hue++) {
            double step = Math.abs(Theme.bloomHue(hue + 1) - Theme.bloomHue(hue)); step = Math.min(step, 360 - step);
            double gap = Math.abs(Theme.bloomHue(hue) - hue); gap = Math.min(gap, 360 - gap);
            assertTrue(step <= 2 && gap <= 40, "companion hue of " + hue + ": " + Theme.bloomHue(hue));
        }
        // Stored hues are normalised and a preset theme carries no hue.
        assertEquals(10, Theme.custom(370, Theme.Mode.LIGHT, false, 62).customHue());
        assertEquals(Theme.PRESET, new Theme(Theme.Accent.TEAL, Theme.Mode.LIGHT, false, 62).customHue());
        assertTrue(Theme.HUE_SPECTRUM.startsWith("linear-gradient(to right") && Theme.HUE_SPECTRUM.contains("100.0%"));
    }
    private static void assertHue(Color color, double hue) {
        double gap = Math.abs(color.getHue() - hue); gap = Math.min(gap, 360 - gap);
        assertTrue(gap <= 3, "hue " + hue + " drifted to " + color.getHue());
    }
    /** Equal after the 8-bit rounding of the generated stylesheet. */
    private static void assertSameColour(Color expected, Color actual, String message) {
        double tolerance = 1.5 / 255;
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
            && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance, message + ": " + expected + " vs " + actual);
    }
    static String css() throws Exception { return Files.readString(Path.of("src/main/resources/com/photopilot/ui/gui/photopilot.css")); }
    static Map<String, String> rootDefaults(String css) {
        String block = css.substring(css.indexOf(".root {") + 7, css.indexOf("}", css.indexOf(".root {")));
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : block.split(";")) {
            int colon = line.indexOf(':');
            if (colon > 0) values.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
        }
        return values;
    }

    @Test void everyStylesheetTokenIsDefinedByEveryThemeAndTheFileDefaultsMatchSoftBlueLight() throws Exception {
        String css = css();
        Set<String> used = new TreeSet<>();
        var matcher = Pattern.compile("-pp-[a-z0-9-]+").matcher(css);
        while (matcher.find()) used.add(matcher.group());
        assertTrue(used.size() > 40, "token audit lost coverage");
        var defaults = rootDefaults(css);
        assertTrue(defaults.keySet().containsAll(used), "photopilot.css .root must define every token it uses");
        for (Theme theme : combinations()) assertTrue(theme.tokens().keySet().containsAll(used), theme + " is missing tokens");
        var softBlue = new Theme(Theme.Accent.BLUE, Theme.Mode.LIGHT, false, Theme.DEFAULT_OPACITY).tokens();
        softBlue.forEach((key, value) -> assertEquals(value, defaults.get(key), "default " + key + " differs from Theme"));
        assertTrue(new Theme(Theme.Accent.SAGE, Theme.Mode.DARK, true, 40).stylesheetUrl().startsWith("data:text/css;base64,"));
    }

    @Test void textControlsAndAccentsKeepReadableContrastAtEveryTransparency() {
        double weakest = Double.MAX_VALUE; String weakestCase = "";
        Map<Theme.Lighting, List<Color>> rendered = new HashMap<>();
        for (Theme theme : combinations()) {
            var t = theme.tokens();
            Color text = color(t.get("-pp-text")), muted = color(t.get("-pp-text-muted")), accentText = color(t.get("-pp-accent-text"));
            for (Color backdrop : rendered.computeIfAbsent(theme.lighting(), ThemeTest::backdrops)) {
                for (String surface : List.of("-pp-surface-1", "-pp-surface-header", "-pp-surface-2", "-pp-card", "-pp-input", "-pp-control")) {
                    Color behind = over(color(t.get(surface)), backdrop);
                    for (var check : Map.of("text", text, "muted", muted, "accent", accentText).entrySet()) {
                        double ratio = contrast(check.getValue(), behind);
                        if (ratio < weakest) { weakest = ratio; weakestCase = theme + " " + surface + " " + check.getKey(); }
                        assertTrue(ratio >= 4.5, theme + ": " + check.getKey() + " on " + surface + " = " + ratio);
                    }
                }
                // Text placed directly on the background (chooser heading, brand) also stays readable.
                assertTrue(contrast(color(t.get("-pp-window-text")), backdrop) >= 4.5, theme + ": window text on background");
            }
            assertTrue(contrast(color(t.get("-pp-on-accent")), color(t.get("-pp-accent"))) >= 4.5, theme + ": text on accent fill");
            assertTrue(contrast(color(t.get("-pp-dialog-text")), color(t.get("-pp-dialog"))) >= 7, theme + ": dialog text");
            assertEquals(1.0, color(t.get("-pp-dialog")).getOpacity(), theme + ": dialogs stay opaque");
            assertEquals(1.0, color(t.get("-pp-photo-mat")).getOpacity(), theme + ": photo previews sit on an opaque mat");
            Color mat = color(t.get("-pp-photo-mat"));
            assertTrue(mat.getSaturation() < .05, theme + ": the photo mat is neutral, never tinted by the theme");
        }
        System.out.printf(Locale.ROOT, "Weakest contrast %.2f (%s)%n", weakest, weakestCase);
    }

    @Test void transparencyRunsFromTranslucentToFullyOpaqueSurfaces() {
        for (Theme.Accent accent : Theme.Accent.values()) for (boolean dark : new boolean[] { false, true }) {
            var clear = new Theme(accent, Theme.Mode.LIGHT, dark, 0).tokens();
            var half = new Theme(accent, Theme.Mode.LIGHT, dark, 50).tokens();
            var solid = new Theme(accent, Theme.Mode.LIGHT, dark, 100).tokens();
            for (String surface : List.of("-pp-surface-1", "-pp-surface-2", "-pp-card")) {
                double a0 = color(clear.get(surface)).getOpacity(), a50 = color(half.get(surface)).getOpacity(), a100 = color(solid.get(surface)).getOpacity();
                assertTrue(a0 < a50 && a50 < a100 && a0 <= .6, surface + " " + a0 + " " + a50);
                assertEquals(1.0, a100, 1e-3, surface + " is opaque at 100%");
            }
            assertTrue(color(clear.get("-pp-input")).getOpacity() >= .8, "inputs keep a readable minimum");
            // Visible steps: each quarter of the slider adds at least 15% panel opacity, from clearly see-through.
            double previous = -1;
            for (int level : new int[] { 0, 25, 50, 75, 100 }) {
                double a = color(new Theme(accent, Theme.Mode.LIGHT, dark, level).tokens().get("-pp-surface-1")).getOpacity();
                if (previous >= 0) assertTrue(a - previous >= .15, accent + " dark=" + dark + " step to " + level + "%: " + previous + " -> " + a);
                previous = a;
            }
            assertTrue(color(clear.get("-pp-surface-1")).getOpacity() <= (dark ? .32 : .16), "0% is highly transparent");
            // Glass treatment fades out: sheen gone and rim no longer bright white on an opaque panel.
            assertTrue(clear.get("-pp-glass-sheen").startsWith("linear-gradient") && !clear.get("-pp-glass-sheen").contains("0.000) 0%"));
            assertTrue(solid.get("-pp-glass-sheen").contains("rgba(255,255,255,0.000) 0%"), "no sheen on an opaque panel");
            assertTrue(color(clear.get("-pp-border")).getOpacity() > color(solid.get("-pp-border")).getOpacity(), "rim recedes as the panel becomes opaque");
            assertTrue(color(clear.get("-pp-shadow")).getOpacity() < color(solid.get("-pp-shadow")).getOpacity(), "lighter shadows under see-through panels");
        }
        // Light mode: the background behind the content carries a soft, low-saturation pool of the theme colour.
        for (Theme.Accent accent : Theme.Accent.values()) {
            var ambient = new Theme(accent, Theme.Mode.LIGHT, false, 62).lighting().ambient().color();
            assertTrue(ambient.getSaturation() > .08 && ambient.getSaturation() < .22 && ambient.getBrightness() > .9, accent + " ambient " + ambient);
        }
    }

    @Test void darkModeRelightsTheSameWavesInsteadOfDimmingLightMode() {
        for (Theme.Accent accent : Theme.Accent.values()) {
            var light = new Theme(accent, Theme.Mode.LIGHT, false, 62).lighting();
            var dark = new Theme(accent, Theme.Mode.DARK, true, 62).lighting();
            assertNotEquals(light.sky().x(), dark.sky().x(), "the light moves");
            assertTrue(dark.base0().getBrightness() < .2 && light.base0().getBrightness() > .9);
            // Not a brightness scale: the dark base is more saturated and its highlights are coloured, not white.
            assertTrue(dark.base0().getSaturation() > light.base0().getSaturation() * 2);
            assertTrue(dark.sky().color().getSaturation() > .1 && light.sky().color().getSaturation() < .01, "dark light is coloured, light-mode light is white");
            assertTrue(dark.primary().rim().getSaturation() > .1 && light.primary().rim().getSaturation() < .01, "dark crests glow in the theme colour, light ones in white");
            // Restrained: the brightest point of the dark composition (a lit crest) stays a dim, coloured light.
            double brightest = backdrops(dark).stream().mapToDouble(ThemeTest::luminance).max().orElseThrow();
            assertTrue(brightest < .1, accent + " dark highlights stay low-contrast: " + brightest);
            // Navy/charcoal base: every dark theme's base sits close to the shared navy, whatever its accent.
            assertTrue(Math.abs(dark.base0().getBrightness() - .15) < .03, accent + " dark base brightness");
        }
        // Each accent recolours the background itself, not only the controls.
        Set<String> bases = new HashSet<>();
        for (Theme.Accent accent : Theme.Accent.values()) bases.add(Theme.css(new Theme(accent, Theme.Mode.LIGHT, false, 62).lighting().primary().deep()));
        assertEquals(Theme.Accent.values().length, bases.size());
    }

    @Test void everyAccentAndModeHasEnglishAndChineseLabelsAndStableStoredIds() throws Exception {
        for (String bundle : List.of("messages", "messages_en", "messages_zh_CN")) {
            var properties = new Properties();
            try (var stream = getClass().getResourceAsStream("/i18n/" + bundle + ".properties")) {
                properties.load(new StringReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            }
            for (Theme.Accent accent : Theme.Accent.values()) assertTrue(properties.containsKey(accent.key), bundle + " " + accent.key);
            for (Theme.Mode mode : Theme.Mode.values()) assertTrue(properties.containsKey(mode.key), bundle + " " + mode.key);
        }
        assertEquals(List.of("blue", "sage", "peach", "rose", "teal"), Arrays.stream(Theme.Accent.values()).map(accent -> accent.id).toList());
        for (Theme.Accent accent : Theme.Accent.values()) assertEquals(accent, Theme.Accent.parse(accent.id).orElseThrow());
        for (Theme.Mode mode : Theme.Mode.values()) assertEquals(mode, Theme.Mode.parse(mode.id).orElseThrow());
        assertEquals(Theme.Accent.BLUE, Theme.Accent.parse("lavender").orElseThrow(), "the merged Lavender choice opens as Cool Blue");
        assertTrue(Theme.Accent.parse("neon").isEmpty()); assertTrue(Theme.Mode.parse(null).isEmpty());
    }

    @Test void exactlyFiveDistinctFamiliesWithTheCoolThemeGenuinelyBlue() {
        assertEquals(5, Theme.Accent.values().length);
        // Cool Blue's controls and its background waves are all blue, never violet (the earlier Blue/Lavender mix-up).
        var blue = Theme.Accent.BLUE;
        for (Color color : List.of(blue.swatch, blue.lightFill, blue.darkFill)) assertTrue(color.getHue() >= 200 && color.getHue() <= 232, "blue hue " + color.getHue());
        for (boolean dark : new boolean[] { false, true }) {
            var light = new Theme(blue, Theme.Mode.LIGHT, dark, 62).lighting();
            for (Color wave : List.of(light.primary().near(), light.primary().deep(), light.secondary().near(), light.secondary().deep(), light.base1()))
                assertTrue(wave.getHue() >= 200 && wave.getHue() <= 238, "background hue " + wave.getHue() + " dark=" + dark);
            // Only the soft side light takes the lavender companion, as in a blue scene lit from the side.
            double side = light.glow().color().getHue();
            assertTrue(side > 238 && side <= 262, "companion light " + side);
        }
        // Every family, including the new Soft Teal, is at least 30 degrees of hue away from every other one.
        for (Theme.Accent a : Theme.Accent.values()) for (Theme.Accent b : Theme.Accent.values()) if (a != b) {
            double gap = Math.abs(a.swatch.getHue() - b.swatch.getHue()); gap = Math.min(gap, 360 - gap);
            assertTrue(gap >= 30, a + " vs " + b + ": " + gap);
            double primary = Math.abs(a.hue - b.hue);
            assertTrue(Math.min(primary, 360 - primary) >= 30, a + " vs " + b + " primary hue");
        }
        // Soft and low-saturation: swatches and background tints stay pastel.
        for (Theme.Accent accent : Theme.Accent.values()) {
            assertTrue(accent.swatch.getSaturation() < .5, accent + " swatch saturation " + accent.swatch.getSaturation());
            assertTrue(new Theme(accent, Theme.Mode.LIGHT, false, 62).lighting().secondary().deep().getSaturation() < .3, accent + " light wave saturation");
        }
    }

    static List<Theme> backgroundThemes() {
        List<Theme> themes = new ArrayList<>();
        for (boolean dark : new boolean[] { false, true }) {
            Theme.Mode mode = dark ? Theme.Mode.DARK : Theme.Mode.LIGHT;
            for (Theme.Accent accent : Theme.Accent.values()) themes.add(new Theme(accent, mode, dark, 62));
            for (int hue : new int[] { 0, 55, 110, 265, 320 }) themes.add(Theme.custom(hue, mode, dark, 62));
        }
        return themes;
    }

    @Test void backgroundIsOneSmoothFieldWithoutEdgesSeamsOrOutlines() {
        for (Theme theme : backgroundThemes()) {
            String name = (theme.custom() ? "custom " + theme.customHue() : theme.accent()) + (theme.dark() ? " dark" : " light");
            var light = theme.lighting();
            // The field of a 1120 x 760 window at the application's half size, and the same window at full size.
            float[] half = WaveRenderer.field(light, 560, 380), full = WaveRenderer.field(light, 1120, 760);
            double halfStep = steepest(half, 560, 380), fullStep = steepest(full, 1120, 760);
            // A drawn edge, polygon seam or outline stroke jumps by tens of levels between neighbours, and by the same
            // amount at any resolution. Every transition here is a gradient a few levels per pixel wide, and it halves
            // when the resolution doubles: the crests are soft light, not strokes.
            assertTrue(halfStep <= 8.5, name + " steepest step at half size " + halfStep);
            assertTrue(fullStep <= .6 * halfStep, name + " steps do not soften with resolution: " + halfStep + " -> " + fullStep);
            // The quantised image moves by at most a few levels too (including the sub-step dither against banding).
            int[] pixels = WaveRenderer.pack(full, 1120, 760);
            int quantised = 0;
            for (int y = 0; y + 1 < 760; y++) for (int x = 0; x + 1 < 1120; x++)
                quantised = Math.max(quantised, Math.max(step(pixels[y * 1120 + x], pixels[y * 1120 + x + 1]), step(pixels[y * 1120 + x], pixels[(y + 1) * 1120 + x])));
            assertTrue(quantised <= 6, name + " steepest quantised step " + quantised);
            float[] blurred = WaveRenderer.blur(WaveRenderer.field(light, 140, 95), 140, 95, 8);
            for (float channel : blurred) assertTrue(channel >= 0 && channel <= 1, "blur stays in range at the borders");
        }
    }
    /** Largest change of any channel between horizontal or vertical neighbours, in 8-bit levels (before dithering). */
    private static double steepest(float[] rgb, int width, int height) {
        double steepest = 0;
        for (int y = 0; y + 1 < height; y++) for (int x = 0; x + 1 < width; x++) for (int c = 0; c < 3; c++) {
            int i = (y * width + x) * 3 + c;
            steepest = Math.max(steepest, Math.max(Math.abs(rgb[i] - rgb[i + 3]), Math.abs(rgb[i] - rgb[i + width * 3])));
        }
        return steepest * 255;
    }
    private static int step(int a, int b) {
        int max = 0;
        for (int shift = 0; shift <= 16; shift += 8) max = Math.max(max, Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)));
        return max;
    }

    @Test void wavesAreRecognisableInTheLowerPartAndTheUpperAreaStaysCalm() {
        int width = 800, height = 500;
        for (Theme theme : backgroundThemes()) {
            String name = (theme.custom() ? "custom " + theme.customHue() : theme.accent()) + (theme.dark() ? " dark" : " light");
            float[] field = WaveRenderer.field(theme.lighting(), width, height);
            // Down a column on the right, each crest shows as a lit ridge: a local brightness peak with darker tones on
            // both sides, at the Bézier crest of the primary and of the secondary wave.
            int x = (int) (width * .82);
            double[] column = new double[height];
            for (int y = 0; y < height; y++) column[y] = luminance(pixel(field, width, x, y));
            for (WaveRenderer.Surface surface : List.of(WaveRenderer.PRIMARY, WaveRenderer.SECONDARY)) {
                int crest = (int) Math.round(WaveRenderer.crest(surface.crest(), width, height)[x]);
                int peak = crest - 6;
                for (int y = crest - 6; y <= crest + 12; y++) if (column[y] > column[peak]) peak = y;
                double above = column[Math.max(0, peak - 30)], below = column[Math.min(height - 1, peak + 30)];
                // Relative lift of the lit crest over the tones on both sides (measured: at least 7% light, 43% dark).
                double ridge = (column[peak] - Math.max(above, below)) / column[peak];
                assertTrue(ridge > (theme.dark() ? .2 : .03), name + " crest at " + crest + " is not a visible ridge: " + ridge);
            }
            // The upper third has only broad gradients: its ridge-like detail is a small fraction of the wave area's.
            double upper = detail(field, width, height, 6, height / 3), lower = detail(field, width, height, height / 2, height - 6);
            assertTrue(upper < .25 * lower, name + " upper detail " + upper + " vs lower " + lower);
        }
    }
    private static Color pixel(float[] field, int width, int x, int y) {
        int i = (y * width + x) * 3; return Color.color(field[i], field[i + 1], field[i + 2]);
    }
    /** 98th percentile of the vertical second difference of luminance (6 px apart): high on crests, near zero on gradients. */
    private static double detail(float[] field, int width, int height, int from, int to) {
        List<Double> values = new ArrayList<>();
        for (int y = Math.max(6, from); y < Math.min(height - 6, to); y += 2) for (int x = 0; x < width; x += 4)
            values.add(Math.abs(luminance(pixel(field, width, x, y + 6)) - 2 * luminance(pixel(field, width, x, y)) + luminance(pixel(field, width, x, y - 6))));
        Collections.sort(values);
        return values.get((int) (values.size() * .98));
    }

    @Test void theBackgroundIsGeneratedInCodeWithoutAnyImageAsset() throws Exception {
        // No reference picture or wallpaper is loaded, embedded or decoded by the background: the renderer, its
        // palette and the backdrop node only compute pixels. (Photographs and the logo are separate and unaffected.)
        for (String source : List.of("WaveRenderer", "ThemeBackdrop", "Theme")) {
            String code = Files.readString(Path.of("src/main/java/com/photopilot/ui/gui/" + source + ".java"));
            for (String forbidden : List.of("getResource", "new Image(", "ImageIO", "Base64.getDecoder", ".png", ".jpg", ".jpeg", ".webp"))
                assertFalse(code.contains(forbidden), source + " uses " + forbidden);
        }
        // Rendering needs nothing but the theme: any size, any palette, deterministic.
        var light = Theme.custom(200, Theme.Mode.LIGHT, false, 62).lighting();
        assertArrayEquals(WaveRenderer.field(light, 64, 40), WaveRenderer.field(light, 64, 40));
    }

    /**
     * The colours of the wave composition that a surface can sit over: the darkest and lightest points and the
     * extremes of each channel, taken from the rendered field itself (crest lights and glows included).
     */
    static List<Color> backdrops(Theme.Lighting l) {
        int width = 400, height = 250;
        float[] field = WaveRenderer.field(l, width, height);
        int[] picks = new int[8];
        double[] best = { 9, -9, 9, -9, 9, -9, 9, -9 };
        for (int i = 0; i < width * height; i++) {
            double[] value = { luminance(Color.color(field[i * 3], field[i * 3 + 1], field[i * 3 + 2])), field[i * 3], field[i * 3 + 1], field[i * 3 + 2] };
            for (int k = 0; k < 4; k++) {
                if (value[k] < best[k * 2]) { best[k * 2] = value[k]; picks[k * 2] = i; }
                if (value[k] > best[k * 2 + 1]) { best[k * 2 + 1] = value[k]; picks[k * 2 + 1] = i; }
            }
        }
        List<Color> colours = new ArrayList<>();
        for (int i : picks) colours.add(Color.color(field[i * 3], field[i * 3 + 1], field[i * 3 + 2]));
        return colours;
    }
    static Color color(String css) {
        var m = RGBA.matcher(css);
        assertTrue(m.matches(), css);
        return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Double.parseDouble(m.group(4)));
    }
    /** Source-over compositing in sRGB, as JavaFX renders translucent fills. */
    static Color over(Color top, Color bottom) {
        double a = top.getOpacity();
        return Color.color(top.getRed() * a + bottom.getRed() * (1 - a), top.getGreen() * a + bottom.getGreen() * (1 - a), top.getBlue() * a + bottom.getBlue() * (1 - a));
    }
    static double contrast(Color a, Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    static double luminance(Color c) { return .2126 * linear(c.getRed()) + .7152 * linear(c.getGreen()) + .0722 * linear(c.getBlue()); }
    private static double linear(double v) { return v <= .03928 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
}
