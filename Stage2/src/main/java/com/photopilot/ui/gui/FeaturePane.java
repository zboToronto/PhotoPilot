package com.photopilot.ui.gui;

import com.photopilot.config.FeatureCatalog;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/** Selected feature content; future workflow controls belong here, not in navigation. */
final class FeaturePane extends VBox {
    private final Label eyebrow = label("", "eyebrow");
    private final Label title = label("", "feature-title");
    private final Label description = label("", "description");
    private final Label status = label("", "status-badge");
    private final Label emptyDescription = label("", "secondary-text");
    private final Label feedback = label("", "feedback-text");
    private final VBox feedbackBox = new VBox(8, label("Status details", "section-title"), feedback);
    private final Button checkStatus = new Button("Check status");
    private final Button workflow = new Button("Workflow unavailable");
    private FeaturePresentation feature;
    private VBox empty;
    private FlowPane actions;
    private InventoryPane inventory;
    private CalculatorPane calculator;

    FeaturePane() {
        getStyleClass().add("feature-content");
        setMaxWidth(880);
        setMinWidth(0);
        setFillWidth(true);
        FlowPane metadata = new FlowPane(10, 8, status);
        VBox heading = new VBox(12, eyebrow, title, description, metadata);
        heading.getStyleClass().add("feature-heading");
        Label placeholder = label("No workspace content yet", "empty-title");
        emptyDescription.setMaxWidth(460);
        emptyDescription.setAlignment(Pos.CENTER);
        emptyDescription.setStyle("-fx-text-alignment: center;");
        workflow.getStyleClass().add("secondary-button");
        workflow.setDisable(true);
        workflow.setAccessibleHelp("This feature does not have an available GUI workflow.");
        empty = new VBox(14, placeholder, emptyDescription, workflow);
        empty.getStyleClass().add("empty-state");
        empty.setAlignment(Pos.CENTER);
        checkStatus.getStyleClass().add("primary-button");
        checkStatus.setId("check-status");
        checkStatus.setOnAction(event -> inspectStatus());
        Label hint = label("Check availability for this feature.", "secondary-text");
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
        if (calculator != null) { calculator.setVisible(calc); calculator.setManaged(calc); calculator.activate(calc); }
        empty.setVisible(!gear && !calc); empty.setManaged(!gear && !calc);
        actions.setVisible(!gear && !calc); actions.setManaged(!gear && !calc);
        if (inventory != null) { inventory.setVisible(gear); inventory.setManaged(gear); inventory.activate(gear); }
        eyebrow.setText(selected.group() + "  /  " + selected.id());
        title.setText(selected.title());
        description.setText(selected.description());
        refreshStatus();
        feedback.setText("");
        feedbackBox.setVisible(false);
        feedbackBox.setManaged(false);
        checkStatus.setAccessibleText("Check status for " + selected.id() + " " + selected.title());
    }

    private void refreshStatus() {
        var current = feature.status();
        status.setText(current.label());
        status.setAccessibleText("Implementation status: " + current.label());
        emptyDescription.setText(switch (current) {
            case SCAFFOLDED -> "This feature is scaffolded. Its workflow is not implemented yet, so no actions are available.";
            case IN_PROGRESS -> "This feature is in progress. Its workflow is not available in this interface yet.";
            case IMPLEMENTED -> "The application reports this feature as implemented. A GUI workflow has not been connected yet.";
            case UNAVAILABLE -> "This feature is currently unavailable. Check its status for more information.";
        });
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

    void inspectStatus() {
        refreshStatus();
        if (feature.id().equals("F08")) { showFeedback("F08 · " + feature.status().label() + ". See calc help or the calculator selector for available tools."); return; }
        if (feature.id().equals("F05") && inventory != null) { showFeedback("F05 · Implemented. Local inventory is available."); return; }
        showFeedback(feature.status() == FeaturePresentation.Status.SCAFFOLDED
            ? FeatureCatalog.unavailable(feature.id())
            : feature.id() + " · " + feature.status().label() + ". No GUI workflow is available; no operation performed.");
    }

    void showFeedback(String message) {
        feedback.setText(message);
        feedbackBox.setVisible(true);
        feedbackBox.setManaged(true);
    }

    String feedbackText() { return feedback.getText(); }
    String statusText() { return status.getText(); }
    Button checkStatusButton() { return checkStatus; }
    boolean workflowDisabled() { return workflow.isDisabled(); }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinHeight(Label.USE_PREF_SIZE);
        return label;
    }
}
