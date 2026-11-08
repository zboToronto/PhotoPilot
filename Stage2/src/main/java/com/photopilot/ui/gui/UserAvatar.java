package com.photopilot.ui.gui;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.image.*;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

/** Presentation only: decoded local bytes or an accessible initial placeholder. */
final class UserAvatar {
    private UserAvatar() { }
    private static final int PORTRAIT_TONES = 3;
    static Node view(String name, Optional<byte[]> bytes, double size) {
        return frame(name, bytes, size, size, "avatar-initial", "user-avatar");
    }
    /** Chooser avatar, decoded above display size because that view is scaled with the window. */
    static Node portrait(String name, Optional<byte[]> bytes, double size) {
        var frame = frame(name, bytes, size, size * 2, "portrait-initial", "user-portrait");
        frame.getStyleClass().add("user-portrait-" + Math.floorMod(name.hashCode(), PORTRAIT_TONES));
        frame.setStyle("-fx-font-size: " + size * .42 + "px;");
        return frame;
    }
    /** Arc diameter of the chooser's rounded-square avatar, as a fraction of its size. */
    static final double TILE_ROUNDING = .48;
    /** Workspace-card avatar: the same initial or photo in a rounded square; the photo itself is never altered. */
    static Node tile(String name, Optional<byte[]> bytes, double size) {
        var frame = (StackPane) portrait(name, bytes, size);
        frame.setStyle("-fx-font-size: " + size * .46 + "px; -fx-background-radius: " + size * TILE_ROUNDING / 2 + ";");
        var clip = new Rectangle(size, size); clip.setArcWidth(size * TILE_ROUNDING); clip.setArcHeight(size * TILE_ROUNDING);
        frame.setClip(clip);
        return frame;
    }
    private static StackPane frame(String name, Optional<byte[]> bytes, double size, double decoded, String initialStyle, String frameStyle) {
        var placeholder = FeaturePane.label(name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT), initialStyle);
        var frame = new StackPane(placeholder);
        frame.getStyleClass().add(frameStyle);
        frame.setMinSize(size, size); frame.setPrefSize(size, size); frame.setMaxSize(size, size);
        bytes.ifPresent(data -> {
            var image = new Image(new ByteArrayInputStream(data), decoded, decoded, true, true);
            if (!image.isError()) { var view = new ImageView(image); view.setFitWidth(size); view.setFitHeight(size); view.setSmooth(true); frame.getChildren().setAll(view); }
        });
        frame.setClip(new Circle(size / 2, size / 2, size / 2)); frame.setAccessibleText(name);
        return frame;
    }
}
