package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Modeless settings, so language selection never navigates or reconstructs feature forms. */
final class SettingsDialog extends Stage {
    SettingsDialog(Window owner, LocalizationService localization) {
        initOwner(owner);
        VBox content = new VBox(14);
        content.getStyleClass().add("settings-content");
        content.setPadding(new Insets(24));
        FxLocalizer texts = new FxLocalizer(content, localization);
        texts.useReadableFont(content);
        texts.text(titleProperty(), "settings.title");
        ComboBox<Language> language = new ComboBox<>();
        language.getItems().setAll(Language.values());
        language.setValue(localization.language());
        language.setId("settings-language");
        texts.text(language.accessibleTextProperty(), "settings.language");
        Label label = texts.label("settings.language", "section-title"); label.setLabelFor(language);
        Label feedback = FeaturePane.label("", "feedback-text"); feedback.setId("settings-feedback");
        boolean[] synchronizing = { false };
        texts.onRefresh(() -> {
            synchronizing[0] = true;
            try { language.setValue(localization.language()); }
            finally { synchronizing[0] = false; }
        });
        if (localization.preferenceFailure()) texts.text(feedback.textProperty(), localization.preferenceFailureKey());
        Runnable save = () -> {
            boolean saved = localization.selectLanguage(language.getValue().tag());
            texts.text(feedback.textProperty(), saved ? "settings.saved" : "settings.saveFailed");
        };
        language.setOnAction(event -> { if (!synchronizing[0]) save.run(); });
        Button retry = texts.button("settings.retry"); retry.getStyleClass().add("primary-button"); retry.setOnAction(event -> save.run());
        Button close = texts.button("settings.close"); close.getStyleClass().add("secondary-button"); close.setOnAction(event -> close());
        content.getChildren().addAll(label, language, feedback, retry, close);
        Scene scene = new Scene(content, 440, 300);
        scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        setScene(scene);
        setMinWidth(360); setMinHeight(320);
        setOnHidden(event -> texts.close());
    }
}
