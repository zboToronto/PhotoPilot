package com.photopilot.matching;

import com.photopilot.agent.AgentContext;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.service.GearMatchingService;
import com.photopilot.strategy.*;
import com.photopilot.validation.CompatibilityValidator;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** F07 generation, UC14 filtering, capability matching and the two ranking Strategies with a mocked model. */
class GearMatchingTest {
    @TempDir Path root;
    final List<AgentContext> seen = new ArrayList<>();
    final Airshow gear = new Airshow();

    GearMatchingService service(java.util.function.Function<AgentContext, Result<com.photopilot.agent.ModelReply>> model) {
        var agent = agent(root, seen, model);
        return new GearMatchingService(new CompatibilityValidator(), new PortabilityRanking(agent), List.of(new CoverageRanking(agent)));
    }
    GearMatchingService offline() { return service(context -> Result.failureKey("ai.error.network")); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static Set<String> names(Candidate candidate) {
        var names = new TreeSet<String>(); candidate.loadout().gear().forEach(g -> names.add(g.model())); return names;
    }
    static EquipmentRequirementProfile optional(String capability, Double min, Double max) {
        return new EquipmentRequirementProfile(List.of(need(capability, min, max, 5, false)), true);
    }

    @Test void missingOrUnconfirmedProfileAndEmptyInventoryAreRejectedBeforeMatching() {
        var service = offline();
        assertTrue(service.analyze(null, gear.inventory(), "portability", "").hasError("needs.error.none"));
        assertTrue(service.analyze(airshowProfile().asDraft(), gear.inventory(), "portability", "").hasError("needs.error.none"), "unreviewed profiles are not used");
        assertTrue(service.analyze(airshowProfile(), new GearInventory(List.of()), "portability", "").hasError("match.error.noGear"));
        assertTrue(service.analyze(airshowProfile(), new GearInventory(List.of(gear.a1, gear.fz)), "portability", "").hasError("match.error.noLens"));
        assertTrue(service.analyze(airshowProfile(), new GearInventory(List.of(gear.tele)), "portability", "").hasError("match.error.noCamera"));
        assertTrue(service.analyze(airshowProfile(), gear.inventory(), "fastest", "").hasError("match.error.strategy"));
        assertTrue(seen.isEmpty(), "no model request without valid inputs");
        var incomplete = new EquipmentRequirementProfile(List.of(need("focalLength", null, null, 1, true)), false);
        assertFalse(incomplete.confirm().isSuccess(), "a focal need without bounds cannot be confirmed");
    }

    @Test void airshowRecommendsOnlyVerifiedOwnedGearAndSeparatesIncompatibleAndUnverifiedItems() {
        var report = ok(offline().analyze(airshowProfile(), gear.inventory(), "portability", ""));
        assertFalse(report.recommendations().isEmpty());
        var owned = new HashSet<UUID>(); gear.items().forEach(e -> owned.add(e.id()));
        for (var candidate : report.recommendations()) {
            assertTrue(candidate.loadout().compatibility().isUsable());
            assertTrue(candidate.loadout().gear().stream().allMatch(g -> owned.contains(g.sourceGearId())), "only owned items");
            assertTrue(Collections.disjoint(names(candidate), Set.of("EF 100-400mm L II", "300mm f/4", "LP-E6NH")), "incompatible/unverified gear never recommended");
        }
        var best = report.recommendations().getFirst();
        assertTrue(best.fullySuitable());
        assertEquals(Set.of("A1", "A7 IV", "FE 200-600mm G", "FE 100-400mm GM", "NP-FZ100", "Extreme 128GB", "77mm CPL"), names(best));
        assertEquals(1, report.recommendations().stream().filter(Candidate::fullySuitable).count(), "only one two-lens set covers 100-600 mm");
        assertTrue(report.rejectedCombinations() > 0);
        var reasons = report.rejections().stream().map(m -> com.photopilot.i18n.EnglishMessages.text(m.key(), m.arguments().toArray())).toList();
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("Excluded (incompatible)") && r.contains("Canon EF")), reasons.toString());
        assertTrue(reasons.stream().anyMatch(r -> r.startsWith("Excluded (unverified)") && r.contains("300mm f/4")), reasons.toString());
        assertTrue(reasons.stream().anyMatch(r -> r.contains("LP-E6NH")), reasons.toString());
        assertFalse(report.aiRanked()); assertTrue(report.rankingNotice().isPresent(), "AI failure is labelled");
        var trade = best.tradeOffs().stream().map(t -> t.key()).toList();
        assertTrue(trade.contains("trade.weight") && trade.contains("trade.noLensChanges"));
    }

    @Test void partialMatchesListUnmetMandatoryNeedsAndNeverExceedOwnedQuantities() {
        var threeBodies = new EquipmentRequirementProfile(List.of(need("cameraCount", 3.0, null, 1, true), need("focalLength", 100.0, 600.0, 2, true)), true);
        var report = ok(offline().analyze(threeBodies, gear.inventory(), "coverage", ""));
        assertFalse(report.recommendations().isEmpty(), "closest verified partial matches are still shown");
        assertTrue(report.recommendations().stream().noneMatch(Candidate::fullySuitable), "only two bodies are owned");
        for (var candidate : report.recommendations()) {
            assertTrue(candidate.loadout().units(EquipmentType.CAMERA) <= 2);
            assertTrue(candidate.mandatoryUnmet() >= 1);
            assertTrue(candidate.loadout().unmetNeeds().stream().anyMatch(u -> u.contains("bodies")), candidate.loadout().unmetNeeds().toString());
            assertFalse(candidate.loadout().compatibility().fullySuitable());
            for (var item : candidate.loadout().gear())
                assertTrue(item.selectedQuantity() <= gear.inventory().find(item.sourceGearId()).orElseThrow().quantity());
        }
    }

    @Test void noVerifiedLoadoutExplainsWhy() {
        var report = ok(offline().analyze(airshowProfile(), new GearInventory(List.of(gear.a1, gear.canon, gear.vintage, gear.fz)), "portability", ""));
        assertTrue(report.recommendations().isEmpty());
        assertEquals(report.combinationsChecked(), report.rejectedCombinations());
        assertEquals(2, report.rejections().stream().filter(m -> m.key().startsWith("match.excluded")).count());
        assertTrue(seen.isEmpty(), "nothing verified, so nothing is sent to the model");
    }

    @Test void portabilityAndCoverageStrategiesOrderTheSameVerifiedCandidatesDifferently() {
        var inventory = new GearInventory(List.of(gear.a1, gear.tele, gear.zoom, gear.standard, gear.fz, gear.sd));
        var profile = optional("cameraCount", 1.0, null);
        var light = ok(offline().analyze(profile, inventory, "portability", ""));
        var wide = ok(offline().analyze(profile, inventory, "coverage", ""));
        assertEquals(light.recommendations().size(), wide.recommendations().size());
        assertEquals(Set.of("A1", "FE 24-105mm G", "NP-FZ100", "Extreme 128GB"), names(light.recommendations().getFirst()), "lightest verified loadout first");
        var first = light.recommendations().getFirst().loadout().totalWeightGrams().orElseThrow();
        assertTrue(light.recommendations().stream().allMatch(c -> c.loadout().totalWeightGrams().orElseThrow() >= first));
        assertEquals(Set.of("A1", "FE 24-105mm G", "FE 100-400mm GM", "FE 200-600mm G", "NP-FZ100", "Extreme 128GB"), names(wide.recommendations().getFirst()),
            "widest verified focal coverage first");
        assertEquals("portability", light.strategy()); assertEquals("coverage", wide.strategy());
    }

    @Test void validGeminiRankingIsAcceptedButCannotLiftPartialAboveFullOrSeeIdentifiers() {
        var inventory = new GearInventory(List.of(gear.a1, gear.a7, gear.tele, gear.zoom, gear.fz, gear.sd));
        var deterministic = ok(offline().analyze(airshowProfile(), inventory, "portability", "subject: airshow"));
        int sent = Math.min(5, deterministic.recommendations().size());
        int[] reversed = new int[sent]; for (int i = 0; i < sent; i++) reversed[i] = sent - i;
        seen.clear();
        var ai = ok(service(context -> json(ranking(reversed))).analyze(airshowProfile(), inventory, "portability", "subject: airshow"));
        assertTrue(ai.aiRanked()); assertTrue(ai.rankingNotice().isEmpty());
        assertEquals("Compared verified candidates.", ai.aiSummary());
        assertTrue(ai.recommendations().getFirst().fullySuitable(), "the full match stays first although Gemini ranked it last");
        assertTrue(ai.recommendations().getFirst().aiExplanation().orElseThrow().startsWith("Trade-off note"));
        assertEquals(deterministic.recommendations().size(), ai.recommendations().size());
        String evidence = seen.getFirst().evidenceJson();
        for (var item : inventory.items()) assertFalse(evidence.contains(item.id().toString()), "no inventory IDs leave the application");
        assertTrue(evidence.contains("\"label\":\"C1\"") && evidence.contains("airshow"));
        assertFalse(seen.getFirst().responseSchema().isBlank());
    }

    @Test void unknownDuplicateOrMissingCandidateIdsAndProviderFailuresFallBackDeterministically() {
        var inventory = new GearInventory(List.of(gear.a1, gear.tele, gear.zoom, gear.fz, gear.sd));
        var profile = optional("cameraCount", 1.0, null);
        var baseline = ok(offline().analyze(profile, inventory, "portability", ""));
        var order = baseline.recommendations().stream().map(c -> c.loadout().id()).toList();
        for (var reply : List.of("{\"ranking\":[{\"candidate\":\"C99\",\"explanation\":\"x\"}],\"summary\":\"\"}",
                "{\"ranking\":[{\"candidate\":\"C1\",\"explanation\":\"x\"},{\"candidate\":\"C1\",\"explanation\":\"y\"},{\"candidate\":\"C2\",\"explanation\":\"z\"}],\"summary\":\"\"}",
                "{\"ranking\":[{\"candidate\":\"C1\",\"explanation\":\"x\"}],\"summary\":\"\"}", "not json")) {
            var report = ok(service(context -> json(reply)).analyze(profile, inventory, "portability", ""));
            assertFalse(report.aiRanked(), reply);
            assertEquals("match.ranking.fallback", report.rankingNotice().orElseThrow().key());
            assertEquals(order, report.recommendations().stream().map(c -> c.loadout().id()).toList(), "deterministic order preserved");
            assertTrue(report.recommendations().stream().allMatch(c -> c.aiExplanation().isEmpty()), "no fabricated AI explanation");
        }
        var unknown = ok(service(context -> json("{\"ranking\":[{\"candidate\":\"C99\",\"explanation\":\"x\"}],\"summary\":\"\"}")).analyze(profile, inventory, "portability", ""));
        assertTrue(unknown.rankingNotice().orElseThrow().toString().contains("match.ai.unknownCandidate"));
        var failed = ok(service(context -> Result.failureKey("ai.error.rateLimited")).analyze(profile, inventory, "coverage", ""));
        assertFalse(failed.aiRanked()); assertFalse(failed.recommendations().isEmpty(), "deterministic findings are preserved on API failure");
    }

    @Test void strategiesRefuseUnverifiedCandidatesAndTheServicePostValidatesRankings() {
        var agent = agent(root, seen, context -> json("{}"));
        var unverified = CompatibilityValidatorTest.loadout(Map.of(), gear.a1, gear.vintage);
        unverified = unverified.withEvaluation(new CompatibilityValidator().validate(unverified), List.of());
        assertTrue(new PortabilityRanking(agent).rank(List.of(unverified), airshowProfile()).hasError("match.error.notUsable"));
        LoadoutRankingStrategy rogue = new LoadoutRankingStrategy() {
            public Result<List<Loadout>> rank(List<Loadout> c, EquipmentRequirementProfile p) { return Result.success(List.of()); }
            public String name() { return "rogue"; }
            public Result<RankingOutcome> rankCandidates(List<Candidate> c, EquipmentRequirementProfile p, String s) {
                return Result.success(new RankingOutcome(c.subList(0, 1), true, "", Optional.empty()));
            }
        };
        var service = new GearMatchingService(new CompatibilityValidator(), rogue);
        assertTrue(service.analyze(optional("cameraCount", 1.0, null), gear.inventory(), "rogue", "").hasError("match.error.rankingIntegrity"));
    }
}
