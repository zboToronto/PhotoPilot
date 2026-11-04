package com.photopilot.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * F04 deterministic comparison of equipment names and specification values from different sources. Matching is
 * exact over normalized tokens, so distinct variants ("GM" vs "GM II", "EF" vs "EF-S") never match.
 */
public final class GearNames {
    private GearNames() { }

    /** Lower-case alphanumeric tokens; letters and digits split ("70mm" -> 70, mm), "α"/"a7" read as alpha. */
    public static List<String> tokens(String text) {
        String value = java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replace("α", " alpha ").replace("f/", "f")
            .replaceAll("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ");
        var raw = Arrays.stream(value.split("[^a-z0-9]+")).filter(token -> !token.isEmpty()).toList();
        var result = new ArrayList<String>();
        for (int i = 0; i < raw.size(); i++) {
            String token = raw.get(i);
            boolean beforeNumber = i + 1 < raw.size() && raw.get(i + 1).matches("[0-9]+");
            result.add(token.equals("a") && beforeNumber ? "alpha" : token);
        }
        return result;
    }

    /** Same product name, ignoring order, punctuation, case and the brand name wherever it appears. */
    public static boolean sameModel(String brand, String first, String second) {
        var a = withoutBrand(brand, first);
        var b = withoutBrand(brand, second);
        if (a.isEmpty() || b.isEmpty()) return false;
        a.sort(null); b.sort(null);
        return a.equals(b);
    }

    public static boolean sameBrand(String first, String second) {
        String a = alnum(first), b = alnum(second);
        return !a.isEmpty() && (a.equals(b) || a.startsWith(b) || b.startsWith(a));
    }

    /** Mount labels compare without the word "mount" and a leading brand ("Sony E" = "E-mount"); "EF" != "EF-S". */
    public static boolean sameMount(String brand, String first, String second) {
        return !mountKey(brand, first).isEmpty() && mountKey(brand, first).equals(mountKey(brand, second));
    }
    private static String mountKey(String brand, String mount) {
        var tokens = tokens(mount);
        tokens.removeIf(token -> token.equals("mount"));
        var brandTokens = tokens(brand);
        if (!tokens.isEmpty() && tokens.size() > 1 && brandTokens.contains(tokens.getFirst())) tokens.removeFirst();
        for (String common : List.of("sony", "canon", "nikon", "fujifilm", "fuji", "leica", "pentax", "panasonic", "olympus"))
            if (tokens.size() > 1 && tokens.getFirst().equals(common)) tokens.removeFirst();
        return String.join("", tokens);
    }

    /** Field-aware equality for comparing two sources: numbers equal up to decimal formatting, sets as sets. */
    public static boolean sameValue(String field, String brand, String first, String second) {
        String a = first == null ? "" : first.strip(), b = second == null ? "" : second.strip();
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        if (field.equals("mount")) return sameMount(brand, a, b);
        if (Set.of("batteryTypes", "storageFormats", "autofocusModes").contains(field)) return set(a).equals(set(b));
        if (Set.of("stabilization", "autofocus").contains(field)) return a.equalsIgnoreCase(b);
        try {
            double x = Double.parseDouble(a), y = Double.parseDouble(b);
            return Math.abs(x - y) <= 0.0051;
        } catch (NumberFormatException text) {
            return alnum(a).equals(alnum(b));
        }
    }

    public static String alnum(String text) { return String.join("", tokens(text)); }

    private static List<String> withoutBrand(String brand, String name) {
        var tokens = new ArrayList<>(tokens(name));
        var brandTokens = tokens(brand);
        if (!brandTokens.isEmpty()) tokens.removeIf(brandTokens::contains);
        return tokens;
    }
    private static Set<String> set(String values) {
        var result = new TreeSet<String>();
        for (String value : values.split(",")) if (!alnum(value).isEmpty()) result.add(alnum(value));
        return result;
    }
}
