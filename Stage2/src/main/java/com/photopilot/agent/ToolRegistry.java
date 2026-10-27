package com.photopilot.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 16 (zero-based): {validated allowlist; no arbitrary execution}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ToolRegistry {
    private Map<String, AgentTool> allowedTools;

    public ToolRegistry(Map<String, AgentTool> allowedTools) {
        this.allowedTools = new LinkedHashMap<>(Objects.requireNonNull(allowedTools));
    }

    public Result<String> execute(ToolCall call) {
        return Result.failure("NOT IMPLEMENTED: ToolRegistry.execute");
    }

    public Map<String, String> schemas() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ToolRegistry.schemas");
    }

    public void register(AgentTool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = Objects.requireNonNull(tool.name(), "tool.name");
        if (name.isBlank() || allowedTools.putIfAbsent(name, tool) != null) {
            throw new IllegalArgumentException("Blank or duplicate tool name: " + name);
        }
    }
}
