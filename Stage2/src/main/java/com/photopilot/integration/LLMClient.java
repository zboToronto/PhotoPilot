package com.photopilot.integration;


import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 14 (zero-based): {Adapter target; runtime model port}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface LLMClient {

    public Result<ModelReply> complete(AgentContext context);
}
