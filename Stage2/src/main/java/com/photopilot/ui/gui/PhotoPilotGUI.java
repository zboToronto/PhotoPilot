package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.config.FeatureCatalog;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Stage 1 UML element 2. JavaFX presentation over the unchanged shared application. */
public class PhotoPilotGUI extends Application {
    private PhotoPilotFacade app;
    private String draftInput = "";
    private ImagePrivacyBoundary privacy;
    private ApplicationBootstrap bootstrap;
    private PhotoPilotWorkspace workspace;
    private Throwable smokeFailure;
    private final ExecutorService operations = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "photopilot-gui-operation");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) { launch(args); }

    @Override
    public void init() {
        bootstrap = new ApplicationBootstrap(System.getenv());
        app = bootstrap.facade();
        privacy = new ImagePrivacyBoundary();
    }

    @Override
    public void start(Stage stage) {
        workspace = new PhotoPilotWorkspace();
        workspace.connectInventory(new InventoryPane(app, operations));
        workspace.connectCalculator(new CalculatorPane(app, operations));
        Scene scene = new Scene(workspace, 1120, 760);
        scene.getStylesheets().add(Objects.requireNonNull(
            PhotoPilotGUI.class.getResource("photopilot.css"), "Missing PhotoPilot stylesheet").toExternalForm());
        stage.setTitle("PhotoPilot");
        stage.setMinWidth(880);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
        if (getParameters().getRaw().contains("--smoke-test")) {
            Platform.runLater(() -> {
                try {
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
                    stage.close();
                }
            });
        }
    }

    public void showSession(UUID id) { showFailure(FeatureCatalog.unavailable("F01")); }

    public void submitBrief(UUID id, String text) {
        onFxThread(() -> {
            draftInput = text;
            long selection = workspace.selectionVersion();
            Task<String> task = new Task<>() {
                @Override protected String call() { return String.join("; ", app.parse(id, text).errors()); }
            };
            task.setOnSucceeded(event -> {
                if (workspace.selectionVersion() == selection) showFailure(task.getValue());
            });
            task.setOnFailed(event -> {
                if (workspace.selectionVersion() == selection) {
                    showFailure("Could not process the photography brief. Please try again.");
                }
            });
            operations.execute(task);
        });
    }

    public void showFailure(String message) { onFxThread(() -> workspace.showFeedback(message)); }

    private static void onFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    @Override
    public void stop() {
        operations.shutdownNow();
        if (bootstrap != null) bootstrap.close();
        System.out.println("PhotoPilot GUI closed cleanly.");
        // Propagate smoke failures through the Application lifecycle to Maven's exit code.
        if (smokeFailure != null) throw new IllegalStateException("GUI smoke verification failed", smokeFailure);
    }
}
