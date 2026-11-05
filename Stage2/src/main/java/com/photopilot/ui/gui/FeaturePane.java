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
    void dispose() {
        texts.close();
        if (capability != null) capability.dispose();
        if (sessions != null) sessions.dispose();
        if (requirements != null) requirements.dispose();
        if (matching != null) matching.dispose();
        if (environment != null) environment.dispose();
        if (discovery != null) discovery.dispose();
        if (planning != null) planning.dispose();
        if (review != null) review.dispose();
        if (inventory != null) inventory.dispose();
        if (gearImport != null) gearImport.dispose();
        if (calculator != null) calculator.dispose();
    }
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
    private final VBox body = new VBox();
    private FeaturePresentation feature;
    private VBox empty;
    private FlowPane actions;
    private InventoryPane inventory;
    private CalculatorPane calculator;
    private SessionPane sessions;
    private RequirementPane requirements;
    private CapabilityPane capability;
    private GearMatchPane matching;
    private EnvironmentPane environment;
    private DiscoveryPane discovery;
    private PlanPane planning;
    private ReviewPane review;
    private GearImportPane gearImport;

    FeaturePane() { this(LocalizationService.english()); }
    FeaturePane(LocalizationService localization) {
        this.localization = localization;
        texts = new FxLocalizer(this, localization);
        texts.text(detailsTitle.textProperty(), "feature.statusDetails");
        texts.text(checkStatus.textProperty(), "feature.checkStatus");
        texts.text(workflow.textProperty(), "feature.workflowUnavailable");
        getStyleClass().add("feature-content");
        setMaxWidth(1180);
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
        body.getStyleClass().add("surface-panel");
        body.getChildren().addAll(heading, empty, new Separator(), actions);
        getChildren().addAll(body, feedbackBox);
    }

    void suspendModules() {
        if (capability != null) capability.activate(false);
        if (sessions != null) sessions.activate(false);
        if (requirements != null) requirements.activate(false);
        if (matching != null) matching.activate(false);
        if (environment != null) environment.activate(false);
        if (discovery != null) discovery.activate(false);
        if (planning != null) planning.activate(false);
        if (review != null) review.activate(false);
        if (inventory != null) inventory.activate(false);
        if (gearImport != null) gearImport.activate(false);
        if (calculator != null) calculator.activate(false);
    }

    void show(FeaturePresentation selected) {
        feature = selected;
        boolean analysis = selected.id().equals("F06") && capability != null;
        if (capability != null) { capability.setVisible(analysis); capability.setManaged(analysis); capability.activate(analysis); }
        boolean gear = selected.id().equals("F05") && inventory != null;
        boolean calc = selected.id().equals("F08") && calculator != null;
        boolean session = selected.id().equals("F01") && sessions != null;
        boolean requirement = selected.id().equals("F03") && requirements != null;
        boolean match = selected.id().equals("F07") && matching != null;
        boolean env = selected.id().equals("F09") && environment != null;
        boolean plan = selected.id().equals("F10") && planning != null;
        boolean reviewing = selected.id().equals("F11") && review != null;
        boolean ideas = selected.id().equals("F02") && discovery != null;
        boolean importing = selected.id().equals("F04") && gearImport != null;
        if (gearImport != null) { gearImport.setVisible(importing); gearImport.setManaged(importing); gearImport.activate(importing); }
        if (matching != null) { matching.setVisible(match); matching.setManaged(match); matching.activate(match); }
        if (environment != null) { environment.setVisible(env); environment.setManaged(env); environment.activate(env); }
        if (discovery != null) { discovery.setVisible(ideas); discovery.setManaged(ideas); discovery.activate(ideas); }
        if (planning != null) { planning.setVisible(plan); planning.setManaged(plan); planning.activate(plan); }
        if (review != null) { review.setVisible(reviewing); review.setManaged(reviewing); review.activate(reviewing); }
        if (sessions != null) { sessions.setVisible(session); sessions.setManaged(session); sessions.activate(session); }
        if (requirements != null) { requirements.setVisible(requirement); requirements.setManaged(requirement); requirements.activate(requirement); }
        if (calculator != null) { calculator.setVisible(calc); calculator.setManaged(calc); calculator.activate(calc); }
        boolean placeholder = !gear && !calc && !session && !analysis && !requirement && !match && !env && !plan && !ideas && !importing && !reviewing;
        empty.setVisible(placeholder); empty.setManaged(placeholder);
        actions.setVisible(placeholder); actions.setManaged(placeholder);
        body.getChildren().stream().filter(Separator.class::isInstance).forEach(line -> { line.setVisible(placeholder); line.setManaged(placeholder); });
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

    void connectCapabilities(CapabilityPane capability) {
        this.capability = capability; capability.setVisible(false); capability.setManaged(false);
        var container = (VBox) empty.getParent();
        container.getChildren().add(container.getChildren().indexOf(empty), capability);
    }

    void connectInventory(InventoryPane inventory) {
        this.inventory = inventory;
        inventory.setVisible(false); inventory.setManaged(false);
        body.getChildren().add(1, inventory);
    }

    void connectCalculator(CalculatorPane calculator) {
        this.calculator=calculator; calculator.setVisible(false); calculator.setManaged(false);
        body.getChildren().add(1, calculator);
    }

    void connectSessions(SessionPane sessions) {
        this.sessions = sessions; sessions.setVisible(false); sessions.setManaged(false); body.getChildren().add(1, sessions);
        if (feature != null) show(feature);
    }

    void connectRequirements(RequirementPane requirements) {
        this.requirements = requirements; requirements.setVisible(false); requirements.setManaged(false); body.getChildren().add(1, requirements);
        if (feature != null) show(feature);
    }

    void connectMatching(GearMatchPane matching) {
        this.matching = matching; matching.setVisible(false); matching.setManaged(false); body.getChildren().add(1, matching);
        if (feature != null) show(feature);
    }

    void connectEnvironment(EnvironmentPane environment) {
        this.environment = environment; environment.setVisible(false); environment.setManaged(false); body.getChildren().add(1, environment);
        if (feature != null) show(feature);
    }

    void connectDiscovery(DiscoveryPane discovery) {
        this.discovery = discovery; discovery.setVisible(false); discovery.setManaged(false); body.getChildren().add(1, discovery);
        if (feature != null) show(feature);
    }

    void connectPlanning(PlanPane planning) {
        this.planning = planning; planning.setVisible(false); planning.setManaged(false); body.getChildren().add(1, planning);
        if (feature != null) show(feature);
    }

    void connectReview(ReviewPane review) {
        this.review = review; review.setVisible(false); review.setManaged(false); body.getChildren().add(1, review);
        if (feature != null) show(feature);
    }

    void connectImport(GearImportPane gearImport) {
        this.gearImport = gearImport; gearImport.setVisible(false); gearImport.setManaged(false); body.getChildren().add(1, gearImport);
        if (feature != null) show(feature);
    }

    void inspectStatus() {
        refreshStatus();
        var selected = feature;
        if (selected.id().equals("F04") && gearImport != null) { showFeedback(() -> localization.text("import.ready")); return; }
        if (selected.id().equals("F06")) { showFeedback(() -> localization.text("capability.scope")); return; }
        if (selected.id().equals("F01")) { showFeedback(() -> localization.text("session.ready")); return; }
        if (selected.id().equals("F02") && discovery != null) { showFeedback(() -> localization.text("discover.ready")); return; }
        if (selected.id().equals("F03")) { showFeedback(() -> localization.text("requirement.ready")); return; }
        if (selected.id().equals("F07") && matching != null) { showFeedback(() -> localization.text("match.ready")); return; }
        if (selected.id().equals("F09") && environment != null) { showFeedback(() -> localization.text("env.ready")); return; }
        if (selected.id().equals("F10") && planning != null) { showFeedback(() -> localization.text("plan.status")); return; }
        if (selected.id().equals("F11") && review != null) { showFeedback(() -> localization.text("review.ready.status")); return; }
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
    boolean matchingConnected() { return matching != null; }
    boolean environmentConnected() { return environment != null; }
    boolean planningConnected() { return planning != null; }
    boolean reviewConnected() { return review != null; }
    boolean discoveryConnected() { return discovery != null; }
    boolean importConnected() { return gearImport != null; }

    static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMinHeight(Label.USE_PREF_SIZE);
        return label;
    }
}
