package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.command.ShootPlan;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.EnvironmentAssessment;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.ShootRequirement;

/**
 * Read-only F10 readiness view for GUI and CLI: what the planner would use and what is missing. No AI call.
 * {@code blockers} are mandatory inputs (UC10 preconditions); {@code notes} describe optional evidence.
 */
public record PlanInputs(PhotographySession session, Optional<ShootRequirement> requirement, Optional<EquipmentRequirementProfile> profile,
        int candidates, Optional<EnvironmentAssessment> environment, List<LocalizedMessage> blockers, List<LocalizedMessage> notes,
        List<ShootPlan> plans) {
    public PlanInputs {
        Objects.requireNonNull(session); Objects.requireNonNull(requirement); Objects.requireNonNull(profile); Objects.requireNonNull(environment);
        blockers = List.copyOf(blockers); notes = List.copyOf(notes); plans = List.copyOf(plans);
    }
    public boolean ready() { return blockers.isEmpty(); }
    /** Latest approved revision, if a plan was approved. */
    public Optional<ShootPlan> current() { return PlanningService.latest(plans); }
}
