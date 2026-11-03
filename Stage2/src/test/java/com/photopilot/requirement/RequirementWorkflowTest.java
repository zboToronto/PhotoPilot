package com.photopilot.requirement;

import com.google.gson.JsonParser;
import com.photopilot.application.RequirementForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.CompletedState;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.requirement.FakeGemini.*;
import static org.junit.jupiter.api.Assertions.*;

/** F03 through the real Facade -> PlanningService -> RequirementService -> agent -> GeminiAdapter chain, offline. */
class RequirementWorkflowTest {
    @TempDir Path root;
    FakeGemini gemini;
    ApplicationBootstrap app;

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); app = new ApplicationBootstrap(gemini.environment(root)); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); }

    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static void fails(Result<?> result, String key) {
        assertFalse(result.isSuccess()); assertTrue(result.hasError(key), () -> "expected " + key + " but got " + result.errors());
    }
    PhotographySession session() { return ok(app.facade().sessions().create("Airshow", LocalDate.of(2026, 11, 14))); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    static String output(String fields) {
        var base = new LinkedHashMap<String, String>();
        for (String key : List.of("subject", "date", "startTime", "timeWindow", "location", "durationMinutes", "cameraCount",
                "lensChangesAllowed", "maxWeightGrams", "portability")) base.put(key, "null");
        for (String key : List.of("goals", "gearConstraints", "priorities", "fixedConstraints", "clarificationQuestions", "flaggedFields")) base.put(key, "[]");
        var override = JsonParser.parseString(fields).getAsJsonObject();
        override.keySet().forEach(key -> base.put(key, override.get(key).toString()));
        var json = new StringBuilder("{");
        base.forEach((key, value) -> json.append(json.length() > 1 ? "," : "").append('"').append(key).append("\":").append(value));
        return json.append('}').toString();
    }

    @Test void detailedRequestKeepsStatedConstraintsInventsNothingAndSavesNothing() throws Exception {
        var session = session(); gemini.reply(AIRSHOW);
        var draft = ok(app.facade().parse(session.id(), AIRSHOW_REQUEST));
        assertEquals("Aircraft at an airshow", draft.subject());
        assertEquals(Optional.of(2), draft.cameraCount());
        assertEquals(Optional.of(false), draft.lensChangesAllowed());
        assertEquals(Optional.of(Duration.ofHours(3)), draft.duration());
        assertEquals("around sunset", draft.timeWindow());
        assertEquals("minimize weight", draft.portability());
        assertTrue(draft.date().isEmpty()); assertTrue(draft.time().isEmpty()); assertTrue(draft.location().isEmpty());
        assertTrue(draft.maxWeightGrams().isEmpty(), "no numeric weight limit was stated");
        assertEquals(AIRSHOW_REQUEST, draft.originalText()); assertFalse(draft.confirmed());
        assertEquals(List.of("location"), draft.unspecifiedFields());
        assertEquals(2, draft.clarificationQuestions().size()); assertTrue(draft.problems().isEmpty());
        assertTrue(ok(app.facade().sessions().open(session.id())).requirements().isEmpty(), "parsing must not save");
        var body = JsonParser.parseString(gemini.requests.getFirst()).getAsJsonObject();
        var generation = body.getAsJsonObject("generationConfig");
        assertEquals("application/json", generation.get("responseMimeType").getAsString());
        assertTrue(generation.getAsJsonObject("responseJsonSchema").getAsJsonObject("properties").has("lensChangesAllowed"));
        assertTrue(body.getAsJsonObject("systemInstruction").toString().contains("Never invent"));
        assertTrue(body.getAsJsonArray("contents").toString().contains("today"));
        assertTrue(body.toString().contains("aircraft at an airshow around sunset"));
        assertFalse(body.toString().contains(KEY), "the key belongs in a header only");
    }

    @Test void missingOptionalDetailsStayExplicitlyUnspecifiedThroughConfirmation() {
        var session = session(); gemini.reply(output("{\"subject\":\"Street photography\"}"));
        var draft = ok(app.facade().parse(session.id(), "Street photography downtown sometime"));
        assertEquals(List.of("date", "location", "duration", "cameraCount", "lensChanges", "goals"), draft.unspecifiedFields());
        var saved = ok(app.facade().planning().confirm(session.id(), draft));
        assertTrue(saved.confirmed()); assertTrue(saved.duration().isEmpty()); assertTrue(saved.cameraCount().isEmpty());
        assertTrue(saved.lensChangesAllowed().isEmpty()); assertTrue(saved.location().isEmpty());
    }

    @Test void ambiguousOrContradictoryInputIsFlaggedClarifiedAndCheckedDeterministically() {
        var session = session();
        gemini.reply(output("{\"subject\":\"Wedding\",\"flaggedFields\":[\"cameraCount\",\"notAField\"],"
            + "\"clarificationQuestions\":[\"Do you want to carry one or two camera bodies?\"]}"));
        var draft = ok(app.facade().parse(session.id(), "Wedding with one camera body, but I will bring two bodies"));
        assertEquals(Set.of("cameraCount"), draft.reviewFields()); assertTrue(draft.cameraCount().isEmpty());
        gemini.reply(output("{\"subject\":\"Wedding\",\"cameraCount\":2}"));
        var clarified = ok(app.facade().planning().clarify(session.id(), draft, "Two bodies."));
        assertEquals(Optional.of(2), clarified.cameraCount()); assertTrue(clarified.reviewFields().isEmpty());
        assertTrue(clarified.originalText().startsWith("Wedding with one camera body")); assertTrue(clarified.originalText().endsWith("Two bodies."));
        String second = gemini.requests.get(1);
        assertTrue(second.contains("currentDraft") && second.contains("Two bodies.") && second.contains("one or two camera bodies"));
        fails(app.facade().planning().clarify(session.id(), clarified, "  "), "requirement.error.answers");
        var zone = ZoneId.of("America/Toronto");
        var contradictory = new ShootRequirement("Brief", "Wedding", List.of(), Optional.of(ZonedDateTime.of(2026, 11, 14, 19, 0, 0, 0, zone)),
            Optional.of(LocalDate.of(2026, 11, 15)), "", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), false);
        fails(contradictory.confirm(), "requirement.error.timeDate");
        assertEquals(2, gemini.requests.size(), "blank answers are rejected before any request");
    }

    @Test void manualCorrectionsUseSharedParsingAndRejectInvalidValues() {
        var session = session(); gemini.reply(output("{\"subject\":\"Aircraft\",\"cameraCount\":2,\"flaggedFields\":[\"location\",\"cameraCount\"]}"));
        var draft = ok(app.facade().parse(session.id(), AIRSHOW_REQUEST));
        var edited = ok(RequirementForm.apply(draft, Map.of("date", "2026-11-14", "time", "19:30", "location", "Lakeshore airfield",
            "duration", "2h 30m", "maxWeight", "1.5kg", "lensChanges", "allowed", "priorities", "low weight=1; reach=2",
            "constraints", "flash=not allowed", "goals", "Banking jets; Sunset silhouettes"), ZoneId.of("America/Toronto")));
        assertEquals(Optional.of(LocalDate.of(2026, 11, 14)), edited.date());
        assertEquals(LocalTime.of(19, 30), edited.time().orElseThrow().toLocalTime());
        assertEquals("Lakeshore airfield", edited.location().orElseThrow().label());
        assertEquals(Optional.of(Duration.ofMinutes(150)), edited.duration()); assertEquals(Optional.of(1500.0), edited.maxWeightGrams());
        assertEquals(Optional.of(true), edited.lensChangesAllowed()); assertEquals(Map.of("low weight", 1, "reach", 2), edited.priorities());
        assertEquals(List.of("Banking jets", "Sunset silhouettes"), edited.goals());
        assertEquals(Set.of("cameraCount"), edited.reviewFields(), "editing a field clears its AI review flag");
        assertEquals(Optional.of(2), edited.cameraCount(), "unedited values are preserved");
        for (var invalid : List.of(Map.of("cameraCount", "0"), Map.of("cameraCount", "11"), Map.of("duration", "-3h"), Map.of("duration", "15 days"),
                Map.of("maxWeight", "-5"), Map.of("time", "25:00"), Map.of("time", "19:00", "date", ""), Map.of("date", "2026-02-30"),
                Map.of("priorities", "portability"), Map.of("lensChanges", "sometimes"), Map.of("unknownField", "x")))
            assertFalse(RequirementForm.apply(edited, invalid, ZoneId.systemDefault()).isSuccess(), invalid.toString());
        fails(RequirementForm.apply(edited, Map.of("time", "19:00", "date", ""), ZoneId.systemDefault()), "requirement.error.timeWithoutDate");
        var blankSubject = ok(RequirementForm.apply(edited, Map.of("subject", " "), ZoneId.systemDefault()));
        fails(app.facade().planning().confirm(session.id(), blankSubject), "requirement.error.subject");
        var outOfRange = ok(RequirementForm.apply(edited, Map.of("priorities", "reach=99"), ZoneId.systemDefault()));
        fails(outOfRange.validate(), "requirement.error.priorities");
        assertTrue(ok(app.facade().sessions().open(session.id())).requirements().isEmpty());
    }

    @Test void explicitConfirmationPersistsAtomicallyAndReopensAfterRestart() {
        var session = session(); gemini.reply(AIRSHOW);
        var draft = ok(RequirementForm.apply(ok(app.facade().parse(session.id(), AIRSHOW_REQUEST)), Map.of("location", "Lakeshore airfield"), ZoneId.systemDefault()));
        var saved = ok(app.facade().planning().confirm(ok(app.facade().sessions().open(session.id())), draft));
        assertTrue(saved.confirmed()); assertTrue(saved.clarificationQuestions().isEmpty());
        var expected = RequirementForm.format(saved);
        app.close();
        app = new ApplicationBootstrap(gemini.environment(root));
        var restored = ok(app.facade().planning().requirement(session.id()));
        assertEquals(expected, RequirementForm.format(restored)); assertTrue(restored.confirmed());
        assertEquals(AIRSHOW_REQUEST, restored.originalText());
        assertEquals("Airshow", ok(app.facade().sessions().open(session.id())).title(), "session metadata is untouched");
        fails(app.facade().planning().requirement(session().id()), "requirement.error.none");
    }

    @Test void cancellationStaleSessionsHistoryAndStorageFailuresNeverOverwriteStoredData() throws Exception {
        var session = session(); gemini.reply(AIRSHOW);
        byte[] before = Files.readAllBytes(store());
        var draft = ok(app.facade().parse(session.id(), AIRSHOW_REQUEST));
        // Cancelling is discarding the draft: the store is byte-for-byte unchanged.
        assertArrayEquals(before, Files.readAllBytes(store()));
        fails(session.attachRequirement(draft), "requirement.error.unconfirmed");
        var opened = ok(app.facade().sessions().open(session.id()));
        assertTrue(app.facade().sessions().update(opened, "Renamed elsewhere", opened.date(), opened.location(), opened.notes()).isSuccess());
        fails(app.facade().planning().confirm(opened, draft), "error.session.changed");
        assertTrue(ok(app.facade().sessions().open(session.id())).requirements().isEmpty());
        var first = ok(app.facade().planning().confirm(session.id(), draft));
        var replacement = ok(RequirementForm.apply(first.asDraft(), Map.of("subject", "Helicopters"), ZoneId.systemDefault()));
        assertEquals("Aircraft at an airshow", ok(app.facade().planning().requirement(session.id())).subject(), "an edited draft does not overwrite until confirmed");
        assertEquals("Helicopters", ok(app.facade().planning().confirm(session.id(), replacement)).subject());
        var completed = ok(new JsonSessionRepository(app.storageScope().sessionsDirectory()).insert(SessionFixtures.linked(new CompletedState(), List.of(), false)));
        int requests = gemini.requests.size();
        fails(app.facade().parse(completed.id(), AIRSHOW_REQUEST), "requirement.error.historical");
        fails(app.facade().planning().confirm(completed.id(), replacement), "requirement.error.historical");
        assertEquals(requests, gemini.requests.size(), "historical sessions are rejected before any AI request");
        fails(app.facade().parse(UUID.randomUUID(), AIRSHOW_REQUEST), "error.session.missing");
        Files.writeString(store(), "broken");
        var failed = app.facade().planning().confirm(session.id(), replacement);
        assertFalse(failed.isSuccess(), "a persistence failure is never reported as saved");
        assertEquals("broken", Files.readString(store()));
    }

    @Test void geminiFailuresAreExplicitAndManualEntryStillConfirms() throws Exception {
        var session = session();
        gemini.status(503, ""); fails(app.facade().parse(session.id(), AIRSHOW_REQUEST), "ai.error.serviceUnavailable");
        gemini.status(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"); fails(app.facade().parse(session.id(), AIRSHOW_REQUEST), "ai.error.rateLimited");
        gemini.status(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}");
        var denied = app.facade().parse(session.id(), AIRSHOW_REQUEST); fails(denied, "ai.error.authentication");
        assertFalse(denied.errors().toString().contains(KEY));
        fails(app.facade().parse(session.id(), "   "), "requirement.error.request");
        fails(app.facade().parse(session.id(), "x".repeat(4001)), "requirement.error.requestLength");
        var environment = new HashMap<>(gemini.environment(root)); environment.put("LLM_API_KEY", "YOUR_LOCAL_API_KEY_HERE");
        int sent = gemini.requests.size();
        try (var unconfigured = new ApplicationBootstrap(environment)) {
            fails(unconfigured.facade().parse(session.id(), AIRSHOW_REQUEST), "ai.error.keyPlaceholder");
        }
        assertEquals(sent, gemini.requests.size(), "an unusable key never reaches the network");
        var manual = ok(RequirementForm.apply(RequirementForm.blank(AIRSHOW_REQUEST), Map.of("subject", "Aircraft", "cameraCount", "2",
            "lensChanges", "avoid", "duration", "3h"), ZoneId.systemDefault()));
        var saved = ok(app.facade().planning().confirm(session.id(), manual));
        assertEquals(AIRSHOW_REQUEST, saved.originalText()); assertEquals(Optional.of(false), saved.lensChangesAllowed());
    }

    @Test void malformedModelOutputIsRejectedInsteadOfRepaired() {
        var session = session();
        for (String reply : List.of("not json", "[1, 2]", "{\"subject\": \"A\"} trailing", "{\"subject\":\"A\",\"cameraCount\":\"two\"}", "{\"subject\":")) {
            gemini.reply(reply);
            fails(app.facade().parse(session.id(), AIRSHOW_REQUEST), "ai.error.malformedOutput");
        }
        gemini.reply("```json\n" + AIRSHOW + "\n```");
        assertEquals(Optional.of(2), ok(app.facade().parse(session.id(), AIRSHOW_REQUEST)).cameraCount());
        assertTrue(ok(app.facade().sessions().open(session.id())).requirements().isEmpty());
    }

    @Test void impossibleModelValuesBecomeUnspecifiedAndFlaggedNeverGuessed() {
        var session = session();
        gemini.reply(output("{\"subject\":\"Birds\",\"durationMinutes\":-30,\"cameraCount\":0,\"maxWeightGrams\":-1,\"date\":\"next Friday\","
            + "\"startTime\":\"18:00\",\"priorities\":[{\"name\":\"\",\"rank\":1}],\"location\":\"" + "x".repeat(600) + "\"}"));
        var draft = ok(app.facade().parse(session.id(), "Birds"));
        assertTrue(draft.duration().isEmpty() && draft.cameraCount().isEmpty() && draft.maxWeightGrams().isEmpty() && draft.date().isEmpty() && draft.location().isEmpty());
        assertEquals("18:00", draft.timeWindow(), "a clock time without a date is kept as text, not turned into a timestamp");
        assertEquals(Set.of("duration", "cameraCount", "maxWeight", "date", "priorities", "location"), draft.reviewFields());
        gemini.reply(output("{\"subject\":\"Birds\",\"cameraCount\":50,\"durationMinutes\":30000}"));
        var excessive = ok(app.facade().parse(session.id(), "Birds"));
        fails(excessive.validate(), "requirement.error.cameraCount"); fails(excessive.validate(), "requirement.error.duration");
        fails(app.facade().planning().confirm(session.id(), excessive), "requirement.error.cameraCount");
    }

    @Test void recordsWrittenBeforeF03FieldsLoadAsUnspecified() throws Exception {
        Path file = root.resolve("legacy/sessions.json"); Files.createDirectories(file.getParent());
        try (var input = getClass().getResourceAsStream("/persistence/legacy-v1-sessions.json")) { Files.copy(Objects.requireNonNull(input), file); }
        var requirement = ok(new JsonSessionRepository(file.getParent()).search("")).getFirst().requirements().orElseThrow();
        assertEquals(Optional.of(Duration.ofHours(1)), requirement.duration()); assertEquals(Optional.of(1), requirement.cameraCount());
        assertTrue(requirement.date().isEmpty()); assertEquals("", requirement.timeWindow()); assertEquals("", requirement.portability());
    }
}
