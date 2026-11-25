package com.photopilot.ui.gui;

import java.util.*;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import static com.photopilot.ui.gui.ThemeTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 QA-01 (production side, no desktop input): the open dropdown's option states as photopilot.css paints
 * them (.combo-box-popup .list-cell: plain, :hover = -pp-hover, :selected and :selected:hover = -pp-selected-soft with
 * -pp-accent-text) on the list surface (-pp-surface-2 over the window background), for every preset accent and mode, and
 * custom hues every 15 degrees, at 0, 40, 62 and 100 % opacity. A selected or hovered option is never transparent, always
 * distinguishable from a plain one; the theme's own text colours keep their earlier floors, and the text as painted once
 * the open list is measured (the theme's colour, or the readable one DropdownContrast gives it) keeps 4.5:1 at every one of
 * these steps. Real-pointer behaviour stays in DropdownGuiTest.
 */
class DropdownThemeStatesTest {
    static List<Theme> themes() {
        var all = new ArrayList<Theme>();
        for (int opacity : new int[] { 0, 40, 62, 100 })
            for (Theme.Mode mode : Theme.Mode.values()) for (boolean dark : new boolean[] { false, true }) {
                for (Theme.Accent accent : Theme.Accent.values()) all.add(new Theme(accent, mode, dark, opacity));
                for (int hue = 0; hue < 360; hue += 15) all.add(Theme.custom(hue, mode, dark, opacity));
            }
        return all;
    }
    static double distance(Color a, Color b) {
        return Math.max(Math.abs(a.getRed() - b.getRed()), Math.max(Math.abs(a.getGreen() - b.getGreen()), Math.abs(a.getBlue() - b.getBlue())));
    }

    @Test void selectedAndHoveredOptionsStayVisibleAndReadableInEveryThemeAndCustomColour() throws Exception {
        String css = ThemeTest.css();
        // The rules this test reads the tokens of (a changed rule must update this check).
        assertTrue(css.contains(".combo-box-popup .list-cell:filled:hover { -fx-background-color: -pp-hover; }"), "hover rule");
        assertTrue(css.contains(".combo-box-popup .list-cell:filled:selected, .combo-box-popup .list-cell:filled:selected:hover {"), "selected rule");
        assertTrue(css.contains("-fx-background-color: -pp-selected-soft; -fx-text-fill: -pp-accent-text;"), "selected colours");
        double weakestSelected = Double.MAX_VALUE, weakestTheme = Double.MAX_VALUE, weakestHover = Double.MAX_VALUE, faintestTint = Double.MAX_VALUE;
        String weakest = ""; int themeBelowAa = 0;
        var backdrops = new HashMap<Theme.Lighting, List<Color>>();
        for (Theme theme : themes()) {
            var t = theme.tokens();
            Color surface = color(t.get("-pp-surface-2")), selected = color(t.get("-pp-selected-soft")), hover = color(t.get("-pp-hover"));
            Color text = color(t.get("-pp-text")), themeAccentText = color(t.get("-pp-accent-text"));
            assertTrue(selected.getOpacity() > 0.1, theme + ": the selected tint is never transparent (" + selected + ")");
            assertTrue(hover.getOpacity() > 0.03, theme + ": the hover tint is never transparent (" + hover + ")");
            var range = DropdownContrastTest.backdrop(theme);
            var painted = DropdownContrast.resolve(theme, range[0], range[1]);
            Color accentText = painted.text(DropdownContrast.Role.ACCENT), hoverText = painted.text(DropdownContrast.Role.TEXT);
            for (Color backdrop : backdrops.computeIfAbsent(theme.lighting(), ThemeTest::backdrops)) {
                Color plain = over(painted.surface(), backdrop);
                Color onSelected = over(selected, plain), onHover = over(hover, plain);
                double selectedRatio = contrast(accentText, onSelected), hoverRatio = contrast(hoverText, onHover), tint = distance(onSelected, plain);
                // The theme's own colours (painted until the open list has been measured, and wherever they already read) keep
                // the guarantees of the original check: selected text >= 4.5:1 at the default transparency and above, >= 4.0
                // below it (found 2026-11-24: 4.01-4.49 at 0 %, and at 40 % for some dark custom hues), hover text >= 4.5:1.
                double themeRatio = contrast(themeAccentText, over(selected, over(surface, backdrop)));
                if (themeRatio < 4.5) themeBelowAa++;
                weakestTheme = Math.min(weakestTheme, themeRatio);
                assertTrue(themeRatio >= (theme.opacity() >= Theme.DEFAULT_OPACITY ? 4.5 : 4.0), theme + ": the theme's selected option text " + themeRatio);
                assertTrue(contrast(text, over(hover, over(surface, backdrop))) >= 4.5, theme + ": the theme's hovered option text");
                // What the open list paints once measured: 4.5:1 at every transparency (stage2optre2-remediation.md, section G).
                assertTrue(selectedRatio >= 4.5, theme + ": selected option text " + selectedRatio);
                assertTrue(hoverRatio >= 4.5, theme + ": hovered option text " + hoverRatio);
                assertTrue(tint >= 0.03, theme + ": the selected option is distinguishable from a plain one (" + tint + ")");
                assertTrue(distance(onHover, plain) >= 0.015, theme + ": the hovered option is distinguishable from a plain one");
                if (selectedRatio < weakestSelected) { weakestSelected = selectedRatio; weakest = theme.toString(); }
                weakestHover = Math.min(weakestHover, hoverRatio); faintestTint = Math.min(faintestTint, tint);
            }
        }
        System.out.printf(Locale.ROOT, "Dropdown states: weakest selected text %.2f (%s), weakest hover text %.2f, faintest selected tint %.3f; "
            + "theme accent text alone: weakest %.2f, %d samples below 4.5:1%n", weakestSelected, weakest, weakestHover, faintestTint, weakestTheme, themeBelowAa);
    }
}
