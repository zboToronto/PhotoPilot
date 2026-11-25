package com.photopilot.ui.gui;

import com.photopilot.ui.gui.DropdownContrast.*;
import java.util.*;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Final GUI stabilization (2026-11-24): readable text in the open dropdown list at every Transparency, deterministic and
 * without a desktop. The list's text sits on its translucent surface over the frosted window content; the model composites
 * every layer (frost range, drop shadow, -pp-surface-2, glass sheen, option tint) as JavaFX paints them, over the wave
 * background's full per-channel range, for every preset and every custom hue in Light, Dark and Follow System at the
 * Transparency steps users can choose. DropdownContrastGuiTest checks the same against real rendered pixels.
 */
class DropdownContrastTest {
    static final int[] OPACITIES = { 0, 1, 10, 25, 40, 50, 61, 62, 63, 75, 90, 98, 99, 100 };

    /** Every preset and a custom hue every 5 degrees, in Light, Dark and Follow System (either result), at every step. */
    static List<Theme> themes() {
        var all = new ArrayList<Theme>();
        for (int opacity : OPACITIES) for (var mode : List.of(Map.entry(Theme.Mode.LIGHT, false), Map.entry(Theme.Mode.DARK, true),
                Map.entry(Theme.Mode.SYSTEM, false), Map.entry(Theme.Mode.SYSTEM, true))) {
            for (Theme.Accent accent : Theme.Accent.values()) all.add(new Theme(accent, mode.getKey(), mode.getValue(), opacity));
            for (int hue = 0; hue < 360; hue += 5) all.add(Theme.custom(hue, mode.getKey(), mode.getValue(), opacity));
        }
        return all;
    }
    private static final Map<Theme.Lighting, Color[]> RANGES = new HashMap<>();
    /** Per-channel darkest and lightest of the rendered wave background (the frost under the list lies within it). */
    static Color[] backdrop(Theme theme) {
        return RANGES.computeIfAbsent(theme.lighting(), lighting -> {
            double[] low = { 1, 1, 1 }, high = { 0, 0, 0 };
            for (Color c : ThemeTest.backdrops(lighting)) {
                double[] v = { c.getRed(), c.getGreen(), c.getBlue() };
                for (int i = 0; i < 3; i++) { low[i] = Math.min(low[i], v[i]); high[i] = Math.max(high[i], v[i]); }
            }
            return new Color[] { Color.color(low[0], low[1], low[2]), Color.color(high[0], high[1], high[2]) };
        });
    }
    /** The weakest contrast of each role's colour over its option states. */
    static double weakest(Palette palette, Map<Role, Color> text, double alpha, Color[] range) { return weakest(palette, text, alpha, range, EnumSet.allOf(Role.class)); }
    static double weakest(Palette palette, Map<Role, Color> text, double alpha, Color[] range, Set<Role> roles) {
        double weakest = Double.MAX_VALUE;
        for (Role role : roles) for (State state : role.states)
            weakest = Math.min(weakest, DropdownContrast.weakest(text.get(role), DropdownContrast.range(palette, alpha, state, range[0], range[1])));
        return weakest;
    }
    static String name(Theme t) { return (t.custom() ? "custom " + t.customHue() : t.accent().id) + " " + t.mode().id + (t.dark() ? "/dark" : "/light") + " " + t.opacity() + "%"; }

