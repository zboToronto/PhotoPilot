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
 * During the F10 tool loop the agent appends each tool exchange to its own request-scoped copy.
 */
public class AgentContext {
    /** One requested tool call and its deterministic result or explicit failure, sent back as labelled data. */
    public record ToolExchange(String tool, String arguments, boolean succeeded, String result) { }

    private UUID sessionId;
    private List<String> messages;
    private String evidenceJson;
    private List<PhotographySession> history;
    private Optional<SanitizedImage> approvedImage;
    private String responseLanguageInstruction = "";
    private String taskInstruction = "";
    private String responseSchema = "";
    private List<ToolExchange> toolResults = new java.util.ArrayList<>();

    public AgentContext(UUID sessionId, List<String> messages, String evidenceJson, List<PhotographySession> history, Optional<SanitizedImage> approvedImage) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.messages = List.copyOf(messages);
        this.evidenceJson = Objects.requireNonNull(evidenceJson, "evidenceJson");
        this.history = List.copyOf(history);
        this.approvedImage = Objects.requireNonNull(approvedImage, "approvedImage");
    }

    /** Appends a successful tool result; only called by the agent on its own request-scoped copy. */
    public void appendToolResult(ToolCall call, String result) { appendToolResult(call, true, result); }
    public void appendToolResult(ToolCall call, boolean succeeded, String result) {
        Objects.requireNonNull(call, "call");
        toolResults.add(new ToolExchange(call.toolName(), call.argumentsJson(), succeeded, Objects.requireNonNull(result, "result")));
    }
    public List<ToolExchange> toolResults() { return List.copyOf(toolResults); }
    /** Next request: a copy with one more tool exchange, so a context already sent to the model never changes. */
    public AgentContext withToolResult(ToolCall call, boolean succeeded, String result) {
        AgentContext copy = copy();
        copy.appendToolResult(call, succeeded, result);
        return copy;
    }

    private AgentContext copy() {
        AgentContext copy = new AgentContext(sessionId, messages, evidenceJson, history, approvedImage);
        copy.responseLanguageInstruction = responseLanguageInstruction;
        copy.taskInstruction = taskInstruction;
        copy.responseSchema = responseSchema;
        copy.toolResults = new java.util.ArrayList<>(toolResults);
        return copy;
    }
    /** Request-scoped copy with replaced evidence (e.g. retrieved history added); the caller's context is untouched. */
    public AgentContext withEvidence(String evidence) {
        AgentContext copy = copy();
        copy.evidenceJson = Objects.requireNonNull(evidence);
        return copy;
    }
    /** Request-scoped copy that also carries retrieved history sessions. */
    public AgentContext withHistory(List<PhotographySession> sessions) {
        AgentContext copy = copy();
        copy.history = List.copyOf(sessions);
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
