package com.photopilot.i18n;

import com.photopilot.common.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalizationTest {
    @TempDir Path directory;

    @Test void defaultLookupUnicodeSwitchAndInvalidLocales() {
        var service = LocalizationService.english();
        assertEquals(Language.ENGLISH, service.language());
        assertEquals("Settings", service.text("settings.title"));
        for (int i = 0; i < 10; i++) {
            service.useLanguage("zh-CN"); assertEquals("设置", service.text("settings.title"));
            assertEquals(Language.SIMPLIFIED_CHINESE, service.language());
            service.useLanguage("en"); assertEquals("Settings", service.text("settings.title"));
        }
        for (String invalid : Arrays.asList(null, "", "zh", "zh_CN", "fr", "简体中文", "EN")) {
            service.useLanguage(invalid); assertEquals(Language.ENGLISH, service.language());
        }
    }
    @Test void englishFallbackMissingKeyApostrophesAndNoOsLocaleLeakage() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            var service = new LocalizationService(LocalePreferenceRepository.transientPreference(), "i18n.fallback");
            assertEquals("English", service.text("shared"));
            service.useLanguage("zh-CN");
            assertEquals("简体中文", service.text("shared"));
            assertEquals("English fallback 用户", service.text("onlyRoot", "用户"));
            assertEquals("[!missing.key!]", service.text("missing.key"));
            assertEquals("User's value: O'Brien {0}\nNext line", service.text("quoted", "O'Brien {0}"));
        } finally { Locale.setDefault(before); }
    }
    @Test void nestedValidationDescriptorsSurvivePropagationAndLocaleChanges() {
        var service = LocalizationService.english();
        var failure = Result.failure(new LocalizedException("error.validation.required", LocalizedMessage.of("gear.field.mount")));
        var propagated = Result.failure(failure);
        assertEquals(List.of("Mount is required."), propagated.errors());
        service.useLanguage("zh-CN");
        assertEquals(List.of("卡口 为必填项。"), propagated.errors(service));
        assertEquals(List.of("Mount is required."), propagated.errors());
        assertTrue(propagated.hasError("error.validation.required"));
    }
    @Test void listenersUpdateIndependentlyAndDetach() {
        var service = LocalizationService.english();
        var first = new AtomicInteger(); var second = new AtomicInteger();
        var a = service.listen(first::incrementAndGet); var b = service.listen(second::incrementAndGet);
        service.useLanguage("zh-CN"); assertEquals(1, first.get()); assertEquals(1, second.get());
        a.close(); a.close(); service.useLanguage("en");
        assertEquals(1, first.get()); assertEquals(2, second.get()); b.close(); assertEquals(0, service.listenerCount());
    }
    @Test void persistsRestoresAndPreservesUnrelatedUtf8Preferences() throws Exception {
        Path file = directory.resolve("settings.properties");
        var repository = new FileLocalePreferenceRepository(file);
        var first = new LocalizationService(repository);
        assertFalse(Files.exists(file));
        Files.writeString(file, "theme=dark\ncustom=用户的设置\n", StandardCharsets.UTF_8);
        assertTrue(first.selectLanguage("zh-CN"));
        var restarted = new LocalizationService(new FileLocalePreferenceRepository(file));
        assertEquals(Language.SIMPLIFIED_CHINESE, restarted.language());
        Properties stored = new Properties();
        try (var reader = Files.newBufferedReader(file)) { stored.load(reader); }
        assertEquals("dark", stored.getProperty("theme")); assertEquals("用户的设置", stored.getProperty("custom"));
        restarted.useLanguage("en");
        assertEquals("zh-CN", repository.load(), "Session override must not persist");
    }
    @Test void corruptValuesAndMalformedFilePreserveOriginal() throws Exception {
        Path file = directory.resolve("settings.properties");
        Files.writeString(file, "language=invalid\n");
        assertEquals(Language.ENGLISH, new LocalizationService(new FileLocalePreferenceRepository(file)).language());
        String malformed = "theme=keep\nlanguage=\\uZZZZ\n";
        Files.writeString(file, malformed);
        var service = new LocalizationService(new FileLocalePreferenceRepository(file));
        assertEquals(Language.ENGLISH, service.language()); assertTrue(service.preferenceFailure());
        assertFalse(service.selectLanguage("zh-CN"));
        assertEquals(Language.SIMPLIFIED_CHINESE, service.language());
        assertEquals(malformed, Files.readString(file));
    }
    @Test void unreadableAndUnwritablePreferencesRemainUsableAndRetryWorks() {
        boolean[] fail = { true };
        var repository = new LocalePreferenceRepository() {
            public String load() throws IOException { throw new IOException("read failure"); }
            public void save(String tag) throws IOException { if (fail[0]) throw new IOException("write failure"); }
        };
        var service = new LocalizationService(repository);
        assertEquals(Language.ENGLISH, service.language()); assertTrue(service.preferenceFailure());
        assertFalse(service.selectLanguage("zh-CN")); assertEquals("设置", service.text("settings.title"));
        assertEquals("settings.saveFailed", service.preferenceFailureKey());
        fail[0] = false;
        assertTrue(service.selectLanguage("zh-CN")); assertFalse(service.preferenceFailure());
    }
    @Test void storagePathFailureDoesNotDestroyExistingFile() throws Exception {
        Path parentFile = directory.resolve("not-a-directory"); Files.writeString(parentFile, "keep");
        var service = new LocalizationService(new FileLocalePreferenceRepository(parentFile.resolve("settings.properties")));
        assertFalse(service.selectLanguage("zh-CN")); assertEquals("keep", Files.readString(parentFile));
    }
}
