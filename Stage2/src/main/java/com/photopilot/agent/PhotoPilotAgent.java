package com.photopilot.agent;

import java.io.StringReader;
import java.util.Objects;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.photopilot.common.Result;
import com.photopilot.integration.LLMClient;

/**
 * Stage 1 UML element 13 (zero-based): {AI; generic method &lt;T&gt; run}
 * Structured single-step runs (F03): one model request, then strict schema decoding of the untrusted reply.
 * The model never saves, confirms or mutates anything; callers apply deterministic domain validation.
 * The bounded tool loop (F10) remains NOT IMPLEMENTED, so tool-call replies are rejected.
 */
public class PhotoPilotAgent {
    private static final Gson OUTPUT = new GsonBuilder().disableJdkUnsafe().setStrictness(Strictness.STRICT).create();
    private LLMClient model;
    private ToolRegistry tools;
    private HistoryRetriever history;
    private int maxSteps;

    public PhotoPilotAgent(LLMClient model, ToolRegistry tools, HistoryRetriever history, int maxSteps) {
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.history = Objects.requireNonNull(history, "history");
        this.maxSteps = maxSteps;
    }

    public <T> Result<T> run(String task, AgentContext context, Class<T> type) {
        if (task == null || task.isBlank() || context == null || type == null || maxSteps < 1) return Result.failureKey("ai.error.emptyPrompt");
        var reply = model.complete(context.withTaskInstruction(task));
        if (!reply.isSuccess()) return Result.failure(reply);
        var value = reply.value().orElseThrow();
        if (value.requiresTool()) return Result.failureKey("ai.error.unexpectedTool");
        return validateOutput(value.structuredOutput().orElse(""), type);
    }

    private Result<ToolCall> selectTool(ModelReply reply) {
        return Result.failureKey("error.notImplemented", "PhotoPilotAgent.selectTool");
    }

    /** Schema check only: one complete JSON object that binds to {@code type}; no business validation here. */
    private <T> Result<T> validateOutput(String json, Class<T> type) {
        String text = json.strip();
        // Tolerate a Markdown code fence around otherwise valid JSON; nothing else is repaired.
        if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return Result.failureKey("ai.error.malformedOutput");
            JsonElement tree = OUTPUT.fromJson(reader, JsonElement.class);
            if (reader.peek() != JsonToken.END_DOCUMENT) return Result.failureKey("ai.error.malformedOutput");
            T value = OUTPUT.fromJson(tree, type);
            return value == null ? Result.failureKey("ai.error.malformedOutput") : Result.success(value);
        } catch (java.io.IOException | RuntimeException malformed) {
            return Result.failureKey("ai.error.malformedOutput");
        }
    }
}
