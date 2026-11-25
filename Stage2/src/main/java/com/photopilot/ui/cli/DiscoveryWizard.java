package com.photopilot.ui.cli;

import com.photopilot.application.*;
import com.photopilot.application.DiscoveryGuide.Slot;
import com.photopilot.application.DiscoveryGuide.Stage;
import com.photopilot.application.DiscoveryInterests.Category;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

/**
 * "discover wizard": Guided Discovery for an interactive terminal in the GUI's three stages (Location &amp; Time,
 * Photography Preferences, Discover &amp; Review). It is a presentation layer only: validation is
 * {@link DiscoveryForm#parse} grouped by {@link DiscoveryGuide}, discovery is the Facade's {@code discover} call, place
 * lookup, device location, save, select, dismiss and saved ideas are the same DiscoveryService calls the discover
 * commands make, and every idea is shown through {@link DiscoveryPresentation}. Results update the shared
 * {@link DiscoveryCommands.State} exactly as {@code discover run} does, so the numbered commands keep working on them.
 * It only reads from the gated interactive Scanner; without one it prints the equivalent commands. End of input leaves
 * the wizard; the inputs stay for the next "discover wizard" until reset, a profile switch or the end of the process.
 */
final class DiscoveryWizard {
    /** The wizard's own inputs and shown results, kept in the CLI process between "discover wizard" runs. */
    static final class Memory {
        private Map<String, String> values;
        /** The lookup match the coordinates came from, its time zone (the zone hint) and whether they came from the device. */
        private PlaceCandidate chosen;
        private Optional<ZoneId> zone = Optional.empty();
        private boolean device;
        private Stage stage = Stage.LOCATION_TIME;
        private final EnumSet<Stage> visited = EnumSet.noneOf(Stage.class);
        /** The result this wizard showed, with the exact request, inputs and match it was made with. */
        private DiscoveryContext shownContext;
        private DiscoveryRequest shownRequest;
        private Map<String, String> shownValues;
        private PlaceCandidate shownChosen;
        void clear() { values = null; chosen = null; zone = Optional.empty(); device = false; stage = Stage.LOCATION_TIME; visited.clear(); forgetResults(); }
        void forgetResults() { shownContext = null; shownRequest = null; shownValues = null; shownChosen = null; }
        Map<String, String> values() { return values == null ? null : Collections.unmodifiableMap(values); }
        Stage stage() { return stage; }
    }

    /** Navigation words end the current prompts; the stage loop decides where they lead. */
    private enum Jump { BACK, NEXT, RESET, EXIT }
    private static final class Navigate extends RuntimeException {
        private final Jump jump;
        Navigate(Jump jump) { super(null, null, false, false); this.jump = jump; }
    }
    private enum Kind { VALUE, KEEP, CLEAR, BACK, NEXT, RESET, EXIT, HELP }
    private record Reply(Kind kind, String text) { }
    private record Item(String key, String label) { }
    /** Where an idea is continued or saved: an editable session, or (session null) a new draft session. */
    private record Target(PhotographySession session) { boolean created() { return session == null; } }

    private static final Set<String> YES = Set.of("y", "yes", "是"), NO = Set.of("n", "no", "否");
    private static final int RULE = 60;
    private final PhotoPilotFacade app;
    private final PrintStream out;
    private final LocalizationService l;
    private final DiscoveryCommands.State state;
    private final Memory memory;
    private final RequirementCommands.State requirement;
    private final Scanner input;
    private final TerminalStyle style;
    private final int width;
    private final boolean echo;
    private Map<String, String> values;
    private Result<GearInventory> inventory;
    private boolean distanceNoted, runRequested;
    /** A problem found when leaving a later stage: its input is asked again when its stage is shown. */
    private Slot pendingFix;
    private List<LocalizedMessage> failure = List.of();

    DiscoveryWizard(PhotoPilotFacade app, PrintStream out, LocalizationService localization, DiscoveryCommands.State state,
            RequirementCommands.State requirement, Scanner input, TerminalStyle style, int width, boolean echo) {
        this.app = Objects.requireNonNull(app); this.out = Objects.requireNonNull(out); this.l = Objects.requireNonNull(localization);
        this.state = Objects.requireNonNull(state); this.memory = state.wizard(); this.requirement = Objects.requireNonNull(requirement);
        this.input = input; this.style = style == null ? TerminalStyle.plain() : style;
        this.width = Math.max(CapabilityTerminalReport.MIN_WIDTH, Math.min(CapabilityTerminalReport.MAX_WIDTH, width)); this.echo = echo;
    }

    /** 0 whenever the user leaves (exit, end of input, after a hand-off); failures inside are shown and can be corrected. */
    int run() {
        if (input == null) { scripted(); return 0; }
        blank();
        emit(l.text("discover.cli.wizard.heading"), 0, 2, style::heading);
        if (memory.values != null) mark("[i]", style::muted, l.text("discover.cli.wizard.resumed"), 0);
        else if (state.lastValues() != null) {
            memory.values = new LinkedHashMap<>(state.lastValues());
            memory.chosen = state.lastChosen(); memory.zone = memory.chosen == null ? Optional.empty() : memory.chosen.zone();
            mark("[i]", style::muted, l.text("discover.cli.wizard.fromLast"), 0);
        } else memory.values = DiscoveryForm.defaults(LocalDate.now());
        values = memory.values;
        emit(l.text("discover.cli.wizard.intro"), 0, 0, style::muted);
        Stage stage = memory.stage;
        while (true) {
            memory.stage = stage;
            try {
                stage = switch (stage) {
                    case LOCATION_TIME -> locationTime();
                    case PREFERENCES -> preferences();
                    case REVIEW -> review();
                };
                if (stage == null) return 0; // handed off to Shooting Requirements
            } catch (Navigate navigation) {
                if (navigation.jump == Jump.RESET) { reset(); stage = Stage.LOCATION_TIME; continue; }
                blank();
                emit(l.text("discover.cli.wizard.left"), 0, 0, UnaryOperator.identity());
                return 0;
            }
        }
    }

    // ---- Stage 1: Location & Time ------------------------------------------------------------------------------------

    private Stage locationTime() {
        header(Stage.LOCATION_TIME);
        mark("[i]", style::muted, l.text("discover.inputs.privacy"), 0);
        if (pendingFix != null && pendingFix.stage() == Stage.LOCATION_TIME) { var slot = pendingFix; pendingFix = null; prompts(() -> checked(slot)); }
        else if (memory.visited.add(Stage.LOCATION_TIME))
            settle(Stage.LOCATION_TIME, prompts(() -> { checked(Slot.LOCATION); checked(Slot.DATE); checked(Slot.TIME); checked(Slot.DISTANCE); }));
        while (true) {
            locationReview();
            String device = l.text("discover.cli.wizard.menu.device");
            menu(List.of(new Item("1", l.text("discover.cli.wizard.menu.place")), new Item("2", l.text("discover.cli.wizard.menu.find")),
                new Item("3", app.discovery().location().deviceSupported() ? device : l.text("discover.cli.wizard.unavailable", device)),
                new Item("4", l.text("discover.cli.wizard.menu.dateTime")),
                new Item("5", l.text("discover.cli.wizard.menu.advanced")), new Item("6", l.text("discover.cli.wizard.menu.saved")),
                new Item("N", l.text("discover.cli.wizard.menu.next", l.text(Stage.PREFERENCES.titleKey()))),
                new Item("R", l.text("discover.cli.wizard.menu.reset")), new Item("X", l.text("discover.cli.wizard.menu.exit"))));
            String choice = choice();
            switch (choice) {
                case "1" -> prompts(() -> checked(Slot.LOCATION));
                case "2" -> prompts(this::findByName);
                case "3" -> prompts(this::device);
                case "4" -> prompts(() -> { checked(Slot.DATE); checked(Slot.TIME); });
                case "5" -> prompts(() -> { blank(); mark("[i]", style::muted, l.text("discover.cli.wizard.advancedNote"), 0); checked(Slot.COORDINATES); checked(Slot.DURATION); });
                case "6" -> { if (handedOff(this::browseSaved)) return null; }
                case "n", "next" -> { var first = problems(Stage.LOCATION_TIME); if (first == null) return Stage.PREFERENCES; prompts(() -> checked(first)); }
                case "b", "back" -> mark("[i]", style::muted, l.text("discover.cli.wizard.firstStep"), 0);
                default -> { if (!common(choice)) invalid(); }
            }
        }
    }

