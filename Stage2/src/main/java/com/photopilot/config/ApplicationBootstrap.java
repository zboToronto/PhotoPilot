package com.photopilot.config;

import com.photopilot.agent.*;
import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.command.PlanEditor;
import com.photopilot.integration.*;
import com.photopilot.i18n.*;
import com.photopilot.repository.*;
import com.photopilot.service.*;
import com.photopilot.strategy.*;
import com.photopilot.validation.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Per-application composition root. Reads preferences, but never writes business data or calls providers at startup. */
public final class ApplicationBootstrap implements AutoCloseable {
    private final PhotoPilotFacade facade;
    private final HttpClient http;
    private final LocalizationService localization;
    private final StorageScope storageScope;

    public ApplicationBootstrap(Map<String, String> environment) {
        this(environment, StorageScope.legacy(Path.of(environment.getOrDefault("PHOTOPILOT_DATA_DIR", "data"))));
    }

    /** One fixed scope for every business repository and agent dependency in this application. */
    public ApplicationBootstrap(Map<String, String> environment, StorageScope storageScope) {
        this.storageScope = java.util.Objects.requireNonNull(storageScope, "storageScope");
        Path configuration = Path.of(environment.getOrDefault("PHOTOPILOT_CONFIG_DIR",
            Path.of(System.getProperty("user.home"), ".photopilot").toString()));
        localization = new LocalizationService(new FileLocalePreferenceRepository(configuration.resolve("settings.properties")));
        String preference = environment.getOrDefault("PHOTOPILOT_RANKING", "portability");
        if (!Set.of("portability", "coverage").contains(preference)) {
            throw new IllegalArgumentException("PHOTOPILOT_RANKING must be portability or coverage");
        }
        URI geminiEndpoint = URI.create(environment.getOrDefault("GEMINI_ENDPOINT", "https://unconfigured.invalid/gemini"));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        SessionRepository sessions = new JsonSessionRepository(storageScope.sessionsDirectory());
        InventoryRepository inventory = new JsonInventoryRepository(storageScope.inventoryFile());
        ToolRegistry tools = new ToolRegistry(Map.of());
        LLMClient model = new LanguageAwareLLMClient(new GeminiAdapter(geminiEndpoint,
            environment.getOrDefault("GEMINI_MODEL", "UNCONFIGURED"), http, Duration.ofSeconds(30)), localization);
        PhotoPilotAgent agent = new PhotoPilotAgent(model, tools, new HistoryRetriever(sessions), 8);
        TechnicalCalculator calculator = new TechnicalCalculator();
        CompatibilityValidator compatibility = new CompatibilityValidator();
        FeasibilityValidator feasibility = new FeasibilityValidator();
        LoadoutRankingStrategy ranking = preference.equals("coverage")
            ? new CoverageRanking(agent) : new PortabilityRanking(agent);
        GearMatchingService matching = new GearMatchingService(compatibility, ranking);
        EnvironmentService environmentService = new EnvironmentService(
            new EnvironmentApiAdapter(URI.create("https://unconfigured.invalid/weather"),
                URI.create("https://unconfigured.invalid/sun"), http),
            new LocationService(Optional.empty()), agent, feasibility);
        EquipmentSpecificationProvider specifications = new EquipmentCatalogAdapter(
            URI.create("https://unconfigured.invalid/equipment"), http);
        PlanValidator validator = new PlanValidator(compatibility, calculator);
        // Phase two closes the existing agent -> registry -> tools -> services -> agent cycle.
        // register() is in the approved UML. It does not execute any tool or request.
        tools.register(new CalculationTool(calculator));
        tools.register(new GearMatchingTool(matching, inventory));
        tools.register(new EnvironmentTool(environmentService));
        tools.register(new SpecificationTool(specifications));
        facade = new PhotoPilotFacade(new SessionService(sessions),
            new InventoryService(inventory, sessions, new GearRecognitionService(agent, specifications)),
            new DiscoveryService(agent, environmentService, feasibility, sessions),
            new PlanningService(new RequirementService(agent), matching, environmentService, agent,
                new PlanEditor(validator), validator, sessions, inventory, calculator),
            new ReviewService(new ExifLibraryAdapter(Set.of()), new StatisticsCalculator(), agent, sessions),
            calculator);
    }

    public PhotoPilotFacade facade() { return facade; }
    public LocalizationService localization() { return localization; }
    public StorageScope storageScope() { return storageScope; }

    @Override
    public void close() { http.close(); }
}
