package com.photopilot.application;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.model.ActivitySuggestion;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationResult;
import com.photopilot.model.DiscoveryContext;
import com.photopilot.model.EnvironmentAssessment;
import com.photopilot.model.Equipment;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.GearImportDraft;
import com.photopilot.model.Loadout;
import com.photopilot.model.PhotographySession;
import com.photopilot.model.PostShootReview;
import com.photopilot.model.ShootRequirement;
import com.photopilot.service.DiscoveryService;
import com.photopilot.config.LLMConfiguration;
import com.photopilot.service.InventoryService;
import com.photopilot.service.LLMDiagnosticService;
import com.photopilot.service.MiniAiService;
import com.photopilot.service.PlanningService;
import com.photopilot.service.ReviewService;
import com.photopilot.service.SessionService;
import com.photopilot.service.ShutterCountService;
import com.photopilot.service.CameraModelCatalog;
import com.photopilot.integration.ExifToolException;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 3 (zero-based): {Facade}
 * Shared GUI/CLI entry point to the F01-F11 application services; it holds no business rules of its own.
 */
public class PhotoPilotFacade {
    // Fixed at construction: one Facade always delegates to the same profile-scoped services.
    private final SessionService sessions;
    private final InventoryService inventory;
    private final DiscoveryService discovery;
    private final PlanningService planning;
    private final ReviewService review;
    private final TechnicalCalculator calculations;
    // Read-only F06 inventory report over the injected InventoryService; it holds no state of its own.
    private final com.photopilot.service.GearCapabilityService capabilities;
    private final LLMDiagnosticService aiDiagnostics;
    // Home Mini AI over the same model port; stateless, reads F05 inventory only for the gear prompt.
    private final MiniAiService miniAi;
    // Gear Inventory shutter count checker: local, read-only metadata through the bundled ExifTool.
    private final ShutterCountService shutterCount;
    // Session workflow progress (milestones 01-07) from persisted records; read-only, shared by GUI and CLI.
    private final com.photopilot.service.SessionWorkflowService workflow;
    // Announcements of this profile's successful session and inventory writes (Observer subject).
    private final com.photopilot.repository.StoreChanges changes;

    public PhotoPilotFacade(SessionService sessions, InventoryService inventory, DiscoveryService discovery, PlanningService planning, ReviewService review, TechnicalCalculator calculations) {
        this(sessions, inventory, discovery, planning, review, calculations, new LLMDiagnosticService(
            context -> Result.failureKey("error.notImplemented", "LLMClient"), LLMConfiguration.unconfigured(), "UNCONFIGURED"));
    }

    public PhotoPilotFacade(SessionService sessions, InventoryService inventory, DiscoveryService discovery, PlanningService planning, ReviewService review, TechnicalCalculator calculations, LLMDiagnosticService aiDiagnostics) {
        this(sessions, inventory, discovery, planning, review, calculations, aiDiagnostics, new MiniAiService(
            context -> Result.failureKey("error.notImplemented", "LLMClient"), inventory, java.time.Clock.systemDefaultZone()));
    }

    public PhotoPilotFacade(SessionService sessions, InventoryService inventory, DiscoveryService discovery, PlanningService planning, ReviewService review, TechnicalCalculator calculations, LLMDiagnosticService aiDiagnostics, MiniAiService miniAi) {
        // Without a configured reader the checker reports ExifTool as unavailable; the model catalog still works.
        this(sessions, inventory, discovery, planning, review, calculations, aiDiagnostics, miniAi, new ShutterCountService(
            file -> { throw new ExifToolException(ExifToolException.Kind.UNAVAILABLE, "No metadata reader configured"); }, CameraModelCatalog.bundled()));
    }

    public PhotoPilotFacade(SessionService sessions, InventoryService inventory, DiscoveryService discovery, PlanningService planning, ReviewService review, TechnicalCalculator calculations, LLMDiagnosticService aiDiagnostics, MiniAiService miniAi, ShutterCountService shutterCount) {
        // Without observed stores nothing is announced; workflow progress is still read on demand.
        this(sessions, inventory, discovery, planning, review, calculations, aiDiagnostics, miniAi, shutterCount, new com.photopilot.repository.StoreChanges());
    }

    public PhotoPilotFacade(SessionService sessions, InventoryService inventory, DiscoveryService discovery, PlanningService planning, ReviewService review, TechnicalCalculator calculations, LLMDiagnosticService aiDiagnostics, MiniAiService miniAi, ShutterCountService shutterCount, com.photopilot.repository.StoreChanges changes) {
        this.changes = Objects.requireNonNull(changes, "changes");
        this.workflow = new com.photopilot.service.SessionWorkflowService(Objects.requireNonNull(sessions, "sessions"),
            Objects.requireNonNull(inventory, "inventory"), java.time.Clock.systemDefaultZone());
        this.shutterCount = Objects.requireNonNull(shutterCount, "shutterCount");
        this.aiDiagnostics = Objects.requireNonNull(aiDiagnostics, "aiDiagnostics");
        this.miniAi = Objects.requireNonNull(miniAi, "miniAi");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.capabilities = new com.photopilot.service.GearCapabilityService(inventory);
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.planning = Objects.requireNonNull(planning, "planning");
        this.review = Objects.requireNonNull(review, "review");
        this.calculations = Objects.requireNonNull(calculations, "calculations");
    }

