package com.photopilot.ui.gui;

import com.photopilot.application.CapabilityPresentation;
import com.photopilot.application.FocalScale;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import static com.photopilot.application.CapabilityPresentation.*;

/**
 * The Gear Capability Report dashboard: a status header (refresh, copy, last analysis or stale notice), six page-level
 * views (Overview, Camera Bodies, Lenses, Compatibility, Data Quality, Technical Details) and their loading, empty and
 * failure states. It renders one {@link GearCapabilityAnalysis} snapshot; switching views or selecting items never
 * re-analyzes, and views are built when first opened. All numbers and conclusions come from the analysis and all text
 * from {@link CapabilityPresentation} and the bundles. Rebuilt on a language change; colours follow the theme tokens.
 */
final class CapabilityDashboard extends VBox {
    enum View { OVERVIEW, CAMERAS, LENSES, COMPATIBILITY, QUALITY, TECHNICAL }
    /** Selections that survive view switches and language changes for one analysis snapshot. */
    static final class State {
        View view = View.OVERVIEW;
        UUID lens, camera, pairCamera, pairLens, chartCamera, matrixCamera;
        boolean equivalent;
        String lensType = "ALL";
        double apertureLimit;
        String matrixMount;
        PairStatus matrixStatus;
        String matrixSearch = "";
    }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final LocalizationService l;
    private final Set<EquipmentType> types;
    private final Button refresh = new Button(), copy = new Button();
    private final Label status = FeaturePane.label("", "cap-status"), copied = FeaturePane.label("", "cap-status");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final FlowPane tabs = new FlowPane(2, 2);
    private final Map<View, ToggleButton> tabButtons = new EnumMap<>(View.class);
    private final VBox body = new VBox(18);
    private final Map<View, Node> views = new EnumMap<>(View.class);
    private final State state = new State();
    private GearCapabilityAnalysis report;
    private Map<UUID, String> names = Map.of();
    private Consumer<UUID> editEquipment;
    private LocalTime analyzedAt;
    private boolean stale, loading, failed;
    private Supplier<String> failure = () -> "";

    CapabilityDashboard(LocalizationService localization, Set<EquipmentType> types) {
        super(16); this.l = localization; this.types = Set.copyOf(types);
        getStyleClass().add("capability-dashboard"); setId("capability-dashboard"); setMinWidth(0);
        refresh.setId("capability-refresh"); refresh.getStyleClass().add("primary-button");
        copy.setId("capability-copy"); copy.getStyleClass().add("secondary-button"); copy.setDisable(true);
        copy.setOnAction(event -> copyReport());
        status.setId("capability-status"); copied.setId("capability-copied"); copied.setVisible(false); copied.setManaged(false);
        spinner.getStyleClass().add("progress-spinner"); spinner.setPrefSize(18, 18); spinner.setMaxSize(18, 18); spinner.setVisible(false); spinner.setManaged(false);
        var statusLine = new HBox(8, spinner, status, copied); statusLine.setAlignment(Pos.CENTER_LEFT); statusLine.setMinWidth(0);
        HBox.setHgrow(statusLine, Priority.ALWAYS); status.setMaxWidth(Double.MAX_VALUE);
        var actions = new HBox(10, copy, refresh); actions.setAlignment(Pos.CENTER_RIGHT); actions.setMinWidth(Region.USE_PREF_SIZE);
        var header = new HBox(12, statusLine, actions); header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("cap-header");
        var group = new ToggleGroup();
        tabs.setId("capability-tabs"); tabs.getStyleClass().add("cap-tabs");
        for (var view : View.values()) {
            var tab = new ToggleButton(); tab.setId("capability-tab-" + view.name()); tab.getStyleClass().setAll("segment");
            tab.setToggleGroup(group); tab.setMinWidth(Region.USE_PREF_SIZE);
            tab.setOnAction(event -> { tab.setSelected(true); select(view); });
            tabButtons.put(view, tab); tabs.getChildren().add(tab);
        }
        tabs.setPrefWrapLength(2400); tabs.setMaxWidth(Region.USE_PREF_SIZE);
        body.setMinWidth(0);
        getChildren().addAll(header, tabs, body);
        relabel();
        showTabs(false);
    }

