package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import java.util.Optional;
import javafx.beans.binding.Bindings;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.*;

/** Compact dialogs matching the workspace chooser; presentation only. */
final class AppDialogs {
    private AppDialogs() { }
    static void style(Dialog<?> dialog, Window owner) {
        dialog.initOwner(owner); dialog.initStyle(StageStyle.TRANSPARENT); dialog.setGraphic(null); AppIcons.windowIcon(dialog);
        var pane = dialog.getDialogPane(); pane.getStyleClass().add("app-dialog");
        pane.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        pane.getScene().setFill(Color.TRANSPARENT);
    }
    static Optional<String> name(Window owner, LocalizationService language, String action, String initial) {
        var dialog = new Dialog<String>(); style(dialog, owner);
        var pane = dialog.getDialogPane(); pane.setId("profile-name-dialog"); pane.setPrefWidth(420);
        var localizer = new FxLocalizer(pane, language); localizer.useReadableFont(pane);
        var confirm = new ButtonType(language.text(action), ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().setAll(confirm, new ButtonType(language.text("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE));
        var field = new TextField(initial); field.setId("profile-name");
        localizer.text(field.promptTextProperty(), "profile.nameHint"); localizer.text(field.accessibleTextProperty(), "profile.name"); localizer.inputMenu(field);
        var label = localizer.label("profile.name", "app-dialog-label"); label.setLabelFor(field);
        pane.setContent(new VBox(8, label, field));
        localizer.text(dialog.titleProperty(), action); localizer.text(dialog.headerTextProperty(), action);
        // A hint only: ProfileManager remains the authority on valid display names.
        pane.lookupButton(confirm).disableProperty().bind(Bindings.createBooleanBinding(() -> field.getText().isBlank(), field.textProperty()));
        dialog.setResultConverter(button -> button == confirm ? field.getText() : null);
        dialog.setOnShown(event -> { field.requestFocus(); field.selectAll(); });
        try { return dialog.showAndWait(); } finally { localizer.close(); }
    }
    /** Not yet shown; callers use show() so a failure never blocks the completion callback reporting it. */
    static Alert error(Window owner, LocalizationService language, String message) {
        var alert = new Alert(Alert.AlertType.ERROR); style(alert, owner);
        var pane = alert.getDialogPane(); pane.setId("profile-error"); pane.setPrefWidth(460);
        var localizer = new FxLocalizer(pane, language); localizer.useReadableFont(pane);
        localizer.text(alert.titleProperty(), "profile.errorTitle"); localizer.text(alert.headerTextProperty(), "profile.errorTitle"); localizer.text(alert.contentTextProperty(), message);
        pane.getButtonTypes().setAll(new ButtonType(language.text("profile.errorDismiss"), ButtonBar.ButtonData.CANCEL_CLOSE));
        ((Button)pane.lookupButton(pane.getButtonTypes().getFirst())).setDefaultButton(true);
        alert.setOnHidden(event -> localizer.close());
        return alert;
    }
}
