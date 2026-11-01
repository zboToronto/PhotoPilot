package com.photopilot.ui.gui;

import com.photopilot.i18n.EnglishMessages;
import javafx.geometry.Pos;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.*;

/** Displays real local initialization; no network work or artificial minimum delay. */
final class SplashScreen extends StackPane {
    SplashScreen() {
        setId("startup-splash"); getStyleClass().add("workspace");
        var progress = new ProgressIndicator(); progress.setMaxSize(28, 28);
        var panel = new VBox(22, FeaturePane.label("◉", "splash-mark"),
            FeaturePane.label("PhotoPilot", "splash-title"),
            FeaturePane.label(EnglishMessages.text("startup.loading"), "secondary-text"), progress);
        panel.setAlignment(Pos.CENTER); panel.setMaxSize(440, 330); panel.getStyleClass().add("glass-panel");
        getChildren().add(panel);
    }
}
