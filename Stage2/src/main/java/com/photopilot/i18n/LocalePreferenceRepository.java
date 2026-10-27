package com.photopilot.i18n;

import java.io.IOException;

/** Storage port, independent of JavaFX and of business-data repositories. */
public interface LocalePreferenceRepository {
    String load() throws IOException;
    void save(String tag) throws IOException;

    static LocalePreferenceRepository transientPreference() {
        return new LocalePreferenceRepository() {
            private String tag;
            public String load() { return tag; }
            public void save(String value) { tag = value; }
        };
    }
}
