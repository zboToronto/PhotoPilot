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
import java.util.UUID;

/** Per-context composition root. Normal startup initializes the stable local profile without provider calls. */
public final class ApplicationBootstrap implements AutoCloseable {
    private final PhotoPilotFacade facade;
    private final HttpClient http;
    private final LocalizationService localization;
    // The active profile's own preference record (language and GUI appearance); shared values for unscoped contexts.
    private final JsonLocalePreferenceRepository preferences;
    private final StorageScope storageScope;
    private final ProfileAccess profileAccess;
    // Shutter count checker: the bundled ExifTool starts on the first check (never here) and stops in close().
    private final ExifToolProcess exifTool;

    private final Optional<LocalizedMessage> storageNotice;

    public ApplicationBootstrap(Map<String, String> environment) {
        this(environment, StorageConfiguration.resolve(environment));
    }
    private ApplicationBootstrap(Map<String, String> environment, StorageConfiguration configuration) {
        this(environment, prepare(environment, configuration.scope(), true, configuration.configurationDirectory()), configuration.configurationDirectory(), configuration.legacyDirectory());
    }
    /** One fixed scope for every business repository and agent dependency in this application. */
    public ApplicationBootstrap(Map<String, String> environment, StorageScope storageScope) {
        this(environment, prepare(environment, storageScope, false, null), StorageConfiguration.resolve(environment).configurationDirectory(), Optional.empty());
    }
    /** stableDefault: the registry's default profile, which keeps the preferences saved before they were per profile. */
    private record Selection(StorageScope scope, ProfileAccess access, boolean stableDefault) { }
    /** configuration: where the preferences live, used only when a fresh root creates the Default user. */
    private static Selection prepare(Map<String, String> environment, StorageScope requested, boolean defaultProfile, Path configuration) {
        if (!Set.of("portability", "coverage").contains(environment.getOrDefault("PHOTOPILOT_RANKING", "portability")))
            throw new IllegalArgumentException("PHOTOPILOT_RANKING must be portability or coverage");
        URI.create(environment.getOrDefault("GEMINI_ENDPOINT", GeminiAdapter.DEFAULT_ENDPOINT));
        timeoutSeconds(environment);
        exifToolTimeoutSeconds(environment);
        try {
            if (defaultProfile || requested.profileId().isPresent()) {
                var registry = new JsonProfileRepository(requested.root());
                var id = defaultProfile ? registry.initializeDefault(created -> initialPreferences(configuration, created)).id() : requested.profileId().orElseThrow();
                boolean stableDefault = defaultProfile || registry.defaultProfileId().filter(id::equals).isPresent();
                var access = registry.open(id); return new Selection(access.scope(), access, stableDefault);
            }
            // Explicit unscoped overload retained for legacy tooling/tests, never normal application startup.
            return new Selection(requested, null, false);
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot open local profile: " + error.getMessage(), error); }
    }
    /**
     * A fresh installation's Default user starts with the standard new-user preferences (UserPreferenceDefaults), unless
     * its record already inherits values saved before preferences were per profile, which are kept as they are. Best
     * effort: if the file cannot be read or written, startup continues and the user has the in-memory defaults.
     */
    private static void initialPreferences(Path configuration, UUID id) {
        try {
            var preferences = sharedPreferences(configuration).forProfile(id, true);
            if (preferences.values().isEmpty()) UserPreferenceDefaults.initialize(preferences);
        } catch (java.io.IOException | RuntimeException unavailable) {
            // In-memory defaults until the user changes a setting; reported like other preference failures.
            java.util.logging.Logger.getLogger(ApplicationBootstrap.class.getName()).warning("preference.initialize: " + unavailable.getClass().getSimpleName());
        }
    }
    private static JsonLocalePreferenceRepository sharedPreferences(Path configuration) {
        return new JsonLocalePreferenceRepository(configuration.resolve("preferences.json"), configuration.resolve("settings.properties"));
    }
    private ApplicationBootstrap(Map<String, String> environment, Selection selection, Path configuration, Optional<Path> legacy) {
        this.storageScope = selection.scope(); this.profileAccess = selection.access();
        storageNotice = legacy.map(path -> LocalizedMessage.of("storage.legacyFound", path.toString(), storageScope.directory().toString()));
        var shared = sharedPreferences(configuration);
        // Language (and, in the GUI, appearance) belongs to the active profile's UUID; unscoped contexts keep the shared values.
        preferences = storageScope.profileId().map(id -> shared.forProfile(id, selection.stableDefault())).orElse(shared);
        localization = new LocalizationService(preferences);
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
        // Constructing the client sends nothing; only user-started AI operations (and CLI ai-test) reach the provider.
        LLMClient model = new LanguageAwareLLMClient(new GeminiAdapter(geminiEndpoint,
            geminiModel, http, Duration.ofSeconds(timeoutSeconds(environment)), llmConfiguration), localization);
        PhotoPilotAgent agent = new PhotoPilotAgent(model, tools, new HistoryRetriever(sessions), 8);
        TechnicalCalculator calculator = new TechnicalCalculator();
        CompatibilityValidator compatibility = new CompatibilityValidator();
        FeasibilityValidator feasibility = new FeasibilityValidator();
        LoadoutRankingStrategy portability = new PortabilityRanking(agent), coverage = new CoverageRanking(agent);
        LoadoutRankingStrategy ranking = preference.equals("coverage") ? coverage : portability;
        // The configured strategy is the default; the other remains selectable per F07 request in GUI and CLI.
        GearMatchingService matching = new GearMatchingService(compatibility, ranking, java.util.List.of(portability, coverage));
        // F09 providers are free and key-less; endpoints are overridable (tests use loopback fakes). Nothing is sent here.
        // No desktop device-location provider exists, so device location reports unsupported and manual entry remains.
        Duration providerTimeout = Duration.ofSeconds(15);
        EnvironmentService environmentService = new EnvironmentService(
            new EnvironmentApiAdapter(endpoint(environment, "PHOTOPILOT_WEATHER_ENDPOINT", EnvironmentApiAdapter.DEFAULT_WEATHER_ENDPOINT),
                endpoint(environment, "PHOTOPILOT_SUN_ENDPOINT", EnvironmentApiAdapter.DEFAULT_SUN_ENDPOINT), http, providerTimeout, java.time.Clock.systemUTC()),
            new LocationService(Optional.empty(), Optional.of(new GeocodingAdapter(
                endpoint(environment, "PHOTOPILOT_GEOCODING_ENDPOINT", GeocodingAdapter.DEFAULT_ENDPOINT), http, providerTimeout)), java.time.Clock.systemUTC()),
            agent, feasibility);
        // F04 specification evidence: Wikipedia API + Lensfun open database, free and key-less; overridable for tests.
        EquipmentSpecificationProvider specifications = new EquipmentCatalogAdapter(
            endpoint(environment, "PHOTOPILOT_SPEC_WIKI_ENDPOINT", EquipmentCatalogAdapter.DEFAULT_ENDPOINT),
            endpoint(environment, "PHOTOPILOT_SPEC_LENSFUN_ENDPOINT", EquipmentCatalogAdapter.DEFAULT_LENSFUN),
            http, providerTimeout, java.time.Clock.systemUTC());
        PlanValidator validator = new PlanValidator(compatibility, calculator, feasibility);
        // Constructing the process starts nothing; the bundle is located (and, from a JAR, extracted) on the first check.
        exifTool = new ExifToolProcess(ExifToolRuntime.forEnvironment(environment)::resolve, Duration.ofSeconds(exifToolTimeoutSeconds(environment)));
        // One ExifTool (one request at a time) serves both the shutter count checker and RAW files in the post-shoot review.
        ExifToolAdapter exifToolReader = new ExifToolAdapter(exifTool);
        // Phase two closes the existing agent -> registry -> tools -> services -> agent cycle.
        // register() is in the approved UML. It does not execute any tool or request. Only tools a task permits are
        // registered: F10 may call the F08 calculator. The other Stage 1 tools stay unregistered by design:
        // EnvironmentTool takes coordinates, which never go to Gemini (F10 receives the F09 evidence the user asked
        // for instead); F07's verified candidates already reach F10 as labelled offers, so GearMatchingTool would
        // duplicate GearMatchingService; SpecificationTool would let model-chosen names reach external catalogues.
        tools.register(new CalculationTool(calculator));
        var inventoryService = new InventoryService(inventory, sessions, new GearRecognitionService(agent, specifications));
        facade = new PhotoPilotFacade(new SessionService(sessions), inventoryService,
            // F02 reads F05 inventory and F07's deterministic loadout generation; it never ranks gear or writes inventory.
            new DiscoveryService(agent, environmentService, feasibility, sessions, inventory, matching),
            new PlanningService(new RequirementService(agent), matching, environmentService, agent,
                new PlanEditor(validator), validator, sessions, inventory, calculator),
            // F11: local JPEG EXIF + ExifTool JSON reading + RAW metadata through the bundled ExifTool; statistics never
            // involve the agent, which only interprets them.
            new ReviewService(new ExifLibraryAdapter(ExifLibraryAdapter.DEFAULT_FORMATS), new StatisticsCalculator(), agent, sessions),
            calculator, new LLMDiagnosticService(model, llmConfiguration, geminiModel),
            // Home Mini AI: the same model port (key, model, timeout, language policy); sends only when the user asks.
            new MiniAiService(model, inventoryService, java.time.Clock.systemDefaultZone()),
            // Gear Inventory shutter count checker: local, read-only ExifTool metadata; never involves the agent.
            new ShutterCountService(exifToolReader, CameraModelCatalog.bundled()));
    }

    /** Per-request ExifTool timeout; 30 s unless PHOTOPILOT_EXIFTOOL_TIMEOUT_SECONDS (5-300) is set (slow network drives). */
    static int exifToolTimeoutSeconds(Map<String, String> environment) {
        String value = environment.getOrDefault("PHOTOPILOT_EXIFTOOL_TIMEOUT_SECONDS", "").strip();
        if (value.isEmpty()) return 30;
        try {
            int seconds = Integer.parseInt(value);
            if (seconds >= 5 && seconds <= 300) return seconds;
        } catch (NumberFormatException invalid) { /* reported below */ }
        throw new IllegalArgumentException("PHOTOPILOT_EXIFTOOL_TIMEOUT_SECONDS must be a whole number from 5 to 300");
    }

    /** Gemini request timeout; 30 s unless GEMINI_TIMEOUT_SECONDS (5-300) is set, e.g. for longer F10 planning replies. */
    static int timeoutSeconds(Map<String, String> environment) {
        String value = environment.getOrDefault("GEMINI_TIMEOUT_SECONDS", "").strip();
        if (value.isEmpty()) return 30;
        try {
            int seconds = Integer.parseInt(value);
            if (seconds >= 5 && seconds <= 300) return seconds;
        } catch (NumberFormatException invalid) { /* reported below */ }
        throw new IllegalArgumentException("GEMINI_TIMEOUT_SECONDS must be a whole number from 5 to 300");
    }

    private static URI endpoint(Map<String, String> environment, String name, URI fallback) {
        String value = environment.getOrDefault(name, "");
        if (value.isBlank()) return fallback;
        URI uri = URI.create(value.strip());
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) throw new IllegalArgumentException(name + " must be an http(s) URL");
        return uri;
    }

    public PhotoPilotFacade facade() { return facade; }
    public LocalizationService localization() { return localization; }
    /** This context's preference record: the active profile's own (by UUID), or the shared values when unscoped. */
    public JsonLocalePreferenceRepository preferences() { return preferences; }
    public Optional<LocalizedMessage> storageNotice() { return storageNotice; }
    public StorageScope storageScope() { return storageScope; }

    @Override
    public void close() { exifTool.close(); if (profileAccess != null) profileAccess.close(); http.close(); }
}
