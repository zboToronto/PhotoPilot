package com.photopilot.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.strategy.LoadoutRankingStrategy;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.CompatibilityValidator.Status;

/**
 * Stage 1 UML element 57 (zero-based): {F07; Strategy context; postvalidates ranked IDs}
 * Generates owned-gear combinations within recorded quantities, lets UC14 reject every incompatible or unverified
 * one, compares the rest with the confirmed capability profile and delegates ordering to a ranking Strategy.
 * Ranked results are post-validated: only candidates this service produced and verified are returned.
 */
public class GearMatchingService {
    public static final int MAX_BODIES = 3, MAX_LENSES = 4, MAX_COMBINATIONS = 5000, MAX_REJECTIONS = 12;
    private CompatibilityValidator validator;
    private LoadoutRankingStrategy ranking;
    private final Map<String, LoadoutRankingStrategy> strategies = new LinkedHashMap<>();

    public GearMatchingService(CompatibilityValidator validator, LoadoutRankingStrategy ranking) {
        this(validator, ranking, List.of());
    }

    /** Default strategy plus alternatives selectable per request without changing the shared default. */
    public GearMatchingService(CompatibilityValidator validator, LoadoutRankingStrategy ranking, List<LoadoutRankingStrategy> alternatives) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.ranking = Objects.requireNonNull(ranking, "ranking");
        strategies.put(ranking.name(), ranking);
        for (var strategy : alternatives) strategies.putIfAbsent(strategy.name(), strategy);
    }

    public Result<List<Loadout>> match(EquipmentRequirementProfile profile, GearInventory gear) {
        var report = analyze(profile, gear, ranking.name(), "");
        return report.isSuccess() ? Result.success(report.value().orElseThrow().recommendations().stream().map(Candidate::loadout).toList()) : Result.failure(report);
    }

    public void setRanking(LoadoutRankingStrategy strategy) {
        this.ranking = Objects.requireNonNull(strategy, "strategy");
        strategies.put(strategy.name(), strategy);
    }
    public String defaultStrategy() { return ranking.name(); }
    public List<String> strategies() { return List.copyOf(strategies.keySet()); }
    public CompatibilityValidator validator() { return validator; }

    /** Every combination UC14 verifies as physically usable, before any profile comparison or ranking. */
    public Result<List<Loadout>> compatibleCandidates(GearInventory gear) {
        var generated = generate(gear, null);
        if (!generated.isSuccess()) return Result.failure(generated);
        return Result.success(generated.value().orElseThrow().raw().stream().filter(l -> l.compatibility().isUsable()).toList());
    }

    /** Complete F07 match for one request: generate, validate (UC14), compare needs, rank with the named strategy. */
    public Result<GearMatchReport> analyze(EquipmentRequirementProfile profile, GearInventory gear, String strategyName, String shootContext) {
        if (profile == null || !profile.reviewed()) return Result.failureKey("needs.error.none");
        var strategy = strategies.get(Objects.toString(strategyName, ranking.name()));
        if (strategy == null) return Result.failureKey("match.error.strategy", strategyName);
        var generated = generate(gear, profile);
        if (!generated.isSuccess()) return Result.failure(generated);
        var found = generated.value().orElseThrow();
        var usable = new ArrayList<Candidate>(); var rejected = new LinkedHashMap<LocalizedMessage, Integer>();
        found.inventoryFindings().forEach(message -> rejected.putIfAbsent(message, 0));
        int rejectedCount = 0;
        for (Loadout raw : found.raw()) {
            if (raw.compatibility().isUsable()) { usable.add(evaluate(raw, profile)); continue; }
            rejectedCount++;
            var assessment = validator.assess(raw);
            for (var reason : assessment.violations()) rejected.merge(reason, 1, Integer::sum);
            for (var reason : assessment.unverified()) rejected.merge(reason, 1, Integer::sum);
        }
        var ranked = strategy.rankCandidates(usable, profile, Objects.toString(shootContext, ""));
        if (!ranked.isSuccess()) return Result.failure(ranked);
        var outcome = ranked.value().orElseThrow();
        // Post-validation: a strategy can only return candidates generated and verified here, each exactly once.
        var expected = new HashSet<UUID>(); usable.forEach(c -> expected.add(c.loadout().id()));
        var returned = new HashSet<UUID>();
        for (var candidate : outcome.ranked())
            if (!expected.contains(candidate.loadout().id()) || !returned.add(candidate.loadout().id()) || !candidate.loadout().compatibility().isUsable())
                return Result.failureKey("match.error.rankingIntegrity");
        if (!returned.equals(expected)) return Result.failureKey("match.error.rankingIntegrity");
        var reasons = rejected.keySet().stream().limit(MAX_REJECTIONS).toList();
        return Result.success(new GearMatchReport(strategy.name(), outcome.ranked(), found.raw().size(), rejectedCount, reasons,
            outcome.aiRanked(), outcome.aiSummary(), outcome.notice(), found.limited()));
    }

    /** Capability comparison, essentials and deterministic trade-offs of one already validated loadout. */
    public Candidate evaluate(Loadout loadout, EquipmentRequirementProfile profile) {
        var needs = CompatibilityValidator.evaluateNeeds(loadout, profile);
        var essentials = validator.assess(loadout).essentials();
        var trade = tradeOffs(loadout);
        var unmet = new ArrayList<String>(CompatibilityValidator.english(essentials));
        unmet.addAll(validator.matchNeeds(loadout, profile));
        var evaluated = loadout.withEvaluation(validator.validate(loadout).withUnmetNeeds(unmet), CompatibilityValidator.english(trade));
        return new Candidate(evaluated, needs, essentials, trade, Optional.empty());
    }

    /** Practical consequences stated from recorded values only. */
    public List<LocalizedMessage> tradeOffs(Loadout loadout) {
        var notes = new ArrayList<LocalizedMessage>();
        var weight = loadout.totalWeightGrams();
        if (weight.isPresent()) notes.add(LocalizedMessage.of("trade.weight", CompatibilityValidator.num(weight.get())));
        else notes.add(LocalizedMessage.of("trade.weightUnknown", String.join(", ", loadout.gear().stream()
            .filter(g -> !g.specs().containsKey("weightGrams")).map(CompatibilityValidator::name).toList())));
        var coverage = CompatibilityValidator.focalCoverage(loadout);
        if (!coverage.isEmpty()) notes.add(LocalizedMessage.of("trade.coverage", String.join("; ", coverage.stream().map(CompatibilityValidator::range).toList())));
        for (int i = 1; i < coverage.size(); i++)
            notes.add(LocalizedMessage.of("trade.gap", CompatibilityValidator.num(coverage.get(i - 1)[1]), CompatibilityValidator.num(coverage.get(i)[0])));
        int bodies = loadout.units(EquipmentType.CAMERA), lenses = loadout.units(EquipmentType.LENS);
        notes.add(lenses > bodies ? LocalizedMessage.of("trade.lensChanges", lenses, bodies) : LocalizedMessage.of("trade.noLensChanges", bodies));
        var byId = new HashMap<UUID, GearSnapshot>(); loadout.gear().forEach(g -> byId.put(g.sourceGearId(), g));
        new TreeMap<>(loadout.cameraLensAssignments()).forEach((camera, lens) ->
            notes.add(LocalizedMessage.of("trade.mounted", CompatibilityValidator.name(byId.get(lens)), CompatibilityValidator.name(byId.get(camera)))));
        return notes;
    }

    private record Generated(List<Loadout> raw, List<LocalizedMessage> inventoryFindings, boolean limited) { }

    /** Combinations of owned bodies and lenses within recorded quantities; verified-compatible accessories are added. */
    private Result<Generated> generate(GearInventory gear, EquipmentRequirementProfile profile) {
        if (gear == null || gear.items().isEmpty()) return Result.failureKey("match.error.noGear");
        var order = Comparator.comparing((Equipment e) -> e.brand().toLowerCase(Locale.ROOT)).thenComparing(e -> e.model().toLowerCase(Locale.ROOT)).thenComparing(e -> e.id().toString());
        var items = gear.items().stream().sorted(order).toList();
        var cameras = of(items, EquipmentType.CAMERA); var lenses = of(items, EquipmentType.LENS);
        if (cameras.isEmpty()) return Result.failureKey("match.error.noCamera");
        if (lenses.isEmpty()) return Result.failureKey("match.error.noLens");
        var accessories = items.stream().filter(e -> e.type() != EquipmentType.CAMERA && e.type() != EquipmentType.LENS).map(Equipment::snapshot).toList();
        var findings = inventoryFindings(cameras, lenses, accessories);
        int bodies = (int) Math.min(cameras.stream().mapToInt(GearSnapshot::selectedQuantity).sum(),
            Math.min(6, Math.max(MAX_BODIES, need(profile, "cameraCount"))));
        int maxLenses = Math.min(6, Math.max(MAX_LENSES, need(profile, "lensCount")));
        var cameraChoices = choices(cameras, bodies); var lensChoices = choices(lenses, maxLenses);
        var raw = new ArrayList<Loadout>(); boolean limited = false;
        outer:
        for (int[] cameraCounts : cameraChoices) {
            for (int[] lensCounts : lensChoices) {
                if (raw.size() >= MAX_COMBINATIONS) { limited = true; break outer; }
                raw.add(build(cameras, cameraCounts, lenses, lensCounts, accessories));
            }
        }
        return Result.success(new Generated(raw, findings, limited || cameraChoices.size() * (long) lensChoices.size() > MAX_COMBINATIONS));
    }

    private Loadout build(List<GearSnapshot> cameras, int[] cameraCounts, List<GearSnapshot> lenses, int[] lensCounts, List<GearSnapshot> accessories) {
        var chosenCameras = chosen(cameras, cameraCounts); var chosenLenses = chosen(lenses, lensCounts);
        var assignments = new LinkedHashMap<UUID, UUID>(); var mounted = new HashMap<UUID, Integer>();
        for (GearSnapshot camera : chosenCameras) {
            chosenLenses.stream().filter(lens -> status(camera, lens) == Status.COMPATIBLE && mounted.getOrDefault(lens.sourceGearId(), 0) < lens.selectedQuantity())
                .max(Comparator.comparingDouble((GearSnapshot lens) -> focal(lens)).thenComparing(lens -> lens.sourceGearId().toString(), Comparator.reverseOrder()))
                .ifPresent(lens -> { assignments.put(camera.sourceGearId(), lens.sourceGearId()); mounted.merge(lens.sourceGearId(), 1, Integer::sum); });
        }
        var gear = new ArrayList<GearSnapshot>(chosenCameras); gear.addAll(chosenLenses);
        for (GearSnapshot item : accessories) {
            var partners = item.type().equals(EquipmentType.FILTER.name()) ? chosenLenses : chosenCameras;
            if (partners.stream().anyMatch(partner -> status(partner, item) == Status.COMPATIBLE)) gear.add(item);
        }
        var draft = new Loadout(candidateId(gear), gear, assignments,
            new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of());
        return draft.withEvaluation(validator.validate(draft), List.of());
    }

    /** Items that cannot work with any owned partner, reported separately so incompatible gear stays visible. */
    private List<LocalizedMessage> inventoryFindings(List<GearSnapshot> cameras, List<GearSnapshot> lenses, List<GearSnapshot> accessories) {
        var findings = new ArrayList<LocalizedMessage>();
        for (GearSnapshot lens : lenses) unmatched(lens, cameras).ifPresent(findings::add);
        for (GearSnapshot item : accessories) unmatched(item, item.type().equals(EquipmentType.FILTER.name()) ? lenses : cameras).ifPresent(findings::add);
        return findings;
    }
    private Optional<LocalizedMessage> unmatched(GearSnapshot item, List<GearSnapshot> partners) {
        CompatibilityValidator.Check problem = null;
        for (GearSnapshot partner : partners) {
            var check = validator.check(item, partner);
            if (check.isEmpty()) continue;
            if (check.get().status() == Status.COMPATIBLE) return Optional.empty();
            if (problem == null || check.get().status() == Status.UNVERIFIED) problem = check.get();
        }
        return problem == null ? Optional.empty() : Optional.of(LocalizedMessage.of(problem.status() == Status.UNVERIFIED
            ? "match.excluded.unverified" : "match.excluded.incompatible", problem.reason()));
    }

    private Status status(GearSnapshot first, GearSnapshot second) { return validator.check(first, second).map(CompatibilityValidator.Check::status).orElse(Status.UNVERIFIED); }
    private static double focal(GearSnapshot lens) {
        try { return Double.parseDouble(lens.specs().getOrDefault("maxFocalMm", "0")); } catch (NumberFormatException unreadable) { return 0; }
    }
    private static int need(EquipmentRequirementProfile profile, String capability) {
        if (profile == null) return 0;
        return profile.need(capability).flatMap(n -> n.minimum().isPresent() ? n.minimum() : n.maximum()).map(Double::intValue).orElse(0);
    }
    private static List<GearSnapshot> of(List<Equipment> items, EquipmentType type) {
        return items.stream().filter(e -> e.type() == type).map(Equipment::snapshot).toList();
    }
    /** Per-record unit counts within owned quantities, total between 1 and {@code limit}. */
    private static List<int[]> choices(List<GearSnapshot> records, int limit) {
        var result = new ArrayList<int[]>();
        choose(records, 0, new int[records.size()], 0, limit, result);
        result.sort(Comparator.comparingInt((int[] counts) -> Arrays.stream(counts).sum()));
        return result;
    }
    private static void choose(List<GearSnapshot> records, int index, int[] counts, int total, int limit, List<int[]> result) {
        if (result.size() > MAX_COMBINATIONS) return;
        if (index == records.size()) { if (total > 0) result.add(counts.clone()); return; }
        for (int n = 0; n <= records.get(index).selectedQuantity() && total + n <= limit; n++) {
            counts[index] = n; choose(records, index + 1, counts, total + n, limit, result);
        }
        counts[index] = 0;
    }
    private static List<GearSnapshot> chosen(List<GearSnapshot> records, int[] counts) {
        var result = new ArrayList<GearSnapshot>();
        for (int i = 0; i < records.size(); i++) if (counts[i] > 0) result.add(withQuantity(records.get(i), counts[i]));
        return result;
    }
    /** Stable identifier of a combination (owned item IDs and quantities), so re-matching yields the same ID. */
    public static UUID candidateId(List<GearSnapshot> gear) {
        String key = String.join(",", gear.stream().map(g -> g.sourceGearId() + ":" + g.selectedQuantity()).sorted().toList());
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
    /** Snapshot of an owned item with the quantity carried in this loadout. */
    public static GearSnapshot withQuantity(GearSnapshot item, int quantity) {
        return new GearSnapshot(item.sourceGearId(), item.type(), item.brand(), item.model(), quantity, item.capturedAt(), item.specs(), item.verifiedFields(), item.sources());
    }
}
