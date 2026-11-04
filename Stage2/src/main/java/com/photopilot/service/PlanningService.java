package com.photopilot.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * F03 parse/clarify/confirm/retrieve, F07 match/select/selection, F09 assessEnvironment and F10 generate/proposeChange/
 * approve (SD04) are implemented, with the minimal deterministic F06 confirmProfile/profile path they depend on.
 * F06 analyzeNeeds (AI derivation) remains NOT IMPLEMENTED.
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
    private final PlanAssembly assembly;
    /** Latest successful F09 assessment per session in this process, for F10 (never persisted). */
    private final Map<UUID, EnvironmentAssessment> assessments = new java.util.concurrent.ConcurrentHashMap<>();
    /** Previews issued by this service, keyed by the exact preview object, with their owning session. */
    private record Issued(ShootPlan plan) {
        @Override public boolean equals(Object other) { return other instanceof Issued that && that.plan == plan; }
        @Override public int hashCode() { return System.identityHashCode(plan); }
    }
    private final Map<Issued, UUID> issued = new java.util.LinkedHashMap<>();

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
        this.assembly = new PlanAssembly(matching, calculator);
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
        return remember(id, environment.assess(requirement.get()));
    }
    private Result<EnvironmentAssessment> remember(UUID id, Result<EnvironmentAssessment> assessed) {
        assessed.value().ifPresent(value -> assessments.put(id, value));
        return assessed;
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
        if (!assessed.isSuccess() || request == null || request.date() == null || session.date().equals(request.date())) return remember(id, assessed);
        return remember(id, Result.success(assessed.value().orElseThrow().withNotices(List.of(com.photopilot.i18n.LocalizedMessage.of(
            "env.notice.sessionDate", session.date().toString(), request.date().toString())))));
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

    // ---------------------------------------------------------------- F10 / UC10, UC11, UC14 (SD04)

    /** Tools the F10 agent may call: deterministic F08 calculations. EnvironmentTool needs coordinates, which never go to Gemini. */
    public static final Set<String> PLAN_TOOLS = Set.of("calculation");
    public static final int MAX_CHANGE_REQUEST = 2000;
    private static final int MAX_ISSUED = 64;

    /** Readiness and evidence the planner would use for this session; read-only, no AI call. */
    public Result<PlanInputs> planInputs(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        var blockers = new ArrayList<com.photopilot.i18n.LocalizedMessage>(); var notes = new ArrayList<com.photopilot.i18n.LocalizedMessage>();
        if (!session.state().canEditPlan()) blockers.add(com.photopilot.i18n.LocalizedMessage.of("plan.error.historical"));
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        if (requirement.isEmpty()) blockers.add(com.photopilot.i18n.LocalizedMessage.of("plan.missing.requirement"));
        if (profile.isEmpty()) blockers.add(com.photopilot.i18n.LocalizedMessage.of("plan.missing.profile"));
        var assessment = Optional.ofNullable(assessments.get(id));
        int offered = 0;
        if (requirement.isPresent() && profile.isPresent()) {
            var gear = inventory.load();
            if (!gear.isSuccess()) notes.addAll(gear.messages());
            else {
                var offers = assembly.offers(session, requirement.get(), profile.get(), gear.value().orElseThrow(), latest(session.plans()));
                offered = offers.offers().size(); notes.addAll(offers.notes());
            }
            notes.addAll(assembly.timing(session, requirement.get(), assessment, staleOf(assessment)).notes());
        }
        return Result.success(new PlanInputs(session, requirement, profile, offered, assessment, blockers, notes, session.plans()));
    }

    /** Latest F09 assessment of this session made through this service in this process (never persisted). */
    public Optional<EnvironmentAssessment> environmentFor(UUID id) { return id == null ? Optional.empty() : Optional.ofNullable(assessments.get(id)); }

    /** Approved revisions of the session's plan, oldest first; nothing unapproved is ever stored. */
    public Result<List<ShootPlan>> plans(UUID id) {
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        return Result.success(found.value().orElseThrow().plans().stream().filter(ShootPlan::approved)
            .sorted(java.util.Comparator.comparingInt(ShootPlan::revision)).toList());
    }
    /** Latest approved revision. */
    public static Optional<ShootPlan> latest(List<ShootPlan> plans) {
        return plans.stream().filter(ShootPlan::approved).max(java.util.Comparator.comparingInt(ShootPlan::revision));
    }

    /** Stage 1 SD04 entry: a validated, approvable proposal or the reasons it is not; nothing is saved. */
    public Result<ShootPlan> generate(UUID id) {
        var preview = generatePreview(id);
        return preview.isSuccess() ? approvable(preview.value().orElseThrow()) : Result.failure(preview);
    }

    /** UC10 with the session's latest F09 assessment from this process, if any. */
    public Result<PlanPreview> generatePreview(UUID id) {
        return generatePreview(id, id == null ? null : assessments.get(id));
    }

    /**
     * UC10: confirmed requirement + reviewed profile + verified owned loadouts (F07) + deterministic calculations
     * (F08) + the F09 assessment the user reviewed (optional; stale groups are withheld) + related history ->
     * Gemini (bounded tool loop) -> deterministic validation -> unapproved preview. The session is not changed.
     * When an approved plan exists, a new proposal is only generated if the confirmed requirement changed since; it then
     * becomes that plan's next revision (a regeneration). Otherwise changes go through {@link #revisePreview}.
     */
    public Result<PlanPreview> generatePreview(UUID id, EnvironmentAssessment assessment) {
        if (id == null) return Result.failureKey("error.session.id");
        var inputs = planningInputs(id);
        if (!inputs.isSuccess()) return Result.failure(inputs);
        var in = inputs.value().orElseThrow();
        var existing = latest(in.session().plans());
        if (existing.isPresent() && existing.get().fixedConstraints().equals(PlanValidator.constraintsOf(in.requirement(), Optional.of(in.profile()))))
            return Result.failureKey("plan.error.exists", existing.get().revision());
        var offers = assembly.offers(in.session(), in.requirement(), in.profile(), in.gear(), existing);
        var environmentEvidence = Optional.ofNullable(assessment);
        var timing = assembly.timing(in.session(), in.requirement(), environmentEvidence, staleOf(environmentEvidence));
        var context = new AgentContext(id, List.of("Generate the shoot plan for this photography session."),
            assembly.evidence(in.requirement(), in.profile(), offers, timing, Optional.empty()), List.of(), Optional.empty())
            .withResponseSchema(PlanGeneration.SCHEMA);
        var run = agent.runWithTools(PlanAssembly.GENERATE, context, PlanGeneration.class, PLAN_TOOLS);
        if (!run.isSuccess()) return aiFailure(run);
        var output = run.value().orElseThrow();
        var built = assembly.plan(output.output(), offers, timing, in.session(), in.requirement(), in.profile(),
            existing.map(ShootPlan::id).orElseGet(UUID::randomUUID), existing.map(p -> p.revision() + 1).orElse(1), output.tools());
        var plan = built.plan();
        if (existing.isPresent()) plan = plan.asRegeneration().withExplanation(plan.summary(), com.photopilot.i18n.EnglishMessages.text("plan.regenerated"),
            plan.limitations(), plan.calculations(), plan.provisionalReasons());
        var report = merge(validator.assess(plan, in.requirement(), validation(in, timing, Optional.empty())), built.problems());
        issue(plan, id);
        var evidence = evidence(in, offers, built.notes(), output);
        var changed = existing.map(plan::differingSections).orElse(List.of()).stream().filter(ShootPlan.DECISIONS::contains).toList();
        return Result.success(new PlanPreview(id, plan, existing, report, evidence, output.tools(), texts(output.output().questions()), "", changed));
    }

    /** Stage 1 SD04 entry for UC11: the validated, approvable revision preview or the reasons it is not; nothing is saved. */
    public Result<ShootPlan> proposeChange(UUID id, String text) {
        var preview = revisePreview(id, text);
        return preview.isSuccess() ? approvable(preview.value().orElseThrow()) : Result.failure(preview);
    }

    /**
     * UC11: the saved approved plan + confirmed constraints + current evidence -> Gemini interprets the request ->
     * PlanCommands for the named sections -> PlanEditor.preview on copyForRevision() (the saved base is untouched) ->
     * validateChange and full validation. Ambiguous requests return the model's questions; conflicts with fixed
     * constraints are rejected with the model's explanation; nothing is saved.
     */
    public Result<PlanPreview> revisePreview(UUID id, String text) {
        if (id == null) return Result.failureKey("error.session.id");
        if (text == null || text.isBlank()) return Result.failureKey("plan.error.request");
        if (text.strip().length() > MAX_CHANGE_REQUEST) return Result.failureKey("plan.error.requestLength", MAX_CHANGE_REQUEST);
        var inputs = planningInputs(id);
        if (!inputs.isSuccess()) return Result.failure(inputs);
        var in = inputs.value().orElseThrow();
        var base = latest(in.session().plans());
        if (base.isEmpty()) return Result.failureKey("plan.error.noSavedPlan");
        var offers = assembly.offers(in.session(), in.requirement(), in.profile(), in.gear(), base);
        var assessment = Optional.ofNullable(assessments.get(id));
        var timing = assembly.timing(in.session(), in.requirement(), assessment, staleOf(assessment));
        String request = text.strip();
        var context = new AgentContext(id, List.of("Change request:\n" + request),
            assembly.evidence(in.requirement(), in.profile(), offers, timing, base), List.of(), Optional.empty())
            .withResponseSchema(PlanChangeInterpretation.SCHEMA);
        var run = agent.runWithTools(PlanAssembly.REVISE, context, PlanChangeInterpretation.class, PLAN_TOOLS);
        if (!run.isSuccess()) return aiFailure(run);
        var output = run.value().orElseThrow(); var change = output.output();
        switch (Objects.toString(change.status(), "")) {
            case "clarify" -> {
                var questions = texts(change.questions());
                return Result.failureKey("plan.revise.clarify", questions.isEmpty() ? Objects.toString(change.summary(), "") : String.join(" ", questions));
            }
            case "conflict" -> {
                String reason = Objects.toString(change.conflict(), "").strip();
                return Result.failureKey("plan.revise.conflict", reason.isEmpty() ? Objects.toString(change.summary(), "").strip() : reason);
            }
            case "change" -> { }
            default -> { return Result.failureKey("plan.error.malformed"); }
        }
        var commands = assembly.commands(change, base.get(), offers, timing, in.profile());
        if (!commands.isSuccess()) return Result.failure(commands);
        var previewed = editor.preview(base.get(), commands.value().orElseThrow());
        if (!previewed.isSuccess()) return Result.failure(previewed);
        var draft = previewed.value().orElseThrow();
        boolean sameLoadout = draft.loadout().map(l -> l.id() + new java.util.TreeMap<>(l.cameraLensAssignments()).toString())
            .equals(base.get().loadout().map(l -> l.id() + new java.util.TreeMap<>(l.cameraLensAssignments()).toString()));
        var calculations = new ArrayList<com.photopilot.model.CalculationResult>(sameLoadout ? base.get().calculations()
            : draft.loadout().map(assembly::baseline).orElse(List.of()));
        for (var extra : assembly.calculations(Optional.empty(), output.tools())) calculations.add(extra);
        var limitations = new ArrayList<String>(texts(change.limitations()));
        var notes = new ArrayList<com.photopilot.i18n.LocalizedMessage>(offers.notes()); notes.addAll(timing.notes());
        notes.forEach(note -> limitations.add(com.photopilot.i18n.EnglishMessages.text(note.key(), note.arguments().toArray())));
        var provisional = draft.loadout().isEmpty() ? List.of(com.photopilot.i18n.EnglishMessages.text("plan.provisional.noLoadout")) : List.<String>of();
        String summary = Objects.toString(change.summary(), "").strip();
        draft = draft.withExplanation(base.get().summary(), (com.photopilot.i18n.EnglishMessages.text("plan.changeRequest", request) + " " + summary).strip(),
            limitations, calculations, provisional);
        var report = validator.assess(draft, in.requirement(), validation(in, timing, base));
        issue(draft, id);
        var changed = draft.differingSections(base.get()).stream().filter(ShootPlan.DECISIONS::contains).toList();
        return Result.success(new PlanPreview(id, draft, base, report, evidence(in, offers, notes, output), output.tools(), texts(change.questions()), request, changed));
    }

    /** The user rejected a preview: it can no longer be approved. Nothing was or is saved. */
    public void reject(UUID id, ShootPlan plan) {
        if (plan == null) return;
        synchronized (issued) { issued.remove(new Issued(plan)); }
    }

    /**
     * SD04 approval of the preview the user reviewed: reloads the session, rejects foreign or stale proposals (it must
     * be issued for this session as a new plan, or as the next revision of the latest saved one), revalidates it against
     * the current requirement, profile, inventory and evidence, re-runs validateChange for revisions, appends the
     * approved copy (no command is reapplied), applies PLAN_APPROVED only for the first approval of a Draft session and
     * saves plan and state atomically. Success is reported only after the save; a failure leaves storage unchanged.
     */
    public Result<ShootPlan> approve(UUID id, ShootPlan plan) {
        if (id == null) return Result.failureKey("error.session.id");
        if (plan == null) return Result.failureKey("plan.error.noDraft");
        if (plan.approved()) return Result.failureKey("plan.error.alreadyApproved");
        UUID owner; synchronized (issued) { owner = issued.get(new Issued(plan)); }
        if (!id.equals(owner)) return Result.failureKey("plan.error.foreign");
        var inputs = planningInputs(id);
        if (!inputs.isSuccess()) return Result.failure(inputs);
        var in = inputs.value().orElseThrow();
        var existing = latest(in.session().plans());
        if (plan.revision() == 1 ? existing.isPresent() || in.session().plans().stream().anyMatch(p -> p.id().equals(plan.id()))
                : existing.filter(p -> p.id().equals(plan.id()) && p.revision() == plan.revision() - 1).isEmpty())
            return Result.failureKey("plan.error.stale");
        var assessment = Optional.ofNullable(assessments.get(id));
        var timing = assembly.timing(in.session(), in.requirement(), assessment, staleOf(assessment));
        var report = validator.assess(plan, in.requirement(), validation(in, timing, plan.regeneration() ? Optional.empty() : existing));
        if (!report.valid()) return Result.failures(report.blocking());
        if (plan.revision() > 1 && !plan.regeneration()) {
            var change = validator.validateChange(existing.orElseThrow(), plan);
            if (!change.isSuccess()) return Result.failure(change);
        }
        var approved = plan.approvedCopy();
        if (!approved.isSuccess()) return Result.failure(approved);
        var candidate = in.session().appendApprovedPlan(approved.value().orElseThrow());
        if (!candidate.isSuccess()) return Result.failure(candidate);
        var next = candidate.value().orElseThrow();
        if (next.status().equals("DRAFT")) {
            var transitioned = next.apply(com.photopilot.state.SessionEvent.PLAN_APPROVED);
            if (!transitioned.isSuccess()) return Result.failure(transitioned);
            next = transitioned.value().orElseThrow();
        }
        var saved = sessions.save(next);
        if (!saved.isSuccess()) return Result.failure(saved);
        reject(id, plan);
        return saved.value().orElseThrow().plans().stream().filter(p -> p.id().equals(plan.id()) && p.revision() == plan.revision()).findFirst()
            .map(Result::success).orElseGet(() -> Result.failureKey("plan.error.notSaved"));
    }

    private record Planning(PhotographySession session, ShootRequirement requirement, EquipmentRequirementProfile profile, com.photopilot.model.GearInventory gear) { }
    /** UC10/UC11 preconditions: editable session, confirmed requirement, reviewed profile, readable inventory. */
    private Result<Planning> planningInputs(UUID id) {
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        if (!session.state().canEditPlan()) return Result.failureKey("plan.error.historical");
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) return Result.failureKey("plan.missing.requirement");
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        if (profile.isEmpty()) return Result.failureKey("plan.missing.profile");
        var gear = inventory.load();
        if (!gear.isSuccess()) return Result.failure(gear);
        return Result.success(new Planning(session, requirement.get(), profile.get(), gear.value().orElseThrow()));
    }
    private static PlanValidator.Context validation(Planning in, PlanAssembly.Timing timing, Optional<ShootPlan> base) {
        return new PlanValidator.Context(Optional.of(in.profile()), Optional.of(in.gear()), timing.environment(), timing.availability(), timing.zone(),
            Optional.of(timing.date()), base);
    }
    private java.util.Set<String> staleOf(Optional<EnvironmentAssessment> assessment) {
        return assessment.map(a -> environment.staleFields(a.context())).orElse(java.util.Set.of());
    }
    private static Result<ShootPlan> approvable(PlanPreview preview) {
        if (preview.approvable()) return Result.success(preview.plan());
        var reasons = new ArrayList<>(preview.validation().blocking());
        if (reasons.isEmpty()) preview.plan().provisionalReasons().forEach(reason -> reasons.add(com.photopilot.i18n.LocalizedMessage.of("plan.missing.provisional", reason)));
        return reasons.isEmpty() ? Result.failureKey("plan.error.notApprovable") : Result.failures(reasons);
    }
    private static <T> Result<T> aiFailure(Result<?> run) {
        return run.hasError("ai.error.malformedOutput") ? Result.failureKey("plan.error.malformed") : Result.failure(run);
    }
    private static PlanValidator.Report merge(PlanValidator.Report report, List<com.photopilot.i18n.LocalizedMessage> problems) {
        if (problems.isEmpty()) return report;
        var violations = new ArrayList<>(problems); violations.addAll(report.violations());
        return new PlanValidator.Report(violations, report.missing(), report.unverified(), report.limitations());
    }
    private static List<com.photopilot.i18n.LocalizedMessage> evidence(Planning in, PlanAssembly.Offers offers, List<com.photopilot.i18n.LocalizedMessage> notes,
            com.photopilot.agent.PhotoPilotAgent.Run<?> run) {
        var lines = new ArrayList<com.photopilot.i18n.LocalizedMessage>();
        lines.add(com.photopilot.i18n.LocalizedMessage.of("plan.evidence.requirement", in.requirement().subject()));
        lines.add(com.photopilot.i18n.LocalizedMessage.of("plan.evidence.profile", in.profile().needs().size()));
        lines.add(com.photopilot.i18n.LocalizedMessage.of("plan.evidence.offers", offers.offers().size()));
        lines.addAll(notes);
        lines.add(com.photopilot.i18n.LocalizedMessage.of("plan.evidence.history", run.historySessions()));
        long ok = run.tools().stream().filter(com.photopilot.agent.AgentContext.ToolExchange::succeeded).count();
        lines.add(com.photopilot.i18n.LocalizedMessage.of("plan.evidence.tools", run.tools().size(), ok));
        return lines;
    }
    private static List<String> texts(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty()).toList();
    }
    private void issue(ShootPlan plan, UUID session) {
        synchronized (issued) {
            issued.put(new Issued(plan), session);
            while (issued.size() > MAX_ISSUED) issued.remove(issued.keySet().iterator().next());
        }
    }
}
