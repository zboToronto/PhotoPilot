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
import com.photopilot.service.InventoryService;
import com.photopilot.service.PlanningService;
import com.photopilot.service.ReviewService;
import com.photopilot.service.SessionService;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 3 (zero-based): {Facade}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class PhotoPilotFacade {
    private SessionService sessions;
    private InventoryService inventory;
    private DiscoveryService discovery;
    private PlanningService planning;
    private ReviewService review;
    private TechnicalCalculator calculations;

    public PhotoPilotFacade(SessionService sessions, InventoryService inventory, DiscoveryService discovery, PlanningService planning, ReviewService review, TechnicalCalculator calculations) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
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

    public Result<ShootRequirement> parse(UUID id, String text) {
        return planning.parse(id, text);
    }

    public Result<GearImportDraft> recognize(SanitizedImage image) {
        return inventory.recognize(image);
    }

    public Result<Equipment> saveGear(Equipment item) {
        return inventory.save(item);
    }

    public Result<EquipmentRequirementProfile> analyzeNeeds(UUID id) {
        return planning.analyzeNeeds(id);
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
