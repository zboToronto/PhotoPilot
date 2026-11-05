package com.photopilot.integration;


import com.photopilot.agent.AgentContext;
import com.photopilot.agent.ModelReply;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 14 (zero-based): {Adapter target; runtime model port}
 * Implemented by GeminiAdapter and decorated by LanguageAwareLLMClient.
 */
public interface LLMClient {

    /**
     * Request/response boundary: context carries messages, verified evidence and approved images;
     * ModelReply represents untrusted structured output that callers must validate.
     * Provider and transport failures are explicit Result errors, never fabricated replies.
     * Credentials belong to injected configuration, never to request context or response data.
     */
    public Result<ModelReply> complete(AgentContext context);
}
