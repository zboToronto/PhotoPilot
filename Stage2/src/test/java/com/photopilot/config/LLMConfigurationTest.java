package com.photopilot.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.photopilot.config.LLMConfiguration.KeyStatus.*;

class LLMConfigurationTest {
    @TempDir Path root;

    private LLMConfiguration load() { return LLMConfiguration.load(Map.of(), root); }
    private void dotenv(String content) throws Exception { Files.writeString(root.resolve(".env"), content); }

    @Test void environmentWinsWithoutReadingLocalFile() throws Exception {
        Files.createDirectory(root.resolve(".env"));
        var config = LLMConfiguration.load(Map.of("LLM_API_KEY", "offline-environment-key"), root);
        assertEquals(CONFIGURED, config.keyStatus());
        assertEquals("offline-environment-key", config.requireApiKey());
    }

    @Test void absentOrBlankEnvironmentFallsBackToUtf8Dotenv() throws Exception {
        dotenv("\uFEFF# Local configuration / 本地配置\nIGNORED=value\n LLM_API_KEY = 'offline-local-key' \n");
        assertEquals("offline-local-key", load().requireApiKey());
        assertEquals("offline-local-key", LLMConfiguration.load(Map.of("LLM_API_KEY", "  "), root).requireApiKey());
        dotenv("LLM_API_KEY=\"offline-double-quoted-key\"\n");
        assertEquals("offline-double-quoted-key", load().requireApiKey());
        dotenv("LLM_API_KEY=AQ.offline-dotted_key\n");
        assertEquals("AQ.offline-dotted_key", load().requireApiKey());
    }

    @Test void invalidNonblankEnvironmentNeverFallsBack() throws Exception {
        dotenv("LLM_API_KEY=offline-local-key\n");
        assertEquals(PLACEHOLDER, LLMConfiguration.load(Map.of("LLM_API_KEY", "YOUR_LOCAL_API_KEY_HERE"), root).keyStatus());
        assertEquals(INVALID, LLMConfiguration.load(Map.of("LLM_API_KEY", "invalid key"), root).keyStatus());
    }

    @Test void missingBlankAndPlaceholderStayUnconfigured() throws Exception {
        assertEquals(MISSING, load().keyStatus());
        for (String value : new String[] {"", "LLM_API_KEY=\n", "LLM_API_KEY=\"\"\n", "OTHER=value\n"}) {
            dotenv(value);
            assertEquals(MISSING, load().keyStatus());
            assertFalse(load().isApiKeyConfigured());
        }
        dotenv("LLM_API_KEY=YOUR_LOCAL_API_KEY_HERE\n");
        assertEquals(PLACEHOLDER, load().keyStatus());
        assertThrows(IllegalStateException.class, () -> load().requireApiKey());
    }

    @Test void malformedKeysFailClosedWithoutExposingContent() throws Exception {
        for (String value : new String[] {"LLM_API_KEY", "LLM_API_KEY='private-invalid-value",
                "LLM_API_KEY=private invalid value", "LLM_API_KEY=first\nLLM_API_KEY=second",
                "LLM_API_KEY=${PRIVATE_VALUE}", "LLM_API_KEY=value # inline comment"}) {
            dotenv(value);
            var config = load();
            assertEquals(INVALID, config.keyStatus());
            var error = assertThrows(IllegalStateException.class, config::requireApiKey);
            assertEquals("LLM_API_KEY is not configured: INVALID", error.getMessage());
            assertNull(error.getCause());
            assertFalse(config.toString().contains("private"));
        }
    }

    @Test void unreadableFileDoesNotBreakStartupOrExposePaths() throws Exception {
        Files.createDirectory(root.resolve(".env"));
        var config = load();
        assertEquals(UNREADABLE_FILE, config.keyStatus());
        assertFalse(config.toString().contains(root.toString()));
        Files.delete(root.resolve(".env"));
        Files.write(root.resolve(".env"), new byte[] {(byte) 0xc3, (byte) 0x28});
        assertEquals(UNREADABLE_FILE, load().keyStatus());
    }

    @Test void repositoryAndMavenLaunchesResolveTheSameRootFile() throws Exception {
        Path stage2 = Files.createDirectory(root.resolve("Stage2"));
        Files.writeString(stage2.resolve("pom.xml"), "<project/>");
        dotenv("LLM_API_KEY=offline-root-key\n");
        Files.writeString(stage2.resolve(".env"), "LLM_API_KEY=offline-wrong-directory-key\n");
        assertEquals(root, LLMConfiguration.projectRoot(root));
        assertEquals(root, LLMConfiguration.projectRoot(stage2));
        assertEquals("offline-root-key", LLMConfiguration.load(Map.of(), LLMConfiguration.projectRoot(stage2)).requireApiKey());
    }

    @Test void diagnosticsRedactConfiguredKeysAndLegacyVariableIsNotUsed() throws Exception {
        var config = LLMConfiguration.load(Map.of("LLM_API_KEY", "offline-secret-test-key"), root);
        assertEquals("LLMConfiguration[keyStatus=CONFIGURED, apiKey=<redacted>]", config.toString());
        assertEquals(MISSING, LLMConfiguration.load(Map.of("GEMINI_API_KEY", "offline-legacy-key"), root).keyStatus());
    }
}