    private void locationReview() {
        blank();
        emit(l.text(Stage.LOCATION_TIME.titleKey()), 0, 2, style::strong);
        rows(List.of(
            row(l.text("discover.field.location"), place()),
            row(l.text("discover.request.label.date"), orNotSet(value("date"))),
            row(l.text("discover.request.label.time"), time()),
            row(l.text("discover.request.label.duration"), value("duration").isEmpty() ? notSet() : l.text("discover.cli.wizard.minutes", value("duration"))),
            row(l.text("discover.request.label.distance"), value("distance").isEmpty() ? notSet() : l.text("discover.request.distance", value("distance")))));
    }
    private String place() {
        String name = orNotSet(value("location"));
        String lat = value("latitude"), lon = value("longitude");
        if (lat.isEmpty() && lon.isEmpty()) return name + " · " + l.text("discover.cli.wizard.source.name");
        String source = memory.chosen != null ? "discover.cli.wizard.source.lookup" : memory.device ? "discover.cli.wizard.source.device" : "discover.cli.wizard.source.manual";
        return name + " · " + orNotSet(lat) + ", " + orNotSet(lon) + " · " + l.text(source);
    }
    private String time() {
        String span = orNotSet(value("from")) + "–" + orNotSet(value("until"));
        return window().map(w -> span + " · " + DiscoveryPresentation.window(w, l)).orElse(span);
    }
    /**
     * The length of the typed window, only while the request's rules accept it: equal times are an empty period for the
     * request, so they get the problem message, never a "24 hours, ends the next day" note.
     */
    private Optional<DiscoveryGuide.Window> window() {
        return slotProblems(Slot.TIME).isEmpty() ? DiscoveryGuide.window(value("from"), value("until")) : Optional.empty();
    }

    private void location() {
        set("location", field("location"));
        if (!value("location").isEmpty() && value("latitude").isEmpty() && value("longitude").isEmpty()
                && confirm(l.text("discover.cli.wizard.lookupOffer"), null, false)) findPlace(value("location"));
    }
    private void findByName() {
        if (value("location").isEmpty()) set("location", field("location"));
        if (value("location").isEmpty()) return;
        findPlace(value("location"));
    }
    /** Name lookup returns every match; the user picks one, or keeps the name alone (never resolved automatically). */
    private void findPlace(String name) {
        blank();
        emit(l.text("env.place.searching"), 0, 0, style::muted);
        var found = CliProgress.run(OperationKind.ENV_LOOKUP, () -> app.discovery().location().search(name));
        if (!found.isSuccess() || found.value().orElseThrow().isEmpty()) {
            if (found.isSuccess()) mark("[!]", style::warn, l.text("discover.cli.wizard.noMatch"), 0);
            found.errors(l).forEach(error -> mark("[!]", style::warn, error, 0));
            mark("[i]", style::muted, l.text("discover.place.manualFallback"), 0);
            return;
        }
        var places = found.value().orElseThrow();
        emit(l.text(places.size() == 1 ? "env.place.one" : "env.place.many", places.size()), 0, 0, UnaryOperator.identity());
        for (int i = 0; i < places.size(); i++) emit(EnvironmentPresentation.candidate(places.get(i), i + 1, l), 2, 3, UnaryOperator.identity());
        while (true) {
            emit(l.text("discover.cli.wizard.pickPlace", places.size()), 0, 2, style::strong);
            var reply = reply();
            if (navigation(reply)) continue;
            String text = reply.kind() == Kind.KEEP ? "0" : reply.text();
            if (text.equals("0")) return;
            int number = number(text);
            if (number >= 1 && number <= places.size()) { choose(places.get(number - 1)); return; }
            mark("[!]", style::warn, l.text("env.cli.placeNumber", places.size()), 0);
        }
    }
    private void choose(PlaceCandidate candidate) {
        values.put("location", candidate.displayName());
        values.put("latitude", EnvironmentForm.coordinate(candidate.latitude()));
        values.put("longitude", EnvironmentForm.coordinate(candidate.longitude()));
        memory.chosen = candidate; memory.zone = candidate.zone(); memory.device = false;
        mark("[OK]", style::ok, l.text("env.place.selected", candidate.displayName()), 0);
    }
    /** Device location only on this explicit choice, after consent; an unsupported device or a denial never asks. */
    private void device() {
        var location = app.discovery().location();
        blank();
        if (!location.deviceSupported()) { mark("[!]", style::warn, l.text("env.location.error.deviceUnsupported"), 0); return; }
        if (location.consentDenied()) { mark("[!]", style::warn, l.text("env.location.error.denied"), 0); return; }
        if (!confirm(l.text("discover.cli.wizard.deviceAllow"), l.text("discover.cli.wizard.deviceConsent"), false)) {
            location.recordDenial(); state.device(null);
            mark("[i]", style::muted, l.text("env.device.declined"), 0);
            return;
        }
        var found = location.current(true);
        if (!found.isSuccess()) { found.errors(l).forEach(error -> mark("[!]", style::warn, error, 0)); return; }
        var place = found.value().orElseThrow();
        state.device(place);
        values.put("location", place.label());
        values.put("latitude", EnvironmentForm.coordinate(place.latitude().orElseThrow()));
        values.put("longitude", EnvironmentForm.coordinate(place.longitude().orElseThrow()));
        memory.chosen = null; memory.zone = Optional.empty(); memory.device = true;
        mark("[OK]", style::ok, l.text("env.device.used"), 0);
    }
    /**
     * Typed coordinates replace a lookup match or device point, so its time zone hint no longer applies. The comparison
     * runs even when a navigation word (back, next, exit, end of input) ends the longitude prompt after the latitude
     * was already changed, so typed coordinates are never labelled as the earlier match.
     */
    private void coordinates() {
        String latitude = value("latitude"), longitude = value("longitude");
        try { set("latitude", field("latitude")); set("longitude", field("longitude")); }
        finally {
            if (!latitude.equals(value("latitude")) || !longitude.equals(value("longitude"))) { memory.chosen = null; memory.zone = Optional.empty(); memory.device = false; }
        }
    }
    private void times() {
        set("from", field("from", l.text("discover.field.from"), l.text("discover.cli.wizard.fromExample"), value("from")));
        set("until", field("until", l.text("discover.field.until"), l.text("discover.cli.wizard.untilExample"), value("until")));
        window().ifPresent(window -> { blank(); mark("[i]", style::muted, DiscoveryPresentation.window(window, l), 2); });
    }
    private void distance() {
        if (!distanceNoted) { distanceNoted = true; blank(); mark("[i]", style::muted, l.text("discover.cli.wizard.distanceNote", String.valueOf((int) DiscoveryRequest.MAX_DISTANCE_KM)), 0); }
        set("distance", field("distance"));
    }

    // ---- Stage 2: Photography Preferences ----------------------------------------------------------------------------

    private Stage preferences() {
        header(Stage.PREFERENCES);
        Jump early = null;
        if (pendingFix != null && pendingFix.stage() == Stage.PREFERENCES) { var slot = pendingFix; pendingFix = null; early = prompts(() -> checked(slot)); }
        else if (memory.visited.add(Stage.PREFERENCES)) {
            early = prompts(() -> { checked(Slot.LISTS); checked(Slot.NOTES); equipment(true); checked(Slot.HISTORY); });
            if (early != Jump.BACK) settle(Stage.PREFERENCES, early);
        }
        if (early == Jump.BACK) return Stage.LOCATION_TIME;
        while (true) {
            preferencesReview();
            boolean results = shownResult(false).isPresent();
            var items = new ArrayList<>(List.of(new Item("1", l.text("discover.cli.wizard.menu.interests")), new Item("2", l.text("discover.cli.wizard.menu.restrictions")),
                new Item("3", l.text("discover.cli.wizard.menu.equipment")), new Item("4", l.text("discover.cli.wizard.menu.history")),
                new Item("5", l.text("discover.cli.wizard.menu.saved"))));
            if (results) items.add(new Item("V", l.text("discover.cli.wizard.menu.view")));
            items.addAll(List.of(new Item("D", l.text("discover.cli.wizard.menu.discover")), new Item("B", l.text("discover.cli.wizard.menu.back")),
                new Item("R", l.text("discover.cli.wizard.menu.reset")), new Item("X", l.text("discover.cli.wizard.menu.exit"))));
            blank();
            mark("[i]", style::muted, l.text("discover.cli.wizard.aiNotice"), 0);
            menu(items);
            String choice = choice();
            switch (choice) {
                case "1" -> prompts(() -> checked(Slot.LISTS, this::interests));
                case "2" -> prompts(() -> { checked(Slot.LISTS, () -> set("avoid", field("avoid"))); checked(Slot.NOTES); });
                case "3" -> { inventory = null; prompts(() -> equipment(false)); }
                case "4" -> prompts(() -> checked(Slot.HISTORY));
                case "5" -> { if (handedOff(this::browseSaved)) return null; }
                case "v" -> { if (results) return Stage.REVIEW; invalid(); }
                case "d" -> { var next = discoverFrom(Stage.PREFERENCES); if (next != null) return next; }
                case "b", "back" -> { return Stage.LOCATION_TIME; }
                case "next" -> mark("[i]", style::muted, l.text("discover.cli.wizard.useDiscover"), 0);
                default -> { if (!common(choice)) invalid(); }
            }
        }
    }

