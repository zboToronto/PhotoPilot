package com.photopilot.model;

import java.time.Instant;
import java.util.*;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ActivitySuggestion.Feasibility;

/**
 * F02 result of one request: validated ideas in deterministic rank order (feasible first, then unverified, then
 * infeasible), AI candidates rejected before validation with their reasons, the limiting factors when nothing is
 * feasible, and the labelled AI notes. Nothing here is saved until the user saves an individual idea.
 */
public record DiscoveryResult(DiscoveryContext context, List<ActivitySuggestion> ideas, List<Rejected> rejected,
        List<LocalizedMessage> notices, List<LocalizedMessage> limitingFactors, List<String> missingContext,
        List<String> adjustments, String summary, Instant generatedAt) {
    /** A model candidate that failed structural validation; never shown as an idea. */
    public record Rejected(String label, String subject, List<LocalizedMessage> reasons) {
        public Rejected { label = Objects.requireNonNullElse(label, ""); subject = Objects.requireNonNullElse(subject, ""); reasons = List.copyOf(reasons); }
    }

    public DiscoveryResult {
        Objects.requireNonNull(context); ideas = List.copyOf(ideas); rejected = List.copyOf(rejected); notices = List.copyOf(notices);
        limitingFactors = List.copyOf(limitingFactors); missingContext = List.copyOf(missingContext); adjustments = List.copyOf(adjustments);
        summary = Objects.requireNonNullElse(summary, ""); Objects.requireNonNull(generatedAt);
    }

    public List<ActivitySuggestion> with(Feasibility status) { return ideas.stream().filter(idea -> idea.feasibility() == status).toList(); }
    public List<ActivitySuggestion> feasible() { return with(Feasibility.FEASIBLE); }
    public List<ActivitySuggestion> unverified() { return with(Feasibility.UNVERIFIED); }
    public List<ActivitySuggestion> infeasible() { return with(Feasibility.INFEASIBLE); }
    /** Ideas the user may continue with or save: feasible and unverified (with their limitations). */
    public List<ActivitySuggestion> usable() { return ideas.stream().filter(idea -> idea.feasibility() != Feasibility.INFEASIBLE).toList(); }
    public Optional<ActivitySuggestion> find(UUID id) { return ideas.stream().filter(idea -> idea.id().equals(id)).findFirst(); }
    public DiscoveryResult without(UUID id) {
        return new DiscoveryResult(context, ideas.stream().filter(idea -> !idea.id().equals(id)).toList(), rejected, notices, limitingFactors,
            missingContext, adjustments, summary, generatedAt);
    }
}
