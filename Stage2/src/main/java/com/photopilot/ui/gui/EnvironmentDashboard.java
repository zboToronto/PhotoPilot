package com.photopilot.ui.gui;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAnalysis.*;
import com.photopilot.service.EnvironmentAnalyzer;
import com.photopilot.service.SolarCalculator;
import java.time.*;
import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

import static com.photopilot.application.EnvironmentPresentation.*;

/**
 * The Environment Assessment result dashboard: an overall status line with the stage statuses, six internal views
 * (Overview, Weather Forecast, Sun and Light, Shooting Windows, AI Analysis, Technical Details) and their cards and
 * charts. It renders one {@link EnvironmentAnalysis} snapshot: switching views, metrics or hours never requests or
 * recalculates anything, and every number and conclusion comes from the analysis (text from
 * {@link EnvironmentPresentation} and the bundles, as in the CLI report). Views are built together so their content is
 * always that of the same snapshot; the dashboard is rebuilt on a language change. Colours follow the theme tokens.
 */
final class EnvironmentDashboard extends VBox {
    enum View { OVERVIEW, WEATHER, LIGHT, WINDOWS, AI, TECHNICAL }
    private final LocalizationService l;
    private final Label overall = FeaturePane.label("", "env-overall"), assessed = FeaturePane.label("", "env-caption"), copied = FeaturePane.label("", "env-caption");
    private final FlowPane stages = new FlowPane(8, 8), tabs = new FlowPane(2, 2);
    private final Map<View, ToggleButton> tabButtons = new EnumMap<>(View.class);
    private final Map<View, Node> views = new EnumMap<>(View.class);
    private final StackPane body = new StackPane();
    private final Button copy = new Button();
    private final List<Button> retryButtons = new ArrayList<>();
    private final List<Label> retryHints = new ArrayList<>();
    private View view = View.OVERVIEW;
    private String trendMetric = "temperatureC";
    private int selectedHour = -1;
    private EnvironmentAnalysis analysis;
    private boolean provisional, busy;
    private Optional<String> retryRefusal = Optional.of("");
    private Runnable retry = () -> { }, record = () -> { };
    private Node recordCard;
    private WeatherTrendChart trend;
    private SolarElevationChart solar;
    private IntervalTimelineChart lightTimeline, windowTimeline;
    private final List<ToggleButton> hourCards = new ArrayList<>();
    private Label weatherDetail;

    EnvironmentDashboard(LocalizationService localization) {
        super(16); this.l = localization;
        getStyleClass().add("env-dashboard"); setId("env-dashboard"); setMinWidth(0);
        overall.setId("env-overall"); assessed.setId("env-assessed"); copied.setId("env-copied"); copied.setVisible(false); copied.setManaged(false);
        copy.setId("env-copy"); copy.getStyleClass().add("secondary-button"); copy.setOnAction(event -> copyReport()); copy.setMinWidth(Region.USE_PREF_SIZE);
        var titles = new VBox(2, overall, new HBox(10, assessed, copied)); HBox.setHgrow(titles, Priority.ALWAYS); titles.setMinWidth(0);
        var header = new HBox(12, titles, copy); header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("env-header");
        stages.setId("env-stages"); stages.getStyleClass().add("env-stages");
        var group = new ToggleGroup();
        tabs.setId("env-tabs"); tabs.getStyleClass().add("env-tabs");
        for (var v : View.values()) {
            var tab = new ToggleButton(); tab.setId("env-tab-" + v.name()); tab.getStyleClass().setAll("segment");
            tab.setToggleGroup(group); tab.setMinWidth(Region.USE_PREF_SIZE);
            tab.setOnAction(event -> { tab.setSelected(true); select(v); });
            tabButtons.put(v, tab); tabs.getChildren().add(tab);
        }
        tabs.setPrefWrapLength(2400); tabs.setMaxWidth(Region.USE_PREF_SIZE);
        body.setMinWidth(0); body.setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(header, stages, tabs, body);
    }

    void onRetry(Runnable action) { retry = Objects.requireNonNull(action); }
    void onRecord(Runnable action) { record = Objects.requireNonNull(action); }
    /** The pane's session progress card, shown at the end of the overview. */
    void recordCard(Node card) { recordCard = card; }
    /** Empty: an AI-only retry is possible now; otherwise the reason it is not (blank: not offered at all). */
    void retryState(Optional<String> refusal) { retryRefusal = refusal; applyRetryState(); }
    void busy(boolean value) { busy = value; applyRetryState(); }
    EnvironmentAnalysis analysis() { return analysis; }
    boolean provisional() { return provisional; }
    View view() { return view; }
    WeatherTrendChart trendChart() { return trend; }
    SolarElevationChart solarChart() { return solar; }
    IntervalTimelineChart lightTimeline() { return lightTimeline; }
    IntervalTimelineChart windowTimeline() { return windowTimeline; }
    List<ToggleButton> hourCards() { return hourCards; }

    /** Shows {@code value}; {@code factsOnly} marks a snapshot whose Gemini interpretation is still running. */
    void show(EnvironmentAnalysis value, boolean factsOnly) {
        boolean same = analysis != null && value != null && analysis.assessment() == value.assessment();
        analysis = value; provisional = factsOnly;
        if (!same) selectedHour = -1;
        rebuild();
    }
    void clear() { analysis = null; views.clear(); body.getChildren().clear(); stages.getChildren().clear(); hourCards.clear(); }

    void select(View next) {
        view = next;
        var tab = tabButtons.get(next); if (!tab.isSelected()) tab.setSelected(true);
        views.forEach((key, node) -> { boolean shown = key == next; node.setVisible(shown); node.setManaged(shown); });
    }

