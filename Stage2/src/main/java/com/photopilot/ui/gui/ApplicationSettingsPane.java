package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** In-app settings; changing language updates views in place and never navigates or rebuilds feature forms. */
final class ApplicationSettingsPane extends VBox {
    private final FxLocalizer texts;

    ApplicationSettingsPane(LocalizationService localization) {
        super(16); setId("application-settings"); getStyleClass().add("page"); setMaxWidth(1180); setMinWidth(0);
        texts = new FxLocalizer(this, localization);
        ComboBox<Language> language = new ComboBox<>();
        language.getItems().setAll(Language.values());
        language.setValue(localization.language());
        language.setId("settings-language");
        texts.text(language.accessibleTextProperty(), "settings.language");
        Label label = texts.label("settings.language", "group-title"); label.setLabelFor(language);
        Label feedback = FeaturePane.label("", "feedback-text"); feedback.setId("settings-feedback");
        feedback.managedProperty().bind(feedback.textProperty().isNotEmpty()); feedback.visibleProperty().bind(feedback.managedProperty());
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
        Button retry = texts.button("settings.retry"); retry.setId("settings-retry"); retry.getStyleClass().add("secondary-button"); retry.setOnAction(event -> save.run());
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        HBox row = new HBox(12, label, space, language, retry); row.setAlignment(Pos.CENTER_LEFT);
        VBox group = new VBox(12, texts.label("menu.appSettings", "page-title"), row, feedback); group.getStyleClass().add("surface-panel");
        getChildren().add(group);
    }
    void dispose() { texts.close(); }
}
