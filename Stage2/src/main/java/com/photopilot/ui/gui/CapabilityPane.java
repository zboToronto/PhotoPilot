package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.GearCapabilityAnalysis;
import java.util.concurrent.ExecutorService;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/**
 * F06 page in the existing feature surface: the UC06 shoot capability-requirement workflow
 * ({@link CapabilityProfilePane}) followed by the read-only inventory capability report, with the existing controls
 * and worker lifecycle.
 */
final class CapabilityPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final CapabilityProfilePane profile;
    private final Button refresh;
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final VBox results = new VBox(18);
    private GearCapabilityAnalysis report;
    private boolean active;
    private long generation;
    CapabilityPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        profile = new CapabilityProfilePane(app, operations, localization);
        refresh = texts.button("capability.refresh"); refresh.setId("capability-refresh"); refresh.getStyleClass().add("primary-button");
        refresh.setOnAction(event -> analyze());
        var notice = texts.label("capability.scope", "secondary-text");
        var box = new VBox(8, feedback); box.getStyleClass().add("feedback-box");
        getChildren().addAll(profile, new Separator(), texts.label("capability.inventoryTitle", "empty-title"), notice, refresh, box, results);
        texts.onRefresh(this::render);
    }
    void activate(boolean value) {
        active = value; generation++; refresh.setDisable(false);
        profile.activate(value);
        if (active) analyze();
    }
    void dispose() { active = false; generation++; profile.dispose(); texts.close(); }
    boolean dirty() { return profile.dirty(); }
    boolean busy() { return profile.busy(); }
    CapabilityProfilePane profile() { return profile; }
    private void analyze() {
        long request = ++generation; report = null; results.getChildren().clear(); refresh.setDisable(true);
        texts.text(feedback.textProperty(), "capability.loading");
        Task<Result<GearCapabilityAnalysis>> task = new Task<>() {
            protected Result<GearCapabilityAnalysis> call() { return app.capabilities().analyze(); }
        };
        task.setOnSucceeded(event -> {
            if (!active || request != generation) return;
            refresh.setDisable(false);
            var result = task.getValue();
            if (result.isSuccess()) { report = result.value().orElseThrow(); texts.text(feedback.textProperty(), "capability.loaded"); render(); }
            else texts.bind(feedback.textProperty(), () -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(event -> {
            if (active && request == generation) { refresh.setDisable(false); texts.text(feedback.textProperty(), "capability.failed"); }
        });
        operations.execute(task);
    }
    private void render() {
        results.getChildren().clear();
        if (report == null) return;
        for (var section : CapabilityPresentation.Section.values()) {
            var title = FeaturePane.label(localization.text("capability.section." + section.name()), "section-title");
            var body = FeaturePane.label(CapabilityPresentation.format(report, section, localization), "secondary-text");
            body.setId("capability-" + section.name().toLowerCase(java.util.Locale.ROOT));
            var card = new VBox(10, title, body); card.getStyleClass().add("feedback-box");
            results.getChildren().add(card);
        }
    }
    String feedbackText() { return feedback.getText(); }
}
