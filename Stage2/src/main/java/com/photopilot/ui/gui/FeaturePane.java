package com.photopilot.ui.gui;

import com.photopilot.config.FeatureCatalog;
import com.photopilot.i18n.*;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Selected feature content; future workflow controls belong here, not in navigation. */
final class FeaturePane extends VBox {
    private final FxLocalizer texts;
    private final LocalizationService localization;
    private final Label detailsTitle = label("", "section-title");
    private final Label eyebrow = label("", "eyebrow");
    private final Label title = label("", "feature-title");
    private final Label description = label("", "description");
    private final Label status = label("", "status-badge");
    private final Label emptyDescription = label("", "secondary-text");
    private final Label feedback = label("", "feedback-text");
    private final VBox feedbackBox = new VBox(8, detailsTitle, feedback);
    private final Button checkStatus = new Button();
    private final Button workflow = new Button();
    private FeaturePresentation feature;
    private VBox empty;
    private FlowPane actions;
    private InventoryPane inventory;
    private CalculatorPane calculator;
    private SessionPane sessions;

    FeaturePane() { this(LocalizationService.english()); }
    FeaturePane(LocalizationService localization) {
        this.localization = localization;
        texts = new FxLocalizer(this, localization);
        texts.text(detailsTitle.textProperty(), "feature.statusDetails");
        texts.text(checkStatus.textProperty(), "feature.checkStatus");
        texts.text(workflow.textProperty(), "feature.workflowUnavailable");
        getStyleClass().add("feature-content");
        setMaxWidth(880);
        setMinWidth(0);
        setFillWidth(true);
        FlowPane metadata = new FlowPane(10, 8, status);
        VBox heading = new VBox(12, eyebrow, title, description, metadata);
        heading.getStyleClass().add("feature-heading");
        Label placeholder = texts.label("feature.empty", "empty-title");
        emptyDescription.setMaxWidth(460);
        emptyDescription.setAlignment(Pos.CENTER);
        emptyDescription.setStyle("-fx-text-alignment: center;");
        workflow.getStyleClass().add("secondary-button");
        workflow.setDisable(true);
        texts.text(workflow.accessibleHelpProperty(), "feature.accessibility.unavailable");
        empty = new VBox(14, placeholder, emptyDescription, workflow);
        empty.getStyleClass().add("empty-state");
        empty.setAlignment(Pos.CENTER);
        checkStatus.getStyleClass().add("primary-button");
        checkStatus.setId("check-status");
        checkStatus.setOnAction(event -> inspectStatus());
        Label hint = texts.label("feature.checkHint", "secondary-text");
        actions = new FlowPane(14, 10, checkStatus, hint);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("feature-actions");
        feedbackBox.getStyleClass().add("feedback-box");
        feedbackBox.setVisible(false);
        feedbackBox.setManaged(false);
        getChildren().addAll(heading, new Separator(), empty, new Separator(), actions, feedbackBox);
    }

    void show(FeaturePresentation selected) {
        feature = selected;
        boolean gear = selected.id().equals("F05") && inventory != null;
        boolean calc = selected.id().equals("F08") && calculator != null;
        boolean session = selected.id().equals("F01") && sessions != null;
        if (sessions != null) { sessions.setVisible(session); sessions.setManaged(session); sessions.activate(session); }
        if (calculator != null) { calculator.setVisible(calc); calculator.setManaged(calc); calculator.activate(calc); }
        empty.setVisible(!gear && !calc && !session); empty.setManaged(!gear && !calc && !session);
        actions.setVisible(!gear && !calc && !session); actions.setManaged(!gear && !calc && !session);
        if (inventory != null) { inventory.setVisible(gear); inventory.setManaged(gear); inventory.activate(gear); }
        texts.bind(eyebrow.textProperty(), () -> localization.text("feature.breadcrumb", selected.group(localization), selected.id()));
        texts.bind(title.textProperty(), () -> selected.title(localization));
        texts.bind(description.textProperty(), () -> selected.description(localization));
        refreshStatus();
        texts.bind(feedback.textProperty(), () -> "");
        feedbackBox.setVisible(false);
        feedbackBox.setManaged(false);
        texts.bind(checkStatus.accessibleTextProperty(), () -> localization.text("feature.checkAccessible", selected.id(), selected.title(localization)));
    }

    private void refreshStatus() {
        var current = feature.status();
        texts.bind(status.textProperty(), () -> current.label(localization));
        texts.bind(status.accessibleTextProperty(), () -> localization.text("feature.statusAccessible", current.label(localization)));
        texts.text(emptyDescription.textProperty(), "feature.empty." + current.name());
    }

    void connectInventory(InventoryPane inventory) {
        this.inventory = inventory;
        inventory.setVisible(false); inventory.setManaged(false);
        getChildren().add(2, inventory);
    }

    void connectCalculator(CalculatorPane calculator) {
        this.calculator=calculator; calculator.setVisible(false); calculator.setManaged(false);
        getChildren().add(2, calculator);
    }

    void connectSessions(SessionPane sessions) {
        this.sessions = sessions; sessions.setVisible(false); sessions.setManaged(false); getChildren().add(2, sessions);
        if (feature != null) show(feature);
    }

    void inspectStatus() {
        refreshStatus();
        var selected = feature;
        if (selected.id().equals("F01")) { showFeedback(() -> localization.text("session.ready")); return; }
        if (selected.id().equals("F08")) { showFeedback(() -> localization.text("feature.calculatorReady", selected.status().label(localization))); return; }
        if (selected.id().equals("F05") && inventory != null) { showFeedback(() -> localization.text("feature.inventoryReady")); return; }
        showFeedback(() -> selected.status() == FeaturePresentation.Status.SCAFFOLDED
            ? localization.text("feature.unavailable", selected.id())
            : localization.text("feature.noWorkflow", selected.id(), selected.status().label(localization)));
    }

    void showFeedback(String message) { showFeedback(() -> message); }
    void showFeedback(java.util.function.Supplier<String> message) {
        texts.bind(feedback.textProperty(), message);
        feedbackBox.setVisible(true);
        feedbackBox.setManaged(true);
    }

    String feedbackText() { return feedback.getText(); }
    String statusText() { return status.getText(); }
    Button checkStatusButton() { return checkStatus; }
    boolean workflowDisabled() { return workflow.isDisabled(); }
    boolean inventoryConnected() { return inventory != null; }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinHeight(Label.USE_PREF_SIZE);
        return label;
    }
}
