package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.config.FeatureCatalog;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.util.UUID;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.geometry.Insets;
import javafx.stage.Stage;

/** Stage 1 UML element 2. Plain bootstrap UI; all feature workflows remain deferred. */
public class PhotoPilotGUI extends Application {
    private PhotoPilotFacade app;
    private String draftInput = "";
    private ImagePrivacyBoundary privacy;
    private ApplicationBootstrap bootstrap;
    private Label feedback;

    public static void main(String[] args) { launch(args); }

    @Override
    public void init() {
        bootstrap = new ApplicationBootstrap(System.getenv());
        app = bootstrap.facade();
        privacy = new ImagePrivacyBoundary();
    }

    @Override
    public void start(Stage stage) {
        Label heading = new Label("PhotoPilot — Stage 2 scaffolding build");
        Label initialized = new Label("Shared application layer initialized. F01–F11 are not implemented.");
        ListView<String> features = new ListView<>();
        features.getItems().setAll(FeatureCatalog.lines());
        features.getSelectionModel().selectFirst();
        features.setPrefHeight(340);
        feedback = new Label("Select a feature to inspect its status.");
        feedback.setWrapText(true);
        Button open = new Button("Check selected feature");
        open.setOnAction(event -> {
            String selected = features.getSelectionModel().getSelectedItem();
            showFailure(selected == null ? "Select a feature first."
                : FeatureCatalog.unavailable(selected.substring(0, 3)));
        });
        Button close = new Button("Exit");
        close.setOnAction(event -> stage.close());
        VBox content = new VBox(12, heading, initialized, features, open, feedback, close);
        content.setPadding(new Insets(16));
        stage.setTitle("PhotoPilot");
        stage.setScene(new Scene(content, 850, 550));
        stage.show();
        if (getParameters().getRaw().contains("--smoke-test")) {
            Platform.runLater(() -> {
                if (!stage.isShowing() || app.sessions() == null || features.getItems().size() != 11) {
                    throw new IllegalStateException("GUI bootstrap verification failed");
                }
                open.fire();
                if (!feedback.getText().startsWith("Not implemented: F01")) {
                    throw new IllegalStateException("Unavailable-operation feedback missing");
                }
                System.out.println("GUI_SMOKE_OK: window shown, 11 features, unavailable feedback displayed");
                stage.close();
            });
        }
    }

    public void showSession(UUID id) { showFailure(FeatureCatalog.unavailable("F01")); }

    public void submitBrief(UUID id, String text) {
        draftInput = text;
        showFailure(String.join("; ", app.parse(id, text).errors()));
    }

    public void showFailure(String message) { feedback.setText(message); }

    @Override
    public void stop() {
        if (bootstrap != null) bootstrap.close();
        System.out.println("PhotoPilot GUI closed cleanly.");
    }
}
