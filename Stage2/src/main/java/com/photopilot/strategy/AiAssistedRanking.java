package com.photopilot.strategy;

import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.GearMatchReport.Candidate;
import com.photopilot.model.GearMatchReport.NeedStatus;
import com.photopilot.validation.CompatibilityValidator;

/**
 * Shared Strategy skeleton for the two Stage 1 AI concrete strategies. A deterministic priority order is always
 * computed; Gemini then compares the top validated candidates and may only reorder them. Its reply is structurally
 * validated (known labels, each exactly once, non-blank explanations); any failure keeps the deterministic order,
 * clearly labelled. Fully suitable loadouts always rank ahead of partial matches, whatever the model says.
 */
abstract class AiAssistedRanking implements LoadoutRankingStrategy {
    static final int AI_CANDIDATES = 5, MAX_EXPLANATION = 600, MAX_SUMMARY = 1200;
    static final Set<String> RANKING_SPECS = Set.of("weightGrams", "minFocalMm", "maxFocalMm", "maxAperture", "maxApertureAtMaxFocal",
        "burstFps", "resolutionMP", "stabilization", "autofocus", "capacityGB", "writeSpeedMBps");
    static final String TASK = """
        You compare photography equipment loadouts for the PhotoPilot app. Every candidate in the application evidence
        was already verified as physically compatible by deterministic rules, and its capability findings are final.
        Rules:
        1. Rank every listed candidate exactly once, using only its label (C1, C2, ...). Never add, merge or rename candidates.
        2. Use only the specifications and findings given. Never introduce equipment, adapters, weights or specifications,
           and never claim a candidate meets a need listed in its unmetNeeds.
        3. Fully suitable candidates must stay ahead of partial matches.
        4. For each candidate write one or two short sentences explaining its trade-offs for the stated priority
           (for example telephoto reach versus carried weight, lens changes, or unmet needs).
        5. summary: one or two sentences comparing the best options.
        Reply only with JSON that matches the response schema.
        """;
    static final String SCHEMA = """
        {"type":"object","properties":{"ranking":{"type":"array","items":{"type":"object","properties":{
        "candidate":{"type":"string"},"explanation":{"type":"string"}},"required":["candidate","explanation"]}},
        "summary":{"type":"string"}},"required":["ranking","summary"]}
        """;

    /** Untrusted structured Gemini reply; bound by the agent's strict JSON decoder, then validated here. */
    public record Reply(List<Entry> ranking, String summary) { public record Entry(String candidate, String explanation) { } }

    private final PhotoPilotAgent agent;
    AiAssistedRanking(PhotoPilotAgent agent) { this.agent = Objects.requireNonNull(agent, "agent"); }

    /** Strategy-specific tie order after suitability; deterministic and independent of AI. */
    abstract Comparator<Metrics> priorityOrder();
    /** Priority statement sent to Gemini. */
    abstract String priorityInstruction();

    public Result<List<Loadout>> rank(List<Loadout> candidates, EquipmentRequirementProfile profile) {
        if (candidates == null) return Result.failureKey("match.error.noCandidates");
        var validator = new CompatibilityValidator();
        var evaluated = candidates.stream().map(l -> new Candidate(l, CompatibilityValidator.evaluateNeeds(l, profile),
            validator.assess(l).essentials(), List.of(), Optional.empty())).toList();
        var outcome = rankCandidates(evaluated, profile, "");
        return outcome.isSuccess() ? Result.success(outcome.value().orElseThrow().ranked().stream().map(Candidate::loadout).toList()) : Result.failure(outcome);
    }

    public Result<RankingOutcome> rankCandidates(List<Candidate> candidates, EquipmentRequirementProfile profile, String shootContext) {
        if (candidates == null) return Result.failureKey("match.error.noCandidates");
        // Only verified candidates may be ranked; UC14 has final authority.
        if (candidates.stream().anyMatch(c -> !c.loadout().compatibility().isUsable())) return Result.failureKey("match.error.notUsable");
        var ordered = candidates.stream().map(Metrics::of)
            .sorted(Comparator.comparing((Metrics m) -> !m.full()).thenComparingLong(Metrics::mandatoryUnmet)
                .thenComparingInt(Metrics::weightedUnmet).thenComparing(priorityOrder())
                .thenComparing(m -> m.candidate().loadout().id().toString()))
            .map(Metrics::candidate).toList();
        if (ordered.isEmpty()) return Result.success(new RankingOutcome(List.of(), false, "", Optional.empty()));
        var sent = ordered.subList(0, Math.min(AI_CANDIDATES, ordered.size()));
        var reply = agent.run(TASK, new AgentContext(UUID.randomUUID(), List.of("Rank these verified loadout candidates. Priority: " + priorityInstruction()),
            evidence(sent, profile, shootContext), List.of(), Optional.empty()).withResponseSchema(SCHEMA), Reply.class);
        var accepted = reply.isSuccess() ? accept(reply.value().orElseThrow(), sent) : Result.<List<Candidate>>failure(reply);
        if (!accepted.isSuccess()) {
            var reason = accepted.messages().isEmpty() ? LocalizedMessage.of("match.ai.invalid") : accepted.messages().getFirst();
            return Result.success(new RankingOutcome(ordered, false, "", Optional.of(LocalizedMessage.of("match.ranking.fallback", reason))));
        }
        var ranked = new ArrayList<Candidate>(accepted.value().orElseThrow());
        // A model may not lift a partial match above a fully suitable one: stable partition restores the tiers.
        ranked.sort(Comparator.comparing(c -> !c.fullySuitable()));
        ranked.addAll(ordered.subList(sent.size(), ordered.size()));
        String summary = Objects.toString(reply.value().orElseThrow().summary(), "").strip();
        return Result.success(new RankingOutcome(ranked, true, summary, Optional.empty()));
    }

