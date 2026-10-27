package com.photopilot.i18n;

import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.integration.LanguageAwareLLMClient;
import com.photopilot.service.TechnicalCalculator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentLanguageTest {
    @Test void perRequestDefaultReachesModelPortWithoutChangingExistingContextOrAddingCalls() {
        var localization = LocalizationService.english();
        var captured = new ArrayList<AgentContext>();
        var calls = new AtomicInteger();
        var expected = Result.success(new ModelReply(Optional.of("{\"operation\":\"EXPOSURE_ND\"}"), List.of(), List.of()));
        var model = new LanguageAwareLLMClient(context -> { captured.add(context); calls.incrementAndGet(); return expected; }, localization);
        String schema = "{\"operation\":\"EXPOSURE_ND\",\"units\":{\"shutter\":\"s\"}}";
        var original = new AgentContext(UUID.randomUUID(), List.of("旧回复 remains unchanged", "Please answer in English."), schema, List.of(), Optional.empty());
        assertSame(expected, model.complete(original));
        assertTrue(captured.getFirst().responseLanguageInstruction().contains("English (en)"));
        localization.useLanguage("zh-CN");
        assertEquals(1, calls.get(), "UI switching must not call the model");
        assertSame(expected, model.complete(original));
        AgentContext chinese = captured.getLast();
        assertTrue(chinese.responseLanguageInstruction().contains("Simplified Chinese (zh-CN)"));
        assertTrue(chinese.responseLanguageInstruction().contains("unless the user explicitly requests another language"));
        for (String rule : List.of("higher-priority system instructions", "safety", "schema keys", "enum values", "tool parameters")) {
            assertTrue(chinese.responseLanguageInstruction().contains(rule), rule);
        }
        assertEquals(original.messages(), chinese.messages()); assertEquals(schema, chinese.evidenceJson());
        assertEquals(original.history(), chinese.history()); assertEquals(original.sessionId(), chinese.sessionId());
        assertEquals(original.approvedImage(), chinese.approvedImage()); assertEquals("", original.responseLanguageInstruction());
        assertEquals(2, calls.get());
    }
    @Test void deterministicToolSchemaAndJsonEvidenceAreLocaleIndependent() {
        var localization = LocalizationService.english();
        var tool = new CalculationTool(new TechnicalCalculator());
        String request = "{\"operation\":\"EXPOSURE_ND\",\"parameters\":{\"shutter\":0.008,\"ndStops\":10},\"units\":{\"shutter\":\"s\",\"ndStops\":\"stops\"}}";
        String schema = tool.inputSchema(); String before = tool.execute(request).value().orElseThrow();
        localization.useLanguage("zh-CN");
        assertEquals(schema, tool.inputSchema()); assertEquals(before, tool.execute(request).value().orElseThrow());
        assertTrue(before.contains("Adjusted shutter")); assertTrue(before.contains("assumptions"));
    }
}