    void onRefresh(Runnable action) { refresh.setOnAction(event -> action.run()); }
    /** Opens the existing Gear Inventory editor for one item (never a second editor here). */
    void onEdit(Consumer<UUID> action) { editEquipment = action; views.remove(View.QUALITY); if (state.view == View.QUALITY) render(); }

    void loading() {
        loading = true; refresh.setDisable(true); copy.setDisable(true);
        spinner.setVisible(true); spinner.setManaged(true); hideCopied();
        status.setText(l.text("capability.loading")); status.pseudoClassStateChanged(STALE, false);
        if (report == null) { body.getChildren().clear(); showTabs(false); }
    }
    void show(GearCapabilityAnalysis analysis) {
        loading = false; failed = false; stale = false; report = analysis; analyzedAt = LocalTime.now(); names = names(analysis.equipment());
        refresh.setDisable(false); copy.setDisable(analysis.equipment().isEmpty()); spinner.setVisible(false); spinner.setManaged(false);
        hideCopied(); keepValidSelections();
        views.clear(); updateStatus(); render();
    }
    void failed(Supplier<String> message) {
        loading = false; failed = true; report = null; failure = message; views.clear();
        refresh.setDisable(false); copy.setDisable(true); spinner.setVisible(false); spinner.setManaged(false);
        updateStatus(); render();
    }
    /** The inventory was saved after this analysis: the snapshot stays readable and is marked outdated. */
    void stale() { if (report != null && !loading) { stale = true; updateStatus(); } }
    String statusText() { return status.getText(); }
    GearCapabilityAnalysis report() { return report; }
    State state() { return state; }
    /** Rebuilds every view in the current language. */
    void relabel() {
        refresh.setText(l.text("capability.refresh")); copy.setText(l.text("capability.copy")); copied.setText(l.text("capability.copied"));
        tabs.setAccessibleText(l.text("capability.tabs"));
        tabButtons.forEach((view, tab) -> tab.setText(l.text("capability.tab." + view.name())));
        views.clear(); updateStatus(); render();
    }

    void select(View view) {
        state.view = view;
        var tab = tabButtons.get(view); if (!tab.isSelected()) tab.setSelected(true);
        render();
    }

    private static final javafx.css.PseudoClass STALE = javafx.css.PseudoClass.getPseudoClass("stale");
    private void updateStatus() {
        status.pseudoClassStateChanged(STALE, stale);
        if (loading) status.setText(l.text("capability.loading"));
        else if (failed) status.setText(l.text("capability.failed"));
        else if (report == null) status.setText("");
        else status.setText(stale ? l.text("capability.status.stale") : l.text("capability.status.loaded", TIME.format(analyzedAt)));
    }
    private void showTabs(boolean shown) { tabs.setVisible(shown); tabs.setManaged(shown); }
    private void hideCopied() { copied.setVisible(false); copied.setManaged(false); }

    private void render() {
        if (failed) { showTabs(false); body.getChildren().setAll(failureState()); return; }
        if (report == null) return;
        if (report.equipment().isEmpty()) { showTabs(false); body.getChildren().setAll(emptyState()); return; }
        showTabs(true);
        tabButtons.get(state.view).setSelected(true);
        body.getChildren().setAll(views.computeIfAbsent(state.view, this::build));
    }
    private Node build(View view) {
        var node = switch (view) {
            case OVERVIEW -> overview();
            case CAMERAS -> CapabilityGearViews.cameras(this, report, names, l);
            case LENSES -> CapabilityGearViews.lenses(this, report, names, l);
            case COMPATIBILITY -> CapabilityMatrixView.build(this, report, names, l);
            case QUALITY -> quality();
            case TECHNICAL -> technical();
        };
        node.setId("capability-view-" + view.name());
        return node;
    }

