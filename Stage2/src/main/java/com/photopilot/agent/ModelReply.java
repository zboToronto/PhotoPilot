package com.photopilot.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 UML element 20 (zero-based): {untrusted AI response}
 * GeminiAdapter fills only structuredOutput. Tool requests travel inside that text as the PhotoPilotAgent step
 * envelope, so the Stage 1 toolCalls and clarificationQuestions fields stay empty for provider replies.
 */
public class ModelReply {
    private Optional<String> structuredOutput;
    private List<ToolCall> toolCalls;
    private List<String> clarificationQuestions;

    public ModelReply(Optional<String> structuredOutput, List<ToolCall> toolCalls, List<String> clarificationQuestions) {
        this.structuredOutput = Objects.requireNonNull(structuredOutput, "structuredOutput");
        this.toolCalls = List.copyOf(toolCalls);
        this.clarificationQuestions = List.copyOf(clarificationQuestions);
    }

    /** Untrusted model text; callers must validate it before relying on it. */
    public Optional<String> structuredOutput() { return structuredOutput; }

    public boolean requiresTool() { return !toolCalls.isEmpty(); }
}
