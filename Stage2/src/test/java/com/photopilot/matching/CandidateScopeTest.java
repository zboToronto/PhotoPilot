package com.photopilot.matching;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.GearMatchingService;
import com.photopilot.service.GearMatchingService.Scope;
import com.photopilot.strategy.CoverageRanking;
import com.photopilot.strategy.PortabilityRanking;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.validation.CompatibilityValidator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2Review1 (high): F07 recommendation, F07 selection and F10 offers share one candidate space. Before the fix,
 * selection and F10 regenerated candidates in the 3-body/4-lens default window while recommendation used the
 * profile-widened window, so a recommended four-body or five-lens loadout was rejected as stale and never offered to F10.
 * Loopback Gemini only (503 = deterministic ranking fallback); real F01/F05 JSON persistence in a temporary directory.
 */
class CandidateScopeTest {
    static final EquipmentType CAMERA = EquipmentType.CAMERA, LENS = EquipmentType.LENS;
    @TempDir Path root;
    FakeGemini gemini;
    ApplicationBootstrap app;
    final Crew gear = new Crew();

    /** Four Sony E bodies, five Sony E lenses (16-600 mm), shared batteries and cards: every pairing is verifiable. */
    static final class Crew {
        final Camera a1 = camera("Sony", "A1", "Sony E", 1, 737.0, Set.of("NP-FZ100"), Set.of("SD"), 30.0, true);
        final Camera a7 = camera("Sony", "A7 IV", "Sony E", 1, 658.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
        final Camera a7r = camera("Sony", "A7R V", "Sony E", 1, 723.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
        final Camera a9 = camera("Sony", "A9 III", "Sony E", 1, 702.0, Set.of("NP-FZ100"), Set.of("SD"), 120.0, true);
        final Lens wide = lens("Sony", "FE 16-35mm GM", "Sony E", 16, 35, 2.8, 82.0, 680.0, true);
        final Lens standard = lens("Sony", "FE 24-105mm G", "Sony E", 24, 105, 4, 77.0, 663.0, true);
        final Lens portrait = lens("Sony", "FE 85mm GM", "Sony E", 85, 85, 1.4, 77.0, 820.0, true);
        final Lens zoom = lens("Sony", "FE 100-400mm GM", "Sony E", 100, 400, 4.5, 77.0, 1395.0, true);
        final Lens tele = lens("Sony", "FE 200-600mm G", "Sony E", 200, 600, 5.6, 95.0, 2115.0, true);
        final Battery fz = battery("NP-FZ100", "NP-FZ100", 8, 83.0);
        final StorageMedia sd = card("Extreme 128GB", "SD", 4, 128, 200.0, 2.0);
        List<Equipment> items() { return List.of(a1, a7, a7r, a9, wide, standard, portrait, zoom, tele, fz, sd); }
        GearInventory inventory() { return new GearInventory(items()); }
    }

    static ShootRequirement requirement(int bodies, boolean lensChanges) {
        return new ShootRequirement("Wedding with " + bodies + " camera bodies", "Wedding ceremony and reception", List.of("Every key moment covered"),
            Optional.empty(), Optional.of(LocalDate.of(2026, 11, 21)), "afternoon", Optional.empty(), Optional.of(Duration.ofHours(4)), Optional.of(bodies),
            Optional.of(lensChanges), Optional.empty(), "", List.of(), Map.of("coverage", 1), Map.of(), List.of(), true);
    }
    /** Four bodies with one lens each (no lens changes) covering 16-600 mm. */
    static EquipmentRequirementProfile fourBodies() {
        return new EquipmentRequirementProfile(List.of(need("cameraCount", 4.0, 4.0, 1, true), need("lensCount", null, 4.0, 2, true),
            need("focalLength", 16.0, 600.0, 1, true)), true);
    }
    /** Two bodies and at least five lenses covering 16-600 mm. */
    static EquipmentRequirementProfile fiveLenses() {
        return new EquipmentRequirementProfile(List.of(need("cameraCount", 2.0, 2.0, 1, true), need("lensCount", 5.0, null, 1, true),
            need("focalLength", 16.0, 600.0, 1, true)), true);
    }

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(gemini.environment(root)); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    UUID session(List<? extends Equipment> items, ShootRequirement req, EquipmentRequirementProfile profile) {
        for (var item : items) ok(app.facade().inventory().add(item, true));
        var id = ok(app.facade().sessions().create("Wedding coverage", LocalDate.of(2026, 11, 21))).id();
        ok(app.facade().planning().confirm(id, req.asDraft()));
        ok(app.facade().planning().confirmProfile(id, profile.asDraft()));
        return id;
    }
    /** F07 through the Facade with the deterministic ranking (the loopback model answers 503). */
    GearMatchReport match(UUID id, String strategy) { gemini.status(503, ""); return ok(app.facade().planning().matchGear(id, strategy)); }
    PhotographySession open(UUID id) { return ok(app.facade().sessions().open(id)); }
    static Candidate full(GearMatchReport report, int bodies, int lenses) {
        return report.recommendations().stream().filter(c -> c.fullySuitable() && c.loadout().units(CAMERA) == bodies && c.loadout().units(LENS) == lenses)
            .findFirst().orElseThrow(() -> new AssertionError("no full " + bodies + "-body/" + lenses + "-lens match"));
    }
    /** Candidates F10 put in its model request; the model answers 503, so nothing is generated or saved. */
    JsonArray offered(UUID id) {
        int before = gemini.requests.size();
        gemini.status(503, "");
        assertFalse(app.facade().planning().generatePreview(id).isSuccess());
        assertTrue(gemini.requests.size() > before, "F10 sent its evidence");
        return PlanFixtures.evidence(gemini.requests.get(before)).getAsJsonArray("candidates");
    }
    static Set<String> names(Loadout loadout) {
        var names = new TreeSet<String>(); loadout.gear().forEach(g -> names.add(CompatibilityValidator.name(g))); return names;
    }
    static Set<String> names(JsonObject offer) {
        var names = new TreeSet<String>(); offer.getAsJsonArray("gear").forEach(g -> names.add(g.getAsJsonObject().get("name").getAsString())); return names;
    }
    static List<UUID> ids(GearMatchReport report) { return report.recommendations().stream().map(c -> c.loadout().id()).toList(); }
    static boolean everyBodyEquipped(Loadout loadout) { return CompatibilityValidator.simultaneousMounts(loadout, false) >= loadout.units(CAMERA); }
    GearMatchingService offline() {
        var agent = agent(root.resolve("offline"), new ArrayList<AgentContext>(), context -> Result.failureKey("ai.error.network"));
        return new GearMatchingService(new CompatibilityValidator(), new PortabilityRanking(agent), List.of(new CoverageRanking(agent)));
    }
    /** A well-formed candidate of whole owned records (identifier derived from its gear), as a client could submit it. */
    static Loadout combination(List<? extends Equipment> items) {
        var snapshots = items.stream().map(Equipment::snapshot).toList();
        return new Loadout(GearMatchingService.candidateId(snapshots), snapshots, Map.of(), new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of());
    }

    // A
    @Test void fourCameraRecommendationIsSelectedSavedAndRestored() throws Exception {
        var id = session(gear.items(), requirement(4, false), fourBodies());
        var report = match(id, "portability");
        var four = full(report, 4, 4);
        assertEquals(4, four.loadout().cameraLensAssignments().size(), "every body has its own lens mounted");
        assertTrue(report.scopeLimit().isEmpty());
        var stored = ok(app.facade().planning().selectLoadout(id, four.loadout()));
        assertEquals(four.loadout().id(), stored.id());
        assertEquals(4, stored.units(CAMERA)); assertTrue(stored.compatibility().fullySuitable());
        app.close(); app = boot();
        var reopened = open(id).selectedLoadout().orElseThrow();
        assertEquals(stored.id(), reopened.id()); assertEquals(names(stored), names(reopened)); assertEquals(4, reopened.units(CAMERA));
        assertTrue(ok(app.facade().planning().selection(id)).current(), "still owned, unchanged and compatible after reopening");
    }

    // B
    @Test void fiveLensRecommendationIsSelectedAndSaved() {
        var id = session(gear.items(), requirement(2, true), fiveLenses());
        var five = full(match(id, "coverage"), 2, 5);
        var stored = ok(app.facade().planning().selectLoadout(id, five.loadout()));
        assertEquals(five.loadout().id(), stored.id());
        assertEquals(5, stored.units(LENS)); assertTrue(stored.compatibility().fullySuitable());
        assertEquals(5, open(id).selectedLoadout().orElseThrow().units(LENS));
    }

    // C
    @Test void f10WithoutASelectionOffersTheFourBodyLoadoutsF07Recommends() {
        // No cameraCount need: only the confirmed requirement (four bodies) widens the space, for F07 and F10 alike.
        var focalOnly = new EquipmentRequirementProfile(List.of(need("focalLength", 16.0, 600.0, 1, true)), true);
        var id = session(gear.items(), requirement(4, false), focalOnly);
        var inputs = ok(app.facade().planning().planInputs(id));
        assertTrue(inputs.candidates() > 0, inputs.notes()::toString);
        assertTrue(inputs.notes().stream().noneMatch(n -> n.key().equals("plan.note.noCandidate")), inputs.notes()::toString);
        var offers = offered(id);
        assertFalse(offers.isEmpty());
        for (var element : offers) {
            var offer = element.getAsJsonObject();
            assertEquals(4, offer.get("cameraBodies").getAsInt()); assertTrue(offer.get("lensUnits").getAsInt() <= 4, "no lens changes");
            assertEquals("verified alternative", offer.get("source").getAsString());
        }
        var best = offers.get(0).getAsJsonObject();
        assertTrue(best.get("fullySuitable").getAsBoolean());
        var recommended = full(match(id, "portability"), 4, 4);
        assertEquals(names(recommended.loadout()), names(best), "F10 offers the F07 recommendation from the same space");
    }

    // D
    @Test void f10UsesTheSavedFourBodySelectionFirstAndNeverReplacesIt() {
        var id = session(gear.items(), requirement(4, false), fourBodies());
        var four = full(match(id, "portability"), 4, 4);
        ok(app.facade().planning().selectLoadout(id, four.loadout()));
        app.close(); app = boot();
        var inputs = ok(app.facade().planning().planInputs(id));
        assertTrue(inputs.candidates() >= 1);
        var keys = inputs.notes().stream().map(LocalizedMessage::key).toList();
        assertFalse(keys.contains("plan.note.selectionStale") || keys.contains("plan.note.selectionLimits"), keys::toString);
        var offers = offered(id);
        var first = offers.get(0).getAsJsonObject();
        assertEquals("F07 selection", first.get("source").getAsString());
        assertEquals(4, first.get("cameraBodies").getAsInt());
        assertEquals(names(four.loadout()), names(first));
        long same = 0; for (var offer : offers) if (names(offer.getAsJsonObject()).equals(names(four.loadout()))) same++;
        assertEquals(1, same, "the selection is offered once, not duplicated by the alternatives");
        assertEquals(four.loadout().id(), open(id).selectedLoadout().orElseThrow().id(), "planning never replaces the saved selection");
    }

    // E
    @Test void inventoryChangesAfterTheRecommendationAreStillRejected() throws Exception {
        var id = session(gear.items(), requirement(4, false), fourBodies());
        var first = full(match(id, "portability"), 4, 4);
        byte[] before = Files.readAllBytes(store());
        var fewer = new Battery(gear.fz.id(), "Sony", "NP-FZ100", 2, Optional.of(83.0), Set.of(), Map.of(), Optional.empty(), "NP-FZ100", 2280, Optional.of(500));
        ok(app.facade().inventory().update(fewer, true));
        var reduced = app.facade().planning().selectLoadout(id, first.loadout());
        assertTrue(reduced.hasError("compat.quantity"), reduced.errors()::toString);
        var second = full(match(id, "portability"), 4, 4);
        ok(app.facade().inventory().add(card("Extreme 64GB", "SD", 2, 64, 200.0, 2.0), true));
        var added = app.facade().planning().selectLoadout(id, second.loadout());
        assertTrue(added.hasError("match.error.staleCandidate"), "the current inventory now generates this combination with the new card: " + added.errors());
        var third = full(match(id, "portability"), 4, 4);
        ok(app.facade().inventory().remove(gear.wide.id(), true));
        var removed = app.facade().planning().selectLoadout(id, third.loadout());
        assertTrue(removed.hasError("compat.itemMissing"), removed.errors()::toString);
        var current = match(id, "portability").recommendations().getFirst().loadout();
        var forged = new Loadout(UUID.randomUUID(), current.gear(), current.cameraLensAssignments(), current.compatibility(), List.of(), List.of());
        assertTrue(app.facade().planning().selectLoadout(id, forged).hasError("match.error.staleCandidate"), "fabricated identifier");
        assertArrayEquals(before, Files.readAllBytes(store()), "no rejected selection writes");
        assertTrue(open(id).selectedLoadout().isEmpty());
    }

    // E (UC14): a well-formed, owned, in-range combination is still checked by the deterministic validator.
    @Test void wellFormedButIncompatibleCombinationIsRejectedByUc14() {
        var canon = lens("Canon", "EF 70-200mm L", "Canon EF", 70, 200, 2.8, 77.0, 1480.0, true);
        var items = new ArrayList<Equipment>(gear.items()); items.add(canon);
        var id = session(items, requirement(4, true), new EquipmentRequirementProfile(List.of(need("cameraCount", 4.0, 4.0, 1, true)), true));
        var sony = match(id, "portability").recommendations().stream().filter(c -> c.loadout().units(CAMERA) == 4 && c.loadout().units(LENS) == 3)
            .findFirst().orElseThrow().loadout();
        var withCanon = new ArrayList<>(sony.gear()); withCanon.add(canon.snapshot());
        var forged = new Loadout(GearMatchingService.candidateId(withCanon), withCanon, Map.of(), sony.compatibility(), List.of(), List.of());
        var rejected = app.facade().planning().selectLoadout(id, forged);
        assertTrue(rejected.hasError("compat.mountMismatch"), rejected.errors()::toString);
        assertTrue(open(id).selectedLoadout().isEmpty());
    }

    // F
    @Test void changedProfileOrRequirementIsEvaluatedNowNotReusedFromTheRecommendation() throws Exception {
        var id = session(gear.items(), requirement(2, true), fiveLenses());
        var five = full(match(id, "coverage"), 2, 5);
        var noLensNeed = new EquipmentRequirementProfile(List.of(need("cameraCount", 2.0, 2.0, 1, true), need("focalLength", 16.0, 600.0, 1, true)), false);
        ok(app.facade().planning().confirmProfile(open(id), noLensNeed, true));
        byte[] before = Files.readAllBytes(store());
        var outside = app.facade().planning().selectLoadout(id, five.loadout());
        assertTrue(outside.hasError("match.error.outsideScope"), outside.errors()::toString);
        assertFalse(outside.hasError("match.error.staleCandidate"), "a search limit is not reported as changed inventory");
        assertTrue(outside.errors().getFirst().contains("5 lenses") && outside.errors().getFirst().contains("4 lenses"), outside.errors()::toString);
        assertArrayEquals(before, Files.readAllBytes(store()));
        var longer = new EquipmentRequirementProfile(List.of(need("cameraCount", 2.0, 2.0, 1, true), need("lensCount", 5.0, null, 1, true),
            need("focalLength", 16.0, 800.0, 1, true)), false);
        ok(app.facade().planning().confirmProfile(open(id), longer, true));
        var stored = ok(app.facade().planning().selectLoadout(id, five.loadout()));
        assertTrue(five.fullySuitable(), "the outdated recommendation was a full match");
        assertFalse(stored.compatibility().fullySuitable(), "re-evaluated against the current profile");
        assertTrue(stored.unmetNeeds().stream().anyMatch(u -> u.contains("16-800")), stored.unmetNeeds()::toString);
        ok(app.facade().planning().confirm(id, requirement(3, true).asDraft()));
        var stale = app.facade().planning().selectLoadout(open(id), five.loadout(), true);
        assertTrue(stale.hasError("needs.error.stale"), "a profile contradicted by the re-confirmed requirement blocks selection");
        assertEquals(stored.unmetNeeds(), open(id).selectedLoadout().orElseThrow().unmetNeeds(), "the earlier saved selection is unchanged");
    }

    // G
    @Test void candidateValidityDoesNotDependOnTheRankingStrategy() {
        var id = session(gear.items(), requirement(4, false), fourBodies());
        var portability = match(id, "portability"); var coverage = match(id, "coverage");
        assertEquals(new HashSet<>(ids(portability)), new HashSet<>(ids(coverage)), "both strategies rank the same verified space");
        assertEquals(full(portability, 4, 4).loadout().id(), full(coverage, 4, 4).loadout().id());
        ok(app.facade().planning().selectLoadout(id, full(coverage, 4, 4).loadout()));
        var other = portability.recommendations().stream().filter(c -> c.loadout().units(CAMERA) == 4 && !c.fullySuitable()).findFirst().orElseThrow();
        var replaced = ok(app.facade().planning().selectLoadout(open(id), other.loadout(), true));
        assertEquals(other.loadout().id(), replaced.id());
        // AI-assisted order (mocked model reversing the sent candidates) changes nothing about validity.
        var inventory = gear.inventory(); var scope = GearMatchingService.scope(fourBodies(), requirement(4, false));
        var deterministic = ok(offline().analyze(fourBodies(), requirement(4, false), inventory, "portability", ""));
        int sent = Math.min(5, deterministic.recommendations().size());
        int[] reversed = new int[sent]; for (int i = 0; i < sent; i++) reversed[i] = sent - i;
        var agent = agent(root.resolve("ai"), new ArrayList<AgentContext>(), context -> json(ranking(reversed)));
        var aiService = new GearMatchingService(new CompatibilityValidator(), new PortabilityRanking(agent), List.of(new CoverageRanking(agent)));
        for (String strategy : List.of("portability", "coverage")) {
            var report = ok(aiService.analyze(fourBodies(), requirement(4, false), inventory, strategy, ""));
            assertEquals(new HashSet<>(ids(deterministic)), new HashSet<>(ids(report)), strategy);
            for (var candidate : report.recommendations())
                assertEquals(candidate.loadout().id(), ok(aiService.reconstruct(candidate.loadout(), inventory, scope)).id(), strategy);
        }
    }

    // H
    @Test void largestRangesStayBoundedAndRequestsBeyondTheCeilingAreReportedNotStale() {
        var bodies = new ArrayList<Equipment>(); var lenses = new ArrayList<Equipment>();
        for (int i = 1; i <= 7; i++) {
            bodies.add(camera("Sony", "Body " + i, "Sony E", 1, 700.0 + i, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true));
            lenses.add(lens("Sony", "Lens " + i, "Sony E", 10.0 * i, 10.0 * i + 50, 4, 77.0, 600.0 + i, true));
        }
        var items = new ArrayList<Equipment>(bodies); items.addAll(lenses);
        var inventory = new GearInventory(items);
        var req = requirement(ShootRequirement.MAX_CAMERAS, true);
        var profile = new EquipmentRequirementProfile(List.of(need("cameraCount", 10.0, 10.0, 1, true), need("lensCount", 10.0, null, 1, true)), true);
        var scope = GearMatchingService.scope(profile, req);
        assertEquals(new Scope(GearMatchingService.SEARCH_MAX_BODIES, GearMatchingService.SEARCH_MAX_LENSES, 10, 10), scope, "domain maxima are capped by the search ceiling");
        var service = offline();
        long started = System.nanoTime();
        var first = ok(service.analyze(profile, req, inventory, "portability", ""));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 30, "bounded work");
        var again = ok(service.analyze(profile, req, inventory, "portability", ""));
        assertEquals(ids(first), ids(again), "deterministic order");
        assertTrue(first.combinationsChecked() <= GearMatchingService.MAX_COMBINATIONS); assertTrue(first.searchLimited());
        for (var candidate : first.recommendations())
            assertTrue(candidate.loadout().units(CAMERA) <= 6 && candidate.loadout().units(LENS) <= 6);
        assertEquals("match.limit.scope", first.scopeLimit().orElseThrow().key());
        var summary = com.photopilot.application.LoadoutPresentation.summary(first, com.photopilot.i18n.LocalizationService.english());
        assertTrue(summary.stream().anyMatch(s -> s.contains("up to 6 camera bodies and 6 lenses")), summary::toString);
        // Validity is not truncation: a six-body, six-lens combination past the 5000-combination cut-off is still valid.
        var largest = combination(List.of(bodies.get(0), bodies.get(1), bodies.get(2), bodies.get(3), bodies.get(4), bodies.get(5),
            lenses.get(0), lenses.get(1), lenses.get(2), lenses.get(3), lenses.get(4), lenses.get(5)));
        assertFalse(ids(first).contains(largest.id()), "not enumerated");
        var rebuilt = ok(service.reconstruct(largest, inventory, scope));
        assertTrue(rebuilt.compatibility().isUsable()); assertEquals(6, rebuilt.units(CAMERA)); assertEquals(6, rebuilt.cameraLensAssignments().size());
        // Beyond the ceiling: an accurate limitation, never "stale".
        var seven = combination(List.of(bodies.get(0), bodies.get(1), bodies.get(2), bodies.get(3), bodies.get(4), bodies.get(5), bodies.get(6), lenses.get(0)));
        var outside = service.reconstruct(seven, inventory, scope);
        assertTrue(outside.hasError("match.error.outsideScope")); assertFalse(outside.hasError("match.error.staleCandidate"));
        // Same rules through the Facade (selection and F10).
        var id = session(items, req, profile);
        var tooLarge = app.facade().planning().selectLoadout(id, seven);
        assertTrue(tooLarge.hasError("match.error.outsideScope"), tooLarge.errors()::toString);
        var keys = ok(app.facade().planning().planInputs(id)).notes().stream().map(LocalizedMessage::key).toList();
        assertTrue(keys.contains("match.limit.scope") && keys.contains("plan.note.noCandidate"), "F10 says why no ten-body loadout is offered: " + keys);
        var saved = ok(app.facade().planning().selectLoadout(id, largest));
        assertEquals(largest.id(), saved.id()); assertFalse(saved.unmetNeeds().isEmpty(), "a partial match keeps its unmet needs");
    }

