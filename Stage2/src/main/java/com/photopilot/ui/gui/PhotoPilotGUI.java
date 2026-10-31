package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.application.ProfileManager;
import com.photopilot.model.LocalProfile;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.util.Objects;
import java.util.UUID;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

/** Stage 1 UML element 2. JavaFX presentation over the unchanged shared application. */
public class PhotoPilotGUI extends Application {
    private PhotoPilotFacade app;
    private String draftInput = "";
    private ImagePrivacyBoundary privacy;
    private ApplicationBootstrap bootstrap;
    private ProfileManager profiles;
    private LocalProfile initialProfile;
    private GuiProfileController controller;
    private Throwable smokeFailure;
    private IllegalArgumentException startupFailure;

    public static void main(String[] args) { launch(args); }

    @Override
    public void init() {
        try {
            profiles = new ProfileManager(System.getenv());
            bootstrap = profiles.activeContext();
            var selected = profiles.activeProfile();
            if (!selected.isSuccess()) throw new IllegalArgumentException(String.join("; ", selected.errors(bootstrap.localization())));
            initialProfile = selected.value().orElseThrow();
            app = bootstrap.facade();
            privacy = new ImagePrivacyBoundary();
        } catch (IllegalArgumentException error) { startupFailure = error; }
    }

    @Override
    public void start(Stage stage) {
        if (startupFailure != null) {
            if (getParameters().getRaw().contains("--smoke-test")) throw startupFailure;
            startupError(startupFailure).showAndWait();
            Platform.exit(); return;
        }
        controller = new GuiProfileController(stage, profiles, initialProfile, context -> {
            bootstrap = context; app = context.facade(); draftInput = "";
        });
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        stage.show();
        if (getParameters().getRaw().contains("--smoke-test")) {
            controller.whenIdle(() -> {
                try {
                    var workspace = controller.workspace();
                    if (!stage.isShowing() || app.sessions() == null) {
                        throw new IllegalStateException("GUI bootstrap verification failed");
                    }
                    workspace.verifyNavigation();
                    stage.setWidth(880);
                    stage.setHeight(600);
                    workspace.applyCss();
                    workspace.layout();
                    workspace.verifyNavigation();
                    System.out.println("GUI_SMOKE_OK: window shown, all 11 features navigated, accurate statuses, disabled workflows, status feedback, selection retained, minimum-size layout");
                } catch (RuntimeException | AssertionError error) {
                    smokeFailure = error;
                    System.err.println("GUI_SMOKE_FAILED: " + error.getMessage());
                } finally {
                    controller.whenIdle(stage::close);
                }
            });
        }
    }

    public void showSession(UUID id) { onFxThread(() -> controller.showSession(id)); }

    static javafx.scene.control.Alert startupError(IllegalArgumentException error) {
        var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(com.photopilot.i18n.EnglishMessages.text("app.title"));
        alert.setHeaderText(com.photopilot.i18n.EnglishMessages.text("error.profile.startup"));
        alert.setContentText(error.getMessage());
        alert.getDialogPane().getStylesheets().add(Objects.requireNonNull(PhotoPilotGUI.class.getResource("photopilot.css")).toExternalForm());
        return alert;
    }

    public void submitBrief(UUID id, String text) {
        onFxThread(() -> {
            draftInput = text;
            controller.submitBrief(id, text);
        });
    }

    public void showFailure(String message) { onFxThread(() -> controller.showFailure(message)); }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    @Override
    public void stop() {
        if (controller != null) controller.close();
        if (profiles != null) profiles.close();
        if (bootstrap != null) System.out.println(bootstrap.localization().text("app.closed"));
        // Propagate smoke failures through the Application lifecycle to Maven's exit code.
        if (smokeFailure != null) throw new IllegalStateException("GUI smoke verification failed", smokeFailure);
    }
}
