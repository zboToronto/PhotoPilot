package com.photopilot.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.command.PlanEditor;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.EnvironmentAssessment;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.Loadout;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.ShootRequirement;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.repository.SessionRepository;
import com.photopilot.validation.PlanValidator;

/**
 * Stage 1 UML element 6 (zero-based): {F03, F06-F10; workflow coordination}
 * F03 parse/clarify/confirm/retrieve are implemented; F06-F10 operations remain NOT IMPLEMENTED.
 */
public class PlanningService {
    private RequirementService requirements;
    private GearMatchingService matching;
    private EnvironmentService environment;
    private PhotoPilotAgent agent;
    private PlanEditor editor;
    private PlanValidator validator;
    private SessionRepository sessions;
    private InventoryRepository inventory;
    private TechnicalCalculator calculator;

    public PlanningService(RequirementService requirements, GearMatchingService matching, EnvironmentService environment, PhotoPilotAgent agent, PlanEditor editor, PlanValidator validator, SessionRepository sessions, InventoryRepository inventory, TechnicalCalculator calculator) {
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.matching = Objects.requireNonNull(matching, "matching");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    /** F03 parse for an existing, still-editable session. Returns an unconfirmed draft; nothing is saved. */
    public Result<ShootRequirement> parse(UUID id, String text) {
        var session = editable(id);
        if (!session.isSuccess()) return Result.failure(session);
        return requirements.parse(text, new AgentContext(id, List.of(), "{}", List.of(), Optional.empty()));
    }

    /** F03 clarification: answers are resubmitted with the draft; the result is still an unconfirmed draft. */
    public Result<ShootRequirement> clarify(UUID id, ShootRequirement draft, String answers) {
        var session = editable(id);
        return session.isSuccess() ? requirements.clarify(draft, answers) : Result.failure(session);
    }

    public Result<ShootRequirement> confirm(UUID id, ShootRequirement edited) {
        var session = sessions.find(id);
        return session.isSuccess() ? confirm(session.value().orElseThrow(), edited) : Result.failure(session);
    }

    /**
     * Confirms against the session version the user reviewed: if another window or process changed it since,
     * the repository's revision check rejects the save instead of silently overwriting.
     * Success is reported only after the atomic session save has succeeded.
     */
    public Result<ShootRequirement> confirm(PhotographySession expected, ShootRequirement edited) {
        if (expected == null) return Result.failureKey("error.session.id");
        var confirmed = requirements.confirm(edited);
        if (!confirmed.isSuccess()) return confirmed;
        var attached = expected.attachRequirement(confirmed.value().orElseThrow());
        if (!attached.isSuccess()) return Result.failure(attached);
        var saved = sessions.save(attached.value().orElseThrow());
        if (!saved.isSuccess()) return Result.failure(saved);
        return saved.value().orElseThrow().requirements().map(Result::success)
            .orElseGet(() -> Result.failureKey("requirement.error.none"));
    }

    /** Confirmed requirement for downstream planning features (F06-F10); drafts are never returned. */
    public Result<ShootRequirement> requirement(UUID id) {
        var session = sessions.find(id);
        if (!session.isSuccess()) return Result.failure(session);
        return session.value().orElseThrow().requirements().filter(ShootRequirement::confirmed).map(Result::success)
            .orElseGet(() -> Result.failureKey("requirement.error.none"));
    }

    private Result<PhotographySession> editable(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var session = sessions.find(id);
        if (!session.isSuccess()) return session;
        return session.value().orElseThrow().state().canEditPlan() ? session : Result.failureKey("requirement.error.historical");
    }

    public Result<EquipmentRequirementProfile> analyzeNeeds(UUID id) {
        return Result.failureKey("error.notImplemented", "PlanningService.analyzeNeeds");
    }

    public Result<EquipmentRequirementProfile> confirmProfile(UUID id, EquipmentRequirementProfile edited) {
        return Result.failureKey("error.notImplemented", "PlanningService.confirmProfile");
    }

    public Result<List<Loadout>> matchGear(UUID id) {
        return Result.failureKey("error.notImplemented", "PlanningService.matchGear");
    }

    public Result<EnvironmentAssessment> assessEnvironment(UUID id) {
        return Result.failureKey("error.notImplemented", "PlanningService.assessEnvironment");
    }

    public Result<ShootPlan> generate(UUID id) {
        return Result.failureKey("error.notImplemented", "PlanningService.generate");
    }

    public Result<ShootPlan> proposeChange(UUID id, String text) {
        return Result.failureKey("error.notImplemented", "PlanningService.proposeChange");
    }

    public Result<ShootPlan> approve(UUID id, ShootPlan plan) {
        return Result.failureKey("error.notImplemented", "PlanningService.approve");
    }
}
