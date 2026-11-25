package com.photopilot.ui.gui;

import java.util.*;
import java.util.regex.Pattern;
import javafx.scene.paint.Color;

/**
 * Readable text in the open dropdown list at every Transparency.
 * <p>What an option's text is painted on, from the bottom up: the frosted backdrop ({@link DropdownSkin}: the window
 * content under the list, blurred; opaque), the list's drop shadow showing through its translucent surface, the list
 * surface ({@code -pp-surface-2}), the glass sheen along its top ({@code -pp-glass-sheen}) and the option's own tint
 * ({@code -pp-hover}, {@code -pp-selected-soft}; photopilot.css). Each layer is composited source-over in sRGB, as JavaFX
 * paints translucent fills, so the background of the text is never taken to be a translucent token on its own. The frost
 * depends on what lies under the list, so the check takes its range per channel (darkest and lightest): compositing and
 * luminance only grow with each channel, so the background luminance of every option state lies between two composites,
 * the darker with the shadow at its full strength and the lighter with the sheen at its full strength, and the weakest
 * contrast of a text colour is at the end of that range nearest to it (WCAG 2 relative luminance and contrast ratio). Every
 * bound is widened by one 8-bit level, so what is checked still holds once JavaFX has painted it. An opaque list (no frost)
 * is checked over {@link #UNKNOWN}: whatever lies under it.
 * <p>The text colours are every {@code -fx-text-fill} photopilot.css gives inside the open list ({@link Role}): option text,
 * secondary text, selected option text and the F09 estimate line.
 * <p>{@link #resolve}: a text colour that has {@link #TARGET} throughout keeps the theme's value exactly. One that does not
 * moves towards black (Light) or white (Dark) just far enough. Only when even black or white cannot reach the target (an
 * almost clear list over an unusually dark or bright backdrop, such as a photograph) does the list's own surface become as
 * much more opaque as that needs, for this open list only. The theme's tokens, the Transparency setting and every other
 * surface are never changed; the result is applied as looked-up colours on the open list ({@link Result#style()}).
 */
final class DropdownContrast {
    /** WCAG AA for normal-size text. */
    static final double TARGET = 4.5;
    private static final Pattern RGBA = Pattern.compile("rgba\\((\\d+),(\\d+),(\\d+),([0-9.]+)\\)");
    private DropdownContrast() { }

    /** Option states as photopilot.css paints them: plain, :hover (-pp-hover), :selected and :selected:hover (-pp-selected-soft). */
    enum State { PLAIN, HOVER, SELECTED }

    /** The text colours of the open list and the option states each appears in. */
    enum Role {
        /** Option text (.combo-box-popup .list-cell), also on a hovered option and inside a selected custom cell. */
        TEXT("-pp-text", EnumSet.allOf(State.class)),
        /** Secondary text in custom cells and the empty-list message. */
        MUTED("-pp-text-muted", EnumSet.allOf(State.class)),
        /** Selected option text (:selected and :selected:hover). */
        ACCENT("-pp-accent-text", EnumSet.of(State.SELECTED)),
        /**
         * The amber second line of an estimated place candidate (F09 location list, :estimated). Not a theme token: the
         * F09 page defines it in photopilot.css (.env-page -env-warn, a ladder on -pp-text), see {@link #WARN_LIGHT}.
         */
        WARN("-env-warn", EnumSet.allOf(State.class));
        final String token;
        final Set<State> states;
        Role(String token, Set<State> states) { this.token = token; this.states = states; }
    }
    /** The two values of photopilot.css's -env-warn ladder (dark text in Light, light text in Dark); a test keeps them equal. */
    static final Color WARN_LIGHT = Color.rgb(166, 98, 0), WARN_DARK = Color.rgb(240, 178, 84);
    /** A backdrop that may be anything: used for an opaque list, whose frost is not drawn. */
    static final Color[] UNKNOWN = { Color.BLACK, Color.WHITE };
    /**
     * Paint precision: JavaFX composites the list in 8-bit channels and opacities, so every bound is widened by one level
     * (1/255) of channel and of surface opacity, and a colour checked here keeps the target when it is painted.
     */
    private static final double LEVEL = 1 / 255.0;

