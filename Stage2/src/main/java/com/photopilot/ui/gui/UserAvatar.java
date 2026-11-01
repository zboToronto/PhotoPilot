package com.photopilot.ui.gui;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.image.*;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;

/** Presentation only: decoded local bytes or an accessible initial placeholder. */
final class UserAvatar {
    private UserAvatar() { }
    static Node view(String name, Optional<byte[]> bytes, double size) {
        var placeholder = FeaturePane.label(name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT), "avatar-initial");
        var frame = new StackPane(placeholder);
        frame.getStyleClass().add("user-avatar");
        frame.setMinSize(size, size); frame.setPrefSize(size, size); frame.setMaxSize(size, size);
        bytes.ifPresent(data -> {
            var image = new Image(new ByteArrayInputStream(data), size, size, true, true);
            if (!image.isError()) { var view = new ImageView(image); view.setFitWidth(size); view.setFitHeight(size); frame.getChildren().setAll(view); }
        });
        frame.setClip(new Circle(size / 2, size / 2, size / 2)); frame.setAccessibleText(name);
        return frame;
    }
}
