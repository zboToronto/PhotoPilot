package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.*;

/**
 * F02 command adapter. Shares DiscoveryService (through the Facade), DiscoveryForm and DiscoveryPresentation with the
 * GUI, so both interfaces show the same ideas, checks and limitations for the same inputs. Idea numbers refer to the
 * last result and stay stable after a dismissal. Device location is requested only by "discover device consent".
 */
final class DiscoveryCommands {
    /** Process-local lookup results, last inputs and numbered ideas; never persisted and cleared on user switch. */
    static final class State {
        private List<PlaceCandidate> places = List.of();
        private GeoLocation device;
        private Map<String, String> lastValues;
        private PlaceCandidate lastChosen;
        private List<ActivitySuggestion> numbered = List.of();
        void clear() { places = List.of(); device = null; lastValues = null; lastChosen = null; numbered = List.of(); }
    }
    private static final Set<String> KEYS;
    static { var keys = new HashSet<>(DiscoveryForm.FIELDS); keys.add("place"); keys.add("device"); KEYS = Set.copyOf(keys); }
    private final PhotoPilotFacade app;
    private final PrintStream output;
    private final LocalizationService localization;
    private final State state;
    private final RequirementCommands.State requirement;
    DiscoveryCommands(PhotoPilotFacade app, PrintStream output, LocalizationService localization, State state, RequirementCommands.State requirement) {
        this.app = app; this.output = output; this.localization = localization; this.state = state; this.requirement = requirement;
    }

    int execute(String line) {
        try {
            var words = InventoryCommands.tokenize(line.strip());
            String action = words.size() < 2 ? "help" : words.get(1).toLowerCase(Locale.ROOT);
            return switch (action) {
                case "help" -> { output.println(localization.text("discover.cli.help")); yield 0; }
                case "gear" -> gear();
                case "place" -> place(words);
                case "device" -> device(words);
                case "run" -> run(words);
                case "again" -> again(words);
                case "list" -> list();
                case "show" -> show(words);
                case "select" -> select(words);
                case "save" -> save(words);
                case "dismiss" -> dismiss(words);
                case "saved" -> saved(words);
                default -> throw new LocalizedException("error.cli.option", action);
            };
        } catch (IllegalArgumentException error) { report(Result.failure(error)); return 2; }
    }

    private int gear() {
        var found = app.inventory().list("");
        if (!report(found)) return 2;
        DiscoveryPresentation.gear(found.value().orElseThrow(), localization).forEach(output::println);
        return 0;
    }
    private int place(List<String> words) {
        if (words.size() < 3) throw new LocalizedException("error.session.command");
        var found = app.discovery().location().search(String.join(" ", words.subList(2, words.size())));
        if (!report(found)) { state.places = List.of(); return 2; }
        state.places = found.value().orElseThrow();
        output.println(localization.text(state.places.size() == 1 ? "env.place.one" : "env.place.many", state.places.size()));
        for (int i = 0; i < state.places.size(); i++) output.println("  " + EnvironmentPresentation.candidate(state.places.get(i), i + 1, localization));
        output.println(localization.text("discover.cli.placeHint"));
        return 0;
    }
    private int device(List<String> words) {
        var location = app.discovery().location();
        String choice = words.size() < 3 ? "" : words.get(2).toLowerCase(Locale.ROOT);
        if (choice.equals("deny")) { location.recordDenial(); state.device = null; output.println(localization.text("env.device.declined")); return 0; }
        if (!choice.equals("consent")) { output.println(localization.text("discover.cli.deviceConsent")); return 2; }
        var found = location.current(true);
        if (!report(found)) return 2;
        state.device = found.value().orElseThrow();
        output.println(localization.text("env.device.used"));
        return 0;
    }