    /** Rebuilds every view from the current snapshot in the current language (selection and metric kept). */
    void rebuild() {
        if (analysis == null) return;
        tabs.setAccessibleText(l.text("env.tabs"));
        tabButtons.forEach((v, tab) -> tab.setText(l.text("env.tab." + v.name())));
        copy.setText(l.text("env.technical.copy")); copied.setText(l.text("env.technical.copied"));
        overall.setText(overall(analysis, l));
        assessed.setText(provisional ? l.text("env.dashboard.provisional")
            : l.text("env.dashboard.assessed", time(analysis.assessment().assessedAt().atZone(analysis.zone()), null)));
        stages.getChildren().clear();
        for (var stage : analysis.stages()) {
            var chip = statusChip(stage(stage.key(), l) + ": " + status(stage.status(), l), stage.status());
            chip.setId("env-stage-" + stage.key().name());
            stage.detail().ifPresent(d -> { var tip = new Tooltip(d.render(l)); tip.setWrapText(true); tip.setMaxWidth(360); chip.setTooltip(tip); });
            chip.setAccessibleText(stageLine(stage, l));
            stages.getChildren().add(chip);
        }
        retryButtons.clear(); retryHints.clear(); hourCards.clear();
        views.clear();
        for (var v : View.values()) {
            var node = switch (v) {
                case OVERVIEW -> overview();
                case WEATHER -> weather();
                case LIGHT -> light();
                case WINDOWS -> windows();
                case AI -> ai();
                case TECHNICAL -> technical();
            };
            node.setId("env-view-" + v.name());
            views.put(v, node);
        }
        body.getChildren().setAll(views.values());
        select(view);
        applyRetryState();
    }

    private void applyRetryState() {
        boolean offered = retryRefusal.isEmpty() || !retryRefusal.get().isBlank();
        for (var button : retryButtons) {
            button.setVisible(offered); button.setManaged(offered);
            button.setDisable(busy || provisional || retryRefusal.isPresent());
        }
        for (var hint : retryHints) {
            String text = retryRefusal.filter(r -> !r.isBlank()).orElse("");
            hint.setText(text); hint.setVisible(!text.isEmpty()); hint.setManaged(!text.isEmpty());
        }
    }

    // ---- Overview -----------------------------------------------------------------------------------------------------

    private Node overview() {
        var a = analysis;
        var glyph = glyph(a.window().primaryCondition(), a.window().samples().isEmpty() ? a.availability().start().toInstant() : a.window().samples().getFirst().time(), 56);
        var temperature = FeaturePane.label(windowValue(a, "temperatureC", l), "env-hero-value"); temperature.setId("env-hero-temp");
        var condition = FeaturePane.label(condition(a, l), "env-hero-condition"); condition.setId("env-hero-condition");
        var scope = FeaturePane.label(scope(a, l), "env-caption"); scope.setId("env-hero-scope");
        var headline = new HBox(16, glyph, new VBox(2, temperature, condition)); headline.setAlignment(Pos.CENTER_LEFT);
        var weather = new VBox(10, FeaturePane.label(l.text("env.overview.title"), "env-card-title"), headline, scope,
            FeaturePane.label(l.text("env.overview.daily", dailySummary(a, l)), "env-caption"));
        var info = new LinkedHashMap<String, String>();
        info.put(l.text("env.info.location"), placeLine(a));
        info.put(l.text("env.info.source"), provenance(a, l));
        info.put(l.text("env.info.date"), a.date().toString());
        info.put(l.text("env.info.available"), availabilityLine(a, l));
        info.put(l.text("env.info.zone"), a.zone().getId() + " (UTC" + a.availability().start().getOffset().getId() + ")");
        info.put(l.text("env.info.subject"), a.assessment().subject());
        a.request().leadTime().filter(d -> !d.isZero()).ifPresent(d -> info.put(l.text("env.info.lead"), minutes(d, l)));
        var heroGrid = new CardGrid(300, 24, 2); heroGrid.getChildren().addAll(weather, rows(info, "env-hero-info"));
        var hero = card(null, heroGrid); hero.getStyleClass().add("env-hero"); hero.setId("env-hero");

        var metrics = new CardGrid(168, 14, 5); metrics.setId("env-metrics");
        String windowCaption = l.text("env.overview.windowCaption");
        metrics.getChildren().add(metricCard("precipitationProbabilityPct", metric("precipitationProbabilityPct", l), windowValue(a, "precipitationProbabilityPct", l),
            daily(a, "precipitationProbabilityMaxPct", l).map(v -> l.text("env.overview.dayMax", v)).orElse(windowCaption)));
        metrics.getChildren().add(metricCard("precipitationMm", metric("precipitationMm", l), windowValue(a, "precipitationMm", l),
            daily(a, "precipitationSumMm", l).map(v -> l.text("env.overview.dayTotal", v)).orElse(windowCaption)));
        metrics.getChildren().add(metricCard("cloudCoverPct", metric("cloudCoverPct", l), windowValue(a, "cloudCoverPct", l), windowCaption));
        metrics.getChildren().add(metricCard("windSpeedKmh", metric("windSpeedKmh", l), windowValue(a, "windSpeedKmh", l), l.text("env.overview.direction", directions(a, l))));
        metrics.getChildren().add(metricCard("windGustsKmh", metric("windGustsKmh", l), windowValue(a, "windGustsKmh", l),
            daily(a, "windGustsMaxKmh", l).map(v -> l.text("env.overview.dayMax", v)).orElse(windowCaption)));
        metrics.getChildren().add(metricCard("visibilityM", metric("visibilityM", l), windowValue(a, "visibilityM", l), windowCaption));
        for (String kind : List.of("sunrise", "sunset")) {
            var event = a.event(a.date(), kind);
            metrics.getChildren().add(metricCard(kind, sunEvent(kind, l), event.map(e -> clock(e.time().atZone(a.zone()), a.date())).orElse(l.text("env.value.unavailable")),
                event.map(e -> l.text("env.events.providerTag", e.source())).orElse(l.text("env.source.sunNone"))));
        }
        metrics.getChildren().add(phaseMetric("golden", l.text("env.overview.golden")));
        metrics.getChildren().add(phaseMetric("blue", l.text("env.overview.blue")));

        var findingsBox = new VBox(8); findingsBox.setId("env-findings");
        var key = a.keyFindings();
        if (key.isEmpty()) findingsBox.getChildren().add(FeaturePane.label(l.text("env.findings.none"), "env-text"));
        int index = 1;
        for (var finding : key) findingsBox.getChildren().add(findingRow(finding, "env-finding-" + index++));
        var findingsCard = card(l.text("env.section.findings"), findingsBox);

        var statusCard = card(l.text("env.section.status"), stageGrid("env-status-grid"));
        var aiLines = new VBox(4);
        var aiChip = statusChip(aiStatus(a.ai(), l), a.stage(StageKey.AI).map(Stage::status).orElse(StageStatus.SKIPPED)); aiChip.setId("env-ai-status");
        aiLines.getChildren().add(aiChip);
        // The failure reason is already in the stage row above; here only what remains usable.
        if (a.ai().status() != AiStatus.SUCCESS) {
            aiLines.getChildren().add(FeaturePane.label(l.text("env.ai.factsKept"), "env-text"));
            if (a.windows().isEmpty()) aiLines.getChildren().add(FeaturePane.label(l.text("env.ai.noValidated"), "env-text"));
        }
        statusCard.getChildren().addAll(aiLines, retryRow("env-retry-ai"));
        var columns = new CardGrid(380, 16, 2); columns.getChildren().addAll(findingsCard, statusCard);
        var box = new VBox(16, hero, metrics, columns);
        if (recordCard != null) box.getChildren().add(recordCard);
        return box;
    }
    private Node phaseMetric(String family, String caption) {
        var a = analysis;
        var candidates = a.relevantOverlaps().stream().filter(o -> o.phase().kind().startsWith(family)).toList();
        var shown = candidates.stream().filter(o -> o.overlap().isPresent() && !o.contact()).findFirst().or(() -> candidates.stream().findFirst());
        String value = shown.map(o -> span(o.phase().start(), o.phase().end(), a.date())).orElse(l.text("env.value.none"));
        Duration total = family.equals("golden") ? a.goldenOverlap() : a.blueOverlap();
        String detail = shown.isEmpty() ? l.text("env.finding." + family + (a.overlaps().stream().anyMatch(o -> o.phase().kind().startsWith(family)) ? "None" : "Absent"))
            : total.isZero() ? l.text("env.overview.noOverlap") : l.text("env.overview.overlap", total.toMinutes());
        return metricCard(family, caption, value, detail);
    }

