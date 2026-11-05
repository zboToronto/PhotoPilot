package com.photopilot.agent;

import java.io.StringReader;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.photopilot.common.Result;
import com.photopilot.integration.LLMClient;

/**
 * Stage 1 UML element 13 (zero-based): {AI; generic method &lt;T&gt; run}
 * {@link #run}: one structured step (F03, F07, F09), tool-call replies rejected.
 * {@link #runWithTools} (F10, SD04): a bounded loop in which the model either requests one permitted tool through
 * the structured reply envelope or returns its final result. Tool names are allowlisted per task, arguments are
 * schema-checked, repeated or failing requests are bounded, and every result is deterministic application output.
 * The model never saves, confirms or mutates anything; callers apply deterministic domain validation.
 */
public class PhotoPilotAgent {
    /** Consecutive invalid, repeated or failed tool requests tolerated before the run is abandoned. */
    public static final int MAX_TOOL_FAILURES = 2;
    private static final Gson OUTPUT = new GsonBuilder().disableJdkUnsafe().setStrictness(Strictness.STRICT).create();
    static final String TOOL_PROTOCOL = """
        Reply protocol (always JSON matching the response schema):
        - To use a deterministic application tool, reply {"step":"tool","tool":{"name":"<tool>","arguments":"<JSON object as a string>"}}.
          The arguments must match that tool's input schema below. You receive the result (or an explicit failure) and then reply again.
          Request one tool per reply, never repeat an identical request, and never invent a tool result.
        - When you are finished, reply {"step":"final","result":{...}} with the complete result.
        Available tools and their input schemas:
        """;

    /**
     * Final validated-schema output of a tool-assisted run with every tool exchange that produced evidence, and the
     * related-history JSON actually added to the request ("" when none), so callers can check citations against it.
     */
    public record Run<T>(T output, List<AgentContext.ToolExchange> tools, int historySessions, String historyEvidence) {
        public Run { Objects.requireNonNull(output); tools = List.copyOf(tools); historyEvidence = Objects.toString(historyEvidence, ""); }
    }
    /** Untrusted reply envelope of the tool protocol, decoded strictly. */
    record Envelope(String step, Tool tool, JsonElement result) { }
    record Tool(String name, String arguments) { }

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

    /**
     * SD04 bounded reasoning: relevant history is added to the evidence, then each reply either requests one tool in
     * {@code permittedTools} or returns the final result, which must bind to {@code type}. Unknown/unauthorized tool
     * names abort the run; schema-invalid, repeated or failing requests are reported back to the model as explicit
     * failures (never as results) and abort after {@link #MAX_TOOL_FAILURES} in a row; at most {@code maxSteps}
     * model requests are made.
     */
    public <T> Result<Run<T>> runWithTools(String task, AgentContext context, Class<T> type, Set<String> permittedTools) {
        if (task == null || task.isBlank() || context == null || type == null || maxSteps < 1 || context.responseSchema().isBlank())
            return Result.failureKey("ai.error.emptyPrompt");
        var permitted = new LinkedHashSet<>(Objects.requireNonNull(permittedTools));
        var schemas = tools.schemas();
        for (String name : permitted) if (!schemas.containsKey(name)) return Result.failureKey("ai.error.unknownTool", name);
        var retrieved = history.contextFor(context.sessionId());
        int related = retrieved.isSuccess() ? retrieved.value().orElseThrow().history().size() : 0;
        AgentContext request = context;
        if (related > 0) request = withHistory(context, retrieved.value().orElseThrow());
        String sentHistory = request == context ? "" : retrieved.value().orElseThrow().evidenceJson();
        var catalogue = new StringBuilder(TOOL_PROTOCOL);
        if (permitted.isEmpty()) catalogue.append("(none: reply with step final)\n");
        for (String name : permitted) catalogue.append("- ").append(name).append(": ").append(schemas.get(name).strip()).append('\n');
        catalogue.append("You may make at most ").append(Math.max(0, maxSteps - 1)).append(" tool requests.\n");
        request = request.withTaskInstruction(task.strip() + "\n\n" + catalogue).withResponseSchema(envelope(context.responseSchema(), permitted));
        var exchanges = new ArrayList<AgentContext.ToolExchange>();
        var seen = new HashSet<String>();
        int failures = 0;
        for (int step = 1; step <= maxSteps; step++) {
            var reply = model.complete(request);
            if (!reply.isSuccess()) return Result.failure(reply);
            var envelope = decodeEnvelope(reply.value().orElseThrow());
            if (!envelope.isSuccess()) return Result.failure(envelope);
            var value = envelope.value().orElseThrow();
            if (value.step().equals("final")) {
                if (value.result() == null || !value.result().isJsonObject()) return Result.failureKey("ai.error.malformedOutput");
                var output = validateOutput(value.result().toString(), type);
                return output.isSuccess() ? Result.success(new Run<>(output.value().orElseThrow(), exchanges, related, sentHistory)) : Result.failure(output);
            }
            if (step == maxSteps) break;
            var selected = selectTool(reply.value().orElseThrow());
            if (!selected.isSuccess()) return Result.failure(selected);
            var call = selected.value().orElseThrow();
            if (!permitted.contains(call.toolName())) return Result.failureKey("ai.error.unauthorizedTool", call.toolName());
            Result<String> outcome;
            if (!seen.add(call.fingerprint())) outcome = Result.failureKey("ai.error.repeatedTool", call.toolName());
            else outcome = tools.execute(call, permitted);
            boolean ok = outcome.isSuccess();
            String text = ok ? outcome.value().orElseThrow() : String.join("; ", outcome.errors());
            request = request.withToolResult(call, ok, text); // appendToolResult on a fresh copy; earlier requests stay unchanged
            exchanges.add(new AgentContext.ToolExchange(call.toolName(), call.argumentsJson(), ok, text));
            failures = ok ? 0 : failures + 1;
            if (failures > MAX_TOOL_FAILURES) return Result.failureKey("ai.error.toolFailures", MAX_TOOL_FAILURES + 1);
        }
        return Result.failureKey("ai.error.stepLimit", maxSteps);
    }

