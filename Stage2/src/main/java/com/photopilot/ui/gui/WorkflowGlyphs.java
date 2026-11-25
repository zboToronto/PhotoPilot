package com.photopilot.ui.gui;

import com.photopilot.workflow.MilestoneStatus;
import java.util.Optional;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;

/**
 * Session workflow marks. A milestone row's step badge sits in the sidebar's 20 px icon slot (so row text keeps the
 * width every other row has): an unfinished step shows its number in a ring, and every other status has its own shape
 * (check, half disc, exclamation, warning triangle, lock, dash), so a state is never told by colour alone. Colours come
 * from the theme tokens through style classes (photopilot.css, "Session workflow"), including on the selected row.
 */
final class WorkflowGlyphs {
    static final double SIZE = 20;
    private WorkflowGlyphs() { }

    /** The step badge of milestone {@code number}, drawn for "no active session" until {@link #set} is called. */
    static StackPane badge(int number) {
        var shapes = new Pane(); shapes.getStyleClass().add("milestone-shapes");
        // A plain Pane keeps the shapes at their drawn coordinates (a StackPane would centre each shape on its own).
        shapes.setMinSize(SIZE, SIZE); shapes.setPrefSize(SIZE, SIZE); shapes.setMaxSize(SIZE, SIZE);
        var digit = new Label(Integer.toString(number)); digit.getStyleClass().add("milestone-number");
        digit.setMinSize(Label.USE_PREF_SIZE, Label.USE_PREF_SIZE);
        var badge = new StackPane(shapes, digit); badge.getStyleClass().add("milestone-badge");
        badge.setMinSize(SIZE, SIZE); badge.setPrefSize(SIZE, SIZE); badge.setMaxSize(SIZE, SIZE);
        set(badge, Optional.empty());
        return badge;
    }

    /** Redraws a badge in place for {@code status} (empty: no active session, a neutral numbered ring). */
    static void set(StackPane badge, Optional<MilestoneStatus> status) {
        var shapes = (Pane) badge.getChildren().get(0); var digit = (Label) badge.getChildren().get(1);
        badge.getStyleClass().removeIf(style -> style.startsWith("milestone-badge-"));
        badge.getStyleClass().add("milestone-badge-" + status.map(s -> s.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')).orElse("none"));
        boolean numbered = status.isEmpty() || status.get() == MilestoneStatus.NOT_STARTED;
        digit.setVisible(numbered);
        shapes.getChildren().setAll(status.isEmpty() ? new Shape[] { ring("milestone-ring") } : switch (status.get()) {
            case COMPLETED -> new Shape[] { disc(), path("M6 10.3 L8.8 13.1 L14.2 7.3", "milestone-check") };
            case IN_PROGRESS -> new Shape[] { ring("milestone-ring-accent"), path("M10 2 A8 8 0 0 0 10 18 Z", "milestone-half") };
            case ACTION_REQUIRED -> new Shape[] { ring("milestone-ring-accent"), path("M10 5.8 L10 11 M10 14.1 L10 14.2", "milestone-mark-accent") };
            case NEEDS_REVIEW -> new Shape[] { path("M10 2.4 L18.4 17 L1.6 17 Z", "milestone-warning"), path("M10 7.6 L10 11.6 M10 14.1 L10 14.2", "milestone-warning-mark") };
            case BLOCKED -> new Shape[] { path("M5.8 9.4 L14.2 9.4 L14.2 16.2 L5.8 16.2 Z M7.6 9.4 L7.6 7.4 A2.4 2.4 0 0 1 12.4 7.4 L12.4 9.4", "milestone-lock") };
            case NOT_APPLICABLE -> new Shape[] { ring("milestone-ring-dashed"), path("M6.5 10 L13.5 10", "milestone-dash") };
            case NOT_STARTED -> new Shape[] { ring("milestone-ring") };
        });
    }

    /** A thin determinate bar in the theme accent; the percentage is always written beside it. */
    static ProgressBar bar(String id) {
        var bar = new ProgressBar(0); bar.setId(id); bar.getStyleClass().add("workflow-bar");
        bar.setMaxWidth(Double.MAX_VALUE); bar.setFocusTraversable(false);
        return bar;
    }

    private static Circle ring(String style) { var ring = new Circle(10, 10, 8); ring.getStyleClass().add(style); return ring; }
    private static Circle disc() { var disc = new Circle(10, 10, 8.5); disc.getStyleClass().add("milestone-disc"); return disc; }
    private static SVGPath path(String content, String style) {
        var path = new SVGPath(); path.setContent(content); path.getStyleClass().add(style); return path;
    }
}
