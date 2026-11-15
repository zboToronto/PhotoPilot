package com.photopilot.service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.strategy.LoadoutRankingStrategy;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.CompatibilityValidator.Mounting;
import com.photopilot.validation.CompatibilityValidator.Status;

/**
 * Stage 1 UML element 57 (zero-based): {F07; Strategy context; postvalidates ranked IDs}
 * Generates owned-gear combinations within recorded quantities, lets UC14 reject every incompatible or unverified
 * one, compares the rest with the confirmed capability profile and delegates ordering to a ranking Strategy.
 * Ranked results are post-validated: only candidates this service produced and verified are returned.
 * One candidate space ({@link #scope}) serves F07 recommendation, F07 selection and F10 offers.
 */
public class GearMatchingService {
    /** Default search window: camera and lens units per loadout when no confirmed count asks for more. */
    public static final int MAX_BODIES = 3, MAX_LENSES = 4, MAX_COMBINATIONS = 5000, MAX_REJECTIONS = 12;
    /**
     * Search ceiling, a bound on enumeration rather than a domain rule: confirmed counts widen the default window up to
     * here. Requirements and profiles may ask for more (up to {@link ShootRequirement#MAX_CAMERAS} bodies and the
     * lensCount limit); such combinations are reported as outside the search range, never as stale or incompatible.
     */
    public static final int SEARCH_MAX_BODIES = 6, SEARCH_MAX_LENSES = 6;
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

    /** Stage 1 Strategy setter: replaces the default strategy. GUI and CLI choose a strategy per request instead. */
    public void setRanking(LoadoutRankingStrategy strategy) {
        this.ranking = Objects.requireNonNull(strategy, "strategy");
        strategies.put(strategy.name(), strategy);
    }
    public String defaultStrategy() { return ranking.name(); }
    public List<String> strategies() { return List.copyOf(strategies.keySet()); }
    public CompatibilityValidator validator() { return validator; }

    /**
     * Candidate space of one request: at most {@code bodies} camera units and {@code lenses} lens units per loadout, each
     * record within its owned quantity. {@code requestedBodies}/{@code requestedLenses} are the largest counts the
     * confirmed requirement or profile allows (0 = none stated); above the ceiling they are not searched. {@code mounting}
     * is the UC14 rule the confirmed requirement sets for equipping every body at once, applied to every candidate.
     */
    public record Scope(int bodies, int lenses, int requestedBodies, int requestedLenses, Mounting mounting) {
        /** Default window, for callers without a requirement or profile (F02 discovery). */
        public static final Scope DEFAULT = new Scope(MAX_BODIES, MAX_LENSES, 0, 0);

        public Scope { Objects.requireNonNull(mounting, "mounting"); }
        /** Window without a requirement-specific mounting rule. */
        public Scope(int bodies, int lenses, int requestedBodies, int requestedLenses) { this(bodies, lenses, requestedBodies, requestedLenses, Mounting.NONE); }
    }

    /**
     * The single candidate-space rule for F07 recommendation, F07 selection and F10 offers: the default window, widened to
     * the largest body/lens count the confirmed requirement or profile allows (every body needs its own lens), capped at
     * the search ceiling, with the requirement's UC14 mounting rule. Either argument may be null.
     */
    public static Scope scope(EquipmentRequirementProfile profile, ShootRequirement requirement) {
        int bodies = Math.max(upper(profile, "cameraCount"), requirement == null ? 0 : requirement.cameraCount().orElse(0));
        int lenses = Math.max(upper(profile, "lensCount"), bodies);
        return new Scope(Math.min(SEARCH_MAX_BODIES, Math.max(MAX_BODIES, bodies)), Math.min(SEARCH_MAX_LENSES, Math.max(MAX_LENSES, lenses)), bodies, lenses,
            Mounting.of(requirement));
    }

    /** Notice when owned gear and the confirmed counts both exceed the search ceiling, so larger loadouts are not generated. */
    public static Optional<LocalizedMessage> scopeLimit(GearInventory gear, Scope scope) {
        if (gear == null) return Optional.empty();
        long cameras = owned(gear, EquipmentType.CAMERA), lenses = owned(gear, EquipmentType.LENS);
        boolean beyond = scope.requestedBodies() > scope.bodies() && cameras > scope.bodies() || scope.requestedLenses() > scope.lenses() && lenses > scope.lenses();
        return beyond ? Optional.of(LocalizedMessage.of("match.limit.scope", scope.bodies(), scope.lenses())) : Optional.empty();
    }

    /** Every combination UC14 verifies as physically usable within the default window, before any comparison or ranking. */
    public Result<List<Loadout>> compatibleCandidates(GearInventory gear) {
        return compatibleCandidates(gear, Scope.DEFAULT);
    }

    /** Every combination inside {@code scope} that UC14 verifies as physically usable (F10 offers use the F07 scope). */
    public Result<List<Loadout>> compatibleCandidates(GearInventory gear, Scope scope) {
        var generated = generate(gear, Objects.requireNonNull(scope, "scope"));
        if (!generated.isSuccess()) return Result.failure(generated);
        return Result.success(generated.value().orElseThrow().raw().stream().filter(l -> l.compatibility().isUsable()).toList());
    }

