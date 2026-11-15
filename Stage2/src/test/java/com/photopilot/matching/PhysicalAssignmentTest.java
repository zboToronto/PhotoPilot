package com.photopilot.matching;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.agent.AgentContext;
import com.photopilot.application.LoadoutPresentation;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.GearMatchingService;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.strategy.CoverageRanking;
import com.photopilot.strategy.PortabilityRanking;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.CompatibilityValidator.Mounting;
import com.photopilot.validation.PlanValidator;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2Review1 (medium): UC14 per-unit camera-lens allocation. Assignments name inventory records and one record may
 * stand for several bodies; before the fix a loadout passed UC14 when its recorded mounts were valid, even if some bodies
 * could never have their own lens while lens changes were to be avoided, or no carried lens fitted a body at all.
 * Deterministic fixtures, loopback Gemini (503 = deterministic fallback), real F01/F05 JSON persistence in a temporary
 * directory; no live service is called.
 */
class PhysicalAssignmentTest {
    static final EquipmentType CAMERA = EquipmentType.CAMERA, LENS = EquipmentType.LENS;
    @TempDir Path root;
    FakeGemini gemini;
    ApplicationBootstrap app;
    final CompatibilityValidator validator = new CompatibilityValidator();

    /** Two bodies of one model under a single inventory record. */
    final Camera pair = camera("Sony", "A7 IV", "Sony E", 2, 658.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
    final Camera a1 = camera("Sony", "A1", "Sony E", 1, 737.0, Set.of("NP-FZ100"), Set.of("SD"), 30.0, true);
    final Camera a9 = camera("Sony", "A9 III", "Sony E", 1, 702.0, Set.of("NP-FZ100"), Set.of("SD"), 120.0, true);
    final Camera r5 = camera("Canon", "EOS R5", "Canon RF", 1, 738.0, Set.of("LP-E6NH"), Set.of("SD"), 20.0, true);
    final Lens standard = lens("Sony", "FE 24-105mm G", "Sony E", 24, 105, 4, 77.0, 663.0, true);
    final Lens zoom = lens("Sony", "FE 100-400mm GM", "Sony E", 100, 400, 4.5, 77.0, 1395.0, true);
    final Lens rf = lens("Canon", "RF 24-70mm F2.8L", "Canon RF", 24, 70, 2.8, 82.0, 900.0, true);
    final Lens vintage = lens("Vintage", "135mm f/2.8", "unknown", 135, 135, 2.8, null, 450.0, false);
    final Battery fz = battery("NP-FZ100", "NP-FZ100", 4, 83.0);
    final Battery lp = new Battery(UUID.randomUUID(), "Canon", "LP-E6NH", 2, Optional.of(80.0), Set.of(), Map.of(), Optional.empty(), "LP-E6NH", 2130, Optional.of(500));
    final StorageMedia sd = card("Extreme 128GB", "SD", 2, 128, 200.0, 2.0);
    final UUID pairLensId = UUID.randomUUID();

    /** One lens record standing for {@code quantity} copies of the FE 24-105mm G; same identifier for every quantity. */
    Lens standardCopies(int quantity) {
        return new Lens(pairLensId, "Sony", "FE 24-105mm G", quantity, Optional.of(663.0), Set.of(), Map.of(), Optional.empty(), "Sony E", 24, 105, 4,
            Optional.of(77.0), Optional.of(true), Optional.of(true));
    }
    static Lens copies(String model, String mount, double focal, int quantity) {
        return new Lens(UUID.randomUUID(), "Sony", model, quantity, Optional.of(500.0), Set.of(), Map.of(), Optional.empty(), mount, focal, focal, 1.8,
            Optional.of(67.0), Optional.of(true), Optional.of(true));
    }
    static ShootRequirement requirement(Optional<Integer> bodies, Optional<Boolean> lensChanges) {
        return new ShootRequirement("Wedding with several bodies", "Wedding ceremony", List.of("Every key moment covered"), Optional.empty(),
            Optional.of(LocalDate.of(2026, 11, 21)), "afternoon", Optional.empty(), Optional.of(Duration.ofHours(4)), bodies, lensChanges,
            Optional.empty(), "", List.of(), Map.of("coverage", 1), Map.of(), List.of(), true);
    }
    static ShootRequirement avoid(int bodies) { return requirement(Optional.of(bodies), Optional.of(false)); }
    static ShootRequirement allowed(int bodies) { return requirement(Optional.of(bodies), Optional.of(true)); }
    static ShootRequirement unspecified(int bodies) { return requirement(Optional.of(bodies), Optional.empty()); }
    static EquipmentRequirementProfile bodies(int count) {
        return new EquipmentRequirementProfile(List.of(need("cameraCount", (double) count, (double) count, 1, true)), true);
    }
    /** Whole owned records as F07 builds them, with the given recorded mounts. */
    static Loadout loadout(Map<UUID, UUID> mounts, Equipment... items) {
        var snapshots = Arrays.stream(items).map(Equipment::snapshot).toList();
        return new Loadout(GearMatchingService.candidateId(snapshots), snapshots, mounts, new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of());
    }
    /** A well-formed candidate as a client could submit it, claiming to be compatible. */
    static Loadout submitted(Map<UUID, UUID> mounts, List<GearSnapshot> gear) {
        return new Loadout(GearMatchingService.candidateId(gear), gear, mounts, new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
    }
    static Set<String> keys(List<LocalizedMessage> messages) { var keys = new TreeSet<String>(); messages.forEach(m -> keys.add(m.key())); return keys; }
    static LocalizedMessage short_(long equipped, long bodies) { return LocalizedMessage.of("compat.bodiesShort", equipped, bodies); }
    static long equipped(Loadout loadout) { return CompatibilityValidator.simultaneousMounts(loadout, false); }
    static ShootPlan plan(Loadout loadout, ShootRequirement req) {
        return new ShootPlan(UUID.randomUUID(), 1, false, List.of(), Optional.of(loadout), Map.of(), List.of(), List.of("Spare cards"),
            List.of("Charge all batteries."), List.of("primary: ceremony"), PlanValidator.constraintsOf(req, Optional.of(bodies(2))), List.of(),
            "Summary", Map.of("subject", req.subject()), Map.of("approach", "Cover the ceremony."), List.of(), List.of(), List.of(), "");
    }

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(gemini.environment(root)); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    UUID session(List<? extends Equipment> items, ShootRequirement req, EquipmentRequirementProfile profile) {
        for (var item : items) ok(app.facade().inventory().add(item, true));
        return session(req, profile);
    }
    UUID session(ShootRequirement req, EquipmentRequirementProfile profile) {
        var id = ok(app.facade().sessions().create("Wedding coverage", LocalDate.of(2026, 11, 21))).id();
        ok(app.facade().planning().confirm(id, req.asDraft()));
        ok(app.facade().planning().confirmProfile(id, profile.asDraft()));
        return id;
    }
    /** F07 through the Facade with the deterministic ranking (the loopback model answers 503). */
    GearMatchReport match(UUID id) { gemini.status(503, ""); return ok(app.facade().planning().matchGear(id, "portability")); }
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
    GearMatchingService offline() {
        var agent = agent(root.resolve("offline"), new ArrayList<AgentContext>(), context -> Result.failureKey("ai.error.network"));
        return new GearMatchingService(new CompatibilityValidator(), new PortabilityRanking(agent), List.of(new CoverageRanking(agent)));
    }

    // A
    @Test void twoBodiesOfOneRecordWithOneLensAreNotUsableWhenLensChangesAreAvoided() {
        var bag = loadout(Map.of(pair.id(), standard.id()), pair, standard, fz, sd);
        assertEquals(2, bag.units(CAMERA)); assertEquals(1, bag.cameraLensAssignments().size(), "one record, one recorded mount");
        assertTrue(validator.assess(bag).usable(), "without a mounting rule the recorded mounts look complete");
        var avoided = validator.assess(bag, Mounting.of(avoid(2)));
        assertFalse(avoided.usable());
        assertTrue(avoided.violations().contains(short_(1, 2)), avoided.violations()::toString);
        assertEquals(1, equipped(bag), "one lens unit equips one of the two bodies");
        var report = validator.validate(bag, Mounting.DEDICATED);
        assertFalse(report.isUsable()); assertFalse(report.compatible());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("only 1 of 2 camera bodies")), report.violations()::toString);
    }