    // ---- Weather forecast ---------------------------------------------------------------------------------------------

    private Node weather() {
        var a = analysis;
        var box = new VBox(16);
        if (a.hours().isEmpty()) {
            var none = FeaturePane.label(l.text("env.weather.none"), "env-text"); none.setId("env-weather-none");
            box.getChildren().add(card(l.text("env.weather.title"), none, FeaturePane.label(dailyLine(), "env-caption")));
            trend = null; weatherDetail = null;
            return box;
        }
        var group = new ToggleGroup();
        var row = new HBox(10); row.setId("env-hours");
        for (int i = 0; i < a.hours().size(); i++) {
            var hour = a.hours().get(i);
            var card = hourCard(hour, i); card.setToggleGroup(group);
            hourCards.add(card); row.getChildren().add(card);
        }
        var scroll = new ScrollPane(row); scroll.getStyleClass().add("env-hours-scroll"); scroll.setFitToHeight(true);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // A horizontal-only scroll pane clips its content unless the viewport keeps the row's full height.
        scroll.prefViewportHeightProperty().bind(row.heightProperty()); scroll.minViewportHeightProperty().bind(row.heightProperty());
        var legend = new FlowPane(16, 6, legendItem("env-hour-window-swatch", l.text("env.weather.inWindow")), legendItem("env-hour-context-swatch", l.text("env.weather.context")));
        var hoursCard = card(l.text("env.weather.title"), scroll, legend, FeaturePane.label(l.text("env.weather.directionNote"), "env-caption"));

        trend = new WeatherTrendChart(l); trend.setId("env-trend-chart");
        trend.setData(a, trendMetric);
        trend.onSelect(this::selectHour);
        var metricTabs = new FlowPane(2, 2); metricTabs.getStyleClass().add("env-segments"); metricTabs.setId("env-trend-metrics");
        metricTabs.setMaxWidth(Region.USE_PREF_SIZE); metricTabs.setAccessibleText(l.text("env.weather.metric"));
        var metricGroup = new ToggleGroup();
        for (String key : EnvironmentAnalysis.METRICS) {
            var toggle = new ToggleButton(metric(key, l)); toggle.getStyleClass().setAll("segment"); toggle.setId("env-trend-" + key);
            toggle.setToggleGroup(metricGroup); toggle.setSelected(key.equals(trendMetric)); toggle.setMinWidth(Region.USE_PREF_SIZE);
            toggle.setOnAction(event -> { toggle.setSelected(true); trendMetric = key; trend.metric(key); trendAccessible(); });
            metricTabs.getChildren().add(toggle);
        }
        weatherDetail = FeaturePane.label(l.text("env.weather.selectHint"), "env-text"); weatherDetail.setId("env-weather-detail");
        boolean single = a.hours().size() == 1;
        var chartCard = card(l.text("env.weather.chart"), metricTabs, trend, weatherDetail);
        if (single) chartCard.getChildren().add(FeaturePane.label(l.text("env.weather.single"), "env-caption"));
        trendAccessible();
        var source = FeaturePane.label(freshness(a, l) + " · " + l.text("env.weather.forecastNote"), "env-caption"); source.setId("env-weather-source");
        var sourceCard = card(l.text("env.weather.sourceTitle"), source, FeaturePane.label(dailyLine(), "env-caption"),
            FeaturePane.label(l.text("env.cli.windowMark", availabilityLine(a, l)), "env-caption"));
        box.getChildren().addAll(hoursCard, chartCard, sourceCard);
        if (selectedHour >= 0 && selectedHour < hourCards.size()) selectHour(selectedHour);
        return box;
    }
    private String dailyLine() { return l.text("env.overview.daily", dailySummary(analysis, l)); }
    private void trendAccessible() {
        if (trend == null) return;
        var parts = new ArrayList<String>();
        for (var hour : analysis.hours()) parts.add(clock(hour.local(), analysis.date()) + " " + hour.value(trendMetric).map(v -> value(trendMetric, v, l)).orElse(l.text("env.value.unavailable")));
        trend.setAccessibleRole(AccessibleRole.TEXT);
        trend.setAccessibleText(metric(trendMetric, l) + ": " + String.join("; ", parts));
    }
    private ToggleButton hourCard(HourSample hour, int index) {
        var box = new VBox(4); box.setAlignment(Pos.TOP_CENTER);
        var when = FeaturePane.label(clock(hour.local(), analysis.date()), "env-hour-time");
        var tag = FeaturePane.label(l.text(hour.inWindow() ? "env.weather.inWindow" : "env.weather.context"), "env-hour-tag");
        var temperature = FeaturePane.label(hour.value("temperatureC").map(v -> value("temperatureC", v, l)).orElse("—"), "env-hour-value");
        var condition = FeaturePane.label(hour.value("weatherCode").map(c -> condition(c.intValue(), l)).orElse(l.text("env.value.unavailable")), "env-hour-line");
        condition.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        box.getChildren().addAll(when, tag, glyph(hour.value("weatherCode").map(Double::intValue), hour.time(), 34), temperature, condition);
        for (String key : List.of("precipitationProbabilityPct", "cloudCoverPct", "windSpeedKmh", "windGustsKmh", "visibilityM")) {
            String text = shortMetric(key, l) + " " + hour.value(key).map(v -> value(key, v, l)).orElse("—");
            if (key.equals("windSpeedKmh")) text += hour.value("windDirectionDeg").map(d -> " " + compass(d)).orElse("");
            box.getChildren().add(FeaturePane.label(text, "env-hour-line"));
        }
        var card = new ToggleButton(); card.setGraphic(box); card.getStyleClass().setAll("env-hour", hour.inWindow() ? "env-hour-window" : "env-hour-context");
        card.setId("env-hour-" + index); card.setMinWidth(116); card.setPrefWidth(116);
        card.setAccessibleText(hourDetail(hour));
        card.setOnAction(event -> { card.setSelected(true); selectHour(index); });
        return card;
    }
    private String hourDetail(HourSample hour) {
        return EnvironmentPresentation.hour(new EnvironmentContext.Hour(hour.time(), hour.values()), analysis.zone(), analysis.date(), l)
            + (hour.inWindow() ? "" : " · " + l.text("env.weather.context"));
    }
    /** Selects one forecast hour in the cards, the chart and the detail line together. */
    void selectHour(int index) {
        if (index < 0 || index >= hourCards.size()) return;
        selectedHour = index;
        hourCards.get(index).setSelected(true);
        if (trend != null && trend.selected() != index) trend.select(index);
        if (weatherDetail != null) weatherDetail.setText(hourDetail(analysis.hours().get(index)));
    }
    int selectedHour() { return selectedHour; }
    String weatherDetail() { return weatherDetail == null ? "" : weatherDetail.getText(); }
    String trendMetric() { return trendMetric; }