    private void preferencesReview() {
        blank();
        emit(l.text(Stage.PREFERENCES.titleKey()), 0, 2, style::strong);
        var gear = inventory();
        rows(List.of(
            row(l.text("discover.request.label.interests"), interestsText()),
            row(l.text("discover.request.label.avoid"), orNotSet(value("avoid"))),
            row(l.text("discover.request.label.portability"), orNotSet(value("portability"))),
            row(l.text("discover.request.label.notes"), orNotSet(value("notes"))),
            row(l.text("discover.cli.wizard.label.equipment"), gear.isSuccess() ? DiscoveryPresentation.gearOverview(gear.value().orElseThrow(), l)
                : l.text("discover.gear.unreadable", String.join(" ", gear.errors(l)))),
            row(l.text("discover.request.label.history"), l.text(history() ? "discover.request.historyOn" : "discover.request.historyOff"))));
    }
    /** The interests entries in their request order, presets as their localized labels (the GUI's and the CLI's shared mapping). */
    private String interestsText() {
        var entries = DiscoveryForm.entries(value("interests"));
        return entries.isEmpty() ? l.text("discover.request.anyInterest") : String.join("; ", DiscoveryPresentation.interests(entries, l));
    }

    /**
     * Numbered preset categories (several at once) plus custom free text, composed into the one interests field. When
     * the user keeps the categories and the custom text, the field is left exactly as it was (never re-cased, reordered
     * or merged); only an actual change composes it again.
     */
    private void interests() {
        var split = DiscoveryInterests.split(value("interests"));
        Set<Category> selected = split.presets();
        boolean kept = false;
        blank();
        emit(l.text("discover.field.interests"), 0, 2, style::strong);
        categories(selected);
        while (true) {
            emit(l.text("discover.cli.wizard.categoryHint"), 2, 0, style::muted);
            var reply = reply();
            if (navigation(reply)) continue;
            if (reply.kind() == Kind.KEEP) { kept = true; break; }
            if (reply.kind() == Kind.CLEAR || reply.text().strip().equals("0")) { selected = EnumSet.noneOf(Category.class); break; }
            var chosen = categories(reply.text());
            if (chosen != null) { selected = chosen; break; }
            mark("[!]", style::warn, l.text("discover.cli.wizard.categoryInvalid", Category.values().length), 0);
        }
        // Stored before the custom prompt, so a navigation word there keeps the chosen categories.
        if (!kept) values.put("interests", DiscoveryInterests.compose(selected, split.customText()));
        String custom = field("interests", l.text("discover.cli.wizard.customInterests"), l.text("discover.cli.wizard.customExample"), split.customText());
        if (!kept || !custom.equals(split.customText())) values.put("interests", DiscoveryInterests.compose(selected, custom));
    }
    private void categories(Set<Category> selected) {
        var cells = new ArrayList<String>();
        for (var category : Category.values())
            cells.add((selected.contains(category) ? "[x] " : "[ ] ") + (category.ordinal() + 1) + " " + l.text(category.labelKey()));
        int cell = cells.stream().mapToInt(TextTable::width).max().orElse(1) + 3;
        int columns = Math.max(1, Math.min(3, (width - 2 + 3) / cell));
        for (int start = 0; start < cells.size(); start += columns) {
            var line = new StringBuilder("  ");
            for (int i = start; i < Math.min(cells.size(), start + columns); i++)
                line.append(i + 1 < Math.min(cells.size(), start + columns) ? TextTable.pad(cells.get(i), cell, TextTable.Align.LEFT) : cells.get(i));
            if (columns == 1) emit(line.toString().strip(), 2, 6, UnaryOperator.identity()); else out.println(TextTable.strip(line.toString()));
        }
    }
    /** The categories named by "1 3", "1,3", "1、3" or "1　3" (ideographic space); null when any entry is not a listed number. */
    private static Set<Category> categories(String text) {
        var chosen = EnumSet.noneOf(Category.class);
        for (String token : text.strip().split("[\\s\\u3000,，、;；]+")) {
            if (token.isEmpty()) continue;
            int number = number(token);
            if (number < 1 || number > Category.values().length) return null;
            chosen.add(Category.values()[number - 1]);
        }
        return chosen.isEmpty() ? null : chosen;
    }

    /** The read-only inventory: one summary line, the grouped list on request (or directly from the menu). */
    private void equipment(boolean ask) {
        blank();
        emit(l.text("discover.gear.title"), 0, 2, style::strong);
        var found = inventory();
        if (!found.isSuccess()) { mark("[!]", style::warn, l.text("discover.gear.unreadable", String.join(" ", found.errors(l))), 2); return; }
        var gear = found.value().orElseThrow();
        emit(DiscoveryPresentation.gearOverview(gear, l), 2, 0, UnaryOperator.identity());
        if (gear.items().isEmpty() || ask && !confirm(l.text("discover.cli.wizard.gearList"), null, false)) return;
        var groups = DiscoveryPresentation.gearGroups(gear, l);
        for (var count : DiscoveryPresentation.gearCounts(gear)) {
            var lines = groups.get(count.type());
            if (lines == null) continue;
            emit(l.text("discover.cli.wizard.gearGroup", DiscoveryPresentation.gearCategory(count.type(), l), DiscoveryPresentation.gearRecords(count, l),
                DiscoveryPresentation.gearUnits(count, l)), 2, 2, style::strong);
            lines.forEach(line -> emit(line, 4, 2, UnaryOperator.identity()));
        }
        emit(l.text("discover.gear.readOnly"), 2, 0, style::muted);
    }
    private Result<GearInventory> inventory() {
        if (inventory == null) inventory = app.inventory().list("");
        return inventory;
    }
    private boolean history() { return !"no".equalsIgnoreCase(value("history")); }
    private void askHistory() {
        blank();
        values.put("history", confirm(l.text("discover.cli.wizard.history"), l.text("discover.cli.wizard.historyNote", com.photopilot.service.DiscoveryService.MAX_HISTORY), history()) ? "yes" : "no");
    }

    // ---- Stage 3: Discover & Review ----------------------------------------------------------------------------------

    /** Validates every input; problems are shown by input and their stage is shown next. Null: discover now. */
    private Stage discoverFrom(Stage current) {
        var first = problems(Stage.REVIEW);
        if (first == null) { runRequested = true; return Stage.REVIEW; }
        if (first == Slot.GENERAL) return null;
        if (first.stage() == current) { prompts(() -> checked(first)); return null; }
        pendingFix = first;
        return first.stage();
    }

    private Stage review() {
        if (runRequested) {
            header(Stage.REVIEW);
            runRequested = false;
            var shown = discover();
            if (shown == null) return failed();
            render(shown);
        } else {
            // Nothing to review (never run, or replaced by a later command): one note, then Photography Preferences.
            boolean had = memory.shownContext != null;
            var shown = shownResult(true);
            if (shown.isEmpty()) { if (!had) { blank(); mark("[i]", style::muted, l.text("discover.cli.wizard.noResults"), 0); } return Stage.PREFERENCES; }
            header(Stage.REVIEW);
            render(shown.get());
        }
        return actions();
    }
    /** One request (the CLI is sequential); state is updated as "discover run" updates it. Null on failure. */
    private DiscoveryResult discover() {
        var parsed = parse();
        if (!parsed.isSuccess()) { failure = parsed.messages(); return null; }
        var request = parsed.value().orElseThrow();
        memory.forgetResults();
        state.requested(values, memory.chosen);
        blank();
        emit(l.text("discover.cli.wizard.running"), 0, 0, style::muted);
        out.flush();
        var result = CliProgress.run(OperationKind.DISCOVER, () -> app.discover(request));
        if (!result.isSuccess()) { failure = result.messages(); return null; }
        var found = result.value().orElseThrow();
        state.numbered(found.ideas());
        memory.shownContext = found.context(); memory.shownRequest = request; memory.shownValues = new LinkedHashMap<>(values); memory.shownChosen = memory.chosen;
        return found;
    }
    /**
     * The failure once: reasons about the whole request (Gemini unavailable, ...) in the headline, reasons about one
     * input (a date that is already past at the place, ...) below it by input, so E leads straight to that input.
     */
    private Stage failed() {
        blank();
        var grouped = DiscoveryGuide.group(failure);
        String general = DiscoveryPresentation.render(grouped.getOrDefault(Slot.GENERAL, List.of()), l);
        mark("[!]", style::warn, l.text("discover.failedInputsKept", general).strip(), 0);
        grouped.forEach((slot, messages) -> {
            if (slot != Slot.GENERAL) mark("[!]", style::warn, l.text("discover.cli.wizard.problem", slotLabel(slot), DiscoveryPresentation.render(messages, l)), 2);
        });
        while (true) {
            menu(List.of(new Item("T", l.text("discover.cli.wizard.menu.retry")), new Item("E", l.text("discover.cli.wizard.menu.edit")),
                new Item("B", l.text("discover.cli.wizard.menu.back")), new Item("X", l.text("discover.cli.wizard.menu.exit"))));
            String choice = choice();
            switch (choice) {
                case "t" -> { var next = discoverFrom(Stage.REVIEW); if (next != null) return next; }
                case "e" -> {
                    var slot = DiscoveryGuide.group(failure).keySet().stream().filter(s -> s != Slot.GENERAL).findFirst();
                    slot.ifPresent(s -> pendingFix = s);
                    return slot.map(Slot::stage).orElse(Stage.PREFERENCES);
                }
                case "b", "back" -> { return Stage.PREFERENCES; }
                default -> { if (!common(choice)) invalid(); }
            }
        }
    }