    /**
     * Targeted F07 selection check, independent of enumeration order and truncation: the identifier must name exactly the
     * gear it carries, the combination must lie inside {@code scope}, and rebuilding it from the current inventory with
     * the generation rules (quantities, accessories, mounts) must yield the same candidate. Ownership/changes
     * ({@link CompatibilityValidator#availability}) and UC14 usability remain the caller's checks.
     */
    public Result<Loadout> reconstruct(Loadout selected, GearInventory gear, Scope scope) {
        if (selected == null) return Result.failureKey("match.error.noSelection");
        if (!candidateId(selected.gear()).equals(selected.id())) return Result.failureKey("match.error.staleCandidate");
        if (gear == null || gear.items().isEmpty()) return Result.failureKey("match.error.noGear");
        var items = sorted(gear);
        var cameras = of(items, EquipmentType.CAMERA); var lenses = of(items, EquipmentType.LENS);
        var wanted = new HashMap<UUID, Integer>(); selected.gear().forEach(g -> wanted.put(g.sourceGearId(), g.selectedQuantity()));
        int[] cameraCounts = counts(cameras, wanted), lensCounts = counts(lenses, wanted);
        if (cameraCounts == null || lensCounts == null) return Result.failureKey("match.error.staleCandidate");
        int cameraUnits = Arrays.stream(cameraCounts).sum(), lensUnits = Arrays.stream(lensCounts).sum();
        if (cameraUnits < 1 || lensUnits < 1) return Result.failureKey("match.error.staleCandidate");
        if (cameraUnits > Objects.requireNonNull(scope, "scope").bodies() || lensUnits > scope.lenses())
            return Result.failureKey("match.error.outsideScope", cameraUnits, lensUnits, scope.bodies(), scope.lenses());
        var rebuilt = build(cameras, cameraCounts, lenses, lensCounts, accessories(items), scope.mounting());
        return rebuilt.id().equals(selected.id()) ? Result.success(rebuilt) : Result.failureKey("match.error.staleCandidate");
    }

    /** Complete F07 match for one request: generate, validate (UC14), compare needs, rank with the named strategy. */
    public Result<GearMatchReport> analyze(EquipmentRequirementProfile profile, GearInventory gear, String strategyName, String shootContext) {
        return analyze(profile, null, gear, strategyName, shootContext);
    }

