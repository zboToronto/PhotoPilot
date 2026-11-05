package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * F02 "Find Something to Shoot" in the existing feature surface: reviewed place/time/constraints/interests (manual,
 * looked-up or consented device location) and the read-only F05 inventory -> background Gemini request -> cards for
 * the context used, feasible, unverified and not-suitable ideas, AI notes and rejected candidates. Each usable idea can
 * continue in F03 (unconfirmed draft), be saved to a session or be dismissed. Reuses EnvironmentPane's controls, cards
 * and Task lifecycle; every rule lives in the shared services. No session is needed to ask for ideas.
 */
final class DiscoveryPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final BiConsumer<UUID, ShootRequirement> handoff;
    private final ComboBox<PlaceCandidate> places = new ComboBox<>();
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final CheckBox history = new CheckBox(), newSession = new CheckBox();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Label gearLines = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final VBox controls = new VBox(16), inputs = new VBox(14), placeBox = new VBox(8), gearBox = new VBox(10),
        results = new VBox(18), targetBox = new VBox(10), savedBox = new VBox(10), feedbackBox = new VBox(8);
    private Optional<ZoneId> zoneHint = Optional.empty();
    private PlaceCandidate chosen;
    private DiscoveryResult result;
    private List<UUID> numbering = List.of();
    private boolean busy, disposed, edited, prefilling;

    DiscoveryPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization, BiConsumer<UUID, ShootRequirement> handoff) {
        super(20); this.app = app; this.operations = operations; this.localization = localization; this.handoff = handoff;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");

        FlowPane location = new FlowPane(16, 14), timing = new FlowPane(16, 14), constraints = new FlowPane(16, 14), interests = new FlowPane(16, 14);
        field(location, "location"); field(location, "latitude"); field(location, "longitude");
        field(timing, "date"); field(timing, "from"); field(timing, "until"); field(timing, "duration");
        field(constraints, "distance"); field(constraints, "portability");
        field(interests, "interests"); field(interests, "avoid"); field(interests, "notes");
        places.setId("discover-places"); places.setMaxWidth(Double.MAX_VALUE); texts.text(places.accessibleTextProperty(), "env.place.choose");
        texts.choices(places, c -> EnvironmentPresentation.candidate(c, places.getItems().indexOf(c) + 1, localization), () -> localization.text("env.place.choose"));
        places.valueProperty().addListener((o, old, value) -> { if (value != null) choose(value); });
        placeBox.getChildren().addAll(texts.label("env.place.title", "section-title"), places);
        history.setId("discover-history"); history.setSelected(true); texts.text(history.textProperty(), "discover.field.historyOption");
        history.selectedProperty().addListener((o, old, value) -> { if (!prefilling) edited = true; });
        inputs.getChildren().addAll(texts.label("discover.inputs.title", "section-title"), texts.label("discover.intro", "secondary-text"), location,
            new FlowPane(12, 10, button("env.find", "discover-find", this::findPlace, false), button("env.useDevice", "discover-device", this::deviceLocation, false)),
            placeBox, texts.label("discover.inputs.privacy", "secondary-text"), timing, texts.label("discover.inputs.timeNotice", "secondary-text"),
            constraints, texts.label("discover.inputs.distanceNotice", "secondary-text"), interests, history);
        gearLines.setId("discover-gear");
        Button refreshGear = button("gear.refresh", "discover-gear-refresh", this::loadGear, false); refreshGear.setMinWidth(Region.USE_PREF_SIZE);
        gearBox.getChildren().addAll(texts.label("discover.gear.title", "section-title"), gearLines, new FlowPane(12, 10, refreshGear));
        VBox actions = new VBox(10, texts.label("discover.aiNotice", "secondary-text"), new FlowPane(12, 10,
            button("discover.generate", "discover-generate", this::generate, true), button("env.resetInputs", "discover-reset", this::resetInputs, false)));

        session.setId("discover-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "discover.target");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())),
            () -> localization.text("discover.target.none"));
        session.valueProperty().addListener((o, old, value) -> showSaved());
        newSession.setId("discover-new-session"); texts.text(newSession.textProperty(), "discover.target.new");
        newSession.selectedProperty().addListener((o, old, value) -> { session.setDisable(value); showSaved(); });
        Label targetCaption = texts.label("discover.target", "section-title"); targetCaption.setLabelFor(session);
        Button refreshSessions = button("gear.refresh", "discover-session-refresh", this::loadSessions, false); refreshSessions.setMinWidth(Region.USE_PREF_SIZE);
        targetBox.getChildren().addAll(targetCaption, new HBox(12, grow(session), refreshSessions), newSession, texts.label("discover.target.hint", "secondary-text"), savedBox);

        for (var box : List.of(inputs, gearBox, targetBox, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("discover.feedback", "section-title"), feedback);
        controls.getChildren().addAll(inputs, gearBox, actions);
        hide(placeBox); hide(feedbackBox); hide(results);
        getChildren().addAll(controls, targetBox, results, feedbackBox);
        prefill(DiscoveryForm.defaults(LocalDate.now()));
        texts.onRefresh(this::render);
    }
    private void field(FlowPane box, String key) {
        TextField input = new TextField(); input.setId("discover-" + key); fields.put(key, input);
        texts.inputMenu(input); texts.text(input.promptTextProperty(), "discover.prompt." + key);
        texts.bind(input.accessibleTextProperty(), () -> localization.text("discover.field." + key));
        input.textProperty().addListener((o, old, value) -> {
            if (prefilling) return;
            edited = true;
            // Edited coordinates no longer belong to the chosen gazetteer place, so its time zone hint is dropped.
            if ((key.equals("latitude") || key.equals("longitude")) && chosen != null) { zoneHint = Optional.empty(); chosen = null; }
        });
        Label caption = FeaturePane.label("", "section-title"); caption.setLabelFor(input);
        texts.bind(caption.textProperty(), () -> key.equals("location") || key.equals("distance")
            ? localization.text("requirement.required", localization.text("discover.field." + key)) : localization.text("discover.field." + key));
        VBox column = new VBox(7, caption, input); column.setMinWidth(0);
        column.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> box.getWidth() < 560 ? Math.max(160, box.getWidth()) : (box.getWidth() - 16) / 2, box.widthProperty()));
        box.getChildren().add(column);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    /** Opening the page reads local inventory and sessions only: no provider, device or Gemini request. */
    void activate(boolean active) { if (active && !busy) { loadGear(); } }
    void dispose() { disposed = true; texts.close(); }
    /** Reviewed inputs are not saved anywhere; leaving them asks for confirmation like other unsaved drafts. */
    boolean dirty() { return edited; }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    DiscoveryResult result() { return result; }

    private void loadGear() {
        run(() -> app.inventory().list(""), inventory -> {
            texts.bind(gearLines.textProperty(), () -> String.join("\n", DiscoveryPresentation.gear(inventory, localization)));
            loadSessions();
        }, failure -> { texts.bind(gearLines.textProperty(), () -> localization.text("discover.gear.unreadable", String.join(" ", failure.errors(localization)))); loadSessions(); });
    }
    /** Editable sessions only: ideas are saved to, and continued in, upcoming (Draft/Planned) sessions. */
    private void loadSessions() {
        UUID selected = session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresentOrElse(session::setValue,
                () -> session.setValue(editable.isEmpty() ? null : editable.getFirst()));
            if (editable.isEmpty()) newSession.setSelected(true);
            showSaved();
        }, null);
    }
    private void showSaved() {
        savedBox.getChildren().clear();
        var target = newSession.isSelected() ? null : session.getValue();
        if (target == null) return;
        savedBox.getChildren().add(texts.label("discover.saved.title", "section-title", target.title()));
        var ideas = target.savedIdeas();
        Label lines = FeaturePane.label(String.join("\n", DiscoveryPresentation.saved(ideas, localization)), "secondary-text"); lines.setId("discover-saved");
        savedBox.getChildren().add(lines);
        var buttons = new FlowPane(10, 8);
        for (int i = 0; i < ideas.size(); i++) {
            var idea = ideas.get(i);
            if (idea.feasibility() == Feasibility.INFEASIBLE) continue;
            Button resume = new Button(); texts.text(resume.textProperty(), "discover.continueSaved", i + 1);
            resume.setId("discover-saved-continue-" + (i + 1)); resume.getStyleClass().add("secondary-button");
            resume.setOnAction(e -> continueSaved(target, idea));
            buttons.getChildren().add(resume);
        }
        if (!buttons.getChildren().isEmpty()) savedBox.getChildren().add(buttons);
    }

    private void prefill(Map<String, String> values) {
        prefilling = true;
        try {
            values.forEach((key, value) -> { if (fields.containsKey(key)) fields.get(key).setText(value); });
            history.setSelected(!"no".equalsIgnoreCase(values.getOrDefault("history", "yes")));
        } finally { prefilling = false; }
        zoneHint = Optional.empty(); chosen = null; edited = false; places.getItems().clear(); hide(placeBox);
    }
    private void resetInputs() { prefill(DiscoveryForm.defaults(LocalDate.now())); clearResults(); message(() -> localization.text("discover.inputsReset")); }

    /** Name lookup returns every match; the user picks one (an ambiguous name is never resolved automatically). */
    private void findPlace() {
        String name = fields.get("location").getText();
        message(() -> localization.text("env.place.searching"));
        run(() -> app.discovery().location().search(name), found -> {
            places.getItems().setAll(found); places.setValue(null); show(placeBox);
            message(() -> localization.text(found.size() == 1 ? "env.place.one" : "env.place.many", found.size()));
            SessionPane.reveal(this, placeBox);
        }, failure -> { places.getItems().clear(); hide(placeBox); message(() -> String.join(" ", failure.errors(localization)) + " " + localization.text("discover.place.manualFallback")); });
    }
    private void choose(PlaceCandidate candidate) {
        prefilling = true;
        try {
            fields.get("location").setText(candidate.displayName());
            fields.get("latitude").setText(EnvironmentForm.coordinate(candidate.latitude()));
            fields.get("longitude").setText(EnvironmentForm.coordinate(candidate.longitude()));
        } finally { prefilling = false; }
        edited = true; chosen = candidate; zoneHint = candidate.zone();
        message(() -> localization.text("env.place.selected", candidate.displayName()));
    }
    /** Device location only on this explicit action, after consent; unsupported devices and a denial never prompt. */
    private void deviceLocation() {
        var location = app.discovery().location();
        if (!location.deviceSupported()) { message(() -> localization.text("env.location.error.deviceUnsupported")); return; }
        if (location.consentDenied()) { message(() -> localization.text("env.location.error.denied")); return; }
        if (!GuiProfileController.confirm(getScene() == null ? null : getScene().getWindow(), localization,
                "env.device.consentTitle", "env.device.consentBody", "env.device.allow")) {
            location.recordDenial(); message(() -> localization.text("env.device.declined")); return;
        }
        run(() -> location.current(true), place -> {
            prefilling = true;
            try {
                fields.get("location").setText(place.label());
                fields.get("latitude").setText(EnvironmentForm.coordinate(place.latitude().orElseThrow()));
                fields.get("longitude").setText(EnvironmentForm.coordinate(place.longitude().orElseThrow()));
            } finally { prefilling = false; }
            edited = true; chosen = null; zoneHint = Optional.empty();
            message(() -> localization.text("env.device.used"));
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }

    private void generate() {
        var values = new LinkedHashMap<String, String>();
        fields.forEach((key, input) -> values.put(key, input.getText()));
        values.put("history", history.isSelected() ? "yes" : "no");
        var parsed = chosen != null ? DiscoveryForm.parse(values, app.discovery().location(), chosen)
            : DiscoveryForm.parse(values, app.discovery().location(), zoneHint);
        if (!parsed.isSuccess()) { message(() -> String.join(" ", parsed.errors(localization))); return; }
        var request = parsed.value().orElseThrow();
        clearResults(); message(() -> localization.text("discover.running"));
        // On failure the reviewed inputs stay exactly as they were and no idea is shown.
        run(() -> app.discover(request), found -> {
            result = found; numbering = found.ideas().stream().map(ActivitySuggestion::id).toList(); render(); show(results);
            message(() -> localization.text(found.feasible().isEmpty() ? "discover.doneNoneFeasible" : "discover.done", found.feasible().size(), found.unverified().size()));
            SessionPane.reveal(this, results);
        }, failure -> message(() -> localization.text("discover.failedInputsKept", String.join(" ", failure.errors(localization)))));
    }
    private void clearResults() { result = null; numbering = List.of(); results.getChildren().clear(); hide(results); }

    /** Rebuilds the localized cards from the latest result (also on language change and after a dismissal). */
    private void render() {
        results.getChildren().clear();
        if (result == null) return;
        var r = result;
        results.getChildren().add(card("discover.card.context", "discover-context", DiscoveryPresentation.context(r.context(), localization)));
        results.getChildren().add(card("discover.card.summary", "discover-summary", DiscoveryPresentation.summary(r, localization)));
        if (!r.limitingFactors().isEmpty()) results.getChildren().add(card("discover.card.limits", "discover-limits", DiscoveryPresentation.limits(r, localization)));
        for (var status : Feasibility.values()) {
            var group = r.with(status);
            if (group.isEmpty()) continue;
            results.getChildren().add(texts.label("discover.group." + status.name(), "group-title"));
            for (var idea : group) {
                int number = numbering.indexOf(idea.id()) + 1;
                var box = card(null, "discover-idea-" + number, DiscoveryPresentation.idea(idea, localization));
                box.getChildren().addFirst(FeaturePane.label(DiscoveryPresentation.heading(idea, number, localization), "section-title"));
                var buttons = new FlowPane(12, 10);
                if (status != Feasibility.INFEASIBLE) buttons.getChildren().addAll(
                    button("discover.continue", "discover-continue-" + number, () -> continueIdea(idea), status == Feasibility.FEASIBLE),
                    button("discover.save", "discover-save-" + number, () -> save(idea), false));
                buttons.getChildren().add(button("discover.dismiss", "discover-dismiss-" + number, () -> dismiss(idea), false));
                box.getChildren().add(buttons);
                results.getChildren().add(box);
            }
        }
        results.getChildren().add(card("discover.card.ai", "discover-ai", DiscoveryPresentation.ai(r, localization)));
        results.getChildren().add(card("discover.card.rejected", "discover-rejected", DiscoveryPresentation.rejected(r, localization)));
    }
    private VBox card(String titleKey, String id, List<String> lines) {
        var body = FeaturePane.label(String.join("\n", lines), "secondary-text"); body.setId(id);
        var box = new VBox(10); box.getStyleClass().add("feedback-box");
        if (titleKey != null) box.getChildren().add(FeaturePane.label(localization.text(titleKey), "section-title"));
        box.getChildren().add(body);
        return box;
    }

    private record Target(PhotographySession session, boolean created) { }
    /** The chosen editable session, or empty when a new Draft session (F01) named after the idea is wanted. */
    private Optional<PhotographySession> chosenSession() {
        return newSession.isSelected() ? Optional.empty() : Optional.ofNullable(session.getValue());
    }
    private record Handoff(Target target, ShootRequirement draft) { }
    /**
     * Unconfirmed F03 draft opened in the requirement page; F03 owns review, clarification and confirmation. A new
     * session is created by DiscoveryService only once the idea passed its checks, so a refusal leaves no empty session.
     */
    private void continueIdea(ActivitySuggestion idea) {
        message(() -> localization.text("discover.preparing"));
        var chosen = chosenSession();
        run(() -> {
            if (chosen.isEmpty()) {
                var made = app.discovery().selectInNewSession(idea);
                return made.isSuccess() ? Result.success(new Handoff(new Target(made.value().orElseThrow().session(), true), made.value().orElseThrow().draft()))
                    : Result.<Handoff>failure(made);
            }
            var draft = app.discovery().select(chosen.get().id(), idea);
            return draft.isSuccess() ? Result.success(new Handoff(new Target(chosen.get(), false), draft.value().orElseThrow())) : Result.<Handoff>failure(draft);
        }, done -> {
            if (done.target().created()) loadSessions();
            message(() -> localization.text("discover.selected", idea.subject(), done.target().session().title()));
            handoff.accept(done.target().session().id(), done.draft());
        }, failure -> message(() -> localization.text("discover.handoffFailed", String.join(" ", failure.errors(localization)))));
    }
    private void continueSaved(PhotographySession target, ActivitySuggestion idea) {
        run(() -> app.discovery().select(target.id(), idea), draft -> {
            message(() -> localization.text("discover.selected", idea.subject(), target.title()));
            handoff.accept(target.id(), draft);
        }, failure -> message(() -> localization.text("discover.handoffFailed", String.join(" ", failure.errors(localization)))));
    }
    private void save(ActivitySuggestion idea) {
        message(() -> localization.text("discover.saving"));
        var chosen = chosenSession();
        run(() -> {
            if (chosen.isEmpty()) return app.discovery().saveInNewSession(idea);
            var saved = app.discovery().save(chosen.get().id(), idea);
            return saved.isSuccess() ? Result.success(chosen.get()) : Result.<PhotographySession>failure(saved);
        }, stored -> {
            message(() -> localization.text("discover.saved", idea.subject(), stored.title()));
            newSession.setSelected(false); loadSessionsSelecting(stored.id());
        }, failure -> message(() -> localization.text("discover.saveFailed", String.join(" ", failure.errors(localization)))));
    }
    private void loadSessionsSelecting(UUID id) {
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(id)).findFirst().ifPresent(session::setValue);
            showSaved();
        }, null);
    }
    private void dismiss(ActivitySuggestion idea) {
        var removed = app.discovery().dismiss(idea.id());
        if (!removed.isSuccess()) { message(() -> String.join(" ", removed.errors(localization))); return; }
        result = app.discovery().latest().orElse(null); render();
        message(() -> localization.text("discover.dismissed", idea.subject()));
    }

    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy = true; controls.setDisable(true); results.setDisable(true); targetBox.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            done(); var outcome = task.getValue();
            if (outcome.isSuccess()) success.accept(outcome.value().orElseThrow());
            else if (failure != null) failure.accept(outcome);
            else message(() -> String.join("; ", outcome.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; done(); message(() -> localization.text("discover.failed")); });
        operations.execute(task);
    }
    private void done() { busy = false; controls.setDisable(false); results.setDisable(false); targetBox.setDisable(false); }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