    @Test void everyThemeTransparencyAndOptionStateReadsAtLeast45() {
        double before = Double.MAX_VALUE, after = Double.MAX_VALUE, maxMove = 0, warnBefore = Double.MAX_VALUE; String worstBefore = "", worstAfter = "", furthest = "";
        int themes = 0, adjusted = 0, failingBefore = 0, protectedLists = 0, warnFailing = 0;
        Map<Integer, Integer> failingByOpacity = new TreeMap<>();
        var themeRoles = EnumSet.of(Role.TEXT, Role.MUTED, Role.ACCENT);
        for (Theme theme : themes()) {
            themes++;
            var range = backdrop(theme);
            var palette = Palette.of(theme);
            var result = DropdownContrast.resolve(theme, range[0], range[1]);
            // Before: the theme's own text colours (option, secondary, selected); the F09 amber line is counted on its own.
            double original = weakest(palette, palette.text(), palette.surface().getOpacity(), range, themeRoles), fixed = weakest(palette, result.text(), result.surfaceAlpha(), range);
            double warn = weakest(palette, palette.text(), palette.surface().getOpacity(), range, EnumSet.of(Role.WARN));
            warnBefore = Math.min(warnBefore, warn); if (warn < DropdownContrast.TARGET) warnFailing++;
            if (original < before) { before = original; worstBefore = name(theme); }
            if (fixed < after) { after = fixed; worstAfter = name(theme); }
            if (original < DropdownContrast.TARGET) { failingBefore++; failingByOpacity.merge(theme.opacity(), 1, Integer::sum); }
            assertTrue(fixed >= DropdownContrast.TARGET, name(theme) + ": weakest option text " + fixed + " (" + result.style() + ")");
            // Text colours are adjusted, the list surface is not: the wave background never needs the local protection.
            assertFalse(result.protectedSurface(), name(theme) + ": surface protection on the theme's own background");
            if (!result.style().isEmpty()) adjusted++;
            for (Role role : Role.values()) {
                double move = ThemeTest.luminance(palette.text().get(role)) - ThemeTest.luminance(result.text(role));
                if (Math.abs(move) > maxMove) { maxMove = Math.abs(move); furthest = name(theme) + " " + role; }
            }
            if (result.protectedSurface()) protectedLists++;
        }
        System.out.printf(Locale.ROOT, "Dropdown contrast model: %d themes; theme colours alone: weakest %.2f (%s), %d below 4.5 %s; F09 estimate amber alone: weakest %.2f, %d below 4.5; "
            + "painted: weakest %.2f (%s); %d with an adjusted colour, largest luminance move %.3f (%s), %d with surface protection%n",
            themes, before, worstBefore, failingBefore, failingByOpacity, warnBefore, warnFailing, after, worstAfter, adjusted, maxMove, furthest, protectedLists);
        assertTrue(failingBefore > 0, "the model reproduces the reported failures with the theme colours alone");
    }

    @Test void theReportedFailingConfigurationsNowPass() {
        // Stage2OptRe2 remediation, 2026-11-24: Cool Blue at 0 % and dark custom colours at 40 % fell below 4.5:1 (4.01-4.49 on
        // the worst background sample). The same configurations, measured over the background's full range.
        List<Theme> reported = new ArrayList<>(List.of(new Theme(Theme.Accent.BLUE, Theme.Mode.LIGHT, false, 0), new Theme(Theme.Accent.BLUE, Theme.Mode.DARK, true, 0)));
        for (int hue = 0; hue < 360; hue += 15) reported.add(Theme.custom(hue, Theme.Mode.DARK, true, 40));
        int reproduced = 0;
        for (Theme theme : reported) {
            var range = backdrop(theme); var palette = Palette.of(theme); var result = DropdownContrast.resolve(theme, range[0], range[1]);
            double selectedBefore = DropdownContrast.weakest(palette.text().get(Role.ACCENT), DropdownContrast.range(palette, palette.surface().getOpacity(), State.SELECTED, range[0], range[1]));
            double selectedAfter = DropdownContrast.weakest(result.text(Role.ACCENT), DropdownContrast.range(palette, result.surfaceAlpha(), State.SELECTED, range[0], range[1]));
            if (selectedBefore < DropdownContrast.TARGET) reproduced++;
            assertTrue(selectedAfter >= DropdownContrast.TARGET, name(theme) + ": selected option " + selectedBefore + " -> " + selectedAfter);
            System.out.printf(Locale.ROOT, "  %-28s selected option text %.2f -> %.2f %s%n", name(theme), selectedBefore, selectedAfter, result.style());
        }
        assertEquals(reported.size(), reproduced, "Cool Blue 0 % and every dark custom colour at 40 % reproduce below 4.5:1");
        var blue = new Theme(Theme.Accent.BLUE, Theme.Mode.LIGHT, false, 0); var range = backdrop(blue); var palette = Palette.of(blue);
        assertTrue(DropdownContrast.weakest(palette.text().get(Role.ACCENT), DropdownContrast.range(palette, palette.surface().getOpacity(), State.SELECTED, range[0], range[1])) < 4.5,
            "Cool Blue at 0 %: the selected option text was below 4.5:1");
    }

