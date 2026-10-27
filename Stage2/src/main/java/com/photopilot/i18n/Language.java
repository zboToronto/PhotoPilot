package com.photopilot.i18n;

import java.util.Locale;

/** Stable identifiers; display names are never parsed as configuration. */
public enum Language {
    ENGLISH("en", "English", Locale.ENGLISH),
    SIMPLIFIED_CHINESE("zh-CN", "简体中文", Locale.SIMPLIFIED_CHINESE);

    private final String tag;
    private final String nativeName;
    private final Locale locale;
    Language(String tag, String nativeName, Locale locale) {
        this.tag = tag; this.nativeName = nativeName; this.locale = locale;
    }
    public String tag() { return tag; }
    public Locale locale() { return locale; }
    public static Language fromTag(String tag) {
        for (Language language : values()) if (language.tag.equals(tag)) return language;
        return ENGLISH;
    }
    public static boolean supports(String tag) {
        return ENGLISH.tag.equals(tag) || SIMPLIFIED_CHINESE.tag.equals(tag);
    }
    @Override public String toString() { return nativeName; }
}