    private Stage actions() {
        while (true) {
            var shown = shownResult(true);
            if (shown.isEmpty()) return Stage.PREFERENCES;
            // Only the actions that have something to act on: no idea number prompt that no answer can satisfy.
            var r = shown.get();
            boolean details = !r.ideas().isEmpty() || !r.rejected().isEmpty(), usable = !r.usable().isEmpty(), ideas = !r.ideas().isEmpty();
            var items = new ArrayList<Item>();
            if (details) items.add(new Item("1", l.text("discover.cli.wizard.menu.details")));
            if (usable) items.addAll(List.of(new Item("2", l.text("discover.cli.wizard.menu.continue")), new Item("3", l.text("discover.cli.wizard.menu.save"))));
            if (ideas) items.add(new Item("4", l.text("discover.cli.wizard.menu.dismiss")));
            items.addAll(List.of(new Item("5", l.text("discover.cli.wizard.menu.browse")), new Item("6", l.text("discover.cli.wizard.menu.edit")),
                new Item("7", l.text("discover.cli.wizard.menu.again")), new Item("L", l.text("discover.cli.wizard.menu.list")),
                new Item("X", l.text("discover.cli.wizard.menu.exit"))));
            menu(items);
            String choice = choice();
            switch (choice) {
                case "1" -> { if (details) prompts(this::details); else invalid(); }
                case "2" -> { if (!usable) invalid(); else if (handedOff(this::continueIdea)) return null; }
                case "3" -> { if (usable) prompts(this::saveIdea); else invalid(); }
                case "4" -> { if (ideas) prompts(() -> { if (dismissIdea()) shownResult(true).ifPresent(this::remaining); }); else invalid(); }
                case "5" -> { if (handedOff(this::browseSaved)) return null; }
                case "6" -> { var stage = new Stage[1]; prompts(() -> stage[0] = editStage()); if (stage[0] != null) return stage[0]; }
                case "7", "a" -> { var next = discoverFrom(Stage.REVIEW); if (next != null) return next; }
                case "l" -> render(shown.get());
                case "b", "back" -> { return Stage.PREFERENCES; }
                default -> { if (!common(choice)) invalid(); }
            }
        }
    }

    /** The result this wizard showed, while it is still the latest one (a later "discover run" replaces it). */
    private Optional<DiscoveryResult> shownResult(boolean announce) {
        if (memory.shownContext == null) return Optional.empty();
        var latest = app.discovery().latest();
        if (latest.isPresent() && latest.get().context() == memory.shownContext) return latest;
        memory.forgetResults();
        if (announce) mark("[i]", style::muted, l.text("discover.cli.wizard.replaced"), 0);
        return Optional.empty();
    }
    private boolean stale() { return memory.shownValues != null && (!memory.shownValues.equals(values) || memory.shownChosen != memory.chosen); }

    private void render(DiscoveryResult r) {
        blank();
        emit(l.text("discover.cli.wizard.conditions"), 0, 2, style::strong);
        rows(DiscoveryPresentation.conditions(memory.shownRequest, l).stream().map(c -> row(c.label(), c.value())).toList());
        // Evidence gaps are printed once, as warnings next to the notes below, never only as a plain context line.
        var gaps = DiscoveryPresentation.gaps(r, l);
        evidence(r.context()).stream().filter(line -> !gaps.contains(line)).forEach(line -> emit(line, 2, 2, UnaryOperator.identity()));
        if (stale()) mark("[!]", style::warn, l.text("discover.cli.wizard.stale"), 0);

        blank();
        emit(l.text("discover.card.summary"), 0, 2, style::strong);
        emit(l.text("discover.summary.counts", r.feasible().size(), r.unverified().size(), r.infeasible().size(), r.rejected().size()), 2, 0, UnaryOperator.identity());
        if (r.feasible().isEmpty()) mark("[!]", style::warn, l.text(r.usable().isEmpty() ? "discover.summary.none" : "discover.summary.noneFeasible"), 2);
        if (!r.notices().isEmpty() || !gaps.isEmpty()) {
            blank();
            emit(l.text("discover.cli.wizard.notices"), 0, 2, style::strong);
            r.notices().forEach(notice -> mark("[i]", style::muted, notice.render(l), 2));
            gaps.forEach(gap -> mark("[!]", style::warn, gap, 2));
        }
        if (!r.limitingFactors().isEmpty()) {
            blank();
            emit(l.text("discover.card.limits"), 0, 2, style::strong);
            r.limitingFactors().forEach(limit -> mark("[!]", style::warn, limit.render(l), 2));
        }
        for (var status : Feasibility.values()) {
            var group = r.with(status);
            if (group.isEmpty()) continue;
            blank();
            emit(l.text("discover.group." + status.name()), 0, 2, style::heading);
            out.println(style.muted("-".repeat(Math.min(width, Math.max(10, Math.min(RULE, TextTable.width(l.text("discover.group." + status.name()))))))));
            group.forEach(this::idea);
        }
        blank();
        emit(l.text("discover.card.ai"), 0, 2, style::strong);
        var ai = DiscoveryPresentation.ai(r, l);
        for (int i = 0; i < ai.size(); i++) if (i > 0 || !ai.get(i).equals(l.text("discover.ai.label"))) emit(ai.get(i), 2, bullet(ai.get(i)), UnaryOperator.identity());
        if (!r.rejected().isEmpty()) { blank(); mark("[i]", style::muted, l.text("discover.cli.wizard.rejectedPointer", r.rejected().size()), 0); }
    }
    /**
     * What PhotoPilot used beyond the typed conditions: the place caveat the location row cannot carry (an approximate
     * lookup point, or a name without coordinates), the time zone source, gear, history and provider evidence.
     */
    private List<String> evidence(DiscoveryContext c) {
        var context = DiscoveryPresentation.context(c, l);
        String gear = c.inventoryReadable() ? l.text("discover.context.gear", c.gear().items().size(), c.usableLoadouts().size()) : l.text("discover.context.gearUnreadable");
        int from = context.indexOf(gear);
        if (from < 1) return context;
        var lines = new ArrayList<String>();
        if (!c.hasCoordinates() || c.location().map(GeoLocation::approximate).orElse(false)) lines.add(context.get(0));
        lines.add(context.get(1));
        lines.addAll(context.subList(from, context.size()));
        return lines;
    }
    /**
     * One idea: PhotoPilot's verdict, then what the AI proposed (its facts, reasons and own limitations), then what
     * PhotoPilot established: the check counts, the computed straight-line distance, each check with the findings that
     * must stay visible ({@link DiscoveryPresentation.CheckView#shown}: every finding of a check that did not pass, and
     * the cautions of a passing one, such as forecast warnings and travel limits, marked [!]), and PhotoPilot's notes
     * about the AI's statements. The remaining evidence of passing checks is in the details action, which prints every
     * finding. Nothing of one block is printed under the other's heading.
     */
    private void idea(ActivitySuggestion idea) {
        var view = DiscoveryPresentation.view(idea, l);
        String number = number(idea) + ". ";
        int in = TextTable.width(number);
        var plain = UnaryOperator.<String>identity();
        blank();
        emit(number + idea.subject(), 0, in, style::strong);
        mark(mark(idea.feasibility()), paint(idea.feasibility()), view.status() + " · " + view.statusDetail(), in);
        emit(l.text("discover.cli.wizard.suggested"), in, 2, style::strong);
        view.genre().ifPresent(text -> emit(text, in + 2, 2, plain));
        emit(view.where(), in + 2, 2, plain);
        emit(view.when(), in + 2, 2, plain);
        view.light().ifPresent(text -> emit(text, in + 2, 2, plain));
        view.equipment().ifPresent(text -> emit(text, in + 2, 2, plain));
        if (idea.feasibility() != Feasibility.INFEASIBLE) { // not offered for planning: its setting and reasons are in the details
            view.setting().ifPresent(text -> emit(text, in + 2, 2, plain));
            emit(view.rationale(), in + 2, 2, plain);
        }
        view.limitationsTitle().ifPresent(title -> { emit(title, in + 2, 2, plain); view.limitations().forEach(item -> emit("- " + item, in + 4, 2, plain)); });
        emit(l.text("discover.cli.wizard.checked"), in, 2, style::strong);
        emit(DiscoveryPresentation.verification(view, l), in + 2, 0, plain);
        view.distance().ifPresent(text -> emit(text, in + 2, 2, plain));
        for (var check : view.checks()) {
            mark(mark(check.status()), paint(check.status()), check.line(), in + 2);
            if (check.status() != Feasibility.FEASIBLE) check.shown().forEach(finding -> emit("- " + finding, in + 7, 2, plain));
            else check.shown().forEach(caution -> mark("[!]", style::warn, caution, in + 7));
        }
        view.notes().forEach(note -> mark("[i]", style::muted, note, in + 2));
    }
    /** After a dismissal: the new counts and the numbers still listed; the full list stays one "L" away. */
    private void remaining(DiscoveryResult r) {
        emit(l.text("discover.summary.counts", r.feasible().size(), r.unverified().size(), r.infeasible().size(), r.rejected().size()), 2, 0, UnaryOperator.identity());
        var numbers = r.ideas().stream().map(idea -> String.valueOf(number(idea))).toList();
        emit(numbers.isEmpty() ? l.text("discover.cli.wizard.noneLeft") : l.text("discover.cli.wizard.remaining", String.join(", ", numbers)), 2, 0, style::muted);
    }

