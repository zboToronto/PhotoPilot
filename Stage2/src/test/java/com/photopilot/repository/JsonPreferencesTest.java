package com.photopilot.repository;

import com.google.gson.JsonParser;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JsonPreferencesTest {
    @TempDir Path root;
    Path json() { return root.resolve("preferences.json"); }
    Path legacy() { return root.resolve("settings.properties"); }
    JsonLocalePreferenceRepository repository() { return new JsonLocalePreferenceRepository(json(), legacy()); }

    @Test void missingFilesStayAbsentUntilExplicitSaveAndJsonSurvivesRestart() throws Exception {
        assertNull(repository().load()); assertFalse(Files.exists(json()));
        repository().save("zh-CN"); assertEquals("zh-CN", repository().load());
        assertFalse(Files.exists(legacy()));
        repository().save("en"); assertEquals("en", repository().load());
    }
    @Test void legacyReadIsSideEffectFreeAndFirstSavePreservesAllValuesAndOriginalBytes() throws Exception {
        String original = "language=zh-CN\ncustom=用户的设置\ntheme=dark\n"; Files.writeString(legacy(), original);
        assertEquals("zh-CN", repository().load()); assertFalse(Files.exists(json()));
        repository().save("en");
        var values = JsonParser.parseString(Files.readString(json())).getAsJsonObject().getAsJsonObject("values");
        assertEquals("用户的设置", values.get("custom").getAsString()); assertEquals("dark", values.get("theme").getAsString());
        assertEquals(original, Files.readString(legacy())); assertEquals("en", repository().load());
        Files.writeString(legacy(), "language=zh-CN\n");
        assertEquals("en", repository().load(), "Once present JSON is authoritative, never silently fall back");
    }
    @Test void emptyLegacyPropertiesRemainCompatible() throws Exception {
        Files.writeString(legacy(), ""); assertNull(repository().load());
        repository().save("en"); assertEquals("", Files.readString(legacy()));
    }
    @Test void malformedJsonNeverFallsBackOrGetsOverwritten() throws Exception {
        Files.writeString(legacy(), "language=en\n");
        for (String bad : List.of("", " ", "{", "null", "[]", "{\"schemaVersion\":2,\"values\":{}}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"values\":{}}",
                "{\"schemaVersion\":1,\"values\":{\"language\":\"unknown\"}}",
                "{\"schemaVersion\":1,\"values\":{\"language\":42}}",
                "{\"schemaVersion\":1,\"values\":{},\"future\":true}")) {
            Files.writeString(json(), bad);
            assertThrows(IOException.class, () -> repository().load());
            assertThrows(IOException.class, () -> repository().save("zh-CN"));
            assertEquals(bad, Files.readString(json()));
        }
    }
    @Test void badLegacyAndInvalidUtf8ArePreservedOnReadAndSave() throws Exception {
        Files.writeString(legacy(), "language=\\uZZZZ\n");
        assertThrows(IOException.class, () -> repository().save("en")); assertFalse(Files.exists(json()));
        byte[] invalid = {(byte) 0xc3, (byte) 0x28}; Files.write(json(), invalid);
        assertThrows(IOException.class, () -> repository().load());
        assertThrows(IOException.class, () -> repository().save("en")); assertArrayEquals(invalid, Files.readAllBytes(json()));
        Files.writeString(json(), " ".repeat(65537));
        assertThrows(IOException.class, () -> repository().load()); assertEquals(65537, Files.size(json()));
    }
    @Test void failedReplacementPreservesPreferencesAndLocalizedRetryWorks() throws Exception {
        repository().save("en"); String original = Files.readString(json());
        var broken = new JsonLocalePreferenceRepository(json(), legacy()) {
            protected void replace(Path temporary) throws IOException { throw new IOException("Injected write failure"); }
        };
        var service = new LocalizationService(broken);
        assertFalse(service.selectLanguage("zh-CN")); assertTrue(service.preferenceFailure());
        assertEquals(original, Files.readString(json())); assertEquals(Language.SIMPLIFIED_CHINESE, service.language());
        assertTrue(new LocalizationService(repository()).selectLanguage("zh-CN"));
        try (var paths = Files.list(root)) { assertFalse(paths.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
    @Test void bootstrapUsesJsonForTheExistingLanguageWorkflow() throws Exception {
        Map<String,String> env = Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.toString());
        Files.writeString(legacy(), "language=zh-CN\ncustom=保留\n");
        try (var app = new ApplicationBootstrap(env)) {
            assertEquals(Language.SIMPLIFIED_CHINESE, app.localization().language());
            assertFalse(Files.exists(json())); assertTrue(app.localization().selectLanguage("en"));
        }
        try (var app = new ApplicationBootstrap(env)) { assertEquals(Language.ENGLISH, app.localization().language()); }
        assertTrue(Files.readString(json()).contains("保留"));
    }
    @Test void inaccessiblePreferencePathDoesNotCrashApplicationOrOverwriteIt() throws Exception {
        Files.createDirectory(json());
        var service = new LocalizationService(repository()); assertTrue(service.preferenceFailure());
        assertFalse(service.selectLanguage("zh-CN")); assertTrue(Files.isDirectory(json()));
        assertThrows(IOException.class, () -> repository().save("unsupported"));
    }
}
