package com.photopilot.environment;

import com.photopilot.agent.EnvironmentTool;
import com.photopilot.application.EnvironmentForm;
import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.EnvironmentService;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 F09 acceptance through the real Facade -> PlanningService -> EnvironmentService -> adapters / agent ->
 * GeminiAdapter chain against loopback provider and Gemini fakes, with real F01/F03 JSON persistence; then the CLI on
 * the same services must show the same findings.
 */
class F09WorkflowTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;

    @BeforeEach void start() throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        app = new ApplicationBootstrap(providers.environment(gemini.environment(root)));
    }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    UUID airshow() {
        var day = EnvironmentFixtures.day();
        var id = ok(app.facade().sessions().create("Toronto airshow", day)).id();
        ok(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()));
        return id;
    }

    @Test void torontoAirshowAssessmentThroughTheFacadeIsValidatedAndNeverSaved() throws Exception {
        var day = EnvironmentFixtures.day();
        var id = airshow();
        byte[] before = Files.readAllBytes(store());
        // The user looks up the place by name, chooses the match and reviews the date and availability.
        var places = ok(app.facade().planning().location().search(EnvironmentFixtures.PLACE));
        assertEquals(1, places.size());
        var values = EnvironmentForm.defaults(ok(app.facade().sessions().open(id)));
        assertEquals(day.toString(), values.get("date"), "prefilled from the confirmed requirement");
        assertEquals("", values.get("latitude"), "no coordinates are invented for a session without them");
        values.putAll(EnvironmentFixtures.form(day));
        var request = ok(EnvironmentForm.parse(values, app.facade().planning().location(), places.getFirst()));
        assertTrue(request.place().approximate(), "a chosen gazetteer point stays labelled approximate");
        assertEquals(places.getFirst().zone(), request.zoneHint());
        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));

        var a = ok(app.facade().assessEnvironment(id, request));
        assertEquals("Aviation / Airshow photography", a.subject());
        assertEquals(FakeEnvironmentProviders.TORONTO, a.availability().orElseThrow().start().getZone());
        assertEquals(List.of("Afternoon flying display", "Golden-hour flybys"), a.windows().stream().map(ShootingWindow::label).toList());
        assertEquals(1, a.rejected().size(), "the window outside the availability is rejected");
        for (var window : a.windows()) {
            assertTrue(a.availability().orElseThrow().contains(window.start(), window.end()));
            assertFalse(window.arrival().isAfter(window.start()));
            assertFalse(window.arrival().isBefore(a.availability().orElseThrow().start()));
        }
        assertTrue(a.context().sunset().isPresent() && !a.context().hourly().isEmpty());
        assertTrue(a.aiInterpreted());
        assertTrue(app.facade().planning().environmentStaleFields(a).isEmpty(), "fresh evidence is usable by F10");
        // Nothing was persisted and the confirmed F03 requirement is unchanged.
        assertArrayEquals(before, Files.readAllBytes(store()));
        var session = ok(app.facade().sessions().open(id));
        assertEquals("DRAFT", session.status());
        assertEquals("afternoon and evening", session.requirements().orElseThrow().timeWindow());
        // Gemini receives evidence but never coordinates or the API key; providers receive coordinates but no shoot details.
        String body = gemini.requests.getLast();
        assertTrue(body.contains("responseJsonSchema") && body.contains("Aviation / Airshow photography") && body.contains("goldenEvening"));
        assertFalse(body.contains(FakeGemini.KEY) || body.contains("43.6275") || body.contains("-79.3961") || body.contains(id.toString()));
        assertTrue(providers.requests.stream().noneMatch(uri -> uri.toString().contains("Airshow") || uri.toString().contains("Aviation")));
        // Stage 1 SD03 entry: a label-only requirement cannot be assessed and the missing inputs are named.
        assertTrue(app.facade().assessEnvironment(id).hasError("env.error.needsInput"));
        assertFalse(app.facade().assessEnvironment(UUID.randomUUID(), request).isSuccess(), "unknown session");
        assertTrue(app.facade().assessEnvironment(null, request).hasError("error.session.id"));
    }

    @Test void environmentToolReturnsOnlyFactualEvidenceForLaterPlanning() throws Exception {
        var planning = app.facade().planning();
        var field = planning.getClass().getDeclaredField("environment"); field.setAccessible(true);
        var tool = new EnvironmentTool((EnvironmentService) field.get(planning));
        var json = ok(tool.execute("{\"latitude\":43.6275,\"longitude\":-79.3961,\"date\":\"" + EnvironmentFixtures.day() + "\"}"));
        assertTrue(json.contains("\"timeZone\":\"America/Toronto\"") && json.contains("\"sunEvents\"") && json.contains("\"cloudCoverPct\":40.0"));
        assertTrue(gemini.requests.isEmpty(), "retrieval never calls the model");
        assertFalse(tool.execute("{\"latitude\":91,\"longitude\":0,\"date\":\"2026-11-07\"}").isSuccess());
        assertFalse(tool.execute("{\"latitude\":43.6,\"longitude\":-79.4,\"date\":\"2026-11-07\",\"label\":\"x\"}").isSuccess(), "unknown keys rejected");
    }

    @Test void cliRunsTheSameWorkflowAndShowsTheSameFindingsAsTheSharedPresentation() throws Exception {
        var day = EnvironmentFixtures.day();
        var id = airshow();
        var bytes = new ByteArrayOutputStream();
        var localization = app.localization();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), localization);
        assertEquals(0, cli.execute("F09")); assertTrue(text(bytes).contains("env assess <session-id>"));
        assertEquals(0, cli.execute("env show " + id));
        assertTrue(text(bytes).contains("Subject: Aviation / Airshow photography") && text(bytes).contains("date = " + day));
        assertEquals(0, cli.execute("env place Toronto"));
        assertTrue(text(bytes).contains("3 matching places found") && text(bytes).contains("Toronto, Ohio, United States"), text(bytes));
        assertEquals(2, cli.execute("env place Nowhere Placeville"));
        assertEquals(2, cli.execute("env device"), "device location needs explicit consent");
        assertEquals(2, cli.execute("env device consent"));
        assertTrue(text(bytes).contains("not supported on this computer"));
        String from = EnvironmentFixtures.clock(EnvironmentFixtures.from(day)), until = EnvironmentFixtures.clock(EnvironmentFixtures.until(day));
        String args = " location=\"" + EnvironmentFixtures.PLACE + "\" latitude=43.62750 longitude=-79.39610 from=" + from + " until=" + until + " lead=30";
        assertEquals(2, cli.execute("env assess " + id + " latitude=95" + args.replace(" latitude=43.62750", "")));
        assertTrue(text(bytes).contains("Latitude must be between -90 and 90"));
        assertEquals(2, cli.execute("env again"), "no successful inputs yet");

        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        bytes.reset();
        assertEquals(0, cli.execute("env assess " + id + args));
        String output = text(bytes);
        // Same inputs through the shared Facade (cached, identical evidence) and the same Gemini reply.
        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        var values = EnvironmentForm.defaults(ok(app.facade().sessions().open(id))); values.putAll(EnvironmentFixtures.form(day));
        values.put("activity", ""); values.put("goals", "");
        var shared = ok(app.facade().assessEnvironment(id, ok(EnvironmentForm.parse(values, app.facade().planning().location(), Optional.empty()))));
        assertEquals(2, shared.windows().size());
        for (var line : EnvironmentPresentation.summary(shared, localization)) assertTrue(output.contains(line), line);
        for (var card : EnvironmentPresentation.windows(shared, localization)) for (var line : card) assertTrue(output.contains(line), line);
        for (var line : EnvironmentPresentation.evidence(shared, localization)) assertTrue(output.contains(line), line);
        for (var line : EnvironmentPresentation.rejected(shared, localization)) assertTrue(output.contains(line), line);
        assertTrue(output.contains("Verified provider data") && output.contains("AI interpretation (Gemini; advice, not measurements)"));
        assertTrue(output.contains("Travel time, parking, access, crowds and event schedules are not known"));

        gemini.status(503, "");
        bytes.reset();
        assertEquals(0, cli.execute("env again lead=10"), "reassessment with changed inputs");
        assertTrue(text(bytes).contains("AI interpretation unavailable") && text(bytes).contains("Sunset:"), text(bytes));
        assertEquals(2, cli.execute("env assess " + id + " colour=blue"));
        assertEquals(0, cli.execute("env place \"" + EnvironmentFixtures.PLACE + "\""));
        gemini.status(503, "");
        bytes.reset();
        assertEquals(0, cli.execute("env assess " + id + " place=1 from=" + from + " until=" + until));
        assertTrue(text(bytes).contains("approximate gazetteer point"), text(bytes));
        assertEquals(2, cli.execute("env assess " + id + " place=7 from=" + from + " until=" + until), "unknown place number");
        LocalizationService chinese = app.localization(); chinese.useLanguage("zh-CN");
        bytes.reset();
        gemini.status(503, "");
        assertEquals(0, cli.execute("env again"));
        assertTrue(text(bytes).contains("已核实的数据提供方数据"), text(bytes));
        chinese.useLanguage("en");
    }
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
}