    // I
    @Test void cliSelectsTheFourBodyRecommendationThroughTheSharedService() {
        var id = session(gear.items(), requirement(4, false), fourBodies());
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        gemini.status(503, "");
        assertEquals(0, cli.execute("match run " + id + " portability"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("#1 Full match"), () -> bytes.toString(StandardCharsets.UTF_8));
        assertEquals(0, cli.execute("match select 1"));
        assertEquals(0, cli.execute("match confirm"), () -> bytes.toString(StandardCharsets.UTF_8));
        var saved = open(id).selectedLoadout().orElseThrow();
        assertEquals(4, saved.units(CAMERA));
        assertEquals(full(match(id, "portability"), 4, 4).loadout().id(), saved.id(), "CLI and direct service calls agree");
    }

    // J
    @Test void ordinaryConfigurationsKeepTheHistoricalDefaultSpace() {
        var airshow = new Airshow();
        var scope = GearMatchingService.scope(airshowProfile(), airshowRequirement());
        assertEquals(GearMatchingService.MAX_BODIES, scope.bodies()); assertEquals(GearMatchingService.MAX_LENSES, scope.lenses());
        assertEquals(Scope.DEFAULT.bodies(), GearMatchingService.scope(null, null).bodies());
        assertEquals(Scope.DEFAULT.lenses(), GearMatchingService.scope(null, null).lenses());
        var service = offline();
        // Same window as F02's default. The confirmed requirement (two bodies, lens changes avoided) additionally removes, through
        // UC14, exactly the loadouts in which a body cannot have its own lens (Stage2Review1 medium, PhysicalAssignmentTest).
        var byDefault = ok(service.compatibleCandidates(airshow.inventory()));
        assertEquals(byDefault.stream().filter(CandidateScopeTest::everyBodyEquipped).map(Loadout::id).toList(),
            ok(service.compatibleCandidates(airshow.inventory(), scope)).stream().map(Loadout::id).toList(), "F02 default and F07/F10 space agree otherwise");
        assertTrue(byDefault.stream().anyMatch(l -> !everyBodyEquipped(l)), "two bodies with one lens are only valid while lens changes are allowed");
        var withRequirement = ok(service.analyze(airshowProfile(), airshowRequirement(), airshow.inventory(), "portability", ""));
        assertEquals(ok(service.analyze(airshowProfile(), airshow.inventory(), "portability", "")).recommendations().stream()
            .filter(c -> everyBodyEquipped(c.loadout())).map(c -> c.loadout().id()).toList(), ids(withRequirement));
        assertTrue(withRequirement.scopeLimit().isEmpty());
        for (var candidate : withRequirement.recommendations()) {
            var rebuilt = ok(service.reconstruct(candidate.loadout(), airshow.inventory(), scope));
            assertEquals(candidate.loadout().id(), rebuilt.id()); assertEquals(candidate.loadout().cameraLensAssignments(), rebuilt.cameraLensAssignments());
        }
        var id = session(airshow.items(), airshowRequirement(), airshowProfile());
        var best = match(id, "portability").recommendations().getFirst();
        assertEquals(best.loadout().id(), ok(app.facade().planning().selectLoadout(id, best.loadout())).id(), "two-body airshow selection unchanged");
    }
}
