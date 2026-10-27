package com.photopilot.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 15 (zero-based): {Adapter; configurable Gemini runtime}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GeminiAdapter implements LLMClient {
    private URI endpoint;
    private String modelName;
    private HttpClient http;
    private Duration timeout;

    public GeminiAdapter(URI endpoint, String modelName, HttpClient http, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.http = Objects.requireNonNull(http, "http");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public Result<ModelReply> complete(AgentContext context) {
        return Result.failure("NOT IMPLEMENTED: GeminiAdapter.complete");
    }
}
