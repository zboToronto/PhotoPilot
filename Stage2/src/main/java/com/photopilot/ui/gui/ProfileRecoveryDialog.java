package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.config.StorageConfiguration;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.LocalProfile;
import com.photopilot.repository.JsonLocalePreferenceRepository;
import java.util.Map;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/** Startup recovery selects an existing identity; it never repairs or resets damaged data. */
final class ProfileRecoveryDialog extends Dialog<ProfileManager> {
    private final UiOperations operations = new UiOperations();
    private boolean opening;

    ProfileRecoveryDialog(Map<String, String> environment, IllegalArgumentException failure) {
        var config = StorageConfiguration.resolve(environment).configurationDirectory();
        var language = new LocalizationService(new JsonLocalePreferenceRepository(
            config.resolve("preferences.json"), config.resolve("settings.properties")));
        var pane = getDialogPane(); pane.setId("profile-recovery");
        pane.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        var texts = new FxLocalizer(pane, language); texts.useReadableFont(pane);
        texts.text(titleProperty(), "app.title"); texts.text(headerTextProperty(), "profile.recovery");
        var feedback = new Label(language.text("user.storageFailed")); feedback.setWrapText(true); feedback.setId("profile-recovery-feedback");
        var choices = new ComboBox<LocalProfile>(); choices.setId("profile-recovery-list"); choices.setMaxWidth(Double.MAX_VALUE);
        texts.choices(choices, profile -> profile.displayName() + " (" + profile.id() + ")");
        texts.text(choices.accessibleTextProperty(), "profile.recovery");
        var available = ProfileManager.availableProfiles(environment);
        if (available.isSuccess()) choices.getItems().setAll(available.value().orElseThrow());
        else feedback.setText(language.text("user.storageFailed"));
        pane.setContent(new VBox(12, feedback, choices)); pane.setPrefWidth(560);
        var enter = new ButtonType(language.text("profile.enter"), ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType(language.text("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        pane.getButtonTypes().setAll(enter, cancel);
        var enterButton = (Button)pane.lookupButton(enter); enterButton.setId("profile-recovery-enter");
        enterButton.setDisable(true);
        choices.valueProperty().addListener((value, old, selected) -> enterButton.setDisable(opening || selected == null));
        setResultConverter(button -> null);
        enterButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); if (opening || choices.getValue() == null) return;
            var id = choices.getValue().id(); opening = true;
            enterButton.setDisable(true); choices.setDisable(true); pane.lookupButton(cancel).setDisable(true);
            operations.run(() -> {
                try { return Result.success(new ProfileManager(environment, id)); }
                catch (IllegalArgumentException error) { return Result.<ProfileManager>failureKey("error.profile.operation", error.getMessage()); }
            }, result -> {
                opening = false;
                if (result.isSuccess()) { setResult(result.value().orElseThrow()); close(); }
                else {
                    feedback.setText(language.text("user.storageFailed"));
                    enterButton.setDisable(false); choices.setDisable(false); pane.lookupButton(cancel).setDisable(false);
                }
            });
        });
        setOnCloseRequest(event -> { if (opening) event.consume(); });
        setOnHidden(event -> { operations.shutdownNow(); texts.close(); });
    }
}