    // ---- Sun and light ------------------------------------------------------------------------------------------------

    private Node light() {
        var a = analysis;
        solar = new SolarElevationChart(l); solar.setId("env-solar-chart");
        var markers = new ArrayList<SolarElevationChart.Marker>();
        for (var event : a.events()) if (Set.of("sunrise", "solarNoon", "sunset").contains(event.kind()))
            markers.add(new SolarElevationChart.Marker(event.time(), sunEvent(event.kind(), l)));
        solar.setData(a, markers);
        var readout = FeaturePane.label(l.text("env.solar.hint"), "env-text"); readout.setId("env-solar-readout");
        solar.onHover(text -> readout.setText(text.isEmpty() ? l.text("env.solar.hint") : text));
        solar.setAccessibleRole(AccessibleRole.TEXT);
        solar.setAccessibleText(l.text("env.solar.title") + ". " + String.join("; ", a.solar().stream().filter(s -> s.time().getMinute() == 0 && s.time().getHour() % 2 == 0)
            .map(s -> clock(s.time(), a.date()) + " " + degrees(s.elevation()) + "°").toList()));
        var solarCard = card(l.text("env.solar.title"), solar, readout, legend(List.of(IntervalTimelineChart.Tone.GOLDEN, IntervalTimelineChart.Tone.BLUE, IntervalTimelineChart.Tone.AVAILABLE)),
            FeaturePane.label(l.text("env.phase.notice"), "env-caption"));

        lightTimeline = new IntervalTimelineChart(l); lightTimeline.setId("env-light-timeline");
        var bands = new ArrayList<IntervalTimelineChart.Segment>();
        for (var band : a.bands()) bands.add(new IntervalTimelineChart.Segment(band.start().toInstant(), band.end().toInstant(), tone(band.kind()), EnvironmentPresentation.light(band.kind(), l)));
        var phases = new ArrayList<IntervalTimelineChart.Segment>();
        for (var overlap : a.overlaps()) phases.add(segment(overlap.phase()));
        var availability = new IntervalTimelineChart.Segment(a.availability().start().toInstant(), a.availability().end().toInstant(), IntervalTimelineChart.Tone.AVAILABLE, l.text("env.timeline.available"));
        lightTimeline.setData(List.of(new IntervalTimelineChart.Row(l.text("env.timeline.bands"), bands), new IntervalTimelineChart.Row(l.text("env.timeline.phases"), phases),
            new IntervalTimelineChart.Row(l.text("env.timeline.available"), List.of(availability))), sunMarkers(), a.from(), a.to(), Optional.empty(), a.zone(), a.date());
        lightTimeline.setAccessibleRole(AccessibleRole.TEXT);
        lightTimeline.setAccessibleText(l.text("env.timeline.title") + ". " + String.join("; ", bands.stream().map(s -> s.label() + " " + span(s.start().atZone(a.zone()), s.end().atZone(a.zone()), a.date())).toList()));
        var timelineCard = card(l.text("env.timeline.title"), lightTimeline, legend(List.of(IntervalTimelineChart.Tone.NIGHT, IntervalTimelineChart.Tone.ASTRONOMICAL,
            IntervalTimelineChart.Tone.NAUTICAL, IntervalTimelineChart.Tone.CIVIL, IntervalTimelineChart.Tone.DAY, IntervalTimelineChart.Tone.GOLDEN, IntervalTimelineChart.Tone.BLUE)),
            FeaturePane.label(l.text("env.timeline.sources"), "env-caption"));

        var events = new CardGrid(168, 14, 4); events.setId("env-events");
        for (String kind : List.of("sunrise", "solarNoon", "sunset")) {
            var event = a.event(a.date(), kind);
            events.getChildren().add(metricCard("event-" + kind, sunEvent(kind, l), event.map(e -> clock(e.time().atZone(a.zone()), a.date())).orElse(l.text("env.value.unavailable")),
                event.map(e -> l.text("env.events.providerTag", e.source())).orElse(l.text("env.source.sunNone"))));
        }
        for (String kind : List.of("goldenMorning", "goldenEvening", "blueMorning", "blueEvening")) {
            var phase = a.overlaps().stream().map(PhaseOverlap::phase).filter(p -> p.kind().equals(kind) && p.date().equals(a.date())).findFirst();
            events.getChildren().add(metricCard("event-" + kind, phase(kind, l), phase.map(p -> span(p.start(), p.end(), a.date())).orElse(l.text("env.value.none")),
                phase.isPresent() ? l.text("env.events.estimateTag") : l.text("env.events.absent")));
        }
        var twilight = new LinkedHashMap<String, String>();
        for (var event : a.events()) if (!Set.of("sunrise", "solarNoon", "sunset").contains(event.kind()) || !event.date().equals(a.date()))
            twilight.put(sunEvent(event.kind(), l) + (event.date().equals(a.date()) ? "" : " (" + event.date() + ")"), clock(event.time().atZone(a.zone()), a.date()) + " · " + event.source());
        for (var overlap : a.overlaps()) if (!overlap.phase().date().equals(a.date()))
            twilight.put(phase(overlap.phase().kind(), l) + " (" + overlap.phase().date() + ")", span(overlap.phase().start(), overlap.phase().end(), a.date()) + " · " + l.text("env.events.estimateTag"));
        var twilightNode = twilight.isEmpty() ? FeaturePane.label(l.text("env.events.noTwilight"), "env-text") : rows(twilight, "env-twilight");
        var details = new VBox(6, FeaturePane.label(l.text("env.light.definitions"), "env-text"), FeaturePane.label(l.text("env.solar.model", EnvironmentAnalyzer.SOLAR_STEP.toMinutes()), "env-text"),
            FeaturePane.label(l.text("env.solar.azimuthNote"), "env-text"), FeaturePane.label(l.text("env.light.limitations"), "env-text"));
        var eventsCard = card(l.text("env.events.title"), events, details(l.text("env.events.twilight"), twilightNode, "env-twilight-details"),
            details(l.text("env.light.details"), details, "env-light-details"));
        return new VBox(16, solarCard, timelineCard, eventsCard);
    }
    private List<IntervalTimelineChart.Marker> sunMarkers() {
        var markers = new ArrayList<IntervalTimelineChart.Marker>();
        for (var event : analysis.events()) if (event.kind().equals("sunrise") || event.kind().equals("sunset"))
            markers.add(new IntervalTimelineChart.Marker(event.time(), sunEvent(event.kind(), l)));
        return markers;
    }
    private IntervalTimelineChart.Segment segment(EnvironmentAssessment.LightPhase phase) {
        return new IntervalTimelineChart.Segment(phase.start().toInstant(), phase.end().toInstant(),
            phase.golden() ? IntervalTimelineChart.Tone.GOLDEN : IntervalTimelineChart.Tone.BLUE, EnvironmentPresentation.phase(phase.kind(), l));
    }
    private static IntervalTimelineChart.Tone tone(LightKind kind) {
        return switch (kind) {
            case NIGHT -> IntervalTimelineChart.Tone.NIGHT;
            case ASTRONOMICAL -> IntervalTimelineChart.Tone.ASTRONOMICAL;
            case NAUTICAL -> IntervalTimelineChart.Tone.NAUTICAL;
            case CIVIL -> IntervalTimelineChart.Tone.CIVIL;
            case DAY -> IntervalTimelineChart.Tone.DAY;
        };
    }

