package com.photopilot.ui.gui;

import com.photopilot.application.CapabilityPresentation;
import com.photopilot.application.FocalScale;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.util.*;
import java.util.function.Function;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import static com.photopilot.application.CapabilityPresentation.*;
import static com.photopilot.ui.gui.CapabilityDashboard.*;

/**
 * Camera Bodies and Lenses views of the capability dashboard: camera cards with a sortable comparison table, the
 * interactive focal coverage chart (physical or conditional 35 mm-equivalent, filtered by type, aperture and camera),
 * the sortable lens table, detail panels and the aperture comparison. Filters and selections only choose what is
 * shown; every value is read from the analysis snapshot. Numeric columns sort by their numbers, unknown values last.
 */
final class CapabilityGearViews {
    private CapabilityGearViews() { }
    private static final double ROW_HEIGHT = 40;

    // ---- Camera bodies -----------------------------------------------------------------------------------------------

    static Node cameras(CapabilityDashboard dashboard, GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l) {
        var state = dashboard.state();
        if (report.cameras().isEmpty()) return card(null, FeaturePane.label(l.text("capability.cli.noCameras"), "secondary-text"));
        if (state.camera == null || report.camera(state.camera).isEmpty()) state.camera = report.cameras().getFirst().camera().id();
        var cards = new CardGrid(250, 14, 4); cards.setId("capability-camera-cards");
        var group = new ToggleGroup();
        var buttons = new LinkedHashMap<UUID, ToggleButton>();
        var table = new TableView<CameraCapability>(); table.setId("capability-camera-table");
        var detail = new VBox(12); detail.setId("capability-camera-detail");
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            var chosen = report.camera(state.camera).orElseThrow();
            buttons.forEach((id, button) -> button.setSelected(id.equals(state.camera)));
            if (table.getSelectionModel().getSelectedItem() != chosen) table.getSelectionModel().select(chosen);
            detail.getChildren().setAll(FeaturePane.label(names.get(state.camera), "cap-card-title"), rows(cameraDetails(chosen, report, names, l), "capability-camera-rows"));
        };
        int index = 1;
        for (var entry : report.cameras()) {
            var button = cameraCard(entry, report, names, l, index); button.setId("capability-camera-card-" + index++); button.setToggleGroup(group);
            UUID id = entry.camera().id();
            button.setOnAction(event -> { state.camera = id; refresh[0].run(); });
            buttons.put(id, button); cards.getChildren().add(button);
        }
        table.getColumns().add(text(l.text("capability.column.model"), 170, c -> names.get(c.camera().id())));
        table.getColumns().add(text(l.text("capability.column.mount"), 80, c -> mount(c.mount(), l)));
        table.getColumns().add(numeric(l.text("capability.column.sensor"), 140, c -> c.camera().sensorWidthMm().isPresent() && c.camera().sensorHeightMm().isPresent()
            ? Optional.of(Math.hypot(c.camera().sensorWidthMm().get(), c.camera().sensorHeightMm().get())) : Optional.empty(), c -> sensor(c.camera(), l)));
        table.getColumns().add(numeric(l.text("capability.column.crop"), 100, CameraCapability::cropFactor, c -> crop(c.cropFactor(), l)));
        table.getColumns().add(numeric(l.text("capability.column.resolution"), 115, c -> c.camera().resolutionMP(), c -> megapixels(c.camera().resolutionMP(), l)));
        table.getColumns().add(numeric(l.text("capability.column.burst"), 115, c -> c.camera().burstFps(), c -> fps(c.camera().burstFps(), l)));
        table.getColumns().add(text(l.text("capability.column.ibis"), 80, c -> yesNo(c.camera().stabilization(), l)));
        table.getColumns().add(text(l.text("capability.column.af"), 140, c -> list(c.camera().autofocusModes(), l)));
        table.getColumns().add(numeric(l.text("capability.column.quantity"), 60, c -> Optional.of((double) c.camera().quantity()), c -> String.valueOf(c.camera().quantity())));
        table.getItems().setAll(report.cameras());
        fit(table, report.cameras().size(), false, l);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> {
            if (value != null && !value.camera().id().equals(state.camera)) { state.camera = value.camera().id(); refresh[0].run(); }
        });
        refresh[0].run();
        return new VBox(16, cards, card(l.text("capability.cameras.compare"), table), card(l.text("capability.cameras.details"), detail));
    }

    private static ToggleButton cameraCard(CameraCapability entry, GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l, int index) {
        var camera = entry.camera();
        var title = FeaturePane.label(names.get(camera.id()), "cap-card-title");
        var meta = FeaturePane.label(l.text("capability.cameras.meta", mount(entry.mount(), l), camera.quantity()), "cap-caption");
        var stats = new HBox(24, stat(megapixels(camera.resolutionMP(), l), l.text("capability.column.resolution"), camera.resolutionMP().isEmpty()),
            stat(fps(camera.burstFps(), l), l.text("capability.column.burst"), camera.burstFps().isEmpty()));
        long matched = report.pairsFor(camera.id()).stream().filter(p -> p.mount().established()).count();
        var facts = new LinkedHashMap<String, String>();
        facts.put(l.text("capability.column.sensor"), sensor(camera, l) + (entry.format().isPresent() ? " · " + sensorFormat(entry, l) + " · " + crop(entry.cropFactor(), l) : ""));
        facts.put(l.text("capability.column.ibis"), yesNo(camera.stabilization(), l));
        facts.put(l.text("capability.column.af"), list(camera.autofocusModes(), l));
        facts.put(l.text("capability.cameras.matched"), l.text("capability.cameras.matchedValue", matched, report.lenses().size()));
        var content = new VBox(10, title, meta, stats, rows(facts, "capability-camera-facts-" + index)); content.setMinWidth(0);
        var button = new ToggleButton(); button.setGraphic(content); button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().setAll("cap-select-card"); button.setMaxWidth(Double.MAX_VALUE); button.setMaxHeight(Double.MAX_VALUE); button.setMinWidth(0);
        button.setAlignment(Pos.TOP_LEFT);
        button.setAccessibleText(names.get(camera.id()) + ", " + megapixels(camera.resolutionMP(), l) + ", " + fps(camera.burstFps(), l));
        return button;
    }
    /** A prominent number; a value that is not recorded is said in muted text, never shown as a large figure or zero. */
    private static Node stat(String value, String caption, boolean missing) {
        var box = new VBox(2, FeaturePane.label(value, missing ? "cap-stat-missing" : "cap-stat-value"), FeaturePane.label(caption, "cap-caption"));
        box.setMinWidth(Region.USE_PREF_SIZE);
        return box;
    }
    private static Map<String, String> cameraDetails(CameraCapability entry, GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l) {
        var c = entry.camera(); var rows = new LinkedHashMap<String, String>();
        rows.put(l.text("capability.detail.cameraMount"), mount(entry.mount(), l));
        rows.put(l.text("capability.detail.identifiedOne"), system(entry.mount(), l));
        rows.put(l.text("capability.column.sensor"), sensor(c, l));
        rows.put(l.text("capability.column.format"), sensorFormat(entry, l));
        rows.put(l.text("capability.column.crop"), crop(entry.cropFactor(), l));
        rows.put(l.text("capability.column.resolution"), megapixels(c.resolutionMP(), l));
        rows.put(l.text("capability.column.burst"), fps(c.burstFps(), l));
        rows.put(l.text("capability.column.ibis"), yesNo(c.stabilization(), l));
        rows.put(l.text("capability.column.af"), list(c.autofocusModes(), l));
        rows.put(l.text("gear.field.batteryTypes"), list(c.batteryTypes(), l));
        rows.put(l.text("gear.field.storageFormats"), list(c.storageFormats(), l));
        rows.put(l.text("gear.field.weightGrams"), c.weightGrams().map(w -> number(w) + " g").orElse(notRecorded(l)));
        rows.put(l.text("capability.column.quantity"), String.valueOf(c.quantity()));
        rows.put(l.text("capability.cli.mountedPhysical"), intervals(entry.mountedCoverage(), l));
        rows.put(l.text("capability.cli.mountedEquivalent"), entry.equivalentCoverage().isEmpty() ? l.text("capability.none")
            : String.join("; ", entry.equivalentCoverage().stream().map(CapabilityPresentation::equivalent).toList()));
        return rows;
    }

    // ---- Lenses ------------------------------------------------------------------------------------------------------

    static Node lenses(CapabilityDashboard dashboard, GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l) {
        var state = dashboard.state();
        if (report.lenses().isEmpty()) return card(null, FeaturePane.label(l.text("capability.cli.noLenses"), "secondary-text"));
        if (state.lens == null || report.lens(state.lens).isEmpty()) state.lens = report.lenses().getFirst().lens().id();
        if (state.equivalent && (state.chartCamera == null || report.camera(state.chartCamera).isEmpty())) {
            state.chartCamera = report.cameras().isEmpty() ? null : report.cameras().getFirst().camera().id();
            state.equivalent = state.chartCamera != null;
        }
        var chart = new FocalCoverageChart(); chart.setId("capability-chart");
        var axis = FeaturePane.label("", "cap-caption"); axis.setId("capability-chart-axis");
        var note = FeaturePane.label("", "cap-caption"); note.setId("capability-chart-note");
        var table = new TableView<LensCapability>(); table.setId("capability-lens-table");
        var detail = new VBox(12); detail.setId("capability-lens-detail");

        var modes = new HBox(); modes.getStyleClass().add("segmented"); modes.setMaxWidth(Region.USE_PREF_SIZE); modes.setId("capability-chart-mode");
        var modeGroup = new ToggleGroup();
        var physical = segment(l.text("capability.chart.mode.PHYSICAL"), "capability-mode-physical", modeGroup);
        var equivalent = segment(l.text("capability.chart.mode.EQUIVALENT"), "capability-mode-equivalent", modeGroup);
        equivalent.setDisable(report.cameras().isEmpty());
        modes.getChildren().addAll(physical, equivalent);
        var camera = new ComboBox<CameraCapability>(); camera.setId("capability-chart-camera");
        camera.getItems().add(null); camera.getItems().addAll(report.cameras());
        configure(camera, c -> c == null ? l.text("capability.chart.anyCamera") : names.get(c.camera().id()));
        var type = new ComboBox<String>(); type.setId("capability-filter-type"); type.getItems().addAll("ALL", "PRIME", "ZOOM");
        configure(type, t -> l.text("capability.filter.type." + (t == null ? "ALL" : t)));
        var aperture = new ComboBox<Double>(); aperture.setId("capability-filter-aperture"); aperture.getItems().addAll(0.0, 2.8, 4.0);
        configure(aperture, v -> v == null || v == 0 ? l.text("capability.filter.anyAperture") : l.text("capability.filter.aperture", fNumber(v)));
        var filters = new FlowPane(12, 10, modes, labelled(l.text("capability.chart.camera"), camera), labelled(l.text("capability.filter.typeLabel"), type),
            labelled(l.text("capability.filter.apertureLabel"), aperture));
        filters.setAlignment(Pos.CENTER_LEFT);

        table.getColumns().add(text(l.text("capability.column.lens"), 180, e -> names.get(e.lens().id())));
        table.getColumns().add(text(l.text("capability.column.mount"), 80, e -> mount(e.mount(), l)));
        table.getColumns().add(text(l.text("capability.column.type"), 70, e -> lensType(e, l)));
        var focal = new TableColumn<LensCapability, Double>(l.text("capability.column.focal"));
        focal.getColumns().add(numeric(l.text("capability.column.focalShort"), 80, e -> Optional.of(e.focal().startMm()), e -> mm(e.focal().startMm())));
        focal.getColumns().add(numeric(l.text("capability.column.focalLong"), 80, e -> Optional.of(e.focal().endMm()), e -> mm(e.focal().endMm())));
        table.getColumns().add(focal);
        table.getColumns().add(numeric(l.text("capability.column.aperture"), 100, e -> Optional.of(e.widestAperture()), e -> CapabilityPresentation.aperture(e, l)));
        table.getColumns().add(text(l.text("capability.column.ois"), 70, e -> yesNo(e.lens().stabilization(), l)));
        table.getColumns().add(text(l.text("capability.column.autofocus"), 70, e -> yesNo(e.lens().autofocus(), l)));
        table.getColumns().add(numeric(l.text("capability.column.filter"), 80, e -> e.lens().filterDiameterMm(), e -> filterThread(e.lens(), l)));
        table.getColumns().add(numeric(l.text("capability.column.quantity"), 60, e -> Optional.of((double) e.lens().quantity()), e -> String.valueOf(e.lens().quantity())));

        Runnable[] showDetail = new Runnable[1];
        showDetail[0] = () -> {
            var chosen = report.lens(state.lens).orElseThrow();
            chart.select(state.lens);
            if (table.getItems().contains(chosen) && table.getSelectionModel().getSelectedItem() != chosen) table.getSelectionModel().select(chosen);
            detail.getChildren().setAll(FeaturePane.label(names.get(state.lens), "cap-card-title"), rows(lensDetails(chosen, report, names, l), "capability-lens-rows"));
        };
        Runnable redraw = () -> {
            var chosenCamera = state.chartCamera == null ? Optional.<CameraCapability>empty() : report.camera(state.chartCamera);
            var visible = report.lenses().stream().filter(e -> shown(e, state)).toList();
            var rows = new ArrayList<FocalCoverageChart.Row>();
            for (var e : visible) {
                String label = names.get(e.lens().id());
                String tip = label + "\n" + focal(e.focal()) + " · " + CapabilityPresentation.aperture(e, l) + " · " + mount(e.mount(), l);
                if (state.equivalent && chosenCamera.isPresent()) {
                    var pair = report.pair(chosenCamera.get().camera().id(), e.lens().id()).orElseThrow();
                    rows.add(new FocalCoverageChart.Row(e.lens().id(), label, pair.equivalentFocal(), equivalentText(pair, l), false,
                        tip + "\n" + l.text("capability.detail.equivalent") + ": " + equivalentText(pair, l)));
                } else if (chosenCamera.isPresent()) {
                    var pair = report.pair(chosenCamera.get().camera().id(), e.lens().id()).orElseThrow();
                    rows.add(new FocalCoverageChart.Row(e.lens().id(), label, Optional.of(e.focal()), "", !pair.mount().established(),
                        tip + "\n" + relation(pair.mount().relation(), l)));
                } else rows.add(new FocalCoverageChart.Row(e.lens().id(), label, Optional.of(e.focal()), "", false, tip));
            }
            FocalCoverageChart.Combined combined; Optional<FocalScale> scale;
            if (state.equivalent && chosenCamera.isPresent()) {
                var c = chosenCamera.get();
                combined = new FocalCoverageChart.Combined(l.text("capability.chart.combinedEquivalent"), c.equivalentCoverage(), GearCapabilityAnalysis.gapsBetween(c.equivalentCoverage()), List.of());
                scale = FocalScale.of(c.equivalentCoverage());
                axis.setText(l.text("capability.chart.equivalentAxis", names.get(c.camera().id())));
                note.setText(l.text("capability.equivalentNote"));
            } else if (chosenCamera.isPresent()) {
                var c = chosenCamera.get();
                combined = new FocalCoverageChart.Combined(l.text("capability.chart.combinedMounted"), c.mountedCoverage(), GearCapabilityAnalysis.gapsBetween(c.mountedCoverage()), List.of());
                scale = FocalScale.of(report.coverage());
                axis.setText(l.text("capability.chart.physicalAxis"));
                note.setText(l.text("capability.chart.dimmedNote", names.get(c.camera().id())));
            } else {
                combined = new FocalCoverageChart.Combined(l.text("capability.chart.combined"), report.coverage(), report.gaps(), report.overlaps());
                scale = FocalScale.of(report.coverage());
                axis.setText(l.text("capability.chart.physicalAxis"));
                note.setText(l.text(visible.size() < report.lenses().size() ? "capability.chart.filteredNote" : "capability.poolNote"));
            }
            chart.setData(rows, combined, scale);
            chart.setAccessibleText(axis.getText() + ". " + combined.label() + ": " + intervals(combined.coverage(), l));
            table.getItems().setAll(visible);
            fit(table, visible.size(), true, l);
            table.setPlaceholder(FeaturePane.label(l.text("capability.chart.empty"), "secondary-text"));
            showDetail[0].run();
        };
        chart.onSelect(id -> { state.lens = id; showDetail[0].run(); });
        table.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> {
            if (value != null && !value.lens().id().equals(state.lens)) { state.lens = value.lens().id(); showDetail[0].run(); }
        });
        (state.equivalent ? equivalent : physical).setSelected(true);
        camera.setValue(state.chartCamera == null ? null : report.camera(state.chartCamera).orElse(null));
        type.setValue(state.lensType); aperture.setValue(state.apertureLimit);
        physical.setOnAction(event -> { physical.setSelected(true); state.equivalent = false; redraw.run(); });
        equivalent.setOnAction(event -> {
            equivalent.setSelected(true); state.equivalent = true;
            if (state.chartCamera == null && !report.cameras().isEmpty()) { state.chartCamera = report.cameras().getFirst().camera().id(); camera.setValue(report.cameras().getFirst()); }
            redraw.run();
        });
        camera.setOnAction(event -> {
            state.chartCamera = camera.getValue() == null ? null : camera.getValue().camera().id();
            if (state.chartCamera == null && state.equivalent) { state.equivalent = false; physical.setSelected(true); }
            redraw.run();
        });
        type.setOnAction(event -> { state.lensType = type.getValue(); redraw.run(); });
        aperture.setOnAction(event -> { state.apertureLimit = aperture.getValue() == null ? 0 : aperture.getValue(); redraw.run(); });
        redraw.run();

        var chartCard = card(l.text("capability.section.COVERAGE"), filters, axis, chart, dashboard.legend(), note);
        return new VBox(16, chartCard, card(l.text("capability.lenses.compare"), table), card(l.text("capability.lenses.details"), detail), apertureCard(report, names, l));
    }
    /** Type and aperture filters on recorded values; the widest aperture is always recorded, so the filter is reliable. */
    static boolean shown(LensCapability lens, CapabilityDashboard.State state) {
        boolean type = switch (state.lensType) { case "PRIME" -> lens.prime(); case "ZOOM" -> !lens.prime(); default -> true; };
        return type && (state.apertureLimit <= 0 || lens.widestAperture() <= state.apertureLimit);
    }
    private static Map<String, String> lensDetails(LensCapability e, GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l) {
        var lens = e.lens(); var rows = new LinkedHashMap<String, String>();
        rows.put(l.text("capability.detail.lensMount"), mount(e.mount(), l));
        rows.put(l.text("capability.detail.identifiedOne"), system(e.mount(), l));
        rows.put(l.text("capability.column.type"), lensType(e, l));
        rows.put(l.text("capability.detail.physical"), focal(e.focal()));
        rows.put(l.text("capability.column.aperture"), CapabilityPresentation.aperture(e, l));
        rows.put(l.text("capability.column.behaviour"), apertureKind(e.apertureKind(), l));
        rows.put(l.text("capability.column.ois"), yesNo(lens.stabilization(), l));
        rows.put(l.text("capability.column.autofocus"), yesNo(lens.autofocus(), l));
        rows.put(l.text("capability.column.filter"), filterThread(lens, l));
        rows.put(l.text("gear.field.weightGrams"), lens.weightGrams().map(w -> number(w) + " g").orElse(notRecorded(l)));
        rows.put(l.text("capability.column.quantity"), String.valueOf(lens.quantity()));
        var matched = new ArrayList<String>(); var open = new ArrayList<String>(); var equivalents = new ArrayList<String>();
        for (var camera : report.cameras()) {
            var pair = report.pair(camera.camera().id(), lens.id()).orElseThrow();
            String name = names.get(camera.camera().id());
            if (pair.mount().established()) matched.add(name); else open.add(name + " (" + relation(pair.mount().relation(), l) + ")");
            pair.equivalentFocal().ifPresent(range -> equivalents.add(name + ": " + equivalent(range)));
        }
        rows.put(l.text("capability.lenses.matched"), matched.isEmpty() ? l.text("capability.none") : String.join(", ", matched));
        rows.put(l.text("capability.lenses.notMatched"), open.isEmpty() ? l.text("capability.none") : String.join(", ", open));
        rows.put(l.text("capability.detail.equivalent"), equivalents.isEmpty() ? l.text("capability.none") : l.text("capability.value.conditional", String.join("; ", equivalents)));
        return rows;
    }
    private static Node apertureCard(GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l) {
        var grid = new GridPane(); grid.setHgap(14); grid.setVgap(8); grid.setId("capability-aperture");
        var name = new ColumnConstraints(); name.setMinWidth(120); name.setPrefWidth(220); name.setMaxWidth(320);
        var kind = new ColumnConstraints(); kind.setMinWidth(Region.USE_PREF_SIZE);
        var value = new ColumnConstraints(); value.setMinWidth(Region.USE_PREF_SIZE);
        var bar = new ColumnConstraints(); bar.setHgrow(Priority.ALWAYS); bar.setMinWidth(60);
        grid.getColumnConstraints().addAll(name, kind, value, bar);
        int row = 0;
        for (var e : report.lenses()) {
            var track = new StackPane(); track.getStyleClass().add("cap-aperture-track"); track.setAlignment(Pos.CENTER_LEFT); track.setMinHeight(10); track.setMaxHeight(10);
            var fill = new Region(); fill.getStyleClass().add(report.widestLenses().contains(e.lens()) ? "cap-aperture-best" : "cap-aperture-fill");
            double share = apertureScale(e.lens().maxAperture());
            fill.maxWidthProperty().bind(track.widthProperty().multiply(share)); fill.setMinWidth(0); fill.setPrefWidth(0);
            track.getChildren().add(fill);
            var label = FeaturePane.label(names.get(e.lens().id()), "secondary-text");
            grid.add(label, 0, row); grid.add(chip(apertureKind(e.apertureKind(), l), e.apertureKind() == ApertureKind.UNKNOWN_ZOOM ? "cap-warn" : "cap-neutral"), 1, row);
            var text = FeaturePane.label(CapabilityPresentation.aperture(e, l), "cap-aperture-value"); text.setId("capability-aperture-" + (row + 1));
            grid.add(text, 2, row); grid.add(track, 3, row++);
        }
        var widest = FeaturePane.label(l.text("capability.finding.WIDEST_APERTURE", fNumber(report.largestAperture().orElseThrow()),
            String.join(", ", report.widestLenses().stream().map(e -> names.get(e.id())).toList())), "cap-card-title");
        widest.setId("capability-widest");
        return card(l.text("capability.section.APERTURE"), widest, grid, FeaturePane.label(l.text("capability.aperture.barNote"), "cap-caption"),
            FeaturePane.label(l.text("capability.apertureNote"), "cap-caption"));
    }

    // ---- Controls ----------------------------------------------------------------------------------------------------

    private static ToggleButton segment(String text, String id, ToggleGroup group) {
        var button = new ToggleButton(text); button.setId(id); button.getStyleClass().setAll("segment"); button.setToggleGroup(group); button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }
    static <T> TableColumn<T, String> text(String title, double width, Function<T, String> value) {
        var column = new TableColumn<T, String>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }
    /** Sorts by the number (unknown last when ascending); shows the formatted text of the row. */
    static <T> TableColumn<T, Double> numeric(String title, double width, Function<T, Optional<Double>> value, Function<T, String> display) {
        var column = new TableColumn<T, Double>(title);
        column.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue()).orElse(null)));
        column.setComparator(Comparator.nullsLast(Double::compare));
        column.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double number, boolean empty) {
                super.updateItem(number, empty);
                T row = empty || getTableRow() == null ? null : getTableRow().getItem();
                setText(row == null ? null : display.apply(row));
            }
        });
        column.setPrefWidth(width);
        return column;
    }
    /** Shows every row up to 12 without an inner scroll bar; larger inventories scroll inside the table. */
    static void fit(TableView<?> table, int rows, boolean nestedHeader, LocalizationService l) {
        table.setFixedCellSize(ROW_HEIGHT);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        double header = nestedHeader ? 72 : 40;
        double height = header + Math.max(1, Math.min(rows, 12)) * ROW_HEIGHT + 4;
        table.setPrefHeight(height); table.setMinHeight(height); table.setMaxHeight(height); table.setMinWidth(0);
        if (table.getPlaceholder() == null) table.setPlaceholder(FeaturePane.label(l.text("capability.none"), "secondary-text"));
    }
}
