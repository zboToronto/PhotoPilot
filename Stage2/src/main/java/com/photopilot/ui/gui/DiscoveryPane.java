package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.application.DiscoveryGuide.Slot;
import com.photopilot.application.DiscoveryInterests.Category;
import com.photopilot.common.Result;
import com.photopilot.i18n.Language;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.progress.OperationContext;
import com.photopilot.validation.CompatibilityValidator;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.skin.DatePickerSkin;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

/**
 * F02 Guided Discovery ("Discover Inspiration") in three stages: 1 Location &amp; Time, 2 Photography Preferences,
 * 3 Discover &amp; Review. Presentation only: the inputs are the {@link DiscoveryForm} fields (preset interest chips add
 * their fixed terms to the free-text interests through {@link DiscoveryInterests}), every check is
 * {@link DiscoveryForm#parse} grouped by {@link DiscoveryGuide}, the request is the existing background
 * {@code app.discover} operation, and every text comes from {@link DiscoveryPresentation} or the bundles. The
 * read-only inventory, the sessions and saved ideas are read through the existing services; nothing is saved unless the
 * user saves an idea. Navigation never discards a value; only Reset does. A response that belongs to an abandoned
 * request (reset, disposed, replaced) is never shown.
 */
final class DiscoveryPane extends VBox {
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid"), LOCATED = PseudoClass.getPseudoClass("located"),
        ERROR = PseudoClass.getPseudoClass("error");
    private static final DiscoveryGuide.Stage LOCATION = DiscoveryGuide.Stage.LOCATION_TIME, PREFERENCES = DiscoveryGuide.Stage.PREFERENCES,
        REVIEW = DiscoveryGuide.Stage.REVIEW;
    private static final double SLIDER_MAX = 200;
    private enum Busy { NONE, LOCAL, LOOKUP, DISCOVER }
    private enum Tab { RESULTS, SAVED }
    /** Where an operation message belongs: the input stages (lookup, device, validation, reset) or the review stage. */
    private enum Scope { INPUTS, REVIEW }

    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final BiConsumer<UUID, ShootRequirement> handoff;
    private final OperationRunner tracked, lookups;

    // Inputs (the DiscoveryForm fields).
    private final Map<String, TextField> fields = new LinkedHashMap<>();
    private final DatePicker date = new DatePicker();
    private final Slider distanceSlider = new Slider(0, SLIDER_MAX, 0);
    private final Map<Category, ToggleButton> chips = new EnumMap<>(Category.class);
    private final CheckBox history = new CheckBox(), newSession = new CheckBox();
    private final ComboBox<PlaceCandidate> places = new ComboBox<>();
    private final ComboBox<PhotographySession> session = new ComboBox<>(), savedSession = new ComboBox<>();
    private final TitledPane advanced = new TitledPane();
    private final List<Node> lockable = new ArrayList<>();
    private final Map<Slot, Label> errorLabels = new EnumMap<>(Slot.class);
    /** The list message's second place: under the Avoid field, for when that list is the one to correct. */
    private final Label avoidError = DiscoveryIdeaCard.text("", "discover-field-error");
    private final Map<Slot, List<LocalizedMessage>> errors = new EnumMap<>(Slot.class);
    /** Input problems only the discovery service finds (a past date, a window already over): kept until that input is edited. */
    private final Map<Slot, List<LocalizedMessage>> serviceErrors = new EnumMap<>(Slot.class);

    // Structure.
    private final DiscoveryStepper stepper;
    private final VBox stage1 = stageCard(1), stage2 = stageCard(2), stage3 = stageCard(3);
    private final Label feedback = FeaturePane.label("", "discover-feedback-text");
    private final HBox feedbackStrip;
    private final Label locationStatus = DiscoveryIdeaCard.text("", "discover-status-line"), windowNote = DiscoveryIdeaCard.text("", "discover-window-note"),
        distanceValue = DiscoveryIdeaCard.text("", "discover-distance-value");
    private final HBox locationStatusRow;
    private final VBox placeBox = new VBox(6), lookupBox = new VBox();
    private final DiscoveryGearSummary gear;
    private final Button next, back, generate, openSaved;
    private final List<Button> resets = new ArrayList<>();
    private final ToggleButton resultsTab = new ToggleButton(), savedTab = new ToggleButton();
    private final VBox resultsView = new VBox(18), savedView = new VBox(16), requestSummary = new VBox(12), conditionRows = new VBox(),
        staleBanner = new VBox(10), loadingCard = new VBox(14), errorCard = new VBox(12), emptyCard = new VBox(12), results = new VBox(18),
        resultsHead = new VBox(18), resultsBody = new VBox(18), savedList = new VBox(12), targetBox = new VBox(10),
        // Holds the finished DISCOVER progress view (its next step and Open action) while the failed/cancelled card shows.
        errorProgress = new VBox();
    private final Label errorText = DiscoveryIdeaCard.text("", "discover-state-text"), savedText = FeaturePane.label("", "discover-text"),
        summaryTitle = DiscoveryIdeaCard.text("", "discover-section-title"), errorTitle = DiscoveryIdeaCard.text("", "discover-state-title"),
        abandonText = DiscoveryIdeaCard.text("", "discover-abandon-text");
    /** Shown next to the disabled Discover action while a request abandoned by Reset is still ending. */
    private final HBox abandonStatus;
    private final Button retry, errorEdit, rediscover;
    /** Result disclosures the user opened (by id), kept while the same results are re-rendered; the results they belong to. */
    private final Set<String> openDisclosures = new HashSet<>();
    private List<UUID> renderedNumbering;

    // UI state (never domain state).
    private DiscoveryGuide.Stage stage = LOCATION, furthest = LOCATION;
    private final EnumSet<DiscoveryGuide.Stage> validated = EnumSet.noneOf(DiscoveryGuide.Stage.class);
    private Tab tab = Tab.RESULTS;
    private boolean savedOpened, cancelled, disposed, edited, prefilling, syncing, deviceUsed;
    private Busy busy = Busy.NONE;
    private long discoverToken;
    /** A request Reset abandoned: a new one waits until it has ended, so the service never mixes the two. */
    private OperationContext abandoned;
    /** The latest operation message: where it belongs, whether it reports a failure, whether it is the outcome of the shown results. */
    private Scope messageScope = Scope.INPUTS;
    private boolean messageFailed, outcomeMessage;
    /** The JVM format locale while the date popup shows the app language; the language its content was built in. */
    private Locale formatLocale;
    private Language popupLanguage;
    /** A typed date the picker could not read; it stays in the editor so the form reports it. */
    private String rejectedDate;
    private Optional<ZoneId> zoneHint = Optional.empty();
    private PlaceCandidate chosen;
    private GearInventory inventory;
    private List<LocalizedMessage> gearProblem;
    /** The request being made, failed or shown, and the form values it was made from. */
    private DiscoveryRequest activeRequest;
    private Map<String, String> requestedValues, shownValues;
    private DiscoveryResult result;
    private List<LocalizedMessage> failure;
    private List<UUID> numbering = List.of();

    DiscoveryPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization, BiConsumer<UUID, ShootRequirement> handoff) {
        super(22); this.app = app; this.operations = operations; this.localization = localization; this.handoff = handoff;
        texts = new FxLocalizer(this, localization); getStyleClass().addAll("inventory-content", "discover-page");
        tracked = new OperationRunner(operations, new OperationProgressView(localization));
        lookups = new OperationRunner(operations, new OperationProgressView(localization));

        // Header: eyebrow, question, description; the saved ideas stay one click away without a request.
        openSaved = DiscoveryIdeaCard.button(texts, "discover.guided.openSaved", "discover-open-saved", AppIcons.BOOKMARK, false);
        openSaved.setOnAction(event -> openSaved());
        var headingText = new VBox(6, texts.label("discover.guided.eyebrow", "discover-eyebrow"), texts.label("discover.guided.title", "discover-title"),
            texts.label("discover.guided.description", "discover-description"));
        headingText.setMinWidth(0); HBox.setHgrow(headingText, Priority.ALWAYS);
        var header = new HBox(16, headingText, openSaved); header.setAlignment(Pos.TOP_LEFT); header.getStyleClass().add("discover-header"); header.setId("discover-header");

        stepper = new DiscoveryStepper(texts, this::go);
        feedback.setId("discover-feedback-text");
        feedbackStrip = new HBox(10, AppIcons.slot(AppIcons.INFO, "discover-feedback-icon", 18), feedback);
        feedbackStrip.setAlignment(Pos.TOP_LEFT); feedbackStrip.getStyleClass().add("discover-feedback"); feedbackStrip.setId("discover-feedback");
        HBox.setHgrow(feedback, Priority.ALWAYS); feedback.setMaxWidth(Double.MAX_VALUE);
        feedback.textProperty().addListener((o, old, value) -> showFeedback());

        for (String key : List.of("location", "latitude", "longitude", "from", "until", "duration", "distance", "interests", "avoid", "portability", "notes")) input(key);
        configureDate();
        configureDistance();
        locationStatusRow = new HBox(10, AppIcons.slot(AppIcons.PIN, "discover-status-icon-pin", 18), locationStatus);

        next = DiscoveryIdeaCard.button(texts, "discover.guided.next", "discover-next", AppIcons.ARROW_RIGHT, true);
        next.getStyleClass().add("discover-primary"); next.setContentDisplay(ContentDisplay.RIGHT); next.setOnAction(event -> go(PREFERENCES));
        back = DiscoveryIdeaCard.button(texts, "discover.guided.back", "discover-back", AppIcons.ARROW_LEFT, false);
        back.setOnAction(event -> go(LOCATION));
        generate = DiscoveryIdeaCard.button(texts, "discover.guided.generate", "discover-generate", AppIcons.SPARKLES, true);
        generate.getStyleClass().add("discover-primary"); generate.setOnAction(event -> generate());
        retry = DiscoveryIdeaCard.button(texts, "discover.guided.retry", "discover-retry", AppIcons.REFRESH, true);
        retry.setOnAction(event -> generate());
        errorEdit = DiscoveryIdeaCard.button(texts, "discover.guided.editConditions", "discover-error-edit", AppIcons.PENCIL, false);
        errorEdit.setOnAction(event -> editConditions());
        rediscover = DiscoveryIdeaCard.button(texts, "discover.guided.rediscover", "discover-rediscover", AppIcons.SPARKLES, true);
        rediscover.setOnAction(event -> generate());
        gear = new DiscoveryGearSummary(texts, this::loadGear);
        lockable.add(gear.refreshButton());
        abandonText.setId("discover-abandon-text"); HBox.setHgrow(abandonText, Priority.ALWAYS); abandonText.setMaxWidth(Double.MAX_VALUE);
        abandonStatus = new HBox(8, AppIcons.slot(AppIcons.HISTORY, "discover-caption-icon", 16), abandonText);
        abandonStatus.setAlignment(Pos.TOP_LEFT); abandonStatus.setId("discover-abandon-status"); abandonStatus.getStyleClass().add("discover-abandon-status");
        hide(abandonStatus);

        buildStage1(); buildStage2(); buildStage3();
        getChildren().addAll(header, stepper, feedbackStrip, stage1, stage2, stage3);
        hide(feedbackStrip);
        prefill(DiscoveryForm.defaults(LocalDate.now()));
        texts.onRefresh(this::refreshAll);
        showStage(false);
    }

    // ---------------------------------------------------------------- construction

    private VBox stageCard(int number) {
        var card = new VBox(24); card.getStyleClass().add("discover-stage"); card.setId("discover-stage-" + number); return card;
    }
    private VBox stageHeading(DiscoveryGuide.Stage of, String subtitleKey, boolean legend) {
        var title = texts.label(of.titleKey(), "discover-stage-title");
        var subtitle = texts.label(subtitleKey, "discover-stage-subtitle");
        var text = new VBox(4, title, subtitle); HBox.setHgrow(text, Priority.ALWAYS); text.setMinWidth(0);
        var row = new HBox(16, text); row.setAlignment(Pos.TOP_LEFT);
        if (legend) { var marker = texts.label("discover.guided.requiredLegend", "discover-caption"); marker.setMinWidth(Region.USE_PREF_SIZE); row.getChildren().add(marker); }
        return new VBox(row);
    }
    private Label section(String key) { return texts.label(key, "discover-section-title"); }
    private static Region separator() { var line = new Region(); line.getStyleClass().add("discover-separator"); line.setMinHeight(1); line.setPrefHeight(1); line.setMaxHeight(1); return line; }

    private void buildStage1() {
        // Location: name, place lookup and device location; resolution status; privacy, always visible.
        var location = fields.get("location");
        var find = DiscoveryIdeaCard.button(texts, "env.find", "discover-find", AppIcons.SEARCH, false); find.setOnAction(event -> findPlace());
        var device = DiscoveryIdeaCard.button(texts, "env.useDevice", "discover-device", AppIcons.PIN, false); device.setOnAction(event -> deviceLocation());
        lockable.addAll(List.of(find, device));
        HBox.setHgrow(location, Priority.ALWAYS); location.setMinWidth(160); location.setMaxWidth(Double.MAX_VALUE);
        var row = new HBox(10, location, find, device); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("discover-location-row");
        // Narrow windows: the two location actions move under the field instead of squeezing it.
        var below = new FlowPane(10, 10); below.setId("discover-location-actions"); hide(below);
        row.widthProperty().addListener((o, old, width) -> {
            boolean narrow = width.doubleValue() > 0 && width.doubleValue() < 600;
            if (narrow == below.isVisible()) return;
            if (narrow) { row.getChildren().removeAll(find, device); below.getChildren().setAll(find, device); }
            else { below.getChildren().clear(); row.getChildren().addAll(find, device); }
            show(below, narrow);
        });
        places.setId("discover-places"); places.setMaxWidth(Double.MAX_VALUE); texts.text(places.accessibleTextProperty(), "env.place.choose");
        texts.choices(places, c -> EnvironmentPresentation.candidate(c, places.getItems().indexOf(c) + 1, localization), () -> localization.text("env.place.choose"));
        places.valueProperty().addListener((o, old, value) -> { if (value != null) choose(value); });
        lockable.add(places);
        var placeCaption = texts.label("env.place.title", "discover-field-label"); placeCaption.setLabelFor(places);
        placeBox.getChildren().addAll(placeCaption, places); placeBox.setId("discover-place-box");
        lookupBox.getChildren().add(lookups.view()); lookupBox.setId("discover-lookup-progress");
        locationStatus.setId("discover-location-status");
        locationStatusRow.setAlignment(Pos.CENTER_LEFT); locationStatusRow.getStyleClass().add("discover-location-status");
        HBox.setHgrow(locationStatus, Priority.ALWAYS);
        var privacy = new HBox(8, AppIcons.slot(AppIcons.LOCK, "discover-caption-icon", 16), texts.label("discover.guided.privacy", "discover-caption"));
        privacy.setAlignment(Pos.TOP_LEFT); privacy.setId("discover-privacy"); HBox.setHgrow(privacy.getChildren().get(1), Priority.ALWAYS);
        var locationSection = new VBox(10, section("discover.guided.section.location"), fieldLabel("location"), row, below, errorLabel(Slot.LOCATION),
            lookupBox, placeBox, locationStatusRow, privacy);
        hide(placeBox); hide(lookupBox);

        // Date and time: native date picker, 24-hour times, the live window length.
        var dateColumn = new VBox(6, fieldLabel("date"), date);
        var times = new CardGrid(130, 12, 2); times.getChildren().addAll(column("from"), column("until"));
        windowNote.setId("discover-window-note");
        var timeNotes = new VBox(4, texts.label("discover.guided.timeFormat", "discover-caption"), texts.label("discover.guided.timeNote", "discover-caption"));
        var when = new VBox(10, section("discover.guided.section.when"), dateColumn, errorLabel(Slot.DATE), times, errorLabel(Slot.TIME), windowNote, timeNotes);

        // Maximum travel distance: the typed value is authoritative; the slider covers a common range.
        var distanceField = fields.get("distance"); distanceField.setPrefColumnCount(6); distanceField.setMaxWidth(140); distanceField.setMinWidth(90);
        distanceValue.setId("discover-distance-value");
        var unit = texts.label("discover.guided.kmUnit", "discover-caption"); unit.setMinWidth(Region.USE_PREF_SIZE);
        var range = texts.label("discover.guided.distanceRange", "discover-caption", (int) DiscoveryRequest.MAX_DISTANCE_KM);
        HBox.setHgrow(range, Priority.ALWAYS); range.setMaxWidth(Double.MAX_VALUE);
        var exact = new HBox(10, distanceField, unit, range); exact.setAlignment(Pos.CENTER_LEFT);
        Label low = texts.label("discover.guided.km", "discover-caption", 0), high = texts.label("discover.guided.km", "discover-caption", (int) SLIDER_MAX);
        var ends = new HBox(low, grow(new Region()), high);
        var distanceCaption = new Label(); distanceCaption.getStyleClass().add("discover-field-label"); distanceCaption.setLabelFor(distanceField);
        texts.bind(distanceCaption.textProperty(), () -> localization.text("discover.guided.exactDistance"));
        var distanceNote = new HBox(8, AppIcons.slot(AppIcons.ROUTE, "discover-caption-icon", 16), texts.label("discover.guided.distanceNote", "discover-caption"));
        distanceNote.setAlignment(Pos.TOP_LEFT); HBox.setHgrow(distanceNote.getChildren().get(1), Priority.ALWAYS);
        var distanceTitle = new Label(); distanceTitle.getStyleClass().add("discover-section-title"); distanceTitle.setWrapText(true);
        texts.bind(distanceTitle.textProperty(), () -> localization.text("requirement.required", localization.text("discover.guided.section.distance")));
        var distance = new VBox(10, distanceTitle, distanceValue, new VBox(2, distanceSlider, ends), distanceCaption, exact, errorLabel(Slot.DISTANCE), distanceNote);
        distance.setId("discover-distance-section");

        var pair = new CardGrid(340, 28, 2); pair.getChildren().addAll(when, distance);

        // Advanced: manual coordinates and the longest activity, collapsed until needed.
        var advancedGrid = new CardGrid(180, 16, 3); advancedGrid.getChildren().addAll(column("latitude"), column("longitude"), column("duration"));
        advanced.setId("discover-advanced"); texts.text(advanced.textProperty(), "discover.guided.advanced");
        advanced.setContent(new VBox(10, advancedGrid, errorLabel(Slot.COORDINATES), errorLabel(Slot.DURATION), texts.label("discover.guided.advancedHint", "discover-caption")));
        advanced.setExpanded(false); advanced.setAnimated(false); advanced.getStyleClass().add("discover-disclosure");

        var reset = resetButton("discover-reset");
        var footer = new DiscoveryFooter(List.of(reset), List.of(next)); footer.setId("discover-footer-1");
        stage1.getChildren().addAll(stageHeading(LOCATION, "discover.guided.stage1.subtitle", true), locationSection, separator(), pair, advanced, footer);
    }

    private void buildStage2() {
        // Interests: preset chips supplement the free text, which is kept exactly as typed.
        var chipRow = new FlowPane(10, 10); chipRow.setId("discover-chips"); chipRow.getStyleClass().add("discover-chips");
        for (var category : Category.values()) chipRow.getChildren().add(chip(category));
        var custom = fields.get("interests"); custom.setMaxWidth(Double.MAX_VALUE);
        var customLabel = texts.label("discover.guided.customInterests", "discover-field-label"); customLabel.setLabelFor(custom);
        var interests = new VBox(10, section("discover.guided.section.interests"), texts.label("discover.guided.interestsHint", "discover-caption"),
            chipRow, new VBox(6, customLabel, custom), errorLabel(Slot.LISTS));

        // A list problem is also shown directly under Avoid when that list is the one to correct.
        avoidError.setId("discover-error-lists-avoid");
        avoidError.setGraphic(AppIcons.slot(AppIcons.WARNING, "discover-error-icon", 16)); avoidError.setGraphicTextGap(8); hide(avoidError);
        var avoidColumn = new VBox(6, fieldLabel("avoid"), fields.get("avoid"), avoidError); avoidColumn.setMinWidth(0);
        var limits = new CardGrid(240, 16, 3); limits.getChildren().addAll(avoidColumn, column("portability"), column("notes"));
        var restrictions = new VBox(10, section("discover.guided.section.limits"), limits, errorLabel(Slot.NOTES));

        history.setId("discover-history"); texts.text(history.textProperty(), "discover.guided.history"); history.setWrapText(true);
        history.selectedProperty().addListener((o, old, value) -> { if (!prefilling) edited("history"); });
        lockable.add(history);
        var historyNote = texts.label("discover.guided.historyNote", "discover-caption", com.photopilot.service.DiscoveryService.MAX_HISTORY);
        historyNote.setId("discover-history-note");
        var historyBox = new VBox(6, section("discover.guided.section.history"), history, historyNote, errorLabel(Slot.HISTORY));

        var aiNotice = new HBox(10, AppIcons.slot(AppIcons.SPARKLES, "discover-caption-icon", 16), texts.label("discover.guided.aiNotice", "discover-caption"));
        aiNotice.setAlignment(Pos.TOP_LEFT); aiNotice.setId("discover-ai-notice"); aiNotice.getStyleClass().add("discover-ai-notice");
        HBox.setHgrow(aiNotice.getChildren().get(1), Priority.ALWAYS);

        // Reset stays apart from Back: secondary on the left, Back next to the primary action on the right.
        var reset = resetButton("discover-reset-2");
        reset.getStyleClass().add("discover-quiet");
        var footer = new DiscoveryFooter(List.of(reset), List.of(back, generate)); footer.setId("discover-footer-2");
        stage2.getChildren().addAll(stageHeading(PREFERENCES, "discover.guided.stage2.subtitle", false), interests, separator(), restrictions, gear,
            historyBox, aiNotice, errorLabel(Slot.GENERAL), abandonStatus, footer);
    }

    private void buildStage3() {
        var group = new ToggleGroup();
        for (var button : List.of(resultsTab, savedTab)) { button.setToggleGroup(group); button.getStyleClass().add("segment"); button.setMnemonicParsing(false); }
        resultsTab.setId("discover-tab-results"); savedTab.setId("discover-tab-saved");
        texts.text(resultsTab.textProperty(), "discover.guided.tab.results"); texts.text(savedTab.textProperty(), "discover.guided.tab.saved");
        resultsTab.setOnAction(event -> { tab = Tab.RESULTS; renderReview(); });
        savedTab.setOnAction(event -> { tab = Tab.SAVED; savedOpened = true; renderReview(); loadSessions(); });
        // A selected segment stays selected (a tab is never "none").
        group.selectedToggleProperty().addListener((o, old, value) -> { if (value == null && old != null) old.setSelected(true); });
        var tabs = new HBox(2, resultsTab, savedTab); tabs.getStyleClass().addAll("segmented", "discover-tabs"); tabs.setId("discover-tabs");
        tabs.setMaxWidth(Region.USE_PREF_SIZE);

        // Request summary: the conditions of the request shown below, never later edits.
        var edit = DiscoveryIdeaCard.button(texts, "discover.guided.editConditions", "discover-edit-conditions", AppIcons.PENCIL, false);
        edit.setOnAction(event -> editConditions());
        var summaryHeader = new HBox(12, summaryTitle, grow(new Region()), edit); summaryHeader.setAlignment(Pos.CENTER_LEFT);
        requestSummary.getChildren().addAll(summaryHeader, conditionRows);
        requestSummary.getStyleClass().add("discover-subcard"); requestSummary.setId("discover-request-summary");

        var staleText = new HBox(10, AppIcons.slot(AppIcons.WARNING, "discover-warn-icon", 18), texts.label("discover.guided.stale", "discover-banner-text"));
        staleText.setAlignment(Pos.TOP_LEFT); HBox.setHgrow(staleText.getChildren().get(1), Priority.ALWAYS);
        staleBanner.getChildren().addAll(staleText, new FlowPane(10, 10, rediscover));
        staleBanner.getStyleClass().add("discover-banner"); staleBanner.setId("discover-stale");

        var spinner = new ProgressIndicator(); spinner.getStyleClass().add("discover-spinner"); spinner.setPrefSize(34, 34); spinner.setMaxSize(34, 34); spinner.setMinSize(34, 34);
        spinner.setFocusTraversable(false);
        var loadingText = new VBox(4, texts.label("discover.guided.loadingTitle", "discover-state-title"), texts.label("discover.guided.loadingText", "discover-state-text"));
        HBox.setHgrow(loadingText, Priority.ALWAYS); loadingText.setMinWidth(0);
        var loadingRow = new HBox(16, spinner, loadingText); loadingRow.setAlignment(Pos.CENTER_LEFT);
        loadingCard.getChildren().addAll(loadingRow, tracked.view());
        loadingCard.getStyleClass().addAll("discover-subcard", "discover-state-card"); loadingCard.setId("discover-loading");

        // Failed or cancelled request: the same card, its title says which; inputs are kept either way.
        errorText.setId("discover-error-text"); errorTitle.setId("discover-error-title");
        var errorHeading = new HBox(12, AppIcons.slot(AppIcons.WARNING, "discover-bad-icon", 22), errorTitle);
        errorHeading.setAlignment(Pos.CENTER_LEFT);
        // Below the actions: the request's finished progress view, with its next step (the manual alternative in
        // Shooting Requirements when Gemini is unavailable) and that step's Open action.
        errorProgress.setId("discover-error-progress");
        errorCard.getChildren().addAll(errorHeading, errorText, new FlowPane(10, 10, retry, errorEdit), errorProgress);
        errorCard.getStyleClass().addAll("discover-subcard", "discover-state-card", "discover-error-card"); errorCard.setId("discover-error");

        var start = DiscoveryIdeaCard.button(texts, "discover.guided.toStart", "discover-empty-start", AppIcons.ARROW_LEFT, false);
        start.setOnAction(event -> go(LOCATION));
        emptyCard.getChildren().addAll(new HBox(12, AppIcons.slot(AppIcons.BULB, "discover-heading-icon", 22), texts.label("discover.guided.emptyTitle", "discover-state-title")),
            texts.label("discover.guided.emptyText", "discover-state-text"), new FlowPane(10, 10, start));
        ((HBox) emptyCard.getChildren().getFirst()).setAlignment(Pos.CENTER_LEFT);
        emptyCard.getStyleClass().addAll("discover-subcard", "discover-state-card"); emptyCard.setId("discover-results-empty");

        // Target session for continuing or saving (same semantics as before the redesign).
        session.setId("discover-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "discover.guided.target");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())),
            () -> localization.text("discover.target.none"));
        session.valueProperty().addListener((o, old, value) -> renderResults());
        newSession.setId("discover-new-session"); texts.text(newSession.textProperty(), "discover.guided.targetNew"); newSession.setWrapText(true);
        newSession.selectedProperty().addListener((o, old, value) -> { session.setDisable(value || busy != Busy.NONE); renderResults(); });
        var targetCaption = texts.label("discover.guided.target", "discover-field-label"); targetCaption.setLabelFor(session);
        var refreshSessions = DiscoveryIdeaCard.button(texts, "discover.guided.refresh", "discover-session-refresh", AppIcons.REFRESH, false);
        texts.bind(refreshSessions.accessibleTextProperty(), () -> localization.text("discover.guided.refreshSessions"));
        refreshSessions.setOnAction(event -> loadSessions());
        targetBox.getChildren().addAll(targetCaption, new HBox(10, grow(session), refreshSessions), newSession, texts.label("discover.guided.targetHint", "discover-caption"));
        targetBox.getStyleClass().add("discover-subcard"); targetBox.setId("discover-target");
        lockable.addAll(List.of(newSession, refreshSessions));

        results.getChildren().addAll(resultsHead, targetBox, resultsBody); results.setId("discover-results");
        // The changed-conditions banner comes first, directly under the tabs, so it is seen before the older results.
        resultsView.getChildren().addAll(staleBanner, requestSummary, loadingCard, errorCard, emptyCard, results);
        resultsView.setId("discover-results-view");

        // Saved ideas: browse any editable session's saved ideas without asking Gemini.
        savedSession.setId("discover-saved-session"); savedSession.setMaxWidth(Double.MAX_VALUE); texts.text(savedSession.accessibleTextProperty(), "discover.guided.savedSession");
        texts.choices(savedSession, s -> localization.text("discover.guided.savedOption",
                localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())), s.savedIdeas().size()),
            () -> localization.text("discover.target.none"));
        savedSession.valueProperty().addListener((o, old, value) -> renderSaved());
        var savedCaption = texts.label("discover.guided.savedSession", "discover-field-label"); savedCaption.setLabelFor(savedSession);
        var refreshSaved = DiscoveryIdeaCard.button(texts, "discover.guided.refresh", "discover-saved-refresh", AppIcons.REFRESH, false);
        texts.bind(refreshSaved.accessibleTextProperty(), () -> localization.text("discover.guided.refreshSessions"));
        refreshSaved.setOnAction(event -> loadSessions());
        lockable.addAll(List.of(savedSession, refreshSaved));
        savedText.setId("discover-saved"); hide(savedText);
        var savedPicker = new VBox(6, savedCaption, new HBox(10, grow(savedSession), refreshSaved), texts.label("discover.guided.savedHint", "discover-caption"));
        savedPicker.getStyleClass().add("discover-subcard");
        savedView.getChildren().addAll(savedPicker, savedList, savedText); savedView.setId("discover-saved-view");

        stage3.getChildren().addAll(stageHeading(REVIEW, "discover.guided.stage3.subtitle", false), tabs, resultsView, savedView);
    }

    private TextField input(String key) {
        TextField input = new TextField(); input.setId("discover-" + key); fields.put(key, input); lockable.add(input);
        texts.inputMenu(input);
        texts.text(input.promptTextProperty(), key.equals("interests") ? "discover.guided.customPrompt" : "discover.prompt." + key);
        texts.bind(input.accessibleTextProperty(), () -> accessibleName(key));
        input.textProperty().addListener((o, old, value) -> {
            if (prefilling) return;
            // Edited coordinates no longer belong to the chosen gazetteer place (or the device), so its zone hint is dropped.
            if ((key.equals("latitude") || key.equals("longitude")) && (chosen != null || deviceUsed)) { zoneHint = Optional.empty(); chosen = null; deviceUsed = false; }
            if (key.equals("location") && lookupBox.isVisible() && busy != Busy.LOOKUP) { lookups.view().clear(); hide(lookupBox); }
            edited(key);
        });
        return input;
    }
    /** Caption above its field; required fields carry the shared "{0} *" marker and say so to assistive technology. */
    private Label fieldLabel(String key) {
        Label caption = new Label(); caption.getStyleClass().add("discover-field-label"); caption.setWrapText(true); caption.setMinHeight(Region.USE_PREF_SIZE);
        caption.setLabelFor(key.equals("date") ? date : fields.get(key));
        texts.bind(caption.textProperty(), () -> DiscoveryGuide.required(key) ? localization.text("requirement.required", label(key)) : label(key));
        return caption;
    }
    private Node column(String key) { var column = new VBox(6, fieldLabel(key), fields.get(key)); column.setMinWidth(0); return column; }
    private String label(String key) {
        return localization.text(switch (key) {
            case "notes" -> "discover.guided.field.notes";
            case "distance" -> "discover.field.distance";
            default -> "discover.field." + key;
        });
    }
    private String accessibleName(String key) {
        if (key.equals("interests")) return localization.text("discover.guided.customInterests");
        return DiscoveryGuide.required(key) ? localization.text("discover.guided.requiredAccessible", label(key)) : label(key);
    }
    private ToggleButton chip(Category category) {
        var chip = new ToggleButton(); chip.setId("discover-chip-" + category.term()); chip.getStyleClass().add("discover-chip"); chip.setMnemonicParsing(false);
        var name = new Label(); name.getStyleClass().add("discover-chip-text"); texts.text(name.textProperty(), category.labelKey());
        var check = AppIcons.slot(AppIcons.CHECK, "discover-chip-check", 14);
        check.visibleProperty().bind(chip.selectedProperty()); check.managedProperty().bind(chip.selectedProperty());
        var graphic = new HBox(8, AppIcons.slot(categoryIcon(category), "discover-chip-icon", 18), name, check); graphic.setAlignment(Pos.CENTER_LEFT);
        chip.setGraphic(graphic);
        texts.bind(chip.accessibleTextProperty(), () -> localization.text(chip.isSelected() ? "discover.guided.chip.selected" : "discover.guided.chip.notSelected",
            localization.text(category.labelKey())));
        chip.selectedProperty().addListener((o, old, value) -> {
            chip.setAccessibleText(localization.text(value ? "discover.guided.chip.selected" : "discover.guided.chip.notSelected", localization.text(category.labelKey())));
            if (!prefilling) edited("interests");
        });
        chips.put(category, chip); lockable.add(chip);
        return chip;
    }
    static String categoryIcon(Category category) {
        return switch (category) {
            case LANDSCAPE -> AppIcons.LANDSCAPE; case STREET -> AppIcons.STREET; case ARCHITECTURE -> AppIcons.ARCHITECTURE;
            case AVIATION -> AppIcons.AVIATION; case WILDLIFE -> AppIcons.WILDLIFE; case PORTRAIT -> AppIcons.PERSON; case NIGHT -> AppIcons.MOON;
            case SPORTS -> AppIcons.SPORTS; case EVENTS -> AppIcons.TICKET;
        };
    }
    private Label errorLabel(Slot slot) {
        var label = DiscoveryIdeaCard.text("", "discover-field-error"); label.setId("discover-error-" + slot.name().toLowerCase(Locale.ROOT));
        label.setGraphic(AppIcons.slot(AppIcons.WARNING, "discover-error-icon", 16)); label.setGraphicTextGap(8);
        errorLabels.put(slot, label); hide(label);
        return label;
    }
    private Button resetButton(String id) {
        var reset = DiscoveryIdeaCard.button(texts, "discover.guided.reset", id, AppIcons.RESET, false);
        reset.setOnAction(event -> resetInputs()); resets.add(reset); return reset;
    }

    /** ISO dates (yyyy-MM-dd) in every language; unparseable text stays visible so the form reports it. */
    private void configureDate() {
        date.setId("discover-date"); date.setEditable(true); date.setShowWeekNumbers(false); date.setMaxWidth(Double.MAX_VALUE);
        lockable.add(date);
        date.setConverter(new StringConverter<>() {
            @Override public String toString(LocalDate value) {
                if (value != null) { rejectedDate = null; return value.format(DateTimeFormatter.ISO_LOCAL_DATE); }
                return rejectedDate == null ? "" : rejectedDate;
            }
            @Override public LocalDate fromString(String text) {
                String value = text == null ? "" : text.strip();
                if (value.isEmpty()) { rejectedDate = null; return null; }
                try { rejectedDate = null; return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE); }
                catch (DateTimeParseException invalid) { rejectedDate = value; return null; }
            }
        });
        // The skin binds the editor's prompt to the picker's prompt.
        texts.text(date.promptTextProperty(), "discover.prompt.date");
        texts.bind(date.accessibleTextProperty(), () -> accessibleName("date"));
        texts.inputMenu(date.getEditor());
        date.valueProperty().addListener((o, old, value) -> {
            // Committing an unparseable entry (Enter, leaving the field) empties the value, and the skin then blanks the
            // editor; the typed text is put back so it stays visible and the form reports it instead of losing it.
            if (value == null && rejectedDate != null) Platform.runLater(() -> {
                if (date.getValue() == null && rejectedDate != null && date.getEditor().getText().isEmpty()) date.getEditor().setText(rejectedDate);
            });
            if (!prefilling) edited("date");
        });
        date.getEditor().textProperty().addListener((o, old, value) -> { if (!prefilling && date.getEditor().isFocused()) edited("date"); });
        // The popup's month and weekday names come from the JVM's format locale, not from the app language. While the
        // popup is open that locale is the app language's; a popup built in another language is rebuilt first.
        date.setOnShowing(event -> {
            if (formatLocale == null) formatLocale = Locale.getDefault(Locale.Category.FORMAT);
            Locale.setDefault(Locale.Category.FORMAT, localization.language().locale());
            if (popupLanguage != null && popupLanguage != localization.language()) date.setSkin(new DatePickerSkin(date));
            popupLanguage = localization.language();
        });
        date.setOnHidden(event -> restoreFormatLocale());
    }
    private void restoreFormatLocale() {
        if (formatLocale != null) { Locale.setDefault(Locale.Category.FORMAT, formatLocale); formatLocale = null; }
    }
    /** The form reads the typed text (also before it is committed), else the picked value. */
    private String dateText() {
        String typed = date.getEditor().getText();
        if (typed != null && !typed.isBlank()) return typed.strip();
        return date.getValue() == null ? "" : date.getValue().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }
    private void configureDistance() {
        distanceSlider.setId("discover-distance-slider"); distanceSlider.getStyleClass().addAll("appearance-slider", "discover-slider");
        distanceSlider.setMajorTickUnit(50); distanceSlider.setMinorTickCount(49); distanceSlider.setSnapToTicks(true); distanceSlider.setBlockIncrement(1);
        distanceSlider.setShowTickMarks(false); distanceSlider.setShowTickLabels(false); distanceSlider.setMaxWidth(Double.MAX_VALUE);
        texts.bind(distanceSlider.accessibleTextProperty(), () -> localization.text("discover.guided.sliderAccessible", (int) SLIDER_MAX));
        lockable.add(distanceSlider);
        var field = fields.get("distance");
        distanceSlider.valueProperty().addListener((o, old, value) -> {
            if (syncing) return;
            syncing = true;
            try { field.setText(String.valueOf(Math.round(value.doubleValue()))); } finally { syncing = false; }
        });
        field.textProperty().addListener((o, old, value) -> {
            if (syncing) return;
            syncing = true;
            try { distanceSlider.setValue(number(value).map(km -> Math.max(0, Math.min(SLIDER_MAX, km))).orElse(0.0)); } finally { syncing = false; }
        });
    }
    /** A plain decimal for display and the slider only; validation stays DiscoveryForm's. */
    private static Optional<Double> number(String text) {
        String value = text == null ? "" : text.strip();
        return value.matches("\\d+(\\.\\d+)?") ? Optional.of(Double.parseDouble(value)) : Optional.empty();
    }

    // ---------------------------------------------------------------- page API (FeaturePane, GuiProfileController, tests)

    /** Opening the page reads local inventory and sessions only: no provider, device or Gemini request. */
    void activate(boolean active) { if (active && busy == Busy.NONE) loadGear(); }
    void dispose() {
        disposed = true; discoverToken++; tracked.cancel(); lookups.cancel();
        if (date.isShowing()) date.hide();
        restoreFormatLocale();
        texts.close(); tracked.view().dispose(); lookups.view().dispose();
    }
    /** The DISCOVER request's runner (its progress view is the loading state's). */
    OperationRunner tracked() { return tracked; }
    /** Reviewed inputs are not saved anywhere; leaving them asks for confirmation like other unsaved drafts. */
    boolean dirty() { return edited; }
    boolean busy() { return busy != Busy.NONE; }
    String feedbackText() { return feedback.getText(); }
    DiscoveryResult result() { return result; }
    DiscoveryGuide.Stage stage() { return stage; }
    DiscoveryStepper stepper() { return stepper; }

    // ---------------------------------------------------------------- values and validation

    private String text(String key) { return fields.get(key).getText().strip(); }
    private EnumSet<Category> selectedCategories() {
        var selected = EnumSet.noneOf(Category.class);
        chips.forEach((category, chip) -> { if (chip.isSelected()) selected.add(category); });
        return selected;
    }
    /** The form values exactly as the request receives them (chips composed into the interests field). */
    Map<String, String> values() {
        var values = new LinkedHashMap<String, String>();
        for (String key : DiscoveryForm.FIELDS) values.put(key, switch (key) {
            case "date" -> dateText();
            case "interests" -> DiscoveryInterests.compose(selectedCategories(), fields.get("interests").getText());
            case "history" -> history.isSelected() ? "yes" : "no";
            default -> fields.get(key).getText();
        });
        return values;
    }
    private Result<DiscoveryRequest> parse(Map<String, String> values) {
        return chosen != null ? DiscoveryForm.parse(values, app.discovery().location(), chosen) : DiscoveryForm.parse(values, app.discovery().location(), zoneHint);
    }
    private boolean stageValid(DiscoveryGuide.Stage of) { return DiscoveryGuide.problems(parse(values()), of).isEmpty(); }

    private void edited(String field) {
        edited = true;
        var slot = slotOf(field);
        serviceErrors.remove(slot);
        if (errors.remove(slot) != null) renderErrors();
        validated.remove(DiscoveryGuide.stage(field));
        renderInputStatus();
    }
    private static Slot slotOf(String field) {
        for (var slot : Slot.values()) if (slot.fields().contains(field)) return slot;
        return Slot.GENERAL;
    }
    /**
     * Replaces the messages of the slots in {@code stages} by {@code problems}; slots of other stages keep theirs. A
     * problem only the service can find stays next to its unchanged input, because the form cannot see it.
     */
    private void setErrors(Set<DiscoveryGuide.Stage> stages, List<LocalizedMessage> problems) {
        errors.keySet().removeIf(slot -> stages.contains(slot.stage()));
        DiscoveryGuide.group(problems).forEach((slot, messages) -> errors.computeIfAbsent(slot, s -> new ArrayList<>()).addAll(messages));
        serviceErrors.forEach((slot, messages) -> {
            if (!stages.contains(slot.stage())) return;
            var shown = errors.computeIfAbsent(slot, s -> new ArrayList<>());
            messages.stream().filter(m -> !shown.contains(m)).forEach(shown::add);
        });
        if (errors.keySet().stream().anyMatch(Slot::advanced)) advanced.setExpanded(true);
        renderErrors();
    }
    private static Set<DiscoveryGuide.Stage> upTo(DiscoveryGuide.Stage last) {
        var stages = EnumSet.noneOf(DiscoveryGuide.Stage.class);
        for (var s : DiscoveryGuide.Stage.values()) if (s.ordinal() <= last.ordinal()) stages.add(s);
        return stages;
    }
    private Node control(String field) { return switch (field) { case "date" -> date; case "history" -> history; default -> fields.get(field); }; }
    /**
     * The inputs a slot's message is about: its fields, except that a list problem names only the list(s) that break the
     * request's rule on their own ({@link DiscoveryGuide#listInputs}), or both when neither does alone.
     */
    private List<String> flagged(Slot slot) {
        if (slot != Slot.LISTS || !errors.containsKey(Slot.LISTS)) return slot.fields();
        var values = values();
        var lists = DiscoveryGuide.listInputs(values.get("interests"), values.get("avoid"));
        return lists.isEmpty() ? slot.fields() : lists;
    }
    /** The first input of the shown stage with a message, if any. */
    private Optional<Node> firstError() {
        return errors.keySet().stream().filter(slot -> slot.stage() == stage).map(slot -> flagged(slot).stream().findFirst().map(this::control).orElse(null))
            .filter(Objects::nonNull).findFirst();
    }
    /**
     * Where keyboard focus goes when a stage is opened: the first input with a message, else the stage's first control
     * (location, first interest, the active tab, or Try again after a failure); the step itself while inputs are locked.
     */
    private Node focusTarget() {
        Node target = firstError().orElseGet(() -> switch (stage) {
            case LOCATION_TIME -> fields.get("location");
            case PREFERENCES -> chips.get(Category.values()[0]);
            case REVIEW -> tab == Tab.SAVED ? savedTab : busy != Busy.DISCOVER && (failure != null || cancelled) ? retry : resultsTab;
        });
        return target.isDisabled() || !target.isVisible() ? stepper.button(stage) : target;
    }
    private void focus(Node target, boolean reveal) {
        if (disposed || target == null || target.getScene() == null || target.isDisabled()) return;
        target.requestFocus();
        // Focus moved by navigation must not select a field's whole text, where one keystroke would replace it.
        if (target instanceof TextInputControl input) { input.deselect(); input.positionCaret(input.getLength()); }
        if (reveal) SessionPane.reveal(this, target);
    }
    private static boolean inside(Node node, Node ancestor) { for (Node n = node; n != null; n = n.getParent()) if (n == ancestor) return true; return false; }
    /** After a request ends on the review stage: Try again gets focus unless the user has moved elsewhere. */
    private void focusReviewOutcome() {
        if (stage != REVIEW || tab != Tab.RESULTS || getScene() == null) return;
        Node owner = getScene().getFocusOwner();
        if (owner != null && !inside(owner, stage3) && !inside(owner, stepper)) return;
        if (failure != null || cancelled) focus(retry, false);
    }

    // ---------------------------------------------------------------- navigation

    /**
     * Opens {@code target}. Entering a stage needs every earlier input stage to be valid: validated when the user left it
     * (and not edited since) or valid now. Otherwise the page stays on (or returns to) the first stage with a problem and
     * shows it next to its input. Going back to an already valid stage never validates and nothing is ever discarded.
     */
    private void go(DiscoveryGuide.Stage target) {
        // Without a request, step 3 only browses saved ideas: nothing to validate, no request is sent.
        if (target == REVIEW && activeRequest == null) { if (savedOpened) openSaved(); return; }
        for (var earlier : List.of(LOCATION, PREFERENCES)) {
            if (earlier.ordinal() >= target.ordinal()) break;
            if (validated.contains(earlier)) continue;
            var problems = DiscoveryGuide.problems(parse(values()), earlier);
            setErrors(upTo(earlier), problems);
            if (!problems.isEmpty()) {
                stage = DiscoveryGuide.firstStage(problems).filter(s -> s != REVIEW).orElse(earlier);
                showStage(true); return;
            }
            validated.add(earlier);
        }
        stage = target;
        showStage(true);
    }
    private void openSaved() {
        savedOpened = true; tab = Tab.SAVED; stage = REVIEW;
        showStage(true); loadSessions();
    }
    /** Back to the inputs: the first stage with a shown problem, else Location &amp; Time. */
    private void editConditions() {
        stage = errors.keySet().stream().map(Slot::stage).filter(s -> s != REVIEW).min(Comparator.naturalOrder()).orElse(LOCATION);
        if (errors.containsKey(Slot.GENERAL) && stage == LOCATION && errors.keySet().stream().allMatch(s -> s == Slot.GENERAL)) stage = PREFERENCES;
        showStage(true);
    }

    /**
     * Shows the current stage. With {@code enter} (a navigation) the page scrolls to the top and keyboard focus moves into
     * the new stage, to its first input with a message when there is one, so keyboard and screen-reader users keep their
     * place.
     */
    private void showStage(boolean enter) {
        show(stage1, stage == LOCATION); show(stage2, stage == PREFERENCES); show(stage3, stage == REVIEW);
        if ((stage != REVIEW || activeRequest != null) && stage.ordinal() > furthest.ordinal()) furthest = stage;
        showFeedback();
        updateStepper();
        if (stage == REVIEW) renderReview();
        if (enter) Platform.runLater(() -> {
            if (disposed || getScene() == null || !isVisible()) return;
            if (getScene().lookup(".content-scroll") instanceof ScrollPane content) content.setVvalue(0);
            boolean error = firstError().isPresent();
            focus(focusTarget(), error);
        });
    }
    private void updateStepper() {
        var states = new EnumMap<DiscoveryGuide.Stage, DiscoveryStepper.State>(DiscoveryGuide.Stage.class);
        var done = EnumSet.noneOf(DiscoveryGuide.Stage.class);
        for (var s : DiscoveryGuide.Stage.values()) {
            boolean completed = s == REVIEW ? result != null : validated.contains(s);
            if (completed) done.add(s);
            states.put(s, s == stage ? DiscoveryStepper.State.CURRENT : completed ? DiscoveryStepper.State.COMPLETED : DiscoveryStepper.State.UPCOMING);
        }
        // Step 2 stays available once reached (a click re-validates step 1 and says why it cannot open yet).
        var enabled = EnumSet.of(LOCATION);
        if (furthest.ordinal() >= PREFERENCES.ordinal() || stageValid(LOCATION)) enabled.add(PREFERENCES);
        if (savedOpened || activeRequest != null) enabled.add(REVIEW);
        stepper.update(stage, states, enabled, done);
    }

    // ---------------------------------------------------------------- rendering

    private void refreshAll() {
        renderInputStatus(); renderErrors(); gear.render(); renderSummary(); renderResults(); renderSaved(); renderReview(); renderAbandon(abandonedRunning());
    }
    private void renderInputStatus() {
        String lat = text("latitude"), lon = text("longitude"), name = text("location");
        boolean coordinates = !lat.isEmpty() && !lon.isEmpty();
        String key = chosen != null ? "discover.guided.status.lookup" : deviceUsed ? "discover.guided.status.device"
            : coordinates ? "discover.guided.status.manual" : !name.isEmpty() ? "discover.guided.status.name" : "discover.guided.status.empty";
        locationStatus.setText(coordinates ? localization.text(key, lat, lon) : localization.text(key));
        locationStatusRow.pseudoClassStateChanged(LOCATED, coordinates);
        ((StackPane) locationStatusRow.getChildren().getFirst()).getChildren().setAll(AppIcons.outline(coordinates ? AppIcons.PIN : AppIcons.INFO, "discover-status-icon-pin"));
        var window = DiscoveryGuide.window(text("from"), text("until"));
        windowNote.setText(window.map(w -> DiscoveryPresentation.window(w, localization)).orElse(""));
        show(windowNote, window.isPresent());
        // The large value shows only a distance the request accepts; anything else reads "— km" until corrected.
        distanceValue.setText(DiscoveryGuide.distance(text("distance")).map(km -> localization.text("discover.guided.km", CompatibilityValidator.num(km)))
            .orElseGet(() -> localization.text("discover.guided.distanceEmpty")));
        updateStepper();
        if (stage == REVIEW) renderReview();
    }
    /** Each message under its input; only the inputs it is about are marked invalid and carry it for assistive technology. */
    private void renderErrors() {
        errorLabels.forEach((slot, label) -> {
            var messages = errors.getOrDefault(slot, List.of());
            String message = DiscoveryPresentation.render(messages, localization);
            boolean any = !messages.isEmpty();
            var about = flagged(slot);
            if (slot == Slot.LISTS) {
                // Under the interests when they are (also) to correct, under Avoid when that list is.
                label.setText(message); show(label, any && about.contains("interests"));
                avoidError.setText(message); show(avoidError, any && about.contains("avoid"));
            } else { label.setText(message); show(label, any); }
            for (String field : slot.fields()) {
                boolean marked = any && about.contains(field);
                var control = control(field);
                control.pseudoClassStateChanged(INVALID, marked);
                control.setAccessibleHelp(marked ? message : null);
            }
        });
    }
    /**
     * Results tab states: loading, failed or cancelled (Try again, Edit conditions), empty, results; the changed-conditions
     * banner when the inputs differ from those of the shown results.
     */
    private void renderReview() {
        boolean discovering = busy == Busy.DISCOVER, stopped = !discovering && (failure != null || cancelled);
        errorTitle.setText(localization.text(cancelled ? "discover.guided.cancelledTitle" : "discover.guided.errorTitle"));
        errorText.setText(cancelled ? localization.text("discover.guided.cancelledText")
            : failure == null ? "" : localization.text("discover.guided.failedText", DiscoveryPresentation.render(failure, localization)));
        resultsTab.setSelected(tab == Tab.RESULTS); savedTab.setSelected(tab == Tab.SAVED);
        show(resultsView, tab == Tab.RESULTS); show(savedView, tab == Tab.SAVED);
        show(requestSummary, activeRequest != null);
        // One progress view: in the loading card while the request runs, in the failed/cancelled card once it stopped,
        // where its final state keeps the next step and its Open action in view.
        Pane host = stopped ? errorProgress : loadingCard;
        if (tracked.view().getParent() != host) {
            if (tracked.view().getParent() instanceof Pane previous) previous.getChildren().remove(tracked.view());
            host.getChildren().add(tracked.view());
        }
        show(loadingCard, discovering);
        show(errorCard, stopped);
        show(emptyCard, !discovering && !stopped && result == null);
        show(results, !discovering && result != null);
        show(staleBanner, stale());
        showFeedback();
    }
    /** Shown results whose conditions the user has edited since. */
    private boolean stale() { return busy != Busy.DISCOVER && result != null && shownValues != null && !values().equals(shownValues); }
    private void renderSummary() {
        conditionRows.getChildren().clear();
        summaryTitle.setText(localization.text(result != null ? "discover.guided.summaryShown" : "discover.guided.summaryTitle"));
        if (activeRequest == null) return;
        var grid = new CardGrid(200, 14, 4);
        for (var condition : DiscoveryPresentation.conditions(activeRequest, localization)) grid.getChildren().add(condition(condition.key(), condition.label(), condition.value()));
        conditionRows.getChildren().add(grid);
        if (result != null) {
            // The equipment used for the shown results: one full-width row (its overview is the longest value).
            var context = result.context();
            var gearRow = condition("gear", localization.text("discover.guided.label.gear"), context.inventoryReadable()
                ? DiscoveryPresentation.gearOverview(context.gear(), localization) : localization.text("discover.context.gearUnreadable"));
            VBox.setMargin(gearRow, new javafx.geometry.Insets(14, 0, 0, 0));
            conditionRows.getChildren().add(gearRow);
        }
    }
    private VBox condition(String key, String label, String value) {
        var box = new VBox(2, DiscoveryIdeaCard.text(label, "discover-condition-label"), DiscoveryIdeaCard.text(value, "discover-condition-value"));
        box.setId("discover-condition-" + key); box.getStyleClass().add("discover-condition"); return box;
    }

    /**
     * Rebuilds the localized result cards (also on language change, after a dismissal, when the target changes and when
     * the sessions are reloaded). Disclosures the user opened stay open while the same results are shown; new results
     * start closed.
     */
    private void renderResults() {
        resultsHead.getChildren().clear(); resultsBody.getChildren().clear();
        if (!numbering.equals(renderedNumbering)) { openDisclosures.clear(); renderedNumbering = numbering; }
        show(targetBox, result != null && !result.usable().isEmpty());
        if (result == null) return;
        var r = result;
        // Counts with a shape and a word each, then the complete result notes (counts, order and application notices).
        var counts = new FlowPane(10, 10); counts.setId("discover-counts");
        for (var status : Feasibility.values()) {
            var chip = DiscoveryIdeaCard.status(status, localization.text("discover.guided.count." + status.name(), r.with(status).size()), "discover-count");
            chip.setId("discover-count-" + status.name()); counts.getChildren().add(chip);
        }
        var summary = DiscoveryIdeaCard.text(String.join("\n", DiscoveryPresentation.summary(r, localization)), "discover-text"); summary.setId("discover-summary");
        var about = new VBox(8, DiscoveryIdeaCard.sectionHeading(AppIcons.INFO, localization.text("discover.guided.about"), "discover-section-icon"), summary);
        about.getStyleClass().add("discover-subcard"); about.setId("discover-about");
        // Evidence the providers did not supply stays in view, so a missing warning is never read as "no risk".
        var gaps = DiscoveryPresentation.gaps(r, localization);
        if (!gaps.isEmpty()) {
            var box = new VBox(6); box.setId("discover-gaps"); box.getStyleClass().add("discover-gaps");
            for (String gap : gaps) box.getChildren().add(DiscoveryIdeaCard.caution(gap, "discover-text"));
            about.getChildren().add(box);
        }
        resultsHead.getChildren().addAll(counts, about);
        if (!r.limitingFactors().isEmpty()) {
            var limits = DiscoveryIdeaCard.text(String.join("\n", DiscoveryPresentation.limits(r, localization)), "discover-text"); limits.setId("discover-limits");
            var card = new VBox(8, DiscoveryIdeaCard.sectionHeading(AppIcons.WARNING, localization.text("discover.card.limits"), "discover-warn-icon"), limits);
            card.getStyleClass().addAll("discover-subcard", "discover-limits-card"); card.setId("discover-limits-card");
            resultsHead.getChildren().add(card);
        }
        if (r.ideas().isEmpty()) {
            var none = new VBox(12, DiscoveryIdeaCard.text(localization.text("discover.summary.none"), "discover-state-title"),
                new FlowPane(10, 10, button("discover.guided.editConditions", "discover-none-edit", AppIcons.PENCIL, false, this::editConditions),
                    button("discover.guided.retry", "discover-none-retry", AppIcons.REFRESH, true, this::generate)));
            none.getStyleClass().addAll("discover-subcard", "discover-state-card"); none.setId("discover-no-ideas");
            resultsHead.getChildren().add(none);
        }
        for (var status : Feasibility.values()) {
            var group = r.with(status);
            if (group.isEmpty()) continue;
            var title = DiscoveryIdeaCard.text(localization.text("discover.guided.group." + status.name()), "discover-group-title");
            title.setId("discover-group-" + status.name());
            var heading = new HBox(10, DiscoveryIdeaCard.mark(status, "discover-group-icon"), title); heading.setAlignment(Pos.CENTER_LEFT);
            resultsBody.getChildren().add(heading);
            for (var idea : group) {
                int number = numbering.indexOf(idea.id()) + 1;
                resultsBody.getChildren().add(DiscoveryIdeaCard.build(idea, number, texts,
                    new DiscoveryIdeaCard.Actions(() -> continueIdea(idea), () -> save(idea), () -> dismiss(idea), savedIn(idea))));
            }
        }
        var aiLines = DiscoveryIdeaCard.text(String.join("\n", DiscoveryPresentation.ai(r, localization)), "discover-text"); aiLines.setId("discover-ai");
        var ai = new VBox(8, DiscoveryIdeaCard.sectionHeading(AppIcons.SPARKLES, localization.text("discover.card.ai"), "discover-section-icon"), aiLines);
        ai.getStyleClass().addAll("discover-subcard", "discover-ai-card"); ai.setId("discover-ai-card");
        var rejected = DiscoveryIdeaCard.text(String.join("\n", DiscoveryPresentation.rejected(r, localization)), "discover-text"); rejected.setId("discover-rejected");
        var context = DiscoveryIdeaCard.text(String.join("\n", DiscoveryPresentation.context(r.context(), localization)), "discover-text"); context.setId("discover-context");
        resultsBody.getChildren().addAll(ai, disclosure("discover-rejected-details", localization.text("discover.guided.rejected", r.rejected().size()), rejected),
            disclosure("discover-context-details", localization.text("discover.card.context"), context));
        keepDisclosures();
        lockResults();
    }
    /** Reopens the disclosures the user opened on these results and records later openings and closings, by id. */
    private void keepDisclosures() {
        for (var node : results.lookupAll(".discover-disclosure")) {
            if (!(node instanceof TitledPane pane) || pane.getId() == null) continue;
            pane.setExpanded(openDisclosures.contains(pane.getId()));
            pane.expandedProperty().addListener((o, old, open) -> { if (open) openDisclosures.add(pane.getId()); else openDisclosures.remove(pane.getId()); });
        }
    }
    private TitledPane disclosure(String id, String title, Node content) {
        var pane = new TitledPane(title, content); pane.setId(id); pane.setExpanded(false); pane.setAnimated(false); pane.getStyleClass().add("discover-disclosure");
        return pane;
    }
    private Button button(String key, String id, String icon, boolean primary, Runnable action) {
        var button = DiscoveryIdeaCard.button(texts, key, id, icon, primary); button.setOnAction(event -> action.run()); return button;
    }
    private Optional<String> savedIn(ActivitySuggestion idea) {
        return chosenSession().filter(s -> s.savedIdeas().stream().anyMatch(saved -> saved.id().equals(idea.id()))).map(PhotographySession::title);
    }

    /** Saved ideas of the chosen session: stored status, place, time and every stored limitation. */
    private void renderSaved() {
        savedList.getChildren().clear();
        var target = savedSession.getValue();
        if (target == null) {
            savedText.setText(localization.text("discover.target.none"));
            savedList.getChildren().add(stateCard("discover-saved-empty", AppIcons.BOOKMARK, localization.text("discover.target.none"), localization.text("discover.guided.savedNoSession")));
            return;
        }
        var ideas = target.savedIdeas();
        savedText.setText(String.join("\n", DiscoveryPresentation.saved(ideas, localization)));
        savedList.setAccessibleText(savedText.getText());
        savedList.getChildren().add(DiscoveryIdeaCard.text(localization.text("discover.guided.savedTitle", target.title()), "discover-section-title"));
        if (ideas.isEmpty()) {
            savedList.getChildren().add(stateCard("discover-saved-empty", AppIcons.BOOKMARK, localization.text("discover.guided.savedNone"), localization.text("discover.guided.savedNoneHint")));
            return;
        }
        for (int i = 0; i < ideas.size(); i++) savedList.getChildren().add(savedCard(target, ideas.get(i), i + 1));
    }
    private VBox savedCard(PhotographySession target, ActivitySuggestion idea, int number) {
        var view = DiscoveryPresentation.view(idea, localization);
        var card = new VBox(10); card.getStyleClass().addAll("discover-idea", "discover-saved-card", "discover-idea-" + DiscoveryIdeaCard.tone(idea.feasibility()));
        card.setId("discover-saved-idea-" + number);
        var top = new HBox(8, DiscoveryIdeaCard.status(idea.feasibility(), view.status(), "discover-status"),
            DiscoveryIdeaCard.text(localization.text("discover.guided.savedOn", idea.suggestedAt().map(t -> t.toString().substring(0, 10))
                .orElse(localization.text("discover.saved.unknownDate"))), "discover-caption"));
        top.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(top, DiscoveryIdeaCard.text(idea.subject(), "discover-idea-title"), DiscoveryIdeaCard.text(view.where(), "discover-fact"));
        if (idea.start().isPresent()) card.getChildren().add(DiscoveryIdeaCard.text(view.when(), "discover-fact"));
        if (!idea.limitations().isEmpty()) {
            card.getChildren().add(DiscoveryIdeaCard.text(localization.text("discover.card.limitations"), "discover-subheading"));
            for (String item : idea.limitations()) card.getChildren().add(DiscoveryIdeaCard.bullet(item, "discover-text"));
        }
        if (idea.feasibility() != Feasibility.INFEASIBLE) {
            var resume = button("discover.continue", "discover-saved-continue-" + number, AppIcons.ARROW_RIGHT, idea.feasibility() == Feasibility.FEASIBLE,
                () -> continueSaved(target, idea));
            resume.setAccessibleText(localization.text("discover.guided.continueSaved", number));
            card.getChildren().add(new FlowPane(10, 10, resume));
        }
        return card;
    }
    private VBox stateCard(String id, String icon, String title, String text) {
        var heading = new HBox(12, AppIcons.slot(icon, "discover-heading-icon", 22), DiscoveryIdeaCard.text(title, "discover-state-title")); heading.setAlignment(Pos.CENTER_LEFT);
        var card = new VBox(8, heading, DiscoveryIdeaCard.text(text, "discover-state-text")); card.getStyleClass().addAll("discover-subcard", "discover-state-card"); card.setId(id);
        return card;
    }

    // ---------------------------------------------------------------- local reads

    private void loadGear() {
        run(() -> app.inventory().list(""), found -> { inventory = found; gearProblem = null; gear.show(found, null); loadSessions(); },
            failed -> { inventory = null; gearProblem = failed.messages(); gear.show(null, gearProblem); loadSessions(); });
    }
    /** Editable sessions only: ideas are saved to, and continued in, upcoming (Draft/Planned) sessions. */
    private void loadSessions() { loadSessionsSelecting(null, () -> { }); }
    /** Reloads the sessions, selects {@code select} (or keeps the selection), re-renders, then runs {@code then}. */
    private void loadSessionsSelecting(UUID select, Runnable then) {
        UUID target = select != null ? select : session.getValue() == null ? null : session.getValue().id();
        UUID browsing = select != null ? select : savedSession.getValue() == null ? null : savedSession.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            session.getItems().setAll(editable); savedSession.getItems().setAll(editable);
            session.setValue(editable.stream().filter(s -> s.id().equals(target)).findFirst().orElse(editable.isEmpty() ? null : editable.getFirst()));
            savedSession.setValue(editable.stream().filter(s -> s.id().equals(browsing)).findFirst().orElse(editable.isEmpty() ? null : editable.getFirst()));
            if (editable.isEmpty()) newSession.setSelected(true);
            renderSaved(); renderResults();
            then.run();
        }, null);
    }

    private void prefill(Map<String, String> values) {
        prefilling = true; rejectedDate = null;
        try {
            values.forEach((key, value) -> { if (fields.containsKey(key) && !key.equals("interests")) fields.get(key).setText(value); });
            var interests = DiscoveryInterests.split(values.getOrDefault("interests", ""));
            chips.forEach((category, chip) -> chip.setSelected(interests.presets().contains(category)));
            fields.get("interests").setText(interests.customText());
            String day = values.getOrDefault("date", "");
            date.getEditor().setText(day);
            try { date.setValue(day.isBlank() ? null : LocalDate.parse(day)); } catch (DateTimeParseException invalid) { date.setValue(null); }
            history.setSelected(!"no".equalsIgnoreCase(values.getOrDefault("history", "yes")));
        } finally { prefilling = false; }
        zoneHint = Optional.empty(); chosen = null; deviceUsed = false; edited = false;
        places.getItems().clear(); hide(placeBox); lookups.view().clear(); hide(lookupBox);
        advanced.setExpanded(false);
    }
    private void resetInputs() {
        if (busy != Busy.NONE && busy != Busy.DISCOVER) return;
        // A running request is abandoned: its late answer is ignored, and a new request waits until it has ended.
        if (busy == Busy.DISCOVER) { abandoned = tracked.current(); discoverToken++; tracked.cancel(); busy = Busy.NONE; }
        prefill(DiscoveryForm.defaults(LocalDate.now()));
        result = null; numbering = List.of(); failure = null; activeRequest = null; requestedValues = null; shownValues = null; cancelled = false;
        errors.clear(); serviceErrors.clear(); validated.clear(); savedOpened = false; tab = Tab.RESULTS; stage = LOCATION; furthest = LOCATION;
        lock();
        post(() -> localization.text("discover.inputsReset"), Scope.INPUTS, false);
        refreshAll(); showStage(true);
    }

    /** Name lookup returns every match; the user picks one (an ambiguous name is never resolved automatically). */
    private void findPlace() {
        if (busy != Busy.NONE) return;
        String name = fields.get("location").getText();
        post(() -> localization.text("env.place.searching"), Scope.INPUTS, false);
        busy = Busy.LOOKUP; lock(); show(lookupBox);
        lookups.run(OperationKind.ENV_LOOKUP, () -> app.discovery().location().search(name), outcome -> {
            if (disposed) return;
            done();
            if (outcome.isSuccess()) {
                var found = outcome.value().orElseThrow();
                places.getItems().setAll(found); places.setValue(null); show(placeBox); lookups.view().clear(); hide(lookupBox);
                errors.remove(Slot.LOCATION); renderErrors();
                post(() -> localization.text(found.size() == 1 ? "env.place.one" : "env.place.many", found.size()), Scope.INPUTS, false);
            } else {
                places.getItems().clear(); hide(placeBox);
                var problems = new ArrayList<>(outcome.messages()); problems.add(LocalizedMessage.of("discover.place.manualFallback"));
                errors.put(Slot.LOCATION, problems); renderErrors();
                post(() -> DiscoveryPresentation.render(problems, localization), Scope.INPUTS, true);
            }
        }, () -> { if (disposed) return; done(); hide(lookupBox); post(() -> localization.text("progress.cancelled"), Scope.INPUTS, false); });
    }
    private void choose(PlaceCandidate candidate) {
        prefilling = true;
        try {
            fields.get("location").setText(candidate.displayName());
            fields.get("latitude").setText(EnvironmentForm.coordinate(candidate.latitude()));
            fields.get("longitude").setText(EnvironmentForm.coordinate(candidate.longitude()));
        } finally { prefilling = false; }
        chosen = candidate; zoneHint = candidate.zone(); deviceUsed = false;
        edited("location"); serviceErrors.remove(Slot.COORDINATES); errors.remove(Slot.COORDINATES); renderErrors();
        post(() -> localization.text("env.place.selected", candidate.displayName()), Scope.INPUTS, false);
    }
    /** Device location only on this explicit action, after consent; unsupported devices and a denial never prompt. */
    private void deviceLocation() {
        var location = app.discovery().location();
        if (!location.deviceSupported()) { post(() -> localization.text("env.location.error.deviceUnsupported"), Scope.INPUTS, true); return; }
        if (location.consentDenied()) { post(() -> localization.text("env.location.error.denied"), Scope.INPUTS, true); return; }
        if (!GuiProfileController.confirm(getScene() == null ? null : getScene().getWindow(), localization,
                "env.device.consentTitle", "env.device.consentBody", "env.device.allow")) {
            location.recordDenial(); post(() -> localization.text("env.device.declined"), Scope.INPUTS, false); return;
        }
        run(() -> location.current(true), place -> {
            prefilling = true;
            try {
                fields.get("location").setText(place.label());
                fields.get("latitude").setText(EnvironmentForm.coordinate(place.latitude().orElseThrow()));
                fields.get("longitude").setText(EnvironmentForm.coordinate(place.longitude().orElseThrow()));
            } finally { prefilling = false; }
            chosen = null; zoneHint = Optional.empty(); deviceUsed = true;
            edited("location");
            post(() -> localization.text("env.device.used"), Scope.INPUTS, false);
        }, failed -> post(() -> String.join(" ", failed.errors(localization)), Scope.INPUTS, true));
    }

    // ---------------------------------------------------------------- the request

    private void generate() {
        if (busy != Busy.NONE || abandonedRunning()) return;
        var values = values();
        var parsed = parse(values);
        if (!parsed.isSuccess()) {
            var problems = parsed.messages();
            setErrors(EnumSet.allOf(DiscoveryGuide.Stage.class), problems);
            var first = DiscoveryGuide.firstStage(problems).filter(s -> s != REVIEW).orElse(PREFERENCES);
            validated.removeIf(s -> s.ordinal() >= first.ordinal());
            for (var s : List.of(LOCATION, PREFERENCES)) if (s.ordinal() < first.ordinal()) validated.add(s);
            post(() -> localization.text("discover.guided.checkInputs", DiscoveryPresentation.render(problems, localization)), Scope.INPUTS, true);
            stage = first; showStage(true);
            return;
        }
        // The service checks its own inputs again, so problems it reported last time are cleared with the new request.
        serviceErrors.clear();
        setErrors(EnumSet.allOf(DiscoveryGuide.Stage.class), List.of());
        validated.add(LOCATION); validated.add(PREFERENCES);
        var request = parsed.value().orElseThrow();
        result = null; numbering = List.of(); failure = null; cancelled = false; shownValues = null;
        activeRequest = request; requestedValues = values;
        tab = Tab.RESULTS; stage = REVIEW;
        long token = ++discoverToken;
        post(() -> localization.text("discover.guided.running"), Scope.REVIEW, false);
        busy = Busy.DISCOVER; lock();
        // On failure the reviewed inputs stay exactly as they were and no idea is shown.
        tracked.run(OperationKind.DISCOVER, () -> app.discover(request), outcome -> {
            if (ignored(token)) return;
            done();
            if (outcome.isSuccess()) {
                var found = outcome.value().orElseThrow();
                result = found; shownValues = requestedValues; numbering = found.ideas().stream().map(ActivitySuggestion::id).toList();
                post(() -> localization.text(found.feasible().isEmpty() ? "discover.guided.doneNoneFeasible" : "discover.guided.done", found.feasible().size(), found.unverified().size()),
                    Scope.REVIEW, false);
                outcomeMessage = true;
            } else {
                result = null; failure = outcome.messages();
                var inline = failure.stream().filter(m -> DiscoveryGuide.slot(m) != Slot.GENERAL).toList();
                if (!inline.isEmpty()) {
                    DiscoveryGuide.group(inline).forEach((slot, messages) -> {
                        errors.put(slot, new ArrayList<>(messages)); serviceErrors.put(slot, new ArrayList<>(messages)); validated.remove(slot.stage());
                    });
                    if (errors.keySet().stream().anyMatch(Slot::advanced)) advanced.setExpanded(true);
                    renderErrors();
                }
                var failed = failure;
                post(() -> localization.text("discover.guided.failedText", DiscoveryPresentation.render(failed, localization)), Scope.REVIEW, true);
            }
            refreshReview();
            // Results: bring the counts and the first cards into view; a failure: focus Try again.
            if (result != null && stage == REVIEW && tab == Tab.RESULTS) SessionPane.reveal(this, resultsHead);
            else focusReviewOutcome();
        }, () -> {
            if (ignored(token)) return;
            done(); cancelled = true;
            post(() -> localization.text("progress.cancelled"), Scope.REVIEW, false);
            refreshReview();
            focusReviewOutcome();
        });
        refreshReview();
        showStage(true);
    }
    /** A completion to drop: the page was disposed or the request was replaced or abandoned. Ends a pending abandonment. */
    private boolean ignored(long token) {
        if (abandoned != null && abandoned.finished()) { abandoned = null; if (!disposed) lock(); }
        return disposed || token != discoverToken;
    }
    private boolean abandonedRunning() {
        if (abandoned != null && abandoned.finished()) abandoned = null;
        return abandoned != null;
    }
    private void refreshReview() {
        renderSummary(); renderResults(); renderReview(); updateStepper();
    }

    // ---------------------------------------------------------------- idea actions

    private record Target(PhotographySession session, boolean created) { }
    private record Handoff(Target target, ShootRequirement draft) { }
    /** The chosen editable session, or empty when a new Draft session named after the idea is wanted. */
    private Optional<PhotographySession> chosenSession() { return newSession.isSelected() ? Optional.empty() : Optional.ofNullable(session.getValue()); }

    /**
     * Unconfirmed F03 draft opened in the requirement page; F03 owns review, clarification and confirmation. A new
     * session is created by DiscoveryService only once the idea passed its checks, so a refusal leaves no empty session.
     */
    private void continueIdea(ActivitySuggestion idea) {
        post(() -> localization.text("discover.preparing"), Scope.REVIEW, false);
        var target = chosenSession();
        run(() -> {
            if (target.isEmpty()) {
                var made = app.discovery().selectInNewSession(idea);
                return made.isSuccess() ? Result.success(new Handoff(new Target(made.value().orElseThrow().session(), true), made.value().orElseThrow().draft()))
                    : Result.<Handoff>failure(made);
            }
            var draft = app.discovery().select(target.get().id(), idea);
            return draft.isSuccess() ? Result.success(new Handoff(new Target(target.get(), false), draft.value().orElseThrow())) : Result.<Handoff>failure(draft);
        }, done -> {
            if (done.target().created()) loadSessions();
            post(() -> localization.text("discover.selected", idea.subject(), done.target().session().title()), Scope.REVIEW, false);
            handoff.accept(done.target().session().id(), done.draft());
        }, failed -> failedAction(() -> localization.text("discover.guided.handoffFailed", String.join(" ", failed.errors(localization)))));
    }
    private void continueSaved(PhotographySession target, ActivitySuggestion idea) {
        run(() -> app.discovery().select(target.id(), idea), draft -> {
            post(() -> localization.text("discover.selected", idea.subject(), target.title()), Scope.REVIEW, false);
            handoff.accept(target.id(), draft);
        }, failed -> failedAction(() -> localization.text("discover.guided.handoffFailed", String.join(" ", failed.errors(localization)))));
    }
    private void save(ActivitySuggestion idea) {
        post(() -> localization.text("discover.guided.saving"), Scope.REVIEW, false);
        var target = chosenSession();
        int number = numbering.indexOf(idea.id()) + 1;
        run(() -> {
            if (target.isEmpty()) return app.discovery().saveInNewSession(idea);
            var saved = app.discovery().save(target.get().id(), idea);
            return saved.isSuccess() ? Result.success(target.get()) : Result.<PhotographySession>failure(saved);
        }, stored -> {
            post(() -> localization.text("discover.saved", idea.subject(), stored.title()), Scope.REVIEW, false);
            newSession.setSelected(false); loadSessionsSelecting(stored.id(), () -> refocusCard(number));
        }, failed -> failedAction(() -> localization.text("discover.guided.saveFailed", String.join(" ", failed.errors(localization)))));
    }
    /**
     * After a save the results were locked and re-rendered, so keyboard focus left the card (to the sidebar or nowhere).
     * It returns to the saved card's next action (Continue, else Dismiss), unless the user has moved it elsewhere on the page.
     */
    private void refocusCard(int number) {
        if (disposed || getScene() == null || stage != REVIEW || tab != Tab.RESULTS || !showing(this)) return;
        Node owner = getScene().getFocusOwner();
        if (owner != null && inside(owner, this) && !inside(owner, results) && showing(owner)) return;
        for (String id : List.of("discover-continue-" + number, "discover-dismiss-" + number)) {
            Node target = results.lookup("#" + id);
            if (target != null && !target.isDisabled() && showing(target)) { focus(target, false); return; }
        }
    }
    private static boolean showing(Node node) { for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible()) return false; return node.getScene() != null; }
    private void dismiss(ActivitySuggestion idea) {
        var removed = app.discovery().dismiss(idea.id());
        if (!removed.isSuccess()) { failedAction(() -> String.join(" ", removed.errors(localization))); return; }
        result = app.discovery().latest().orElse(null); renderResults(); renderReview(); updateStepper();
        post(() -> localization.text("discover.dismissed", idea.subject()), Scope.REVIEW, false);
    }
    /** A failed card action: the message is shown at the top of the stage, so the page scrolls to it. */
    private void failedAction(Supplier<String> text) { post(text, Scope.REVIEW, true); SessionPane.reveal(this, feedbackStrip); }

    // ---------------------------------------------------------------- operations and locking

    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy != Busy.NONE) return;
        busy = Busy.LOCAL; lock();
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            done(); var outcome = task.getValue();
            if (outcome.isSuccess()) success.accept(outcome.value().orElseThrow());
            else if (failure != null) failure.accept(outcome);
            else post(() -> String.join("; ", outcome.errors(localization)), stage == REVIEW ? Scope.REVIEW : Scope.INPUTS, true);
        });
        task.setOnFailed(e -> { if (disposed) return; done(); post(() -> localization.text("discover.failed"), stage == REVIEW ? Scope.REVIEW : Scope.INPUTS, true); });
        operations.execute(task);
    }
    private void done() { busy = Busy.NONE; lock(); }
    /**
     * While an operation runs the inputs and actions are disabled; navigation stays available, and Reset can abandon a
     * request. A new request also waits for an abandoned one to end.
     */
    private void lock() {
        boolean locked = busy != Busy.NONE, finishing = abandonedRunning(), waiting = locked || finishing;
        for (var node : lockable) node.setDisable(locked);
        session.setDisable(locked || newSession.isSelected());
        generate.setDisable(waiting); retry.setDisable(waiting); rediscover.setDisable(waiting);
        renderAbandon(finishing);
        for (var reset : resets) reset.setDisable(locked && busy != Busy.DISCOVER);
        lockResults();
        if (stage == REVIEW) renderReview();
    }
    /** Why Discover is unavailable after Reset: the abandoned request is still ending (shown, and given to assistive technology). */
    private void renderAbandon(boolean finishing) {
        abandonText.setText(finishing ? localization.text("discover.guided.abandoning") : "");
        show(abandonStatus, finishing);
        for (var action : List.of(generate, retry, rediscover)) action.setAccessibleHelp(finishing ? abandonText.getText() : null);
    }
    private void lockResults() { results.setDisable(busy != Busy.NONE); savedList.setDisable(busy != Busy.NONE); }
    /** Shows an operation message in the strip; {@code failed} gives it the warning shape and the danger border. */
    private void post(Supplier<String> message, Scope scope, boolean failed) {
        messageScope = scope; messageFailed = failed; outcomeMessage = false;
        texts.bind(feedback.textProperty(), message);
        showFeedback();
    }
    /**
     * The strip shows the latest message on the stages it belongs to (input messages on steps 1 and 2, request and card
     * messages on step 3), except while the results show the same failure in their own card, and except the outcome of
     * results whose conditions have since been edited (the changed-conditions banner says so instead).
     */
    private void showFeedback() {
        String text = feedback.getText();
        boolean here = (stage == REVIEW) == (messageScope == Scope.REVIEW);
        boolean duplicate = stage == REVIEW && tab == Tab.RESULTS && busy == Busy.NONE && (failure != null || cancelled) && messageScope == Scope.REVIEW;
        boolean outdated = outcomeMessage && stage == REVIEW && stale();
        feedbackStrip.pseudoClassStateChanged(ERROR, messageFailed);
        ((StackPane) feedbackStrip.getChildren().getFirst()).getChildren().setAll(AppIcons.outline(messageFailed ? AppIcons.WARNING : AppIcons.INFO, "discover-feedback-icon"));
        show(feedbackStrip, text != null && !text.isEmpty() && here && !duplicate && !outdated);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private static void show(Node node, boolean visible) { node.setVisible(visible); node.setManaged(visible); }
    private static void show(Node node) { show(node, true); }
    private static void hide(Node node) { show(node, false); }
}
