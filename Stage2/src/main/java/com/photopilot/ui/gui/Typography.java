package com.photopilot.ui.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.scene.text.Font;

/**
 * Global typography preferences: Font Size (85-125% in 5% steps) and Bold Text. They reach the interface only through
 * the sheet ThemeManager generates after photopilot.css, so every window, dialog, popup and later-loaded view follows
 * them without per-control styling, and nothing is scaled graphically.
 * <ul>
 * <li>Font Size rescales every pixel font size declared in photopilot.css (same selectors, so the same specificity, and
 *     the generated sheet comes later) including the root size all other text inherits; headings keep their
 *     proportions. Text that is part of a fixed-size graphic keeps its size (see {@link #FIXED}).</li>
 * <li>Bold Text makes regular text one step heavier and keeps bold text bold. JavaFX resolves only regular and bold
 *     faces, so where the Latin interface font has a separate Semibold family (Segoe UI on Windows) English text uses
 *     it and existing bold stays Segoe UI Bold. Those cuts have no CJK glyphs, so they are scoped to the
 *     {@code :english} pseudo-class that {@link FxLocalizer#useReadableFont} sets on window content; Chinese text and
 *     platforms without the Semibold family use the bold face.</li>
 * </ul>
 */
record Typography(int fontSize, boolean bold) {
    static final int MIN = 85, MAX = 125, DEFAULT_SIZE = 100, STEP = 5;
    static final Typography DEFAULT = new Typography(DEFAULT_SIZE, false);
    /**
     * Text drawn as part of a fixed-size graphic keeps its pixel size: avatar initials inside their circles, the splash
     * mark, and the workspace chooser's composition (authored in design pixels and scaled as one unit with its window).
     */
    private static final List<String> FIXED = List.of(".avatar-initial", ".portrait-initial", ".splash-mark", ".selection-", ".profile-name");
    /** Symbols shown as text keep their regular weight under Bold Text. */
    private static final String SYMBOLS = ".splash-mark";
    private static final String LATIN = "Segoe UI", LATIN_SEMIBOLD = "Segoe UI Semibold";
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL), BLOCK = Pattern.compile("([^{}]+)\\{([^{}]*)}"),
        SIZE = Pattern.compile("-fx-font-size\\s*:\\s*([0-9.]+)px"), WEIGHT = Pattern.compile("-fx-font-weight\\s*:\\s*(bold|normal)"),
        FAMILY = Pattern.compile("-fx-font-family\\s*:");
    // After the patterns it is read with.
    private static final List<Rule> RULES = rules();
    private static volatile Boolean semibold;

    Typography { fontSize = clamp(fontSize); }
    /** The nearest supported step within 85-125%. */
    static int clamp(int percent) { return Math.max(MIN, Math.min(MAX, Math.round(percent / (float) STEP) * STEP)); }

    /** Rules placed after the theme tokens; empty at 100% without Bold Text, so the default look is exactly the stylesheet's. */
    String stylesheet() {
        if (equals(DEFAULT)) return "";
        var css = new StringBuilder();
        if (fontSize != DEFAULT_SIZE) for (Rule rule : RULES)
            if (rule.size() > 0 && FIXED.stream().noneMatch(rule.selectors()::contains))
                css.append(rule.selectors()).append(" { -fx-font-size: ")
                    .append(String.format(Locale.ROOT, "%.2fpx", rule.size() * fontSize / DEFAULT_SIZE)).append("; }\n");
        if (bold) {
            css.append(".root { -fx-font-weight: bold; }\n");
            if (semibold()) {
                css.append("*:english { -fx-font-family: \"").append(LATIN_SEMIBOLD).append("\"; -fx-font-weight: normal; }\n");
                // Rules that choose their own family are left alone; the others get the cut that matches their weight.
                for (Rule rule : RULES) if (rule.weight() != null && !rule.family())
                    css.append(english(rule.selectors())).append(" { -fx-font-family: \"")
                        .append("bold".equals(rule.weight()) ? LATIN : LATIN_SEMIBOLD).append("\"; }\n");
            }
            css.append(SYMBOLS).append(" { -fx-font-weight: normal; }\n");
        }
        return css.toString();
    }

    /** Each selector inside :english content, or on that content itself (a dialog pane is its own window content). */
    private static String english(String selectors) {
        var scoped = new StringJoiner(", ");
        for (String selector : selectors.split(",")) {
            String s = selector.strip();
            int end = 0;
            while (end < s.length() && s.charAt(end) != ' ' && s.charAt(end) != '>') end++;
            scoped.add("*:english " + s); scoped.add(s.substring(0, end) + ":english" + s.substring(end));
        }
        return scoped.toString();
    }
    private static boolean semibold() {
        if (semibold == null) {
            try { var families = Font.getFamilies(); semibold = families.contains(LATIN) && families.contains(LATIN_SEMIBOLD); }
            catch (RuntimeException unavailable) { return false; }
        }
        return semibold;
    }

    /** A rule of photopilot.css that sets a pixel font size (0: none) or a font weight (null: none). */
    private record Rule(String selectors, double size, String weight, boolean family) { }
    /** Read once from photopilot.css itself, so new or changed rules follow the preferences without a second list. */
    private static List<Rule> rules() {
        try (InputStream in = PhotoPilotGUI.class.getResourceAsStream("photopilot.css")) {
            if (in == null) return List.of();
            String css = COMMENT.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8)).replaceAll("");
            var rules = new ArrayList<Rule>();
            for (Matcher block = BLOCK.matcher(css); block.find(); ) {
                String body = block.group(2);
                Matcher size = SIZE.matcher(body), weight = WEIGHT.matcher(body);
                boolean sized = size.find(), weighted = weight.find();
                if (sized || weighted) rules.add(new Rule(block.group(1).strip().replaceAll("\\s+", " "),
                    sized ? Double.parseDouble(size.group(1)) : 0, weighted ? weight.group(1) : null, FAMILY.matcher(body).find()));
            }
            return List.copyOf(rules);
        } catch (IOException | RuntimeException unreadable) { return List.of(); }
    }
}
