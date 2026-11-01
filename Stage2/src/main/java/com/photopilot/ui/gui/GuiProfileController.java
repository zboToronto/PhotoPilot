package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.*;
import com.photopilot.model.LocalProfile;
import java.util.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.*;

/** Window lifecycle over the shared ProfileManager; no profile persistence or business rules. */
final class GuiProfileController implements AutoCloseable {
    private final Stage stage;
    private final ProfileManager profiles;
    private final Consumer<ApplicationBootstrap> changed;
    private final UiOperations operations = new UiOperations();
    private ApplicationBootstrap context;
    private PhotoPilotWorkspace workspace;
    private SessionPane sessions;
    private InventoryPane inventory;
    private CalculatorPane calculator;
    private FxLocalizer texts;
    private UserSelectionPane picker;
    private boolean switching, closed;

    GuiProfileController(Stage stage, ProfileManager profiles, LocalProfile initial, Consumer<ApplicationBootstrap> changed) {
        this.stage = stage; this.profiles = profiles; this.changed = changed;
        install(initial);
        openProfiles();
        stage.setOnCloseRequest(event -> { if (!allowClose()) event.consume(); else close(); });
    }
    PhotoPilotWorkspace workspace() { return workspace; }
    ApplicationBootstrap context() { return context; }
    UiOperations operations() { return operations; }
    boolean busy() { return switching || operations.busy(); }
    boolean dirty() { return sessions.dirty() || inventory.dirty() || calculator.dirty(); }
    private void install(LocalProfile selected) {
        if (picker != null) { picker.dispose(); picker = null; }
        if (workspace != null) workspace.dispose();
        if (texts != null) texts.close();
        context = profiles.activeContext(); changed.accept(context);
        var language = context.localization();
        workspace = new PhotoPilotWorkspace(language);
        sessions = new SessionPane(context.facade(), operations, language);
        inventory = new InventoryPane(context.facade(), operations, language);
        calculator = new CalculatorPane(context.facade(), operations, language);
        workspace.connectSessions(sessions); workspace.connectInventory(inventory); workspace.connectCalculator(calculator);
        workspace.connectProfiles(this::openProfiles); workspace.showProfile(Optional.of(selected));
        texts = new FxLocalizer(workspace, language); texts.text(stage.titleProperty(), "app.title");
        if (stage.getScene() == null) {
            var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene);
        } else stage.getScene().setRoot(workspace);
        context.storageNotice().ifPresent(workspace::showStorageNotice);
    }
    void openProfiles() {
        if (closed || switching) return;
        if (busy()) { message("profile.wait"); return; }
        if (picker != null) picker.dispose();
        picker = new UserSelectionPane(stage, profiles, operations, this::requestSwitch, this::showProfile, context.localization());
        workspace.closeSettings();
        stage.getScene().setRoot(picker); picker.refresh();
    }
    UserSelectionPane picker() { return picker; }
    private void showProfile(Optional<LocalProfile> profile) {
        if (closed) return;
        workspace.showProfile(profile);
        var view = workspace;
        if (profile.isEmpty()) { view.showAvatar("", Optional.empty()); return; }
        var selected = profile.orElseThrow();
        operations.run(() -> profiles.avatar(selected.id()), result -> {
            if (!closed && view == workspace) view.showAvatar(selected.displayName(), result.value().orElse(Optional.empty()));
        });
    }
    void requestSwitch(UUID id) {
        if (closed) return;
        if (busy()) { message("profile.wait"); return; }
        boolean same = context.storageScope().profileId().orElseThrow().equals(id);
        if (!same && dirty() && !confirm(stage, context.localization(), "profile.switchTitle", "profile.discard", "profile.switch")) return;
        switching = true; workspace.freeze(true); if (picker != null) picker.freeze(true);
        operations.run(() -> profiles.selectProfile(id, true), result -> {
            if (closed) return;
            switching = false;
            if (result.isSuccess()) {
                if (!same) install(result.value().orElseThrow());
                else { if (picker != null) { picker.dispose(); picker = null; } }
                stage.getScene().setRoot(workspace); workspace.showHome(); showProfile(result.value());
                workspace.freeze(false); workspace.showFeedback(() -> context.localization().text("profile.switched"));
            } else {
                workspace.freeze(false); if (picker != null) { picker.freeze(false); picker.failure(result); }
                else workspace.showFeedback(() -> String.join("; ", result.errors(context.localization())));
            }
        });
    }
    private void message(String key) {
        if (picker != null) picker.message(key);
        else workspace.showFeedback(() -> context.localization().text(key));
    }
    private boolean allowClose() {
        if (busy()) { message("profile.wait"); return false; }
        return !dirty() || confirm(stage, context.localization(), "profile.closeTitle", "profile.discard", "profile.discardAction");
    }
    static boolean confirm(Window owner, LocalizationService language, String title, String body, String action, Object... args) {
        var yes = new ButtonType(language.text(action), ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType(language.text("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, "", yes, cancel); alert.initOwner(owner);
        alert.getDialogPane().setId("profile-confirmation");
        alert.getDialogPane().getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        var localizer = new FxLocalizer(alert.getDialogPane(), language); localizer.useReadableFont(alert.getDialogPane());
        localizer.text(alert.titleProperty(), title); localizer.text(alert.headerTextProperty(), title); localizer.text(alert.contentTextProperty(), body, args);
        localizer.text(((Button) alert.getDialogPane().lookupButton(yes)).textProperty(), action);
        localizer.text(((Button) alert.getDialogPane().lookupButton(cancel)).textProperty(), "button.cancel");
        ((Button) alert.getDialogPane().lookupButton(yes)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        try { return alert.showAndWait().filter(yes::equals).isPresent(); } finally { localizer.close(); }
    }
    void showSession(UUID id) { if (!switching && !closed) { workspace.selectSessions(); sessions.open(id); } }
    void submitBrief(UUID id, String text) {
        if (switching || closed) return;
        var captured = context; var view = workspace; long selection = view.selectionVersion();
        operations.run(() -> captured.facade().parse(id, text), result -> {
            if (!closed && view == workspace && view.selectionVersion() == selection)
                view.showFeedback(() -> String.join("; ", result.errors(captured.localization())));
        });
    }
    void showFailure(String text) { if (!closed) workspace.showFeedback(text); }
    void whenIdle(Runnable action) { waitIdle(action, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)); }
    private void waitIdle(Runnable action, long deadline) {
        if (!busy()) { action.run(); return; }
        if (System.nanoTime() > deadline) throw new IllegalStateException("GUI operation queue did not become idle.");
        var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(25)); pause.setOnFinished(event -> waitIdle(action, deadline)); pause.play();
    }
    @Override public void close() {
        if (closed) return; closed = true;
        if (picker != null) picker.dispose(); workspace.dispose(); texts.close(); operations.shutdownNow();
    }
}
