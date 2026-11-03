package com.photopilot.integration;


import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 14 (zero-based): {Adapter target; runtime model port}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface LLMClient {

    /**
     * Future request/response boundary: context carries messages, verified evidence and approved images;
     * ModelReply represents untrusted structured output, tool calls and clarification questions.
     * Provider implementations must return explicit failures until integration is implemented.
     * Credentials belong to injected configuration, never to request context or response data.
     */
    public Result<ModelReply> complete(AgentContext context);
}
