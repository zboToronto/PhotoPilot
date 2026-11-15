package com.photopilot.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.application.ReviewPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.model.PostShootReview;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.ExifAnalysis;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2Review1 (medium) through the real F11 path: Facade -> ReviewService.draft -> loopback Gemini -> ReviewEvidence,
 * then save and the shared GUI/CLI presentation. The airshow photos record ISO 400 (one frame), a mean focal length of
 * 346.7 mm and no ISO 3200, while the approved plan allows ISO 100-3200.
 */
class SemanticReviewWorkflowTest {
    @TempDir Path root;
    @TempDir Path photos;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    UUID analysed() throws Exception {
        var id = ReviewFixtures.completed(app, gemini, new PlanFixtures.Gear());
        var analysis = ok(app.facade().reviews().analysis(id, ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id)), false));
        assertEquals(ExifAnalysis.Status.SAVED, analysis.status());
        return id;
    }
    /** One cited fact, two misattributed figures (ISO 400 as a focal length average, the planned ISO 3200 as recorded) and advice. */
    static String reply(String request) {
        var evidence = PlanFixtures.evidence(request);
        String iso = evidence.getAsJsonObject("statistics").getAsJsonObject("iso").get("id").getAsString();
        var result = new JsonObject();
        result.addProperty("summary", "Median ISO was ISO 720 [" + iso + "].");
        result.add("technicalObservations", strings("The average focal length was 400 mm."));
        result.add("equipmentObservations", strings());
        result.add("exposureObservations", strings("ISO 3200 was recorded.", "Median ISO was ISO 720 [" + iso + "]."));
        result.add("lessons", strings("Consider a faster shutter speed for banking passes."));
        result.add("improvements", strings("Next time try 1/4000 s for jets passing close."));
        result.add("limitations", strings("Only metadata was available; image content was not reviewed."));
        var root = new JsonObject(); root.addProperty("step", "final"); root.add("result", result);
        return root.toString();
    }
    static JsonArray strings(String... values) { var array = new JsonArray(); for (String v : values) array.add(v); return array; }

    // S
    @Test void unsupportedFiguresNeverReachTheDraftOrTheSavedReview() throws Exception {
        var id = analysed();
        gemini.replyWith(SemanticReviewWorkflowTest::reply);
        var draft = ok(app.facade().reviews().draft(id));
        var review = draft.review();
        assertEquals(2, draft.removedStatements());
        assertTrue(draft.notes().stream().anyMatch(n -> n.key().equals("review.note.removed")), "the draft says statements were removed");
        assertEquals("Median ISO was ISO 720.", review.summary());
        assertTrue(review.technicalObservations().isEmpty());
        assertEquals(List.of("Median ISO was ISO 720."), review.exposureObservations());
        assertEquals(List.of("Next time try 1/4000 s for jets passing close."), review.improvements(), "a proposed value is advice, not a fact");
        assertTrue(review.limitations().stream().anyMatch(l -> l.startsWith("2 AI statement(s) were removed")));
        var saved = ok(app.facade().reviews().save(id, review));
        assertEquals(PostShootReview.SAVED, saved.status());
        var stored = ok(app.facade().reviews().reviews(id)).getFirst();
        var all = new ArrayList<String>(List.of(stored.summary())); all.addAll(stored.technicalObservations()); all.addAll(stored.exposureObservations());
        assertTrue(all.stream().noneMatch(t -> t.contains("400 mm") || t.contains("3200") || t.contains("[")), all::toString);
    }

    // U
    @Test void guiAndCliShowTheSameCheckedStatementsFromTheSharedService() throws Exception {
        var id = analysed();
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        gemini.replyWith(SemanticReviewWorkflowTest::reply);
        assertEquals(0, cli.execute("review generate " + id), () -> bytes.toString(StandardCharsets.UTF_8));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Median ISO was ISO 720."), output);
        assertFalse(output.contains("The average focal length was 400 mm") || output.contains("ISO 3200 was recorded") || output.contains("[S"), output);
        gemini.replyWith(SemanticReviewWorkflowTest::reply);
        var draft = ok(app.facade().reviews().draft(id)).review();
        for (String section : List.of("summary", "technical", "exposure", "improvements"))
            for (String line : ReviewPresentation.section(draft, section, app.localization())) assertTrue(output.contains(line), section + ": " + line);
    }
}
