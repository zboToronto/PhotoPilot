package com.photopilot.agent;


import com.photopilot.common.Result;

/**
 * Stage 1 UML element 17 (zero-based): {tool contract; all inputs validated}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface AgentTool {

    public String name();

    public String inputSchema();

    public Result<String> execute(String argumentsJson);
}
