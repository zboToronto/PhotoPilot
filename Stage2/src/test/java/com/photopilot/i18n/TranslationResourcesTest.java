package com.photopilot.i18n;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TranslationResourcesTest {
    private Properties read(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/i18n/" + name + ".properties")) {
            assertNotNull(stream, name + " must be packaged on the classpath");
            String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(stream.readAllBytes())).toString();
            Properties entries = new Properties() {
                @Override public synchronized Object put(Object key, Object value) {
                    assertFalse(containsKey(key), name + ": duplicate key " + key);
                    return super.put(key, value);
                }
            };
            entries.load(new StringReader(source));
            return entries;
        }
    }
    private Set<String> placeholders(String pattern) {
        var matcher = Pattern.compile("\\{(\\d+)(?:,([^}]+))?}").matcher(pattern);
        Set<String> placeholders = new TreeSet<>();
        while (matcher.find()) placeholders.add(matcher.group());
        return placeholders;
    }
    @Test void resourcesAreUtf8UniqueCompleteAndHaveMatchingValidPlaceholders() throws Exception {
        var root = read("messages"); var english = read("messages_en"); var chinese = read("messages_zh_CN");
        assertEquals(root, english, "English bundle must agree with root fallback");
        assertEquals(root.stringPropertyNames(), chinese.stringPropertyNames(), "Missing or extra translation keys");
        assertTrue(root.size() > 250, "Audit unexpectedly lost resource coverage");
        for (String key : root.stringPropertyNames()) {
            assertTrue(key.matches("[A-Za-z][A-Za-z0-9_.-]*"), key);
            String en = root.getProperty(key), zh = chinese.getProperty(key);
            assertFalse(en.isBlank(), key); assertFalse(zh.isBlank(), key);
            var enFormat = new MessageFormat(en, Locale.ENGLISH);
            var zhFormat = new MessageFormat(zh, Locale.SIMPLIFIED_CHINESE);
            assertEquals(placeholders(en), placeholders(zh), key + ": placeholder mismatch");
            assertEquals(enFormat.getFormatsByArgumentIndex().length, zhFormat.getFormatsByArgumentIndex().length, key);
            Object[] arguments = new Object[enFormat.getFormatsByArgumentIndex().length];
            Arrays.fill(arguments, "用户 O'Brien {raw}");
            String output = zhFormat.format(arguments);
            assertFalse(output.contains("\uFFFD"), key + ": malformed Unicode");
            for (Object argument : arguments) assertTrue(output.contains(argument.toString()), key + ": accidentally quoted placeholder");
        }
        assertEquals("设置", chinese.getProperty("settings.title"));
    }
    @Test void staticallyReferencedKeysExistAndUiDoesNotIntroduceLiteralCaptions() throws Exception {
        var entries = read("messages");
        Pattern calls = Pattern.compile("(?:(?:localization|EnglishMessages|l)\\.text|failureKey|LocalizedException|LocalizedMessage\\.of|texts\\.label|texts\\.button)\\(\\s*\"([A-Za-z][A-Za-z0-9_.-]+)\"");
        Pattern directCaption = Pattern.compile("(?:new (?:Button|CheckBox|Label)|set(?:Text|PromptText|AccessibleText|AccessibleHelp))\\(\"([^\"]*[A-Za-z][^\"]*)\"\\)");
        try (var paths = Files.walk(Path.of("src/main/java/com/photopilot"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path); var matcher = calls.matcher(source);
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (!key.endsWith(".")) assertTrue(entries.containsKey(key), path + ": missing " + key);
                }
                if (path.toString().contains("ui" + File.separator + "gui")) {
                    var literal = directCaption.matcher(source);
                    assertFalse(literal.find(), () -> path + ": literal caption should be extracted");
                }
            }
        }
    }
}
