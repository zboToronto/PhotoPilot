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
    private RequirementPane requirements;
    private CapabilityPane capability;
    private GearMatchPane matching;
    private EnvironmentPane environment;
    private DiscoveryPane discovery;
    private PlanPane planning;
    private ReviewPane review;
    private InventoryPane inventory;
    private GearImportPane gearImport;
    private CalculatorPane calculator;
    private FxLocalizer texts;
    private UserSelectionPane picker;
    private UserSelectionWindow pickerWindow;
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
    boolean dirty() { return sessions.dirty() || requirements.dirty() || capability.dirty() || matching.dirty() || environment.dirty() || discovery.dirty() || planning.dirty() || review.dirty() || inventory.dirty() || gearImport.dirty() || calculator.dirty(); }
    private void install(LocalProfile selected) {
        if (picker != null) { picker.dispose(); picker = null; }
        if (workspace != null) workspace.dispose();
        if (texts != null) texts.close();
        context = profiles.activeContext(); changed.accept(context);
        var language = context.localization();
        workspace = new PhotoPilotWorkspace(language);
        sessions = new SessionPane(context.facade(), operations, language);
        requirements = new RequirementPane(context.facade(), operations, language);
        matching = new GearMatchPane(context.facade(), operations, language);
        environment = new EnvironmentPane(context.facade(), operations, language);
        discovery = new DiscoveryPane(context.facade(), operations, language, this::reviewIdea);
        planning = new PlanPane(context.facade(), operations, language);
        review = new ReviewPane(context.facade(), operations, language);
        inventory = new InventoryPane(context.facade(), operations, language);
        var view = workspace;
        // F04 shares the F05 services; each workspace has its own privacy boundary for temporary image copies.
        gearImport = new GearImportPane(context.facade(), operations, language,
            com.photopilot.ui.privacy.ImagePrivacyBoundary.fromEnvironment(System.getenv()), view::selectInventory);
        inventory.onImport(view::selectImport);
        calculator = new CalculatorPane(context.facade(), operations, language);
        capability = new CapabilityPane(context.facade(), operations, language);
        workspace.connectCapabilities(capability);
        workspace.connectSessions(sessions); workspace.connectRequirements(requirements); workspace.connectMatching(matching);
        workspace.connectEnvironment(environment); workspace.connectDiscovery(discovery); workspace.connectPlanning(planning); workspace.connectReview(review); workspace.connectInventory(inventory); workspace.connectImport(gearImport); workspace.connectCalculator(calculator);
        workspace.connectUserSettings(new UserSettingsPane(stage, profiles, operations, this::showProfile, this::switchFromSettings, language));
        workspace.connectProfiles(); workspace.showProfile(Optional.of(selected));
        texts = new FxLocalizer(workspace, language); texts.text(stage.titleProperty(), "app.title");
        if (stage.getScene() == null) {
            var scene = new Scene(workspace, 1120, 760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); ThemeManager.get().style(scene.getStylesheets()); stage.setScene(scene);
        } else stage.getScene().setRoot(workspace);
        context.storageNotice().ifPresent(workspace::showStorageNotice);
    }
    void openProfiles() {
        if (closed || switching) return;
        if (busy()) { message("profile.wait"); return; }
        if (picker != null) picker.dispose();
        pickerWindow = new UserSelectionWindow(context.localization());
        pickerWindow.setOnCloseRequest(event -> { if (!allowClose()) event.consume(); else close(); });
        picker = new UserSelectionPane(pickerWindow, profiles, operations, this::requestSwitch, this::showProfile, context.localization());
        // Shown before the main window hides, so JavaFX never sees zero open windows and exits.
        pickerWindow.present(stage, picker); stage.hide(); picker.refresh();
    }
    UserSelectionPane picker() { return picker; }
    UserSelectionWindow pickerWindow() { return pickerWindow; }
    private void showWorkspace() {
        if (pickerWindow == null) return;
        pickerWindow.restore(stage); stage.show(); pickerWindow.dispose(); pickerWindow = null;
    }
    private Window owner() { return pickerWindow != null ? pickerWindow : stage; }
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
    void requestSwitch(UUID id) { requestSwitch(id, false); }
    /**
     * The Switch action of a user in User Settings: the same checks and switch as the chooser (busy guard, unsaved-input
     * confirmation, ProfileManager.selectProfile and the workspace rebuild), then User Settings again with that user Current.
     */
    void switchFromSettings(UUID id) { requestSwitch(id, true); }
    private void requestSwitch(UUID id, boolean fromSettings) {
        if (closed) return;
        if (busy()) { message("profile.wait"); return; }
        boolean same = context.storageScope().profileId().orElseThrow().equals(id);
        if (!same && dirty() && !confirm(owner(), context.localization(), "profile.switchTitle", "profile.discard", "profile.switch")) return;
        switching = true; workspace.freeze(true); if (picker != null) picker.freeze(true);
        operations.run(() -> profiles.selectProfile(id, true), result -> {
            if (closed) return;
            switching = false;
            if (result.isSuccess()) {
                if (!same) install(result.value().orElseThrow());
                else { if (picker != null) { picker.dispose(); picker = null; } }
                showWorkspace();
                if (fromSettings) workspace.showUserSettings(); else workspace.showHome();
                showProfile(result.value());
                workspace.freeze(false);
                if (fromSettings) workspace.userSettings().notice("profile.switched");
                else workspace.showFeedback(() -> context.localization().text("profile.switched"));
            } else {
                workspace.freeze(false); if (picker != null) { picker.freeze(false); picker.failure(result); }
                else if (fromSettings) workspace.userSettings().failure();
                else workspace.showFeedback(() -> String.join("; ", result.errors(context.localization())));
            }
        });
    }
    private void message(String key) {
        if (picker != null) picker.message(key);
        else if (workspace.atUserSettings()) workspace.userSettings().notice(key);
        else workspace.showFeedback(() -> context.localization().text(key));
    }
    private boolean allowClose() {
        if (busy()) { message("profile.wait"); return false; }
        return !dirty() || confirm(owner(), context.localization(), "profile.closeTitle", "profile.discard", "profile.discardAction");
    }
    static boolean confirm(Window owner, LocalizationService language, String title, String body, String action, Object... args) {
        var yes = new ButtonType(language.text(action), ButtonBar.ButtonData.OK_DONE);
        var cancel = new ButtonType(language.text("button.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        var alert = new Alert(Alert.AlertType.CONFIRMATION, "", yes, cancel);
        AppDialogs.style(alert, owner);
        alert.getDialogPane().setId("profile-confirmation");
        var localizer = new FxLocalizer(alert.getDialogPane(), language); localizer.useReadableFont(alert.getDialogPane());
        localizer.text(alert.titleProperty(), title); localizer.text(alert.headerTextProperty(), title); localizer.text(alert.contentTextProperty(), body, args);
        localizer.text(((Button) alert.getDialogPane().lookupButton(yes)).textProperty(), action);
        localizer.text(((Button) alert.getDialogPane().lookupButton(cancel)).textProperty(), "button.cancel");
        ((Button) alert.getDialogPane().lookupButton(yes)).setDefaultButton(false);
        ((Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        try { return alert.showAndWait().filter(yes::equals).isPresent(); } finally { localizer.close(); }
    }
    void showSession(UUID id) { if (!switching && !closed) { workspace.selectSessions(); sessions.open(id); } }
    /** Routes an external brief into the F03 pane, which owns review and explicit confirmation. */
    void submitBrief(UUID id, String text) {
        if (switching || closed) return;
        workspace.selectRequirements(); requirements.submit(id, text);
    }
    /** Routes a selected F02 idea (unconfirmed draft) into the F03 pane, which owns review and explicit confirmation. */
    void reviewIdea(UUID id, com.photopilot.model.ShootRequirement draft) {
        if (switching || closed) return;
        workspace.selectRequirements(); requirements.review(id, draft);
    }
    RequirementPane requirements() { return requirements; }
    DiscoveryPane discovery() { return discovery; }
    GearMatchPane matching() { return matching; }
    CapabilityPane capability() { return capability; }
    EnvironmentPane environment() { return environment; }
    PlanPane planning() { return planning; }
    ReviewPane review() { return review; }
    void showFailure(String text) { if (!closed) workspace.showFeedback(text); }
    void whenIdle(Runnable action) { waitIdle(action, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)); }
    private void waitIdle(Runnable action, long deadline) {
        if (!busy()) { action.run(); return; }
        if (System.nanoTime() > deadline) throw new IllegalStateException("GUI operation queue did not become idle.");
        var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(25)); pause.setOnFinished(event -> waitIdle(action, deadline)); pause.play();
    }
    @Override public void close() {
        if (closed) return; closed = true;
        if (picker != null) picker.dispose();
        if (pickerWindow != null) pickerWindow.dispose();
        workspace.dispose(); texts.close(); operations.shutdownNow();
    }
}