    /** The theme's colours of the open list, exactly as its token sheet gives them to JavaFX. */
    record Palette(boolean dark, Map<Role, Color> text, Color surface, Color shadow, Color sheen, Color hover, Color selected) {
        static Palette of(Theme theme) {
            var tokens = theme.tokens();
            Map<Role, Color> text = new EnumMap<>(Role.class);
            for (Role role : Role.values()) text.put(role, role == Role.WARN ? theme.dark() ? WARN_DARK : WARN_LIGHT : parse(tokens.get(role.token)));
            return new Palette(theme.dark(), Collections.unmodifiableMap(text), parse(tokens.get("-pp-surface-2")), parse(tokens.get("-pp-shadow")),
                parse(tokens.get("-pp-glass-sheen")), parse(tokens.get("-pp-hover")), parse(tokens.get("-pp-selected-soft")));
        }
        /** Black text moves away from a light list, white text from a dark one. */
        Color strongest() { return dark ? Color.WHITE : Color.BLACK; }
    }

    /**
     * Text colours and list surface for one open list over a backdrop between darkest and lightest: the palette's own
     * values where they already read, so an unchanged colour is exactly the theme's.
     */
    record Result(Palette palette, Map<Role, Color> text, double surfaceAlpha, Color darkest, Color lightest) {
        Color text(Role role) { return text.get(role); }
        /** Luminance range of an option's background in the state, as painted with this result. */
        double[] range(State state) { return DropdownContrast.range(palette, surfaceAlpha, state, darkest, lightest); }
        Color surface() { return withAlpha(palette.surface(), surfaceAlpha); }
        boolean protectedSurface() { return surfaceAlpha > palette.surface().getOpacity(); }
        /** Looked-up colours for the open list that differ from the theme's; empty when the theme's colours read as they are. */
        String style() {
            var style = new StringBuilder();
            for (Role role : Role.values()) if (!text.get(role).equals(palette.text().get(role))) style.append(role.token).append(": ").append(Theme.css(text.get(role))).append("; ");
            if (protectedSurface()) style.append("-pp-surface-2: ").append(Theme.css(surface())).append("; ");
            return style.toString();
        }
    }