    @Test void configurationsThatAlreadyReadKeepTheirExactThemeColours() {
        int kept = 0;
        for (Theme theme : themes()) {
            var range = backdrop(theme); var palette = Palette.of(theme); var result = DropdownContrast.resolve(theme, range[0], range[1]);
            for (Role role : Role.values()) {
                boolean reads = role.states.stream().allMatch(state -> DropdownContrast.weakest(palette.text().get(role),
                    DropdownContrast.range(palette, palette.surface().getOpacity(), state, range[0], range[1])) >= DropdownContrast.TARGET);
                if (reads) { assertEquals(palette.text().get(role), result.text(role), name(theme) + " " + role + " keeps the theme's colour"); kept++; }
                else assertFalse(result.style().isEmpty(), name(theme) + " " + role);
            }
            assertEquals(palette.surface().getOpacity(), result.surfaceAlpha(), 1e-9, name(theme) + ": the list keeps the Transparency setting");
        }
        // The default appearance (Cool Blue, Follow System on a light or unknown system, 62 %) and every opaque list keep the
        // theme's option, secondary and selected text exactly; an opaque list is checked over any backdrop, as DropdownSkin
        // does. (On a dark system the glass sheen over the top rows can call for a lighter secondary text; the F09 amber
        // estimate line is F09's own colour, below 4.5:1 on these surfaces, and is adjusted wherever it falls short.)
        var standard = new Theme(Theme.Accent.BLUE, Theme.Mode.SYSTEM, false, Theme.DEFAULT_OPACITY); var light = backdrop(standard);
        assertThemeText(standard, DropdownContrast.resolve(standard, light[0], light[1]), "default appearance");
        var darkStandard = new Theme(Theme.Accent.BLUE, Theme.Mode.SYSTEM, true, Theme.DEFAULT_OPACITY); var dark = backdrop(darkStandard);
        System.out.println("Default appearance on a dark system: '" + DropdownContrast.resolve(darkStandard, dark[0], dark[1]).style() + "'");
        // Opaque lists: the theme's colours, except where a theme colour sits at 4.5:1 within paint precision (dark secondary
        // text on a selected row), which moves by at most two 8-bit levels: no visible change.
        int nudged = 0; double largest = 0;
        for (Theme theme : themes()) if (theme.opacity() >= DropdownSkin.OPAQUE) {
            var opaque = DropdownContrast.resolve(theme, DropdownContrast.UNKNOWN[0], DropdownContrast.UNKNOWN[1]); var palette = Palette.of(theme);
            assertFalse(opaque.protectedSurface(), name(theme) + ": opaque list");
            for (Role role : List.of(Role.TEXT, Role.MUTED, Role.ACCENT)) {
                Color from = palette.text().get(role), to = opaque.text(role);
                double delta = Math.max(Math.abs(from.getRed() - to.getRed()), Math.max(Math.abs(from.getGreen() - to.getGreen()), Math.abs(from.getBlue() - to.getBlue())));
                assertTrue(delta <= 2.5 / 255, name(theme) + ": opaque list keeps the theme's " + role + " (" + from + " -> " + to + ")");
                if (delta > 0) { nudged++; largest = Math.max(largest, delta * 255); }
            }
        }
        System.out.printf(Locale.ROOT, "Opaque lists: %d theme colours moved by paint precision, largest %.0f level(s)%n", nudged, largest);
        assertTrue(kept > 0);
    }
    private static void assertThemeText(Theme theme, Result result, String where) {
        var palette = Palette.of(theme);
        for (Role role : List.of(Role.TEXT, Role.MUTED, Role.ACCENT)) assertEquals(palette.text().get(role), result.text(role), where + " keeps the theme's " + role);
    }