    // ---- Shooting windows ---------------------------------------------------------------------------------------------

    private Node windows() {
        var a = analysis;
        var summary = new CardGrid(200, 14, 4); summary.setId("env-window-metrics");
        summary.getChildren().add(metricCard("available", l.text("env.windows.available"), minutes(a.availability().duration(), l), availabilityLine(a, l)));
        summary.getChildren().add(metricCard("goldenOverlap", l.text("env.windows.golden"), minutes(a.goldenOverlap(), l),
            l.text("env.windows.of", a.goldenOverlap().toMinutes(), a.availability().duration().toMinutes())));
        summary.getChildren().add(metricCard("blueOverlap", l.text("env.windows.blue"), minutes(a.blueOverlap(), l),
            l.text("env.windows.of", a.blueOverlap().toMinutes(), a.availability().duration().toMinutes())));
        summary.getChildren().add(metricCard("recommended", l.text("env.windows.recommended"), a.windows().isEmpty() ? l.text("env.value.none") : minutes(a.recommended(), l),
            a.windows().isEmpty() ? l.text("env.windows.noneValidated") : l.text("env.windows.recommendedDetail", a.windows().size(), a.recommended().toMinutes())));

        windowTimeline = new IntervalTimelineChart(l); windowTimeline.setId("env-windows-chart");
        var available = new IntervalTimelineChart.Segment(a.availability().start().toInstant(), a.availability().end().toInstant(), IntervalTimelineChart.Tone.AVAILABLE,
            l.text("env.windows.row.available"));
        var golden = new ArrayList<IntervalTimelineChart.Segment>(); var blue = new ArrayList<IntervalTimelineChart.Segment>();
        for (var overlap : a.relevantOverlaps()) (overlap.phase().golden() ? golden : blue).add(segment(overlap.phase()));
        var preparation = EnvironmentAnalyzer.preparation(a.assessment()).stream().map(i -> new IntervalTimelineChart.Segment(i.start(), i.end(),
            IntervalTimelineChart.Tone.PREPARATION, l.text("env.windows.row.preparation"))).toList();
        var recommended = a.windows().stream().map(w -> new IntervalTimelineChart.Segment(w.start().toInstant(), w.end().toInstant(), IntervalTimelineChart.Tone.RECOMMENDED,
            w.label().isBlank() ? l.text("env.windows.row.recommended") : w.label())).toList();
        Instant from = available.start(), to = available.end();
        for (var list : List.of(golden, blue, preparation, recommended)) for (var s : list) {
            if (s.start().isBefore(from)) from = s.start();
            if (s.end().isAfter(to)) to = s.end();
        }
        from = from.minus(Duration.ofMinutes(30)); to = to.plus(Duration.ofMinutes(30));
        windowTimeline.setData(List.of(new IntervalTimelineChart.Row(l.text("env.windows.row.available"), List.of(available)),
            new IntervalTimelineChart.Row(l.text("env.windows.row.preparation"), preparation), new IntervalTimelineChart.Row(l.text("env.windows.row.golden"), golden),
            new IntervalTimelineChart.Row(l.text("env.windows.row.blue"), blue), new IntervalTimelineChart.Row(l.text("env.windows.row.recommended"), recommended)),
            sunMarkers(), from, to, Optional.of(new Instant[] {available.start(), available.end()}), a.zone(), a.date());
        windowTimeline.setAccessibleRole(AccessibleRole.TEXT);
        windowTimeline.setAccessibleText(l.text("env.windows.timeline") + ". " + l.text("env.windows.golden") + " " + minutes(a.goldenOverlap(), l) + "; "
            + l.text("env.windows.blue") + " " + minutes(a.blueOverlap(), l));
        var timelineCard = card(l.text("env.windows.timeline"), windowTimeline, legend(List.of(IntervalTimelineChart.Tone.AVAILABLE, IntervalTimelineChart.Tone.PREPARATION,
            IntervalTimelineChart.Tone.GOLDEN, IntervalTimelineChart.Tone.BLUE, IntervalTimelineChart.Tone.RECOMMENDED)), FeaturePane.label(l.text("env.windows.overlapLegend"), "env-caption"));

        var table = new GridPane(); table.setHgap(18); table.setVgap(8); table.setId("env-overlaps"); table.getStyleClass().add("env-table");
        table.addRow(0, FeaturePane.label(l.text("env.windows.column.phase"), "env-detail-key"), FeaturePane.label(l.text("env.windows.column.time"), "env-detail-key"),
            FeaturePane.label(l.text("env.windows.column.overlap"), "env-detail-key"));
        int row = 1;
        for (var overlap : a.relevantOverlaps()) {
            var value = FeaturePane.label(overlap(overlap, a, l), "env-detail-value"); value.setId("env-overlap-" + overlap.phase().kind());
            table.addRow(row++, FeaturePane.label(phase(overlap.phase().kind(), l), "env-detail-value"),
                FeaturePane.label(span(overlap.phase().start(), overlap.phase().end(), a.date()), "env-detail-value"), value);
        }
        if (row == 1) table.addRow(1, FeaturePane.label(l.text("env.phase.none"), "env-text"));
        var overlapCard = card(l.text("env.windows.overlapTitle"), table, FeaturePane.label(l.text("env.windows.overlapNote"), "env-caption"));

        var recommendations = new VBox(12);
        var cards = EnvironmentPresentation.windows(a.assessment(), l);
        if (cards.isEmpty()) {
            var none = FeaturePane.label(l.text("env.card.noWindows") + " " + (a.ai().status() == AiStatus.SUCCESS ? "" : aiStatus(a.ai(), l) + "."), "env-text");
            none.setId("env-windows"); recommendations.getChildren().add(none);
        }
        for (int i = 0; i < cards.size(); i++) {
            var lines = cards.get(i);
            var bodyLabel = FeaturePane.label(String.join("\n", lines.subList(1, lines.size())), "env-text"); bodyLabel.setId("env-window-" + (i + 1));
            var heading = FeaturePane.label(lines.getFirst(), "section-title");
            var box = new VBox(8, heading, bodyLabel); box.getStyleClass().add("env-recommendation");
            recommendations.getChildren().add(box);
        }
        var recommendationCard = card(l.text("env.windows.recommendedTitle"), recommendations);
        return new VBox(16, summary, timelineCard, overlapCard, recommendationCard);
    }