    // B
    @Test void twoCopiesUnderOneLensRecordEquipBothBodiesOfOneCameraRecord() {
        var lensPair = standardCopies(2);
        var bag = loadout(Map.of(pair.id(), lensPair.id()), pair, lensPair, fz, sd);
        assertEquals(2, equipped(bag), "two lens units for two body units, though only one mount is recorded");
        for (var mounting : Mounting.values()) assertTrue(validator.assess(bag, mounting).usable(), mounting::name);
        var inventory = new GearInventory(List.of(pair, lensPair, fz, sd));
        var service = offline();
        var report = ok(service.analyze(bodies(2), avoid(2), inventory, "portability", ""));
        var both = full(report, 2, 2);
        assertEquals(1, both.loadout().cameraLensAssignments().size(), "the representation is unchanged: one mount per record");
        assertTrue(both.loadout().compatibility().isUsable());
        assertTrue(report.recommendations().stream().noneMatch(c -> c.loadout().units(CAMERA) == 2 && c.loadout().units(LENS) == 1),
            "taking one of the two copies leaves a body without a lens");
        var scope = GearMatchingService.scope(bodies(2), avoid(2));
        assertEquals(both.loadout().id(), ok(service.reconstruct(both.loadout(), inventory, scope)).id(), "selection rebuilds the same candidate");
    }

