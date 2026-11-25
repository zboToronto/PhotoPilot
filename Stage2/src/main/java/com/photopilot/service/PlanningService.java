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
import com.photopilot.progress.Progress;
import com.photopilot.repository.InventoryRepository;
import com.photopilot.repository.SessionRepository;
import com.photopilot.validation.EnvironmentEvidence;
import com.photopilot.validation.PlanValidator;

/**
 * Stage 1 UML element 6 (zero-based): {F03, F06-F10; workflow coordination}
 * F03 parse/clarify/confirm/retrieve, F06 analyzeNeeds/proposeNeeds/clarifyNeeds/manualNeeds/confirmProfile/profile
 * (SD02), F07 match/select/selection, F09 assessEnvironment and F10 generate/proposeChange/approve (SD04).
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
    /**
     * Latest successful F09 assessment per session in this process (never persisted), with the confirmed requirement it
     * was made under. F06 and F10 use it only through the shared {@link EnvironmentEvidence} check.
     */
    private record Assessed(EnvironmentAssessment assessment, EnvironmentEvidence.Basis basis, java.time.LocalDate sessionDate) { }
    private final Map<UUID, Assessed> assessments = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Object> assessmentRuns = new java.util.HashMap<>();
    /** AI-only retries in progress per session (at most one; a second request is refused, never queued). */
    private final Map<UUID, Object> interpretationRetries = new java.util.HashMap<>();

    /** Drop only this session's in-process evidence. Persisted checks and approved plans retain their history. */
    public synchronized void invalidateEnvironment(UUID id) {
        assessments.remove(id);
        assessmentRuns.remove(id);
    }

    private synchronized Object beginAssessment(UUID id) {
        Object token = new Object();
        assessmentRuns.put(id, token);
        assessments.remove(id);
        return token;
    }
    /** Previews issued by this service, keyed by the exact preview object, with their owning session. */
    private record Issued(ShootPlan plan) {
        @Override public boolean equals(Object other) { return other instanceof Issued that && that.plan == plan; }
        @Override public int hashCode() { return System.identityHashCode(plan); }
    }
    /**
     * What approval trusts about an issued plan (never persisted): the complete preview as issued, whose report holds the
     * assembly problems found while converting the model reply (removed from the plan, never repaired), a private copy of
     * the plan as validated (never handed out, so nothing can change it) and that copy's content. Unapproved drafts can
     * still be changed by PlanCommands, so the caller's object must still have this content, and only the copy is saved.
     */
    private record Registered(PlanPreview preview, ShootPlan reviewed, List<Object> content) {
        Registered { Objects.requireNonNull(preview, "preview"); Objects.requireNonNull(reviewed, "reviewed"); content = List.copyOf(content); }
    }
    private final Map<Issued, Registered> issued = new java.util.LinkedHashMap<>();

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
        Progress.stage("session.load");
        var session = editable(id);
        if (!session.isSuccess()) return Result.failure(session);
        return requirements.parse(text, new AgentContext(id, List.of(), "{}", List.of(), Optional.empty()));
    }

    /** F03 clarification: answers are resubmitted with the draft; the result is still an unconfirmed draft. */
    public Result<ShootRequirement> clarify(UUID id, ShootRequirement draft, String answers) {
        Progress.stage("session.load");
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
        Progress.validating("requirement.validate");
        var confirmed = requirements.confirm(edited);
        if (!confirmed.isSuccess()) return confirmed;
        Progress.stage("requirement.save");
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

    /** F06/UC06 Stage 1 entry: Gemini-proposed, unreviewed capability profile for the session. Nothing is saved. */
    public Result<EquipmentRequirementProfile> analyzeNeeds(UUID id) {
        var proposal = proposeNeeds(id);
        return proposal.isSuccess() ? Result.success(proposal.value().orElseThrow().profile()) : Result.failure(proposal);
    }

    /**
     * F06 analysis for review: the session's confirmed F03 requirement plus this run's F09 light/weather summary (if
     * any) go to Gemini; the reply is converted and checked deterministically into an unreviewed draft.
     */
    public Result<CapabilityProposal> proposeNeeds(UUID id) {
        Progress.stage("session.load");
        var session = needsSession(id);
        if (!session.isSuccess()) return Result.failure(session);
        var found = session.value().orElseThrow(); var requirement = confirmedRequirement(found);
        Progress.stage("needs.evidence");
        var environment = storedEvidence(found, requirement);
        return withEnvironment(requirements.analyzeProfile(requirement, needsEnvironment(environment)), environment);
    }

    /** F06 clarification: the current draft (with manual edits) and the answers are resubmitted; still unreviewed. */
    public Result<CapabilityProposal> clarifyNeeds(UUID id, CapabilityProposal current, String answers) {
        Progress.stage("session.load");
        var session = needsSession(id);
        if (!session.isSuccess()) return Result.failure(session);
        var found = session.value().orElseThrow(); var requirement = confirmedRequirement(found);
        Progress.stage("needs.evidence");
        var environment = storedEvidence(found, requirement);
        return withEnvironment(requirements.clarifyProfile(requirement, needsEnvironment(environment), current, answers), environment);
    }
    /** Why the F09 assessment was not used, or which of its groups were withheld, shown with the F06 proposal. */
    private static Result<CapabilityProposal> withEnvironment(Result<CapabilityProposal> proposal, EnvironmentEvidence environment) {
        if (!proposal.isSuccess()) return proposal;
        var notes = new ArrayList<>(environment.notes());
        if (environment.usable() && !environment.stale().isEmpty())
            notes.add(com.photopilot.i18n.LocalizedMessage.of("env.notice.stale", String.join(", ", new java.util.TreeSet<>(environment.stale()))));
        return notes.isEmpty() ? proposal : Result.success(proposal.value().orElseThrow().withEnvironmentNotes(notes));
    }

    /** Manual entry (also when Gemini is unavailable): the saved profile as a draft, or an empty one. No AI involved. */
    public Result<CapabilityProposal> manualNeeds(UUID id) {
        var session = needsSession(id);
        if (!session.isSuccess()) return Result.failure(session);
        var found = session.value().orElseThrow();
        var draft = found.profile().filter(EquipmentRequirementProfile::reviewed)
            .orElse(new EquipmentRequirementProfile(List.of(), false));
        return Result.success(CapabilityProposal.manual(draft, RequirementService.capabilityGaps(confirmedRequirement(found))));
    }

    private Result<PhotographySession> needsSession(UUID id) {
        if (id == null) return Result.failureKey("error.session.id");
        var found = sessions.find(id);
        if (!found.isSuccess()) return found;
        var session = found.value().orElseThrow();
        if (!session.state().canEditPlan()) return Result.failureKey("needs.error.historical");
        return session.requirements().filter(ShootRequirement::confirmed).isPresent() ? found : Result.failureKey("needs.error.requirement");
    }
    private static ShootRequirement confirmedRequirement(PhotographySession session) {
        return session.requirements().filter(ShootRequirement::confirmed).orElseThrow();
    }

    /**
     * F06 evidence from this process's F09 assessment, only after the shared check: light phases, the F09 windows whose
     * evidence is still fresh and the daily weather while it is fresh; stale groups are named, never sent as facts. No
     * place or coordinates. Empty when no assessment applies to the confirmed requirement.
     */
    private static String needsEnvironment(EnvironmentEvidence environment) {
        if (!environment.usable()) return "";
        var assessment = environment.assessment().orElseThrow();
        var json = new com.google.gson.JsonObject();
        assessment.request().ifPresent(r -> json.addProperty("locationSource", r.locationSource().name()));
        var windows = new com.google.gson.JsonArray();
        for (var window : assessment.windows()) {
            var item = new com.google.gson.JsonObject();
            item.addProperty("start", window.start().toLocalDateTime().toString()); item.addProperty("end", window.end().toLocalDateTime().toString());
            item.addProperty("light", window.light()); windows.add(item);
        }
        var phases = new com.google.gson.JsonArray();
        for (var phase : assessment.lightPhases()) {
            var item = new com.google.gson.JsonObject();
            item.addProperty("kind", phase.kind()); item.addProperty("start", phase.start().toLocalDateTime().toString());
            item.addProperty("end", phase.end().toLocalDateTime().toString()); phases.add(item);
        }
        if (!windows.isEmpty()) json.add("shootingWindows", windows);
        if (!phases.isEmpty()) json.add("lightPhases", phases);
        // Field-level freshness evaluated now: the filtered copy carries the daily summary only while it is fresh.
        if (!assessment.context().weather().isEmpty()) {
            var weather = new com.google.gson.JsonObject();
            assessment.context().weather().forEach(weather::addProperty);
            json.add("weather", weather);
        }
        if (json.isEmpty()) return "";
        if (!environment.stale().isEmpty()) {
            var omitted = new com.google.gson.JsonArray(); new java.util.TreeSet<>(environment.stale()).forEach(omitted::add);
            json.add("staleOmitted", omitted);
        }
        if (environment.status() == EnvironmentEvidence.Status.PARTIAL) json.addProperty("availabilityCoversShootingTime", "partly");
        return json.toString();
    }

    /** The shared F06/F10 check of {@code assessment} against the session's current confirmed requirement, evaluated now. */
    private EnvironmentEvidence environmentEvidence(PhotographySession session, ShootRequirement requirement, EnvironmentAssessment assessment) {
        if (assessment == null) return EnvironmentEvidence.none();
        var stored = assessments.get(session.id());
        // The recorded basis belongs to the stored assessment only; any other instance is checked without it.
        var basis = stored != null && stored.assessment() == assessment ? Optional.of(stored.basis()) : Optional.<EnvironmentEvidence.Basis>empty();
        return environment.evidence(assessment, requirement, PlanAssembly.shootingDate(session, requirement), basis);
    }
    private EnvironmentEvidence storedEvidence(PhotographySession session, ShootRequirement requirement) {
        var stored = assessments.get(session.id());
        return environmentEvidence(session, requirement, stored == null ? null : stored.assessment());
    }

    public Result<EquipmentRequirementProfile> confirmProfile(UUID id, EquipmentRequirementProfile edited) {
        var session = sessions.find(id);
        return session.isSuccess() ? confirmProfile(session.value().orElseThrow(), edited) : Result.failure(session);
    }

    /** Confirmation that never replaces an existing confirmed profile; see the three-argument form. */
    public Result<EquipmentRequirementProfile> confirmProfile(PhotographySession expected, EquipmentRequirementProfile edited) {
        return confirmProfile(expected, edited, false);
    }

    /**
     * F06 confirmation (AI-assisted or manual drafts alike): structural rules, consistency with the confirmed
     * requirement, explicit confirmation, then an atomic save against the reviewed session version (a concurrent
     * change is rejected by the revision check). An existing confirmed profile is replaced only when {@code replace}.
     * Success is reported only after the save succeeded.
     */
    public Result<EquipmentRequirementProfile> confirmProfile(PhotographySession expected, EquipmentRequirementProfile edited, boolean replace) {
        if (expected == null) return Result.failureKey("error.session.id");
        if (!expected.state().canEditPlan()) return Result.failureKey("needs.error.historical");
        var requirement = expected.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) return Result.failureKey("needs.error.requirement");
        // A version that is no longer the stored one is reported as changed before the profile is validated against its
        // (possibly outdated) requirement, so the caller can reload instead of being shown a conflict that no longer exists.
        var stored = sessions.find(expected.id());
        if (!stored.isSuccess()) return Result.failure(stored);
        if (!stored.value().orElseThrow().revision().equals(expected.revision())) return Result.failureKey("error.session.changed");
        if (expected.profile().filter(EquipmentRequirementProfile::reviewed).isPresent() && !replace) return Result.failureKey("needs.error.replaceRequired");
        Progress.validating("needs.validate");
        var structural = requirements.validateProfile(edited);
        if (!structural.isSuccess()) return structural;
        var consistent = edited.validate(requirement.get());
        if (!consistent.isSuccess()) return consistent;
        var confirmed = edited.confirm();
        if (!confirmed.isSuccess()) return confirmed;
        var attached = expected.attachProfile(confirmed.value().orElseThrow());
        if (!attached.isSuccess()) return Result.failure(attached);
        Progress.stage("needs.save");
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

    /**
     * The confirmed profile only while it is still consistent with the session's current confirmed requirement
     * (F03 may have been re-confirmed since). F07 matching and selection use nothing else.
     */
    public static Result<EquipmentRequirementProfile> usableProfile(PhotographySession session) {
        var profile = session.profile().filter(EquipmentRequirementProfile::reviewed);
        if (profile.isEmpty()) return Result.failureKey("needs.error.none");
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) return Result.failureKey("needs.error.requirement");
        var checked = profile.get().validate(requirement.get());
        if (checked.isSuccess()) return checked;
        var problems = new ArrayList<com.photopilot.i18n.LocalizedMessage>();
        problems.add(com.photopilot.i18n.LocalizedMessage.of("needs.error.stale")); problems.addAll(checked.messages());
        return Result.failures(problems);
    }

    public Result<List<Loadout>> matchGear(UUID id) {
        var report = matchGear(id, matching.defaultStrategy());
        return report.isSuccess() ? Result.success(report.value().orElseThrow().recommendations().stream()
            .map(com.photopilot.model.GearMatchReport.Candidate::loadout).toList()) : Result.failure(report);
    }

    /** F07/UC07: confirmed profile + current F05 inventory -> UC14-verified, ranked candidates. Nothing is saved. */
    public Result<com.photopilot.model.GearMatchReport> matchGear(UUID id, String strategy) {
        if (id == null) return Result.failureKey("error.session.id");
        Progress.stage("session.load");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        if (!session.state().canEditPlan()) return Result.failureKey("match.error.historical");
        var profile = usableProfile(session);
        if (!profile.isSuccess()) return Result.failure(profile);
        Progress.stage("match.inventory");
        var gear = inventory.load();
        if (!gear.isSuccess()) return Result.failure(gear);
        return matching.analyze(profile.value().orElseThrow(), confirmedOrNull(session), gear.value().orElseThrow(), strategy, shootContext(session));
    }
    private static ShootRequirement confirmedOrNull(PhotographySession session) {
        return session.requirements().filter(ShootRequirement::confirmed).orElse(null);
    }

    public Result<Loadout> selectLoadout(UUID id, Loadout selected) {
        var session = sessions.find(id);
        return session.isSuccess() ? selectLoadout(session.value().orElseThrow(), selected, false) : Result.failure(session);
    }

    /**
     * Explicit F07 selection. Revalidates everything against current data before the atomic save: every item must be
     * owned, unchanged and available in the carried quantity; the candidate must be rebuilt from the current inventory
     * inside the same candidate space F07 recommends from (requirement + profile scope, not a narrower default; unknown
     * or stale IDs are rejected, larger combinations are reported as outside the search range); UC14 must verify it; and
     * needs are re-evaluated against the current profile. An existing selection is replaced only when {@code replace}
     * is true. Success is reported only after the save succeeded.
     */
    public Result<Loadout> selectLoadout(PhotographySession expected, Loadout selected, boolean replace) {
        if (expected == null) return Result.failureKey("error.session.id");
        if (selected == null) return Result.failureKey("match.error.noSelection");
        if (!expected.state().canEditPlan()) return Result.failureKey("match.error.historical");
        if (expected.selectedLoadout().isPresent() && !replace) return Result.failureKey("match.error.alreadySelected");
        var usable = usableProfile(expected);
        if (!usable.isSuccess()) return Result.failure(usable);
        var profile = usable.value();
        Progress.validating("match.recheck");
        var loaded = inventory.load();
        if (!loaded.isSuccess()) return Result.failure(loaded);
        var gear = loaded.value().orElseThrow();
        var unavailable = matching.validator().availability(selected, gear);
        if (!unavailable.isEmpty()) return Result.failures(unavailable);
        if (!GearMatchingService.candidateId(selected.gear()).equals(selected.id())) return Result.failureKey("match.error.staleCandidate");
        var scope = GearMatchingService.scope(profile.get(), confirmedOrNull(expected));
        var rebuilt = matching.reconstruct(selected, gear, scope);
        if (!rebuilt.isSuccess()) return Result.failure(rebuilt);
        var fresh = rebuilt.value();
        var check = matching.validator().assess(fresh.get(), scope.mounting());
        if (!check.usable()) {
            var problems = new java.util.ArrayList<>(check.violations()); problems.addAll(check.unverified());
            return Result.failures(problems);
        }
        var evaluated = matching.evaluate(fresh.get(), profile.get(), scope.mounting()).loadout();
        var attached = expected.attachSelectedLoadout(evaluated);
        if (!attached.isSuccess()) return Result.failure(attached);
        Progress.stage("match.save");
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
        var mounting = com.photopilot.validation.CompatibilityValidator.Mounting.of(confirmedOrNull(session));
        var check = selectionCheck(matching.validator(), saved.get(), confirmedOrNull(session), gear.value().orElseThrow());
        var evaluation = matching.evaluate(saved.get(), session.profile().filter(EquipmentRequirementProfile::reviewed).orElse(null), mounting);
        return Result.success(new com.photopilot.model.GearMatchReport.SelectionStatus(saved.get(), evaluation, check.compatibility(), check.availability()));
    }

    /** The read-only recheck of a saved selection: UC14 problems (violations, then unverified items) and F05 availability now. */
    public record SelectionCheck(List<com.photopilot.i18n.LocalizedMessage> compatibility, List<com.photopilot.i18n.LocalizedMessage> availability) {
        public SelectionCheck { compatibility = List.copyOf(compatibility); availability = List.copyOf(availability); }
        public boolean current() { return compatibility.isEmpty() && availability.isEmpty(); }
    }
    /** Shared by {@link #selection(UUID)} and the session workflow progress, so both judge a saved selection alike. */
    static SelectionCheck selectionCheck(com.photopilot.validation.CompatibilityValidator validator, Loadout saved, ShootRequirement requirementOrNull,
            com.photopilot.model.GearInventory gear) {
        var check = validator.assess(saved, com.photopilot.validation.CompatibilityValidator.Mounting.of(requirementOrNull));
        var compatibility = new ArrayList<>(check.violations()); compatibility.addAll(check.unverified());
        return new SelectionCheck(compatibility, validator.availability(saved, gear));
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
        if (requirement.isEmpty()) return Result.failureKey("env.error.needsInput", "confirmed requirement or reviewed environment inputs");
        return remember(session.value().orElseThrow(), beginAssessment(id), environment.assess(requirement.get()));
    }
    /** Keeps a successful assessment with the confirmed requirement it was made under (F03 is never changed here). */
    private synchronized Result<EnvironmentAssessment> remember(PhotographySession session, Object token, Result<EnvironmentAssessment> assessed) {
        if (!assessmentRuns.remove(session.id(), token) || Progress.cancelled()) return Result.failureKey("env.resolve.changed");
        var basis = new EnvironmentEvidence.Basis(session.requirements().filter(ShootRequirement::confirmed));
        assessed.value().ifPresent(value -> assessments.put(session.id(), new Assessed(value, basis, session.date())));
        return assessed;
    }

    /**
     * F09/UC09 with the location, date and availability the user reviewed. Reads the session and its confirmed F03
     * requirement (never modified); returns a validated assessment for display or later F10 use. Nothing is saved.
     */
    public Result<EnvironmentAssessment> assessEnvironment(UUID id, com.photopilot.model.EnvironmentRequest request) {
        return assessEnvironment(id, request, null);
    }

    /**
     * {@link #assessEnvironment(UUID, com.photopilot.model.EnvironmentRequest)} that passes the factual snapshot to
     * {@code onFacts} (on the calling thread) while the Gemini interpretation is still pending; only the returned
     * assessment is remembered for the session.
     */
    public Result<EnvironmentAssessment> assessEnvironment(UUID id, com.photopilot.model.EnvironmentRequest request,
            java.util.function.Consumer<EnvironmentAssessment> onFacts) {
        Progress.stage("session.load");
        var found = environmentSession(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        Object token = beginAssessment(id);
        boolean otherDate = request != null && request.date() != null && !session.date().equals(request.date());
        java.util.function.UnaryOperator<EnvironmentAssessment> sessionNotice = value -> otherDate ? value.withNotices(List.of(com.photopilot.i18n.LocalizedMessage.of(
            "env.notice.sessionDate", session.date().toString(), request.date().toString()))) : value;
        var assessed = environment.assess(session.requirements().filter(ShootRequirement::confirmed).orElse(null), request,
            onFacts == null ? null : facts -> onFacts.accept(sessionNotice.apply(facts)));
        if (!assessed.isSuccess()) return remember(session, token, assessed);
        return remember(session, token, Result.success(sessionNotice.apply(assessed.value().orElseThrow())));
    }

    /** The deterministic dashboard analysis of an assessment (no provider or Gemini request); {@code aiPending} for a factual snapshot. */
    public com.photopilot.model.EnvironmentAnalysis analyzeEnvironment(EnvironmentAssessment assessment, boolean aiPending) {
        return EnvironmentAnalyzer.analyze(assessment, aiPending);
    }

    /**
     * Why an AI-only retry of {@code shown} is not possible now, or empty when it is. Deterministic and local: the shown
     * assessment must still be the session's latest one (any edit of place, coordinates, date, times or zone, or a new
     * assessment, replaces or clears it), the session's date and confirmed requirement must be those it was made under,
     * its evidence must still be fresh, its availability must not have ended, and its interpretation must have failed.
     */
    public synchronized Optional<com.photopilot.i18n.LocalizedMessage> environmentRetryRefusal(UUID id, EnvironmentAssessment shown) {
        var quick = environmentRetryPrecheck(id, shown);
        if (quick.isPresent()) return quick;
        var stored = assessments.get(id);
        var found = sessions.find(id);
        if (!found.isSuccess()) return Optional.of(found.messages().isEmpty() ? com.photopilot.i18n.LocalizedMessage.of("error.session.id") : found.messages().getFirst());
        var session = found.value().orElseThrow();
        if (!session.state().canEditPlan()) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.error.historical"));
        if (!session.date().equals(stored.sessionDate())) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.sessionChanged"));
        if (!sameRequirement(stored.basis().requirement(), session.requirements().filter(ShootRequirement::confirmed)))
            return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.requirementChanged"));
        return Optional.empty();
    }

    /**
     * The in-memory part of {@link #environmentRetryRefusal} (no storage read), cheap enough for an interface to decide
     * whether to offer the retry: current assessment, failed interpretation, no retry running, fresh evidence and an
     * availability that has not ended. The session date and requirement are checked again when the retry runs.
     */
    public synchronized Optional<com.photopilot.i18n.LocalizedMessage> environmentRetryPrecheck(UUID id, EnvironmentAssessment shown) {
        if (id == null) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("error.session.id"));
        var stored = assessments.get(id);
        if (stored == null || shown == null) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.none"));
        if (stored.assessment() != shown || assessmentRuns.containsKey(id)) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.replaced"));
        if (interpretationRetries.containsKey(id)) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.busy"));
        var ai = EnvironmentAnalyzer.ai(shown, false).status();
        if (ai == com.photopilot.model.EnvironmentAnalysis.AiStatus.SUCCESS) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.notNeeded"));
        if (!ai.failed()) return Optional.of(com.photopilot.i18n.LocalizedMessage.of("env.retry.skipped"));
        return environment.reinterpretRefusal(shown);
    }

    /**
     * Interpretation-only retry for the session's current assessment {@code shown}: reuses its confirmed location and
     * provenance, retrieved weather and sun data, computed phases and resolved availability, and asks only Gemini again
     * (never the place lookup, weather or sun providers). Refused under the conditions of
     * {@link #environmentRetryRefusal}; a second retry while one runs is refused. The result replaces the session's
     * assessment only if nothing replaced or cleared it meanwhile; otherwise it is discarded and the refusal returned.
     */
    public Result<EnvironmentAssessment> retryEnvironmentInterpretation(UUID id, EnvironmentAssessment shown) {
        Progress.stage("session.load");
        Object token = new Object();
        Optional<ShootRequirement> requirement;
        synchronized (this) {
            var refusal = environmentRetryRefusal(id, shown);
            if (refusal.isPresent()) return Result.failure(refusal.get());
            interpretationRetries.put(id, token);
            requirement = assessments.get(id).basis().requirement();
        }
        try {
            Progress.validating("env.retryCheck");
            var result = environment.reinterpret(requirement.orElse(null), shown);
            synchronized (this) {
                var current = assessments.get(id);
                if (current == null || current.assessment() != shown || assessmentRuns.containsKey(id) || Progress.cancelled())
                    return Result.failureKey("env.retry.replaced");
                result.value().ifPresent(value -> assessments.put(id, new Assessed(value, current.basis(), current.sessionDate())));
                return result;
            }
        } finally {
            synchronized (this) { interpretationRetries.remove(id, token); }
        }
    }

    /** The confirmed requirement fields an interpretation depends on (subject, goals, timing, duration, place, date). */
    private static boolean sameRequirement(Optional<ShootRequirement> then, Optional<ShootRequirement> now) {
        if (then.isEmpty() || now.isEmpty()) return then.isEmpty() && now.isEmpty();
        var a = then.get(); var b = now.get();
        return Objects.equals(a.subject(), b.subject()) && Objects.equals(a.goals(), b.goals()) && Objects.equals(a.timeWindow(), b.timeWindow())
            && Objects.equals(a.duration(), b.duration()) && Objects.equals(a.date(), b.date())
            && Objects.equals(a.time().map(java.time.ZonedDateTime::toLocalDateTime), b.time().map(java.time.ZonedDateTime::toLocalDateTime))
            && Objects.equals(a.priorities(), b.priorities()) && Objects.equals(a.fixedConstraints(), b.fixedConstraints())
            && Objects.equals(a.location().map(com.photopilot.model.GeoLocation::label), b.location().map(com.photopilot.model.GeoLocation::label))
            && Objects.equals(a.location().flatMap(com.photopilot.model.GeoLocation::latitude), b.location().flatMap(com.photopilot.model.GeoLocation::latitude))
            && Objects.equals(a.location().flatMap(com.photopilot.model.GeoLocation::longitude), b.location().flatMap(com.photopilot.model.GeoLocation::longitude));
    }

    /**
     * F09 -> workflow milestone 04: adds the record of this process's latest successful assessment of the session
     * ({@link EnvironmentCheck}: identifying metadata only) to the session the user reviewed it for. Refused when there is
     * no assessment, when the session is historical, when the assessment was made for another day than the shooting date,
     * or when it does not apply to the current confirmed requirement (the check F06 and F10 use). An earlier record is
     * replaced. Atomic save against the reviewed session version (a concurrent change is rejected by the revision
     * check); success is reported only after the save. F06/F10 keep using the in-process assessment itself.
     * {@code shown} must be the assessment the user reviewed, which is still the latest one for the session.
     */
    public Result<PhotographySession> recordEnvironment(PhotographySession expected, EnvironmentAssessment shown) {
        if (expected == null) return Result.failureKey("error.session.id");
        if (!expected.state().canEditPlan()) return Result.failureKey("env.error.historical");
        var assessed = assessments.get(expected.id());
        if (assessed == null || shown == null) return Result.failureKey("env.record.error.none");
        // Only the assessment the user reviewed is recorded; a later run for the session replaced it.
        if (assessed.assessment() != shown) return Result.failureKey("env.record.error.replaced");
        Progress.validating("env.recordCheck");
        var assessment = assessed.assessment();
        var request = assessment.request();
        var availability = assessment.availability();
        if (request.isEmpty() || availability.isEmpty()) return Result.failureKey("env.record.error.incomplete");
        var requirement = expected.requirements().filter(ShootRequirement::confirmed);
        var shootingDate = requirement.map(r -> PlanAssembly.shootingDate(expected, r)).orElse(expected.date());
        if (!request.get().date().equals(shootingDate))
            return Result.failureKey("env.record.error.date", request.get().date().toString(), shootingDate.toString());
        if (requirement.isPresent()) {
            var evidence = environmentEvidence(expected, requirement.get(), assessment);
            if (!evidence.usable()) {
                var reasons = new ArrayList<com.photopilot.i18n.LocalizedMessage>();
                reasons.add(com.photopilot.i18n.LocalizedMessage.of("env.record.error.notApplicable")); reasons.addAll(evidence.notes());
                return Result.failures(reasons);
            }
        }
        // The record keeps the basis the shared check used (the requirement confirmed when the assessment was made), so a
        // later check of the record decides exactly as EnvironmentEvidence.check does for the assessment itself.
        var basis = assessed.basis().requirement();
        var check = new com.photopilot.model.EnvironmentCheck(assessment.assessedAt(), request.get().date(), request.get().place(),
            availability.get().start(), availability.get().end(), assessment.windows().size(),
            basis.flatMap(ShootRequirement::location), basis.map(ShootRequirement::timeWindow), request.get().accepted().map(com.photopilot.model.PlaceCandidate::source));
        var attached = expected.attachEnvironmentCheck(check);
        if (!attached.isSuccess()) return attached;
        Progress.stage("env.record");
        return sessions.save(attached.value().orElseThrow());
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

    /** Tools the F10 agent may call: deterministic F08 calculations. EnvironmentTool needs coordinates, which never go to Gemini, so it is not registered. */
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
        // The same rule as F07 (usableProfile): a profile confirmed for an earlier requirement that no longer fits is kept but not used.
        else if (requirement.isPresent()) { var usable = usableProfile(session); if (!usable.isSuccess()) blockers.addAll(usable.messages()); }
        var assessment = environmentFor(id);
        int offered = 0;
        if (requirement.isPresent() && profile.isPresent()) {
            var gear = inventory.load();
            if (!gear.isSuccess()) notes.addAll(gear.messages());
            else {
                var offers = assembly.offers(session, requirement.get(), profile.get(), gear.value().orElseThrow(), latest(session.plans()));
                offered = offers.offers().size(); notes.addAll(offers.notes());
            }
            notes.addAll(assembly.timing(session, requirement.get(), storedEvidence(session, requirement.get())).notes());
        }
        return Result.success(new PlanInputs(session, requirement, profile, offered, assessment, blockers, notes, session.plans()));
    }

    /**
     * Latest F09 assessment of this session made through this service in this process (never persisted), as made.
     * Whether F06/F10 may use it is decided again at each use against the current confirmed requirement.
     */
    public Optional<EnvironmentAssessment> environmentFor(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(assessments.get(id)).map(Assessed::assessment);
    }

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
        return generatePreview(id, environmentFor(id).orElse(null));
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
        Progress.stage("plan.offers");
        var offers = assembly.offers(in.session(), in.requirement(), in.profile(), in.gear(), existing);
        Progress.stage("plan.environment");
        var timing = assembly.timing(in.session(), in.requirement(), environmentEvidence(in.session(), in.requirement(), assessment));
        var context = new AgentContext(id, List.of("Generate the shoot plan for this photography session."),
            assembly.evidence(in.requirement(), in.profile(), offers, timing, Optional.empty()), List.of(), Optional.empty())
            .withResponseSchema(PlanGeneration.SCHEMA);
        var run = agent.runWithTools(PlanAssembly.GENERATE, context, PlanGeneration.class, PLAN_TOOLS);
        if (!run.isSuccess()) return aiFailure(run);
        var output = run.value().orElseThrow();
        Progress.validating("plan.validate");
        var built = assembly.plan(output.output(), offers, timing, in.session(), in.requirement(), in.profile(),
            existing.map(ShootPlan::id).orElseGet(UUID::randomUUID), existing.map(p -> p.revision() + 1).orElse(1), output.tools());
        var plan = built.plan();
        if (existing.isPresent()) plan = plan.asRegeneration().withExplanation(plan.summary(), com.photopilot.i18n.EnglishMessages.text("plan.regenerated"),
            plan.limitations(), plan.calculations(), plan.provisionalReasons());
        var report = merge(validator.assess(plan, in.requirement(), validation(in, timing, Optional.empty())), built.problems());
        Progress.stage("plan.ready");
        var evidence = evidence(in, offers, built.notes(), output);
        var changed = existing.map(plan::differingSections).orElse(List.of()).stream().filter(ShootPlan.DECISIONS::contains).toList();
        return Result.success(issue(new PlanPreview(id, plan, existing, report, evidence, output.tools(), texts(output.output().questions()), "", changed)));
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
        Progress.stage("plan.offers");
        var offers = assembly.offers(in.session(), in.requirement(), in.profile(), in.gear(), base);
        Progress.stage("plan.environment");
        var timing = assembly.timing(in.session(), in.requirement(), storedEvidence(in.session(), in.requirement()));
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
        Progress.stage("plan.commands");
        var commands = assembly.commands(change, base.get(), offers, timing, in.profile());
        if (!commands.isSuccess()) return Result.failure(commands);
        var planned = new ArrayList<com.photopilot.command.PlanCommand>(commands.value().orElseThrow());
        // A re-confirmed date or place does not change the fixed constraints (so it is revised, not regenerated); the
        // revision then records the current shoot's facts instead of the base's, which windows are also checked against.
        if (!sameShoot(base.get(), in.session(), in.requirement()))
            planned.add(new com.photopilot.command.ReviseSessionInfoCommand(PlanAssembly.sessionInfo(in.session(), in.requirement(), timing)));
        var previewed = editor.preview(base.get(), planned);
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
        Progress.validating("plan.validate");
        var report = validator.assess(draft, in.requirement(), validation(in, timing, base));
        // The change summary may be long because it echoes the request; the model's own part keeps MAX_SUMMARY.
        if (summary.length() > PlanValidator.MAX_SUMMARY)
            report = merge(report, List.of(com.photopilot.i18n.LocalizedMessage.of("plan.violation.text", com.photopilot.i18n.LocalizedMessage.of("plan.section.summary"))));
        var changed = draft.differingSections(base.get()).stream().filter(ShootPlan.DECISIONS::contains).toList();
        Progress.stage("plan.ready");
        return Result.success(issue(new PlanPreview(id, draft, base, report, evidence(in, offers, notes, output), output.tools(), texts(change.questions()), request, changed)));
    }

    /** The user rejected a preview: it can no longer be approved. Nothing was or is saved. */
    public void reject(UUID id, ShootPlan plan) {
        if (plan == null) return;
        synchronized (issued) { issued.remove(new Issued(plan)); }
    }

    /**
     * SD04 approval of the preview the user reviewed: it must be the exact plan object this service issued for this
     * session, with its content unchanged since then, and that issued preview must itself have been approvable: its
     * original report (assembly problems included) is authoritative, whatever the caller checked or shows. Then it reloads
     * the session, rejects stale proposals (a new plan, or the next revision of the latest saved one), revalidates against
     * the current requirement, profile, inventory and evidence, re-runs validateChange for revisions, appends the approved
     * copy (no command is reapplied), applies PLAN_APPROVED only for the first approval of a Draft session and saves plan
     * and state atomically. Success is reported only after the save; a failure leaves storage unchanged.
     */
    public Result<ShootPlan> approve(UUID id, ShootPlan plan) {
        if (id == null) return Result.failureKey("error.session.id");
        if (plan == null) return Result.failureKey("plan.error.noDraft");
        if (plan.approved()) return Result.failureKey("plan.error.alreadyApproved");
        Registered registered; synchronized (issued) { registered = issued.get(new Issued(plan)); }
        // Unknown, rejected, saved, evicted or other-session previews (and every preview after a restart) fail closed.
        if (registered == null || !id.equals(registered.preview().sessionId()) || registered.preview().plan() != plan) return Result.failureKey("plan.error.foreign");
        if (!registered.content().equals(content(plan))) return Result.failureKey("plan.error.modified");
        var eligible = approvable(registered.preview());
        if (!eligible.isSuccess()) return Result.failure(eligible);
        // From here on only the service's own copy is checked and saved, never the caller's (still editable) object.
        var reviewed = registered.reviewed();
        var inputs = planningInputs(id);
        if (!inputs.isSuccess()) return Result.failure(inputs);
        var in = inputs.value().orElseThrow();
        var existing = latest(in.session().plans());
        if (reviewed.revision() == 1 ? existing.isPresent() || in.session().plans().stream().anyMatch(p -> p.id().equals(reviewed.id()))
                : existing.filter(p -> p.id().equals(reviewed.id()) && p.revision() == reviewed.revision() - 1).isEmpty())
            return Result.failureKey("plan.error.stale");
        // Date and place are not fixed constraints; a plan prepared for another shoot date or place is not approved.
        if (!sameShoot(reviewed, in.session(), in.requirement())) return Result.failureKey("plan.violation.constraintsChanged");
        var timing = assembly.timing(in.session(), in.requirement(), storedEvidence(in.session(), in.requirement()));
        Progress.validating("plan.validate");
        var report = validator.assess(reviewed, in.requirement(), validation(in, timing, reviewed.regeneration() ? Optional.empty() : existing));
        if (!report.valid()) return Result.failures(report.blocking());
        if (reviewed.revision() > 1 && !reviewed.regeneration()) {
            var change = validator.validateChange(existing.orElseThrow(), reviewed);
            if (!change.isSuccess()) return Result.failure(change);
        }
        var approved = reviewed.approvedCopy();
        if (!approved.isSuccess()) return Result.failure(approved);
        var candidate = in.session().appendApprovedPlan(approved.value().orElseThrow());
        if (!candidate.isSuccess()) return Result.failure(candidate);
        var next = candidate.value().orElseThrow();
        if (next.status().equals("DRAFT")) {
            var transitioned = next.apply(com.photopilot.state.SessionEvent.PLAN_APPROVED);
            if (!transitioned.isSuccess()) return Result.failure(transitioned);
            next = transitioned.value().orElseThrow();
        }
        // The only path that grows the plan history; a plain save refuses any plan change.
        Progress.stage("plan.save");
        var saved = sessions.append(next);
        if (!saved.isSuccess()) return Result.failure(saved);
        reject(id, plan);
        return saved.value().orElseThrow().plans().stream().filter(p -> p.id().equals(reviewed.id()) && p.revision() == reviewed.revision()).findFirst()
            .map(Result::success).orElseGet(() -> Result.failureKey("plan.error.notSaved"));
    }

    /**
     * Whether a plan records the current shoot: the shooting date, and the confirmed place when the requirement names one
     * (labels compared as EnvironmentEvidence does). This is the rule PlanValidator uses to carry a base plan's windows;
     * facts that depend on in-memory F09 evidence (a looked-up place, its availability) are deliberately not compared.
     */
    static boolean sameShoot(ShootPlan plan, PhotographySession session, ShootRequirement requirement) {
        var info = plan.sessionInfo();
        if (!PlanAssembly.shootingDate(session, requirement).toString().equals(info.get("date"))) return false;
        return requirement.location().map(place -> info.containsKey("location") && EnvironmentEvidence.sameLabel(place.label(), info.get("location"))).orElse(true);
    }
    private record Planning(PhotographySession session, ShootRequirement requirement, EquipmentRequirementProfile profile, com.photopilot.model.GearInventory gear) { }
    /**
     * UC10/UC11 preconditions: editable session, confirmed requirement, a reviewed profile that still fits that requirement
     * (F07's {@link #usableProfile} rule; Stage2OptRe2 Review2 #03), readable inventory. Used by generate, revise and approve.
     */
    private Result<Planning> planningInputs(UUID id) {
        Progress.stage("plan.inputs");
        var found = sessions.find(id);
        if (!found.isSuccess()) return Result.failure(found);
        var session = found.value().orElseThrow();
        if (!session.state().canEditPlan()) return Result.failureKey("plan.error.historical");
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) return Result.failureKey("plan.missing.requirement");
        if (session.profile().filter(EquipmentRequirementProfile::reviewed).isEmpty()) return Result.failureKey("plan.missing.profile");
        var profile = usableProfile(session);
        if (!profile.isSuccess()) return Result.failure(profile);
        var gear = inventory.load();
        if (!gear.isSuccess()) return Result.failure(gear);
        return Result.success(new Planning(session, requirement.get(), profile.value().orElseThrow(), gear.value().orElseThrow()));
    }
    private static PlanValidator.Context validation(Planning in, PlanAssembly.Timing timing, Optional<ShootPlan> base) {
        return new PlanValidator.Context(Optional.of(in.profile()), Optional.of(in.gear()), timing.environment(), timing.availability(), timing.zone(),
            Optional.of(timing.date()), base);
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
    /** Registers the complete preview (its report, a private copy of its plan and that content) for a later approval. */
    private PlanPreview issue(PlanPreview preview) {
        var p = preview.plan();
        var reviewed = p.withExplanation(p.summary(), p.changeSummary(), p.limitations(), p.calculations(), p.provisionalReasons());
        synchronized (issued) {
            issued.put(new Issued(p), new Registered(preview, reviewed, content(reviewed)));
            while (issued.size() > MAX_ISSUED) issued.remove(issued.keySet().iterator().next());
        }
        return preview;
    }
    /**
     * Comparable content of a plan: every section's value plus the loadout and windows themselves (sections() summarises
     * them) and the draft's revision record. A PlanCommand applied to an issued draft replaces these values.
     */
    private static List<Object> content(ShootPlan plan) {
        return List.of(plan.sections(), plan.loadout(), plan.windows(), plan.provisionalReasons(), plan.revisedSections(), plan.regeneration(), plan.changeSummary());
    }
}
