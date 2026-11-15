package com.photopilot.config;

import com.photopilot.i18n.Language;
import com.photopilot.repository.JsonLocalePreferenceRepository;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The preferences every new local user starts with, defined once for the GUI and the CLI: Theme Color Blue (the built-in
 * preset, by its stored id; its colours stay in the GUI's Theme), Appearance Mode Follow System (still resolved from the
 * operating system at run time), Transparency 50% (the stored opacity that the Settings slider shows), Font Size 100%,
 * Bold Text off and English.
 *
 * <p>They are written to the new profile's own record ({@code profile.<uuid>.*} in preferences.json) before the profile is
 * registered (ProfileManager.createProfile), and for the Default user of a fresh installation, so a new user never
 * inherits the active user's, the Default user's or the shared legacy values. Existing users are never rewritten: a
 * record without a value keeps the GUI's in-memory fallback (Theme.DEFAULT, Typography.DEFAULT).
 */
public final class UserPreferenceDefaults {
    /** The stored appearance keys; ThemeManager reads and saves the same keys. */
    public static final String ACCENT_KEY = "appearance.accent", MODE_KEY = "appearance.mode", OPACITY_KEY = "appearance.opacity",
        FONT_SIZE_KEY = "appearance.fontSize", BOLD_KEY = "appearance.boldText";
    /** Blue preset, Follow System, Transparency 50%, Font Size 100%, Bold Text off, in a stable order. */
    public static final Map<String, String> APPEARANCE = appearance();
    public static final Language LANGUAGE = Language.ENGLISH;

    private UserPreferenceDefaults() { }

    private static Map<String, String> appearance() {
        var values = new LinkedHashMap<String, String>();
        values.put(ACCENT_KEY, "blue"); values.put(MODE_KEY, "system"); values.put(OPACITY_KEY, "50");
        values.put(FONT_SIZE_KEY, "100"); values.put(BOLD_KEY, "false");
        return Collections.unmodifiableMap(values);
    }

    /** Saves all six values in a new profile's own record (never in the shared values). */
    public static void initialize(JsonLocalePreferenceRepository profile) throws IOException {
        if (profile.profile().isEmpty()) throw new IllegalArgumentException("Initial preferences belong to one profile.");
        profile.merge(APPEARANCE);
        profile.save(LANGUAGE.tag());
    }
}