    public Result<PhotographySession> createSession(String title, LocalDate date) {
        return sessions.create(title, date);
    }

    public Result<List<ActivitySuggestion>> suggest(DiscoveryContext context) {
        return discovery.suggest(context);
    }

    /** F02 for the context the user reviewed; select/save/dismiss and saved ideas continue through discovery(). */
    public Result<com.photopilot.model.DiscoveryResult> discover(com.photopilot.model.DiscoveryRequest request) {
        return discovery.discover(request);
    }

    /** F02 operations shared by GUI and CLI (Stage 1 select/save/dismiss had no Facade route). */
    public DiscoveryService discovery() { return discovery; }

    /** F03: AI-assisted, unconfirmed draft; review, clarification and confirmation continue through planning(). */
    public Result<ShootRequirement> parse(UUID id, String text) {
        return planning.parse(id, text);
    }

    public Result<GearImportDraft> recognize(SanitizedImage image) {
        return inventory.recognize(image);
    }

    public Result<Equipment> saveGear(Equipment item) {
        return inventory.save(item);
    }

    /** F06 (Stage 1): Gemini-proposed, unreviewed capability profile; review, clarification and confirmation continue through planning(). */
    public Result<EquipmentRequirementProfile> analyzeNeeds(UUID id) {
        return planning.analyzeNeeds(id);
    }

    /** F06 analysis with its review context (questions, deterministic adjustments, unsupported needs) for GUI and CLI. */
    public Result<com.photopilot.service.CapabilityProposal> proposeNeeds(UUID id) {
        return planning.proposeNeeds(id);
    }

    public Result<List<Loadout>> matchGear(UUID id) {
        return planning.matchGear(id);
    }

    public Result<CalculationResult> calculate(CalculationInput input) {
        return calculations.calculate(input);
    }

    public Result<EnvironmentAssessment> assessEnvironment(UUID id) {
        return planning.assessEnvironment(id);
    }

    /** F09 with the location, date and availability the user reviewed; location selection is under planning().location(). */
    public Result<EnvironmentAssessment> assessEnvironment(UUID id, com.photopilot.model.EnvironmentRequest request) {
        return planning.assessEnvironment(id, request);
    }

    /** As above; {@code onFacts} receives the factual snapshot on the worker thread while Gemini is still interpreting it. */
    public Result<EnvironmentAssessment> assessEnvironment(UUID id, com.photopilot.model.EnvironmentRequest request,
            java.util.function.Consumer<EnvironmentAssessment> onFacts) {
        return planning.assessEnvironment(id, request, onFacts);
    }

    /** F09 AI-only retry of the session's current assessment: Gemini again, no location, weather or sun request. */
    public Result<EnvironmentAssessment> retryEnvironmentInterpretation(UUID id, EnvironmentAssessment shown) {
        return planning.retryEnvironmentInterpretation(id, shown);
    }

    /** The shared, deterministic dashboard analysis that the GUI and CLI both display for one assessment snapshot. */
    public com.photopilot.model.EnvironmentAnalysis analyzeEnvironment(EnvironmentAssessment assessment) {
        return planning.analyzeEnvironment(assessment, false);
    }

    public Result<ShootPlan> generatePlan(UUID id) {
        return planning.generate(id);
    }

    public Result<ShootPlan> proposeChange(UUID id, String text) {
        return planning.proposeChange(id, text);
    }

    public Result<ExifStatistics> analyze(UUID id, List<Path> files) {
        return review.analyze(id, files);
    }

    public Result<PostShootReview> review(UUID id) {
        return review.review(id);
    }

    public com.photopilot.service.GearCapabilityService capabilities() { return capabilities; }

    /** Diagnostic Gemini connection check only; not an AI feature. */
    public LLMDiagnosticService aiDiagnostics() { return aiDiagnostics; }

    /** Home Mini AI: short inspiration answers; independent of F02 discovery(). */
    public MiniAiService miniAi() { return miniAi; }

    /** Gear Inventory shutter count checker and its Sony/Nikon model catalog. */
    public ShutterCountService shutterCount() { return shutterCount; }

    /** Session workflow progress and its recommended next milestone, from persisted records only. */
    public com.photopilot.service.SessionWorkflowService workflow() { return workflow; }

    /** Successful writes of this profile's stores, for views that follow persisted state. */
    public com.photopilot.repository.StoreChanges changes() { return changes; }

    public SessionService sessions() {
        return sessions;
    }

    public InventoryService inventory() {
        return inventory;
    }

    public PlanningService planning() {
        return planning;
    }

    public ReviewService reviews() {
        return review;
    }
}
