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
 * F03 parse/clarify/confirm/retrieve, F07 match/select/selection and F09 assessEnvironment are implemented, with the
 * minimal deterministic F06 confirmProfile/profile path they depend on. F06 analyzeNeeds (AI derivation) and F10
 * remain NOT IMPLEMENTED.
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

    /**
     * F06 minimal confirmation path (no AI): validates manually entered capability needs deterministically and
     * against the session's confirmed requirement, then saves the reviewed profile atomically.
     */
    public Result<EquipmentRequirementProfile> confirmProfile(UUID id, EquipmentRequirementProfile edited) {
        var session = sessions.find(id);
        return session.isSuccess() ? confirmProfile(session.value().orElseThrow(), edited) : Result.failure(session);
    }

    /** Confirms against the reviewed session version; a concurrent change is rejected by the revision check. */
    public Result<EquipmentRequirementProfile> confirmProfile(PhotographySession expected, EquipmentRequirementProfile edited) {
        if (expected == null) return Result.failureKey("error.session.id");
        if (!expected.state().canEditPlan()) return Result.failureKey("needs.error.historical");
        var requirement = expected.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) return Result.failureKey("needs.error.requirement");
        var structural = requirements.validateProfile(edited);
        if (!structural.isSuccess()) return structural;
        var consistent = edited.validate(requirement.get());
        if (!consistent.isSuccess()) return consistent;
        var confirmed = edited.confirm();
        if (!confirmed.isSuccess()) return confirmed;
        var attached = expected.attachProfile(confirmed.value().orElseThrow());
        if (!attached.isSuccess()) return Result.failure(attached);
        var saved = sessions.save(attached.value().orElseThrow());
        if (!saved.isSuccess()) return Result.failure(saved);
        return saved.value().orElseThrow().profile().map(Result::success).orElseGet(() -> Result.failureKey("needs.error.none"));
    }

    /** Confirmed capability profile for F07/F10; unreviewed profiles are never returned. */
    public Result<EquipmentRequirementProfile> profile(UUID id) {
        var session = sessions.find(id);
        if (!session.isSuccess()) return Result.failure(session);
        return session.value().orElseThrow().profile().filter(EquipmentRequirementProfile::reviewed).map(Result::success)
            .orElseGet(() -> Result.failureKey("needs.error.none"));
    }

    public Result<List<Loadout>> matchGear(UUID id) {
        var report = matchGear(id, matching.defaultStrategy());
        return report.isSuccess() ? Result.success(report.value().orElseThrow().recommendations().stream()
            .map(com.photopilot.model.GearMatchReport.Candidate::loadout).toList()) : Result.failure(report);
    }

    /** F07/UC07: confirmed profile + current F05 inventory -> UC14-verified, ranked candidates. Nothing is saved. */
    public Result<com.photopilot.model.GearMatchReport> matchGear(UUID id, String strategy) {
        if (id == null) return Result.failureKey("error.session.id");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        if (!session.state().canEditPlan()) return Result.failureKey("match.error.historical");
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        if (profile.isEmpty()) return Result.failureKey("needs.error.none");
        var gear = inventory.load();
        if (!gear.isSuccess()) return Result.failure(gear);
        return matching.analyze(profile.get(), gear.value().orElseThrow(), strategy, shootContext(session));
    }

    public Result<Loadout> selectLoadout(UUID id, Loadout selected) {
        var session = sessions.find(id);
        return session.isSuccess() ? selectLoadout(session.value().orElseThrow(), selected, false) : Result.failure(session);
    }

    /**
     * Explicit F07 selection. Revalidates everything against current data before the atomic save: the candidate must
     * still be generated from the current inventory (unknown/stale IDs are rejected), every item must be owned and
     * unchanged, UC14 must verify it, and needs are re-evaluated against the current profile. An existing selection
     * is replaced only when {@code replace} is true. Success is reported only after the save succeeded.
     */
    public Result<Loadout> selectLoadout(PhotographySession expected, Loadout selected, boolean replace) {
        if (expected == null) return Result.failureKey("error.session.id");
        if (selected == null) return Result.failureKey("match.error.noSelection");
        if (!expected.state().canEditPlan()) return Result.failureKey("match.error.historical");
        if (expected.selectedLoadout().isPresent() && !replace) return Result.failureKey("match.error.alreadySelected");
        var profile = expected.profile().filter(EquipmentRequirementProfile::reviewed);
        if (profile.isEmpty()) return Result.failureKey("needs.error.none");
        var loaded = inventory.load();
        if (!loaded.isSuccess()) return Result.failure(loaded);
        var gear = loaded.value().orElseThrow();
        var unavailable = matching.validator().availability(selected, gear);
        if (!unavailable.isEmpty()) return Result.failures(unavailable);
        if (!GearMatchingService.candidateId(selected.gear()).equals(selected.id())) return Result.failureKey("match.error.staleCandidate");
        var current = matching.compatibleCandidates(gear);
        if (!current.isSuccess()) return Result.failure(current);
        var fresh = current.value().orElseThrow().stream().filter(l -> l.id().equals(selected.id())).findFirst();
        if (fresh.isEmpty()) return Result.failureKey("match.error.staleCandidate");
        var check = matching.validator().assess(fresh.get());
        if (!check.usable()) {
            var problems = new java.util.ArrayList<>(check.violations()); problems.addAll(check.unverified());
            return Result.failures(problems);
        }
        var evaluated = matching.evaluate(fresh.get(), profile.get()).loadout();
        var attached = expected.attachSelectedLoadout(evaluated);
        if (!attached.isSuccess()) return Result.failure(attached);
        var saved = sessions.save(attached.value().orElseThrow());
        if (!saved.isSuccess()) return Result.failure(saved);
        return saved.value().orElseThrow().selectedLoadout().map(Result::success).orElseGet(() -> Result.failureKey("match.error.noSavedSelection"));
    }

    /** Saved selection re-checked against the current inventory and profile (read-only; usable by F10). */
    public Result<com.photopilot.model.GearMatchReport.SelectionStatus> selection(UUID id) {
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        var saved = session.selectedLoadout();
        if (saved.isEmpty()) return Result.failureKey("match.error.noSavedSelection");
        var gear = inventory.load();
        if (!gear.isSuccess()) return Result.failure(gear);
        var check = matching.validator().assess(saved.get());
        var compatibility = new java.util.ArrayList<>(check.violations()); compatibility.addAll(check.unverified());
        var evaluation = matching.evaluate(saved.get(), session.profile().filter(EquipmentRequirementProfile::reviewed).orElse(null));
        return Result.success(new com.photopilot.model.GearMatchReport.SelectionStatus(saved.get(), evaluation, compatibility,
            matching.validator().availability(saved.get(), gear.value().orElseThrow())));
    }

    /** Ranking strategies available to GUI and CLI; the first is the configured default. */
    public List<String> rankingStrategies() {
        var names = new java.util.ArrayList<>(matching.strategies());
        names.remove(matching.defaultStrategy()); names.addFirst(matching.defaultStrategy());
        return names;
    }

    /** Short, non-identifying shoot context for AI comparison: subject, priorities and portability wording only. */
    private static String shootContext(PhotographySession session) {
        return session.requirements().filter(ShootRequirement::confirmed).map(r -> {
            var parts = new java.util.ArrayList<String>();
            if (!r.subject().isBlank()) parts.add("subject: " + r.subject());
            if (!r.priorities().isEmpty()) parts.add("priorities: " + r.priorities());
            if (!r.portability().isBlank()) parts.add("portability: " + r.portability());
            r.lensChangesAllowed().ifPresent(allowed -> parts.add("lens changes " + (allowed ? "allowed" : "to be avoided")));
            return String.join("; ", parts);
        }).orElse("");
    }

    /** Stage 1 SD03 entry: assessment from the confirmed requirement alone (it must carry coordinates and a start time). */
    public Result<EnvironmentAssessment> assessEnvironment(UUID id) {
        var session = environmentSession(id);
        if (!session.isSuccess()) return Result.failure(session);
        var requirement = session.value().orElseThrow().requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) return Result.failureKey("env.error.needsInput", "confirmed requirement (F03) or reviewed F09 inputs");
        return environment.assess(requirement.get());
    }

    /**
     * F09/UC09 with the location, date and availability the user reviewed. Reads the session and its confirmed F03
     * requirement (never modified); returns a validated assessment for display or later F10 use. Nothing is saved.
     */
    public Result<EnvironmentAssessment> assessEnvironment(UUID id, com.photopilot.model.EnvironmentRequest request) {
        var found = environmentSession(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        var assessed = environment.assess(session.requirements().filter(ShootRequirement::confirmed).orElse(null), request);
        if (!assessed.isSuccess() || request == null || request.date() == null || session.date().equals(request.date())) return assessed;
        return Result.success(assessed.value().orElseThrow().withNotices(List.of(com.photopilot.i18n.LocalizedMessage.of(
            "env.notice.sessionDate", session.date().toString(), request.date().toString()))));
    }

    /** Location selection (manual, consented device, place lookup) shared by the F09 GUI and CLI. */
    public LocationService location() { return environment.location(); }

    /** Evidence groups of an earlier assessment that are stale now; F10 must reassess before relying on them. */
    public java.util.Set<String> environmentStaleFields(EnvironmentAssessment assessment) {
        return assessment == null ? java.util.Set.of() : environment.staleFields(assessment.context());
    }

    private Result<PhotographySession> environmentSession(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var session = sessions.find(id);
        if (!session.isSuccess()) return session;
        return session.value().orElseThrow().state().canEditPlan() ? session : Result.failureKey("env.error.historical");
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