    @Test void anAdjustedColourMovesOnlyAsFarAsNeededAndKeepsItsHue() {
        int checked = 0;
        for (Theme theme : themes()) {
            var range = backdrop(theme); var palette = Palette.of(theme); var result = DropdownContrast.resolve(theme, range[0], range[1]);
            for (Role role : Role.values()) {
                Color from = palette.text().get(role), to = result.text(role);
                if (from.equals(to)) continue;
                checked++;
                // On the line from the theme's colour towards black (Light) or white (Dark): the hue is the theme's.
                double gap = Math.abs(from.getHue() - to.getHue()); gap = Math.min(gap, 360 - gap);
                if (from.getSaturation() > .05 && to.getSaturation() > .05) assertTrue(gap <= 6, name(theme) + " " + role + " hue " + from.getHue() + " -> " + to.getHue());
                assertTrue(theme.dark() ? ThemeTest.luminance(to) > ThemeTest.luminance(from) : ThemeTest.luminance(to) < ThemeTest.luminance(from), name(theme) + " " + role + " direction");
                // Minimal: a colour a little closer to the theme's (2 % of the way back) no longer reaches the target.
                double t = fraction(from, to, palette.strongest());
                Color less = DropdownContrast.eight(from.interpolate(palette.strongest(), Math.max(0, t - .02)));
                boolean lessReads = role.states.stream().allMatch(state -> DropdownContrast.weakest(less, DropdownContrast.range(palette, result.surfaceAlpha(), state, range[0], range[1])) >= DropdownContrast.TARGET);
                assertFalse(lessReads && t > .03, name(theme) + " " + role + ": moved " + t + " but " + (t - .02) + " already reads");
            }
        }
        assertTrue(checked > 0, "some colours are adjusted");
    }
    /** How far along from -> strongest the colour lies (0 = unchanged, 1 = black or white). */
    private static double fraction(Color from, Color to, Color strongest) {
        double best = 0, distance = Double.MAX_VALUE;
        for (int i = 0; i <= 1000; i++) { double t = i / 1000.0; Color c = from.interpolate(strongest, t);
            double d = Math.abs(c.getRed() - to.getRed()) + Math.abs(c.getGreen() - to.getGreen()) + Math.abs(c.getBlue() - to.getBlue());
            if (d < distance) { distance = d; best = t; } }
        return best;
    }

    @Test void anUnusuallyDarkOrBrightBackdropIsHandledOnTheOpenListOnly() {
        // What a list can open over besides the background: a dark photograph, a bright one, saturated colours, or anything.
        List<Color[]> backdrops = new ArrayList<>();
        for (String c : List.of("#000000", "#202020", "#7f7f7f", "#ffffff", "#ff0000", "#00a000", "#0000ff", "#ffd000", "#4a68cc"))
            backdrops.add(new Color[] { Color.web(c), Color.web(c) });
        backdrops.add(new Color[] { Color.BLACK, Color.WHITE });
        int protectedLists = 0;
        for (Theme theme : themes()) {
            if (theme.custom() && theme.customHue() % 45 != 0) continue;
            for (Color[] range : backdrops) {
                var palette = Palette.of(theme); var result = DropdownContrast.resolve(theme, range[0], range[1]);
                String where = name(theme) + " over " + range[0] + ".." + range[1];
                assertTrue(weakest(palette, result.text(), result.surfaceAlpha(), range) >= DropdownContrast.TARGET, where + ": " + result.style());
                assertTrue(result.surfaceAlpha() >= palette.surface().getOpacity() - 1e-9 && result.surfaceAlpha() <= 1, where);
                if (result.protectedSurface()) {
                    protectedLists++;
                    // Minimal: 0.002 less opacity than the protection cannot be made readable by text colour alone.
                    assertTrue(DropdownContrast.attempt(palette, result.surfaceAlpha() - .002, range[0], range[1]).isEmpty(), where + ": protection " + result.surfaceAlpha());
                    assertTrue(result.style().contains("-pp-surface-2: "), where);
                }
            }
        }
        assertTrue(protectedLists > 0, "an almost clear list over black, white or mixed content needs its surface protected");
        System.out.println("Extreme backdrops: " + protectedLists + " lists protected");
    }

