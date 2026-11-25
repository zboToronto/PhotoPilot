package com.photopilot.application;

import java.util.*;

/**
 * Guided Discovery preset photography categories (GUI chips, CLI numbered list). A preset only supplements the free-text
 * interests: it contributes one fixed, language-independent term to the same "; "-separated interests field that
 * {@link DiscoveryForm} parses, so the request, the Gemini evidence and the deterministic interest ranking are exactly
 * what typing that term would produce. Custom entries are kept verbatim and in order; nothing is translated or merged.
 */
public final class DiscoveryInterests {
    private DiscoveryInterests() { }

    /** Display order of the presets; {@link #term()} is what the request receives. */
    public enum Category {
        LANDSCAPE("landscape"), STREET("street"), ARCHITECTURE("architecture"), AVIATION("aviation"), WILDLIFE("wildlife"),
        PORTRAIT("portrait"), NIGHT("night"), SPORTS("sports"), EVENTS("events");
        private final String term;
        Category(String term) { this.term = term; }
        public String term() { return term; }
        public String labelKey() { return "discover.category." + name(); }
    }

    /** An interests field split into the presets it names exactly (case-insensitive) and the remaining custom entries. */
    public record Split(Set<Category> presets, List<String> custom) {
        public Split { presets = Collections.unmodifiableSet(EnumSet.copyOf(presets.isEmpty() ? EnumSet.noneOf(Category.class) : presets)); custom = List.copyOf(custom); }
        /** The custom entries as one editable text, "; "-separated. */
        public String customText() { return String.join("; ", custom); }
    }

    public static Optional<Category> byTerm(String entry) {
        String value = entry == null ? "" : entry.strip();
        return Arrays.stream(Category.values()).filter(c -> c.term.equalsIgnoreCase(value)).findFirst();
    }

    /** Splits a stored interests field with the same separators as {@link DiscoveryForm}. */
    public static Split split(String interests) {
        var presets = EnumSet.noneOf(Category.class);
        var custom = new ArrayList<String>();
        for (String entry : DiscoveryForm.entries(interests == null ? "" : interests)) {
            var preset = byTerm(entry);
            if (preset.isPresent()) presets.add(preset.get()); else custom.add(entry);
        }
        return new Split(presets, custom);
    }

    /** The interests field for the request: selected presets in display order, then the custom entries as typed. */
    public static String compose(Collection<Category> presets, String customText) {
        var entries = new ArrayList<String>();
        for (var category : Category.values()) if (presets.contains(category)) entries.add(category.term);
        entries.addAll(DiscoveryForm.entries(customText == null ? "" : customText));
        return String.join("; ", entries);
    }
}
