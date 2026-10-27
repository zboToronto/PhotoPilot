package com.photopilot.i18n;

import java.util.List;

/** Locale-neutral message data, retained until a GUI/CLI consumer renders it. */
public record LocalizedMessage(String key, List<Object> arguments) {
    public LocalizedMessage { arguments = List.copyOf(arguments); }
    public static LocalizedMessage of(String key, Object... arguments) { return new LocalizedMessage(key, List.of(arguments)); }
    public String render(LocalizationService localization) { return render(localization, localization.language()); }
    public String render(LocalizationService localization, Language language) { return localization.text(language, key, arguments.toArray()); }
}