    @Test void theClosedDropdownAndDisabledStatesKeepTheirEstablishedAppearance() throws Exception {
        String css = ThemeTest.css();
        assertTrue(css.contains(".combo-box > .list-cell { -fx-background-color: transparent; -fx-text-fill: -pp-text; }"), "closed control text");
        assertTrue(css.contains("-fx-background-color: -pp-input; -fx-background-insets: 0; -fx-background-radius: 8;"), "closed control surface");
        // Disabled controls and options keep JavaFX's 40 % opacity (modena); PhotoPilot adds no disabled override of its own.
        assertFalse(css.matches("(?s).*\\.combo-box[^{]*:disabled.*") || css.matches("(?s).*\\.list-cell[^{]*:disabled.*"), "no PhotoPilot disabled dropdown rule");
        for (Theme theme : themes()) {
            var t = theme.tokens(); var range = backdrop(theme);
            Color text = ThemeTest.color(t.get("-pp-text")), input = ThemeTest.color(t.get("-pp-input"));
            for (Color under : List.of(range[0], range[1], ThemeTest.over(ThemeTest.color(t.get("-pp-surface-1")), range[0]), ThemeTest.over(ThemeTest.color(t.get("-pp-surface-1")), range[1]))) {
                Color behind = ThemeTest.over(input, under);
                double enabled = ThemeTest.contrast(text, behind);
                assertTrue(enabled >= DropdownContrast.TARGET, name(theme) + ": closed dropdown text " + enabled);
                // Disabled: the whole control at 40 % over what is under it, clearly fainter than the enabled text.
                Color disabledText = ThemeTest.over(new Color(text.getRed(), text.getGreen(), text.getBlue(), .4), ThemeTest.over(new Color(input.getRed(), input.getGreen(), input.getBlue(), input.getOpacity() * .4), under));
                Color disabledBehind = ThemeTest.over(new Color(input.getRed(), input.getGreen(), input.getBlue(), input.getOpacity() * .4), under);
                assertTrue(ThemeTest.contrast(disabledText, disabledBehind) < enabled - 1, name(theme) + ": disabled is distinguishable from enabled");
            }
        }
    }

    @Test void theModelPaintsTheLayersPhotopilotCssPaints() throws Exception {
        String css = ThemeTest.css();
        assertTrue(css.contains("-fx-background-color: -pp-surface-2, -pp-glass-sheen; -fx-background-insets: 0; -fx-background-radius: 12;"), "list surface and sheen");
        assertTrue(css.contains("-fx-effect: dropshadow(gaussian, -pp-shadow, 22, 0.3, 0, 7);"), "list shadow");
        assertTrue(css.contains(".combo-box-popup .list-cell {\n    -fx-background-color: transparent;") || css.contains(".combo-box-popup .list-cell {\r\n    -fx-background-color: transparent;"), "plain option");
        assertTrue(css.contains("-fx-padding: 6 10; -fx-text-fill: -pp-text;"), "option text");
        assertTrue(css.contains(".combo-box-popup .list-cell:filled:hover { -fx-background-color: -pp-hover; }"), "hovered option");
        assertTrue(css.contains(".combo-box-popup .list-cell:filled:selected, .combo-box-popup .list-cell:filled:selected:hover {"), "selected and selected+hovered option");
        assertTrue(css.contains("-fx-background-color: -pp-selected-soft; -fx-text-fill: -pp-accent-text;"), "selected colours");
        // Keyboard focus has no look of its own in the list (it moves the selection), so it is the plain or selected state.
        assertFalse(css.contains(".combo-box-popup .list-cell:focused"), "no separate focused option style");
        assertTrue(css.contains(".dropdown-empty { -fx-text-fill: -pp-text-muted;"), "empty list message");
        assertTrue(css.contains(".env-candidate-detail { -fx-font-size: 12px; -fx-text-fill: -pp-text-muted; }"), "secondary text in custom cells");
        // Every text colour photopilot.css gives inside the open list is one DropdownContrast checks.
        var roles = Arrays.stream(Role.values()).map(role -> role.token).collect(java.util.stream.Collectors.toSet());
        var fills = java.util.regex.Pattern.compile("\\.combo-box-popup[^{]*\\{[^}]*-fx-text-fill:\\s*(-[a-z-]+)").matcher(css);
        int rules = 0;
        while (fills.find()) { rules++; assertTrue(roles.contains(fills.group(1)), "open-list text colour " + fills.group(1) + " is checked"); }
        assertTrue(rules >= 3, "open-list text rules found: " + rules);
        assertTrue(css.contains(".combo-box-popup .list-cell.env-candidate-cell:estimated .env-candidate-detail { -fx-text-fill: -env-warn; }"), "F09 estimate line");
        // The estimate colour's two values are the ones the F09 page's ladder picks (dark text in Light, light text in Dark).
        assertTrue(css.contains("-env-warn: ladder(-pp-text, " + rgba(DropdownContrast.WARN_LIGHT) + " 49%, " + rgba(DropdownContrast.WARN_DARK) + " 50%);"), "-env-warn ladder");
    }
    private static String rgba(Color c) {
        return "rgba(" + Math.round(c.getRed() * 255) + "," + Math.round(c.getGreen() * 255) + "," + Math.round(c.getBlue() * 255) + ",1)";
    }

