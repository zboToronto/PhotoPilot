package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 21 (zero-based): {untrusted until schema and permission checks}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ToolCall {
    private String toolName;
    private String argumentsJson;

    public ToolCall(String toolName, String argumentsJson) {
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.argumentsJson = Objects.requireNonNull(argumentsJson, "argumentsJson");
    }

    public Result<ToolCall> validateAgainst(String schema) {
        return Result.failure("NOT IMPLEMENTED: ToolCall.validateAgainst");
    }
}
