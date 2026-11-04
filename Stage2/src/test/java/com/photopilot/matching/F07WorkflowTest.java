package com.photopilot.matching;

import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 airshow acceptance through the real Facade -> PlanningService -> GearMatchingService -> Strategy ->
 * agent -> GeminiAdapter chain against a loopback Gemini stand-in, with real F01/F05 JSON persistence.
 */
class F07WorkflowTest {
    @TempDir Path root;
    FakeGemini gemini;
    ApplicationBootstrap app;
    final Airshow gear = new Airshow();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); app = new ApplicationBootstrap(gemini.environment(root)); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    UUID airshowSession(boolean withProfile) {
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var id = ok(app.facade().sessions().create("Lakeshore airshow", LocalDate.of(2026, 11, 14))).id();
        ok(app.facade().planning().confirm(id, airshowRequirement().asDraft()));
        if (withProfile) ok(app.facade().planning().confirmProfile(id, airshowProfile().asDraft()));
        return id;
    }

    @Test void capabilityProfileIsValidatedAgainstTheConfirmedRequirementAndOnlySavedWhenConfirmed() throws Exception {
        var bare = ok(app.facade().sessions().create("No requirement", LocalDate.of(2026, 11, 20))).id();
        assertTrue(app.facade().planning().confirmProfile(bare, airshowProfile().asDraft()).hasError("needs.error.requirement"));
        var id = airshowSession(false);
        byte[] before = Files.readAllBytes(store());
        assertTrue(app.facade().planning().confirmProfile(id, new EquipmentRequirementProfile(List.of(), false)).hasError("needs.error.empty"));
        var threeBodies = new EquipmentRequirementProfile(List.of(need("cameraCount", 3.0, null, 1, true)), false);
        assertTrue(app.facade().planning().confirmProfile(id, threeBodies).hasError("needs.error.cameraConflict"), "contradicts the confirmed two-body requirement");
        assertTrue(app.facade().planning().profile(id).hasError("needs.error.none"));
        assertTrue(app.facade().planning().matchGear(id, "portability").hasError("needs.error.none"), "F07 needs a confirmed profile");
        assertArrayEquals(before, Files.readAllBytes(store()), "rejected confirmations write nothing");
        var saved = ok(app.facade().planning().confirmProfile(id, airshowProfile().asDraft()));
        assertTrue(saved.reviewed()); assertEquals(6, saved.needs().size());
        assertTrue(ok(app.facade().planning().profile(id)).reviewed());
        assertTrue(gemini.requests.isEmpty(), "the minimal F06 path never calls Gemini");
        assertFalse(app.facade().analyzeNeeds(id).isSuccess(), "AI capability analysis remains unavailable");
    }

    @Test void airshowAcceptanceMatchesRanksSelectsSavesAndRestores() throws Exception {
        var id = airshowSession(true);
        gemini.status(503, "");
        var fallback = ok(app.facade().planning().matchGear(id, "portability"));
        assertFalse(fallback.aiRanked()); assertEquals("match.ranking.fallback", fallback.rankingNotice().orElseThrow().key());
        int sent = Math.min(5, fallback.recommendations().size());
        int[] order = new int[sent]; for (int i = 0; i < sent; i++) order[i] = i + 1;
        gemini.reply(ranking(order));
        var report = ok(app.facade().planning().matchGear(id, "portability"));
        assertTrue(report.aiRanked(), () -> report.rankingNotice().toString());
        var best = report.recommendations().getFirst();
        assertTrue(best.fullySuitable());
        assertTrue(best.aiExplanation().isPresent());
        String body = gemini.requests.getLast();
        assertFalse(body.contains(FakeGemini.KEY)); assertFalse(body.contains(id.toString()));
        for (var item : gear.items()) assertFalse(body.contains(item.id().toString()), "inventory identifiers stay local");
        assertTrue(body.contains("responseJsonSchema") && body.contains("Aircraft at an airshow"));
        assertTrue(app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().isEmpty(), "matching never saves");
        // Explicit selection: revalidated, saved, and an existing selection is never silently replaced.
        var stored = ok(app.facade().planning().selectLoadout(id, best.loadout()));
        assertEquals(best.loadout().id(), stored.id());
        assertTrue(stored.compatibility().isUsable() && stored.compatibility().fullySuitable());
        assertTrue(stored.tradeOffs().stream().anyMatch(t -> t.startsWith("Verified total weight")));
        assertTrue(stored.tradeOffs().stream().noneMatch(t -> t.contains("Trade-off note")), "AI text is not saved as equipment fact");
        assertTrue(app.facade().planning().selectLoadout(id, report.recommendations().get(1).loadout()).hasError("match.error.alreadySelected"));
        assertTrue(ok(app.facade().inventory().affectedSessions(gear.tele.id())).contains(id), "F05 warns before removing selected gear");
        app.close();
        app = new ApplicationBootstrap(gemini.environment(root));
        var reopened = ok(app.facade().sessions().open(id));
        assertEquals(best.loadout().id(), reopened.selectedLoadout().orElseThrow().id());
        assertEquals(stored.gear().size(), reopened.selectedLoadout().orElseThrow().gear().size());
        var status = ok(app.facade().planning().selection(id));
        assertTrue(status.current()); assertTrue(status.evaluation().fullySuitable());
        var partial = report.recommendations().stream().filter(c -> !c.fullySuitable()).findFirst().orElseThrow();
        var replaced = ok(app.facade().planning().selectLoadout(reopened, partial.loadout(), true));
        assertEquals(partial.loadout().id(), replaced.id()); assertFalse(replaced.unmetNeeds().isEmpty(), "partial selection keeps its unmet needs");
    }

    @Test void staleUnknownAndConflictingSelectionsAreRejectedWithoutWriting() throws Exception {
        var id = airshowSession(true);
        gemini.status(503, "");
        var best = ok(app.facade().planning().matchGear(id, "coverage")).recommendations().getFirst();
        var session = ok(app.facade().sessions().open(id));
        byte[] before = Files.readAllBytes(store());
        var forged = new Loadout(UUID.randomUUID(), best.loadout().gear(), best.loadout().cameraLensAssignments(), best.loadout().compatibility(), List.of(), List.of());
        assertTrue(app.facade().planning().selectLoadout(id, forged).hasError("match.error.staleCandidate"), "unknown candidate identifier");
        var notOwned = new Loadout(UUID.randomUUID(), List.of(camera("Nikon", "Z9", "Nikon Z", 1, 1340.0, Set.of(), Set.of(), 20.0, true).snapshot()), Map.of(),
            best.loadout().compatibility(), List.of(), List.of());
        assertTrue(app.facade().planning().selectLoadout(id, notOwned).hasError("compat.itemMissing"), "never saves equipment the user does not own");
        assertTrue(app.facade().planning().selectLoadout(id, null).hasError("match.error.noSelection"));
        ok(app.facade().sessions().save(session.edit("Lakeshore airshow (edited)", session.date(), session.location(), session.notes())));
        byte[] edited = Files.readAllBytes(store());
        assertTrue(app.facade().planning().selectLoadout(session, best.loadout(), false).hasError("error.session.changed"), "stale session version");
        assertArrayEquals(edited, Files.readAllBytes(store()), "a failed save reports failure and writes nothing");
        var heavier = new Lens(gear.zoom.id(), "Sony", "FE 100-400mm GM", 1, Optional.of(1500.0), Set.of(), Map.of(), Optional.empty(), "Sony E", 100, 400, 4.5,
            Optional.of(77.0), Optional.of(true), Optional.of(true));
        ok(app.facade().inventory().update(heavier, true));
        var changed = app.facade().planning().selectLoadout(id, best.loadout());
        assertFalse(changed.isSuccess());
        assertTrue(changed.hasError("compat.itemChanged") || changed.hasError("match.error.staleCandidate"), changed.errors().toString());
        assertTrue(ok(app.facade().sessions().open(id)).selectedLoadout().isEmpty());
        assertNotEquals(0, before.length);
    }
}
