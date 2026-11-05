package com.photopilot.model;

import com.photopilot.i18n.LocalizedMessage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * F07 result for one Match My Gear request: deterministically verified recommendations in ranked order, the
 * combinations and inventory items UC14 rejected, and how the ranking was produced. Findings stay locale-neutral.
 * AI text is advisory only and is never copied into saved loadouts.
 */
public record GearMatchReport(String strategy, List<Candidate> recommendations, int combinationsChecked, int rejectedCombinations,
        List<LocalizedMessage> rejections, boolean aiRanked, String aiSummary, Optional<LocalizedMessage> rankingNotice, boolean searchLimited,
        boolean unverifiedExclusions) {
    public GearMatchReport {
        Objects.requireNonNull(strategy); recommendations = List.copyOf(recommendations); rejections = List.copyOf(rejections);
        Objects.requireNonNull(aiSummary); Objects.requireNonNull(rankingNotice);
    }

    /**
     * UC07 A1: true when an item was excluded as unverified or a recommendation has an UNKNOWN need, so the user must
     * record or confirm the missing specification in F05 before it can be verified.
     */
    public boolean missingSpecifications() {
        return unverifiedExclusions || recommendations.stream().anyMatch(c -> c.needs().stream().anyMatch(n -> n.status() == NeedStatus.UNKNOWN));
    }

    public enum NeedStatus { MET, UNMET, UNKNOWN }
    /** One capability need compared with verified loadout values; UNKNOWN means a specification is missing. */
    public record NeedResult(CapabilityNeed need, NeedStatus status, LocalizedMessage detail) { }

    /** A physically verified loadout with its capability comparison, deterministic trade-offs and optional AI note. */
    public record Candidate(Loadout loadout, List<NeedResult> needs, List<LocalizedMessage> essentials,
            List<LocalizedMessage> tradeOffs, Optional<String> aiExplanation) {
        public Candidate {
            Objects.requireNonNull(loadout); needs = List.copyOf(needs); essentials = List.copyOf(essentials);
            tradeOffs = List.copyOf(tradeOffs); Objects.requireNonNull(aiExplanation);
        }
        public boolean fullySuitable() {
            return loadout.compatibility().isUsable() && essentials.isEmpty() && needs.stream().allMatch(n -> n.status() == NeedStatus.MET);
        }
        public long mandatoryUnmet() { return needs.stream().filter(n -> n.status() != NeedStatus.MET && n.need().hardConstraint()).count(); }
        public Candidate withExplanation(Optional<String> text) { return new Candidate(loadout, needs, essentials, tradeOffs, text); }
    }

    /** Saved F07 selection re-evaluated against the current profile and inventory. */
    public record SelectionStatus(Loadout loadout, Candidate evaluation, List<LocalizedMessage> compatibility, List<LocalizedMessage> availability) {
        public SelectionStatus { Objects.requireNonNull(loadout); Objects.requireNonNull(evaluation); compatibility = List.copyOf(compatibility); availability = List.copyOf(availability); }
        public boolean current() { return compatibility.isEmpty() && availability.isEmpty(); }
    }
}