    /**
     * An oracle independent of DropdownContrast.range(): the frost DropdownSkin would draw for the bare background of an
     * 800 x 500 window (quarter-size box average, then the same Gaussian blur), every pixel composited exactly through the
     * list's layers with the shadow and the sheen each at none, half and full strength, in every option state.
     */
    @Test void everyPixelOfTheActualFrostReadsWithThePaintedColours() {
        int width = 800, height = 500, w = width / 4, h = height / 4;
        double themeWeakest = Double.MAX_VALUE, paintedWeakest = Double.MAX_VALUE; String where = "";
        List<Theme> sample = new ArrayList<>();
        for (int opacity : new int[] { 0, 25, 40, 62, 98 }) for (boolean dark : new boolean[] { false, true }) {
            var mode = dark ? Theme.Mode.DARK : Theme.Mode.LIGHT;
            for (Theme.Accent accent : Theme.Accent.values()) sample.add(new Theme(accent, mode, dark, opacity));
            for (int hue : new int[] { 0, 60, 120, 240, 300 }) sample.add(Theme.custom(hue, mode, dark, opacity));
        }
        Map<Theme.Lighting, float[]> frosts = new HashMap<>();
        for (Theme theme : sample) {
            float[] frost = frosts.computeIfAbsent(theme.lighting(), lighting -> {
                float[] field = WaveRenderer.field(lighting, width, height), small = new float[w * h * 3];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 3; c++) {
                    float sum = 0;
                    for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 4; dx++) sum += field[((y * 4 + dy) * width + x * 4 + dx) * 3 + c];
                    small[(y * w + x) * 3 + c] = sum / 16;
                }
                return WaveRenderer.blur(small, w, h, 10);
            });
            var range = backdrop(theme); var palette = Palette.of(theme); var painted = DropdownContrast.resolve(theme, range[0], range[1]);
            Map<State, double[]> lum = new EnumMap<>(State.class);
            for (State state : State.values()) lum.put(state, new double[] { 9, -9 });
            for (int i = 0; i < w * h; i++) {
                Color f = Color.color(frost[i * 3], frost[i * 3 + 1], frost[i * 3 + 2]);
                for (double s : new double[] { 0, .5, 1 }) for (double g : new double[] { 0, .5, 1 }) {
                    Color plain = ThemeTest.over(withAlpha(palette.sheen(), g), ThemeTest.over(painted.surface(), ThemeTest.over(withAlpha(palette.shadow(), s), f)));
                    for (State state : State.values()) {
                        Color bg = state == State.HOVER ? ThemeTest.over(palette.hover(), plain) : state == State.SELECTED ? ThemeTest.over(palette.selected(), plain) : plain;
                        double l = ThemeTest.luminance(bg); var mm = lum.get(state);
                        mm[0] = Math.min(mm[0], l); mm[1] = Math.max(mm[1], l);
                    }
                }
            }
            for (Role role : Role.values()) for (State state : role.states) {
                var mm = lum.get(state);
                double after = exact(painted.text(role), mm), before = exact(palette.text().get(role), mm);
                if (after < paintedWeakest) { paintedWeakest = after; where = name(theme) + " " + role + " " + state; }
                if (role != Role.WARN) themeWeakest = Math.min(themeWeakest, before);
                assertTrue(after >= DropdownContrast.TARGET, name(theme) + " " + role + " " + state + ": " + after + " on the actual frost");
            }
        }
        System.out.printf(Locale.ROOT, "Actual frost of the background (%d themes): theme colours weakest %.2f, painted weakest %.2f (%s)%n", sample.size(), themeWeakest, paintedWeakest, where);
    }
    /** Weakest contrast over attained background luminances between low and high (the text never lies inside them here). */
    private static double exact(Color text, double[] lowHigh) {
        double l = ThemeTest.luminance(text);
        assertTrue(l < lowHigh[0] || l > lowHigh[1], "text luminance " + l + " lies outside the backgrounds " + lowHigh[0] + "-" + lowHigh[1]);
        return ThemeTest.contrast(text, l < lowHigh[0] ? gray(lowHigh[0]) : gray(lowHigh[1]));
    }
    private static Color gray(double luminance) {
        double v = luminance <= .03928 / 12.92 ? luminance * 12.92 : 1.055 * Math.pow(luminance, 1 / 2.4) - .055;
        return Color.color(v, v, v);
    }
    private static Color withAlpha(Color c, double alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), c.getOpacity() * alpha); }
}
