package com.photopilot.ui.gui;

import com.photopilot.application.CapabilityPresentation;
import com.photopilot.application.FocalScale;
import com.photopilot.model.GearCapabilityAnalysis.Interval;
import java.util.*;
import java.util.function.Consumer;
import javafx.css.PseudoClass;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;
import javafx.util.Duration;

/**
 * Horizontal interval chart of focal-length coverage: one row per lens (a zoom as a bar, a prime as a diamond), and a
 * combined row with the union of all plotted ranges, its gaps (dashed) and overlaps (thin band). Every position uses
 * the shared logarithmic {@link FocalScale}, exactly as the CLI chart does; the chart draws, it never calculates
 * coverage. Rows without a plottable interval show their reason instead of a bar. Colours are theme tokens (CSS), so
 * Light/Dark and Transparency apply. Click, or Up/Down with focus, selects a lens.
 */
final class FocalCoverageChart extends Region {
    /** One lens: its plotted interval (empty with a reason when it cannot be plotted), dimmed when not established for the chosen camera. */
    record Row(UUID id, String label, Optional<Interval> range, String reason, boolean dimmed, String tooltip) { }
    /** The combined row: union of the plotted ranges, open gaps between them, and overlaps where known. */
    record Combined(String label, List<Interval> coverage, List<Interval> gaps, List<Interval> overlaps) { }
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final double ROW = 30, AXIS = 24, LABEL_MIN = 110, LABEL_MAX = 260, PAD = 14, BAR = 10, MARK = 12;
    private final List<Region> rowBackgrounds = new ArrayList<>();
    private final List<Label> rowLabels = new ArrayList<>(), reasons = new ArrayList<>();
    private final List<Region> marks = new ArrayList<>();
    private final List<Region> combinedMarks = new ArrayList<>();
    private final List<Interval> combinedRanges = new ArrayList<>();
    private final List<Line> grid = new ArrayList<>();
    private final List<Label> tickLabels = new ArrayList<>();
    private final Label combinedLabel = FeaturePane.label("", "cap-chart-combined");
    private final Region divider = new Region();
    private List<Row> rows = List.of();
    private Combined combined = new Combined("", List.of(), List.of(), List.of());
    private Optional<FocalScale> scale = Optional.empty();
    private Consumer<UUID> onSelect = id -> { };
    private UUID selected;
    /** Number of ticks the current width allows; the grid is rebuilt only when it changes. */
    private int tickCount = -1;

