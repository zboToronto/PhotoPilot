package com.photopilot.i18n;

/** Fixed English renderer for existing domain diagnostics, never an active application locale. */
public final class EnglishMessages {
    private EnglishMessages() { }
    private static final LocalizationService SERVICE = LocalizationService.english();
    public static String text(String key, Object... arguments) { return SERVICE.text(Language.ENGLISH, key, arguments); }
}