    // ---- AI analysis ----------------------------------------------------------------------------------------------------

    private Node ai() {
        var a = analysis;
        var statusBox = new VBox(6);
        var chip = statusChip(aiStatus(a.ai(), l), a.stage(StageKey.AI).map(Stage::status).orElse(StageStatus.SKIPPED)); chip.setId("env-ai-tab-status");
        statusBox.getChildren().add(chip);
        for (String line : aiOutcome(a, l).subList(1, aiOutcome(a, l).size())) statusBox.getChildren().add(FeaturePane.label(line, "env-text"));
        a.stage(StageKey.VALIDATION).ifPresent(stage -> statusBox.getChildren().add(FeaturePane.label(stageLine(stage, l), "env-caption")));
        statusBox.getChildren().add(retryRow("env-retry-ai-tab"));
        var interpretation = FeaturePane.label(String.join("\n", interpretation(a.assessment(), l)), "env-text"); interpretation.setId("env-ai");
        var rejected = FeaturePane.label(String.join("\n", rejected(a.assessment(), l)), "env-text"); rejected.setId("env-rejected");
        var validated = FeaturePane.label(a.windows().isEmpty() ? l.text("env.windows.noneValidated")
            : l.text("env.windows.recommendedDetail", a.windows().size(), a.recommended().toMinutes()), "env-text");
        var open = new Hyperlink(l.text("env.ai.openWindows")); open.getStyleClass().add("env-link"); open.setId("env-open-windows"); open.setOnAction(e -> select(View.WINDOWS));
        return new VBox(16, card(l.text("env.ai.statusTitle"), statusBox), card(l.text("env.card.ai"), interpretation),
            card(l.text("env.windows.recommendedTitle"), validated, open), card(l.text("env.card.rejected"), rejected));
    }

