package com.photopilot.integration;

import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;
import com.photopilot.progress.Progress;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded retry policy at the model port (the only retry layer for Gemini). Only transient provider answers are
 * retried: HTTP 5xx ({@code ai.error.serviceUnavailable}) and HTTP 429 ({@code ai.error.rateLimited}); neither consumes
 * tokens. Timeouts (already a long wait), network/credential/request errors and every malformed or rejected reply are
 * returned at once. Waits double from {@code initialDelay}; each attempt and wait is published as progress, and a
 * cancelled operation stops before the next attempt. The last failure is returned unchanged; nothing is substituted.
 */
public final class RetryingLLMClient implements LLMClient {
    /** Retries after the first attempt unless GEMINI_RETRIES (0-3) says otherwise. */
    public static final int DEFAULT_RETRIES = 2;
    public static final Duration DEFAULT_DELAY = Duration.ofSeconds(1);
    private static final Set<String> TRANSIENT = Set.of("ai.error.serviceUnavailable", "ai.error.rateLimited");

    /** Waits between attempts; the default sleeps, so a cancel interrupt ends the wait. */
    @FunctionalInterface public interface Pause { void sleep(Duration delay) throws InterruptedException; }

    private final LLMClient delegate;
    private final int retries;
    private final Duration initialDelay;
    private final Pause pause;

    public RetryingLLMClient(LLMClient delegate, int retries, Duration initialDelay) {
        this(delegate, retries, initialDelay, delay -> Thread.sleep(delay.toMillis()));
    }
    public RetryingLLMClient(LLMClient delegate, int retries, Duration initialDelay, Pause pause) {
        if (retries < 0 || retries > 3) throw new IllegalArgumentException("retries must be 0-3");
        this.delegate = Objects.requireNonNull(delegate); this.retries = retries;
        this.initialDelay = Objects.requireNonNull(initialDelay); this.pause = Objects.requireNonNull(pause);
    }

    public int maxAttempts() { return retries + 1; }

    @Override public Result<ModelReply> complete(AgentContext context) {
        int attempts = retries + 1;
        Duration delay = initialDelay;
        for (int attempt = 1; ; attempt++) {
            if (Progress.cancelled()) return Result.failureKey("progress.cancelled");
            if (attempts > 1) Progress.attempt(attempt, attempts);
            var reply = delegate.complete(context);
            if (reply.isSuccess() || attempt == attempts || reply.messages().stream().noneMatch(m -> TRANSIENT.contains(m.key()))) return reply;
            Progress.retrying(attempt, attempts, status(reply), delay);
            try { pause.sleep(delay); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Result.failureKey("progress.cancelled");
            }
            delay = delay.multipliedBy(2);
        }
    }

    /** The HTTP status of a transient answer, for the progress line ("503", "429"). */
    private static String status(Result<?> reply) {
        for (var message : reply.messages()) {
            if (message.key().equals("ai.error.rateLimited")) return "429";
            if (message.key().equals("ai.error.serviceUnavailable") && !message.arguments().isEmpty()) return String.valueOf(message.arguments().getFirst());
        }
        return "5xx";
    }

    /** GEMINI_RETRIES: whole number 0-3; absent means {@link #DEFAULT_RETRIES}. */
    public static int retries(java.util.Map<String, String> environment) {
        String value = environment.getOrDefault("GEMINI_RETRIES", "").strip();
        if (value.isEmpty()) return DEFAULT_RETRIES;
        try {
            int retries = Integer.parseInt(value);
            if (retries >= 0 && retries <= 3) return retries;
        } catch (NumberFormatException invalid) { /* reported below */ }
        throw new IllegalArgumentException("GEMINI_RETRIES must be a whole number from 0 to 3");
    }
}
