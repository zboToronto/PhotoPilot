package com.photopilot.agent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 UML element 20 (zero-based): {untrusted AI response}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
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

    public boolean requiresTool() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ModelReply.requiresTool");
    }
}