    // ---- Technical details --------------------------------------------------------------------------------------------

    private Node technical() {
        var a = analysis;
        var provenance = new LinkedHashMap<String, String>();
        provenance.put(l.text("env.info.input"), a.request().accepted().map(PlaceCandidate::query).filter(q -> !q.isBlank()).orElse(a.request().place().label()));
        provenance.put(l.text("env.info.location"), placeLine(a));
        provenance.put(l.text("env.info.coordinates"), coordinates(a));
        provenance.put(l.text("env.info.source"), EnvironmentPresentation.provenance(a, l));
        provenance.put(l.text("env.info.zone"), a.zone().getId() + " · " + zoneSource(a.assessment(), l));
        provenance.put(l.text("env.info.assessed"), time(a.assessment().assessedAt().atZone(a.zone()), null));
        a.assessment().context().sourceTimestamps().forEach((group, stamp) -> provenance.put(l.text("env.info.retrievedGroup", group), time(stamp.atZone(a.zone()), null)));
        var status = new VBox(4);
        for (var stage : a.stages()) status.getChildren().add(FeaturePane.label(tag(stage.status()) + " " + stageLine(stage, l), "env-text"));
        status.getChildren().add(FeaturePane.label(l.text("env.cli.overall", overall(a, l)), "env-text"));
        var findings = new VBox(4); findings.setId("env-findings-all");
        if (a.findings().isEmpty()) findings.getChildren().add(FeaturePane.label(l.text("env.findings.none"), "env-text"));
        for (var finding : a.findings()) findings.getChildren().add(FeaturePane.label("• " + finding(finding, l), "env-text"));
        var summary = FeaturePane.label(String.join("\n", EnvironmentPresentation.summary(a.assessment(), l)), "env-text"); summary.setId("env-summary");
        var evidence = FeaturePane.label(String.join("\n", EnvironmentPresentation.evidence(a.assessment(), l)), "env-mono"); evidence.setId("env-evidence");
        var phases = FeaturePane.label(String.join("\n", EnvironmentPresentation.phases(a.assessment(), l)), "env-text"); phases.setId("env-phases");
        var limitations = FeaturePane.label(String.join("\n", EnvironmentPresentation.limitations(a.assessment(), l)), "env-text"); limitations.setId("env-limitations");
        var copyHere = new Button(l.text("env.technical.copy")); copyHere.setId("env-copy-technical"); copyHere.getStyleClass().add("secondary-button"); copyHere.setOnAction(e -> copyReport());
        var grid = new CardGrid(420, 16, 2);
        grid.getChildren().addAll(card(l.text("env.technical.provenance"), rows(provenance, "env-provenance")), card(l.text("env.section.status"), status));
        return new VBox(16, grid, card(l.text("env.findings.all"), findings), card(l.text("env.card.summary"), summary), card(l.text("env.card.evidence"), evidence),
            card(l.text("env.card.phases"), phases), card(l.text("env.card.limitations"), limitations, FeaturePane.label(l.text("env.light.limitations"), "env-caption")),
            new HBox(copyHere));
    }

    /** The complete technical report as plain text (Copy): the same lines as the CLI technical view's report part. */
    String copyText() {
        if (analysis == null) return "";
        var lines = new ArrayList<String>();
        lines.add(l.text("env.cli.title"));
        lines.add(overall(analysis, l));
        for (var stage : analysis.stages()) lines.add(tag(stage.status()) + " " + stageLine(stage, l));
        lines.add("");
        lines.add(l.text("env.info.coordinates") + ": " + coordinates(analysis) + " · " + analysis.zone().getId() + " · " + zoneSource(analysis.assessment(), l));
        for (var finding : analysis.findings()) lines.add("- " + finding(finding, l));
        lines.addAll(report(analysis.assessment(), l));
        return String.join(System.lineSeparator(), lines);
    }
    private void copyReport() {
        if (analysis == null) return;
        var content = new ClipboardContent(); content.putString(copyText());
        Clipboard.getSystemClipboard().setContent(content);
        copied.setVisible(true); copied.setManaged(true); copiedTimer.playFromStart();
    }
    private final javafx.animation.PauseTransition copiedTimer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
    { copiedTimer.setOnFinished(event -> { copied.setVisible(false); copied.setManaged(false); }); }

    // ---- Shared building blocks ---------------------------------------------------------------------------------------

