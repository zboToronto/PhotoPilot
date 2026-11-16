package com.photopilot.integration;

import com.photopilot.agent.AgentContext;
import com.photopilot.common.Result;
import com.photopilot.config.LLMConfiguration;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.GeoLocation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline: a loopback server that sends response headers and then stalls or trickles the body, or announces or streams an
 * oversized body, for all four HTTP adapters of this package (request timeout 1 s; {@link BoundedHttp} adds its grace
 * period). No live provider request is made.
 */
class HttpAdapterLimitsTest {
    private static final String KEY = "offline-limits-test-key";
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    /** More than every adapter's body limit (8 MB at most). */
    private static final int OVERSIZED = 16 * 1024 * 1024;
    private final CountDownLatch release = new CountDownLatch(1), disconnected = new CountDownLatch(1);
    private ExecutorService handlers;
    private HttpServer server;
    private HttpClient http;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        // Headers with a small Content-Length, one byte of body, then nothing until the test ends.
        server.createContext("/stall/", exchange -> { headers(exchange, 200, 1_000); write(exchange, new byte[]{'{'}); hold(); exchange.close(); });
        // A body that trickles one byte every 250 ms and so never ends; notes when the client has closed the connection.
        server.createContext("/trickle/", exchange -> {
            headers(exchange, 200, 1_000_000);
            try { do write(exchange, new byte[]{' '}); while (!release.await(250, TimeUnit.MILLISECONDS)); }
            catch (IOException closedByClient) { disconnected.countDown(); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        // Content-Length above every limit; the body never comes.
        server.createContext("/announced/", exchange -> { headers(exchange, 200, 50_000_000L); write(exchange, new byte[]{'{'}); hold(); exchange.close(); });
        // Chunked (no Content-Length): more than every limit, then the body never ends.
        server.createContext("/streamed/", exchange -> { headers(exchange, 200, 0); stream(exchange); hold(); exchange.close(); });
        // An oversized error page that does end: the status keeps its own error.
        server.createContext("/error/", exchange -> { headers(exchange, 503, 0); stream(exchange); exchange.close(); });
        server.createContext("/missing/", exchange -> { headers(exchange, 404, -1); exchange.close(); });
        server.start();
        http = HttpClient.newHttpClient();
    }

    @AfterEach void stop() { release.countDown(); server.stop(0); handlers.shutdownNow(); http.close(); }

    private static void headers(HttpExchange exchange, int status, long length) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(status, length);
    }
    private static void write(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
    }
    private static void stream(HttpExchange exchange) {
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) ' ');
        try { for (int sent = 0; sent < OVERSIZED; sent += chunk.length) write(exchange, chunk); }
        catch (IOException closedByClient) { /* the client stopped reading */ }
    }
    private void hold() {
        try { release.await(2, TimeUnit.MINUTES); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }

    /** One call per adapter, all against {@code path} on the loopback server; the second segment selects the behaviour. */
    private Map<String, Callable<Result<?>>> calls(String path) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        Duration timeout = Duration.ofSeconds(1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var configured = LLMConfiguration.load(Map.of("LLM_API_KEY", KEY), Path.of("unused"));
        var calls = new LinkedHashMap<String, Callable<Result<?>>>();
        calls.put("Gemini", () -> new GeminiAdapter(URI.create(base + "v1beta"), "test-model", http, timeout, configured)
            .complete(new AgentContext(UUID.randomUUID(), List.of("ping"), "{}", List.of(), Optional.empty())));
        // The sun service answers 404 at once, so only the weather request meets the server's behaviour.
        URI sun = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/missing/json");
        calls.put("Environment", () -> new EnvironmentApiAdapter(URI.create(base + "v1/forecast"), sun, http, timeout, clock)
            .fetch(new GeoLocation("Test place", 43.6275, -79.3961, false, false, Optional.empty()), LocalDate.ofInstant(NOW, ZoneOffset.UTC)));
        calls.put("Geocoding", () -> new GeocodingAdapter(URI.create(base + "v1/search"), http, timeout).search("Toronto"));
        // No brand: only the Wikipedia source is asked.
        calls.put("Catalog", () -> new EquipmentCatalogAdapter(URI.create(base + "w/api.php"), URI.create(base + "db/"), http, timeout, clock)
            .search(EquipmentType.CAMERA, "", "Alpha 1", ""));
        return calls;
    }

    private record Outcome(Result<?> result, Duration elapsed) { }

    private Map<String, Outcome> concurrently(String path, Duration limit) { return concurrently(calls(path), limit); }

    /** Runs the calls at once; fails, naming the blocked adapters, if they have not all returned within {@code limit}. */
    private Map<String, Outcome> concurrently(Map<String, Callable<Result<?>>> calls, Duration limit) {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var futures = new LinkedHashMap<String, Future<Outcome>>();
            calls.forEach((name, call) -> futures.put(name, pool.submit(() -> {
                long started = System.nanoTime();
                Result<?> result = call.call();
                return new Outcome(result, Duration.ofNanos(System.nanoTime() - started));
            })));
            return assertTimeoutPreemptively(limit, () -> {
                var outcomes = new LinkedHashMap<String, Outcome>();
                for (var future : futures.entrySet()) outcomes.put(future.getKey(), future.getValue().get());
                return outcomes;
            }, () -> "still blocked: " + futures.entrySet().stream().filter(f -> !f.getValue().isDone()).map(Map.Entry::getKey).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertFailure(Outcome outcome, String key) {
        assertFalse(outcome.result().isSuccess());
        assertTrue(outcome.result().hasError(key), () -> "expected " + key + " but got " + outcome.result().errors());
        assertFalse(outcome.result().errors().toString().contains(KEY), "API key leaked into an error message");
    }

    @Test void aBodyThatStallsAfterTheHeadersEndsAsATimeoutInsteadOfBlockingForever() throws Exception {
        var calls = calls("/stall/");
        URI trickle = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/trickle/v1/search");
        calls.put("Geocoding, trickling body", () -> new GeocodingAdapter(trickle, http, Duration.ofSeconds(1)).search("Toronto"));
        var outcomes = concurrently(calls, Duration.ofSeconds(45));
        assertFailure(outcomes.get("Gemini"), "ai.error.timeout");
        assertFailure(outcomes.get("Environment"), "env.error.timeout");
        assertFailure(outcomes.get("Geocoding"), "env.error.timeout");
        assertFailure(outcomes.get("Catalog"), "import.error.catalogTimeout");
        assertFailure(outcomes.get("Geocoding, trickling body"), "env.error.timeout");
        outcomes.forEach((name, outcome) -> assertTrue(outcome.elapsed().compareTo(Duration.ofSeconds(1)) >= 0, name + " gave up before its request timeout"));
        assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the abandoned exchange is cancelled and its connection closed");
    }

    @Test void anInterruptEndsTheWaitAndKeepsTheInterruptFlag() throws Exception {
        URI stall = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/stall/v1/search");
        var result = new CompletableFuture<Result<?>>();
        var flagged = new CompletableFuture<Boolean>();
        Thread caller = new Thread(() -> {
            result.complete(new GeocodingAdapter(stall, http, Duration.ofSeconds(1)).search("Toronto"));
            flagged.complete(Thread.currentThread().isInterrupted());
        });
        caller.start();
        Thread.sleep(300);
        caller.interrupt();
        assertTrue(result.get(5, TimeUnit.SECONDS).hasError("env.error.interrupted"), () -> result.join().errors().toString());
        assertTrue(flagged.get(5, TimeUnit.SECONDS), "the caller still sees its interrupt");
    }

    @Test void anAnnouncedOversizedBodyIsRefusedWithoutWaitingForIt() {
        // Well below the request timeout plus grace: the Content-Length alone decides.
        var outcomes = concurrently("/announced/", Duration.ofSeconds(10));
        assertFailure(outcomes.get("Gemini"), "ai.error.invalidResponse");
        assertFailure(outcomes.get("Environment"), "env.error.format");
        assertFailure(outcomes.get("Geocoding"), "env.error.format");
        assertFailure(outcomes.get("Catalog"), "import.error.catalogResponse");
    }

    @Test void aStreamedBodyStopsOnceItExceedsTheLimit() {
        var outcomes = concurrently("/streamed/", Duration.ofSeconds(10));
        assertFailure(outcomes.get("Gemini"), "ai.error.invalidResponse");
        assertFailure(outcomes.get("Environment"), "env.error.format");
        assertFailure(outcomes.get("Geocoding"), "env.error.format");
        assertFailure(outcomes.get("Catalog"), "import.error.catalogResponse");
    }

    @Test void anOversizedErrorPageKeepsTheErrorOfItsStatus() {
        var outcomes = concurrently("/error/", Duration.ofSeconds(30));
        assertFailure(outcomes.get("Gemini"), "ai.error.serviceUnavailable");
        assertFailure(outcomes.get("Environment"), "env.error.unavailable");
        assertFailure(outcomes.get("Geocoding"), "env.error.unavailable");
        assertFailure(outcomes.get("Catalog"), "import.error.catalogHttp");
    }
}