    private void keepValidSelections() {
        Set<UUID> ids = new HashSet<>(); report.equipment().forEach(e -> ids.add(e.id()));
        if (state.lens != null && !ids.contains(state.lens)) state.lens = null;
        if (state.camera != null && !ids.contains(state.camera)) state.camera = null;
        if (state.chartCamera != null && !ids.contains(state.chartCamera)) { state.chartCamera = null; state.equivalent = false; }
        if (state.matrixCamera != null && !ids.contains(state.matrixCamera)) state.matrixCamera = null;
        if (state.pairCamera != null && (!ids.contains(state.pairCamera) || !ids.contains(state.pairLens))) { state.pairCamera = null; state.pairLens = null; }
    }

    // ---- States ------------------------------------------------------------------------------------------------------

    private Node emptyState() {
        var box = new VBox(12, FeaturePane.label(l.text("capability.emptyTitle"), "empty-title"), FeaturePane.label(l.text("capability.empty"), "secondary-text"));
        var open = new Button(l.text("capability.openInventory")); open.setId("capability-open-inventory"); open.getStyleClass().add("secondary-button");
        open.setOnAction(event -> open.fireEvent(new NavigationRequest("F05")));
        box.getChildren().add(open);
        box.getStyleClass().addAll("cap-card", "cap-state"); box.setId("capability-empty");
        return box;
    }
    private Node failureState() {
        var message = FeaturePane.label(failure.get(), "cap-error"); message.setId("capability-error");
        var retry = new Button(l.text("capability.retry")); retry.setId("capability-retry"); retry.getStyleClass().add("secondary-button");
        retry.setOnAction(event -> refresh.fire());
        var box = new VBox(12, FeaturePane.label(l.text("capability.failed"), "empty-title"), message, retry);
        box.getStyleClass().addAll("cap-card", "cap-state"); box.setId("capability-failure");
        return box;
    }

    // ---- Overview ----------------------------------------------------------------------------------------------------