    private Node retryRow(String id) {
        var button = new Button(l.text("env.ai.retry")); button.setId(id); button.getStyleClass().add("primary-button"); button.setOnAction(e -> retry.run());
        var hint = FeaturePane.label("", "env-caption"); hint.setId(id + "-hint");
        retryButtons.add(button); retryHints.add(hint);
        return new VBox(6, button, hint);
    }
    private GridPane stageGrid(String id) {
        var grid = new GridPane(); grid.setHgap(12); grid.setVgap(8); grid.setId(id); grid.getStyleClass().add("env-table");
        var names = new ColumnConstraints(); names.setMinWidth(Region.USE_PREF_SIZE);
        var chips = new ColumnConstraints(); chips.setMinWidth(Region.USE_PREF_SIZE);
        var details = new ColumnConstraints(); details.setHgrow(Priority.ALWAYS); details.setMinWidth(0);
        grid.getColumnConstraints().addAll(names, chips, details);
        int row = 0;
        for (var stage : analysis.stages()) {
            var detail = FeaturePane.label(stage.detail().map(d -> d.render(l)).orElse(""), "env-caption"); detail.setMaxWidth(Double.MAX_VALUE);
            var chip = statusChip(status(stage.status(), l), stage.status()); chip.setId(id + "-" + stage.key().name());
            grid.addRow(row++, FeaturePane.label(stage(stage.key(), l), "env-text"), chip, detail);
        }
        return grid;
    }
    private Node findingRow(Finding finding, String id) {
        var icon = new Region(); icon.getStyleClass().addAll("env-level-icon", finding.level() == Level.WARN ? "env-level-warn" : "env-level-info");
        icon.setMinSize(14, 14); icon.setMaxSize(14, 14);
        var text = FeaturePane.label(finding(finding, l), "env-finding"); text.setId(id);
        text.setAccessibleText(l.text(finding.level() == Level.WARN ? "env.level.warn" : "env.level.info") + ": " + text.getText());
        var row = new HBox(10, icon, text); row.setAlignment(Pos.TOP_LEFT); HBox.setHgrow(text, Priority.ALWAYS); text.setMaxWidth(Double.MAX_VALUE);
        return row;
    }
    private Node glyph(Optional<Integer> code, Instant at, double size) {
        var request = analysis.request();
        boolean night = SolarCalculator.elevation(request.place().latitude().orElseThrow(), request.place().longitude().orElseThrow(), at) < SolarCalculator.SUNRISE_ELEVATION;
        return new WeatherGlyph(code.map(EnvironmentPresentation::category).orElse("other"), night, size);
    }
    private Node metricCard(String key, String caption, String value, String detail) {
        var title = FeaturePane.label(caption, "env-metric-caption");
        var number = FeaturePane.label(value, "env-metric-value"); number.setId("env-metric-" + key);
        var sub = FeaturePane.label(detail, "env-metric-detail"); sub.setId("env-metric-" + key + "-detail");
        var box = new VBox(4, title, number, sub); box.getStyleClass().add("env-metric");
        box.setAccessibleRole(AccessibleRole.TEXT); box.setAccessibleText(caption + ": " + value + ". " + detail);
        return box;
    }
    private Node legend(List<IntervalTimelineChart.Tone> tones) {
        var row = new FlowPane(14, 6); row.getStyleClass().add("env-legend");
        for (var tone : tones) {
            var swatch = new Region(); swatch.getStyleClass().addAll("env-swatch", "env-swatch-" + tone.name().toLowerCase(Locale.ROOT));
            swatch.setMinSize(18, 10); swatch.setPrefSize(18, 10); swatch.setMaxSize(18, 10);
            var item = new HBox(6, swatch, FeaturePane.label(l.text("env.tone." + tone.name()), "env-caption")); item.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(item);
        }
        return row;
    }
    private Node legendItem(String style, String text) {
        var swatch = new Region(); swatch.getStyleClass().addAll("env-swatch", style); swatch.setMinSize(18, 10); swatch.setPrefSize(18, 10); swatch.setMaxSize(18, 10);
        var item = new HBox(6, swatch, FeaturePane.label(text, "env-caption")); item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }
    static VBox card(String title, Node... content) {
        var box = new VBox(12); box.getStyleClass().add("env-card"); box.setMinWidth(0);
        if (title != null && !title.isEmpty()) box.getChildren().add(FeaturePane.label(title, "env-card-title"));
        box.getChildren().addAll(content);
        return box;
    }
    static Label statusChip(String text, StageStatus status) {
        String symbol = switch (status) {
            case SUCCESS -> "✓ ";
            case PARTIAL -> "! ";
            case PENDING -> "… ";
            case FAILED, TIMED_OUT, UNAVAILABLE -> "✕ ";
            case SKIPPED, NOT_PERFORMED -> "– ";
        };
        var chip = FeaturePane.label(symbol + text, "env-chip");
        chip.getStyleClass().add(switch (status) {
            case SUCCESS -> "env-ok";
            case PARTIAL -> "env-warn";
            case FAILED, TIMED_OUT, UNAVAILABLE -> "env-bad";
            default -> "env-neutral";
        });
        chip.setWrapText(false); chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }
    /** Label / value rows; values wrap, labels keep one column. */
    static GridPane rows(Map<String, String> values, String id) {
        var grid = new GridPane(); grid.setHgap(16); grid.setVgap(8); grid.setId(id); grid.getStyleClass().add("env-table"); grid.setMinWidth(0);
        var keys = new ColumnConstraints(); keys.setMinWidth(80); keys.setPrefWidth(Region.USE_COMPUTED_SIZE); keys.setMaxWidth(220);
        var data = new ColumnConstraints(); data.setHgrow(Priority.ALWAYS); data.setMinWidth(0); data.setFillWidth(true);
        grid.getColumnConstraints().addAll(keys, data);
        int row = 0;
        for (var entry : values.entrySet()) {
            var key = FeaturePane.label(entry.getKey(), "env-detail-key"); var value = FeaturePane.label(entry.getValue(), "env-detail-value");
            value.setMaxWidth(Double.MAX_VALUE); value.setId(id + "-" + row);
            grid.add(key, 0, row); grid.add(value, 1, row++);
        }
        return grid;
    }
    /** A collapsed disclosure row in the calculators' Calculation details style. */
    static TitledPane details(String title, Node content, String id) {
        var pane = new TitledPane(title, content); pane.setExpanded(false); pane.setAnimated(false); pane.setId(id); pane.getStyleClass().add("env-disclosure");
        return pane;
    }
}
