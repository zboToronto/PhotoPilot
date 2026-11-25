package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import java.io.PrintStream;
import java.util.*;

/**
 * F09 command adapter. Shares PlanningService, LocationService, EnvironmentForm and EnvironmentPresentation with the
 * GUI, so both interfaces show the same evidence, windows and limitations for the same inputs. Device location is
 * requested only by the explicit "env device consent" command. Only an explicit record command saves a reviewed check.
 */
final class EnvironmentCommands {
    /** Process-local lookup results and the last inputs; never persisted and cleared when the user switches. */
    static final class State {
        private List<PlaceCandidate> places = List.of();
        private GeoLocation device;
        private UUID lastSession;
        private Map<String, String> lastValues;
        private PlaceCandidate lastChosen;
        private GeoLocation lastDevice;
        private UUID lookupSession;
        void forgetAssessment() { lastSession = null; lastValues = null; lastChosen = null; lastDevice = null; }
        void clear() { places = List.of(); device = null; lookupSession = null; forgetAssessment(); }
    }
    private static final Set<String> KEYS;
    static { var keys = new HashSet<>(EnvironmentForm.FIELDS); keys.add("pick"); keys.add("acceptApprox"); keys.add("place"); keys.add("device"); KEYS = Set.copyOf(keys); }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    private final Scanner input;
    private final int width;
    EnvironmentCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state, Scanner input) {
        this(app, output, localization, state, input, CapabilityTerminalReport.width(System.getenv()));
    }
    EnvironmentCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state, Scanner input, int width) {
        this.input = input; this.width = width;
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line.strip());
            String action = words.size() < 2 ? "help" : words.get(1).toLowerCase(Locale.ROOT);
            return switch (action) {
                case "help" -> { output.println(localization.text("env.cli.help")); output.println(localization.text("env.cli.reportHelp")); yield 0; }
                case "show" -> show(words);
                case "place" -> place(words);
                case "device" -> device(words);
                case "assess" -> assess(words);
                case "again" -> again(words);
                case "record" -> record(words);
                case "report" -> report(words);
                case "retry-ai", "retry" -> retry(words);
                case "menu" -> menu(words);
                default -> throw new LocalizedException("error.cli.option", action);
            };
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    // ---- Structured report of this process's latest assessment ---------------------------------------------------------

    /** The latest assessment made in this CLI process (env assess / env again / env retry-ai), with its session. */
    private Optional<EnvironmentAssessment> latest() {
        return state.lastSession == null ? Optional.empty() : app.planning().environmentFor(state.lastSession);
    }
    private EnvironmentTerminalReport terminal(EnvironmentAssessment assessment) {
        return new EnvironmentTerminalReport(app.analyzeEnvironment(assessment), localization, width);
    }

    /** {@code env report [overview|hourly|trend <metric>|sun|windows|ai|status|technical]}: one section, read-only. */
    private int report(List<String> words) {
        String section = words.size() < 3 ? "overview" : words.get(2).toLowerCase(Locale.ROOT);
        boolean trend = section.equals("trend");
        if (words.size() > (trend ? 4 : 3)) { output.println(localization.text("env.cli.reportHelp")); return 2; }
        var shown = latest();
        if (shown.isEmpty()) { output.println(localization.text("env.cli.noAssessment")); return 2; }
        return section(shown.get(), section, trend && words.size() == 4 ? words.get(3).toLowerCase(Locale.ROOT) : null) ? 0 : 2;
    }
    private boolean section(EnvironmentAssessment assessment, String section, String metric) {
        var report = terminal(assessment);
        List<String> lines;
        switch (section) {
            case "overview", "summary" -> lines = report.overview();
            case "hourly", "weather" -> lines = report.hourly();
            case "trend" -> {
                if (metric == null || !EnvironmentTerminalReport.TREND_METRICS.containsKey(metric)) {
                    output.println(localization.text("env.cli.trendHelp", String.join(" | ", EnvironmentTerminalReport.TREND_METRICS.keySet())));
                    return metric == null;
                }
                lines = report.trend(EnvironmentTerminalReport.TREND_METRICS.get(metric));
            }
            case "sun", "light" -> lines = report.sun();
            case "windows" -> lines = report.windows();
            case "ai" -> lines = report.ai();
            case "status" -> lines = report.status();
            case "technical", "all" -> lines = report.technical();
            default -> { output.println(localization.text("env.cli.reportHelp")); return false; }
        }
        lines.forEach(output::println);
        output.println();
        return true;
    }

    /**
     * {@code env retry-ai}: asks Gemini again about the retained evidence of this process's latest assessment (same
     * place, provider data, phases and availability; no location, weather or sun request). The shared service refuses
     * it, with the reason, when the evidence is stale, the inputs or requirements changed, or the AI already succeeded.
     */
    private int retry(List<String> words) {
        if (words.size() != 2) throw new LocalizedException("error.cli.option", String.join(" ", words.subList(2, words.size())));
        var shown = latest();
        if (shown.isEmpty()) { output.println(localization.text("env.cli.noAssessment")); return 2; }
        UUID session = state.lastSession;
        output.println(localization.text("env.ai.retrying"));
        var result = CliProgress.run(OperationKind.ENV_RETRY_AI, session, () -> app.retryEnvironmentInterpretation(session, shown.get()));
        if (!report(result)) return 2;
        var report = terminal(result.value().orElseThrow());
        // A retry whose interpretation failed again is reported as such (exit 2); the facts stay available.
        boolean failed = app.analyzeEnvironment(result.value().orElseThrow()).ai().status().failed();
        output.println(localization.text(failed ? "env.ai.retryFailed" : "env.ai.retried"));
        output.println();
        report.ai().forEach(output::println);
        output.println();
        report.status().forEach(output::println);
        return failed ? 2 : 0;
    }

    /**
     * {@code env menu}: numbered sections of the latest assessment for an interactive terminal; actions appear only when
     * they apply (AI retry only while it is possible). Without interactive input it lists the equivalent commands, so
     * scripts are never asked a question.
     */
    private int menu(List<String> words) {
        if (words.size() != 2) throw new LocalizedException("error.cli.option", String.join(" ", words.subList(2, words.size())));
        var shown = latest();
        if (shown.isEmpty()) { output.println(localization.text("env.cli.noAssessment")); return 2; }
        if (input == null) { output.println(localization.text("env.cli.reportHelp")); return 0; }
        while (true) {
            var current = latest();
            if (current.isEmpty()) { output.println(localization.text("env.cli.noAssessment")); return 2; }
            var refusal = app.planning().environmentRetryRefusal(state.lastSession, current.get());
            output.println(localization.text("env.cli.menuTitle"));
            for (String key : List.of("overview", "hourly", "sun", "windows", "ai")) output.println("  " + localization.text("env.cli.menu." + key));
            output.println("  " + (refusal.isEmpty() ? localization.text("env.cli.menu.retry")
                : localization.text("env.cli.menu.retryUnavailable", refusal.get().render(localization))));
            output.println("  " + localization.text("env.cli.menu.technical"));
            output.println("  " + localization.text("env.cli.menu.record"));
            output.println("  " + localization.text("env.cli.menu.exit"));
            String choice = prompt("env.cli.menuPrompt");
            switch (choice) {
                case "1" -> section(current.get(), "overview", null);
                case "2" -> section(current.get(), "hourly", null);
                case "3" -> section(current.get(), "sun", null);
                case "4" -> section(current.get(), "windows", null);
                case "5" -> section(current.get(), "ai", null);
                case "6" -> { if (refusal.isPresent()) output.println(localization.text("cli.error", refusal.get().render(localization))); else retry(List.of("env", "retry-ai")); }
                case "7" -> section(current.get(), "technical", null);
                case "8" -> record(List.of("env", "record", state.lastSession.toString()));
                case "0", "", "q", "exit" -> { return 0; }
                default -> output.println(localization.text("env.cli.menuInvalid"));
            }
        }
    }

    /**
     * Workflow milestone 04: adds this process's latest assessment of the session (made with env assess or env again)
     * to the session, after the user reviewed it; refused when there is none or it does not apply to the session.
     */
    private int record(List<String> words) {
        if (words.size() != 3) throw new LocalizedException("error.session.command");
        var session = open(words.get(2)); if (session == null) return 2;
        var shown = app.planning().environmentFor(session.id()).orElse(null);
        var saved = CliProgress.run(OperationKind.ENV_RECORD, session.id(), () -> app.planning().recordEnvironment(session, shown));
        if (!report(saved)) return 2;
        output.println(localization.text("env.recorded"));
        return 0;
    }

    private int show(List<String> words) {
        if (words.size() != 3) throw new LocalizedException("error.session.command");
        var session = open(words.get(2)); if (session == null) return 2;
        output.println(localization.text("env.cli.session", session.title(), session.id()));
        EnvironmentPresentation.context(session, localization).forEach(text -> output.println("  " + text));
        output.println(localization.text("env.cli.defaults"));
        EnvironmentForm.defaults(session).forEach((key, value) -> output.println("  " + localization.text("env.cli.field", key,
            value.isBlank() ? localization.text("env.cli.notSet") : value)));
        output.println(localization.text("env.cli.next"));
        return 0;
    }

    private int place(List<String> words) {
        if (words.size() < 3) throw new LocalizedException("error.session.command");
        return lookup(String.join(" ", words.subList(2, words.size())), null) ? 0 : 2;
    }

    private boolean lookup(String query, UUID session) {
        state.places = List.of(); state.lookupSession = session;
        output.println(localization.text("env.resolve.lookup"));
        var found = CliProgress.run(OperationKind.ENV_LOOKUP, () -> app.planning().location().resolve(query));
        if (!report(found)) { output.println(localization.text("env.resolve.recover")); return false; }
        state.places = found.value().orElseThrow();
        output.println(localization.text(state.places.size() == 1 ? "env.place.one" : "env.place.many", state.places.size()));
        for (int i = 0; i < state.places.size(); i++) output.println("  " + EnvironmentPresentation.candidate(state.places.get(i), i + 1, localization));
        output.println(localization.text("env.cli.placeHint"));
        return true;
    }

    private int device(List<String> words) {
        var location = app.planning().location();
        String choice = words.size() < 3 ? "" : words.get(2).toLowerCase(Locale.ROOT);
        if (choice.equals("deny")) { location.recordDenial(); state.device = null; output.println(localization.text("env.device.declined")); return 0; }
        if (!choice.equals("consent")) { output.println(localization.text("env.cli.deviceConsent")); return 2; }
        var found = location.current(true);
        if (!report(found)) return 2;
        state.device = found.value().orElseThrow();
        output.println(localization.text("env.device.used"));
        output.println(localization.text("env.cli.deviceHint"));
        return 0;
    }

    private int assess(List<String> words) {
        if (words.size() < 3) throw new LocalizedException("error.session.command");
        var session = open(words.get(2)); if (session == null) return 2;
        return prepare(session, EnvironmentForm.defaults(session), options(words.subList(3, words.size())), null, null);
    }

    private int again(List<String> words) {
        if (state.lastValues == null) { output.println(localization.text("env.cli.noLast")); return 2; }
        var session = app.sessions().open(state.lastSession);
        if (!report(session)) return 2;
        return prepare(session.value().orElseThrow(), new LinkedHashMap<>(state.lastValues), options(words.subList(2, words.size())),
            state.lastChosen, state.lastDevice);
    }

    private int prepare(PhotographySession session, Map<String,String> values, Map<String,String> options, PlaceCandidate chosen, GeoLocation device) {
        String place = options.remove("place"), pick = options.remove("pick"), accept = options.remove("acceptApprox");
        boolean useDevice = options.remove("device") != null;
        if (accept != null && !Set.of("yes","no").contains(accept)) throw new LocalizedException("error.cli.option", "acceptApprox");
        boolean coordinates = options.containsKey("latitude") || options.containsKey("longitude");
        boolean changedName = options.containsKey("location") && !options.get("location").equals(values.get("location"));
        if ((place != null || useDevice) && (options.containsKey("location") || coordinates || pick != null)
                || place != null && useDevice || pick != null && coordinates) throw new LocalizedException("env.resolve.mixed");
        // A confirmed point can be reused for the same explicit destination within its session.
        if (!coordinates && place == null && pick == null && !useDevice && session.id().equals(state.lastSession)
                && state.lastChosen != null && state.lastChosen.query().equals(options.get("location"))
                && (!state.lastChosen.estimated() || "yes".equals(accept) || chosen != null && accept == null)) {
            chosen = state.lastChosen;
            options.remove("location"); changedName = false;
            EnvironmentForm.apply(values, chosen);
        }
        // A failed edit must never make "env again" replay an older destination or availability.
        state.forgetAssessment();
        if (changedName || coordinates || place != null || useDevice) {
            chosen = null; device = null;
            if (changedName || place != null || useDevice) values.remove("zone");
            if (changedName) { values.remove("latitude"); values.remove("longitude"); }
        }
        app.planning().invalidateEnvironment(session.id());
        values.putAll(options);
        if (chosen != null && chosen.estimated() && "no".equals(accept)) { report(Result.failureKey("env.resolve.needAccept")); return 2; }
        if (useDevice) {
            device = state.device;
            if (device == null) { output.println(localization.text("env.cli.deviceConsent")); return 2; }
            values.put("location", device.label());
            values.put("latitude", EnvironmentForm.coordinate(device.latitude().orElseThrow()));
            values.put("longitude", EnvironmentForm.coordinate(device.longitude().orElseThrow()));
        }
        if (place != null) {
            if (state.lookupSession != null && !session.id().equals(state.lookupSession)) { report(Result.failureKey("env.cli.noPlaces")); return 2; }
            chosen = candidate(place);
            if (chosen == null || !accept(chosen, accept)) return 2;
            EnvironmentForm.apply(values, chosen);
        } else if (chosen == null && device == null && !coordinates && values.getOrDefault("latitude","").isBlank()
                && values.getOrDefault("longitude","").isBlank()) {
            return resolveAndAssess(session, values, pick, accept);
        } else if (pick != null) throw new LocalizedException("env.resolve.mixed");
        return run(session, values, chosen, device);
    }

    private int resolveAndAssess(PhotographySession session, Map<String,String> values, String pick, String acceptance) {
        String name = values.getOrDefault("location", "");
        if (name.isBlank() && input != null) name = prompt("env.resolve.askName");
        while (!name.isBlank()) {
            values.put("location", name);
            if (lookup(name, session.id())) {
                PlaceCandidate chosen = null;
                if (pick != null) chosen = candidate(pick);
                else if (state.places.size() == 1 && (input != null || state.places.getFirst().estimated())) chosen = state.places.getFirst();
                else if (input != null) {
                    while (chosen == null) {
                        String selection = prompt("env.resolve.pick");
                        if (selection.isBlank() || selection.equals("0")) return 2;
                        chosen = candidate(selection);
                    }
                }
                if (chosen == null) { output.println(localization.text("env.resolve.selection")); return 2; }
                boolean confirmDatabase = input != null && !chosen.estimated();
                if (accept(chosen, acceptance) && (!confirmDatabase || yes(prompt("env.resolve.confirmPrompt")))) {
                    EnvironmentForm.apply(values, chosen);
                    return run(session, values, chosen, null);
                }
            }
            if (input == null) return 2;
            String choice = prompt("env.resolve.menu");
            while (!List.of("0", "1", "2", "").contains(choice)) choice = prompt("env.resolve.menu");
            if (choice.equals("2")) {
                values.put("latitude", prompt("env.resolve.askLat")); values.put("longitude", prompt("env.resolve.askLon"));
                String zone = prompt("env.resolve.askZone");
                if (!zone.isBlank()) values.put("zone", zone);
                return run(session, values, null, null);
            }
            if (!choice.equals("1")) return 2;
            name = prompt("env.resolve.askName"); pick = null; acceptance = null;
        }
        report(Result.failureKey("env.location.error.label")); return 2;
    }

    private boolean accept(PlaceCandidate candidate, String option) {
        if (!candidate.estimated()) return true;
        output.println(localization.text("env.resolve.estimateHint"));
        if ("yes".equals(option)) return true;
        if (option == null && input != null && yes(prompt("env.resolve.acceptPrompt"))) return true;
        output.println(localization.text("env.resolve.needAccept"));
        return false;
    }
    private static boolean yes(String answer) { return Set.of("y","yes","是").contains(answer.toLowerCase(Locale.ROOT)); }
    private String prompt(String key) {
        output.println(localization.text(key)); output.flush();
        return input.hasNextLine() ? input.nextLine().strip() : "";
    }
    private int run(PhotographySession session, Map<String, String> values, PlaceCandidate chosen, GeoLocation device) {
        if (session.requirements().filter(ShootRequirement::confirmed).isPresent()) { values.put("activity", ""); values.put("goals", ""); }
        var parsed = device == null ? EnvironmentForm.parse(values, app.planning().location(), chosen)
            : EnvironmentForm.device(values, app.planning().location(), device);
        if (!report(parsed)) return 2;
        state.lastSession = session.id(); state.lastValues = new LinkedHashMap<>(values); state.lastChosen = chosen; state.lastDevice = device;
        // Pre-flight: date or time conflicts with the session are shown before any provider is contacted.
        var conflicts = EnvironmentForm.conflicts(session, values);
        if (!conflicts.isEmpty()) {
            output.println(localization.text("env.conflict.title"));
            conflicts.forEach(c -> output.println("  [!] " + c.render(localization)));
        }
        output.println(localization.text("env.running"));
        var result = CliProgress.run(OperationKind.ENV_ASSESS, () -> app.assessEnvironment(session.id(), parsed.value().orElseThrow()));
        if (!result.isSuccess()) { output.println(localization.text("env.cli.inputsKept")); report(result); return 2; }
        output.println();
        terminal(result.value().orElseThrow()).overview().forEach(output::println);
        output.println();
        output.println(localization.text("env.cli.again"));
        return 0;
    }

    private Map<String, String> options(List<String> tokens) {
        var values = new LinkedHashMap<String, String>();
        for (String token : tokens) {
            int split = token.indexOf('=');
            String key = split <= 0 ? token.toLowerCase(Locale.ROOT) : token.substring(0, split);
            if (split <= 0 && !key.equals("device")) throw new LocalizedException("error.cli.option", token);
            if (!KEYS.contains(key)) throw new LocalizedException("error.cli.option", token);
            if (values.putIfAbsent(key, split <= 0 ? "yes" : token.substring(split + 1)) != null) throw new LocalizedException("error.cli.repeated", key);
        }
        return values;
    }
    private PlaceCandidate candidate(String number) {
        if (state.places.isEmpty()) { output.println(localization.text("env.cli.noPlaces")); return null; }
        try {
            int index = Integer.parseInt(number) - 1;
            if (index >= 0 && index < state.places.size()) return state.places.get(index);
        } catch (NumberFormatException invalid) { /* reported below */ }
        report(Result.failureKey("env.cli.placeNumber", state.places.size()));
        return null;
    }
    private PhotographySession open(String id) {
        var session = app.sessions().open(SessionForm.id(id));
        return report(session) ? session.value().orElseThrow() : null;
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
}