    private Node overview() {
        var metrics = new CardGrid(168, 14, 6); metrics.setId("capability-metrics");
        for (var type : REPORT_TYPES)
            metrics.getChildren().add(metric(type.name().toLowerCase(Locale.ROOT), l.text("capability.metric." + type.name()), String.valueOf(report.units(type)),
                l.text("capability.metric.records", report.records(type))));
        metrics.getChildren().add(metric("span", l.text("capability.metric.span"), report.focalSpan().map(CapabilityPresentation::focal).orElse("—"),
            report.focalSpan().isPresent() ? l.text("capability.metric.spanDetail", report.continuous().size(), report.isolated().size()) : l.text("capability.cli.noLenses")));
        metrics.getChildren().add(metric("aperture", l.text("capability.metric.aperture"), report.largestAperture().map(CapabilityPresentation::fNumber).orElse("—"),
            report.widestLenses().stream().map(e -> names.get(e.id())).reduce((a, b) -> a + ", " + b).orElse(l.text("capability.value.unknown"))));
        metrics.getChildren().add(metric("gaps", l.text("capability.metric.gaps"), String.valueOf(report.gaps().size()),
            report.gaps().isEmpty() ? l.text("capability.none") : String.join("; ", report.gaps().stream().map(g -> l.text("capability.cli.between", mm(g.startMm()), mm(g.endMm()))).toList())));

        var findings = new VBox(8); findings.setId("capability-findings");
        int index = 1;
        for (var finding : report.findings()) {
            var icon = new Region(); icon.getStyleClass().addAll("cap-level-icon", finding.level() == Level.WARN ? "cap-level-warn" : "cap-level-info");
            icon.setMinSize(14, 14); icon.setMaxSize(14, 14);
            var text = FeaturePane.label(finding(finding, report, l), "cap-finding"); text.setId("capability-finding-" + index++);
            text.setAccessibleText(level(finding.level(), l) + ": " + text.getText());
            var row = new HBox(10, icon, text); row.setAlignment(Pos.TOP_LEFT); HBox.setHgrow(text, Priority.ALWAYS); text.setMaxWidth(Double.MAX_VALUE);
            findings.getChildren().add(row);
        }
        var findingsCard = card(l.text("capability.cli.findings"), findings);

        var chart = new FocalCoverageChart(); chart.setId("capability-overview-chart"); chart.setFocusTraversable(false);
        chart.setData(List.of(), new FocalCoverageChart.Combined(l.text("capability.chart.combined"), report.coverage(), report.gaps(), report.overlaps()),
            FocalScale.of(report.coverage()));
        chart.setAccessibleText(l.text("capability.chart.combined") + ": " + intervals(report.coverage(), l));
        var openLenses = link(l.text("capability.openLenses"), "capability-open-lenses", () -> select(View.LENSES));
        var coverageCard = card(l.text("capability.section.COVERAGE"), chart, legend(), FeaturePane.label(l.text("capability.chart.physicalAxis"), "cap-caption"), openLenses);

        var counts = new EnumMap<PairStatus, Long>(PairStatus.class);
        for (var status : PairStatus.values()) counts.put(status, report.pairs().stream().filter(p -> p.mount().status() == status).count());
        var chips = new FlowPane(8, 8);
        for (var status : PairStatus.values()) {
            var chip = chip(status(status, l) + ": " + counts.get(status), statusStyle(status)); chip.setId("capability-pairs-" + status.name());
            chips.getChildren().add(chip);
        }
        var compatibility = card(l.text("capability.section.COMPATIBILITY"), report.pairs().isEmpty() ? FeaturePane.label(l.text("capability.noPairs"), "secondary-text") : chips,
            FeaturePane.label(l.text("capability.matrixNote"), "cap-caption"), link(l.text("capability.openMatrix"), "capability-open-matrix", () -> select(View.COMPATIBILITY)));
        var right = new VBox(16, coverageCard, compatibility);
        var columns = new CardGrid(380, 16, 2); columns.getChildren().addAll(findingsCard, right);
        return new VBox(16, metrics, columns);
    }
    private Node metric(String key, String caption, String value, String detail) {
        var title = FeaturePane.label(caption, "cap-metric-caption");
        var number = FeaturePane.label(value, "cap-metric-value"); number.setId("capability-metric-" + key);
        var sub = FeaturePane.label(detail, "cap-metric-detail");
        var box = new VBox(4, title, number, sub); box.getStyleClass().add("cap-metric");
        box.setAccessibleRole(javafx.scene.AccessibleRole.TEXT); box.setAccessibleText(caption + ": " + value + ". " + detail);
        return box;
    }
    /** Chart legend: zoom bar, prime diamond, gap and overlap swatches with their names. */
    Node legend() {
        var row = new FlowPane(16, 6); row.getStyleClass().add("cap-legend");
        for (var entry : List.of(Map.entry("cap-bar", "capability.chart.legend.zoom"), Map.entry("cap-prime", "capability.chart.legend.prime"),
                Map.entry("cap-gap", "capability.chart.legend.gap"), Map.entry("cap-overlap", "capability.chart.legend.overlap"))) {
            var swatch = new Region(); swatch.getStyleClass().addAll("cap-swatch", entry.getKey());
            double w = entry.getKey().equals("cap-prime") ? 12 : 22, h = entry.getKey().equals("cap-overlap") ? 4 : entry.getKey().equals("cap-prime") ? 12 : 10;
            swatch.setMinSize(w, h); swatch.setPrefSize(w, h); swatch.setMaxSize(w, h);
            var item = new HBox(6, swatch, FeaturePane.label(l.text(entry.getValue()), "cap-caption")); item.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(item);
        }
        return row;
    }

    // ---- Data quality ------------------------------------------------------------------------------------------------