    // C
    @Test void oneLensUnitIsNeverAllocatedToTwoBodies() {
        var twice = loadout(Map.of(a1.id(), standard.id(), a9.id(), standard.id()), a1, a9, standard, fz, sd);
        assertTrue(keys(validator.assess(twice).violations()).contains("compat.lensMountedTwice"), "a recorded double mount is a conflict under any rule");
        assertTrue(validator.assess(twice, Mounting.DEDICATED).violations().contains(short_(1, 2)), "the double mount does not count as two lenses");
        assertEquals(1, CompatibilityValidator.simultaneousMounts(twice, true));
        var once = loadout(Map.of(a1.id(), standard.id()), a1, a9, standard, fz, sd);
        assertTrue(validator.assess(once).usable(), "bodies may take turns when no rule needs both equipped");
        assertTrue(validator.assess(once, Mounting.DEDICATED).violations().contains(short_(1, 2)), "an unrecorded second body is not assumed equipped");
    }

    // D
    @Test void equalLensAndBodyCountsDoNotHideABodyThatNoLensFits() {
        var portrait = lens("Sony", "FE 85mm GM", "Sony E", 85, 85, 1.4, 77.0, 820.0, true);
        var mixed = loadout(Map.of(a1.id(), standard.id()), a1, r5, standard, portrait, fz, lp, sd);
        assertEquals(2, mixed.units(CAMERA)); assertEquals(2, mixed.units(LENS));
        var any = validator.assess(mixed);
        assertFalse(any.usable(), "no carried lens fits the R5, whatever the lens-change rule");
        assertTrue(any.violations().stream().anyMatch(m -> m.key().equals("compat.mountMismatch") && m.arguments().contains("Canon EOS R5")), any.violations()::toString);
        assertTrue(validator.assess(mixed, Mounting.DEDICATED).violations().contains(short_(1, 2)));
        assertEquals(1, CompatibilityValidator.simultaneousMounts(mixed, true), "even if every unrecorded mount fitted");
    }

    // E
    @Test void mixedSystemsWithAFittingLensForEachBodyAreCompatible() {
        var mixed = loadout(Map.of(), a1, r5, standard, rf, fz, lp, sd);
        for (var mounting : Mounting.values()) assertTrue(validator.assess(mixed, mounting).usable(), () -> mounting + " " + validator.assess(mixed, mounting).violations());
        assertEquals(2, equipped(mixed), "allocated even without recorded mounts");
        var report = ok(offline().analyze(bodies(2), avoid(2), new GearInventory(List.of(a1, r5, standard, rf, fz, lp, sd)), "portability", ""));
        var both = full(report, 2, 2);
        assertEquals(Map.of(a1.id(), standard.id(), r5.id(), rf.id()), both.loadout().cameraLensAssignments());
        assertEquals(1, report.recommendations().stream().filter(c -> c.loadout().units(CAMERA) == 2).count(), "only the pairing that equips both bodies");
    }

