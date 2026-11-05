package com.photopilot.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 16 (zero-based): {validated allowlist; no arbitrary execution}
 * Only registered tools can run, and a caller may narrow that further to the tools one task permits. Arguments are
 * schema-checked before the tool's own strict validation. Tools are fixed application code (deterministic
 * calculators and read-only providers); nothing here reaches the filesystem, processes or a shell.
 */
public class ToolRegistry {
    private Map<String, AgentTool> allowedTools;

    public ToolRegistry(Map<String, AgentTool> allowedTools) {
        this.allowedTools = new LinkedHashMap<>(Objects.requireNonNull(allowedTools));
    }

    public Result<String> execute(ToolCall call) {
        return execute(call, allowedTools.keySet());
    }

    /** Runs a registered tool only if {@code permitted} also allows it for the current task. */
    public Result<String> execute(ToolCall call, Collection<String> permitted) {
        if (call == null) return Result.failureKey("ai.error.unknownTool", "");
        AgentTool tool = allowedTools.get(call.toolName());
        if (tool == null || permitted == null || !permitted.contains(call.toolName())) return Result.failureKey("ai.error.unknownTool", call.toolName());
        var checked = call.validateAgainst(tool.inputSchema());
        if (!checked.isSuccess()) return Result.failure(checked);
        var result = tool.execute(call.argumentsJson());
        return result == null ? Result.failureKey("ai.error.toolFailed", call.toolName(), "no result") : result;
    }

    /** Input schemas of the registered tools (every registered tool publishes one; see register). */
    public Map<String, String> schemas() {
        var schemas = new LinkedHashMap<String, String>();
        allowedTools.forEach((name, tool) -> schemas.put(name, tool.inputSchema()));
        return schemas;
    }

    public boolean has(String name) { return allowedTools.containsKey(name); }

    /** Registers a complete tool. A tool without an input schema cannot be offered or checked, so it is refused here. */
    public void register(AgentTool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = Objects.requireNonNull(tool.name(), "tool.name");
        String schema;
        try { schema = tool.inputSchema(); }
        catch (UnsupportedOperationException incomplete) { throw new IllegalArgumentException("Tool " + name + " publishes no input schema and cannot be registered", incomplete); }
        if (schema == null || schema.isBlank()) throw new IllegalArgumentException("Tool " + name + " publishes no input schema and cannot be registered");
        if (name.isBlank() || allowedTools.putIfAbsent(name, tool) != null) {
            throw new IllegalArgumentException("Blank or duplicate tool name: " + name);
        }
    }
}