    private void details() {
        var latest = shownResult(true);
        if (latest.isEmpty()) return;
        int rejected = latest.get().rejected().size();
        while (true) {
            blank();
            emit(rejected == 0 ? l.text("discover.cli.wizard.ideaNumber", state.numbered().size())
                : l.text("discover.cli.wizard.ideaOrRejected", state.numbered().size(), rejected), 0, 2, style::strong);
            var reply = reply();
            if (navigation(reply)) continue;
            String text = reply.kind() == Kind.KEEP ? "0" : reply.text().strip();
            if (text.equals("0")) return;
            if (rejected > 0 && text.equalsIgnoreCase("r")) {
                blank();
                emit(l.text("discover.card.rejected"), 0, 2, style::strong);
                DiscoveryPresentation.rejected(latest.get(), l).forEach(line -> emit(line, 2, 2, UnaryOperator.identity()));
                return;
            }
            var idea = idea(text);
            if (idea == null) continue;
            blank();
            emit(DiscoveryPresentation.heading(idea, number(idea), l), 0, 2, style::strong);
            for (String line : DiscoveryPresentation.idea(idea, l)) emit(line, 2, 2, UnaryOperator.identity());
            return;
        }
    }
    /** An idea number of the shown results (Enter or 0 cancels); dismissed ideas keep their number but are refused. */
    private ActivitySuggestion askIdea() {
        while (true) {
            blank();
            emit(l.text("discover.cli.wizard.ideaNumber", state.numbered().size()), 0, 2, style::strong);
            var reply = reply();
            if (navigation(reply)) continue;
            String text = reply.kind() == Kind.KEEP ? "0" : reply.text().strip();
            if (text.equals("0")) return null;
            var idea = idea(text);
            if (idea != null) return idea;
        }
    }
    private ActivitySuggestion idea(String text) {
        var numbered = state.numbered();
        int index = number(text) - 1;
        if (index >= 0 && index < numbered.size()) {
            var current = app.discovery().latest().flatMap(r -> r.find(numbered.get(index).id()));
            if (current.isPresent()) return current.get();
            mark("[!]", style::warn, l.text("discover.cli.gone", index + 1), 0);
            return null;
        }
        mark("[!]", style::warn, l.text("discover.cli.number", numbered.size()), 0);
        return null;
    }
    private int number(ActivitySuggestion idea) {
        var numbered = state.numbered();
        for (int i = 0; i < numbered.size(); i++) if (numbered.get(i).id().equals(idea.id())) return i + 1;
        return 0;
    }

    /** "discover select": an unconfirmed F03 draft (nothing is confirmed here); true once handed off. */
    private boolean continueIdea() {
        if (requirement.hasDraft()) { mark("[!]", style::warn, l.text("discover.error.draftOpen"), 0); return false; }
        var idea = askIdea();
        if (idea == null) return false;
        if (idea.feasibility() == Feasibility.INFEASIBLE) { mark("[!]", style::warn, l.text("discover.error.infeasible"), 0); return false; }
        var target = target(l.text("discover.cli.wizard.continueWhere"));
        if (target == null) return false;
        PhotographySession session; ShootRequirement draft;
        if (target.created()) {
            // DiscoveryService checks the idea before it creates the session, so a refusal leaves no empty session.
            var made = app.discovery().selectInNewSession(idea);
            if (!made.isSuccess()) { refused("discover.handoffFailed", made); return false; }
            session = made.value().orElseThrow().session(); draft = made.value().orElseThrow().draft();
            mark("[OK]", style::ok, l.text("discover.sessionCreated", session.title(), session.id()), 0);
        } else {
            session = target.session();
            var selected = app.discovery().select(session.id(), idea);
            if (!selected.isSuccess()) { refused("discover.handoffFailed", selected); return false; }
            draft = selected.value().orElseThrow();
        }
        return handOff(idea, session, draft);
    }
    private boolean handOff(ActivitySuggestion idea, PhotographySession session, ShootRequirement draft) {
        if (!requirement.open(draft, session)) { mark("[!]", style::warn, l.text("discover.error.draftOpen"), 0); return false; }
        blank();
        mark("[OK]", style::ok, l.text("discover.selected", idea.subject(), session.title()), 0);
        emit(l.text("discover.cli.handoff", session.id()), 0, 0, UnaryOperator.identity());
        blank();
        // The F03 draft exactly as "requirement draft" prints it, re-emitted wrapped to the width (each line keeps its own
        // indentation, continuation lines hang under it); "discover select" and "requirement draft" print it unchanged.
        var draftText = new ByteArrayOutputStream();
        new RequirementCommands(app, new PrintStream(draftText, true, StandardCharsets.UTF_8), l, requirement).execute("requirement draft");
        draftText.toString(StandardCharsets.UTF_8).lines().forEach(line -> emit(line, 0, 2, UnaryOperator.identity()));
        return true;
    }
    /** "discover save": the idea is stored in the chosen session (or a new draft session in one atomic insert). */
    private void saveIdea() {
        var idea = askIdea();
        if (idea == null) return;
        if (idea.feasibility() == Feasibility.INFEASIBLE) { mark("[!]", style::warn, l.text("discover.error.infeasible"), 0); return; }
        var target = target(l.text("discover.cli.wizard.saveWhere"));
        if (target == null) return;
        PhotographySession session;
        if (target.created()) {
            var stored = app.discovery().saveInNewSession(idea);
            if (!stored.isSuccess()) { refused("discover.saveFailed", stored); return; }
            session = stored.value().orElseThrow();
            mark("[OK]", style::ok, l.text("discover.sessionCreated", session.title(), session.id()), 0);
        } else {
            session = target.session();
            var saved = app.discovery().save(session, idea);
            if (!saved.isSuccess()) { refused("discover.saveFailed", saved); return; }
        }
        mark("[OK]", style::ok, l.text("discover.saved", idea.subject(), session.title()), 0);
        emit(l.text("discover.cli.savedIn", session.id()), 0, 0, UnaryOperator.identity());
    }
    /** "discover dismiss": removed from these results only; saved records are unchanged. */
    private boolean dismissIdea() {
        var idea = askIdea();
        if (idea == null) return false;
        var removed = app.discovery().dismiss(idea.id());
        if (!removed.isSuccess()) { removed.errors(l).forEach(error -> mark("[!]", style::warn, error, 0)); return false; }
        mark("[OK]", style::ok, l.text("discover.dismissed", idea.subject()), 0);
        return true;
    }
    private Stage editStage() {
        while (true) {
            menu(List.of(new Item("1", l.text(Stage.LOCATION_TIME.titleKey())), new Item("2", l.text(Stage.PREFERENCES.titleKey())),
                new Item("0", l.text("discover.cli.wizard.menu.cancel"))));
            String choice = choice();
            switch (choice) {
                case "1" -> { return Stage.LOCATION_TIME; }
                case "2" -> { return Stage.PREFERENCES; }
                case "", "0", "b", "back" -> { return null; }
                default -> { if (!common(choice)) invalid(); }
            }
        }
    }

    /** Editable (Draft/Planned) sessions, a new draft session, or cancel (null). */
    private Target target(String question) {
        var found = editableSessions();
        var sessions = found == null ? List.<PhotographySession>of() : found; // unreadable list: a new draft session is still offered
        while (true) {
            blank();
            emit(question, 0, 2, style::strong);
            if (sessions.isEmpty()) emit(l.text("discover.target.none"), 2, 0, style::muted);
            var items = new ArrayList<Item>();
            for (int i = 0; i < sessions.size(); i++) items.add(new Item(String.valueOf(i + 1), option(sessions.get(i))));
            items.add(new Item("N", l.text("discover.target.new")));
            items.add(new Item("0", l.text("discover.cli.wizard.menu.cancel")));
            list(items);
            String choice = choice();
            if (choice.isEmpty() || choice.equals("0") || choice.equals("b") || choice.equals("back")) return null;
            if (choice.equals("n")) return new Target(null);
            int number = number(choice);
            if (number >= 1 && number <= sessions.size()) return new Target(sessions.get(number - 1));
            if (!common(choice)) invalid();
        }
    }
    private List<PhotographySession> editableSessions() {
        var found = app.sessions().search("");
        if (!found.isSuccess()) { found.errors(l).forEach(error -> mark("[!]", style::warn, error, 0)); return null; }
        return found.value().orElseThrow().stream().filter(s -> s.state().canEditPlan()).toList();
    }
    private String option(PhotographySession s) {
        return l.text("requirement.sessionOption", s.title(), s.date(), l.text("session.state." + s.status()));
    }