    private Node quality() {
        var box = new VBox(16);
        int complete = report.quality().size() - report.needsAttention().size();
        var summary = FeaturePane.label(l.text("capability.quality.summary", complete, report.quality().size()), "cap-card-title"); summary.setId("capability-quality-summary");
        box.getChildren().add(summary);
        if (report.needsAttention().isEmpty()) box.getChildren().add(FeaturePane.label(l.text("capability.quality.none"), "secondary-text"));
        var grid = new CardGrid(300, 14, 3); grid.setId("capability-quality");
        int index = 1;
        for (var item : report.needsAttention()) grid.getChildren().add(qualityCard(item, index++));
        if (!grid.getChildren().isEmpty()) box.getChildren().add(grid);
        if (!report.mountNaming().isEmpty()) {
            var naming = new VBox(6);
            for (var group : report.mountNaming()) naming.getChildren().add(FeaturePane.label(namingText(group, l), "secondary-text"));
            naming.getChildren().add(FeaturePane.label(l.text("capability.quality.namingAdvice"), "cap-caption"));
            var card = card(l.text("capability.quality.namingTitle"), naming); card.setId("capability-naming");
            box.getChildren().add(card);
        }
        var optional = report.quality().stream().filter(q -> !q.needsAttention() && !q.issues().isEmpty()).toList();
        if (!optional.isEmpty()) {
            var list = new VBox(6);
            for (var item : optional) list.getChildren().add(FeaturePane.label(names.get(item.item().id()) + ": "
                + String.join("; ", item.issues().stream().map(i -> issue(i, l)).toList()), "secondary-text"));
            box.getChildren().add(details(l.text("capability.quality.optionalTitle"), list, "capability-quality-optional"));
        }
        box.getChildren().add(details(l.text("capability.quality.definition"), FeaturePane.label(l.text("capability.quality.keyFields"), "secondary-text"), "capability-quality-definition"));
        return box;
    }
    private Node qualityCard(ItemQuality item, int index) {
        var content = new VBox(8);
        content.getChildren().add(FeaturePane.label(l.text("capability.quality.itemCaption", l.text("gear.type." + item.item().type().name()),
            String.valueOf(item.item().quantity())), "cap-caption"));
        for (var category : IssueCategory.values()) {
            var issues = item.of(category);
            if (issues.isEmpty()) continue;
            var chip = chip(category(category, l), categoryStyle(category));
            var list = FeaturePane.label(String.join("; ", issues.stream().map(i -> issue(i, l)).toList()), "secondary-text");
            var row = new VBox(4, chip, list); content.getChildren().add(row);
        }
        if (editEquipment != null) {
            var edit = new Button(l.text("capability.quality.edit")); edit.setId("capability-edit-" + index); edit.getStyleClass().add("secondary-button");
            UUID id = item.item().id(); edit.setOnAction(event -> editEquipment.accept(id));
            content.getChildren().add(edit);
        }
        var card = card(names.get(item.item().id()), content); card.setId("capability-quality-item-" + index);
        return card;
    }

    // ---- Technical details -------------------------------------------------------------------------------------------

    private Node technical() {
        var assumptions = new VBox(6);
        for (String line : assumptions(l)) assumptions.getChildren().add(FeaturePane.label("• " + line, "secondary-text"));
        var text = new TextArea(all(report, l, types)); text.setId("capability-technical-text");
        text.setEditable(false); text.setWrapText(true); text.setPrefRowCount(22); text.getStyleClass().add("cap-technical");
        var copyHere = new Button(l.text("capability.copy")); copyHere.setId("capability-copy-technical"); copyHere.getStyleClass().add("secondary-button");
        copyHere.setOnAction(event -> copyReport());
        return new VBox(16, card(l.text("capability.section.ASSUMPTIONS"), assumptions),
            card(l.text("capability.technical.title"), FeaturePane.label(l.text("capability.technical.description"), "cap-caption"), text, copyHere));
    }
    private void copyReport() {
        if (report == null) return;
        var content = new ClipboardContent(); content.putString(all(report, l, types));
        Clipboard.getSystemClipboard().setContent(content);
        copied.setVisible(true); copied.setManaged(true);
        copiedTimer.playFromStart();
    }
    /** "Report copied" is a short confirmation, not a lasting status. */
    private final javafx.animation.PauseTransition copiedTimer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
    { copiedTimer.setOnFinished(event -> hideCopied()); }

