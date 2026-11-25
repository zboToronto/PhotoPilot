package com.photopilot.ui.gui;

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * A Guided Discovery stage footer: secondary actions on the left, the forward actions (Back and the primary action)
 * right-aligned on the same row while everything fits. When the row is too narrow (small windows, long translations,
 * a larger Font Size or Bold Text) the forward actions move onto their own right-aligned row and, if needed, wrap
 * further; nothing is clipped or overlapped and no button is shortened. Layout only: it holds no navigation rule.
 */
final class DiscoveryFooter extends Pane {
    private static final double GAP = 12, ROW_GAP = 10, GROUP_GAP = 24;
    private final List<Node> start, end;

    private record Row(List<Node> left, List<Node> right) {
        Row() { this(new ArrayList<>(), new ArrayList<>()); }
        boolean empty() { return left.isEmpty() && right.isEmpty(); }
    }

    DiscoveryFooter(List<? extends Node> start, List<? extends Node> end) {
        this.start = List.copyOf(start); this.end = List.copyOf(end);
        getChildren().addAll(this.start); getChildren().addAll(this.end);
        getStyleClass().add("discover-footer");
    }

    @Override public Orientation getContentBias() { return Orientation.HORIZONTAL; }

    private static List<Node> shown(List<Node> nodes) { return nodes.stream().filter(Node::isManaged).toList(); }
    private static double width(Node node) { return snap(node.prefWidth(-1)); }
    private static double snap(double value) { return Math.ceil(value); }
    private static double span(List<Node> nodes) {
        double total = 0;
        for (Node node : nodes) total += width(node);
        return total + Math.max(0, nodes.size() - 1) * GAP;
    }

    /** Rows for the available content width; a single row when everything fits. */
    private List<Row> rows(double available) {
        var rows = new ArrayList<Row>();
        var row = new Row(); double used = 0;
        for (Node node : shown(start)) {
            double w = width(node);
            if (!row.empty() && used + GAP + w > available) { rows.add(row); row = new Row(); used = 0; }
            used += (row.empty() ? 0 : GAP) + w; row.left().add(node);
        }
        var forward = shown(end);
        if (!forward.isEmpty()) {
            double group = span(forward);
            // Beside the secondary actions when the whole group fits there, else on a row of its own; a group wider
            // than the row wraps one action after another (each row right-aligned).
            if (!row.empty() && used + GROUP_GAP + group > available) { rows.add(row); row = new Row(); used = 0; }
            if (group <= available || !row.empty()) row.right().addAll(forward);
            else for (Node node : forward) {
                double w = width(node);
                if (!row.right().isEmpty() && used + GAP + w > available) { rows.add(row); row = new Row(); used = 0; }
                used += (row.right().isEmpty() ? 0 : GAP) + w; row.right().add(node);
            }
        }
        if (!row.empty()) rows.add(row);
        return rows;
    }
    private static double height(Row row) {
        double h = 0;
        for (Node node : row.left()) h = Math.max(h, snap(node.prefHeight(-1)));
        for (Node node : row.right()) h = Math.max(h, snap(node.prefHeight(-1)));
        return h;
    }

    @Override protected double computeMinWidth(double height) {
        Insets in = getInsets(); double widest = 0;
        for (Node node : shown(start)) widest = Math.max(widest, width(node));
        for (Node node : shown(end)) widest = Math.max(widest, width(node));
        return in.getLeft() + widest + in.getRight();
    }
    @Override protected double computePrefWidth(double height) {
        Insets in = getInsets(); double total = span(shown(start));
        double forward = span(shown(end));
        if (forward > 0) total += (total > 0 ? GROUP_GAP : 0) + forward;
        return in.getLeft() + total + in.getRight();
    }
    @Override protected double computeMinHeight(double width) { return computePrefHeight(width); }
    @Override protected double computePrefHeight(double width) {
        Insets in = getInsets();
        double available = width < 0 ? Double.MAX_VALUE : Math.max(0, width - in.getLeft() - in.getRight());
        double total = 0; var rows = rows(available);
        for (int i = 0; i < rows.size(); i++) total += height(rows.get(i)) + (i > 0 ? ROW_GAP : 0);
        return in.getTop() + total + in.getBottom();
    }

    @Override protected void layoutChildren() {
        Insets in = getInsets();
        double left = in.getLeft(), available = Math.max(0, getWidth() - left - in.getRight()), y = in.getTop();
        for (Row row : rows(available)) {
            double h = height(row), x = left;
            for (Node node : row.left()) {
                double w = width(node), nh = snap(node.prefHeight(w));
                node.resizeRelocate(x, y + (h - nh) / 2, w, nh); x += w + GAP;
            }
            double right = left + available;
            var forward = row.right();
            double widthNeeded = span(forward);
            double fx = Math.max(left, right - widthNeeded);
            for (Node node : forward) {
                double w = Math.min(width(node), available), nh = snap(node.prefHeight(w));
                node.resizeRelocate(fx, y + (h - nh) / 2, w, nh); fx += w + GAP;
            }
            y += h + ROW_GAP;
        }
    }
}
