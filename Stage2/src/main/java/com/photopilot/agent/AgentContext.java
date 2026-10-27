package com.photopilot.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.model.PhotographySession;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 19 (zero-based): {retrieved context and verified evidence}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class AgentContext {
    private UUID sessionId;
    private List<String> messages;
    private String evidenceJson;
    private List<PhotographySession> history;
    private Optional<SanitizedImage> approvedImage;

    public AgentContext(UUID sessionId, List<String> messages, String evidenceJson, List<PhotographySession> history, Optional<SanitizedImage> approvedImage) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.messages = List.copyOf(messages);
        this.evidenceJson = Objects.requireNonNull(evidenceJson, "evidenceJson");
        this.history = List.copyOf(history);
        this.approvedImage = Objects.requireNonNull(approvedImage, "approvedImage");
    }

    public void appendToolResult(ToolCall call, String result) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: AgentContext.appendToolResult");
    }
}