    // ---- Shared building blocks --------------------------------------------------------------------------------------

    static VBox card(String title, Node... content) {
        var box = new VBox(12); box.getStyleClass().add("cap-card"); box.setMinWidth(0);
        if (title != null && !title.isEmpty()) box.getChildren().add(FeaturePane.label(title, "cap-card-title"));
        box.getChildren().addAll(content);
        return box;
    }
    static Label chip(String text, String style) {
        var chip = FeaturePane.label(text, "cap-chip"); chip.getStyleClass().add(style); chip.setWrapText(false); chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }
    static String statusStyle(PairStatus status) {
        return switch (status) { case ESTABLISHED -> "cap-ok"; case NOT_ESTABLISHED -> "cap-warn"; case CONFLICT -> "cap-bad"; };
    }
    static String categoryStyle(IssueCategory category) {
        return switch (category) { case MISSING, INCONSISTENT -> "cap-warn"; case INVALID -> "cap-bad"; case OPTIONAL -> "cap-neutral"; };
    }
    static Hyperlink link(String text, String id, Runnable action) {
        var link = new Hyperlink(text); link.setId(id); link.getStyleClass().add("cap-link"); link.setOnAction(event -> action.run());
        return link;
    }
    /** Label / value rows; values wrap, labels keep one column. */
    static GridPane rows(Map<String, String> values, String id) {
        var grid = new GridPane(); grid.setHgap(16); grid.setVgap(8); grid.setId(id); grid.getStyleClass().add("cap-details"); grid.setMinWidth(0);
        var keys = new ColumnConstraints(); keys.setMinWidth(90); keys.setPrefWidth(Region.USE_COMPUTED_SIZE); keys.setMaxWidth(230);
        var data = new ColumnConstraints(); data.setHgrow(Priority.ALWAYS); data.setMinWidth(0); data.setFillWidth(true);
        grid.getColumnConstraints().addAll(keys, data);
        int row = 0;
        for (var entry : values.entrySet()) {
            var key = FeaturePane.label(entry.getKey(), "cap-detail-key"); var value = FeaturePane.label(entry.getValue(), "cap-detail-value");
            value.setMaxWidth(Double.MAX_VALUE); value.setId(id + "-" + row);
            grid.add(key, 0, row); grid.add(value, 1, row++);
        }
        return grid;
    }
    /** Read-only choice box whose items (including the null "all" choice) are shown through {@code text}. */
    static <T> void configure(ComboBox<T> box, java.util.function.Function<T, String> text) {
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(T value) { return text.apply(value); }
            @Override public T fromString(String value) { throw new UnsupportedOperationException("Read-only selection"); }
        });
        box.setCellFactory(list -> new ListCell<>() { @Override protected void updateItem(T v, boolean empty) { super.updateItem(v, empty); setText(empty ? null : text.apply(v)); } });
        box.setButtonCell(new ListCell<>() { @Override protected void updateItem(T v, boolean empty) { super.updateItem(v, empty); setText(text.apply(empty ? null : v)); } });
    }
    /** A caption beside its control; the caption is also the control's accessible name. */
    static Node labelled(String caption, Control control) {
        var label = FeaturePane.label(caption, "cap-caption"); label.setLabelFor(control); control.setAccessibleText(caption);
        var box = new HBox(8, label, control); box.setAlignment(Pos.CENTER_LEFT); return box;
    }
    /** A collapsed disclosure section for definitions and secondary lists. */
    static TitledPane details(String title, Node content, String id) {
        var pane = new TitledPane(title, content); pane.setExpanded(false); pane.setAnimated(false); pane.setId(id); pane.getStyleClass().add("cap-disclosure");
        return pane;
    }
}