    /** Saved ideas of editable sessions (read only, no AI request); true after a saved idea was continued. */
    private boolean browseSaved() {
        while (true) {
            var sessions = editableSessions();
            if (sessions == null) return false;
            blank();
            emit(l.text("discover.cli.wizard.savedTitle"), 0, 2, style::strong);
            if (sessions.isEmpty()) { emit(l.text("discover.target.none"), 2, 0, style::muted); return false; }
            var items = new ArrayList<Item>();
            for (int i = 0; i < sessions.size(); i++)
                items.add(new Item(String.valueOf(i + 1), option(sessions.get(i)) + " · " + l.text("discover.cli.wizard.savedCount", sessions.get(i).savedIdeas().size())));
            items.add(new Item("0", l.text("discover.cli.wizard.menu.return")));
            list(items);
            String choice = choice();
            if (choice.isEmpty() || choice.equals("0") || choice.equals("b") || choice.equals("back")) return false;
            int number = number(choice);
            if (number >= 1 && number <= sessions.size()) { if (savedIdeas(sessions.get(number - 1))) return true; continue; }
            if (!common(choice)) invalid();
        }
    }
    private boolean savedIdeas(PhotographySession session) {
        var found = app.discovery().saved(session.id());
        if (!found.isSuccess()) { found.errors(l).forEach(error -> mark("[!]", style::warn, error, 0)); return false; }
        var ideas = found.value().orElseThrow();
        while (true) {
            blank();
            emit(l.text("discover.saved.title", session.title()), 0, 2, style::strong);
            if (ideas.isEmpty()) { emit(l.text("discover.saved.none"), 2, 0, style::muted); return false; }
            for (int i = 0; i < ideas.size(); i++) {
                var idea = ideas.get(i);
                var view = DiscoveryPresentation.view(idea, l);
                mark(mark(idea.feasibility()), paint(idea.feasibility()), l.text("discover.saved.item", i + 1, idea.subject(), view.status(),
                    idea.suggestedAt().map(t -> t.toString().substring(0, 10)).orElse(l.text("discover.saved.unknownDate"))), 2);
                emit(view.where(), 7, 2, UnaryOperator.identity());
                emit(view.when(), 7, 2, UnaryOperator.identity());
                idea.limitations().forEach(item -> emit("- " + item, 7, 2, UnaryOperator.identity()));
            }
            boolean continuable = ideas.stream().anyMatch(idea -> idea.feasibility() != Feasibility.INFEASIBLE);
            var items = new ArrayList<Item>();
            if (continuable) items.add(new Item("C", l.text("discover.cli.wizard.menu.continueSaved")));
            items.add(new Item("0", l.text("discover.cli.wizard.menu.return")));
            list(items);
            String choice = choice();
            if (choice.isEmpty() || choice.equals("0") || choice.equals("b") || choice.equals("back")) return false;
            if (continuable && choice.equals("c")) { if (continueSaved(session, ideas)) return true; continue; }
            if (!common(choice)) invalid();
        }
    }
    private boolean continueSaved(PhotographySession session, List<ActivitySuggestion> ideas) {
        if (requirement.hasDraft()) { mark("[!]", style::warn, l.text("discover.error.draftOpen"), 0); return false; }
        while (true) {
            blank();
            emit(l.text("discover.cli.wizard.savedNumber", ideas.size()), 0, 2, style::strong);
            var reply = reply();
            if (navigation(reply)) continue;
            String text = reply.kind() == Kind.KEEP ? "0" : reply.text().strip();
            if (text.equals("0")) return false;
            int number = number(text);
            if (number < 1 || number > ideas.size()) { mark("[!]", style::warn, l.text("discover.cli.wizard.savedNumberInvalid", ideas.size()), 0); continue; }
            var idea = ideas.get(number - 1);
            if (idea.feasibility() == Feasibility.INFEASIBLE) { mark("[!]", style::warn, l.text("discover.error.infeasible"), 0); continue; }
            var selected = app.discovery().select(session.id(), idea);
            if (!selected.isSuccess()) { refused("discover.handoffFailed", selected); return false; }
            return handOff(idea, session, selected.value().orElseThrow());
        }
    }

    // ---- Validation (DiscoveryForm through DiscoveryGuide) -----------------------------------------------------------

    private Result<DiscoveryRequest> parse() { return parse(values); }
    private Result<DiscoveryRequest> parse(Map<String, String> input) {
        return memory.chosen != null ? DiscoveryForm.parse(input, app.discovery().location(), memory.chosen)
            : DiscoveryForm.parse(input, app.discovery().location(), memory.zone);
    }
    /**
     * One input's problems by the shared rules alone ({@link DiscoveryForm#parse}, then the request's own validation),
     * as if the inputs not asked yet were valid. Parsing stops before the request's validation while any input is still
     * empty or malformed, which would hide this input's window, range or list problems until Next; so the other inputs
     * that have problems are replaced, in a copy only, by placeholders that the rules accept and that cannot add a
     * problem of their own to this input. No rule is repeated here.
     */
    private List<LocalizedMessage> slotProblems(Slot slot) {
        var probe = new LinkedHashMap<>(values);
        var parsed = parse(probe);
        if (parsed.isSuccess()) return List.of();
        var own = parsed.messages().stream().filter(m -> DiscoveryGuide.slot(m) == slot).toList();
        var others = DiscoveryGuide.group(parsed.messages()).keySet().stream().filter(s -> s != slot && s != Slot.GENERAL).toList();
        if (!own.isEmpty() || others.isEmpty()) return own;
        // One round is enough: the placeholders parse, so the request's validation (or this input's own parsing) runs.
        others.forEach(other -> placeholder(other, probe));
        return parse(probe).messages().stream().filter(m -> DiscoveryGuide.slot(m) == slot).toList();
    }
    /**
     * Accepted stand-ins for another input while one input is checked. Equal times are deliberately an (empty) window:
     * the request then reports only a time problem and skips comparing the longest activity with a made-up window.
     */
    private static void placeholder(Slot slot, Map<String, String> probe) {
        switch (slot) {
            case LOCATION -> probe.put("location", "x");
            case COORDINATES -> { probe.put("latitude", ""); probe.put("longitude", ""); }
            case DATE -> probe.put("date", LocalDate.now().toString());
            case TIME -> { probe.put("from", "00:00"); probe.put("until", "00:00"); }
            case DURATION -> probe.put("duration", "");
            case DISTANCE -> probe.put("distance", "0");
            case LISTS -> { probe.put("interests", ""); probe.put("avoid", ""); }
            case NOTES -> { probe.put("portability", ""); probe.put("notes", ""); }
            case HISTORY -> probe.put("history", "yes");
            case GENERAL -> { }
        }
    }
    /** Asks a slot's inputs, then shows only that slot's problems and asks again until there are none. */
    private void checked(Slot slot) { checked(slot, () -> ask(slot)); }
    private void checked(Slot slot, Runnable first) {
        if (slot == Slot.GENERAL) return; // concerns the whole request: shown, but there is no input to ask again
        first.run();
        while (true) {
            var messages = slotProblems(slot);
            if (messages.isEmpty()) return;
            blank();
            mark("[!]", style::warn, l.text("discover.cli.wizard.problem", slotLabel(slot), DiscoveryPresentation.render(messages, l)), 0);
            ask(slot);
        }
    }
    /**
     * The end of a stage's first pass: a problem that appears only once every input of the stage is filled in (for
     * example a longest activity that no longer fits a shorter window) is asked again before the stage's menu. After
     * "next" or "back" skipped the remaining questions the problems are only listed, so the skip is respected.
     */
    private void settle(Stage stage, Jump skipped) {
        if (skipped != null) { problems(stage); return; }
        for (int round = 0; round < Slot.values().length; round++) {
            var first = problems(stage);
            if (first == null || first == Slot.GENERAL || first.stage() != stage) return;
            if (prompts(() -> checked(first)) != null) return;
        }
    }
    private void ask(Slot slot) {
        switch (slot) {
            case LOCATION -> location();
            case COORDINATES -> coordinates();
            case DATE -> set("date", field("date"));
            case TIME -> times();
            case DURATION -> set("duration", field("duration"));
            case DISTANCE -> distance();
            case LISTS -> { interests(); set("avoid", field("avoid")); }
            case NOTES -> { set("portability", field("portability")); notes(); }
            case HISTORY -> askHistory();
            case GENERAL -> { }
        }
    }
    /** The notes input under the wizard's (and the GUI's) term "Other restrictions"; the request field is unchanged. */
    private void notes() {
        set("notes", field("notes", l.text("discover.cli.wizard.field.notes"), l.text("discover.prompt.notes"), value("notes")));
    }
    /** The problems that keep the user in {@code stage}, printed by input; returns the first input's slot, or null. */
    private Slot problems(Stage stage) {
        var problems = DiscoveryGuide.problems(parse(), stage);
        if (problems.isEmpty()) return null;
        var grouped = DiscoveryGuide.group(problems);
        blank();
        mark("[!]", style::warn, l.text("discover.cli.wizard.fix"), 0);
        grouped.forEach((slot, messages) -> mark("[!]", style::warn, l.text("discover.cli.wizard.problem", slotLabel(slot), DiscoveryPresentation.render(messages, l)), 2));
        return grouped.keySet().iterator().next();
    }
    private String slotLabel(Slot slot) { return l.text("discover.cli.wizard.slot." + slot.name()); }

