package com.photopilot.planning;

import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.TechnicalCalculator;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** SD04 bounded tool loop with a scripted fake model: allowlist, schema checks, repeats, failures and the step limit. */
class AgentToolLoopTest {
    @TempDir Path root;
    record Answer(String text) { }
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
    static final String FINAL = "{\"step\":\"final\",\"result\":{\"text\":\"done\"}}";
    static final String DOF = "{\\\"operation\\\":\\\"DEPTH_OF_FIELD\\\",\\\"parameters\\\":{\\\"focalLength\\\":400,\\\"aperture\\\":8,\\\"coc\\\":0.03,\\\"distance\\\":100},"
        + "\\\"units\\\":{\\\"focalLength\\\":\\\"mm\\\",\\\"aperture\\\":\\\"f-number\\\",\\\"coc\\\":\\\"mm\\\",\\\"distance\\\":\\\"m\\\"}}";
    static String tool(String name, String escapedArguments) { return "{\"step\":\"tool\",\"tool\":{\"name\":\"" + name + "\",\"arguments\":\"" + escapedArguments + "\"}}"; }

    final List<AgentContext> seen = new ArrayList<>();
    boolean executedForbidden;
    PhotoPilotAgent agent(int steps, String... replies) {
        var queue = new ArrayDeque<>(List.of(replies));
        Function<AgentContext, Result<ModelReply>> model = context -> {
            seen.add(context);
            return queue.isEmpty() ? Result.failureKey("ai.error.serviceUnavailable", "503") : Result.success(new ModelReply(Optional.of(queue.poll()), List.of(), List.of()));
        };
        var tools = new ToolRegistry(Map.of());
        tools.register(new CalculationTool(new TechnicalCalculator()));
        tools.register(new AgentTool() { // registered, but never permitted for this task
            public String name() { return "gearMatching"; }
            public String inputSchema() { return "{\"type\":\"object\"}"; }
            public Result<String> execute(String argumentsJson) { executedForbidden = true; return Result.success("{}"); }
        });
        return new PhotoPilotAgent(model::apply, tools, new HistoryRetriever(new JsonSessionRepository(root)), steps);
    }
    Result<PhotoPilotAgent.Run<Answer>> run(PhotoPilotAgent agent) {
        return agent.runWithTools("Answer.", new AgentContext(UUID.randomUUID(), List.of("question"), "{}", List.of(), Optional.empty()).withResponseSchema(SCHEMA),
            Answer.class, Set.of("calculation"));
    }

    @Test void validStructuredFinalReplyBindsWithoutTools() {
        var result = run(agent(8, FINAL));
        assertTrue(result.isSuccess(), result.errors()::toString);
        assertEquals("done", result.value().orElseThrow().output().text());
        assertTrue(result.value().orElseThrow().tools().isEmpty());
        String schema = seen.getFirst().responseSchema();
        assertTrue(schema.contains("\"step\"") && schema.contains("\"calculation\"") && !schema.contains("gearMatching"), "only permitted tools are offered");
        assertTrue(seen.getFirst().taskInstruction().contains("Available tools"));
    }

    @Test void permittedToolRunsDeterministicallyAndItsResultIsSentBackAsData() {
        var result = run(agent(8, tool("calculation", DOF), FINAL));
        assertTrue(result.isSuccess(), result.errors()::toString);
        var exchange = result.value().orElseThrow().tools().getFirst();
        assertTrue(exchange.succeeded()); assertEquals("calculation", exchange.tool());
        assertTrue(exchange.result().contains("Hyperfocal distance"), exchange.result());
        assertEquals(2, seen.size());
        assertTrue(seen.getFirst().toolResults().isEmpty(), "the caller-visible first request is not mutated");
        assertEquals(1, seen.get(1).toolResults().size(), "the next request carries the verified tool evidence");
    }

