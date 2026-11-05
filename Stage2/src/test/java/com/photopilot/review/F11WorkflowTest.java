package com.photopilot.review;

import com.photopilot.agent.HistoryRetriever;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.agent.ToolRegistry;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.integration.ExifLibraryAdapter;
import com.photopilot.model.*;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.ExifAnalysis;
import com.photopilot.service.ReviewService;
import com.photopilot.service.StatisticsCalculator;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 F11 airshow acceptance through the real Facade -> ReviewService -> ExifLibraryAdapter (metadata-extractor)
 * -> StatisticsCalculator -> JSON persistence, and ReviewService -> PhotoPilotAgent -> GeminiAdapter against the
 * loopback Gemini fake. The session's plan is approved by the real F10 planner first.
 */
class F11WorkflowTest {
    @TempDir Path root;
    @TempDir Path photos;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    final PlanFixtures.Gear gear = new PlanFixtures.Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    PhotographySession session(UUID id) { return ok(app.facade().sessions().open(id)); }
    /** Completed airshow session with its photos analysed and saved. */
    UUID analysed() throws Exception {
        var id = ReviewFixtures.completed(app, gemini, gear);
        var analysis = ok(app.facade().reviews().analysis(id, ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id)), false));
        assertEquals(ExifAnalysis.Status.SAVED, analysis.status(), () -> analysis.notices().toString());
        return id;
    }

    @Test void uc12AnalysesLocallyWithoutGeminiSavesStatisticsAndRestoresThemAfterRestart() throws Exception {
        var id = ReviewFixtures.completed(app, gemini, gear);
        var planBefore = session(id).plans();
        var files = ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id));
        int requests = gemini.requests.size();
        var analysis = ok(app.facade().reviews().analysis(id, files, false));
        assertEquals(requests, gemini.requests.size(), "UC12 never calls the model");
        assertEquals(ExifAnalysis.Status.SAVED, analysis.status());
        var stats = analysis.statistics().orElseThrow();
        assertEquals(7, stats.importedCount()); assertEquals(6, stats.usableCount()); assertEquals(0, stats.failedFileCount());
        assertEquals(Map.of("Sony A1", 4, "SONY ILCE-7M4", 2), stats.cameraCounts());
        assertEquals(Map.of("Sony FE 200-600mm G", 3, "Sony FE 100-400mm GM", 2), stats.lensCounts());
        assertEquals(2, stats.missing("lens"), "the frame without lens data and the stripped JPEG");
        assertEquals(6, stats.count("focalLength")); assertEquals(6, stats.count("iso"));
        assertEquals(720, stats.summary("iso").orElseThrow().median(), 1e-12, "median of 320, 400, 640, 800, 1250, 1600");
        assertEquals(1.0 / 3200, stats.summary("shutter").orElseThrow().min(), 1e-15);
        assertEquals(6, stats.zonedCount()); assertTrue(stats.captureOffset().isPresent());
        // Statistics are attached to the same completed session; plan and lifecycle are untouched.
        var saved = session(id);
        assertEquals("COMPLETED", saved.status());
        assertTrue(saved.statistics().orElseThrow().sameEvidence(stats));
        assertEquals(planBefore.size(), saved.plans().size()); assertEquals(planBefore.getFirst().settings(), saved.plans().getFirst().settings());
        // The Stage 1 Facade route returns the same saved statistics for a repeated import.
        assertTrue(ok(app.facade().analyze(id, files)).sameEvidence(stats));
        app.close(); app = null;
        // Restart without any Gemini key or reachable endpoint: saved statistics are retrievable and UC12 still works.
        var offline = Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(),
            "GEMINI_ENDPOINT", "http://127.0.0.1:9/v1beta", "LLM_API_KEY", "");
        try (var restarted = new ApplicationBootstrap(offline)) {
            var inputs = ok(restarted.facade().reviews().inputs(id));
            assertTrue(inputs.statistics().orElseThrow().sameEvidence(stats), "statistics survive a restart");
            assertFalse(inputs.differences().isEmpty(), "plan comparison works offline");
            var again = ok(restarted.facade().reviews().analysis(id, files.subList(0, 3), true));
            assertEquals(ExifAnalysis.Status.REPLACED, again.status(), "analysis works with no key and no network");
        }
    }

    @Test void repeatedImportsReplacementNoDataAndWrongStatesNeverOverwriteSavedStatistics() throws Exception {
        var id = analysed();
        var files = ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id));
        var saved = session(id).statistics().orElseThrow();
        byte[] before = Files.readAllBytes(store());
        assertEquals(ExifAnalysis.Status.UNCHANGED, ok(app.facade().reviews().analysis(id, files, false)).status());
        var subset = ok(app.facade().reviews().analysis(id, files.subList(0, 2), false));
        assertEquals(ExifAnalysis.Status.REPLACE_REQUIRED, subset.status());
        assertEquals(2, subset.statistics().orElseThrow().importedCount(), "the new statistics are shown");
        var junk = List.of(ExifFixtures.plainJpeg(photos, "edited.jpg"), ExifFixtures.corruptJpeg(photos, "damaged.jpg"), ExifFixtures.png(photos, "screen.png"));
        var none = ok(app.facade().reviews().analysis(id, junk, true));
        assertEquals(ExifAnalysis.Status.NO_USABLE_DATA, none.status(), "even with replace, no-data results never overwrite");
        assertEquals(2, none.scan().failedFiles());
        assertTrue(app.facade().analyze(id, junk).hasError("review.error.noUsable"));
        assertArrayEquals(before, Files.readAllBytes(store()), "nothing was written");
        assertTrue(session(id).statistics().orElseThrow().sameEvidence(saved));
        assertTrue(app.facade().reviews().analysis(id, List.of(), false).hasError("review.error.noFiles"));
        var draftSession = ok(app.facade().sessions().create("Not shot yet", PlanFixtures.day())).id();
        assertTrue(app.facade().reviews().analysis(draftSession, files, false).hasError("review.error.notCompleted"), "statistics need a completed session");
        assertTrue(session(draftSession).statistics().isEmpty());
        var replaced = ok(app.facade().reviews().analysis(id, files.subList(0, 2), true));
        assertEquals(ExifAnalysis.Status.REPLACED, replaced.status());
        assertEquals(2, session(id).statistics().orElseThrow().importedCount());
    }

    @Test void uc13DraftIsGroundedUnsavedUntilConfirmedThenSavedAsReviewedAndRestored() throws Exception {
        var id = analysed();
        var inputs = ok(app.facade().reviews().inputs(id));
        assertTrue(inputs.canReview(), inputs.reviewBlockers()::toString);
        byte[] before = Files.readAllBytes(store());
        int sent = gemini.requests.size();
        gemini.replyWith(body -> ReviewFixtures.review(body, false));
        var draft = ok(app.facade().reviews().draft(id));
        var review = draft.review();
        assertEquals(PostShootReview.DRAFT, review.status()); assertEquals(Optional.of(id), review.sessionId());
        assertEquals(ReviewFixtures.plan(app, id).id(), review.comparedPlanId()); assertEquals(1, review.comparedRevision());
        assertTrue(review.summary().startsWith("Of the 7 photos imported, 6 had usable metadata"), review.summary());
        assertTrue(review.technicalObservations().getFirst().startsWith("Shutter speed: planned 1/1600-1/3200 s"), review.technicalObservations().toString());
        assertEquals(List.of("Median ISO was ISO 720."), review.exposureObservations());
        assertFalse(review.planDifferences().isEmpty(), "deterministic comparison is part of the review");
        assertTrue(review.planDifferences().stream().anyMatch(d -> d.startsWith("Recorded camera SONY ILCE-7M4")), review.planDifferences().toString());
        assertTrue(review.evidence().stream().anyMatch(e -> e.startsWith("Photos imported: 7; with usable metadata: 6")), review.evidence().toString());
        assertTrue(review.limitations().stream().anyMatch(l -> l.contains("does not show image quality")));
        assertEquals(0, draft.historySessions(), "no related history exists, so none is claimed");
        assertEquals(0, draft.removedStatements());
        // Generation never writes: the session is unchanged and still Completed.
        assertArrayEquals(before, Files.readAllBytes(store()));
        assertEquals("COMPLETED", session(id).status()); assertTrue(session(id).reviews().isEmpty());
        // Privacy: only aggregate evidence went to Gemini.
        String request = gemini.requests.get(sent);
        for (String secret : List.of("air-01.jpg", "air-07", "SourceFile", photos.toString().replace("\\", "\\\\"), String.valueOf(EnvironmentFixtures.LAT), "GPS"))
            assertFalse(request.contains(secret), "request leaked " + secret);
        var evidence = PlanFixtures.evidence(request);
        assertFalse(evidence.has("relatedHistory"), "missing history is not invented");
        assertEquals(7, evidence.getAsJsonObject("statistics").get("photosImported").getAsInt());
        assertTrue(request.contains("metadata only, never images"));

        var stored = ok(app.facade().reviews().save(id, review));
        assertEquals(PostShootReview.SAVED, stored.status());
        assertEquals("REVIEWED", session(id).status(), "the saved review performs Completed -> Reviewed");
        assertTrue(app.facade().reviews().save(id, review).hasError("review.error.foreign"), "a saved draft cannot be saved twice");
        assertTrue(app.facade().reviews().analysis(id, ReviewFixtures.photos(photos.resolve("again"), ReviewFixtures.plan(app, id)), true)
            .hasError("review.error.reviewedStatistics"), "a reviewed session keeps its evidence");
        assertFalse(ok(app.facade().reviews().inputs(id)).canReview());
        app.close(); app = boot();
        var restored = ok(app.facade().reviews().reviews(id));
        assertEquals(1, restored.size());
        var reopened = restored.getFirst();
        assertEquals(review.id(), reopened.id()); assertEquals(review.summary(), reopened.summary());
        assertEquals(review.planDifferences(), reopened.planDifferences()); assertEquals(review.technicalObservations(), reopened.technicalObservations());
        assertTrue(reopened.statistics().sameEvidence(review.statistics()));
        assertEquals(1, session(id).plans().size(), "the approved plan is preserved");
    }

    @Test void cancelledDraftsAndForeignDraftsAreNeverSaved() throws Exception {
        var id = analysed();
        var other = ok(app.facade().sessions().create("Another shoot", PlanFixtures.day())).id();
        gemini.replyWith(body -> ReviewFixtures.review(body, false));
        var draft = ok(app.facade().reviews().draft(id));
        byte[] before = Files.readAllBytes(store());
        app.facade().reviews().discard(id, draft.review());
        assertTrue(app.facade().reviews().save(id, draft.review()).hasError("review.error.foreign"), "a discarded draft cannot be saved");
        gemini.replyWith(body -> ReviewFixtures.review(body, false));
        var second = ok(app.facade().reviews().draft(id));
        assertTrue(app.facade().reviews().save(other, second.review()).hasError("review.error.foreign"), "drafts belong to their session");
        assertTrue(app.facade().reviews().save(id, second.review().savedCopy()).hasError("review.error.noDraft"), "only issued drafts are saved");
        assertTrue(app.facade().reviews().save(id, null).hasError("review.error.noDraft"));
        assertEquals("COMPLETED", session(id).status());
        assertTrue(session(id).reviews().isEmpty());
        assertArrayEquals(before, Files.readAllBytes(store()), "drafting, discarding and refused saves write nothing");
    }

    @Test void unsupportedClaimsAreRemovedAndMalformedOrFailedAiLeavesStatisticsUsable() throws Exception {
        var id = analysed();
        gemini.replyWith(body -> ReviewFixtures.review(body, true));
        var draft = ok(app.facade().reviews().draft(id));
        assertEquals(3, draft.removedStatements());
        var all = new ArrayList<String>(draft.review().technicalObservations()); all.addAll(draft.review().equipmentObservations()); all.addAll(draft.review().lessons());
        assertTrue(all.stream().noneMatch(s -> s.contains("sharp") || s.contains("97%") || s.contains("8/10")), all.toString());
        assertTrue(draft.notes().stream().anyMatch(n -> n.key().equals("review.note.removed")));
        assertTrue(draft.review().limitations().stream().anyMatch(l -> l.startsWith("3 AI statement(s) were removed")));
        app.facade().reviews().discard(id, draft.review());

        gemini.reply(ReviewFixtures.unsupportedOnly());
        assertTrue(app.facade().reviews().draft(id).hasError("review.error.unsupported"), "nothing supported means no review, not an invented one");
        gemini.reply("{\"step\":\"final\",\"result\":\"not an object\"}");
        assertTrue(app.facade().reviews().draft(id).hasError("review.error.malformed"));
        gemini.reply("definitely not json");
        assertTrue(app.facade().reviews().draft(id).hasError("review.error.malformed"));
        gemini.status(503, "{\"error\":{\"code\":503}}");
        var failed = app.facade().reviews().draft(id);
        assertTrue(failed.hasError("ai.error.serviceUnavailable"), failed.errors()::toString);
        assertFalse(app.facade().review(id).isSuccess(), "the Stage 1 Facade route reports the same failure (no reply queued)");
        var inputs = ok(app.facade().reviews().inputs(id));
        assertTrue(inputs.statistics().isPresent() && !inputs.differences().isEmpty(), "deterministic results survive AI failures");
        assertEquals("COMPLETED", session(id).status());
    }

    @Test void missingPrerequisitesAreNamedWithoutCallingGemini() throws Exception {
        var id = ReviewFixtures.completed(app, gemini, gear);
        int sent = gemini.requests.size();
        var noStats = app.facade().reviews().draft(id);
        assertTrue(noStats.hasError("review.missing.statistics"), noStats.errors()::toString);
        var bare = ok(app.facade().sessions().create("Draft only", PlanFixtures.day())).id();
        var blocked = app.facade().reviews().draft(bare);
        for (String key : List.of("review.missing.completed", "review.missing.statistics", "review.missing.plan", "review.missing.requirement"))
            assertTrue(blocked.hasError(key), key + " " + blocked.errors());
        assertEquals(sent, gemini.requests.size(), "no model request without the prerequisites");
    }

    @Test void persistenceFailuresAreNeverReportedAsSuccessAndRetryWorks() throws Exception {
        var id = analysed();
        // Portable fault injection: an atomic replace that always fails, behind a ReviewService whose agent would fail if called.
        var failing = new JsonSessionRepository(app.storageScope().sessionsDirectory()) {
            @Override protected void replace(Path temporary) throws IOException { throw new IOException("injected write failure"); }
        };
        var noModel = new PhotoPilotAgent(context -> Result.failureKey("error.notImplemented", "LLMClient"), new ToolRegistry(Map.of()), new HistoryRetriever(failing), 8);
        var service = new ReviewService(new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS), new StatisticsCalculator(), noModel, failing);
        byte[] before = Files.readAllBytes(store());
        var unsaved = ok(service.analysis(id, ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id)).subList(0, 2), true));
        assertEquals(ExifAnalysis.Status.SAVE_FAILED, unsaved.status());
        assertFalse(unsaved.status().persisted());
        assertTrue(unsaved.notices().stream().anyMatch(n -> n.key().equals("error.session.storage")), unsaved.notices()::toString);
        assertArrayEquals(before, Files.readAllBytes(store()));

        Assumptions.assumeTrue(Files.getFileAttributeView(store(), DosFileAttributeView.class) != null, "Windows read-only check");
        gemini.replyWith(body -> ReviewFixtures.review(body, false));
        var draft = ok(app.facade().reviews().draft(id));
        var attributes = Files.getFileAttributeView(store(), DosFileAttributeView.class);
        attributes.setReadOnly(true);
        try {
            var failed = app.facade().reviews().save(id, draft.review());
            assertFalse(failed.isSuccess(), "a failed write is never reported as a saved review");
            assertTrue(failed.hasError("error.session.storage"), failed.errors()::toString);
        } finally { attributes.setReadOnly(false); }
        assertArrayEquals(before, Files.readAllBytes(store()));
        assertEquals("COMPLETED", session(id).status(), "the session is not left Reviewed");
        assertEquals(PostShootReview.SAVED, ok(app.facade().reviews().save(id, draft.review())).status(), "the same draft can be saved once storage works");
        assertEquals("REVIEWED", session(id).status());
    }
}