    // F
    @Test void singleCameraLoadoutsKeepTheirResult() {
        var single = loadout(Map.of(a1.id(), zoom.id()), a1, zoom, fz, sd);
        var plain = validator.validate(single);
        assertTrue(plain.isUsable() && plain.unmetNeeds().isEmpty());
        for (var mounting : Mounting.values()) {
            var report = validator.validate(single, mounting);
            assertTrue(report.isUsable(), mounting::name);
            assertEquals(plain.violations(), report.violations()); assertEquals(plain.unverified(), report.unverified()); assertEquals(plain.unmetNeeds(), report.unmetNeeds());
        }
        var trade = offline().tradeOffs(single).stream().map(LocalizedMessage::key).toList();
        assertTrue(trade.contains("trade.noLensChanges") && !trade.contains("trade.bodiesShort"), trade::toString);
        assertTrue(keys(validator.assess(loadout(Map.of(), a1, rf, fz, sd)).violations()).contains("compat.mountMismatch"));
        var unknown = validator.assess(loadout(Map.of(), a1, vintage, fz, sd), Mounting.DEDICATED);
        assertTrue(unknown.violations().isEmpty()); assertEquals(Set.of("compat.mountUnknown"), keys(unknown.unverified()));
    }

    // G
    @Test void allowedLensChangesImposeNoMountingRuleAndAnUnansweredOneIsAskedNotAssumed() {
        assertEquals(Mounting.NONE, Mounting.of(null));
        assertEquals(Mounting.NONE, Mounting.of(allowed(2)));
        assertEquals(Mounting.DEDICATED, Mounting.of(avoid(2)));
        assertEquals(Mounting.DEDICATED, Mounting.of(requirement(Optional.empty(), Optional.of(false))));
        assertEquals(Mounting.UNSPECIFIED, Mounting.of(unspecified(2)));
        assertEquals(Mounting.NONE, Mounting.of(unspecified(1)));
        assertEquals(Mounting.NONE, Mounting.of(requirement(Optional.empty(), Optional.empty())));
        var bag = loadout(Map.of(pair.id(), standard.id()), pair, standard, fz, sd);
        assertTrue(validator.assess(bag, Mounting.of(allowed(2))).usable(), "lens changes allowed: the second body is used after one");
        var trade = offline().tradeOffs(bag).stream().map(LocalizedMessage::key).toList();
        assertTrue(trade.contains("trade.bodiesShort") && !trade.contains("trade.noLensChanges"), "no longer says 'no lens changes needed': " + trade);
        var open = validator.assess(bag, Mounting.of(unspecified(2)));
        assertFalse(open.usable()); assertTrue(open.violations().isEmpty(), "not a confirmed conflict");
        assertTrue(open.unverified().contains(LocalizedMessage.of(CompatibilityValidator.MOUNTING_UNSPECIFIED, 1L, 2L)), open.unverified()::toString);
        var inventory = new GearInventory(List.of(pair, standard, fz, sd));
        var sequential = ok(offline().analyze(bodies(2), allowed(2), inventory, "portability", ""));
        var shared = full(sequential, 2, 1);
        assertTrue(shared.tradeOffs().stream().anyMatch(t -> t.key().equals("trade.bodiesShort")), shared.tradeOffs()::toString);
        var asked = ok(offline().analyze(bodies(2), unspecified(2), inventory, "portability", ""));
        assertTrue(asked.recommendations().stream().noneMatch(c -> c.loadout().units(CAMERA) == 2));
        assertTrue(asked.rejections().stream().anyMatch(m -> m.key().equals(CompatibilityValidator.MOUNTING_UNSPECIFIED)), asked.rejections()::toString);
        assertFalse(asked.missingSpecifications(), "an unanswered lens-change question is not a missing gear specification");
        var lines = LoadoutPresentation.rejections(asked, LocalizationService.english());
        assertTrue(lines.stream().anyMatch(s -> s.contains("Set Lens changes in Requirement Parsing")), lines::toString);
    }

