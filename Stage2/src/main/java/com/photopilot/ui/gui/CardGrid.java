package com.photopilot.ui.gui;

import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Responsive card layout of the capability report: as many equal columns as fit at {@code minColumn} width (at most
 * {@code maxColumns}, never more than there are cards), each row as tall as its tallest card. Cards wrap to fewer
 * columns in a narrow window and spread out on a wide one, so the dashboard uses the available width without
 * horizontal scrolling.
 */
final class CardGrid extends Pane {
    private final double minColumn, gap;
    private final int maxColumns;

    CardGrid(double minColumn, double gap, int maxColumns) {
        this.minColumn = minColumn; this.gap = gap; this.maxColumns = Math.max(1, maxColumns);
        getStyleClass().add("cap-card-grid");
        setMinWidth(0);
    }

    @Override public Orientation getContentBias() { return Orientation.HORIZONTAL; }

    private int columns(double width) {
        int cards = Math.max(1, getManagedChildren().size());
        int fit = (int) Math.floor((width + gap) / (minColumn + gap));
        return Math.max(1, Math.min(Math.min(maxColumns, cards), fit));
    }
    private double columnWidth(double inner, int columns) { return Math.max(0, (inner - gap * (columns - 1)) / columns); }

    @Override protected double computeMinWidth(double height) { Insets in = getInsets(); return in.getLeft() + in.getRight() + Math.min(minColumn, 160); }
    @Override protected double computePrefWidth(double height) {
        Insets in = getInsets(); int n = Math.min(maxColumns, Math.max(1, getManagedChildren().size()));
        return in.getLeft() + in.getRight() + n * minColumn + (n - 1) * gap;
    }
    @Override protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double inner = (width < 0 ? computePrefWidth(-1) : width) - in.getLeft() - in.getRight();
        int columns = columns(inner); double cell = columnWidth(inner, columns);
        var children = getManagedChildren();
        double height = 0;
        for (int start = 0; start < children.size(); start += columns) {
            double row = 0;
            for (int i = start; i < Math.min(children.size(), start + columns); i++) row = Math.max(row, children.get(i).prefHeight(cell));
            height += row + (start > 0 ? gap : 0);
        }
        return in.getTop() + in.getBottom() + height;
    }
    @Override protected void layoutChildren() {
        Insets in = getInsets();
        double inner = getWidth() - in.getLeft() - in.getRight();
        int columns = columns(inner); double cell = columnWidth(inner, columns);
        var children = getManagedChildren();
        double y = in.getTop();
        for (int start = 0; start < children.size(); start += columns) {
            double row = 0;
            for (int i = start; i < Math.min(children.size(), start + columns); i++) row = Math.max(row, children.get(i).prefHeight(cell));
            for (int i = start; i < Math.min(children.size(), start + columns); i++) {
                Node child = children.get(i);
                child.resizeRelocate(snapPositionX(in.getLeft() + (i - start) * (cell + gap)), snapPositionY(y), snapSizeX(cell), snapSizeY(row));
            }
            y += row + gap;
        }
    }
}
