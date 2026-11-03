package com.photopilot.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.model.PhotographySession;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 19 (zero-based): {retrieved context and verified evidence}
 * Provider-neutral request context. Request-scoped copies add the task instruction, an optional
 * JSON Schema for structured output and the response-language policy; saved data is never rewritten.
 * Tool-result accumulation remains NOT IMPLEMENTED (F10 tool loop).
 */
public class AgentContext {
    private UUID sessionId;
    private List<String> messages;
    private String evidenceJson;
    private List<PhotographySession> history;
    private Optional<SanitizedImage> approvedImage;
    private String responseLanguageInstruction = "";
    private String taskInstruction = "";
    private String responseSchema = "";

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

    private AgentContext copy() {
        AgentContext copy = new AgentContext(sessionId, messages, evidenceJson, history, approvedImage);
        copy.responseLanguageInstruction = responseLanguageInstruction;
        copy.taskInstruction = taskInstruction;
        copy.responseSchema = responseSchema;
        return copy;
    }

    /** Request-scoped copy: never rewrite saved messages, evidence, history, or image approval. */
    public AgentContext withResponseLanguageInstruction(String instruction) {
        AgentContext copy = copy();
        copy.responseLanguageInstruction = Objects.requireNonNull(instruction);
        return copy;
    }
    /** Application-authored task rules; providers send them as system-level instructions, not user text. */
    public AgentContext withTaskInstruction(String instruction) {
        AgentContext copy = copy();
        copy.taskInstruction = Objects.requireNonNull(instruction);
        return copy;
    }
    /** JSON Schema the reply must follow; providers that support it enforce structured JSON output. */
    public AgentContext withResponseSchema(String schemaJson) {
        AgentContext copy = copy();
        copy.responseSchema = Objects.requireNonNull(schemaJson);
        return copy;
    }
    public String responseLanguageInstruction() { return responseLanguageInstruction; }
    public String taskInstruction() { return taskInstruction; }
    public String responseSchema() { return responseSchema; }
    public List<String> messages() { return messages; }
    public String evidenceJson() { return evidenceJson; }
    public List<PhotographySession> history() { return history; }
    public UUID sessionId() { return sessionId; }
    public Optional<SanitizedImage> approvedImage() { return approvedImage; }
}