    FocalCoverageChart() {
        getStyleClass().add("cap-chart");
        divider.getStyleClass().add("cap-chart-divider");
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.LIST_VIEW);
        setMinWidth(0);
        setOnKeyPressed(event -> {
            if (rows.isEmpty() || event.getCode() != KeyCode.DOWN && event.getCode() != KeyCode.UP) return;
            int index = indexOf(selected);
            int next = event.getCode() == KeyCode.DOWN ? Math.min(rows.size() - 1, index + 1) : Math.max(0, index < 0 ? 0 : index - 1);
            choose(rows.get(next).id()); event.consume();
        });
    }

    void onSelect(Consumer<UUID> action) { onSelect = Objects.requireNonNull(action); }

    void setData(List<Row> rows, Combined combined, Optional<FocalScale> scale) {
        this.rows = List.copyOf(rows); this.combined = combined; this.scale = scale;
        getChildren().clear(); rowBackgrounds.clear(); rowLabels.clear(); reasons.clear(); marks.clear(); combinedMarks.clear(); combinedRanges.clear();
        grid.clear(); tickLabels.clear();
        for (int i = 0; i < this.rows.size(); i++) {
            var row = this.rows.get(i);
            var background = new Region(); background.getStyleClass().add("cap-chart-row"); background.setId("capability-chart-row-" + (i + 1));
            var label = FeaturePane.label(row.label(), "cap-chart-label"); label.setWrapText(false); label.setTextOverrun(OverrunStyle.ELLIPSIS);
            label.setMinHeight(Region.USE_COMPUTED_SIZE); label.setMouseTransparent(true);
            var reason = FeaturePane.label(row.reason(), "cap-chart-reason"); reason.setWrapText(false); reason.setTextOverrun(OverrunStyle.ELLIPSIS);
            reason.setMinHeight(Region.USE_COMPUTED_SIZE); reason.setMouseTransparent(true); reason.setVisible(row.range().isEmpty());
            var mark = new Region(); mark.getStyleClass().add(row.range().map(r -> r.point() ? "cap-prime" : "cap-bar").orElse("cap-bar"));
            mark.setVisible(row.range().isPresent()); mark.setMouseTransparent(true);
            var tip = new Tooltip(row.tooltip()); tip.setShowDelay(Duration.millis(150)); tip.setWrapText(true); tip.setMaxWidth(360);
            Tooltip.install(background, tip);
            UUID id = row.id();
            background.setOnMouseClicked(event -> { requestFocus(); choose(id); });
            if (row.dimmed()) for (var node : List.of(label, mark, reason)) node.getStyleClass().add("cap-dimmed");
            rowBackgrounds.add(background); rowLabels.add(label); reasons.add(reason); marks.add(mark);
        }
        getChildren().addAll(rowBackgrounds);
        tickCount = -1;
        getChildren().addAll(rowLabels); getChildren().addAll(reasons); getChildren().addAll(marks);
        combinedLabel.setText(combined.label()); combinedLabel.setWrapText(false); combinedLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        combinedLabel.setMinHeight(Region.USE_COMPUTED_SIZE);
        addCombined(combined.gaps(), "cap-gap");
        addCombined(combined.coverage(), null);
        addCombined(combined.overlaps(), "cap-overlap");
        getChildren().addAll(divider, combinedLabel); getChildren().addAll(combinedMarks);
        applySelection();
        requestLayout();
    }
    private void addCombined(List<Interval> ranges, String style) {
        for (var range : ranges) {
            var mark = new Region(); mark.setMouseTransparent(true);
            mark.getStyleClass().add(style != null ? style : range.point() ? "cap-coverage-point" : "cap-coverage");
            combinedMarks.add(mark); combinedRanges.add(range);
        }
    }

    void select(UUID id) { selected = id; applySelection(); }
    UUID selected() { return selected; }
    int rowCount() { return rows.size(); }
    private void choose(UUID id) { select(id); onSelect.accept(id); }
    private int indexOf(UUID id) { for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) return i; return -1; }
    private void applySelection() {
        for (int i = 0; i < rowBackgrounds.size(); i++) rowBackgrounds.get(i).pseudoClassStateChanged(SELECTED, rows.get(i).id().equals(selected));
    }

    private double labelWidth(double width) { return Math.max(LABEL_MIN, Math.min(LABEL_MAX, width * 0.26)); }
    @Override protected double computeMinWidth(double height) { return 260; }
    @Override protected double computePrefWidth(double height) { return 720; }
    @Override protected double computePrefHeight(double width) { return 4 + rows.size() * ROW + 12 + ROW + 8 + AXIS; }
    @Override protected double computeMinHeight(double width) { return computePrefHeight(width); }

    /** Grid lines sit just above the row backgrounds, below labels and marks; about one tick per 56 px. */
    private void ticksFor(double plotW) {
        int wanted = Math.max(3, (int) (plotW / 56));
        if (wanted == tickCount) return;
        tickCount = wanted;
        getChildren().removeAll(grid); getChildren().removeAll(tickLabels); grid.clear(); tickLabels.clear();
        scale.ifPresent(axis -> {
            for (double tick : axis.ticks(wanted)) {
                var line = new Line(); line.getStyleClass().add("cap-grid-line"); line.setUserData(tick); line.setMouseTransparent(true); grid.add(line);
                var text = FeaturePane.label(CapabilityPresentation.number(tick), "cap-tick-label"); text.setWrapText(false);
                text.setMinHeight(Region.USE_COMPUTED_SIZE); text.setUserData(tick); tickLabels.add(text);
            }
        });
        getChildren().addAll(rowBackgrounds.size(), grid);
        getChildren().addAll(tickLabels);
        for (var node : tickLabels) node.applyCss();
    }

    @Override protected void layoutChildren() {
        double width = getWidth(), labelWidth = labelWidth(width), plotX = labelWidth + PAD, plotW = Math.max(40, width - plotX - PAD);
        ticksFor(plotW);
        double y = 4;
        for (int i = 0; i < rows.size(); i++) {
            double top = y + i * ROW, centre = top + ROW / 2;
            rowBackgrounds.get(i).resizeRelocate(0, top + 1, width, ROW - 2);
            var label = rowLabels.get(i); label.resizeRelocate(8, top, labelWidth - 8, ROW);
            var reason = reasons.get(i); reason.resizeRelocate(plotX, top, plotW, ROW);
            var range = rows.get(i).range();
            if (range.isPresent() && scale.isPresent()) place(marks.get(i), range.get(), plotX, plotW, centre, BAR);
        }
        double rowsEnd = y + rows.size() * ROW;
        divider.resizeRelocate(0, rowsEnd + 5, width, 1);
        double combinedTop = rowsEnd + 12, combinedCentre = combinedTop + ROW / 2;
        combinedLabel.resizeRelocate(8, combinedTop, labelWidth - 8, ROW);
        for (int i = 0; i < combinedMarks.size(); i++) {
            var mark = combinedMarks.get(i); var range = combinedRanges.get(i);
            if (scale.isEmpty()) continue;
            if (mark.getStyleClass().contains("cap-overlap")) place(mark, range, plotX, plotW, combinedCentre + 10, 4);
            else if (mark.getStyleClass().contains("cap-gap")) {
                double x1 = x(range.startMm(), plotX, plotW), x2 = x(range.endMm(), plotX, plotW);
                mark.resizeRelocate(snapPositionX(x1), snapPositionY(combinedCentre - 8), Math.max(3, snapSizeX(x2 - x1)), 16);
            } else place(mark, range, plotX, plotW, combinedCentre, 14);
        }
        double axisTop = combinedTop + ROW + 8;
        for (int i = 0; i < grid.size(); i++) {
            double x = snapPositionX(x((Double) grid.get(i).getUserData(), plotX, plotW)) + 0.5;
            var line = grid.get(i); line.setStartX(x); line.setEndX(x); line.setStartY(2); line.setEndY(axisTop);
            var text = tickLabels.get(i); double w = text.prefWidth(-1);
            text.resizeRelocate(snapPositionX(Math.max(plotX - 8, Math.min(width - w, x - w / 2))), axisTop + 2, w, AXIS - 4);
        }
    }
    private double x(double mm, double plotX, double plotW) { return plotX + scale.orElseThrow().fraction(mm) * plotW; }
    /** A zoom spans its endpoints (at least 4 px); a prime is a diamond centred on its focal length. */
    private void place(Region mark, Interval range, double plotX, double plotW, double centre, double height) {
        double x1 = x(range.startMm(), plotX, plotW), x2 = x(range.endMm(), plotX, plotW);
        if (range.point()) mark.resizeRelocate(snapPositionX(x1 - MARK / 2), snapPositionY(centre - MARK / 2), MARK, MARK);
        else mark.resizeRelocate(snapPositionX(x1), snapPositionY(centre - height / 2), Math.max(4, snapSizeX(x2 - x1)), height);
    }

    /** Observation helpers for tests: left and right pixel edges of a lens row's mark, and whether it is drawn. */
    double[] markBounds(int row) { var b = marks.get(row).getBoundsInParent(); return new double[]{b.getMinX(), b.getMaxX()}; }
    boolean plotted(int row) { return marks.get(row).isVisible(); }
    String reason(int row) { return reasons.get(row).isVisible() ? reasons.get(row).getText() : ""; }
    double tickX(double mm) { for (var line : grid) if ((Double) line.getUserData() == mm) return line.getStartX(); return Double.NaN; }
    List<Double> ticks() { return grid.stream().map(line -> (Double) line.getUserData()).toList(); }
}