    @Test void unregisteredOrUnpermittedToolsStopTheRunWithoutExecution() {
        var unknown = run(agent(8, tool("shell", "{}"), FINAL));
        assertTrue(unknown.hasError("ai.error.unauthorizedTool"), unknown.errors()::toString);
        var unpermitted = run(agent(8, tool("gearMatching", "{}"), FINAL));
        assertTrue(unpermitted.hasError("ai.error.unauthorizedTool"), "registered but not allowed for this task");
        assertFalse(executedForbidden, "an unpermitted tool is never executed");
        var registry = new ToolRegistry(Map.of()); registry.register(new CalculationTool(new TechnicalCalculator()));
        assertTrue(registry.execute(new ToolCall("calculation", "{}"), Set.of()).hasError("ai.error.unknownTool"), "per-task permission is enforced in the registry too");
    }

    @Test void invalidAndRepeatedToolRequestsAreReportedAsFailuresAndBounded() {
        var recovered = run(agent(8, tool("calculation", "{\\\"operation\\\":\\\"DEPTH_OF_FIELD\\\"}"), FINAL));
        assertTrue(recovered.isSuccess(), "a schema-invalid request is reported back and the model may recover");
        var failed = recovered.value().orElseThrow().tools().getFirst();
        assertFalse(failed.succeeded()); assertTrue(failed.result().contains("parameters"), failed.result());
        var repeated = run(agent(8, tool("calculation", DOF), tool("calculation", DOF), FINAL));
        assertTrue(repeated.isSuccess());
        assertFalse(repeated.value().orElseThrow().tools().get(1).succeeded(), "an identical repeat is not executed again");
        var bad = tool("calculation", "{\\\"operation\\\":\\\"NOPE\\\",\\\"parameters\\\":{},\\\"units\\\":{}}");
        var stopped = run(agent(8, bad, tool("calculation", "{\\\"x\\\":1}"), tool("calculation", "[]"), FINAL));
        assertTrue(stopped.hasError("ai.error.toolFailures"), stopped.errors()::toString);
        int before = seen.size();
        var limited = run(agent(3, tool("calculation", DOF), tool("calculation", DOF.replace("100", "120")), tool("calculation", DOF.replace("100", "140"))));
        assertTrue(limited.hasError("ai.error.stepLimit"), limited.errors()::toString);
        assertEquals(3, seen.size() - before, "never more model requests than maxSteps");
    }

    @Test void malformedRepliesAndProviderFailuresProduceNoResult() {
        assertTrue(run(agent(8, "not json")).hasError("ai.error.malformedOutput"));
        assertTrue(run(agent(8, "{\"step\":\"maybe\"}")).hasError("ai.error.malformedOutput"));
        assertTrue(run(agent(8, "{\"step\":\"final\",\"result\":\"text\"}")).hasError("ai.error.malformedOutput"));
        assertTrue(run(agent(8, "{\"step\":\"final\",\"result\":{\"text\":1}} trailing")).hasError("ai.error.malformedOutput"));
        assertTrue(run(agent(8)).hasError("ai.error.serviceUnavailable"));
    }

    @Test void toolCallSchemaValidationIsStrict() {
        String schema = new CalculationTool(new TechnicalCalculator()).inputSchema();
        assertTrue(new ToolCall("calculation", DOF.replace("\\\"", "\"")).validateAgainst(schema).isSuccess());
        assertFalse(new ToolCall("calculation", "{\"operation\":\"DEPTH_OF_FIELD\",\"operation\":\"HYPERFOCAL\",\"parameters\":{},\"units\":{}}").validateAgainst(schema).isSuccess(), "duplicate keys");
        assertFalse(new ToolCall("calculation", "{\"operation\":\"RM\",\"parameters\":{},\"units\":{}}").validateAgainst(schema).isSuccess(), "enum");
        assertFalse(new ToolCall("calculation", "{\"operation\":\"STORAGE\",\"parameters\":{\"a\":\"1\"},\"units\":{}}").validateAgainst(schema).isSuccess(), "number type");
        assertFalse(new ToolCall("calculation", "{\"operation\":\"STORAGE\",\"parameters\":{},\"units\":{},\"path\":\"/etc\"}").validateAgainst(schema).isSuccess(), "additional property");
        assertFalse(new ToolCall("calculation", "{\"operation\":DEPTH}").validateAgainst(schema).isSuccess(), "strict JSON");
    }
}
