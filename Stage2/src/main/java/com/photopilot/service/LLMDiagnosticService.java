package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.agent.AgentContext;
import com.photopilot.common.Result;
import com.photopilot.config.LLMConfiguration;
import com.photopilot.integration.GeminiAdapter;
import com.photopilot.integration.LLMClient;

/**
 * Diagnostic-only connection smoke test for the shared model port (CLI `ai-test`).
 * Sends one fixed prompt; it is not a PhotoPilot AI feature and uses no session data, evidence or images.
 */
public final class LLMDiagnosticService {
    public static final String TEST_PROMPT = "What is 2 + 2? Answer briefly.";

    private final LLMClient model;
    private final LLMConfiguration configuration;
    private final String modelName;

    public LLMDiagnosticService(LLMClient model, LLMConfiguration configuration, String modelName) {
        this.model = Objects.requireNonNull(model, "model");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
    }

    public String modelName() { return modelName; }

    /** Offline check of the local key configuration; never returns or displays the key. */
    public Result<LLMConfiguration.KeyStatus> checkConfiguration() {
        return configuration.isApiKeyConfigured() ? Result.success(configuration.keyStatus())
            : Result.failureKey(GeminiAdapter.keyStatusError(configuration.keyStatus()));
    }

    /** One real provider request; succeeds only with non-empty generated text. */
    public Result<String> sendTestPrompt() {
        Result<LLMConfiguration.KeyStatus> configured = checkConfiguration();
        if (!configured.isSuccess()) return Result.failure(configured);
        var reply = model.complete(new AgentContext(UUID.randomUUID(), List.of(TEST_PROMPT), "{}", List.of(), Optional.empty()));
        if (!reply.isSuccess()) return Result.failure(reply);
        Optional<String> text = reply.value().orElseThrow().structuredOutput().map(String::strip).filter(value -> !value.isEmpty());
        return text.isPresent() ? Result.success(text.get()) : Result.failureKey("ai.error.emptyResponse");
    }
}
