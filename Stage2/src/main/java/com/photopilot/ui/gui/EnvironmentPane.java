package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;

/**
 * F09 page in the existing feature surface. Inputs: the session, the shooting location (one name field; the hybrid
 * lookup's matches, an AI estimate awaiting explicit acceptance, or a compact confirmed-location card), collapsed
 * advanced location options (manual coordinates, IANA zone, consented device location), the date and local times with
 * date/time conflicts against the session shown beside them before anything is requested, and the assess action. Results:
 * the {@link EnvironmentDashboard} of one assessment snapshot, shown as soon as the factual data is in (while Gemini is
 * still interpreting it), an AI-only retry and the explicit record action. All rules live in the shared services; old
 * worker results are ignored through the input generation.
 */
final class EnvironmentPane extends VBox implements SessionScoped {
    private static final PseudoClass ESTIMATE = PseudoClass.getPseudoClass("estimated");
    private enum PlaceState { IDLE, SEARCHING, CANDIDATES, CONFIRMED, MANUAL, DEVICE, FAILED }
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final ComboBox<PlaceCandidate> places = new ComboBox<>();
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final Map<String, Node> fieldColumns = new LinkedHashMap<>();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), contextLines = FeaturePane.label("", "secondary-text");
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final VBox controls = new VBox(16), contextBox = new VBox(10), inputs = new VBox(16), placeBox = new VBox(10),
        results = new VBox(18), feedbackBox = new VBox(8), locationCard = new VBox(10), conflicts = new VBox(8), activityBox = new VBox(10);
    private final Label locationStatus = FeaturePane.label("", "env-location-detail"), locationTitle = FeaturePane.label("", "env-location-name"),
        locationZone = FeaturePane.label("", "env-caption"), conflictLines = FeaturePane.label("", "env-text"), inputSummary = FeaturePane.label("", "env-summary-line");
    private final HBox summaryBar;
    private final Button confirmPlace, changePlace;
    private final TitledPane advanced = new TitledPane();
    private final EnvironmentDashboard dashboard;
    private GeoLocation device;
    private long generation;
    private boolean assessAfterConfirm, manualApplied;
    private PlaceState placeState = PlaceState.IDLE;
    private PlaceCandidate chosen;
    private UUID prefilledFor;
    private EnvironmentAssessment assessment;
    /** The factual snapshot shown while the Gemini interpretation of the running assessment is pending. */
    private EnvironmentAnalysis provisional;
    /** The assessment last added to the session from this page (shown as added instead of offering it again). */
    private EnvironmentAssessment recorded;
    private boolean busy, disposed, edited, prefilling, inputsCollapsed;
    private final OperationRunner tracked;
    private final SessionChooser chooser;

    EnvironmentPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().addAll("inventory-content", "env-page");
        tracked = new OperationRunner(operations, new OperationProgressView(localization));
        dashboard = new EnvironmentDashboard(localization);
        dashboard.onRetry(this::retryAi);
        session.setId("env-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "env.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> {
            if (busy && old != null && (value == null || !old.id().equals(value.id()))) tracked.cancel();
            showSession();
        });
        chooser = new SessionChooser(session, texts);
        Label sessionCaption = texts.label("env.session", "section-title"); sessionCaption.setLabelFor(session);
        Button refresh = button("gear.refresh", "env-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);

        contextLines.setId("env-context");
        contextBox.getChildren().addAll(texts.label("env.context.title", "section-title"), contextLines);

        // Shooting location: one name field and Find location; the result appears in the location card below it.
        var locationField = input("location");
        var find = button("env.find", "env-find", this::findPlace, false); find.setMinWidth(Region.USE_PREF_SIZE);
        var locationRow = new HBox(10, locationField, find); locationRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(locationField, Priority.ALWAYS);
        Label locationCaption = texts.label("env.location.title", "env-section-title"); locationCaption.setLabelFor(locationField);

        places.setId("env-places"); places.setMaxWidth(Double.MAX_VALUE); texts.text(places.accessibleTextProperty(), "env.place.choose");
        texts.choices(places, c -> c.displayName() + " — " + localization.text("env.resolve.source." + c.source()), () -> localization.text("env.place.choose"));
        places.setCellFactory(list -> new CandidateCell());
        places.valueProperty().addListener((o, old, value) -> preview(value));
        confirmPlace = button("env.resolve.confirm", "env-confirm", this::confirmPlace, true);
        changePlace = button("env.resolve.change", "env-change", this::changePlace, false);
        placeBox.getChildren().addAll(texts.label("env.place.title", "env-caption"), places);
        locationStatus.setId("env-location-status"); locationTitle.setId("env-location-name"); locationZone.setId("env-location-zone");
        texts.text(locationStatus.textProperty(), "env.resolve.pending");
        var pin = new SVGPath(); pin.setContent("M8 0.5a6 6 0 0 0-6 6c0 4.4 6 10.5 6 10.5s6-6.1 6-10.5a6 6 0 0 0-6-6zm0 3.5a2.4 2.4 0 1 1 0 4.8a2.4 2.4 0 1 1 0-4.8z");
        pin.getStyleClass().add("env-pin");
        var pinBox = new StackPane(pin); pinBox.getStyleClass().add("env-pin-box"); pinBox.setMinSize(36, 36); pinBox.setMaxSize(36, 36);
        var headline = new HBox(12, pinBox, new VBox(3, locationTitle, locationStatus, locationZone)); headline.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(headline.getChildren().get(1), Priority.ALWAYS);
        var placeActions = new FlowPane(10, 8, confirmPlace, changePlace);
        locationCard.getChildren().addAll(headline, placeBox, placeActions);
        locationCard.getStyleClass().add("env-location-card"); locationCard.setId("env-location-card");

        // Advanced location options: the calculators' plain disclosure row, closed by default.
        var coordinates = new CardGrid(170, 16, 3);
        coordinates.getChildren().addAll(column("latitude"), column("longitude"), column("zone"));
        var manualActions = new FlowPane(12, 10, button("env.resolve.manual", "env-manual", this::applyManual, false));
        if (app.planning().location().deviceSupported()) manualActions.getChildren().add(button("env.useDevice", "env-device", this::deviceLocation, false));
        advanced.setId("env-advanced"); texts.text(advanced.textProperty(), "env.resolve.advanced");
        advanced.setContent(new VBox(12, coordinates, texts.label("env.advanced.hint", "env-caption"), manualActions));
        advanced.setExpanded(false); advanced.setAnimated(false); advanced.getStyleClass().add("env-disclosure");

        // Date and time: one aligned row of four fields; conflicts with the session appear right below it.
        var timing = new CardGrid(150, 16, 4);
        timing.getChildren().addAll(column("date"), column("from"), column("until"), column("lead"));
        conflicts.setId("env-conflicts"); conflicts.getStyleClass().add("env-conflicts"); conflictLines.setId("env-conflict-lines");
        conflicts.getChildren().addAll(texts.label("env.conflict.title", "env-conflict-title"), conflictLines,
            new FlowPane(10, 8, button("env.conflict.restore", "env-restore", this::resetInputs, false)));
        var activity = new CardGrid(260, 16, 2);
        activity.getChildren().addAll(column("activity"), column("goals"));
        activityBox.getChildren().addAll(activity, texts.label("env.inputs.activityNotice", "env-caption"));
        var about = new TitledPane(); about.setId("env-about"); texts.text(about.textProperty(), "env.inputs.about");
        about.setContent(new VBox(8, texts.label("env.inputs.privacy", "env-text"), texts.label("env.inputs.timeNotice", "env-text"), texts.label("env.aiNotice", "env-text")));
        about.setExpanded(false); about.setAnimated(false); about.getStyleClass().add("env-disclosure");
        var assess = button("env.assess", "env-assess", this::assess, true);
        var actions = new HBox(12, button("env.resetInputs", "env-reset", this::resetInputs, false), assess); actions.setAlignment(Pos.CENTER_RIGHT);
        inputs.getChildren().addAll(locationCaption, locationRow, locationCard, advanced, new Separator(),
            texts.label("env.time.title", "env-section-title"), timing, conflicts, activityBox, about, actions);
        inputs.getStyleClass().add("env-input-card"); inputs.setId("env-inputs");

        // After an assessment the inputs fold into one summary line that reopens them.
        var edit = button("env.inputs.edit", "env-edit-inputs", this::expandInputs, false); edit.setMinWidth(Region.USE_PREF_SIZE);
        inputSummary.setId("env-input-summary"); inputSummary.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(inputSummary, Priority.ALWAYS);
        summaryBar = new HBox(12, inputSummary, edit); summaryBar.setAlignment(Pos.CENTER_LEFT); summaryBar.getStyleClass().add("env-summary-bar"); summaryBar.setId("env-summary-bar");

        for (var box : List.of(contextBox, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("env.feedback", "section-title"), feedback, tracked.view());
        results.getChildren().add(dashboard); results.setId("env-results");
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), chooser.context(), sessionHint), contextBox, inputs, summaryBar);
        hide(sessionHint); hide(feedbackBox); hide(results); hide(contextBox); hide(inputs); hide(summaryBar); hide(conflicts);
        getChildren().addAll(controls, feedbackBox, results);
        renderLocation();
        texts.onRefresh(this::render);
    }

    /** One structured row per lookup match: name, then region and country with the source and its specificity. */
    private final class CandidateCell extends ListCell<PlaceCandidate> {
        private final Label name = FeaturePane.label("", "env-candidate-name"), detail = FeaturePane.label("", "env-candidate-detail");
        private final VBox box = new VBox(2, name, detail);
        CandidateCell() { getStyleClass().add("env-candidate-cell"); name.setWrapText(true); detail.setWrapText(true); box.setMaxWidth(520); }
        @Override protected void updateItem(PlaceCandidate c, boolean empty) {
            super.updateItem(c, empty);
            setText(null);
            if (empty || c == null) { setGraphic(null); setAccessibleText(null); return; }
            name.setText(c.name());
            var where = String.join(", ", java.util.stream.Stream.of(c.city(), c.region(), c.country()).filter(s -> !s.isBlank() && !s.equals(c.name())).distinct().toList());
            detail.setText((where.isEmpty() ? "" : where + " · ") + EnvironmentPresentation.provenance(c, localization));
            pseudoClassStateChanged(ESTIMATE, c.estimated());
            setGraphic(box);
            setAccessibleText(name.getText() + ", " + detail.getText());
        }
    }

    private TextField input(String key) {
        TextField input = new TextField(); input.setId("env-" + key); fields.put(key, input);
        texts.inputMenu(input); texts.text(input.promptTextProperty(), "env.prompt." + key);
        texts.bind(input.accessibleTextProperty(), () -> localization.text("env.field." + key));
        input.textProperty().addListener((o, old, value) -> {
            if (prefilling) return;
            edited = true;
            if (busy) tracked.cancel();
            invalidateEvidence();
            if (key.equals("location") || key.equals("latitude") || key.equals("longitude")) {
                forgetPlace(key.equals("location"));
            }
            if (key.equals("zone")) renderLocation();
            if (Set.of("date", "from", "until").contains(key)) updateConflicts();
        });
        return input;
    }
    /** Caption above its field, as in the other session forms. */
    private Node column(String key) {
        var input = input(key);
        Label caption = FeaturePane.label("", "env-field-caption"); caption.setLabelFor(input);
        texts.bind(caption.textProperty(), () -> localization.text("env.field." + key));
        VBox column = new VBox(6, caption, input); column.setMinWidth(0);
        fieldColumns.put(key, column);
        return column;
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; tracked.cancel(); texts.close(); tracked.view().dispose(); }
    OperationRunner tracked() { return tracked; }
    @Override public javafx.beans.value.ObservableValue<PhotographySession> selectedSession() { return chooser.selected(); }
    @Override public void useSessions(SessionWorkflowModel sessions) { chooser.use(sessions); }
    /** Reviewed inputs are not saved anywhere; leaving them asks for confirmation like other unsaved drafts. */
    boolean dirty() { return edited; }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    EnvironmentAssessment assessment() { return assessment; }
    EnvironmentDashboard dashboard() { return dashboard; }
    boolean inputsCollapsed() { return inputsCollapsed; }

    /** Editable sessions only (F09 plans upcoming shoots). Selecting a different session prefills from it. */
    void refresh() {
        if (busy) return;
        // Edited inputs or an assessment not yet added keep their session, as drafts do on the other session pages.
        UUID kept = (edited || assessment != null) && session.getValue() != null ? session.getValue().id() : null;
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            session.getItems().setAll(editable);
            chooser.select(editable, kept, true);
            texts.text(sessionHint.textProperty(), "requirement.noSessions");
            if (editable.isEmpty()) show(sessionHint); else hide(sessionHint);
            showSession();
        }, null);
    }

    private void showSession() {
        var selected = session.getValue();
        if (selected == null) { generation++; hide(results); hide(feedbackBox); hide(contextBox); hide(inputs); hide(summaryBar); return; }
        show(contextBox);
        if (!inputsCollapsed) show(inputs);
        texts.bind(contextLines.textProperty(), () -> String.join("\n", EnvironmentPresentation.context(selected, localization))
            + selected.environmentCheck().map(check -> "\n" + localization.text("env.recorded.line", check.place().label(), check.date().toString(),
                check.assessedAt().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString())
                + check.lookupSource().map(s -> " · " + localization.text("env.resolve.source." + s)).orElse("")).orElse(""));
        boolean manualContext = selected.requirements().filter(ShootRequirement::confirmed).isEmpty();
        fields.get("activity").setDisable(!manualContext); fields.get("goals").setDisable(!manualContext);
        if (manualContext) show(activityBox); else hide(activityBox);
        if (!selected.id().equals(prefilledFor)) { hide(feedbackBox); prefill(EnvironmentForm.defaults(selected)); prefilledFor = selected.id(); clearResults(); }
        else if (assessment != null) show(results);
        updateConflicts();
    }
    private void prefill(Map<String, String> values) {
        prefilling = true;
        try { values.forEach((key, value) -> { if (fields.containsKey(key)) fields.get(key).setText(value); }); }
        finally { prefilling = false; }
        generation++; forgetPlace(false); edited = false; manualApplied = false; renderLocation(); updateConflicts();
    }
    private void resetInputs() {
        var selected = session.getValue();
        if (selected == null) return;
        invalidateEvidence(); prefill(EnvironmentForm.defaults(selected)); message(() -> localization.text("env.inputsReset"));
    }

    private void invalidateEvidence() {
        generation++;
        if (session.getValue() != null) app.planning().invalidateEnvironment(session.getValue().id());
        clearResults();
        if (!busy) { hide(feedbackBox); tracked.view().clear(); }
    }
    private void forgetPlace(boolean clearCoordinates) {
        prefilling = true;
        try {
            chosen = null; device = null; assessAfterConfirm = false; manualApplied = false;
            places.getItems().clear(); places.setValue(null);
            if (clearCoordinates) {
                fields.get("latitude").clear(); fields.get("longitude").clear(); fields.get("zone").clear();
            }
        } finally { prefilling = false; }
        locationStatus.pseudoClassStateChanged(ESTIMATE, false);
        texts.text(locationStatus.textProperty(), "env.resolve.pending");
        placeState = PlaceState.IDLE; renderLocation();
    }
    private void changePlace() {
        invalidateEvidence(); forgetPlace(true);
        fields.get("location").requestFocus(); fields.get("location").selectAll();
    }
    private void findPlace() { findPlace(false); }
    private void findPlace(boolean continueAssessment) {
        if (busy) return;
        if (hasCoordinates() && chosen == null) {
            advanced.setExpanded(true);
            message(() -> localization.text("env.resolve.manualPresent"));
            return;
        }
        invalidateEvidence(); forgetPlace(true);
        assessAfterConfirm = continueAssessment;
        String name = fields.get("location").getText();
        texts.text(locationStatus.textProperty(), "env.place.searching");
        placeState = PlaceState.SEARCHING; renderLocation();
        message(() -> localization.text("env.place.searching"));
        run(OperationKind.ENV_LOOKUP, () -> app.planning().location().resolve(name), found -> {
            places.getItems().setAll(found); confirmPlace.setDisable(true);
            texts.text(confirmPlace.textProperty(), "env.resolve.confirm");
            placeState = PlaceState.CANDIDATES;
            if (found.size() == 1) places.setValue(found.getFirst());
            else texts.text(locationStatus.textProperty(), "env.place.many", found.size());
            renderLocation();
            message(() -> localization.text(found.size() == 1 ? "env.place.one" : "env.place.many", found.size()));
            SessionPane.reveal(this, locationCard);
        }, failure -> {
            forgetPlace(false);
            placeState = PlaceState.FAILED;
            texts.bind(locationStatus.textProperty(), () -> String.join(" ", failure.errors(localization)) + " " + localization.text("env.resolve.recover"));
            renderLocation();
            message(locationStatus::getText);
        });
    }
    private void preview(PlaceCandidate candidate) {
        if (prefilling || candidate == null) return;
        invalidateEvidence();
        chosen = null; device = null; manualApplied = false;
        prefilling = true;
        try { fields.get("latitude").clear(); fields.get("longitude").clear(); }
        finally { prefilling = false; }
        locationStatus.pseudoClassStateChanged(ESTIMATE, candidate.estimated());
        confirmPlace.setDisable(false);
        texts.text(confirmPlace.textProperty(), candidate.estimated() ? "env.resolve.accept" : "env.resolve.confirm");
        texts.bind(locationStatus.textProperty(), () -> EnvironmentPresentation.candidate(candidate, places.getItems().indexOf(candidate) + 1, localization)
            + "\n" + localization.text(candidate.estimated() ? "env.resolve.estimateHint" : "env.resolve.wait"));
        placeState = PlaceState.CANDIDATES; renderLocation();
    }
    private void confirmPlace() {
        var candidate = places.getValue();
        if (candidate == null) return;
        boolean resume = assessAfterConfirm;
        invalidateEvidence(); chosen = candidate; edited = true; assessAfterConfirm = false;
        prefilling = true;
        try {
            var values = new LinkedHashMap<String,String>(); EnvironmentForm.apply(values, candidate);
            values.forEach((key, value) -> fields.get(key).setText(value));
        } finally { prefilling = false; }
        confirmPlace.setDisable(true);
        texts.bind(locationStatus.textProperty(), () -> localization.text(candidate.estimated() ? "env.location.estimateAccepted" : "env.location.confirmed")
            + " · " + EnvironmentPresentation.provenance(candidate, localization));
        placeState = PlaceState.CONFIRMED; renderLocation();
        if (resume) assess();
    }

    /**
     * Shows the location card for the current state: hidden before any search; the matches (with the accept button
     * worded for an AI estimate); the confirmed place with its source, specificity, time zone and Change location
     * (candidate controls hidden); a manual or device position; or the lookup failure with the way forward.
     */
    private void renderLocation() {
        boolean candidates = placeState == PlaceState.CANDIDATES && !places.getItems().isEmpty();
        boolean confirmed = placeState == PlaceState.CONFIRMED && chosen != null;
        boolean estimate = candidates && places.getValue() != null && places.getValue().estimated() || confirmed && chosen.estimated();
        boolean shown = placeState != PlaceState.IDLE;
        if (shown) show(locationCard); else hide(locationCard);
        locationCard.pseudoClassStateChanged(ESTIMATE, estimate);
        if (candidates && places.getItems().size() > 1) show(placeBox); else hide(placeBox);
        if (candidates) show(confirmPlace); else hide(confirmPlace);
        if (candidates || confirmed || placeState == PlaceState.MANUAL || placeState == PlaceState.DEVICE) show(changePlace); else hide(changePlace);
        texts.text(changePlace.textProperty(), candidates ? "env.location.searchAgain" : "env.resolve.change");
        String title = switch (placeState) {
            case SEARCHING -> localization.text("env.location.searching");
            case CANDIDATES -> estimate ? localization.text("env.location.estimateTitle") : places.getValue() != null ? places.getValue().displayName()
                : localization.text("env.place.title");
            case CONFIRMED -> chosen == null ? "" : chosen.displayName();
            case MANUAL -> localization.text("env.resolve.source.USER_PROVIDED");
            case DEVICE -> localization.text("env.resolve.source.DEVICE_LOCATION");
            case FAILED -> localization.text("env.location.notFound");
            case IDLE -> "";
        };
        if (candidates && estimate && places.getValue() != null) title = localization.text("env.location.estimateTitle") + " · " + places.getValue().displayName();
        locationTitle.setText(title);
        String zone = fields.get("zone").getText().strip();
        Optional<String> zoneLine = !zone.isEmpty() ? Optional.of(localization.text("env.location.zone", zone, localization.text("env.zoneSource.entered")))
            : confirmed ? chosen.zone().map(z -> localization.text("env.location.zone", z.getId(), localization.text(chosen.estimated() ? "env.zoneSource.estimate" : "env.zoneSource.lookup")))
            : Optional.empty();
        if (confirmed && estimate) zoneLine = Optional.of(zoneLine.map(z -> z + "\n").orElse("") + localization.text("env.location.estimateNotice"));
        locationZone.setText(zoneLine.orElse(""));
        if (zoneLine.isPresent()) show(locationZone); else hide(locationZone);
    }

    private Map<String,String> values() {
        var values = new LinkedHashMap<String,String>();
        fields.forEach((key, field) -> values.put(key, field.isDisabled() ? "" : field.getText()));
        return values;
    }
    private boolean hasCoordinates() {
        return !fields.get("latitude").getText().isBlank() || !fields.get("longitude").getText().isBlank();
    }
    private void applyManual() {
        var input = values();
        var manual = EnvironmentForm.manual(input, app.planning().location());
        if (!manual.isSuccess()) { message(() -> String.join(" ", manual.errors(localization))); return; }
        if (!input.get("zone").isBlank()) {
            var zone = EnvironmentForm.zone(input.get("zone"));
            if (!zone.isSuccess()) { message(() -> String.join(" ", zone.errors(localization))); return; }
        }
        // Reapplying displayed values cannot relabel an accepted estimate as a manual position.
        if (chosen != null || device != null) return;
        invalidateEvidence(); forgetPlace(false); edited = true; manualApplied = true;
        texts.bind(locationStatus.textProperty(), () -> localization.text("env.resolve.source.USER_PROVIDED") + " · "
            + fields.get("latitude").getText().strip() + ", " + fields.get("longitude").getText().strip());
        placeState = PlaceState.MANUAL; renderLocation();
    }

    /** Device location only on this explicit action, after consent; unsupported devices and a denial never prompt. */
    private void deviceLocation() {
        if (busy) return;
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
            invalidateEvidence(); forgetPlace(false); edited = true; device = place;
            texts.text(locationStatus.textProperty(), "env.resolve.source.DEVICE_LOCATION");
            placeState = PlaceState.DEVICE; renderLocation();
            message(() -> localization.text("env.device.used"));
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }

    /** Date and time conflicts with the session, from the same rule the assessment applies; shown before any request. */
    private void updateConflicts() {
        var selected = session.getValue();
        var found = selected == null ? List.<LocalizedMessage>of() : EnvironmentForm.conflicts(selected, values());
        if (found.isEmpty()) { hide(conflicts); return; }
        texts.bind(conflictLines.textProperty(), () -> String.join("\n", found.stream().map(m -> m.render(localization)).toList()));
        show(conflicts);
    }

    private void assess() {
        if (busy) return;
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        if (chosen == null && !hasCoordinates()) {
            if (places.getItems().isEmpty()) findPlace(true);
            else {
                assessAfterConfirm = true; message(() -> localization.text("env.resolve.wait")); SessionPane.reveal(this, locationCard);
            }
            return;
        }
        var values = values();
        var parsed = device != null ? EnvironmentForm.device(values, app.planning().location(), device)
            : EnvironmentForm.parse(values, app.planning().location(), chosen);
        if (!parsed.isSuccess()) { message(() -> String.join(" ", parsed.errors(localization))); return; }
        var request = parsed.value().orElseThrow();
        clearResults(); message(() -> localization.text("env.running"));
        long token = generation;
        // The factual snapshot is analysed on the worker and shown while Gemini is still interpreting it.
        Consumer<EnvironmentAssessment> facts = snapshot -> {
            var analysis = app.planning().analyzeEnvironment(snapshot, true);
            Platform.runLater(() -> showFacts(token, analysis));
        };
        run(OperationKind.ENV_ASSESS, () -> app.assessEnvironment(selected.id(), request, facts), found -> {
            assessment = found; provisional = null; render(); show(results);
            message(() -> localization.text(found.windows().isEmpty() ? "env.doneNoWindows" : "env.done", found.windows().size()));
            compactProgress(true);
            collapseInputs(); dashboard.select(EnvironmentDashboard.View.OVERVIEW);
            SessionPane.reveal(this, results);
        }, failure -> { provisional = null; clearResults(); message(() -> localization.text("env.failedInputsKept", String.join(" ", failure.errors(localization)))); });
    }
    private void showFacts(long token, EnvironmentAnalysis analysis) {
        if (disposed || token != generation || !busy || assessment != null) return;
        provisional = analysis;
        dashboard.recordCard(null);
        dashboard.show(analysis, true); dashboard.busy(true); dashboard.retryState(Optional.of(""));
        show(results); dashboard.select(EnvironmentDashboard.View.OVERVIEW);
        SessionPane.reveal(this, results);
    }

    /** AI-only retry of the shown assessment: the shared service reuses its evidence and asks only Gemini again. */
    private void retryAi() {
        if (busy) return;
        var selected = session.getValue(); var shown = assessment;
        if (selected == null || shown == null) return;
        message(() -> localization.text("env.ai.retrying"));
        run(OperationKind.ENV_RETRY_AI, () -> app.retryEnvironmentInterpretation(selected.id(), shown), found -> {
            assessment = found; render();
            boolean failed = dashboard.analysis() != null && dashboard.analysis().ai().status().failed();
            message(() -> failed ? localization.text("env.ai.retryFailed")
                : localization.text("env.ai.retried") + " " + localization.text(found.windows().isEmpty() ? "env.doneNoWindows" : "env.done", found.windows().size()));
            compactProgress(true);
            dashboard.select(EnvironmentDashboard.View.AI);
        }, failure -> { updateRetryState(); message(() -> String.join(" ", failure.errors(localization))); });
    }
    private void updateRetryState() {
        var selected = session.getValue();
        var shown = dashboard.analysis();
        if (assessment == null || selected == null || shown == null || !shown.ai().status().failed()) { dashboard.retryState(Optional.of("")); return; }
        dashboard.retryState(app.planning().environmentRetryPrecheck(selected.id(), assessment).map(m -> m.render(localization)));
    }

    private void clearResults() {
        assessment = null; provisional = null; dashboard.clear(); hide(results); expandInputs();
    }
    private void collapseInputs() {
        var a = assessment;
        if (a == null || a.request().isEmpty() || a.availability().isEmpty()) return;
        var request = a.request().get(); var av = a.availability().get();
        texts.bind(inputSummary.textProperty(), () -> localization.text("env.inputs.summary", request.place().label(), request.date().toString(),
            EnvironmentPresentation.span(av.start(), av.end(), request.date()), av.start().getZone().getId()));
        inputsCollapsed = true; hide(inputs); show(summaryBar);
    }
    private void expandInputs() {
        inputsCollapsed = false; hide(summaryBar);
        if (session.getValue() != null) show(inputs);
    }

    /** Rebuilds the dashboard from the current snapshot (also on a language change). */
    private void render() {
        renderLocation();
        updateConflicts();
        if (assessment == null) {
            if (provisional != null) { dashboard.recordCard(null); dashboard.show(provisional, true); }
            return;
        }
        dashboard.recordCard(recordCard());
        dashboard.show(app.planning().analyzeEnvironment(assessment, false), false);
        dashboard.busy(busy);
        updateRetryState();
    }

    /**
     * Workflow milestone 04: the reviewed assessment counts for the session only once the user adds it (identifying
     * metadata only, see PlanningService.recordEnvironment); nothing is recorded automatically.
     */
    private VBox recordCard() {
        var selected = session.getValue();
        boolean added = recorded != null && recorded == assessment;
        var text = FeaturePane.label("", "secondary-text"); text.setId("env-record-text");
        if (added) texts.text(text.textProperty(), "env.record.done");
        else if (selected != null && selected.environmentCheck().isPresent())
            texts.text(text.textProperty(), "env.record.replace", selected.environmentCheck().get().date().toString());
        else texts.text(text.textProperty(), "env.record.prompt");
        var box = new VBox(10, FeaturePane.label(localization.text("env.record.title"), "env-card-title"), text);
        box.setId("env-record-card"); box.getStyleClass().add("env-card");
        if (!added) {
            var action = button("env.record.action", "env-record", this::record, true); action.setDisable(busy);
            box.getChildren().add(new FlowPane(12, 10, action));
        }
        return box;
    }
    private void record() {
        var selected = session.getValue(); var shown = assessment;
        if (selected == null || shown == null) return;
        message(() -> localization.text("env.recording"));
        run(OperationKind.ENV_RECORD, () -> app.planning().recordEnvironment(selected, shown), saved -> {
            recorded = shown;
            if (assessment == shown) render();   // the session progress card now says it was added
            message(() -> localization.text("env.recorded"));
            // The chooser takes the stored version (new revision); the shown assessment stays for reference.
            refresh();
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }

    /** A tracked operation: progress, cancellation and next step in the feedback card; same locking as {@link #run}. */
    private <T> void run(OperationKind kind, Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy(true, kind != OperationKind.ENV_LOOKUP);
        compactProgress(false);
        long token = generation;
        tracked.run(kind, operation, result -> {
            if (disposed) return;
            done();
            if (token != generation) return;
            if (result.isSuccess()) success.accept(result.value().orElseThrow()); else failure.accept(result);
        }, () -> { if (disposed) return; done(); if (token == generation) { if (provisional != null && assessment == null) clearResults(); message(() -> localization.text("progress.cancelled")); } });
    }
    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy(true, true);
        long token = generation;
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            done(); if (token != generation) return; var result = task.getValue();
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else if (failure != null) failure.accept(result);
            else message(() -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; done(); message(() -> localization.text("env.failed")); });
        operations.execute(task);
    }
    /** Inputs are locked while an operation runs; the dashboard stays readable, only its actions wait. */
    private void busy(boolean value, boolean lockInputs) {
        busy = value; controls.setDisable(value && lockInputs); dashboard.busy(value);
        if (results.lookup("#env-record") instanceof Button record) record.setDisable(value);
    }
    private void done() { busy(false, false); if (assessment != null) updateRetryState(); }
    /**
     * After a successful assessment or retry the dashboard's stage statuses say how each step ended, so the feedback
     * card keeps only its title, elapsed time and next step; the stage list returns for the next operation.
     */
    private void compactProgress(boolean compact) {
        var stages = tracked.view().lookup("#progress-stages");
        if (stages != null) { stages.setVisible(!compact); stages.setManaged(!compact); }
    }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
