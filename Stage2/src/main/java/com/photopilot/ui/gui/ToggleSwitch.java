package com.photopilot.ui.gui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Pill-shaped on/off switch: a capsule track with a circular thumb on the left (off) or right (on). It is a standard
 * ToggleButton, so clicking anywhere on it, Space, focus traversal and the accessible toggle state behave as usual; it
 * adds only the track and thumb, coloured entirely by theme tokens in photopilot.css ({@code .toggle-switch}).
 * A user toggle slides the thumb briefly (restarted from where it is, never queued); a programmatic change, such as
 * restoring a saved preference, places it directly.
 */
final class ToggleSwitch extends ToggleButton {
    private static final Duration SLIDE = Duration.millis(140);
    private final Region thumb = new Region();
    private final Pane track = new Pane(thumb) {
        @Override protected void layoutChildren() {
            Insets in = getInsets();
            double width = thumb.prefWidth(-1), height = thumb.prefHeight(-1);
            double travel = Math.max(0, getWidth() - in.getLeft() - in.getRight() - width);
            thumb.resizeRelocate(snapPositionX(in.getLeft() + travel * position.get()),
                snapPositionY(in.getTop() + (getHeight() - in.getTop() - in.getBottom() - height) / 2), width, height);
        }
    };
    /** Thumb position: 0 off (left) to 1 on (right). */
    private final DoubleProperty position = new SimpleDoubleProperty(this, "position") {
        @Override protected void invalidated() { track.requestLayout(); }
    };
    private final Timeline slide = new Timeline();
    private boolean userToggle;

    ToggleSwitch() {
        getStyleClass().setAll("toggle-switch");
        track.getStyleClass().add("switch-track"); thumb.getStyleClass().add("switch-thumb");
        setGraphic(track); setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE); setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        position.set(isSelected() ? 1 : 0);
        selectedProperty().addListener((property, was, on) -> move(on));
    }
    /** Mouse, Space and accessibility activation all arrive here. */
    @Override public void fire() {
        userToggle = true;
        try { super.fire(); } finally { userToggle = false; }
    }
    private void move(boolean on) {
        slide.stop();
        double target = on ? 1 : 0;
        if (!userToggle || getScene() == null) { position.set(target); return; }
        slide.getKeyFrames().setAll(new KeyFrame(SLIDE, new KeyValue(position, target, Interpolator.EASE_BOTH)));
        slide.playFromStart();
    }
}
