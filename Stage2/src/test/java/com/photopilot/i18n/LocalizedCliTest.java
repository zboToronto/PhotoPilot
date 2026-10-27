package com.photopilot.i18n;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalizedCliTest {
    @TempDir Path directory;
    private Map<String,String> environment() { return Map.of("PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString(), "PHOTOPILOT_DATA_DIR", directory.resolve("data").toString()); }
    private PhotoPilotCLI cli(ApplicationBootstrap app, ByteArrayOutputStream output, String input) {
        return new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(input),
            new PrintStream(output, true, StandardCharsets.UTF_8), app.localization());
    }
    private String output(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    @Test void defaultPersistentOverrideAndInvalidLanguage() {
        try (var app = new ApplicationBootstrap(environment())) {
            var bytes = new ByteArrayOutputStream(); var cli = cli(app, bytes, "");
            cli.run(new String[]{"help"}); assertTrue(output(bytes).contains("commands"));
            assertEquals(0, cli.execute("lang zh-CN")); assertTrue(output(bytes).contains("语言偏好已保存"));
        }
        try (var app = new ApplicationBootstrap(environment())) {
            assertEquals(Language.SIMPLIFIED_CHINESE, app.localization().language());
            var bytes = new ByteArrayOutputStream(); var cli = cli(app, bytes, "");
            cli.run(new String[]{"--lang", "en", "help"}); assertTrue(output(bytes).contains("commands"));
            assertEquals(2, cli.execute("lang unsupported")); assertEquals(Language.ENGLISH, app.localization().language());
            bytes.reset(); cli.run(new String[]{"--lang=invalid", "help"});
            assertTrue(output(bytes).contains("Unsupported or missing language"));
        }
        try (var app = new ApplicationBootstrap(environment())) { assertEquals(Language.SIMPLIFIED_CHINESE, app.localization().language()); }
    }
    @Test void chineseHelpValidationCrudAndCalculationPreserveIdentifiersAndValues() throws Exception {
        try (var app = new ApplicationBootstrap(environment())) {
            var bytes = new ByteArrayOutputStream(); var cli = cli(app, bytes, "");
            cli.run(new String[]{"--lang", "zh-CN", "help", "gear help", "calc help", "status", "unknown", "F01"});
            String text = output(bytes);
            assertTrue(text.contains("器材命令")); assertTrue(text.contains("gear edit <UUID>"));
            assertTrue(text.contains("ndStops")); assertTrue(text.contains("未知命令")); assertTrue(text.contains("尚未实现：F01"));
            assertEquals(2, cli.execute("gear add camera brand=品牌 model=O'Brien")); // unmatched quote is localized
            assertTrue(output(bytes).contains("引号未闭合"));
            bytes.reset(); assertEquals(2, cli.execute("gear add camera brand=品牌 model=相机"));
            assertTrue(output(bytes).contains("卡口 为必填项"));
            assertEquals(0, cli.execute("gear add camera brand=品牌 model=相机 mount=E"));
            var gear = app.facade().inventory().list("").value().orElseThrow().items().getFirst();
            assertEquals("品牌", gear.brand()); assertEquals("E", gear.specifications().get("mount"));
            byte[] stored = Files.readAllBytes(directory.resolve("data/inventory.json"));
            bytes.reset(); assertEquals(0, cli.execute("calc nd shutter=1/125 ndStops=10"));
            assertTrue(output(bytes).contains("调整后的快门时间: 8.192 s"));
            assertFalse(output(bytes).contains("Adjusted shutter")); assertTrue(output(bytes).contains("理想"));
            cli.execute("lang en"); assertArrayEquals(stored, Files.readAllBytes(directory.resolve("data/inventory.json")));
            assertEquals(0, cli.execute("gear edit " + gear.id() + " quantity=2"));
            assertEquals(0, cli.execute("gear remove " + gear.id()));
        }
    }
    @Test void interactiveLocaleOnlyOverrideAndEofWorkWithUtf8Streams() {
        try (var app = new ApplicationBootstrap(environment())) {
            var bytes = new ByteArrayOutputStream();
            cli(app, bytes, "help\nexit\nunknown\n").run(new String[]{"--lang", "zh-CN"});
            assertTrue(output(bytes).endsWith("PhotoPilot 已关闭。" + System.lineSeparator()));
            assertFalse(output(bytes).contains("未知命令"));
            assertFalse(Files.exists(directory.resolve("config/settings.properties")));
            bytes.reset(); cli(app, bytes, "").run(new String[]{"--lang"});
            assertTrue(output(bytes).contains("Unsupported or missing language"));
        }
    }
}
