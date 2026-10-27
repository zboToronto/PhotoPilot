package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.service.EnvironmentService;

/**
 * Stage 1 UML element 96 (zero-based): {AgentTool; invokes retrieve(), not assess()}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EnvironmentTool implements AgentTool {
    private EnvironmentService environment;

    public EnvironmentTool(EnvironmentService environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public String name() {
        return "environment";
    }

    public String inputSchema() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: EnvironmentTool.inputSchema");
    }

    public Result<String> execute(String argumentsJson) {
        return Result.failure("NOT IMPLEMENTED: EnvironmentTool.execute");
    }
}
