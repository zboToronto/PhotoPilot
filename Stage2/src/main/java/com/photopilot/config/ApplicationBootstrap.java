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

/** Per-context composition root. Normal startup initializes the stable local profile without provider calls. */
public final class ApplicationBootstrap implements AutoCloseable {
    private final PhotoPilotFacade facade;
    private final HttpClient http;
    private final LocalizationService localization;
    private final StorageScope storageScope;
    private final ProfileAccess profileAccess;

    private final Optional<LocalizedMessage> storageNotice;

    public ApplicationBootstrap(Map<String, String> environment) {
        this(environment, StorageConfiguration.resolve(environment));
    }
    private ApplicationBootstrap(Map<String, String> environment, StorageConfiguration configuration) {
        this(environment, prepare(environment, configuration.scope(), true), configuration.configurationDirectory(), configuration.legacyDirectory());
    }
    /** One fixed scope for every business repository and agent dependency in this application. */
    public ApplicationBootstrap(Map<String, String> environment, StorageScope storageScope) {
        this(environment, prepare(environment, storageScope, false), StorageConfiguration.resolve(environment).configurationDirectory(), Optional.empty());
    }
    private record Selection(StorageScope scope, ProfileAccess access) { }
    private static Selection prepare(Map<String, String> environment, StorageScope requested, boolean defaultProfile) {
        if (!Set.of("portability", "coverage").contains(environment.getOrDefault("PHOTOPILOT_RANKING", "portability")))
            throw new IllegalArgumentException("PHOTOPILOT_RANKING must be portability or coverage");
        URI.create(environment.getOrDefault("GEMINI_ENDPOINT", GeminiAdapter.DEFAULT_ENDPOINT));
        try {
            if (defaultProfile || requested.profileId().isPresent()) {
                var registry = new JsonProfileRepository(requested.root());
                var id = defaultProfile ? registry.initializeDefault().id() : requested.profileId().orElseThrow();
                var access = registry.open(id); return new Selection(access.scope(), access);
            }
            // Explicit unscoped overload retained for legacy tooling/tests, never normal application startup.
            return new Selection(requested, null);
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot open local profile: " + error.getMessage(), error); }
    }
    private ApplicationBootstrap(Map<String, String> environment, Selection selection, Path configuration, Optional<Path> legacy) {
        this.storageScope = selection.scope(); this.profileAccess = selection.access();
        storageNotice = legacy.map(path -> LocalizedMessage.of("storage.legacyFound", path.toString(), storageScope.directory().toString()));
        localization = new LocalizationService(new JsonLocalePreferenceRepository(configuration.resolve("preferences.json"),
            configuration.resolve("settings.properties")));
        String preference = environment.getOrDefault("PHOTOPILOT_RANKING", "portability");
        if (!Set.of("portability", "coverage").contains(preference)) {
            throw new IllegalArgumentException("PHOTOPILOT_RANKING must be portability or coverage");
        }
        URI geminiEndpoint = URI.create(environment.getOrDefault("GEMINI_ENDPOINT", GeminiAdapter.DEFAULT_ENDPOINT));
        String geminiModel = environment.getOrDefault("GEMINI_MODEL", GeminiAdapter.DEFAULT_MODEL);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        SessionRepository sessions = profileAccess == null ? new JsonSessionRepository(storageScope.sessionsDirectory()) : profileAccess.sessions();
        InventoryRepository inventory = profileAccess == null ? new JsonInventoryRepository(storageScope.inventoryFile()) : profileAccess.inventory();
        ToolRegistry tools = new ToolRegistry(Map.of());
        LLMConfiguration llmConfiguration = LLMConfiguration.load(environment);
        // Constructing the client sends nothing; only explicit complete() calls (CLI ai-test) reach the provider.
        LLMClient model = new LanguageAwareLLMClient(new GeminiAdapter(geminiEndpoint,
            geminiModel, http, Duration.ofSeconds(30), llmConfiguration), localization);
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
            calculator, new LLMDiagnosticService(model, llmConfiguration, geminiModel));
    }

    public PhotoPilotFacade facade() { return facade; }
    public LocalizationService localization() { return localization; }
    public Optional<LocalizedMessage> storageNotice() { return storageNotice; }
    public StorageScope storageScope() { return storageScope; }

    @Override
    public void close() { if (profileAccess != null) profileAccess.close(); http.close(); }
}
