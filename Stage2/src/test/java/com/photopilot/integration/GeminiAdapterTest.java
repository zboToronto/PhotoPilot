package com.photopilot.integration;

import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;
import com.photopilot.config.LLMConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Offline: a loopback HttpServer stands in for Gemini; no live provider request is made. */
class GeminiAdapterTest {
    private static final String KEY = "offline-gemini-test-key";
    private HttpServer server;
    private HttpClient http;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> path = new AtomicReference<>(), query = new AtomicReference<>(),
        sentKey = new AtomicReference<>(), sentBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";
    private volatile long delayMillis;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getRawQuery());
            sentKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        http = HttpClient.newHttpClient();
    }

    @AfterEach void stop() { server.stop(0); http.close(); }

    private GeminiAdapter adapter(LLMConfiguration configuration, Duration timeout) {
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/");
        return new GeminiAdapter(endpoint, "test-model", http, timeout, configuration);
    }
    private static LLMConfiguration configured() { return LLMConfiguration.load(Map.of("LLM_API_KEY", KEY), Path.of("unused")); }
    private Result<ModelReply> send() { return send(configured()); }
    private Result<ModelReply> send(LLMConfiguration configuration) {
        return adapter(configuration, Duration.ofSeconds(5)).complete(new AgentContext(UUID.randomUUID(),
            List.of("What is 2 + 2? Answer briefly."), "{}", List.of(), Optional.empty()).withResponseLanguageInstruction("Use English."));
    }
    private void respond(int code, String json) { status = code; body = json; }
    private static void assertFailure(Result<?> result, String key) {
        assertFalse(result.isSuccess());
        assertTrue(result.hasError(key), () -> "expected " + key + " but got " + result.errors());
        assertFalse(result.errors().toString().contains(KEY), "API key leaked into an error message");
    }

    @Test void successfulResponseReturnsGeneratedTextAndSendsKeyOnlyInHeader() {
        respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"internal\",\"thought\":true},{\"text\":\" 2 + 2 = 4. \"}]}}]}");
        var result = send();
        assertTrue(result.isSuccess(), result.errors().toString());
        assertEquals("2 + 2 = 4.", result.value().orElseThrow().structuredOutput().orElseThrow());
        assertEquals("/v1beta/models/test-model:generateContent", path.get());
        assertNull(query.get(), "the key must never be placed in the URL");
        assertEquals(KEY, sentKey.get());
        assertTrue(sentBody.get().contains("What is 2 + 2? Answer briefly."));
        assertTrue(sentBody.get().contains("\"systemInstruction\""));
        assertFalse(sentBody.get().contains(KEY));
    }

    @Test void unusableLocalConfigurationFailsWithoutAnyRequest() {
        var missing = LLMConfiguration.load(Map.of(), Path.of("no-such-directory"));
        assertFailure(send(missing), "ai.error.keyMissing");
        assertFailure(send(LLMConfiguration.load(Map.of("LLM_API_KEY", "YOUR_LOCAL_API_KEY_HERE"), Path.of("unused"))), "ai.error.keyPlaceholder");
        assertFailure(send(LLMConfiguration.load(Map.of("LLM_API_KEY", "bad key"), Path.of("unused"))), "ai.error.keyInvalid");
        assertFailure(adapter(configured(), Duration.ofSeconds(5)).complete(null), "ai.error.emptyPrompt");
        assertFailure(adapter(configured(), Duration.ofSeconds(5)).complete(new AgentContext(UUID.randomUUID(),
            List.of(" "), "{}", List.of(), Optional.empty())), "ai.error.emptyPrompt");
        assertEquals(0, requests.get());
    }

    @Test void providerErrorsMapToClearFailures() {
        respond(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}");
        assertFailure(send(), "ai.error.authentication");
        respond(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");
        assertFailure(send(), "ai.error.authentication");
        respond(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
        assertFailure(send(), "ai.error.rateLimited");
        respond(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}");
        assertFailure(send(), "ai.error.modelNotFound");
        respond(503, "");
        assertFailure(send(), "ai.error.serviceUnavailable");
        respond(400, "{\"error\":{\"message\":\"echo " + KEY + "\",\"status\":\"FAILED_PRECONDITION\"}}");
        var echoed = send();
        assertFailure(echoed, "ai.error.request");
        assertTrue(echoed.errors().toString().contains("<redacted>"));
    }

    @Test void malformedEmptyOrBlockedResponsesAreNotTreatedAsSuccess() {
        respond(200, "not json");
        assertFailure(send(), "ai.error.invalidResponse");
        respond(200, "{\"candidates\":[]}");
        assertFailure(send(), "ai.error.emptyResponse");
        respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"   \"}]},\"finishReason\":\"MAX_TOKENS\"}]}");
        assertFailure(send(), "ai.error.emptyResponse");
        respond(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertFailure(send(), "ai.error.blocked");
    }

    @Test void approvedSanitizedImageIsSentInlineAndReleasedImagesAreRefusedLocally(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var privacy = new com.photopilot.ui.privacy.ImagePrivacyBoundary();
        var review = privacy.open(com.photopilot.gearimport.ImportFixtures.png(directory, "camera.png", 300, 200),
            com.photopilot.ui.privacy.ImageReview.Kind.PHOTO).value().orElseThrow();
        var image = privacy.approveRedacted(privacy.sanitize(review).value().orElseThrow(), true).value().orElseThrow();
        byte[] expected = image.approvedBytes().orElseThrow();
        respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        var context = new AgentContext(UUID.randomUUID(), List.of("Identify the camera."), "{}", List.of(), Optional.of(image));
        assertTrue(adapter(configured(), Duration.ofSeconds(5)).complete(context).isSuccess());
        var inline = com.google.gson.JsonParser.parseString(sentBody.get()).getAsJsonObject().getAsJsonArray("contents").get(0).getAsJsonObject()
            .getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("inlineData");
        assertEquals("image/png", inline.get("mimeType").getAsString());
        assertArrayEquals(expected, java.util.Base64.getDecoder().decode(inline.get("data").getAsString()));
        assertEquals(1, requests.get());
        privacy.discard(image);
        assertFailure(adapter(configured(), Duration.ofSeconds(5)).complete(context), "ai.error.imageNotApproved");
        assertEquals(1, requests.get(), "a released image never reaches the provider");
    }

    @Test void timeoutAndUnreachableHostAreReported() throws Exception {
        respond(200, "{}");
        delayMillis = 2_000;
        var timedOut = adapter(configured(), Duration.ofMillis(200)).complete(new AgentContext(UUID.randomUUID(),
            List.of("ping"), "{}", List.of(), Optional.empty()));
        assertFailure(timedOut, "ai.error.timeout");
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        var unreachable = new GeminiAdapter(URI.create("http://127.0.0.1:" + closedPort), "test-model", http,
            Duration.ofSeconds(5), configured()).complete(new AgentContext(UUID.randomUUID(), List.of("ping"), "{}", List.of(), Optional.empty()));
        assertFailure(unreachable, "ai.error.network");
    }
}