    // H
    @Test void missingMountDataLeavesTheAllocationUnverifiedNeitherCompatibleNorConflicting() {
        var partly = loadout(Map.of(a1.id(), standard.id()), a1, a9, standard, vintage, fz, sd);
        var avoided = validator.assess(partly, Mounting.DEDICATED);
        assertFalse(avoided.usable());
        assertFalse(keys(avoided.violations()).contains("compat.bodiesShort"), "the unrecorded mount might fit: no confirmed shortage");
        assertTrue(avoided.unverified().contains(LocalizedMessage.of("compat.bodiesUnverified", 1L, 2L)), avoided.unverified()::toString);
        assertEquals(1, equipped(partly)); assertEquals(2, CompatibilityValidator.simultaneousMounts(partly, true));
        assertFalse(validator.validate(partly, Mounting.DEDICATED).compatible());
        var three = loadout(Map.of(), a1, pair, standard, vintage, fz, sd);
        assertTrue(validator.assess(three, Mounting.DEDICATED).violations().contains(short_(2, 3)), "short even if the unknown lens fitted");
        assertTrue(validator.assess(three, Mounting.UNSPECIFIED).unverified().contains(LocalizedMessage.of(CompatibilityValidator.MOUNTING_UNSPECIFIED, 2L, 3L)));
        var report = ok(offline().analyze(bodies(2), avoid(2), new GearInventory(List.of(a1, a9, standard, vintage, fz, sd)), "portability", ""));
        assertTrue(report.recommendations().stream().allMatch(c -> equipped(c.loadout()) >= c.loadout().units(CAMERA)));
        assertTrue(report.missingSpecifications(), "UC07 A1: the missing mount is asked for");
    }

    // I
    @Test void reducingTheInventoryAfterMatchingInvalidatesTheAllocationEverywhere() throws Exception {
        var id = session(List.of(pair, standardCopies(2), fz, sd), avoid(2), bodies(2));
        var both = full(match(id), 2, 2);
        assertEquals(2, equipped(both.loadout()));
        ok(app.facade().planning().selectLoadout(id, both.loadout()));
        assertTrue(ok(app.facade().planning().selection(id)).current());
        ok(app.facade().inventory().update(standardCopies(1), true));
        var status = ok(app.facade().planning().selection(id));
        assertFalse(status.current());
        assertTrue(keys(status.availability()).contains("compat.quantity"), status.availability()::toString);
        assertTrue(keys(ok(app.facade().planning().planInputs(id)).notes()).contains("plan.note.selectionStale"));
        byte[] before = Files.readAllBytes(store());
        var again = app.facade().planning().selectLoadout(open(id), both.loadout(), true);
        assertTrue(again.hasError("compat.quantity"), again.errors()::toString);
        assertArrayEquals(before, Files.readAllBytes(store()), "a rejected selection writes nothing");
        var rematched = match(id);
        assertTrue(rematched.recommendations().stream().noneMatch(c -> c.loadout().units(CAMERA) == 2), "one lens left for two bodies");
        assertTrue(rematched.rejections().contains(short_(1, 2)), rematched.rejections()::toString);
    }

    // J
    @Test void f07NeverRecommendsAnUnequippedBodyAndValidCandidatesStaySelectable() throws Exception {
        var id = session(List.of(pair, standard, zoom, fz, sd), avoid(2), bodies(2));
        var report = match(id);
        assertFalse(report.recommendations().isEmpty());
        for (var candidate : report.recommendations()) {
            assertTrue(equipped(candidate.loadout()) >= candidate.loadout().units(CAMERA), candidate.loadout().gear()::toString);
            assertTrue(candidate.loadout().compatibility().isUsable());
        }
        assertTrue(report.recommendations().stream().noneMatch(c -> c.loadout().units(CAMERA) == 2 && c.loadout().units(LENS) == 1),
            "both two-body, one-lens loadouts used to be full matches");
        assertTrue(report.rejections().contains(short_(1, 2)), report.rejections()::toString);
        assertFalse(report.missingSpecifications());
        var lines = LoadoutPresentation.rejections(report, LocalizationService.english());
        assertTrue(lines.stream().anyMatch(s -> s.contains("only 1 of 2 camera bodies can have their own compatible lens")), lines::toString);
        byte[] before = Files.readAllBytes(store());
        var forged = submitted(Map.of(pair.id(), standard.id()), List.of(GearMatchingService.withQuantity(pair.snapshot(), 2), standard.snapshot(), fz.snapshot(), sd.snapshot()));
        var rejected = app.facade().planning().selectLoadout(id, forged);
        assertTrue(rejected.hasError("compat.bodiesShort"), "owned, in range and claiming compatibility, still rejected: " + rejected.errors());
        assertArrayEquals(before, Files.readAllBytes(store()));
        var best = full(report, 2, 2);
        var stored = ok(app.facade().planning().selectLoadout(id, best.loadout()));
        assertEquals(best.loadout().id(), stored.id()); assertTrue(stored.compatibility().fullySuitable());
        assertTrue(ok(app.facade().planning().selection(id)).current());
    }

