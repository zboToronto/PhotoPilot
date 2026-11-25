package com.photopilot.integration;

import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.progress.*;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The single, bounded retry layer at the model port: only HTTP 5xx and 429 are retried, with doubling waits, every
 * attempt is visible as progress, the last failure is returned unchanged and nothing is ever substituted.
 */
class RetryingLLMClientTest {
    @TempDir Path root;
    private static final AgentContext CONTEXT = new AgentContext(UUID.randomUUID(), List.of("question"), "{}", List.of(), Optional.empty());
    private static Result<ModelReply> ok(String text) { return Result.success(new ModelReply(Optional.of(text), List.of(), List.of())); }

    private record Scripted(Deque<Result<ModelReply>> replies, List<Duration> pauses, LLMClient client) { }
    private static Scripted scripted(int retries, List<Result<ModelReply>> replies) {
        var queue = new ArrayDeque<>(replies); var pauses = new ArrayList<Duration>();
        var client = new RetryingLLMClient(context -> queue.isEmpty() ? Result.failureKey("unexpected") : queue.poll(), retries, Duration.ofMillis(800), pauses::add);
        return new Scripted(queue, pauses, client);
    }

    @Test void transientAnswersAreRetriedWithDoublingWaitsAndEveryAttemptIsVisible() {
        var script = scripted(2, List.of(Result.failureKey("ai.error.serviceUnavailable", "503"), Result.failureKey("ai.error.rateLimited"), ok("answer")));
        var events = new ArrayList<ExecutionProgressEvent>();
        var context = OperationContext.create("F03", "requirementParse", List.of("ai.wait"), true).listen(events::add);
        var reply = context.run(() -> script.client().complete(CONTEXT));
        assertTrue(reply.isSuccess());
        assertEquals("answer", reply.value().orElseThrow().structuredOutput().orElseThrow());
        assertTrue(script.replies().isEmpty(), "three attempts in total");
        assertEquals(List.of(Duration.ofMillis(800), Duration.ofMillis(1600)), script.pauses());
        var retries = events.stream().filter(e -> e.status() == OperationStatus.RETRYING).map(e -> e.current().orElseThrow().arguments()).distinct().toList();
        assertEquals(List.of(List.of("503", 1, 2, "1"), List.of("429", 2, 2, "2")), retries, "HTTP status, retry number, retries allowed, wait (s)");
        assertEquals(3, events.getLast().attempt());
        assertEquals(3, events.getLast().maxAttempts());
    }

    @Test void nonTransientFailuresAreReturnedAtOnceAndExhaustedRetriesKeepTheLastFailure() {
        for (String key : List.of("ai.error.timeout", "ai.error.network", "ai.error.authentication", "ai.error.keyMissing",
                "ai.error.invalidResponse", "ai.error.request", "ai.error.blocked")) {
            var script = scripted(2, List.of(Result.failureKey(key, "x", "y"), ok("never")));
            var reply = script.client().complete(CONTEXT);
            assertTrue(reply.hasError(key), key);
            assertEquals(1, script.replies().size(), key + " is not retried (a timeout already waited; others cannot succeed unchanged)");
            assertTrue(script.pauses().isEmpty());
        }
        var exhausted = scripted(1, List.of(Result.failureKey("ai.error.serviceUnavailable", "502"), Result.failureKey("ai.error.serviceUnavailable", "503"), ok("never")));
        var reply = exhausted.client().complete(CONTEXT);
        assertFalse(reply.isSuccess(), "no fabricated answer after the retries are used up");
        assertEquals(List.of("503"), reply.messages().getFirst().arguments(), "the last provider answer is reported unchanged");
        assertEquals(1, exhausted.replies().size(), "GEMINI_RETRIES bounds the attempts");

        var off = scripted(0, List.of(Result.failureKey("ai.error.serviceUnavailable", "503"), ok("never")));
        assertTrue(off.client().complete(CONTEXT).hasError("ai.error.serviceUnavailable"));
        assertTrue(off.pauses().isEmpty());
    }

    @Test void aCancelledOperationStopsDuringTheWaitAndSendsNothingMore() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var client = new RetryingLLMClient(context -> { attempts.incrementAndGet(); return Result.failureKey("ai.error.serviceUnavailable", "503"); },
            2, Duration.ofSeconds(30));
        var operation = OperationContext.create("F10", "planGenerate", List.of(), true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var reply = pool.submit(() -> operation.run(() -> client.complete(CONTEXT)));
            for (int i = 0; i < 200 && operation.status() != OperationStatus.RETRYING; i++) Thread.sleep(10);
            assertEquals(OperationStatus.RETRYING, operation.status());
            long started = System.nanoTime();
            assertTrue(operation.cancel());
            var result = reply.get(5, TimeUnit.SECONDS);
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "the 30 s backoff ends at once");
            assertTrue(result.hasError("progress.cancelled"));
            assertEquals(1, attempts.get(), "no request after the cancel");
        } finally { pool.shutdownNow(); }
    }

    @Test void theRetryCountIsConfigurableAndBounded() {
        assertEquals(RetryingLLMClient.DEFAULT_RETRIES, RetryingLLMClient.retries(Map.of()));
        assertEquals(0, RetryingLLMClient.retries(Map.of("GEMINI_RETRIES", "0")));
        assertEquals(3, RetryingLLMClient.retries(Map.of("GEMINI_RETRIES", " 3 ")));
        for (String invalid : List.of("4", "-1", "two")) assertThrows(IllegalArgumentException.class, () -> RetryingLLMClient.retries(Map.of("GEMINI_RETRIES", invalid)));
        assertThrows(IllegalArgumentException.class, () -> new RetryingLLMClient(context -> ok("x"), 4, Duration.ZERO));
    }

    /** Real bootstrap wiring against loopback Gemini: feature requests retry a 503, the ai-test diagnostic never does. */
    @Test void featureRequestsRetryAndTheConnectionDiagnosticSendsExactlyOnce() throws Exception {
        try (var gemini = new FakeGemini()) {
            var environment = new HashMap<>(gemini.environment(root));
            environment.put("GEMINI_RETRIES", "1");
            try (var app = new ApplicationBootstrap(environment)) {
                var session = app.facade().sessions().create("Retry wiring", java.time.LocalDate.now().plusDays(3)).value().orElseThrow();
                gemini.status(503, "").reply(FakeGemini.AIRSHOW);
                var events = new ArrayList<ExecutionProgressEvent>();
                var operation = com.photopilot.application.OperationKind.REQUIREMENT_PARSE.start(events::add);
                var parsed = operation.run(() -> app.facade().parse(session.id(), FakeGemini.AIRSHOW_REQUEST));
                assertTrue(parsed.isSuccess(), () -> parsed.errors().toString());
                assertEquals(2, gemini.requests.size(), "one retry after HTTP 503");
                assertTrue(events.stream().anyMatch(e -> e.status() == OperationStatus.RETRYING));

                gemini.requests.clear(); gemini.status(503, "").reply("never used");
                var diagnostic = app.facade().aiDiagnostics().sendTestPrompt();
                assertFalse(diagnostic.isSuccess());
                assertEquals(1, gemini.requests.size(), "the connection check reports the first answer as it is");
            }
        }
    }
}