    /** The validated tool request of a reply: provider-native calls first, otherwise the protocol envelope. */
    private Result<ToolCall> selectTool(ModelReply reply) {
        var envelope = decodeEnvelope(reply);
        if (!envelope.isSuccess()) return Result.failure(envelope);
        var tool = envelope.value().orElseThrow().tool();
        if (tool == null || tool.name() == null || tool.name().isBlank() || tool.arguments() == null)
            return Result.failureKey("ai.error.malformedOutput");
        if (!tools.has(tool.name())) return Result.failureKey("ai.error.unauthorizedTool", tool.name());
        if (tool.arguments().length() > ToolCall.MAX_ARGUMENTS) return Result.failureKey("ai.error.toolArguments", tool.name(), "too long");
        return Result.success(new ToolCall(tool.name(), tool.arguments()));
    }

    private Result<Envelope> decodeEnvelope(ModelReply reply) {
        if (reply.requiresTool()) return Result.failureKey("ai.error.unexpectedTool");
        var decoded = validateOutput(reply.structuredOutput().orElse(""), Envelope.class);
        if (!decoded.isSuccess()) return decoded;
        var value = decoded.value().orElseThrow();
        if (!"tool".equals(value.step()) && !"final".equals(value.step())) return Result.failureKey("ai.error.malformedOutput");
        return decoded;
    }

    /** Envelope schema around the task schema; the tool name enum is exactly the permitted set. */
    private static String envelope(String resultSchema, Set<String> permitted) {
        var root = new JsonObject(); root.addProperty("type", "object");
        var properties = new JsonObject();
        var step = new JsonObject(); step.addProperty("type", "string");
        var steps = new JsonArray(); steps.add("final"); if (!permitted.isEmpty()) steps.add("tool"); step.add("enum", steps);
        properties.add("step", step);
        if (!permitted.isEmpty()) {
            var tool = new JsonObject(); tool.addProperty("type", "object");
            var toolProperties = new JsonObject();
            var name = new JsonObject(); name.addProperty("type", "string");
            var names = new JsonArray(); permitted.forEach(names::add); name.add("enum", names);
            var arguments = new JsonObject(); arguments.addProperty("type", "string");
            arguments.addProperty("description", "The tool arguments: one JSON object encoded as a string.");
            toolProperties.add("name", name); toolProperties.add("arguments", arguments);
            tool.add("properties", toolProperties);
            var required = new JsonArray(); required.add("name"); required.add("arguments"); tool.add("required", required);
            properties.add("tool", tool);
        }
        properties.add("result", JsonParser.parseString(resultSchema));
        root.add("properties", properties);
        var required = new JsonArray(); required.add("step"); root.add("required", required);
        return root.toString();
    }

    private static AgentContext withHistory(AgentContext context, AgentContext retrieved) {
        try {
            var evidence = JsonParser.parseString(context.evidenceJson()).getAsJsonObject();
            var related = JsonParser.parseString(retrieved.evidenceJson()).getAsJsonObject();
            related.entrySet().forEach(entry -> evidence.add(entry.getKey(), entry.getValue()));
            return context.withEvidence(evidence.toString()).withHistory(retrieved.history());
        } catch (RuntimeException unreadable) { return context; }
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
