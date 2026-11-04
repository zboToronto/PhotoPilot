package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
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
 * F09 content in the existing feature surface: session -> reviewed location/date/availability (manual, place lookup
 * or consented device location) -> background assessment -> separate cards for provider facts, computed estimates,
 * validated AI windows and limitations. Reuses GearMatchPane's controls, cards and Task lifecycle; all rules live in
 * the shared services, and nothing is saved.
 */
final class EnvironmentPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final ComboBox<PlaceCandidate> places = new ComboBox<>();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), contextLines = FeaturePane.label("", "secondary-text");
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final VBox controls = new VBox(16), contextBox = new VBox(10), inputs = new VBox(14), placeBox = new VBox(8),
        results = new VBox(18), feedbackBox = new VBox(8);
    private Optional<ZoneId> zoneHint = Optional.empty();
    private PlaceCandidate chosen;
    private UUID prefilledFor;
    private EnvironmentAssessment assessment;
    private boolean busy, disposed, edited, prefilling;

    EnvironmentPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        session.setId("env-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "env.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> showSession());
        Label sessionCaption = texts.label("env.session", "section-title"); sessionCaption.setLabelFor(session);
        Button refresh = button("gear.refresh", "env-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);

        contextLines.setId("env-context");
        contextBox.getChildren().addAll(texts.label("env.context.title", "section-title"), contextLines);

        FlowPane location = new FlowPane(16, 14), timing = new FlowPane(16, 14), activity = new FlowPane(16, 14);
        field(location, "location"); field(location, "latitude"); field(location, "longitude");
        field(timing, "date"); field(timing, "from"); field(timing, "until"); field(timing, "lead");
        field(activity, "activity"); field(activity, "goals");
        places.setId("env-places"); places.setMaxWidth(Double.MAX_VALUE); texts.text(places.accessibleTextProperty(), "env.place.choose");
        texts.choices(places, c -> EnvironmentPresentation.candidate(c, places.getItems().indexOf(c) + 1, localization), () -> localization.text("env.place.choose"));
        places.valueProperty().addListener((o, old, value) -> { if (value != null) choose(value); });
        placeBox.getChildren().addAll(texts.label("env.place.title", "section-title"), places);
        inputs.getChildren().addAll(texts.label("env.inputs.title", "section-title"), location,
            new FlowPane(12, 10, button("env.find", "env-find", this::findPlace, false), button("env.useDevice", "env-device", this::deviceLocation, false)),
            placeBox, texts.label("env.inputs.privacy", "secondary-text"), timing, texts.label("env.inputs.timeNotice", "secondary-text"),
            activity, texts.label("env.inputs.activityNotice", "secondary-text"),
            texts.label("env.aiNotice", "secondary-text"), new FlowPane(12, 10, button("env.assess", "env-assess", this::assess, true),
                button("env.resetInputs", "env-reset", this::resetInputs, false)));
        for (var box : List.of(contextBox, inputs, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("env.feedback", "section-title"), feedback);
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), sessionHint), contextBox, inputs);
        hide(sessionHint); hide(placeBox); hide(feedbackBox); hide(results); hide(contextBox); hide(inputs);
        getChildren().addAll(controls, results, feedbackBox);
        texts.onRefresh(this::render);
    }
    private void field(FlowPane box, String key) {
        TextField input = new TextField(); input.setId("env-" + key); fields.put(key, input);
        texts.inputMenu(input); texts.text(input.promptTextProperty(), "env.prompt." + key);
        texts.bind(input.accessibleTextProperty(), () -> localization.text("env.field." + key));
        input.textProperty().addListener((o, old, value) -> {
            if (prefilling) return;
            edited = true;
            // Edited coordinates no longer belong to the chosen gazetteer place, so its time zone hint is dropped.
            if ((key.equals("latitude") || key.equals("longitude")) && chosen != null) { zoneHint = Optional.empty(); chosen = null; }
        });
        Label caption = FeaturePane.label("", "section-title"); caption.setLabelFor(input);
        texts.bind(caption.textProperty(), () -> localization.text("env.field." + key));
        VBox column = new VBox(7, caption, input); column.setMinWidth(0);
        column.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> box.getWidth() < 560 ? Math.max(160, box.getWidth()) : (box.getWidth() - 16) / 2, box.widthProperty()));
        box.getChildren().add(column);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; texts.close(); }
    /** Reviewed inputs are not saved anywhere; leaving them asks for confirmation like other unsaved drafts. */
    boolean dirty() { return edited; }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    EnvironmentAssessment assessment() { return assessment; }

    /** Editable sessions only (F09 plans upcoming shoots). Selecting a different session prefills from it. */
    void refresh() {
        UUID selected = session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresentOrElse(session::setValue,
                () -> { if (!editable.isEmpty()) session.setValue(editable.getFirst()); else session.setValue(null); });
            texts.text(sessionHint.textProperty(), "requirement.noSessions");
            if (editable.isEmpty()) show(sessionHint); else hide(sessionHint);
            showSession();
        }, null);
    }

    private void showSession() {
        var selected = session.getValue();
        if (selected == null) { hide(contextBox); hide(inputs); return; }
        show(contextBox); show(inputs);
        texts.bind(contextLines.textProperty(), () -> String.join("\n", EnvironmentPresentation.context(selected, localization)));
        boolean manualContext = selected.requirements().filter(ShootRequirement::confirmed).isEmpty();
        fields.get("activity").setDisable(!manualContext); fields.get("goals").setDisable(!manualContext);
        if (!selected.id().equals(prefilledFor)) { prefill(EnvironmentForm.defaults(selected)); prefilledFor = selected.id(); clearResults(); }
    }
    private void prefill(Map<String, String> values) {
        prefilling = true;
        try { values.forEach((key, value) -> { if (fields.containsKey(key)) fields.get(key).setText(value); }); }
        finally { prefilling = false; }
        zoneHint = Optional.empty(); chosen = null; edited = false; places.getItems().clear(); hide(placeBox);
    }
    private void resetInputs() {
        var selected = session.getValue();
        if (selected == null) return;
        prefill(EnvironmentForm.defaults(selected)); clearResults(); message(() -> localization.text("env.inputsReset"));
    }

    /** Name lookup returns every match; the user picks one (an ambiguous name is never resolved automatically). */
    private void findPlace() {
        String name = fields.get("location").getText();
        message(() -> localization.text("env.place.searching"));
        run(() -> app.planning().location().search(name), found -> {
            places.getItems().setAll(found); places.setValue(null); show(placeBox);
            message(() -> localization.text(found.size() == 1 ? "env.place.one" : "env.place.many", found.size()));
            SessionPane.reveal(this, placeBox);
        }, failure -> { places.getItems().clear(); hide(placeBox); message(() -> String.join(" ", failure.errors(localization))); });
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
        var location = app.planning().location();
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

    private void assess() {
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        var values = new LinkedHashMap<String, String>();
        fields.forEach((key, input) -> values.put(key, input.isDisabled() ? "" : input.getText()));
        var parsed = chosen != null ? EnvironmentForm.parse(values, app.planning().location(), chosen)
            : EnvironmentForm.parse(values, app.planning().location(), zoneHint);
        if (!parsed.isSuccess()) { message(() -> String.join(" ", parsed.errors(localization))); return; }
        var request = parsed.value().orElseThrow();
        clearResults(); message(() -> localization.text("env.running"));
        run(() -> app.assessEnvironment(selected.id(), request), found -> {
            assessment = found; render(); show(results);
            message(() -> localization.text(found.windows().isEmpty() ? "env.doneNoWindows" : "env.done", found.windows().size()));
            SessionPane.reveal(this, results);
        }, failure -> message(() -> localization.text("env.failedInputsKept", String.join(" ", failure.errors(localization)))));
    }
    private void clearResults() { assessment = null; results.getChildren().clear(); hide(results); }

    /** Rebuilds the localized cards from the last assessment (also on language change). */
    private void render() {
        results.getChildren().clear();
        if (assessment == null) return;
        var a = assessment;
        results.getChildren().add(card("env.card.summary", "env-summary", EnvironmentPresentation.summary(a, localization)));
        var windows = EnvironmentPresentation.windows(a, localization);
        if (windows.isEmpty()) results.getChildren().add(card("env.card.windows", "env-windows", List.of(localization.text("env.card.noWindows"))));
        for (int i = 0; i < windows.size(); i++) {
            // Heading line styled like F07 candidate cards; the body keeps the shared presentation lines.
            var lines = windows.get(i);
            var box = card(null, "env-window-" + (i + 1), lines.subList(1, lines.size()));
            box.getChildren().addFirst(FeaturePane.label(lines.getFirst(), "section-title"));
            results.getChildren().add(box);
        }
        results.getChildren().add(card("env.card.ai", "env-ai", EnvironmentPresentation.interpretation(a, localization)));
        results.getChildren().add(card("env.card.evidence", "env-evidence", EnvironmentPresentation.evidence(a, localization)));
        results.getChildren().add(card("env.card.phases", "env-phases", EnvironmentPresentation.phases(a, localization)));
        results.getChildren().add(card("env.card.rejected", "env-rejected", EnvironmentPresentation.rejected(a, localization)));
        results.getChildren().add(card("env.card.limitations", "env-limitations", EnvironmentPresentation.limitations(a, localization)));
    }
    private VBox card(String titleKey, String id, List<String> lines) {
        var body = FeaturePane.label(String.join("\n", lines), "secondary-text"); body.setId(id);
        var box = new VBox(10); box.getStyleClass().add("feedback-box");
        if (titleKey != null) box.getChildren().add(FeaturePane.label(localization.text(titleKey), "section-title"));
        box.getChildren().add(body);
        return box;
    }

    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy = true; controls.setDisable(true); results.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            done(); var result = task.getValue();
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else if (failure != null) failure.accept(result);
            else message(() -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; done(); message(() -> localization.text("env.failed")); });
        operations.execute(task);
    }
    private void done() { busy = false; controls.setDisable(false); results.setDisable(false); }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
