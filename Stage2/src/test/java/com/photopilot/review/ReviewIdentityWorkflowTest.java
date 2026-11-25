package com.photopilot.review;

import com.google.gson.JsonParser;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.ExifAnalysis;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 PP-03 end to end through the real F11 services, loopback Gemini fake and CLI: the plan's "Sony A7 IV" and
 * the EXIF "SONY ILCE-7M4" are one catalogued model. A reply that calls them different equipment loses that statement
 * before the draft is shown or saved; a review saved before this check keeps its stored text and is shown with a note.
 */
class ReviewIdentityWorkflowTest {
    @TempDir Path root;
    @TempDir Path photos;
    final PlanFixtures.Gear gear = new PlanFixtures.Gear();
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    static final String ALIAS_CLAIM = "The recorded camera was SONY ILCE-7M4 for 2 of 6 photos with camera data (33.3%), differing from the planned Sony A7 IV.";

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    UUID analysed() throws Exception {
        var id = ReviewFixtures.completed(app, gemini, gear);
        var analysis = ok(app.facade().reviews().analysis(id, ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id)), false));
        assertEquals(ExifAnalysis.Status.SAVED, analysis.status(), () -> analysis.notices().toString());
        return id;
    }
    /** The grounded fixture reply plus one equipment statement with correct figures and a wrong identity claim. */
    static String replyWithAliasClaim(String request) {
        var root = JsonParser.parseString(ReviewFixtures.review(request, false)).getAsJsonObject();
        root.getAsJsonObject("result").getAsJsonArray("equipmentObservations").add(ALIAS_CLAIM);
        return root.toString();
    }
    String cli(String... commands) {
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        for (String command : commands) assertEquals(0, cli.execute(command), command);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test void anAliasDifferenceIsRemovedBeforeTheDraftAndNeverSaved() throws Exception {
        var id = analysed();
        int sent = gemini.requests.size();
        gemini.replyWith(ReviewIdentityWorkflowTest::replyWithAliasClaim);
        var draft = ok(app.facade().reviews().draft(id));
        // The model was told the deterministic identity of the pair.
        var identity = PlanFixtures.evidence(gemini.requests.get(sent)).getAsJsonObject("equipmentIdentity").getAsJsonArray("pairs");
        assertTrue(identity.asList().stream().map(e -> e.getAsJsonObject()).anyMatch(p -> p.get("recorded").getAsString().equals("SONY ILCE-7M4")
            && p.get("planned").getAsString().equals("Sony A7 IV") && p.get("relation").getAsString().equals("same model")), identity.toString());
        var review = draft.review();
        assertFalse(review.equipmentObservations().contains(ALIAS_CLAIM), review.equipmentObservations().toString());
        assertTrue(review.equipmentObservations().stream().anyMatch(s -> s.startsWith("Planned camera Sony A1")), "the grounded statement is kept");
        assertEquals(1, draft.removedStatements());
        assertTrue(draft.notes().stream().anyMatch(n -> n.key().equals("review.note.identityRemoved") && n.arguments().equals(List.of(1))), draft.notes().toString());
        assertTrue(draft.notes().stream().noneMatch(n -> n.key().equals("review.note.removed")), "no figure or content removal to report");
        assertTrue(review.limitations().stream().anyMatch(l -> l.startsWith("1 AI statement(s) were removed because they made equipment identity claims")), review.limitations().toString());
        assertTrue(review.planDifferences().contains("Planned camera Sony A7 IV: 2 of 6 photos with camera data (33.3%), recorded as SONY ILCE-7M4."), review.planDifferences().toString());

        var saved = ok(app.facade().reviews().save(id, review));
        assertFalse(saved.equipmentObservations().contains(ALIAS_CLAIM));
        assertFalse(Files.readString(store()).contains("differing from the planned Sony A7 IV"), "validation happened before persistence");
        // Reopened through the CLI: the same validated text and no identity note.
        String output = cli("review open " + id);
        assertTrue(output.contains("Planned camera Sony A7 IV: 2 of 6 photos with camera data (33.3%), recorded as SONY ILCE-7M4."), output);
        assertFalse(output.contains("differing from the planned"), output);
        assertFalse(output.contains("Note: this saved statement"), output);
    }

    @Test void aReviewSavedBeforeTheCheckKeepsItsTextAndIsShownWithANote() throws Exception {
        var id = analysed();
        gemini.replyWith(body -> ReviewFixtures.review(body, false));
        var saved = ok(app.facade().reviews().save(id, ok(app.facade().reviews().draft(id)).review()));
        var session = ok(app.facade().sessions().open(id));
        // A legacy record: the same saved review as an earlier build stored it, with the wrong identity statement.
        var equipment = new ArrayList<>(saved.equipmentObservations()); equipment.add(ALIAS_CLAIM);
        var legacy = new PostShootReview(UUID.randomUUID(), saved.createdAt(), saved.statistics(), saved.comparedPlanId(), saved.comparedRevision(),
            saved.planDifferences(), saved.lessons(), saved.limitations(), Optional.empty(), saved.summary(), saved.technicalObservations(), equipment,
            saved.exposureObservations(), saved.improvements(), saved.evidence(), PostShootReview.SAVED);
        var old = new PhotographySession(UUID.randomUUID(), "Legacy airshow", session.date(), session.location(), "", session.createdAt(), session.updatedAt(),
            session.state(), session.requirements(), session.plans(), List.of(legacy), session.profile(), session.statistics(), List.of(), session.selectedLoadout());
        var inserted = ok(new JsonSessionRepository(app.storageScope().sessionsDirectory()).insert(old));
        byte[] before = Files.readAllBytes(store());

        String output = cli("review open " + inserted.id());
        assertTrue(output.contains("- " + ALIAS_CLAIM), "the stored statement is shown unchanged: " + output);
        assertTrue(output.contains("Note: this saved statement treats SONY ILCE-7M4 and Sony A7 IV as different equipment, but PhotoPilot's camera catalog lists both as one model: Sony α7 IV (ILCE-7M4)."), output);
        int statement = output.indexOf("- " + ALIAS_CLAIM), note = output.indexOf("Note: this saved statement");
        assertTrue(statement >= 0 && note > statement, "the note follows its statement");
        assertEquals(1, output.split("Note: this saved statement", -1).length - 1, "only the contradicted statement is noted");
        assertArrayEquals(before, Files.readAllBytes(store()), "reading never rewrites saved history");
        assertEquals(equipment, ok(app.facade().reviews().reviews(inserted.id())).getFirst().equipmentObservations());

        // The same note in Chinese, with the stored English statement unchanged.
        String zh = cli("lang zh-CN", "review open " + inserted.id());
        assertTrue(zh.contains("- " + ALIAS_CLAIM), zh);
        assertTrue(zh.contains("注意：这条已保存的陈述把 SONY ILCE-7M4 与 Sony A7 IV 当作不同器材"), zh);
        cli("lang en");
    }

    /** Stage2OptRe2 Review2 #05: the stricter numeral check applies to new drafts only; saved history is never rewritten. */
    @Test void aSavedStatementWithACountInWordsIsKeptAsSavedWhileANewDraftDropsIt() throws Exception {
        var id = analysed();
        String wrong = "本次共导入了九十九张照片。";
        gemini.replyWith(body -> ReviewFixtures.review(body, false).replace("\"technicalObservations\":[", "\"technicalObservations\":[\"" + wrong + "\","));
        var draft = ok(app.facade().reviews().draft(id)).review();
        assertFalse(draft.technicalObservations().contains(wrong), "a new draft does not keep an unsupported count written in words");
        var saved = ok(app.facade().reviews().save(id, draft));
        var session = ok(app.facade().sessions().open(id));
        var technical = new ArrayList<>(saved.technicalObservations()); technical.add(wrong);
        var legacy = new PostShootReview(UUID.randomUUID(), saved.createdAt(), saved.statistics(), saved.comparedPlanId(), saved.comparedRevision(),
            saved.planDifferences(), saved.lessons(), saved.limitations(), Optional.empty(), saved.summary(), technical, saved.equipmentObservations(),
            saved.exposureObservations(), saved.improvements(), saved.evidence(), PostShootReview.SAVED);
        var old = new PhotographySession(UUID.randomUUID(), "Legacy count", session.date(), session.location(), "", session.createdAt(), session.updatedAt(),
            session.state(), session.requirements(), session.plans(), List.of(legacy), session.profile(), session.statistics(), List.of(), session.selectedLoadout());
        var inserted = ok(new JsonSessionRepository(app.storageScope().sessionsDirectory()).insert(old));
        byte[] before = Files.readAllBytes(store());
        String zh = cli("lang zh-CN", "review open " + inserted.id());
        assertTrue(zh.contains(wrong), "the saved statement is shown as saved: " + zh);
        cli("lang en");
        assertEquals(technical, ok(app.facade().reviews().reviews(inserted.id())).getFirst().technicalObservations());
        assertArrayEquals(before, Files.readAllBytes(store()), "reading never rewrites saved history");
    }
}
