package com.photopilot.environment;

import com.photopilot.agent.*;
import com.photopilot.application.EnvironmentForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.integration.EnvironmentApiAdapter;
import com.photopilot.integration.LLMClient;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAnalysis.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.EnvironmentService;
import com.photopilot.service.LocationService;
import com.photopilot.validation.FeasibilityValidator;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AI-only retry and AI failure handling through the real Facade -> PlanningService -> EnvironmentService -> agent chain
 * against loopback provider and Gemini fakes: the retained evidence is interpreted again without any location, weather
 * or sun request, failures keep the facts, and stale, replaced, changed or concurrent retries are refused.
 */
class EnvironmentRetryTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;

    void start(Map<String, String> extra) throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        var environment = new HashMap<>(providers.environment(gemini.environment(root))); environment.putAll(extra);
        app = new ApplicationBootstrap(environment);
    }
    @AfterEach void stop() { if (app != null) app.close(); if (gemini != null) gemini.close(); if (providers != null) providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    UUID airshow() {
        var day = EnvironmentFixtures.day();
        var id = ok(app.facade().sessions().create("Toronto airshow", day)).id();
        ok(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()));
        return id;
    }
    EnvironmentRequest request(UUID id) {
        var values = EnvironmentForm.defaults(ok(app.facade().sessions().open(id))); values.putAll(EnvironmentFixtures.form(EnvironmentFixtures.day()));
        values.put("activity", ""); values.put("goals", "");
        return ok(EnvironmentForm.parse(values, app.facade().planning().location(), Optional.empty()));
    }
    long providerRequests() { return providers.count("/v1/forecast") + providers.count("/json") + providers.count("/v1/search"); }

    @Test void aiOnlyRetryReusesTheRetainedEvidenceAndAsksOnlyGemini() throws Exception {
        start(Map.of());
        var id = airshow(); var day = EnvironmentFixtures.day();
        gemini.status(503, "");
        var failed = ok(app.facade().assessEnvironment(id, request(id)));
        var analysis = app.facade().analyzeEnvironment(failed);
        assertEquals(AiStatus.UNAVAILABLE, analysis.ai().status());
        assertEquals(StageStatus.UNAVAILABLE, analysis.stage(StageKey.AI).orElseThrow().status());
        assertEquals(StageStatus.NOT_PERFORMED, analysis.stage(StageKey.VALIDATION).orElseThrow().status());
        assertFalse(failed.context().hourly().isEmpty() || failed.lightPhases().isEmpty(), "the facts survive the AI failure");
        long before = providerRequests(); int asked = gemini.requests.size();
        assertTrue(app.facade().planning().environmentRetryRefusal(id, failed).isEmpty(), "eligible");

        // A retry that fails again keeps the facts, says so and can itself be retried.
        gemini.status(503, "");
        var stillFailed = ok(app.facade().retryEnvironmentInterpretation(id, failed));
        assertEquals(AiStatus.UNAVAILABLE, app.facade().analyzeEnvironment(stillFailed).ai().status());
        assertSame(failed.context(), stillFailed.context());
        assertEquals(1, stillFailed.notices().stream().filter(n -> n.key().equals("env.notice.aiUnavailable")).count(), "one current AI failure, not an accumulation");
        assertEquals(before, providerRequests()); assertEquals(asked + 1, gemini.requests.size());
        assertTrue(app.facade().retryEnvironmentInterpretation(id, failed).hasError("env.retry.replaced"), "the earlier snapshot was replaced by the retry");
        failed = stillFailed; asked = gemini.requests.size();

        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        var retried = ok(app.facade().retryEnvironmentInterpretation(id, failed));
        assertEquals(before, providerRequests(), "no location, weather or sun request");
        assertEquals(asked + 1, gemini.requests.size(), "exactly one Gemini interpretation request");
        assertSame(failed.context(), retried.context(), "the same provider evidence");
        assertEquals(failed.context().retrievedAt(), retried.context().retrievedAt(), "old evidence is not marked as newly retrieved");
        assertEquals(failed.assessedAt(), retried.assessedAt());
        assertEquals(failed.request(), retried.request()); assertEquals(failed.lightPhases(), retried.lightPhases());
        assertTrue(retried.aiInterpreted());
        assertEquals(List.of("Afternoon flying display", "Golden-hour flybys"), retried.windows().stream().map(ShootingWindow::label).toList());
        assertEquals(1, retried.rejected().size(), "rejected proposals stay rejected");
        assertTrue(retried.notices().stream().anyMatch(n -> n.key().equals("env.notice.aiRetried")));
        assertTrue(retried.notices().stream().noneMatch(n -> n.key().equals("env.notice.aiUnavailable")), "the old AI failure is replaced");
        assertTrue(retried.notices().stream().anyMatch(n -> n.key().equals("env.notice.sessionDate")) == failed.notices().stream().anyMatch(n -> n.key().equals("env.notice.sessionDate")));
        assertSame(retried, app.facade().planning().environmentFor(id).orElseThrow(), "the session now uses the interpreted snapshot");
        assertFalse(gemini.requests.getLast().contains("43.6275") || gemini.requests.getLast().contains("-79.3961"), "coordinates never reach Gemini");
        var after = app.facade().analyzeEnvironment(retried);
        assertEquals(AiStatus.SUCCESS, after.ai().status());
        assertEquals(StageStatus.SUCCESS, after.stage(StageKey.VALIDATION).orElseThrow().status());
        assertTrue(app.facade().retryEnvironmentInterpretation(id, retried).hasError("env.retry.notNeeded"));
        assertEquals(asked + 1, gemini.requests.size());
        // The retried assessment is the one the session records.
        assertTrue(app.facade().planning().recordEnvironment(ok(app.facade().sessions().open(id)), retried).isSuccess());
    }

    @Test void retriesAreRefusedForClearedReplacedChangedOrConcurrentAssessmentsAndNeverOverwriteNewerOnes() throws Exception {
        start(Map.of());
        var id = airshow(); var day = EnvironmentFixtures.day();
        gemini.status(503, "");
        var first = ok(app.facade().assessEnvironment(id, request(id)));
        app.facade().planning().invalidateEnvironment(id);   // what editing a location, date, time or zone does
        assertTrue(app.facade().retryEnvironmentInterpretation(id, first).hasError("env.retry.none"));
        gemini.status(503, ""); var older = ok(app.facade().assessEnvironment(id, request(id)));
        gemini.status(503, ""); var newer = ok(app.facade().assessEnvironment(id, request(id)));
        assertTrue(app.facade().retryEnvironmentInterpretation(id, older).hasError("env.retry.replaced"));
        int asked = gemini.requests.size(); long fetched = providerRequests();

        // A newer full assessment or an input change during the retry wins: the late interpretation is discarded.
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        gemini.replyWith(body -> { started.countDown(); await(release); return EnvironmentFixtures.airshowInterpretation(day); });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var running = executor.submit(() -> app.facade().retryEnvironmentInterpretation(id, newer));
            assertTrue(started.await(10, TimeUnit.SECONDS));
            assertTrue(app.facade().retryEnvironmentInterpretation(id, newer).hasError("env.retry.busy"), "no duplicate concurrent retry");
            app.facade().planning().invalidateEnvironment(id);
            release.countDown();
            assertTrue(running.get(10, TimeUnit.SECONDS).hasError("env.retry.replaced"));
            assertTrue(app.facade().planning().environmentFor(id).isEmpty(), "a cleared session is not refilled by a late reply");
        } finally { release.countDown(); executor.shutdownNow(); }
        assertEquals(asked + 1, gemini.requests.size()); assertEquals(fetched, providerRequests());

        // A changed confirmed requirement needs a new full assessment.
        gemini.status(503, ""); var current = ok(app.facade().assessEnvironment(id, request(id)));
        var changed = new ShootRequirement("Airshow at night", "Night aviation photography", List.of("Aircraft lights"), Optional.empty(), Optional.of(day),
            "evening", Optional.empty(), Optional.of(Duration.ofHours(3)), Optional.of(2), Optional.of(false), Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), true);
        ok(app.facade().planning().confirm(id, changed.asDraft()));
        assertTrue(app.facade().planning().environmentRetryPrecheck(id, current).isEmpty(), "the in-memory check alone cannot see the requirement");
        assertTrue(app.facade().retryEnvironmentInterpretation(id, current).hasError("env.retry.requirementChanged"));
        assertEquals(asked + 2, gemini.requests.size(), "only the full assessment asked Gemini");
    }

    @Test void aTimedOutInterpretationKeepsTheFactsAndIsReportedAsTimedOut() throws Exception {
        start(Map.of("GEMINI_TIMEOUT_SECONDS", "5"));   // the smallest accepted timeout; the fake answers after it
        var id = airshow();
        var release = new CountDownLatch(1);
        gemini.replyWith(body -> { await(release); return "{}"; });
        try {
            var a = ok(app.facade().assessEnvironment(id, request(id)));
            var analysis = app.facade().analyzeEnvironment(a);
            assertEquals(AiStatus.TIMED_OUT, analysis.ai().status());
            assertEquals("ai.error.timeout", analysis.ai().reason().orElseThrow().key());
            assertEquals(StageStatus.TIMED_OUT, analysis.stage(StageKey.AI).orElseThrow().status());
            assertEquals(Overall.AI_INCOMPLETE, analysis.overall());
            assertTrue(analysis.stage(StageKey.WEATHER).orElseThrow().status().ok() && analysis.stage(StageKey.SUN).orElseThrow().status().ok());
            assertFalse(analysis.window().samples().isEmpty()); assertFalse(analysis.solar().isEmpty());
            assertTrue(a.windows().isEmpty(), "no unvalidated window is shown");
        } finally { release.countDown(); }
    }

    @Test void staleEvidenceIsNeverReinterpretedAndThePromptIsNotSent() throws Exception {
        var clock = new EnvironmentAssessmentTest.MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
        var prompts = new CopyOnWriteArrayList<AgentContext>();
        LLMClient model = context -> { prompts.add(context); return Result.failureKey("ai.error.serviceUnavailable", "503"); };
        try (var fake = new FakeEnvironmentProviders(); var http = HttpClient.newHttpClient()) {
            var adapter = new EnvironmentApiAdapter(fake.weatherEndpoint(), fake.sunEndpoint(), http, Duration.ofSeconds(5), clock);
            var agent = new PhotoPilotAgent(model, new ToolRegistry(Map.of()), new HistoryRetriever(new JsonSessionRepository(root)), 8);
            var service = new EnvironmentService(adapter, new LocationService(Optional.empty(), Optional.empty(), clock), agent, new FeasibilityValidator(clock), clock);
            var request = new EnvironmentRequest(EnvironmentAssessmentTest.AIRPORT, EnvironmentAssessmentTest.DAY, LocalTime.of(13, 0), LocalTime.of(19, 0),
                Optional.empty(), Optional.empty(), "Aviation", List.of());
            var a = ok(service.assess(null, request));
            assertEquals(1, prompts.size());
            assertTrue(service.reinterpretRefusal(a).isEmpty());
            clock.advance(Duration.ofHours(3).plusMinutes(1));
            assertTrue(service.reinterpret(null, a).hasError("env.retry.stale"));
            assertEquals(1, prompts.size(), "stale evidence is never sent to Gemini again");
            assertEquals(2, fake.requests.size(), "and no provider was asked either");
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(15, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
