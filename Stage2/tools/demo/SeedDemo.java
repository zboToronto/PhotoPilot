import com.google.gson.*;
import com.photopilot.agent.*;
import com.photopilot.application.*;
import com.photopilot.command.PlanEditor;
import com.photopilot.common.Result;
import com.photopilot.config.*;
import com.photopilot.i18n.Language;
import com.photopilot.model.*;
import com.photopilot.repository.*;
import com.photopilot.service.*;
import com.photopilot.state.SessionEvent;
import com.photopilot.strategy.PortabilityRanking;
import com.photopilot.ui.privacy.*;
import com.photopilot.validation.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.photopilot.model.EquipmentType.*;

/** One-time local tool; deliberately outside Maven's application and test source sets. */
public final class SeedDemo {
    private static final String NAME = "PhotoPilot Demo";
    private static final String STREET = "Toronto Winter Street Photography";
    private static final String PORTRAIT = "Golden Hour Outdoor Portrait Session";
    private static final String AVIATION = "Aviation Photography — Completed Airshow Session";
    private static final ZoneId ZONE = ZoneId.of("America/Toronto");
    private static final LocalDate AIR_DATE = LocalDate.of(2026, 9, 5);
    private static final String A7 = "Alpha 7 IV", A7R = "Alpha 7R V", PRIME = "FE 55mm F1.8 ZA";
    private static final String LONG = "50-400mm F4.5-6.3 Di III VC VXD (A067)";
    private static final String SHORT = "70-180mm F2.8 Di III VC VXD G2 (A065)";
    private static final List<String> RAWS = List.of("DSC00166.ARW", "DSC01767.ARW", "DSC09908.ARW");
    private static final Gson JSON = new Gson();
    private static final String OFFLINE = "Offline demonstration: locally authored planning example; no AI or weather service was called.";

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("paths", "seed", "verify").contains(args[0]))
            throw new IllegalArgumentException("Usage: SeedDemo paths|seed|verify <Demo assets directory> <expected data directory> <expected configuration directory>");
        var environment = System.getenv();
        var config = StorageConfiguration.resolve(environment);
        var root = config.scope().root();
        var prefs = config.configurationDirectory().resolve("preferences.json");
        System.out.println("Data: " + root + "\nPreferences: " + prefs);
        require(root.equals(Path.of(args[2]).toAbsolutePath().normalize()), "Storage location differs from the expected data directory.");
        require(config.configurationDirectory().equals(Path.of(args[3]).toAbsolutePath().normalize()),
            "Configuration location differs from the expected configuration directory.");
        if (args[0].equals("paths")) return;
        var assets = Path.of(args[1]).toAbsolutePath().normalize();
        var registry = new JsonProfileRepository(root);
        var existing = registry.defaultProfileId();
        // A scratch run may initialize an empty installation. Do not repair an interrupted shared installation.
        if (existing.isPresent()) require(JsonParser.parseString(Files.readString(root.resolve("profiles.json"))).getAsJsonObject()
            .get("pending").isJsonNull(), "The registry has a pending operation. Finish recovery in PhotoPilot before seeding.");
        if (args[0].equals("seed")) seed(environment, config, registry, existing, assets);
        verify(environment, assets);
    }

    private static Optional<LocalProfile> demo(List<LocalProfile> profiles) {
        var matches = profiles.stream().filter(p -> p.displayName().equalsIgnoreCase(NAME)).toList();
        require(matches.size() <= 1, "Multiple demo profiles already exist; cannot choose one safely.");
        return matches.stream().findFirst();
    }

    private static void seed(Map<String, String> env, StorageConfiguration config, JsonProfileRepository registry,
            Optional<UUID> initial, Path assets) throws IOException {
        var profileFile = registry.root().resolve("profiles.json");
        var prefsFile = config.configurationDirectory().resolve("preferences.json");
        var prefs = new JsonLocalePreferenceRepository(prefsFile, config.configurationDirectory().resolve("settings.properties"));
        Map<String, String> beforePrefs = prefs.values();
        JsonObject beforeRegistry = Files.exists(profileFile) ? readRegistry(profileFile) : null;
        var beforeUsers = initial.isPresent() ? registry.list() : List.<LocalProfile>of();
        var beforeDemo = demo(beforeUsers);
        require(!beforeDemo.map(LocalProfile::id).equals(initial) || initial.isEmpty(),
            "Refusing to populate the existing Default identity.");
        var otherFiles = fileHashes(registry.root(), beforeDemo.map(LocalProfile::id).orElse(null));
        var backup = new Backup(config.configurationDirectory());
        var avatar = ok("prepare avatar", ProfileManager.prepareAvatar(assets.resolve("demo-avatar.jpeg")));
        validateRecognitionSamples(env, assets);
        if (initial.isEmpty()) backup.copy(prefsFile, "preferences");
        UUID id;
        try (var manager = new ProfileManager(env, beforeDemo.or(() -> initial.map(uuid ->
                beforeUsers.stream().filter(p -> p.id().equals(uuid)).findFirst().orElseThrow())).map(LocalProfile::id).orElse(null))) {
            LocalProfile selected;
            if (beforeDemo.isPresent()) {
                selected = beforeDemo.get();
                if (!selected.displayName().equals(NAME)) {
                    backup.copy(profileFile, "registry");
                    selected = ok("normalize demo name", manager.renameProfile(selected.id(), NAME));
                }
            } else {
                backup.copy(profileFile, "registry");
                backup.copy(prefsFile, "preferences");
                selected = ok("create profile", manager.createProfile(NAME));
            }
            id = selected.id();
            ok("select demo user", manager.selectProfile(id, true));
            var app = manager.activeContext();
            var scope = app.storageScope();
            var demoPrefs = app.preferences();
            var values = demoPrefs.values();
            if (!preferencesMatch(values)) {
                backup.copy(prefsFile, "preferences");
                if (!values.entrySet().containsAll(UserPreferenceDefaults.APPEARANCE.entrySet()))
                    demoPrefs.merge(UserPreferenceDefaults.APPEARANCE);
                if (!"en".equals(values.get("language"))) demoPrefs.save("en");
            }
            if (ok("read avatar", manager.avatar(id)).isEmpty()) ok("save avatar", manager.saveAvatar(id, avatar));
            var api = app.facade();
            var items = new ArrayList<>(ok("load inventory", api.inventory().list("")).items());
            for (var item : equipment()) {
                var matches = items.stream().filter(current -> sameGear(current, item)).toList();
                require(matches.size() <= 1, "Duplicate equipment already exists: " + item.model());
                if (matches.isEmpty()) {
                    backup.copy(scope.inventoryFile(), "demo");
                    items.add(ok("manual inventory entry " + item.model(), api.inventory().add(item, false)));
                }
            }

            // Only the completion port is substituted. All matching, preview ownership, validation, history, and writes are real.
            try (var access = registry.open(id)) {
                var calculator = new TechnicalCalculator();
                var feasibility = new FeasibilityValidator();
                var compatibility = new CompatibilityValidator();
                var validator = new PlanValidator(compatibility, calculator, feasibility);
                var agent = new PhotoPilotAgent(SeedDemo::localExample, new ToolRegistry(Map.of("calculation", new CalculationTool(calculator))),
                    new HistoryRetriever(access.sessions()), 8);
                var matching = new GearMatchingService(compatibility, new PortabilityRanking(agent));
                var environment = new EnvironmentService((place, day) -> Result.failure("Offline demo"),
                    new LocationService(Optional.empty()), agent, feasibility);
                var planning = new PlanningService(new RequirementService(agent), matching, environment, agent,
                    new PlanEditor(validator), validator, access.sessions(), access.inventory(), calculator);
                var sessions = api.sessions();
                Path sessionFile = scope.sessionsDirectory().resolve("sessions.json");
                var now = LocalDate.now(ZONE);
                var portraitDate = YearMonth.from(now).plusMonths(1).atDay(12);
                var streetDate = YearMonth.from(now).plusMonths(2).atDay(9);
                populate(sessions, planning, backup, sessionFile, STREET, streetDate,
                    "Downtown Toronto: St. Lawrence Market to the Distillery District",
                    "Scout a short winter street route. Look for steam against backlight, umbrellas in motion and reflections after snow. "
                    + "Suggested kit: Alpha 7 IV with FE 20-70mm F4 G; pack the FE 35mm F1.4 ZA only if the route extends into dusk. "
                    + "Exact departure time, ice conditions and a sheltered fallback still need confirmation.",
                    null, 105, 1, 1700,
                    List.of("Make a six-frame story from wide context to street details", "Use reflections and warm storefront light"),
                    null);
                populate(sessions, planning, backup, sessionFile, PORTRAIT, portraitDate,
                    "Humber Bay Park East, Toronto",
                    "Demo booking: environmental portraits for one adult, using a short route around the shoreline. "
                    + "Meet at the entrance and confirm consent, wardrobe and the shaded fallback before shooting.",
                    LocalTime.of(15, 25), 85, 1, 1500,
                    List.of("Make backlit head-and-shoulders frames", "Finish with wider waterfront portraits",
                        "Keep the subject's eyes sharp while protecting highlights"),
                    Set.of(A7, PRIME));
                var air = populate(sessions, planning, backup, sessionFile, AVIATION, AIR_DATE,
                    "Toronto waterfront airshow, Ontario",
                    "Demo plan reconstructed for the 5 September 2026 sample set. Photograph jet passes, formations and wider crowd-line context. "
                    + "The approved plan is illustrative; F11 uses only real metadata from the three supplied Airshow RAW files.",
                    LocalTime.of(11, 45), 240, 2, 4000,
                    List.of("Freeze fast jets in flight", "Capture banked passes with space ahead of the aircraft",
                        "Keep a second body ready for wider formations and close passes"),
                    Set.of(A7, A7R, LONG, SHORT));

                if (air.status().equals("PLANNED")) {
                    backup.copy(sessionFile, "demo");
                    air = ok("mark airshow shoot completed", sessions.transition(air, SessionEvent.SHOOT_COMPLETED));
                }
                if (air.status().equals("COMPLETED") && air.statistics().isEmpty()) {
                    backup.copy(sessionFile, "demo");
                    var files = RAWS.stream().map(assets.resolve("SamplePhotos/Airshow")::resolve).toList();
                    var analysis = ok("run local EXIF analysis", api.reviews().analysis(air.id(), files, false));
                    require(analysis.status().persisted(), "EXIF was not saved: " + analysis.status() + " " + analysis.notices());
                    System.out.println("EXIF import: " + analysis.status() + "; usable=" + analysis.statistics().orElseThrow().usableCount());
                }
            }
        }
        if (beforeRegistry != null) {
            require(registryProjection(beforeRegistry, id).equals(registryProjection(readRegistry(profileFile), id)),
                "Other profile entries or the registry default changed.");
        }
        var afterPrefs = prefs.values();
        String prefix = "profile." + id + ".";
        require(withoutPrefix(beforePrefs, prefix).equals(withoutPrefix(afterPrefs, prefix)) || initial.isEmpty(),
            "Preferences outside the demo record changed.");
        require(otherFiles.equals(fileHashes(registry.root(), id)), "Files outside the demo profile changed.");
        System.out.println("Isolation verified: other profile entries, preferences and business file hashes are unchanged.");
        if (backup.created) System.out.println("Backup: " + backup.directory);
    }

    private static Map<String, String> withoutPrefix(Map<String, String> values, String prefix) {
        var filtered = new HashMap<>(values);
        filtered.keySet().removeIf(key -> key.startsWith(prefix));
        return filtered;
    }

    private static JsonObject readRegistry(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static JsonObject registryProjection(JsonObject original, UUID demo) {
        var copy = original.deepCopy();
        copy.getAsJsonArray("profiles").asList()
            .removeIf(p -> p.getAsJsonObject().getAsJsonObject("profile").get("id").getAsString().equals(demo.toString()));
        return copy;
    }

    private static Map<Path, String> fileHashes(Path root, UUID excluded) throws IOException {
        var result = new TreeMap<Path, String>();
        if (!Files.isDirectory(root)) return result;
        var demo = excluded == null ? null : StorageScope.forProfile(root, excluded).directory();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".lock") || name.equals("profiles.json") && root.equals(path.getParent())
                        || demo != null && path.startsWith(demo)) continue;
                try (var in = Files.newInputStream(path)) {
                    var md = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1;) md.update(buffer, 0, n);
                    result.put(root.relativize(path), HexFormat.of().formatHex(md.digest()));
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            }
        }
        return result;
    }

    private static final class Backup {
        private final Path directory;
        private final Set<Path> copied = new HashSet<>();
        private boolean created;
        Backup(Path configuration) {
            directory = configuration.resolve("demo-seed-backups").resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
        }
        void copy(Path file, String group) throws IOException {
            if (!copied.add(file) || !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "Unsafe backup input: " + file);
            Path target = directory.resolve(group).resolve(file.getFileName());
            Files.createDirectories(target.getParent());
            Files.copy(file, target);
            created = true;
        }
    }

    private static PhotographySession populate(SessionService sessions, PlanningService planning, Backup backup, Path sessionFile,
            String title, LocalDate date, String place, String notes, LocalTime start, int minutes, int bodies, double weight,
            List<String> goals, Set<String> gear) throws IOException {
        var existing = find(sessions, title);
        boolean draft = gear == null;
        if (existing.isPresent()) {
            var saved = existing.get();
            var actual = saved.status();
            if (actual.equals(draft ? "DRAFT" : "PLANNED") && saved.requirements().isPresent()
                    || AVIATION.equals(title) && actual.equals("COMPLETED")) return saved;
            require(actual.equals("DRAFT"), "Unexpected existing demo session state: " + title + " " + actual);
        }
        backup.copy(sessionFile, "demo");
        var location = Optional.of(GeoLocation.manual(place));
        var session = existing.orElseGet(() -> ok("create " + title, sessions.create(title, date, location, notes)));
        var time = Optional.ofNullable(start).map(t -> ZonedDateTime.of(session.date(), t, ZONE));
        UUID id = session.id();
        if (session.requirements().isEmpty()) {
            var requirement = new ShootRequirement(notes, draft ? "Winter street scenes and pedestrians" :
                bodies == 1 ? "An adult portrait subject in evening light" : "Fast-moving aircraft in a waterfront flying display",
                goals, time, Optional.of(session.date()), draft ? "Late afternoon; exact start pending" :
                    bodies == 1 ? "Aim for low evening sun; use open shade if overcast" : "Midday and afternoon flying display",
                session.location(), Optional.of(Duration.ofMinutes(minutes)), Optional.of(bodies), draft ? Optional.empty() : Optional.of(false),
                Optional.of(weight), draft ? "Keep the walking kit under 1.7 kg" : "Work handheld with a compact bag",
                draft ? List.of("Bring a weather cover and keep one spare battery warm") :
                    List.of("Keep one lens mounted per body during shooting"),
                Map.of(draft ? "story sequence" : bodies == 1 ? "eye sharpness" : "sharpness on fast passes", 1,
                    draft ? "portability" : bodies == 1 ? "natural light" : "telephoto reach", 2),
                Map.of(), List.of(), false);
            ok("confirm shooting requirements for " + title, planning.confirm(session, requirement));
        }
        if (draft) return ok("reload draft", sessions.open(id));

        if (session.profile().isEmpty()) {
            var needs = new ArrayList<CapabilityNeed>();
            needs.add(need("cameraCount", (double) bodies, (double) bodies, 1, "One permanently mounted setup per body."));
            needs.add(need("lensCount", (double) bodies, (double) bodies, 1, "No lens changes during shooting."));
            needs.add(need("focalLength", bodies == 1 ? 55.0 : 180.0, bodies == 1 ? 55.0 : 400.0, 2,
                bodies == 1 ? "Natural perspective for a single subject." : "Coverage for near formations and distant passes."));
            needs.add(need("autofocus", null, null, 2, "Continuous autofocus and subject tracking."));
            if (bodies == 1) needs.add(need("maxAperture", null, 2.0, 3, "Room for selective focus as the light falls."));
            else needs.add(need("burstRate", 8.0, null, 3, "Short bursts through banks and crossings."));
            ok("confirm manual capability profile for " + title, planning.confirmProfile(id, new EquipmentRequirementProfile(needs, false)));
        }
        if (session.selectedLoadout().isEmpty()) {
            var report = ok("match gear for " + title, planning.matchGear(id, "portability"));
            var chosen = report.recommendations().stream().filter(GearMatchReport.Candidate::fullySuitable)
                .map(GearMatchReport.Candidate::loadout).filter(candidate -> models(candidate).equals(gear)).findFirst().orElseThrow(
                    () -> new IllegalStateException("No compatible demo loadout for " + title + ": " + gear));
            ok("save selected loadout for " + title, planning.selectLoadout(id, chosen));
        }
        var preview = ok("generate validated preview for " + title, planning.generatePreview(id));
        ok("approve preview for " + title, planning.approve(id, preview.plan()));
        return ok("reload approved session", sessions.open(id));
    }

    private static Optional<PhotographySession> find(SessionService sessions, String title) {
        var hits = ok("search sessions", sessions.search(title)).stream().filter(s -> s.title().equalsIgnoreCase(title)).toList();
        require(hits.size() <= 1, "Duplicate demo session titles already exist: " + title);
        return hits.stream().findFirst();
    }

    private static CapabilityNeed need(String name, Double min, Double max, int priority, String rationale) {
        return CapabilityNeed.of(name, Optional.ofNullable(min), Optional.ofNullable(max), priority, true, rationale);
    }

    private static Set<String> models(Loadout loadout) {
        var result = new HashSet<String>();
        loadout.gear().stream().filter(item -> item.type().equals(CAMERA.name()) || item.type().equals(LENS.name()))
            .forEach(item -> result.add(item.model()));
        return result;
    }

    private static Result<ModelReply> localExample(AgentContext context) {
        if (!context.messages().contains("Generate the shoot plan for this photography session."))
            return Result.failure("Offline demo uses deterministic matching; no model was called.");
        var evidence = JsonParser.parseString(context.evidenceJson()).getAsJsonObject();
        var selected = evidence.getAsJsonArray("candidates").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(c -> "F07 selection".equals(c.get("source").getAsString())).findFirst().orElseThrow();
        var labels = new HashMap<String, String>();
        for (var item : selected.getAsJsonArray("gear")) {
            var gear = item.getAsJsonObject();
            labels.put(gear.get("name").getAsString(), gear.get("label").getAsString());
        }
        boolean portrait = selected.get("cameraBodies").getAsInt() == 1;
        LocalDate date = LocalDate.parse(evidence.getAsJsonObject("timing").get("date").getAsString());
        var result = new LinkedHashMap<String, Object>();
        result.put("summary", portrait
            ? "Photograph one subject along the waterfront, working from open shade to low-angle backlight."
            : "Use two mounted zooms to cover distant high-speed passes and wider formations from the public viewing area.");
        result.put("loadout", selected.get("label").getAsString());
        result.put("mounts", portrait
            ? List.of(mount(labels, "Sony " + A7, "Sony " + PRIME, "Primary portrait setup"))
            : List.of(mount(labels, "Sony " + A7R, "Tamron " + LONG, "Distant solo passes"),
                      mount(labels, "Sony " + A7, "Tamron " + SHORT, "Near formations and wider passes")));
        result.put("equipmentNotes", portrait
            ? List.of("Use the same mounted prime throughout; change subject distance for wider frames.")
            : List.of("Keep each setup mounted; switch bodies as the formation approaches."));
        result.put("settings", portrait ? List.of(
            setting("shutterSpeed", "1/250-1/500 s", "all"), setting("aperture", "f/2.2-f/4", "all"),
            setting("iso", "100-1600", "all"), setting("autofocusMode", "AF-C with human eye detection", "all"))
            : List.of(setting("shutterSpeed", "1/1600-1/3200 s", "all"), setting("aperture", "f/6.3-f/8", "all"),
                setting("iso", "100-3200", "all"), setting("autofocusMode", "AF-C with tracking zone", "all")));
        result.put("strategy", portrait
            ? Map.of("approach", "Start in open shade to settle posing, then turn toward the waterfront for rim light.",
                "technical", "Use short bursts during movement and check eye focus at full magnification.",
                "autofocus", "Start with the near eye; switch to a small zone if eye detection hunts.",
                "exposure", "Meter for skin and check the highlight warning after changing the angle to the sun.",
                "composition", "Keep the horizon level and away from the neckline; shoot a vertical and a wide variation.")
            : Map.of("approach", "Track along the display line from the public area; use short bursts around banks.",
                "technical", "Use a fast shutter for jets; use a separate slower setting only for intentional propeller blur.",
                "autofocus", "Acquire early with a medium tracking zone and pan smoothly through the pass.",
                "exposure", "Check the histogram on the first sky pass, then adjust for dark undersides without clipping whites.",
                "composition", "Leave space ahead of the aircraft and keep room for wing tips during a bank."));
        result.put("schedule", portrait ? List.of(
            window(date, "Open-shade portraits", "15:35", "16:00", "15:25", "Meet, check the background and make safe portraits in soft light."),
            window(date, "Low-angle backlight and waterfront", "16:05", "16:40", "16:00", "Try warm edge light if available; use open shade if overcast."))
            : List.of(window(date, "Midday fast passes", "12:00", "13:15", "11:45", "Establish tracking settings and work across the display line."),
                window(date, "Afternoon formations and solo passes", "14:00", "15:40", "13:50", "Watch the display sequence and leave lead room in wider frames.")));
        result.put("preparation", portrait ? List.of("Confirm meeting point, wardrobe and consent.", "Scout an open-shade fallback with an uncluttered horizon.")
            : List.of("Check the organizer's display order and public viewing rules.", "Confirm the sight line and establish exposure before the first fast pass."));
        result.put("checklist", portrait ? List.of("Consent and contact details", "Walking route and fallback meeting point")
            : List.of("Hearing protection", "Drinking water", "Confirmed public viewing position"));
        result.put("shotPriorities", portrait ? List.of(
            priority("primary", "Backlit head-and-shoulders portrait", "A clean background is more useful than stronger flare."),
            priority("secondary", "Wide environmental portrait", "Stop down slightly to give the shoreline context."))
            : List.of(priority("primary", "Sharp banking jet against clean sky", "Short shutter times can raise ISO."),
                priority("secondary", "Approaching formation and clear aircraft spacing", "Use a wider framing margin for unexpected movement.")));
        result.put("limitations", List.of(OFFLINE, portrait
            ? "Proposed times are local availability; confirm light and cloud cover before shooting."
            : "Reconstructed teaching plan; confirmed date and exposure evidence are in the separate EXIF statistics."));
        result.put("questions", List.of());
        return Result.success(new ModelReply(Optional.of(JSON.toJson(Map.of("step", "final", "result", result))), List.of(), List.of()));
    }

    private static Map<String, String> mount(Map<String, String> labels, String camera, String lens, String role) {
        return Map.of("camera", Objects.requireNonNull(labels.get(camera), camera),
            "lens", Objects.requireNonNull(labels.get(lens), lens), "role", role);
    }

    private static Map<String, String> setting(String key, String value, String camera) {
        return Map.of("setting", key, "value", value, "camera", camera);
    }

    private static Map<String, Object> window(LocalDate date, String label, String start, String end, String arrival, String conditions) {
        return Map.of("label", label, "start", date + "T" + start, "end", date + "T" + end, "arrival", date + "T" + arrival,
            "conditions", conditions, "light", "mixed", "evidence", List.of());
    }

    private static Map<String, String> priority(String type, String description, String tradeOff) {
        return Map.of("priority", type, "description", description, "tradeOff", tradeOff);
    }

    private static List<Equipment> equipment() {
        return List.of(camera(A7, "658", "35.9", "23.9", "33"),
            camera(A7R, "723", "35.7", "23.8", "61"),
            lens("Sony", "FE 20-70mm F4 G", "488", "20", "70", "4", "4", "72", false),
            lens("Sony", PRIME, "281", "55", "55", "1.8", "1.8", "49", false),
            lens("Sony", "FE 35mm F1.4 ZA", "630", "35", "35", "1.4", "1.4", "72", false),
            lens("Tamron", SHORT, "855", "70", "180", "2.8", "2.8", "67", true),
            lens("Tamron", LONG, "1155", "50", "400", "4.5", "6.3", "67", true),
            lens("Viltrox", "AF 16mm F1.8 FE", "550", "16", "16", "1.8", "1.8", "77", false),
            equipment(BATTERY, "Sony", "NP-FZ100", "3", "83", Map.of("batteryType", "NP-FZ100", "capacityMah", "2280")),
            equipment(STORAGEMEDIA, "Sony", "SF-M128 128GB SDXC UHS-II", "2", "2",
                Map.of("format", "SDXC", "capacityGB", "128", "writeSpeedMBps", "150")));
    }

    private static Equipment camera(String model, String grams, String width, String height, String resolution) {
        return equipment(CAMERA, "Sony", model, "1", grams, Map.of("mount", "Sony E", "sensorWidthMm", width, "sensorHeightMm", height,
            "resolutionMP", resolution, "burstFps", "10", "stabilization", "true", "autofocusModes", "AF-S, AF-C, AF-A, DMF, MF",
            "batteryTypes", "NP-FZ100", "storageFormats", "SDHC, SDXC, CFexpress Type A"));
    }

    private static Equipment lens(String brand, String model, String grams, String min, String max, String wideAperture,
            String longAperture, String filter, boolean stabilized) {
        return equipment(LENS, brand, model, "1", grams, Map.of("mount", "Sony E", "minFocalMm", min, "maxFocalMm", max,
            "maxAperture", wideAperture, "maxApertureAtMaxFocal", longAperture, "filterDiameterMm", filter,
            "autofocus", "true", "stabilization", Boolean.toString(stabilized)));
    }

    private static Equipment equipment(EquipmentType type, String brand, String model, String count, String grams, Map<String, String> specs) {
        var values = new HashMap<>(specs);
        values.putAll(Map.of("brand", brand, "model", model, "quantity", count, "weightGrams", grams));
        return ok("validate manual form " + model, EquipmentForm.parse(type, UUID.randomUUID(), values, null));
    }

    private static boolean sameGear(Equipment a, Equipment b) {
        return a.type() == b.type() && a.brand().equalsIgnoreCase(b.brand()) && a.model().equalsIgnoreCase(b.model());
    }

    private static boolean preferencesMatch(Map<String, String> values) {
        return "en".equals(values.get("language")) && values.entrySet().containsAll(UserPreferenceDefaults.APPEARANCE.entrySet());
    }

    private static void validateRecognitionSamples(Map<String, String> env, Path assets) {
        var boundary = ImagePrivacyBoundary.fromEnvironment(env);
        for (String sample : List.of("sony2470gm2.png", "sonya7v-f04.png")) {
            var review = ok("open F04 image " + sample, boundary.open(assets.resolve("Equipments").resolve(sample), ImageReview.Kind.LABEL));
            System.out.println("F04 input: " + sample + " " + review.width() + " x " + review.height());
            boundary.discard(review);
        }
    }

    private static void verify(Map<String, String> env, Path assets) throws IOException {
        UUID id = demo(ok("list profiles after reopening", ProfileManager.availableProfiles(env))).orElseThrow().id();
        try (var reopened = new ProfileManager(env, id)) {
            var app = reopened.activeContext();
            var api = app.facade();
            require(Files.isDirectory(app.storageScope().directory()), "Demo profile directory is missing.");
            require(app.localization().language() == Language.ENGLISH && preferencesMatch(app.preferences().values()), "Demo preferences differ.");
            require(ok("reload avatar", reopened.avatar(id)).isPresent(), "Demo avatar is missing.");
            var items = ok("reload inventory", api.inventory().list("")).items();
            for (var expected : equipment()) {
                var actual = items.stream().filter(item -> sameGear(item, expected)).toList();
                require(actual.size() == 1, "Missing or repeated inventory entry: " + expected.model());
            }
            var ids = new HashSet<UUID>();
            for (var item : items) {
                ids.add(item.id());
                System.out.println("Gear: " + item.brand() + " " + item.model() + " (qty " + item.quantity() + ")");
            }
            var expected = Map.of(STREET, "DRAFT", PORTRAIT, "PLANNED", AVIATION, "COMPLETED");
            for (String title : List.of(STREET, PORTRAIT, AVIATION)) {
                var session = find(api.sessions(), title).orElseThrow();
                require(expected.get(title).equals(session.status()), "Wrong stage for " + title + ": " + session.status());
                require(session.requirements().filter(ShootRequirement::confirmed).isPresent(), "Missing requirement: " + title);
                if (title.equals(STREET)) require(session.plans().isEmpty(), "Draft contains a plan.");
                else {
                    require(session.profile().filter(EquipmentRequirementProfile::reviewed).isPresent(), "Missing confirmed equipment needs.");
                    var loadout = session.selectedLoadout().orElseThrow();
                    var plan = PlanningService.latest(ok("reload approved plans", api.planning().plans(session.id()))).orElseThrow();
                    require(plan.approved() && !plan.windows().isEmpty(), "Missing approved plan or schedule.");
                    for (var gear : List.of(loadout, plan.loadout().orElseThrow()))
                        require(gear.gear().stream().allMatch(item -> ids.contains(item.sourceGearId())), "Missing inventory reference in " + title);
                    System.out.println("Plan revision: " + plan.revision() + "; scheduled windows: " + plan.windows().size());
                }
                if (title.equals(AVIATION)) {
                    var stats = session.statistics().orElseThrow();
                    require(stats.usableCount() == RAWS.size() && session.reviews().isEmpty(), "Unexpected statistics or review.");
                    System.out.println("EXIF: " + stats.usableCount() + "/" + stats.importedCount() + " usable; " + stats.cameraCounts() + "; " + stats.lensCounts());
                }
                System.out.println("Session: " + session.status() + " | " + session.date() + " | " + session.id() + " | " + title);
            }
            validateRecognitionSamples(env, assets);
            System.out.println("Verified after restart: " + id + " at " + app.storageScope().directory() + "; inventory=" + items.size());
        }
    }

    private static <T> T ok(String action, Result<T> result) {
        if (!result.isSuccess()) throw new IllegalStateException(action + ": " + result.errors());
        return result.value().orElseThrow();
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