    /** The readable colours for the theme's open list over a backdrop whose channels lie between darkest and lightest. */
    static Result resolve(Theme theme, Color darkest, Color lightest) { return resolve(Palette.of(theme), darkest, lightest); }
    static Result resolve(Palette palette, Color darkest, Color lightest) {
        double alpha = palette.surface().getOpacity();
        var direct = attempt(palette, alpha, darkest, lightest);
        if (direct.isPresent()) return direct.get();
        // Local protection: the least surface opacity (in the stylesheet's 0.001 steps) at which every text colour can read.
        double low = alpha, high = 1;
        for (int i = 0; i < 20; i++) {
            double mid = (low + high) / 2;
            if (attempt(palette, steps(mid), darkest, lightest).isPresent()) high = mid; else low = mid;
        }
        double protection = steps(high);
        return attempt(palette, protection, darkest, lightest).or(() -> attempt(palette, 1, darkest, lightest))
            .orElseGet(() -> new Result(palette, all(palette.strongest()), 1, darkest, lightest));
    }
    private static Map<Role, Color> all(Color color) {
        Map<Role, Color> text = new EnumMap<>(Role.class);
        for (Role role : Role.values()) text.put(role, color);
        return text;
    }
    /** Every role kept or moved only as far as the target needs at this surface opacity; empty when one cannot reach it. */
    static Optional<Result> attempt(Palette palette, double alpha, Color darkest, Color lightest) {
        Map<Role, Color> chosen = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            List<double[]> ranges = role.states.stream().map(state -> range(palette, alpha, state, darkest, lightest)).toList();
            Color preferred = palette.text().get(role), strongest = palette.strongest();
            if (reads(preferred, ranges)) { chosen.put(role, preferred); continue; }
            if (!reads(strongest, ranges)) return Optional.empty();
            double low = 0, high = 1;
            for (int i = 0; i < 24; i++) { double mid = (low + high) / 2; if (reads(eight(preferred.interpolate(strongest, mid)), ranges)) high = mid; else low = mid; }
            chosen.put(role, eight(preferred.interpolate(strongest, high)));
        }
        return Optional.of(new Result(palette, Collections.unmodifiableMap(chosen), alpha, darkest, lightest));
    }
    private static boolean reads(Color text, List<double[]> ranges) { return ranges.stream().allMatch(range -> weakest(text, range) >= TARGET); }

    /**
     * Relative luminance range {lowest, highest} of an option's background in the state, over a backdrop whose channels
     * lie between darkest and lightest, with the list surface at the given opacity.
     */
    static double[] range(Palette p, double surfaceAlpha, State state, Color darkest, Color lightest) {
        Color dark = shift(darkest, -LEVEL), light = shift(lightest, LEVEL);
        // The drop shadow shows through anywhere from not at all to its full strength; the sheen likewise.
        Color under = min(dark, ColorContrast.over(p.shadow(), dark)), over = max(light, ColorContrast.over(p.shadow(), light));
        // The surface is painted at its opacity or one 8-bit level less; either can be the darker (or lighter) composite.
        Color thin = withAlpha(p.surface(), surfaceAlpha - LEVEL), surface = withAlpha(p.surface(), surfaceAlpha);
        Color low = min(ColorContrast.over(thin, under), ColorContrast.over(surface, under));
        Color high = ColorContrast.over(p.sheen(), max(ColorContrast.over(thin, over), ColorContrast.over(surface, over)));
        Color tint = state == State.HOVER ? p.hover() : state == State.SELECTED ? p.selected() : null;
        if (tint != null) { low = ColorContrast.over(tint, low); high = ColorContrast.over(tint, high); }
        return new double[] { ColorContrast.luminance(shift(low, -LEVEL)), ColorContrast.luminance(shift(high, LEVEL)) };
    }
    private static Color shift(Color c, double by) {
        return Color.color(clamp(c.getRed() + by), clamp(c.getGreen() + by), clamp(c.getBlue() + by));
    }
    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
    /** The weakest contrast of the text colour on any background luminance in the range. */
    static double weakest(Color text, double[] range) {
        double l = ColorContrast.luminance(text);
        return ColorContrast.ratio(l, Math.max(range[0], Math.min(range[1], l)));
    }

    static Color parse(String css) {
        var m = RGBA.matcher(Objects.requireNonNull(css, "token"));
        if (!m.find()) throw new IllegalArgumentException("not an rgba colour: " + css);
        return Color.rgb(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Double.parseDouble(m.group(4)));
    }
    /** The colour as the stylesheet writes it (8-bit channels), so what is checked is what is painted. */
    static Color eight(Color c) {
        return Color.rgb((int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), c.getOpacity());
    }
    /** Up to the stylesheet's next 0.001 step of opacity. */
    private static double steps(double alpha) { return Math.min(1, Math.ceil(alpha * 1000 - 1e-9) / 1000); }
    static Color withAlpha(Color c, double alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(1, alpha))); }
    private static Color min(Color a, Color b) { return Color.color(Math.min(a.getRed(), b.getRed()), Math.min(a.getGreen(), b.getGreen()), Math.min(a.getBlue(), b.getBlue())); }
    private static Color max(Color a, Color b) { return Color.color(Math.max(a.getRed(), b.getRed()), Math.max(a.getGreen(), b.getGreen()), Math.max(a.getBlue(), b.getBlue())); }
}