    // K
    @Test void f10NeitherOffersNorValidatesALoadoutThatCannotEquipEveryBody() {
        var id = session(List.of(pair, standard, zoom, fz, sd), allowed(2), bodies(2));
        var shared = full(match(id), 2, 1);
        ok(app.facade().planning().selectLoadout(id, shared.loadout())); // valid while lens changes are allowed
        ok(app.facade().planning().confirm(id, avoid(2).asDraft()));
        var status = ok(app.facade().planning().selection(id));
        assertFalse(status.current());
        assertTrue(status.compatibility().contains(short_(1, 2)), status.compatibility()::toString);
        var shown = LoadoutPresentation.selection(status, LocalizationService.english());
        assertTrue(shown.stream().anyMatch(s -> s.contains("only 1 of 2 camera bodies")), shown::toString);
        var notes = keys(ok(app.facade().planning().planInputs(id)).notes());
        assertTrue(notes.contains("plan.note.selectionStale"), notes::toString);
        var offers = offered(id);
        assertFalse(offers.isEmpty());
        for (JsonElement element : offers) {
            var offer = element.getAsJsonObject();
            assertNotEquals("F07 selection", offer.get("source").getAsString());
            assertTrue(offer.get("lensUnits").getAsInt() >= offer.get("cameraBodies").getAsInt(), offer::toString);
        }
        // PlanValidator gates generation, revision and approval with the same rule.
        var plans = new PlanValidator(new CompatibilityValidator(), new TechnicalCalculator());
        var context = new PlanValidator.Context(Optional.of(bodies(2)), Optional.of(new GearInventory(List.of(pair, standard, zoom, fz, sd))),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        var blocked = plans.assess(plan(shared.loadout(), avoid(2)), avoid(2), context);
        assertFalse(blocked.valid());
        assertTrue(blocked.violations().contains(short_(1, 2)), blocked.violations()::toString);
        var sequential = plans.assess(plan(shared.loadout(), allowed(2)), allowed(2), context);
        assertTrue(sequential.violations().stream().noneMatch(m -> m.key().startsWith("compat.")), sequential.violations()::toString);
    }

    // L
    @Test void savedLoadoutsKeepTheirRepresentationAndStayValid() throws Exception {
        Path file = root.resolve("legacy/sessions.json"); Files.createDirectories(file.getParent());
        try (var input = getClass().getResourceAsStream("/persistence/legacy-v1-sessions.json")) { Files.copy(Objects.requireNonNull(input), file); }
        byte[] legacyBytes = Files.readAllBytes(file);
        var legacy = ok(new JsonSessionRepository(file.getParent()).search("")).getFirst();
        var historical = legacy.plans().getFirst().loadout().orElseThrow();
        var mounting = Mounting.of(legacy.requirements().orElseThrow());
        assertEquals(Mounting.NONE, mounting, "one body, lens changes allowed");
        assertEquals(validator.assess(historical).violations(), validator.assess(historical, mounting).violations());
        assertArrayEquals(legacyBytes, Files.readAllBytes(file), "reading and checking never rewrites history");
        var single = session(List.of(a1, pair, zoom, standardCopies(2), fz, sd), avoid(1), bodies(1));
        var one = full(match(single), 1, 1);
        ok(app.facade().planning().selectLoadout(single, one.loadout()));
        var twin = session(avoid(2), bodies(2));
        var two = match(twin).recommendations().stream().filter(c -> c.fullySuitable() && c.loadout().ofType(CAMERA).size() == 1
            && c.loadout().units(CAMERA) == 2 && c.loadout().ofType(LENS).size() == 1 && c.loadout().units(LENS) == 2).findFirst().orElseThrow();
        ok(app.facade().planning().selectLoadout(twin, two.loadout()));
        app.close(); app = boot();
        for (var pick : List.of(Map.entry(single, one), Map.entry(twin, two))) {
            var restored = open(pick.getKey()).selectedLoadout().orElseThrow();
            assertEquals(pick.getValue().loadout().id(), restored.id());
            assertEquals(pick.getValue().loadout().cameraLensAssignments(), restored.cameraLensAssignments());
            assertTrue(ok(app.facade().planning().selection(pick.getKey())).current(), "restored selection is still current");
        }
        var stored = JsonParser.parseString(Files.readString(store()));
        var loadouts = new ArrayList<JsonObject>(); collect(stored, loadouts);
        assertEquals(2, loadouts.size());
        for (var saved : loadouts)
            assertEquals(Set.of("id", "gear", "cameraLensAssignments", "compatibility", "tradeOffs", "unmetNeeds"), saved.keySet(), "no new persisted fields");
    }
    static void collect(JsonElement element, List<JsonObject> found) {
        if (element.isJsonArray()) element.getAsJsonArray().forEach(e -> collect(e, found));
        if (!element.isJsonObject()) return;
        var object = element.getAsJsonObject();
        if (object.has("cameraLensAssignments")) { found.add(object); return; }
        object.entrySet().forEach(e -> collect(e.getValue(), found));
    }

    // M
    @Test void largeQuantitiesStayBoundedExactAndFreeOfOverflow() {
        int many = ShootRequirement.MAX_CAMERAS;
        var crew = camera("Sony", "FX3", "Sony E", many, 715.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
        var primes = copies("FE 35mm GM", "Sony E", 35, many);
        var service = offline();
        long started = System.nanoTime();
        var report = ok(service.analyze(bodies(many), avoid(many), new GearInventory(List.of(crew, primes, fz, sd)), "portability", ""));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 30, "bounded work");
        assertFalse(report.recommendations().isEmpty());
        for (var candidate : report.recommendations()) {
            assertTrue(candidate.loadout().units(CAMERA) <= GearMatchingService.SEARCH_MAX_BODIES, "search ceiling respected");
            assertTrue(equipped(candidate.loadout()) >= candidate.loadout().units(CAMERA), "every body has its own lens");
        }
        assertEquals("match.limit.scope", report.scopeLimit().orElseThrow().key(), "ten bodies are beyond the search ceiling and the summary says so");
        // Saved or submitted loadouts are not bounded by the search window: exact unit arithmetic, no overflow, no blow-up.
        int max = Integer.MAX_VALUE;
        var bodiesA = camera("Sony", "Body A", "Sony E", max, 700.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
        var bodiesB = camera("Sony", "Body B", "Sony E", max, 700.0, Set.of("NP-FZ100"), Set.of("SD"), 10.0, true);
        var glass = copies("Glass", "Sony E", 50, max);
        var otherMount = copies("Other system", "Nikon Z", 50, max);
        var lacking = loadout(Map.of(bodiesA.id(), glass.id()), bodiesA, bodiesB, glass, fz, sd);
        assertEquals(Integer.MAX_VALUE, lacking.units(CAMERA), "saturates instead of overflowing");
        assertEquals(2L * max, CompatibilityValidator.units(lacking, CAMERA));
        started = System.nanoTime();
        assertEquals(max, equipped(lacking), "no lens unit counted twice");
        assertTrue(validator.assess(lacking, Mounting.DEDICATED).violations().contains(short_(max, 2L * max)));
        var enough = loadout(Map.of(), bodiesA, glass, fz, sd);
        assertTrue(validator.assess(enough, Mounting.DEDICATED).usable());
        var unrelated = loadout(Map.of(), bodiesA, bodiesB, glass, otherMount, fz, sd);
        assertEquals(max, equipped(unrelated), "lenses of another mount add no capacity");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "work depends on records, not quantities");
        var owned = new GearInventory(List.of(bodiesA, bodiesB, glass, fz, sd));
        var candidates = ok(service.compatibleCandidates(owned, GearMatchingService.scope(bodies(2), avoid(2))));
        assertFalse(candidates.isEmpty(), "owned totals beyond the int range no longer empty the search");
        for (var candidate : candidates) {
            assertTrue(candidate.units(CAMERA) <= GearMatchingService.MAX_BODIES && candidate.units(LENS) <= GearMatchingService.MAX_LENSES);
            assertTrue(equipped(candidate) >= candidate.units(CAMERA));
        }
    }
}
