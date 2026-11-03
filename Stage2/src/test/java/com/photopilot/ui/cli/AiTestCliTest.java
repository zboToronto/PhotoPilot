package com.photopilot.ui.cli;

import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.config.LLMConfiguration;
import com.photopilot.service.LLMDiagnosticService;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real CLI and bootstrap wiring against a loopback Gemini stand-in; no live provider request. */
class AiTestCliTest {
    private static final String KEY = "offline-cli-test-key";
    @TempDir Path temporary;
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"2 + 2 = 4.\"}]}}]}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private String run(String key, String script) {
        var environment = new HashMap<String, String>(Map.of(
            "PHOTOPILOT_DATA_DIR", temporary.resolve("data").toString(),
            "PHOTOPILOT_CONFIG_DIR", temporary.resolve("config").toString(),
            "GEMINI_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta",
            "GEMINI_MODEL", "test-model"));
        environment.put("LLM_API_KEY", key);
        try (var bootstrap = new ApplicationBootstrap(environment)) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(), new Scanner(script),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), bootstrap.localization());
            bootstrap.localization().useLanguage("en");
            cli.run(new String[0]);
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertFalse(output.contains(KEY), "API key must never be printed");
            return output;
        }
    }

    @Test void aiTestIsRegisteredInHelpAndPrintsTheRealResponseThenKeepsRunning() {
        String output = run(KEY, "help\nai-test\nstatus\nexit\n");
        assertTrue(output.contains("ai-test: diagnostic Gemini connection smoke test"));
        assertTrue(output.contains("[INFO] Checking Gemini API configuration..."));
        assertTrue(output.contains("[INFO] Sending test request to Gemini (model test-model)..."));
        assertTrue(output.contains("[AI Response]" + System.lineSeparator() + "2 + 2 = 4."));
        assertTrue(output.contains("[SUCCESS] Gemini API connection successful."));
        assertTrue(output.contains("F11 "), "CLI must keep accepting commands after ai-test");
        assertTrue(output.endsWith("PhotoPilot closed." + System.lineSeparator()));
        assertEquals(1, requests.get());
    }

    @Test void failuresAreReportedWithoutTerminatingTheCli() {
        String placeholder = run("YOUR_LOCAL_API_KEY_HERE", "ai-test\nstatus\nexit\n");
        assertTrue(placeholder.contains("[ERROR] LLM_API_KEY still contains the placeholder value."));
        assertFalse(placeholder.contains("[SUCCESS]"));
        assertTrue(placeholder.contains("F11 "));
        assertEquals(0, requests.get());

        status = 429; body = "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}";
        String limited = run(KEY, "ai-test\nexit\n");
        assertTrue(limited.contains("[ERROR] Gemini rate limit or quota exceeded (HTTP 429)."));
        assertTrue(limited.endsWith("PhotoPilot closed." + System.lineSeparator()));
    }

    @Test void missingOrEmptyKeyIsCaughtBeforeAnyModelCall() throws Exception {
        var calls = new AtomicInteger();
        java.nio.file.Files.writeString(temporary.resolve(".env"), "LLM_API_KEY=\n");
        for (var configuration : new LLMConfiguration[] {LLMConfiguration.load(Map.of(), temporary.resolve("absent")),
                LLMConfiguration.load(Map.of(), temporary)}) {
            var service = new LLMDiagnosticService(context -> { calls.incrementAndGet(); return Result.failure("unexpected"); },
                configuration, "test-model");
            assertTrue(service.checkConfiguration().hasError("ai.error.keyMissing"));
            assertTrue(service.sendTestPrompt().hasError("ai.error.keyMissing"));
        }
        assertEquals(0, calls.get());
    }
}