    /** As {@link #analyze(EquipmentRequirementProfile, GearInventory, String, String)}, in the scope of the confirmed requirement too. */
    public Result<GearMatchReport> analyze(EquipmentRequirementProfile profile, ShootRequirement requirement, GearInventory gear, String strategyName, String shootContext) {
        if (profile == null || !profile.reviewed()) return Result.failureKey("needs.error.none");
        var strategy = strategies.get(Objects.toString(strategyName, ranking.name()));
        if (strategy == null) return Result.failureKey("match.error.strategy", strategyName);
        var scope = scope(profile, requirement);
        var generated = generate(gear, scope);
        if (!generated.isSuccess()) return Result.failure(generated);
        var found = generated.value().orElseThrow();
        var usable = new ArrayList<Candidate>(); var rejected = new LinkedHashMap<LocalizedMessage, Integer>();
        found.inventoryFindings().forEach(message -> rejected.putIfAbsent(message, 0));
        boolean unverified = found.inventoryFindings().stream().anyMatch(m -> m.key().equals("match.excluded.unverified"));
        int rejectedCount = 0;
        for (Loadout raw : found.raw()) {
            if (raw.compatibility().isUsable()) { usable.add(evaluate(raw, profile, scope.mounting())); continue; }
            rejectedCount++;
            var assessment = validator.assess(raw, scope.mounting());
            for (var reason : assessment.violations()) rejected.merge(reason, 1, Integer::sum);
            for (var reason : assessment.unverified()) rejected.merge(reason, 1, Integer::sum);
            // UC07 A1 asks for missing gear specifications; an unanswered lens-change question is named by its own message.
            unverified |= assessment.unverified().stream().anyMatch(m -> !m.key().equals(CompatibilityValidator.MOUNTING_UNSPECIFIED));
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
            outcome.aiRanked(), outcome.aiSummary(), outcome.notice(), found.limited(), unverified, scopeLimit(gear, scope)));
    }

    /** Capability comparison, essentials and deterministic trade-offs of one already validated loadout. */
    public Candidate evaluate(Loadout loadout, EquipmentRequirementProfile profile) {
        return evaluate(loadout, profile, Mounting.NONE);
    }

    /** As {@link #evaluate(Loadout, EquipmentRequirementProfile)}, with the UC14 report under the requirement's mounting rule. */
    public Candidate evaluate(Loadout loadout, EquipmentRequirementProfile profile, Mounting mounting) {
        var needs = CompatibilityValidator.evaluateNeeds(loadout, profile);
        var essentials = validator.assess(loadout, mounting).essentials();
        var trade = tradeOffs(loadout);
        var unmet = new ArrayList<String>(CompatibilityValidator.english(essentials));
        unmet.addAll(validator.matchNeeds(loadout, profile));
        var evaluated = loadout.withEvaluation(validator.validate(loadout, mounting).withUnmetNeeds(unmet), CompatibilityValidator.english(trade));
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
        long bodies = CompatibilityValidator.units(loadout, EquipmentType.CAMERA), lenses = CompatibilityValidator.units(loadout, EquipmentType.LENS);
        long equipped = CompatibilityValidator.simultaneousMounts(loadout, false);
        if (equipped < bodies) notes.add(LocalizedMessage.of("trade.bodiesShort", equipped, bodies));
        if (lenses > bodies) notes.add(LocalizedMessage.of("trade.lensChanges", lenses, bodies));
        else if (equipped >= bodies) notes.add(LocalizedMessage.of("trade.noLensChanges", bodies));
        var byId = new HashMap<UUID, GearSnapshot>(); loadout.gear().forEach(g -> byId.put(g.sourceGearId(), g));
        new TreeMap<>(loadout.cameraLensAssignments()).forEach((camera, lens) ->
            notes.add(LocalizedMessage.of("trade.mounted", CompatibilityValidator.name(byId.get(lens)), CompatibilityValidator.name(byId.get(camera)))));
        return notes;
    }

    private record Generated(List<Loadout> raw, List<LocalizedMessage> inventoryFindings, boolean limited) { }

    /** Combinations of owned bodies and lenses within recorded quantities and {@code scope}; verified-compatible accessories are added. */
    private Result<Generated> generate(GearInventory gear, Scope scope) {
        if (gear == null || gear.items().isEmpty()) return Result.failureKey("match.error.noGear");
        var items = sorted(gear);
        var cameras = of(items, EquipmentType.CAMERA); var lenses = of(items, EquipmentType.LENS);
        if (cameras.isEmpty()) return Result.failureKey("match.error.noCamera");
        if (lenses.isEmpty()) return Result.failureKey("match.error.noLens");
        var accessories = accessories(items);
        var findings = inventoryFindings(cameras, lenses, accessories);
        int bodies = (int) Math.min(cameras.stream().mapToLong(GearSnapshot::selectedQuantity).sum(), scope.bodies());
        int maxLenses = scope.lenses();
        var cameraChoices = choices(cameras, bodies); var lensChoices = choices(lenses, maxLenses);
        var raw = new ArrayList<Loadout>(); boolean limited = false;
        outer:
        for (int[] cameraCounts : cameraChoices) {
            for (int[] lensCounts : lensChoices) {
                if (raw.size() >= MAX_COMBINATIONS) { limited = true; break outer; }
                raw.add(build(cameras, cameraCounts, lenses, lensCounts, accessories, scope.mounting()));
            }
        }
        return Result.success(new Generated(raw, findings, limited || cameraChoices.size() * (long) lensChoices.size() > MAX_COMBINATIONS));
    }

    private Loadout build(List<GearSnapshot> cameras, int[] cameraCounts, List<GearSnapshot> lenses, int[] lensCounts, List<GearSnapshot> accessories, Mounting mounting) {
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
        return draft.withEvaluation(validator.validate(draft, mounting), List.of());
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
    /** Largest unit count a count need accepts (its maximum, else its minimum); 0 without a profile or need. */
    private static int upper(EquipmentRequirementProfile profile, String capability) {
        if (profile == null) return 0;
        return profile.need(capability).flatMap(n -> n.maximum().isPresent() ? n.maximum() : n.minimum()).map(Double::intValue).orElse(0);
    }
    private static long owned(GearInventory gear, EquipmentType type) {
        return gear.items().stream().filter(e -> e.type() == type).mapToLong(Equipment::quantity).sum();
    }
    /** Deterministic generation order (brand, model, id), shared by generation and reconstruction. */
    private static List<Equipment> sorted(GearInventory gear) {
        var order = Comparator.comparing((Equipment e) -> e.brand().toLowerCase(Locale.ROOT)).thenComparing(e -> e.model().toLowerCase(Locale.ROOT)).thenComparing(e -> e.id().toString());
        return gear.items().stream().sorted(order).toList();
    }
    private static List<GearSnapshot> accessories(List<Equipment> items) {
        return items.stream().filter(e -> e.type() != EquipmentType.CAMERA && e.type() != EquipmentType.LENS).map(Equipment::snapshot).toList();
    }
    /** Per-record units the selection carries, or null when it carries more of a record than is owned. */
    private static int[] counts(List<GearSnapshot> records, Map<UUID, Integer> wanted) {
        int[] counts = new int[records.size()];
        for (int i = 0; i < records.size(); i++) {
            counts[i] = wanted.getOrDefault(records.get(i).sourceGearId(), 0);
            if (counts[i] < 0 || counts[i] > records.get(i).selectedQuantity()) return null;
        }
        return counts;
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
