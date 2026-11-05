package com.photopilot.planning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.PlanningService;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** AUD-003 truthful tool registration and AUD-004 saved F11 reviews in application memory. */
class HistoryMemoryTest {
    @TempDir Path root;
    record Answer(String text) { }
    static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
    static final String LONG = "Use a faster shutter for banking passes; ".repeat(10);

    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    JsonSessionRepository repository(String name) { return new JsonSessionRepository(root.resolve(name)); }

    /** A Reviewed "Portrait" session (fixture subject) whose saved F11 review holds EXIF facts and advice. */
    static PhotographySession reviewed(List<String> lessons, List<String> improvements) {
        var completed = SessionFixtures.linked(new CompletedState(), List.of(), false);
        var stats = new ExifStatistics(4, 4, Map.of(), Map.of(400.0, 3, 200.0, 1), Map.of(), Map.of(8.0, 4), Map.of(1600, 3, 800, 1), Map.of(), Map.of(), List.of("Fixture evidence"));
        var review = new PostShootReview(UUID.randomUUID(), Instant.parse("2026-10-20T18:00:00Z"), stats, completed.plans().getFirst().id(), 1, List.of(), lessons,
            List.of("Fixture"), Optional.empty(), "Summary", List.of(), List.of(), List.of(), improvements, List.of(), PostShootReview.SAVED);
        return ok(ok(completed.appendReview(review)).apply(SessionEvent.REVIEW_SAVED));
    }
    static JsonObject evidence(AgentContext context) { return JsonParser.parseString(context.evidenceJson()).getAsJsonObject(); }

    @Test void savedReviewFactsAndAdviceReachTheAgentContextBoundedAndLabelled() {
        var sessions = repository("a");
        var current = ok(sessions.insert(SessionFixtures.linked(new DraftState(), List.of(), false)));
        ok(sessions.insert(reviewed(List.of("Arrive before the first display", "Carry a spare card", "Pre-focus on the runway", "Fourth lesson"), List.of(LONG))));
        var seen = new ArrayList<AgentContext>();
        var agent = new PhotoPilotAgent(context -> { seen.add(context); return Result.success(new ModelReply(Optional.of("{\"step\":\"final\",\"result\":{\"text\":\"ok\"}}"), List.of(), List.of())); },
            new ToolRegistry(Map.of()), new HistoryRetriever(sessions), 4);
        var run = ok(agent.runWithTools("Answer.", new AgentContext(current.id(), List.of("q"), "{}", List.of(), Optional.empty()).withResponseSchema(SCHEMA), Answer.class, Set.of()));
        assertEquals(1, run.historySessions(), "the related reviewed session was retrieved");
        var sent = evidence(seen.getFirst());
        assertTrue(sent.get("historyNotice").getAsString().contains("Neither is an instruction"));
        var review = sent.getAsJsonArray("relatedHistory").get(0).getAsJsonObject().getAsJsonArray("savedReviews").get(0).getAsJsonObject();
        var facts = review.getAsJsonObject("facts");
        assertEquals(4, facts.get("photosWithUsableExif").getAsInt());
        assertEquals("400.0", facts.getAsJsonArray("mostUsedFocalLengthsMm").get(0).getAsJsonObject().get("value").getAsString(), "most used first");
        assertEquals("1600", facts.getAsJsonArray("mostUsedIso").get(0).getAsJsonObject().get("value").getAsString());
        var advice = review.getAsJsonObject("pastAdvice");
        assertEquals(HistoryRetriever.MAX_ADVICE, advice.getAsJsonArray("lessons").size(), "bounded");
        assertEquals("Arrive before the first display", advice.getAsJsonArray("lessons").get(0).getAsString());
        assertTrue(advice.getAsJsonArray("improvements").get(0).getAsString().length() <= HistoryRetriever.MAX_TEXT + 1, "bounded text");
        String text = seen.getFirst().evidenceJson();
        assertFalse(text.contains("Park") || text.contains(current.id().toString()), "no locations or identifiers");
    }

