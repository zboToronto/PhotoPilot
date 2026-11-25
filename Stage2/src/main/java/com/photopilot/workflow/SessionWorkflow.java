package com.photopilot.workflow;

import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.PhotographySession;
import com.photopilot.progress.NextStep;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Workflow progress of one photography session, derived from its stored records (see
 * {@code service.SessionWorkflowService}). It is the single representation the sidebar, page headers, Home, the
 * session list and the CLI read; nothing keeps a separate counter. Progress is milestone completion only
 * (completed / applicable), never time, page visits or AI requests.
 */
public record SessionWorkflow(PhotographySession session, Phase phase, List<MilestoneState> milestones) {
    /** Where the session stands, from its lifecycle state and stored records. */
    public enum Phase {
        GETTING_STARTED, PLANNING, READY, AWAITING_REVIEW, REVIEWED;
        public String labelKey() { return "workflow.phase." + name(); }
    }

    public SessionWorkflow {
        Objects.requireNonNull(session, "session"); Objects.requireNonNull(phase, "phase");
        milestones = List.copyOf(milestones);
        if (milestones.size() != Milestone.values().length) throw new IllegalArgumentException("Expected one state per milestone.");
        for (int i = 0; i < milestones.size(); i++)
            if (milestones.get(i).milestone().ordinal() != i) throw new IllegalArgumentException("Milestones must be in workflow order.");
    }

    public UUID sessionId() { return session.id(); }
    public String title() { return session.title(); }
    /** Lifecycle state name (DRAFT, PLANNED, COMPLETED, REVIEWED). */
    public String lifecycle() { return session.status(); }

    public MilestoneState state(Milestone milestone) { return milestones.get(milestone.ordinal()); }
    public MilestoneStatus status(Milestone milestone) { return state(milestone).status(); }
    public int completed() { return (int) milestones.stream().filter(m -> m.status() == MilestoneStatus.COMPLETED).count(); }
    public int applicable() { return (int) milestones.stream().filter(m -> m.status().applicable()).count(); }
    /** Every applicable milestone is completed. */
    public boolean complete() { return completed() == applicable(); }
    /** Completed share in whole percent; 100 only when the session is complete, so rounding never claims it early. */
    public int percent() {
        int of = applicable();
        if (of == 0) return 0;
        if (complete()) return 100;
        return (int) Math.min(99, Math.round(100.0 * completed() / of));
    }
    /** A milestone needs renewed confirmation or an action now. */
    public boolean attention() { return milestones.stream().anyMatch(m -> m.status().attention()); }
    /** The recommended next milestone: the first one in order that can be acted on; empty once the session is complete. */
    public Optional<MilestoneState> next() { return milestones.stream().filter(m -> m.status().actionable()).findFirst(); }
    /** The same recommendation as guidance with its page; never approves, saves or opens anything by itself. */
    public Optional<NextStep> nextStep() {
        return next().map(m -> NextStep.to(m.milestone().target(), guidanceKey(m)));
    }
    /** The guidance sentence for an actionable milestone: re-check, act now, continue, or start. */
    public static String guidanceKey(MilestoneState state) {
        String prefix = switch (state.status()) {
            case NEEDS_REVIEW -> "workflow.recheck.";
            case ACTION_REQUIRED -> "workflow.act.";
            case IN_PROGRESS -> "workflow.continue.";
            default -> "workflow.next.";
        };
        return prefix + state.milestone().name();
    }
    /** "57% · 4 of 7 milestones". */
    public LocalizedMessage progress() { return LocalizedMessage.of("workflow.progress", percent(), completed(), applicable()); }
}