    private int run(List<String> words) {
        var values = DiscoveryForm.defaults(LocalDate.now());
        var options = options(words.subList(2, words.size()));
        PlaceCandidate chosen = null;
        if (options.containsKey("place")) {
            chosen = candidate(options.remove("place")); if (chosen == null) return 2;
            values.put("location", chosen.displayName()); values.put("latitude", EnvironmentForm.coordinate(chosen.latitude()));
            values.put("longitude", EnvironmentForm.coordinate(chosen.longitude()));
        }
        if (options.containsKey("device")) {
            options.remove("device");
            if (state.device == null) { output.println(localization.text("discover.cli.deviceConsent")); return 2; }
            values.put("location", state.device.label()); values.put("latitude", EnvironmentForm.coordinate(state.device.latitude().orElseThrow()));
            values.put("longitude", EnvironmentForm.coordinate(state.device.longitude().orElseThrow()));
        }
        values.putAll(options);
        return generate(values, chosen);
    }
    /** Re-runs with changed inputs; changed coordinates drop the chosen lookup match and its time zone. */
    private int again(List<String> words) {
        if (state.lastValues == null) { output.println(localization.text("discover.cli.noLast")); return 2; }
        var options = options(words.subList(2, words.size()));
        if (options.containsKey("place") || options.containsKey("device")) throw new LocalizedException("error.cli.option", "place/device");
        var values = new LinkedHashMap<>(state.lastValues);
        boolean moved = options.containsKey("latitude") || options.containsKey("longitude") || options.containsKey("location");
        values.putAll(options);
        return generate(values, moved ? null : state.lastChosen);
    }
    private int generate(Map<String, String> values, PlaceCandidate chosen) {
        var parsed = chosen != null ? DiscoveryForm.parse(values, app.discovery().location(), chosen)
            : DiscoveryForm.parse(values, app.discovery().location(), Optional.empty());
        if (!report(parsed)) return 2;
        state.lastValues = new LinkedHashMap<>(values); state.lastChosen = chosen;
        output.println(localization.text("discover.running"));
        var result = app.discover(parsed.value().orElseThrow());
        if (!result.isSuccess()) {
            output.println(localization.text("discover.failedInputsKept", String.join(" ", result.errors(localization))));
            return 2;
        }
        state.numbered = result.value().orElseThrow().ideas();
        print(result.value().orElseThrow());
        return 0;
    }

    private void print(DiscoveryResult r) {
        section("discover.card.context", DiscoveryPresentation.context(r.context(), localization));
        section("discover.card.summary", DiscoveryPresentation.summary(r, localization));
        if (!r.limitingFactors().isEmpty()) section("discover.card.limits", DiscoveryPresentation.limits(r, localization));
        for (var status : ActivitySuggestion.Feasibility.values()) {
            var group = r.with(status);
            if (group.isEmpty()) continue;
            section("discover.group." + status.name(), List.of());
            for (var idea : group) {
                output.println(DiscoveryPresentation.heading(idea, number(idea), localization));
                DiscoveryPresentation.idea(idea, localization).forEach(text -> output.println("  " + text));
                output.println();
            }
        }
        section("discover.card.ai", DiscoveryPresentation.ai(r, localization));
        section("discover.card.rejected", DiscoveryPresentation.rejected(r, localization));
        output.println(localization.text("discover.cli.next"));
    }
    private int list() {
        var latest = app.discovery().latest();
        if (latest.isEmpty() || state.numbered.isEmpty()) { output.println(localization.text("discover.cli.noResults")); return 2; }
        print(latest.get());
        return 0;
    }
    private int show(List<String> words) {
        var idea = idea(words, 3); if (idea == null) return 2;
        output.println(DiscoveryPresentation.heading(idea, number(idea), localization));
        DiscoveryPresentation.idea(idea, localization).forEach(text -> output.println("  " + text));
        return 0;
    }