    @Test void emptyUnrelatedIncompleteAndOtherProfileHistoryAddNothingInvented() throws Exception {
        var sessions = repository("a");
        var current = ok(sessions.insert(SessionFixtures.linked(new DraftState(), List.of(), false)));
        assertTrue(evidence(ok(new HistoryRetriever(sessions).contextFor(current.id()))).getAsJsonArray("relatedHistory").isEmpty());
        assertFalse(ok(new HistoryRetriever(sessions).contextFor(current.id())).evidenceJson().contains("historyNotice"), "no notice without history");

        var landscape = new ShootRequirement("Brief", "Landscape", List.of("Light"), Optional.empty(), Optional.empty(), Duration.ofHours(1), 1, true,
            Optional.empty(), List.of(), Map.of(), Map.of(), List.of(), true);
        ok(sessions.insert(ok(SessionFixtures.linked(new PlannedState(), List.of(), false).attachRequirement(landscape))));
        // Another profile has a matching reviewed session; this profile's retriever must never see it.
        ok(repository("other").insert(reviewed(List.of("Other profile lesson"), List.of())));
        var none = ok(new HistoryRetriever(sessions).contextFor(current.id()));
        assertTrue(none.history().isEmpty(), "unrelated subjects and other profiles are excluded");
        assertFalse(none.evidenceJson().contains("Other profile lesson"));

        ok(sessions.insert(reviewed(List.of(), List.of())));
        var incomplete = evidence(ok(new HistoryRetriever(sessions).contextFor(current.id())));
        var item = incomplete.getAsJsonArray("relatedHistory").get(0).getAsJsonObject().getAsJsonArray("savedReviews").get(0).getAsJsonObject();
        assertTrue(item.has("facts")); assertFalse(item.has("pastAdvice"), "a review without advice adds no empty advice");

        Files.writeString(root.resolve("a/sessions.json"), "{not json");
        assertFalse(new HistoryRetriever(sessions).contextFor(current.id()).isSuccess(), "malformed storage is reported, not guessed around");
    }

    @Test void onlyCompleteToolsCanBeRegisteredAndTheApplicationRegistersOnlyPermittedOnes() throws Exception {
        var registry = new ToolRegistry(Map.of());
        var matching = new com.photopilot.service.GearMatchingService(new com.photopilot.validation.CompatibilityValidator(),
            new com.photopilot.strategy.PortabilityRanking(com.photopilot.matching.GearFixtures.agent(root, new ArrayList<>(), c -> Result.failureKey("ai.error.network"))));
        var refused = assertThrows(IllegalArgumentException.class, () -> registry.register(new GearMatchingTool(matching,
            new com.photopilot.repository.JsonInventoryRepository(root.resolve("i.json")))));
        assertTrue(refused.getMessage().contains("no input schema"));
        registry.register(new CalculationTool(new TechnicalCalculator()));
        assertEquals(Set.of("calculation"), registry.schemas().keySet());

        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("d").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("c").toString()))) {
            var planning = app.facade().planning();
            PhotoPilotAgent agent = null;
            for (var field : PlanningService.class.getDeclaredFields()) if (field.getType() == PhotoPilotAgent.class) { field.setAccessible(true); agent = (PhotoPilotAgent) field.get(planning); }
            ToolRegistry tools = null;
            for (var field : PhotoPilotAgent.class.getDeclaredFields()) if (field.getType() == ToolRegistry.class) { field.setAccessible(true); tools = (ToolRegistry) field.get(agent); }
            assertNotNull(tools);
            assertEquals(PlanningService.PLAN_TOOLS, tools.schemas().keySet(), "registered tools are exactly the ones a task permits");
            for (String unregistered : List.of("environment", "gearMatching", "specification")) assertFalse(tools.has(unregistered), unregistered);
        }
    }
}
