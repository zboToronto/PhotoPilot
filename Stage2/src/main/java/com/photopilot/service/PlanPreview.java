package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.agent.AgentContext;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.validation.PlanValidator;

/**
 * F10 result shown before approval: an unapproved plan (new, or a revision of {@code base}), its deterministic
 * validation report, the evidence it was built from and the tool exchanges the agent made. Nothing here is saved;
 * only {@link PlanningService#approve} persists a plan, after revalidating it. PlanningService keeps the preview it
 * issued and decides approval from that one, so a copy with another report cannot make its plan approvable.
 */
public record PlanPreview(UUID sessionId, ShootPlan plan, Optional<ShootPlan> base, PlanValidator.Report validation,
        List<LocalizedMessage> evidence, List<AgentContext.ToolExchange> tools, List<String> questions, String request, List<String> changedSections) {
    public PlanPreview {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(plan); Objects.requireNonNull(base); Objects.requireNonNull(validation);
        evidence = List.copyOf(evidence); tools = List.copyOf(tools); questions = List.copyOf(questions);
        request = Objects.requireNonNullElse(request, ""); changedSections = List.copyOf(changedSections);
    }
    /** Approval is offered only for a deterministically valid, non-provisional preview (approve enforces it on the issued preview). */
    public boolean approvable() { return validation.valid() && plan.provisionalReasons().isEmpty() && !plan.approved(); }
    public boolean revision() { return base.isPresent(); }
}
