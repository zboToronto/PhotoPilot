package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.integration.LLMClient;

/**
 * Stage 1 UML element 13 (zero-based): {AI; generic method &lt;T&gt; run}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PhotoPilotAgent {
    private LLMClient model;
    private ToolRegistry tools;
    private HistoryRetriever history;
    private int maxSteps;

    public PhotoPilotAgent(LLMClient model, ToolRegistry tools, HistoryRetriever history, int maxSteps) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.history = Objects.requireNonNull(history, "history");
        this.maxSteps = maxSteps;
    }

    public <T> Result<T> run(String task, AgentContext context, Class<T> type) {
        return Result.failure("NOT IMPLEMENTED: PhotoPilotAgent.run");
    }

    private Result<ToolCall> selectTool(ModelReply reply) {
        return Result.failure("NOT IMPLEMENTED: PhotoPilotAgent.selectTool");
    }

    private <T> Result<T> validateOutput(String json, Class<T> type) {
        return Result.failure("NOT IMPLEMENTED: PhotoPilotAgent.validateOutput");
    }
}
