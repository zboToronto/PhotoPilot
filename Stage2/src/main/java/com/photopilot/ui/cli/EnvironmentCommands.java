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
 * requested only by the explicit "env device consent" command. Nothing is saved.
 */
final class EnvironmentCommands {
    /** Process-local lookup results and the last inputs; never persisted and cleared when the user switches. */
    static final class State {
        private List<PlaceCandidate> places = List.of();
        private GeoLocation device;
        private UUID lastSession;
        private Map<String, String> lastValues;
        private PlaceCandidate lastChosen;
        void clear() { places = List.of(); device = null; lastSession = null; lastValues = null; lastChosen = null; }
    }
    private static final Set<String> KEYS;
    static { var keys = new HashSet<>(EnvironmentForm.FIELDS); keys.add("place"); keys.add("device"); KEYS = Set.copyOf(keys); }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    EnvironmentCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state) {
        this.app = app; this.output = output; this.localization = localization; this.state = state;
    }

    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line.strip());
            String action = words.size() < 2 ? "help" : words.get(1).toLowerCase(Locale.ROOT);
            return switch (action) {
                case "help" -> { output.println(localization.text("env.cli.help")); yield 0; }
                case "show" -> show(words);
                case "place" -> place(words);
                case "device" -> device(words);
                case "assess" -> assess(words);
                case "again" -> again(words);
                default -> throw new LocalizedException("error.cli.option", action);
            };
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
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
        var found = app.planning().location().search(String.join(" ", words.subList(2, words.size())));
        if (!report(found)) { state.places = List.of(); return 2; }
        state.places = found.value().orElseThrow();
        output.println(localization.text(state.places.size() == 1 ? "env.place.one" : "env.place.many", state.places.size()));
        for (int i = 0; i < state.places.size(); i++) output.println("  " + EnvironmentPresentation.candidate(state.places.get(i), i + 1, localization));
        output.println(localization.text("env.cli.placeHint"));
        return 0;
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
        var values = EnvironmentForm.defaults(session);
        PlaceCandidate chosen = null;
        var options = options(words.subList(3, words.size()));
        if (options.containsKey("place")) {
            var candidate = candidate(options.remove("place")); if (candidate == null) return 2;
            values.put("location", candidate.displayName()); values.put("latitude", EnvironmentForm.coordinate(candidate.latitude()));
            values.put("longitude", EnvironmentForm.coordinate(candidate.longitude())); chosen = candidate;
        }
        if (options.containsKey("device")) {
            options.remove("device");
            if (state.device == null) { output.println(localization.text("env.cli.deviceConsent")); return 2; }
            values.put("location", state.device.label()); values.put("latitude", EnvironmentForm.coordinate(state.device.latitude().orElseThrow()));
            values.put("longitude", EnvironmentForm.coordinate(state.device.longitude().orElseThrow()));
        }
        values.putAll(options);
        return run(session, values, chosen);
    }
    /** Re-runs the last assessment with changed inputs (same session); changed coordinates drop the chosen lookup match. */
    private int again(List<String> words) {
        if (state.lastValues == null) { output.println(localization.text("env.cli.noLast")); return 2; }
        var session = app.sessions().open(state.lastSession);
        if (!report(session)) return 2;
        var options = options(words.subList(2, words.size()));
        if (options.containsKey("place") || options.containsKey("device")) throw new LocalizedException("error.cli.option", "place/device");
        var values = new LinkedHashMap<>(state.lastValues);
        boolean moved = options.containsKey("latitude") || options.containsKey("longitude");
        values.putAll(options);
        return run(session.value().orElseThrow(), values, moved ? null : state.lastChosen);
    }

    private int run(PhotographySession session, Map<String, String> values, PlaceCandidate chosen) {
        if (session.requirements().filter(ShootRequirement::confirmed).isPresent()) { values.put("activity", ""); values.put("goals", ""); }
        var parsed = EnvironmentForm.parse(values, app.planning().location(), chosen);
        if (!report(parsed)) return 2;
        state.lastSession = session.id(); state.lastValues = new LinkedHashMap<>(values); state.lastChosen = chosen;
        output.println(localization.text("env.running"));
        var result = app.assessEnvironment(session.id(), parsed.value().orElseThrow());
        if (!result.isSuccess()) { output.println(localization.text("env.cli.inputsKept")); report(result); return 2; }
        print(result.value().orElseThrow());
        return 0;
    }

    private void print(EnvironmentAssessment a) {
        section("env.card.summary", EnvironmentPresentation.summary(a, localization));
        var windows = EnvironmentPresentation.windows(a, localization);
        if (windows.isEmpty()) section("env.card.windows", List.of(localization.text("env.card.noWindows")));
        for (var card : windows) { output.println(); card.forEach(output::println); }
        section("env.card.ai", EnvironmentPresentation.interpretation(a, localization));
        section("env.card.evidence", EnvironmentPresentation.evidence(a, localization));
        section("env.card.phases", EnvironmentPresentation.phases(a, localization));
        section("env.card.rejected", EnvironmentPresentation.rejected(a, localization));
        section("env.card.limitations", EnvironmentPresentation.limitations(a, localization));
        output.println(localization.text("env.cli.again"));
    }
    private void section(String title, List<String> lines) {
        output.println(); output.println("== " + localization.text(title) + " ==");
        lines.forEach(output::println);
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