    // ---- Prompts ---------------------------------------------------------------------------------------------------

    /** One input: label (required marker), example and current value; Enter keeps it, "-" clears an optional one. */
    private String field(String key) {
        return field(key, l.text("discover.field." + key), l.text("discover.prompt." + key), value(key));
    }
    private String field(String key, String label, String example, String current) {
        boolean required = DiscoveryGuide.required(key);
        while (true) {
            blank();
            emit(required ? l.text("discover.cli.wizard.required", label) : label, 0, 2, style::strong);
            emit(example, 2, 0, style::muted);
            emit("[" + (current.isEmpty() ? notSet() : current) + "]", 2, 3, UnaryOperator.identity());
            var reply = reply();
            if (navigation(reply)) continue;
            switch (reply.kind()) {
                case KEEP -> { return current; }
                case CLEAR -> {
                    if (!required) return "";
                    mark("[!]", style::warn, l.text("discover.cli.wizard.requiredValue", label), 0);
                }
                default -> { return reply.text(); }
            }
        }
    }
    private boolean confirm(String question, String note, boolean byDefault) {
        while (true) {
            emit(l.text(byDefault ? "discover.cli.wizard.defaultYes" : "discover.cli.wizard.defaultNo", question), 0, 2, style::strong);
            if (note != null) emit(note, 2, 0, style::muted);
            var reply = reply();
            if (navigation(reply)) continue;
            if (reply.kind() == Kind.KEEP) return byDefault;
            String answer = reply.text().strip().toLowerCase(Locale.ROOT);
            if (YES.contains(answer)) return true;
            if (NO.contains(answer)) return false;
            mark("[!]", style::warn, l.text("discover.cli.wizard.yesNo"), 0);
        }
    }
    private boolean confirmReset() {
        emit(l.text("discover.cli.wizard.defaultNo", l.text("discover.cli.wizard.resetConfirm")), 0, 2, style::strong);
        return YES.contains(read().toLowerCase(Locale.ROOT));
    }
    /** Runs prompts that a navigation word may end early: back and next return to the caller, reset and exit propagate. */
    private Jump prompts(Runnable prompts) {
        try { prompts.run(); return null; }
        catch (Navigate navigation) {
            if (navigation.jump == Jump.BACK || navigation.jump == Jump.NEXT) return navigation.jump;
            throw navigation;
        }
    }
    private boolean handedOff(BooleanSupplier action) {
        var done = new boolean[1];
        prompts(() -> done[0] = action.getAsBoolean());
        return done[0];
    }
    /** Navigation words: true when the prompt should be asked again (help shown, reset declined). */
    private boolean navigation(Reply reply) {
        switch (reply.kind()) {
            case BACK -> throw new Navigate(Jump.BACK);
            case NEXT -> throw new Navigate(Jump.NEXT);
            case EXIT -> throw new Navigate(Jump.EXIT);
            case RESET -> { if (confirmReset()) throw new Navigate(Jump.RESET); return true; }
            case HELP -> { help(); return true; }
            default -> { return false; }
        }
    }
    /** Menu words shared by every menu: exit leaves, reset asks first, help explains. True when handled here. */
    private boolean common(String choice) {
        switch (choice) {
            case "x", "exit" -> throw new Navigate(Jump.EXIT);
            case "r", "reset" -> { if (confirmReset()) throw new Navigate(Jump.RESET); return true; }
            case "help" -> { help(); return true; }
            default -> { return false; }
        }
    }
    private String choice() {
        var reply = reply();
        return switch (reply.kind()) {
            case KEEP -> "";
            case BACK -> "back";
            case NEXT -> "next";
            case RESET -> "reset";
            case EXIT -> "exit";
            case HELP -> "help";
            default -> reply.text().strip().toLowerCase(Locale.ROOT);
        };
    }
    private Reply reply() {
        String text = read();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) return new Reply(Kind.VALUE, text.substring(1, text.length() - 1));
        if (text.isEmpty()) return new Reply(Kind.KEEP, "");
        if (text.equals("-")) return new Reply(Kind.CLEAR, "");
        return new Reply(switch (text.toLowerCase(Locale.ROOT)) {
            case "back", "返回", "上一步" -> Kind.BACK;
            case "next", "下一步" -> Kind.NEXT;
            case "reset", "重置" -> Kind.RESET;
            case "exit", "quit", "退出" -> Kind.EXIT;
            case "help", "?", "帮助" -> Kind.HELP;
            default -> Kind.VALUE;
        }, text);
    }
    /** One answer line; end of input leaves the wizard (it never waits or loops on a closed input). */
    private String read() {
        out.print("> ");
        out.flush();
        if (!input.hasNextLine()) { out.println(); throw new Navigate(Jump.EXIT); }
        String line = input.nextLine();
        if (echo) out.println(line);
        return line.strip();
    }

    private void reset() {
        memory.values = DiscoveryForm.defaults(LocalDate.now()); values = memory.values;
        memory.chosen = null; memory.zone = Optional.empty(); memory.device = false; memory.visited.clear(); memory.forgetResults();
        pendingFix = null; runRequested = false; distanceNoted = false; failure = List.of();
        blank();
        mark("[OK]", style::ok, l.text("discover.inputsReset"), 0);
    }
    private void help() {
        blank();
        emit(l.text("discover.cli.wizard.helpTitle"), 0, 2, style::strong);
        for (String text : List.of(l.text("discover.cli.wizard.helpValues"), l.text("discover.cli.wizard.helpWords"), l.text("discover.cli.wizard.helpMenus"),
                l.text("discover.cli.wizard.helpWidth", String.valueOf(width))))
            emit(text, 2, 0, UnaryOperator.identity());
    }
    private void invalid() { mark("[!]", style::warn, l.text("discover.cli.wizard.invalid"), 0); }
    /** A refused save or hand-off: the service's reasons in the action's own message ("The idea was not saved: ..."). */
    private void refused(String key, Result<?> result) { mark("[!]", style::warn, l.text(key, String.join(" ", result.errors(l))), 0); }
    /**
     * No interactive input (scripts, pipes, batch arguments): how to start the wizard and the equivalent commands, never
     * a question. The command templates wrap only at ASCII spaces, so a token such as {@code distance=<km>} is never
     * split (the prose sentence uses the wrap that also breaks between Chinese characters).
     */
    private void scripted() {
        emit(l.text("discover.cli.wizard.scripted"), 0, 0, UnaryOperator.identity());
        for (String text : List.of(l.text("discover.cli.wizard.cmdGear"), l.text("discover.cli.wizard.cmdPlace"), l.text("discover.cli.wizard.cmdRun"),
                l.text("discover.cli.wizard.cmdSaved"), l.text("discover.cli.wizard.cmdHelp"))) TextTable.hanging("  " + text, width, 6).forEach(out::println);
    }

    // ---- Layout: wrapped to the width, styled only after wrapping ------------------------------------------------------

    private void header(Stage current) {
        blank();
        var entries = progress(current);
        // Narrow terminals: "Step 1/3" and one stage per line; the marks and words carry the state without colour. The
        // choice is made for all three steps at once, so the header keeps one shape from step to step. The one-line
        // progress is flush with the title above it (the header block), and fits 80 columns in every step.
        boolean fullTitles = true, oneLine = true;
        for (var stage : Stage.values()) {
            fullTitles &= TextTable.width(l.text("discover.cli.wizard.title", stage.number(), l.text(stage.titleKey()))) <= width;
            oneLine &= TextTable.width(String.join(" > ", progress(stage))) <= width;
        }
        if (fullTitles) out.println(style.heading(l.text("discover.cli.wizard.title", current.number(), l.text(current.titleKey()))));
        else emit(l.text("discover.cli.wizard.titleShort", current.number()), 0, 2, style::heading);
        String marked = entries.get(current.ordinal());
        if (oneLine) out.println(String.join(" > ", entries).replace(marked, style.strong(marked)));
        else for (int i = 0; i < entries.size(); i++) emit(entries.get(i), 2, 3, i == current.ordinal() ? style::strong : UnaryOperator.identity());
        out.println(style.muted("-".repeat(Math.min(width, RULE))));
    }
    /** "[current]", "done" and upcoming entries of the progress line. */
    private List<String> progress(Stage current) {
        var entries = new ArrayList<String>();
        for (var stage : Stage.values()) {
            String name = l.text(stage.titleKey());
            entries.add(stage == current ? "[" + stage.number() + " " + name + "]"
                : stage.ordinal() < current.ordinal() ? l.text("discover.cli.wizard.done", name) : stage.number() + " " + name);
        }
        return entries;
    }
    private static String[] row(String label, String value) { return new String[] {label, value}; }
    /** Aligned "label  value" rows; values wrap under themselves, or below the label when the terminal is narrow. */
    private void rows(List<String[]> rows) {
        int labels = rows.stream().mapToInt(r -> TextTable.width(r[0])).max().orElse(0);
        int room = width - 2 - labels - 2;
        boolean stacked = room < 24;
        for (var row : rows) {
            if (stacked) { emit(row[0], 2, 2, style::strong); emit(row[1], 4, 0, UnaryOperator.identity()); continue; }
            var parts = new ArrayList<String>();
            for (String part : row[1].split("\\R", -1)) parts.addAll(wrap(part, room, room));
            for (int i = 0; i < parts.size(); i++) {
                String label = i == 0 ? TextTable.pad(row[0], labels, TextTable.Align.LEFT) : " ".repeat(labels);
                String line = TextTable.strip("  " + label + "  " + parts.get(i));
                out.println(i == 0 && style.enabled() ? "  " + style.strong(row[0]) + line.substring(2 + row[0].length()) : line);
            }
        }
    }
    /** "Choose:" and the items, in two columns when they fit, otherwise one per line. */
    private void menu(List<Item> items) {
        blank();
        emit(l.text("discover.cli.wizard.choose"), 0, 2, style::strong);
        int keys = items.stream().mapToInt(i -> TextTable.width(i.key())).max().orElse(1);
        var cells = items.stream().map(i -> TextTable.pad(i.key(), keys, TextTable.Align.LEFT) + "  " + i.label()).toList();
        int half = (cells.size() + 1) / 2;
        int left = cells.subList(0, half).stream().mapToInt(TextTable::width).max().orElse(0);
        int right = cells.subList(half, cells.size()).stream().mapToInt(TextTable::width).max().orElse(0);
        if (cells.size() > 3 && 2 + left + 3 + right <= width) {
            for (int i = 0; i < half; i++)
                out.println(TextTable.strip("  " + TextTable.pad(cells.get(i), left, TextTable.Align.LEFT) + (i + half < cells.size() ? "   " + cells.get(i + half) : "")));
        } else cells.forEach(cell -> emit(cell, 2, keys + 2, UnaryOperator.identity()));
    }
    private void list(List<Item> items) {
        int keys = items.stream().mapToInt(i -> TextTable.width(i.key())).max().orElse(1);
        items.forEach(item -> emit(TextTable.pad(item.key(), keys, TextTable.Align.LEFT) + "  " + item.label(), 2, keys + 2, UnaryOperator.identity()));
    }
    /**
     * {@code text} at {@code indent} (plus its own leading spaces), wrapped to the width; continuation lines are indented
     * {@code hang} more columns than the first.
     */
    private void emit(String text, int indent, int hang, UnaryOperator<String> paint) {
        for (String part : text.split("\\R", -1)) {
            String body = part.stripLeading();
            int lead = indent + part.length() - body.length();
            var lines = wrap(body, width - lead, width - lead - hang);
            for (int i = 0; i < lines.size(); i++) out.println(paint.apply(" ".repeat(i == 0 ? lead : lead + hang) + lines.get(i)));
        }
    }
    /** A bracketed ASCII mark before the text; colour (when enabled) only on the mark, the words are always printed. */
    private void mark(String mark, UnaryOperator<String> paint, String text, int indent) {
        int step = indent + mark.length() + 1;
        var lines = wrap(text.replaceAll("\\R", " ").strip(), width - step, width - step);
        for (int i = 0; i < lines.size(); i++)
            out.println(i == 0 ? " ".repeat(indent) + paint.apply(mark) + " " + lines.get(0) : " ".repeat(step) + lines.get(i));
    }
    private static final String OPENING = "（《「『【〔“‘([{", CLOSING = "，。、；：！？）》」』】〕”’…,.;:!?)]}%";
    /**
     * Line breaking for terminals: at spaces, and between East Asian wide characters (Chinese has no spaces), but never
     * before closing or after opening punctuation, and never inside a command token such as {@code <回答>},
     * {@code width=<列数>} or {@code 字段=值}. Widths count wide characters as two columns; a unit longer than a whole
     * line is split at characters, so no line is wider than asked. The first line may have a different width.
     */
    static List<String> wrap(String text, int first, int rest) {
        first = Math.max(1, first); rest = Math.max(1, rest);
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        int used = 0;
        for (String unit : units(text)) {
            int limit = lines.isEmpty() ? first : rest, size = TextTable.width(unit);
            if (unit.isBlank()) { if (used > 0 && used + size <= limit) { line.append(unit); used += size; } continue; }
            if (used + size <= limit) { line.append(unit); used += size; continue; }
            if (used > 0) { lines.add(TextTable.strip(line.toString())); line.setLength(0); used = 0; limit = rest; }
            for (int i = 0; i < unit.length(); ) {
                int cp = unit.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                int columns = TextTable.width(ch);
                if (used > 0 && used + columns > limit) { lines.add(TextTable.strip(line.toString())); line.setLength(0); used = 0; limit = rest; }
                line.append(ch); used += columns; i += Character.charCount(cp);
            }
        }
        if (used > 0 || lines.isEmpty()) lines.add(TextTable.strip(line.toString()));
        return lines;
    }
    /** Runs of spaces, and the pieces between break opportunities. */
    private static List<String> units(String text) {
        int[] cps = text.codePoints().toArray();
        boolean[] joined = commandTokens(cps);
        var units = new ArrayList<String>();
        var unit = new StringBuilder();
        int previous = -1;
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            boolean space = cp == ' ', previousSpace = previous == ' ';
            boolean breakBefore = !unit.isEmpty() && !joined[i] && (space != previousSpace || !space && (wide(previous) || wide(cp))
                && CLOSING.indexOf(cp) < 0 && OPENING.indexOf(previous) < 0);
            if (breakBefore) { units.add(unit.toString()); unit.setLength(0); }
            unit.appendCodePoint(cp);
            previous = cp;
        }
        if (!unit.isEmpty()) units.add(unit.toString());
        return units;
    }
    /**
     * {@code joined[i]}: no break before code point {@code i}, because it continues a command token. A token is a run
     * between spaces and wide punctuation (、，：。...) that holds a field template ({@code =}) or a placeholder
     * ({@code <...>}); the user copies it as one piece, so the wide characters inside it are never a break opportunity.
     */
    private static boolean[] commandTokens(int[] cps) {
        boolean[] joined = new boolean[cps.length];
        for (int start = 0; start < cps.length; ) {
            if (tokenEnd(cps[start])) { start++; continue; }
            int end = start;
            boolean template = false, opened = false;
            while (end < cps.length && !tokenEnd(cps[end])) {
                if (cps[end] == '=') template = true;
                else if (cps[end] == '<') opened = true;
                else if (cps[end] == '>' && opened) template = true;
                end++;
            }
            if (template) for (int i = start + 1; i < end; i++) joined[i] = true;
            start = end;
        }
        return joined;
    }
    private static boolean tokenEnd(int cp) {
        return Character.isWhitespace(cp) || wide(cp) && (OPENING.indexOf(cp) >= 0 || CLOSING.indexOf(cp) >= 0);
    }
    private static boolean wide(int cp) { return cp >= 0 && TextTable.width(new String(Character.toChars(cp))) == 2; }
    private void blank() { out.println(); }
    /** Continuation indent of a presentation line: under the text of a "- " bullet, flush for a sentence. */
    private static int bullet(String line) { return line.stripLeading().startsWith("- ") ? 2 : 0; }
    private static String mark(Feasibility status) {
        return switch (status) { case FEASIBLE -> "[OK]"; case UNVERIFIED -> "[??]"; case INFEASIBLE -> "[NO]"; };
    }
    private UnaryOperator<String> paint(Feasibility status) {
        return switch (status) { case FEASIBLE -> style::ok; case UNVERIFIED -> style::warn; case INFEASIBLE -> style::bad; };
    }
    private String value(String key) { return Objects.toString(values.get(key), "").strip(); }
    private void set(String key, String value) { values.put(key, value == null ? "" : value); }
    private String notSet() { return l.text("discover.cli.wizard.notSet"); }
    private String orNotSet(String value) { return value.isEmpty() ? notSet() : value; }
    private static int number(String text) {
        try { return Integer.parseInt(text.strip()); } catch (NumberFormatException invalid) { return -1; }
    }
}
