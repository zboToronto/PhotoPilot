package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.GearCapabilityAnalysis;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.util.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import static com.photopilot.application.CapabilityPresentation.*;
import static com.photopilot.ui.gui.CapabilityDashboard.*;

/**
 * Compatibility view: lenses as rows, cameras as columns, one selectable cell per recorded pair showing the mount
 * comparison as symbol, short text and colour (green established within the mount-label scope, amber not established,
 * red different mount systems; grey for cells outside the status filter). The detail panel shows the shared
 * {@code pairDetails} rows of the selected cell only. Filters by camera, lens mount, status and lens name choose what is
 * shown; they never change a status. The matrix scrolls horizontally only when the cameras do not fit.
 */
final class CapabilityMatrixView {
    private CapabilityMatrixView() { }

    static Node build(CapabilityDashboard dashboard, GearCapabilityAnalysis report, Map<UUID, String> names, LocalizationService l) {
        var state = dashboard.state();
        if (report.pairs().isEmpty()) return card(null, FeaturePane.label(l.text("capability.noPairs"), "secondary-text"));
        var camera = new ComboBox<CameraCapability>(); camera.setId("capability-matrix-camera");
        camera.getItems().add(null); camera.getItems().addAll(report.cameras());
        configure(camera, c -> c == null ? l.text("capability.chart.anyCamera") : names.get(c.camera().id()));
        var mounts = new ArrayList<String>();
        for (var lens : report.lenses()) { String label = mount(lens.mount(), l); if (!mounts.contains(label)) mounts.add(label); }
        if (state.matrixMount != null && !mounts.contains(state.matrixMount)) state.matrixMount = null; // that label is no longer recorded
        var mount = new ComboBox<String>(); mount.setId("capability-matrix-mount"); mount.getItems().add(null); mount.getItems().addAll(mounts);
        configure(mount, m -> m == null ? l.text("capability.matrix.anyMount") : m);
        var status = new ComboBox<PairStatus>(); status.setId("capability-matrix-status"); status.getItems().add(null); status.getItems().addAll(PairStatus.values());
        configure(status, s -> s == null ? l.text("capability.matrix.anyStatus") : status(s, l));
        var search = new TextField(state.matrixSearch); search.setId("capability-matrix-search"); search.setPromptText(l.text("capability.matrix.search"));
        search.setAccessibleText(l.text("capability.matrix.search")); search.setPrefColumnCount(14);
        var filters = new FlowPane(12, 10, labelled(l.text("capability.chart.camera"), camera), labelled(l.text("capability.matrix.mountLabel"), mount),
            labelled(l.text("capability.matrix.statusLabel"), status), search);
        filters.setAlignment(Pos.CENTER_LEFT);

        var grid = new GridPane(); grid.setId("capability-matrix"); grid.getStyleClass().add("cap-matrix"); grid.setHgap(6); grid.setVgap(6);
        var scroll = new ScrollPane(grid); scroll.getStyleClass().add("cap-matrix-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED); scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.setMinWidth(0);
        // Only the width may scroll: the viewport is always as tall as the whole matrix, so no row is ever cut off.
        scroll.prefViewportHeightProperty().bind(grid.heightProperty()); scroll.minViewportHeightProperty().bind(grid.heightProperty());
        scroll.setFocusTraversable(false);
        var empty = FeaturePane.label(l.text("capability.matrix.empty"), "secondary-text"); empty.setId("capability-matrix-empty");
        var detail = new VBox(12); detail.setId("capability-pair-detail");
        var group = new ToggleGroup();
        var cells = new HashMap<String, ToggleButton>();

        Runnable[] showPair = new Runnable[1];
        showPair[0] = () -> {
            if (state.pairCamera == null) { detail.getChildren().setAll(FeaturePane.label(l.text("capability.matrix.select"), "secondary-text")); return; }
            var pair = report.pair(state.pairCamera, state.pairLens).orElseThrow();
            var lens = report.lens(state.pairLens).orElseThrow();
            var notes = new VBox(4);
            for (String note : pairNotes(l)) notes.getChildren().add(FeaturePane.label("• " + note, "cap-caption"));
            detail.getChildren().setAll(new HBox(10, chip(symbol(pair.mount().relation()) + "  " + status(pair.mount().status(), l), statusStyle(pair.mount().status())),
                    FeaturePane.label(names.get(pair.lens().id()) + " · " + names.get(pair.camera().id()), "cap-card-title")),
                rows(pairDetails(pair, lens, names, l), "capability-pair-rows"), notes);
            var key = key(state.pairCamera, state.pairLens);
            cells.forEach((k, cell) -> cell.setSelected(k.equals(key)));
        };
        Runnable redraw = () -> {
            grid.getChildren().clear(); grid.getColumnConstraints().clear(); cells.clear();
            var shownCameras = new ArrayList<Integer>();
            for (int i = 0; i < report.cameras().size(); i++) if (state.matrixCamera == null || report.cameras().get(i).camera().id().equals(state.matrixCamera)) shownCameras.add(i);
            var first = new ColumnConstraints(); first.setMinWidth(150); first.setPrefWidth(230); grid.getColumnConstraints().add(first);
            for (int ignored : shownCameras) { var c = new ColumnConstraints(); c.setMinWidth(118); c.setPrefWidth(140); grid.getColumnConstraints().add(c); }
            grid.add(FeaturePane.label(l.text("capability.matrix.corner"), "cap-caption"), 0, 0);
            int column = 1;
            for (int i : shownCameras) {
                var c = report.cameras().get(i);
                var head = new VBox(2, FeaturePane.label(names.get(c.camera().id()), "cap-matrix-head"), FeaturePane.label(mount(c.mount(), l), "cap-caption"));
                head.setId("capability-matrix-camera-" + (i + 1));
                grid.add(head, column++, 0);
            }
            int row = 1; String query = state.matrixSearch.strip().toLowerCase(Locale.ROOT);
            for (int j = 0; j < report.lenses().size(); j++) {
                var lens = report.lenses().get(j);
                if (state.matrixMount != null && !state.matrixMount.equals(mount(lens.mount(), l))) continue;
                if (!query.isEmpty() && !names.get(lens.lens().id()).toLowerCase(Locale.ROOT).contains(query)) continue;
                var rowPairs = shownCameras.stream().map(i -> report.pair(report.cameras().get(i).camera().id(), lens.lens().id()).orElseThrow()).toList();
                if (state.matrixStatus != null && rowPairs.stream().noneMatch(p -> p.mount().status() == state.matrixStatus)) continue;
                var head = new VBox(2, FeaturePane.label(names.get(lens.lens().id()), "cap-matrix-head"), FeaturePane.label(mount(lens.mount(), l) + " · " + focal(lens.focal()), "cap-caption"));
                head.setId("capability-matrix-lens-" + (j + 1));
                grid.add(head, 0, row);
                for (int k = 0; k < shownCameras.size(); k++) {
                    var pair = rowPairs.get(k); int cameraNumber = shownCameras.get(k) + 1;
                    boolean filtered = state.matrixStatus != null && pair.mount().status() != state.matrixStatus;
                    var cell = new ToggleButton(filtered ? "—" : symbol(pair.mount().relation()) + "  " + relation(pair.mount().relation(), l));
                    cell.setId("capability-cell-" + (j + 1) + "-" + cameraNumber);
                    cell.getStyleClass().setAll("cap-cell", filtered ? "cap-neutral" : statusStyle(pair.mount().status()));
                    cell.setToggleGroup(group); cell.setMaxWidth(Double.MAX_VALUE); cell.setMaxHeight(Double.MAX_VALUE); cell.setMinWidth(0);
                    cell.setAccessibleText(l.text("capability.matrix.cellAccessible", names.get(lens.lens().id()), names.get(pair.camera().id()), relation(pair.mount().relation(), l)));
                    var tip = new Tooltip(relationDetail(pair.mount(), l)); tip.setWrapText(true); tip.setMaxWidth(360); tip.setShowDelay(Duration.millis(200));
                    cell.setTooltip(tip);
                    UUID cameraId = pair.camera().id(), lensId = lens.lens().id();
                    cell.setOnAction(event -> { state.pairCamera = cameraId; state.pairLens = lensId; showPair[0].run(); });
                    cells.put(key(cameraId, lensId), cell);
                    grid.add(cell, k + 1, row);
                }
                row++;
            }
            boolean any = row > 1;
            empty.setVisible(!any); empty.setManaged(!any); scroll.setVisible(any); scroll.setManaged(any);
            // Keep the chosen combination while it is shown (or while nothing is shown); otherwise choose the first cell
            // that matches the status filter, never a greyed-out one.
            if (any && (state.pairCamera == null || !cells.containsKey(key(state.pairCamera, state.pairLens)))) {
                var firstCell = report.pairs().stream().filter(p -> cells.containsKey(key(p.camera().id(), p.lens().id())))
                    .filter(p -> state.matrixStatus == null || p.mount().status() == state.matrixStatus).findFirst();
                state.pairCamera = firstCell.map(p -> p.camera().id()).orElse(null); state.pairLens = firstCell.map(p -> p.lens().id()).orElse(null);
            }
            showPair[0].run();
        };
        camera.setValue(state.matrixCamera == null ? null : report.camera(state.matrixCamera).orElse(null));
        mount.setValue(state.matrixMount); status.setValue(state.matrixStatus);
        camera.setOnAction(event -> { state.matrixCamera = camera.getValue() == null ? null : camera.getValue().camera().id(); redraw.run(); });
        mount.setOnAction(event -> { state.matrixMount = mount.getValue(); redraw.run(); });
        status.setOnAction(event -> { state.matrixStatus = status.getValue(); redraw.run(); });
        search.textProperty().addListener((o, old, value) -> { state.matrixSearch = value; redraw.run(); });
        redraw.run();

        var legend = new FlowPane(10, 8); legend.setId("capability-matrix-legend");
        for (var relation : Relation.values())
            legend.getChildren().add(chip(symbol(relation) + "  " + relation(relation, l), statusStyle(relation.status())));
        var note = FeaturePane.label(l.text("capability.matrixNote"), "cap-caution"); note.setId("capability-matrix-note");
        var matrixCard = card(l.text("capability.section.COMPATIBILITY"), filters, scroll, empty, legend, note);
        return new VBox(16, matrixCard, card(l.text("capability.detail.title"), detail));
    }

    private static String key(UUID camera, UUID lens) { return camera + "/" + lens; }
}