    /** "discover select <n> <session-id>|new": unconfirmed F03 draft for review with the requirement commands. */
    private int select(List<String> words) {
        if (words.size() != 4) throw new LocalizedException("error.session.command");
        var idea = idea(words, 4); if (idea == null) return 2;
        if (idea.feasibility() == ActivitySuggestion.Feasibility.INFEASIBLE) { report(Result.failureKey("discover.error.infeasible")); return 2; }
        if (requirement.hasDraft()) { output.println(localization.text("discover.error.draftOpen")); return 2; }
        var session = target(words.get(3), idea); if (session == null) return 2;
        var draft = app.discovery().select(session.id(), idea);
        if (!report(draft)) return 2;
        if (!requirement.open(draft.value().orElseThrow(), session)) { output.println(localization.text("discover.error.draftOpen")); return 2; }
        output.println(localization.text("discover.selected", idea.subject(), session.title()));
        output.println(localization.text("discover.cli.handoff", session.id()));
        return new RequirementCommands(app, output, localization, requirement).execute("requirement draft");
    }
    private int save(List<String> words) {
        if (words.size() != 4) throw new LocalizedException("error.session.command");
        var idea = idea(words, 4); if (idea == null) return 2;
        if (idea.feasibility() == ActivitySuggestion.Feasibility.INFEASIBLE) { report(Result.failureKey("discover.error.infeasible")); return 2; }
        var session = target(words.get(3), idea); if (session == null) return 2;
        var saved = app.discovery().save(session, idea);
        if (!report(saved)) return 2;
        output.println(localization.text("discover.saved", idea.subject(), session.title()));
        output.println(localization.text("discover.cli.savedIn", session.id()));
        return 0;
    }
    private int dismiss(List<String> words) {
        if (words.size() != 3) throw new LocalizedException("error.session.command");
        var idea = idea(words, 3); if (idea == null) return 2;
        var removed = app.discovery().dismiss(idea.id());
        if (!report(removed)) return 2;
        output.println(localization.text("discover.dismissed", idea.subject()));
        return 0;
    }
    private int saved(List<String> words) {
        if (words.size() != 3) throw new LocalizedException("error.session.command");
        var found = app.discovery().saved(SessionForm.id(words.get(2)));
        if (!report(found)) return 2;
        DiscoveryPresentation.saved(found.value().orElseThrow(), localization).forEach(output::println);
        return 0;
    }

    /** An existing session, or "new": a Draft session (F01) named after the idea on its date. */
    private PhotographySession target(String word, ActivitySuggestion idea) {
        if (word.equalsIgnoreCase("new")) {
            String title = idea.subject().length() > 200 ? idea.subject().substring(0, 200) : idea.subject();
            var created = app.sessions().create(title, idea.start().map(s -> s.toLocalDate()).orElse(LocalDate.now()));
            if (!report(created)) return null;
            output.println(localization.text("discover.sessionCreated", created.value().orElseThrow().title(), created.value().orElseThrow().id()));
            return created.value().orElseThrow();
        }
        var session = app.sessions().open(SessionForm.id(word));
        return report(session) ? session.value().orElseThrow() : null;
    }
    /** The numbered idea from the last result, if it is still there (not dismissed). */
    private ActivitySuggestion idea(List<String> words, int expected) {
        if (words.size() < 3 || words.size() > expected) throw new LocalizedException("error.session.command");
        var latest = app.discovery().latest();
        if (latest.isEmpty() || state.numbered.isEmpty()) { output.println(localization.text("discover.cli.noResults")); return null; }
        try {
            int index = Integer.parseInt(words.get(2)) - 1;
            if (index >= 0 && index < state.numbered.size()) {
                var current = latest.get().find(state.numbered.get(index).id());
                if (current.isPresent()) return current.get();
                output.println(localization.text("discover.cli.gone", index + 1)); return null;
            }
        } catch (NumberFormatException invalid) { /* reported below */ }
        report(Result.failureKey("discover.cli.number", state.numbered.size()));
        return null;
    }
    private int number(ActivitySuggestion idea) {
        for (int i = 0; i < state.numbered.size(); i++) if (state.numbered.get(i).id().equals(idea.id())) return i + 1;
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
        if (state.places.isEmpty()) { output.println(localization.text("discover.cli.noPlaces")); return null; }
        try {
            int index = Integer.parseInt(number) - 1;
            if (index >= 0 && index < state.places.size()) return state.places.get(index);
        } catch (NumberFormatException invalid) { /* reported below */ }
        report(Result.failureKey("env.cli.placeNumber", state.places.size()));
        return null;
    }
    private void section(String title, List<String> lines) {
        output.println(); output.println("== " + localization.text(title) + " ==");
        lines.forEach(output::println);
    }
    private boolean report(Result<?> result) {
        if (result.isSuccess()) return true;
        result.errors(localization).forEach(e -> output.println(localization.text("cli.error", e))); return false;
    }
}