    private static Result<List<Candidate>> accept(Reply reply, List<Candidate> sent) {
        if (reply.ranking() == null) return Result.failureKey("match.ai.invalid");
        if (reply.summary() != null && reply.summary().length() > MAX_SUMMARY) return Result.failureKey("match.ai.invalid");
        var byLabel = new LinkedHashMap<String, Candidate>();
        for (int i = 0; i < sent.size(); i++) byLabel.put("C" + (i + 1), sent.get(i));
        var seen = new HashSet<String>(); var result = new ArrayList<Candidate>();
        for (var entry : reply.ranking()) {
            if (entry == null || entry.candidate() == null) return Result.failureKey("match.ai.invalid");
            String label = entry.candidate().strip();
            if (!byLabel.containsKey(label)) return Result.failureKey("match.ai.unknownCandidate", label);
            if (!seen.add(label)) return Result.failureKey("match.ai.duplicateCandidate", label);
            String text = Objects.toString(entry.explanation(), "").strip();
            if (text.isEmpty() || text.length() > MAX_EXPLANATION) return Result.failureKey("match.ai.explanation", label);
            result.add(byLabel.get(label).withExplanation(Optional.of(text)));
        }
        if (seen.size() != byLabel.size()) return Result.failureKey("match.ai.missingCandidate");
        return Result.success(result);
    }

    /** Only capability needs and the candidates' recorded gear values; no IDs, notes, locations or credentials. */
    private String evidence(List<Candidate> sent, EquipmentRequirementProfile profile, String shootContext) {
        var root = new JsonObject();
        root.addProperty("priority", priorityInstruction());
        if (!shootContext.isBlank()) root.addProperty("shoot", shootContext);
        var needs = new JsonArray();
        for (var need : profile == null ? List.<CapabilityNeed>of() : profile.needs()) {
            var item = new JsonObject(); item.addProperty("capability", need.capability()); item.addProperty("unit", need.unit());
            need.minimum().ifPresent(v -> item.addProperty("minimum", v)); need.maximum().ifPresent(v -> item.addProperty("maximum", v));
            item.addProperty("priority", need.priority()); item.addProperty("mandatory", need.hardConstraint()); needs.add(item);
        }
        root.add("needs", needs);
        var list = new JsonArray();
        for (int i = 0; i < sent.size(); i++) {
            var candidate = sent.get(i); var loadout = candidate.loadout(); var item = new JsonObject();
            item.addProperty("label", "C" + (i + 1)); item.addProperty("fullySuitable", candidate.fullySuitable());
            loadout.totalWeightGrams().ifPresentOrElse(w -> item.addProperty("totalWeightGrams", w), () -> item.addProperty("totalWeightGrams", "unknown"));
            item.addProperty("cameraBodies", loadout.units(EquipmentType.CAMERA)); item.addProperty("lensUnits", loadout.units(EquipmentType.LENS));
            item.addProperty("focalCoverage", String.join("; ", CompatibilityValidator.focalCoverage(loadout).stream().map(CompatibilityValidator::range).toList()));
            var unmet = new JsonArray();
            CompatibilityValidator.english(candidate.needs().stream().filter(n -> n.status() != NeedStatus.MET).map(GearMatchReport.NeedResult::detail).toList()).forEach(unmet::add);
            CompatibilityValidator.english(candidate.essentials()).forEach(unmet::add);
            item.add("unmetNeeds", unmet);
            var gear = new JsonArray();
            for (var snapshot : loadout.gear()) {
                var g = new JsonObject(); g.addProperty("type", snapshot.type()); g.addProperty("name", CompatibilityValidator.name(snapshot));
                g.addProperty("quantity", snapshot.selectedQuantity());
                // Compatibility is already settled, so only ranking-relevant recorded values are sent (smaller, faster request).
                var specs = new JsonObject();
                new TreeMap<>(snapshot.specs()).forEach((key, value) -> { if (RANKING_SPECS.contains(key)) specs.addProperty(key, value); });
                g.add("specs", specs); gear.add(g);
            }
            item.add("gear", gear); list.add(item);
        }
        root.add("candidates", list);
        return root.toString();
    }

    /** Deterministic ranking values derived from verified snapshots only. */
    record Metrics(Candidate candidate, boolean full, long mandatoryUnmet, int weightedUnmet, Optional<Double> weight,
            double focalSpan, double maxFocal, int lensUnits, int itemUnits) {
        static Metrics of(Candidate c) {
            var loadout = c.loadout(); var coverage = CompatibilityValidator.focalCoverage(loadout);
            double span = coverage.stream().mapToDouble(r -> r[0] == r[1] ? 0.1 : Math.log(r[1] / r[0])).sum();
            double longest = coverage.stream().mapToDouble(r -> r[1]).max().orElse(0);
            int weighted = c.needs().stream().filter(n -> n.status() != NeedStatus.MET).mapToInt(n -> CapabilityNeed.MAX_PRIORITY + 1 - n.need().priority()).sum()
                + c.essentials().size() * CapabilityNeed.MAX_PRIORITY;
            return new Metrics(c, c.fullySuitable(), c.mandatoryUnmet(), weighted, loadout.totalWeightGrams(), span, longest,
                loadout.units(EquipmentType.LENS), loadout.gear().stream().mapToInt(GearSnapshot::selectedQuantity).sum());
        }
        double weightOrMax() { return weight.orElse(Double.MAX_VALUE); }
    }
}
