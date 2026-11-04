package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.service.PlanInputs;
import com.photopilot.service.PlanPreview;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * F10 content in the existing feature surface: session -> planning inputs -> Generate plan (Gemini with deterministic
 * tools, off the FX thread) -> structured preview with validation -> explicit approve/reject; saved plan and history
 * -> natural-language revision -> comparison -> approve/reject. Reuses GearMatchPane's controls, cards and Task
 * lifecycle; all rules live in PlanningService.
 */
final class PlanPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final TextArea request = new TextArea();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final Label inputLines = FeaturePane.label("", "secondary-text"), historyLines = FeaturePane.label("", "secondary-text");
    private final VBox controls = new VBox(16), inputsBox = new VBox(10), generateBox = new VBox(12), savedBox = new VBox(12),
        savedSections = new VBox(10), results = new VBox(18), feedbackBox = new VBox(8);
    private final Button generate, approve;
    private PlanInputs inputs;
    private PlanPreview preview;
    private boolean busy, disposed;

    PlanPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        session.setId("plan-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "plan.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> { if (preview == null) showSession(); });
        Label sessionCaption = texts.label("plan.session", "section-title"); sessionCaption.setLabelFor(session);
        Button refresh = button("gear.refresh", "plan-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);

        inputLines.setId("plan-inputs");
        inputsBox.getChildren().addAll(texts.label("plan.inputs.title", "section-title"), inputLines);
        generate = button("plan.generate", "plan-generate", this::generate, true);
        generateBox.getChildren().addAll(texts.label("plan.generate.title", "section-title"), texts.label("plan.ai.notice", "secondary-text"), new FlowPane(12, 10, generate));
        historyLines.setId("plan-history"); savedSections.setId("plan-saved");
        request.setId("plan-request"); request.setWrapText(true); request.setPrefRowCount(2); request.getStyleClass().add("session-notes");
        texts.inputMenu(request); texts.text(request.accessibleTextProperty(), "plan.revise.request"); texts.text(request.promptTextProperty(), "plan.revise.prompt");
        Label requestCaption = texts.label("plan.revise.request", "section-title"); requestCaption.setLabelFor(request);
        savedBox.getChildren().addAll(texts.label("plan.saved.title", "section-title"), historyLines, savedSections,
            new VBox(7, requestCaption, request, texts.label("plan.revise.hint", "secondary-text")),
            new FlowPane(12, 10, button("plan.revise.action", "plan-revise", this::revise, false)));
        approve = button("plan.approve", "plan-approve", this::approve, true);
        for (var box : List.of(inputsBox, generateBox, savedBox, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("plan.feedback", "section-title"), feedback);
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), sessionHint), inputsBox, generateBox, savedBox);
        hide(feedbackBox); hide(sessionHint); hide(savedBox); hide(results); hide(inputsBox); hide(generateBox);
        getChildren().addAll(controls, results, feedbackBox);
        texts.onRefresh(this::render);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; texts.close(); }
    boolean dirty() { return preview != null || !request.getText().isBlank(); }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    PlanPreview preview() { return preview; }

    /** Editable sessions only: completed/reviewed sessions keep their historical plans. */
    void refresh() {
        UUID selected = preview != null ? preview.sessionId() : session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresentOrElse(session::setValue,
                () -> { if (!editable.isEmpty()) session.setValue(editable.getFirst()); });
            texts.text(sessionHint.textProperty(), "requirement.noSessions");
            if (editable.isEmpty()) show(sessionHint); else hide(sessionHint);
            showSession();
        }, null);
    }

    private void showSession() {
        var selected = session.getValue();
        inputs = null;
        if (selected == null) { hide(inputsBox); hide(generateBox); hide(savedBox); return; }
        UUID id = selected.id();
        run(() -> app.planning().planInputs(id), found -> {
            if (session.getValue() == null || !session.getValue().id().equals(id)) return;
            inputs = found; render();
            show(inputsBox); show(generateBox);
            generate.setDisable(!found.ready());
            if (found.current().isPresent()) show(savedBox); else hide(savedBox);
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }

    private void generate() {
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        clearPreview(); message(() -> localization.text("plan.generating"));
        UUID id = selected.id();
        run(() -> app.planning().generatePreview(id), found -> {
            preview = found; session.setDisable(true); render(); show(results);
            message(() -> localization.text(found.approvable() ? "plan.ready" : "plan.readyBlocked"));
            SessionPane.reveal(this, results);
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }
    private void revise() {
        var selected = session.getValue();
        if (selected == null) return;
        String text = request.getText();
        if (text.isBlank()) { message(() -> localization.text("plan.error.request")); return; }
        clearPreview(); message(() -> localization.text("plan.revising"));
        UUID id = selected.id();
        run(() -> app.planning().revisePreview(id, text), found -> {
            preview = found; session.setDisable(true); render(); show(results);
            message(() -> localization.text(found.approvable() ? "plan.revision.ready" : "plan.readyBlocked"));
            SessionPane.reveal(this, results);
        }, failure -> message(() -> String.join(" ", failure.errors(localization))));
    }
    private void approve() {
        if (preview == null || !preview.approvable()) return;
        var reviewed = preview;
        message(() -> localization.text("plan.saving"));
        run(() -> app.planning().approve(reviewed.sessionId(), reviewed.plan()), saved -> {
            preview = null; request.clear(); clearPreview(); session.setDisable(false);
            message(() -> localization.text("plan.savedGui", saved.revision()));
            refresh();
        }, failure -> message(() -> localization.text("plan.saveFailed") + " " + String.join(" ", failure.errors(localization))));
    }
    private void reject() {
        if (preview == null) return;
        app.planning().reject(preview.sessionId(), preview.plan());
        clearPreview(); session.setDisable(false);
        message(() -> localization.text("plan.rejected"));
        showSession();
    }
    private void clearPreview() { preview = null; results.getChildren().clear(); hide(results); }

    /** Rebuilds the localized cards from the last service results (also on language change). */
    private void render() {
        if (inputs != null) {
            inputLines.setText(String.join("\n", PlanPresentation.inputs(inputs, localization)));
            historyLines.setText(String.join("\n", PlanPresentation.history(inputs.plans().stream().filter(com.photopilot.command.ShootPlan::approved)
                .sorted(Comparator.comparingInt(com.photopilot.command.ShootPlan::revision)).toList(), localization)));
            savedSections.getChildren().clear();
            inputs.current().ifPresent(plan -> {
                savedSections.getChildren().add(FeaturePane.label(PlanPresentation.heading(plan, localization), "section-title"));
                for (String section : PlanPresentation.SECTIONS)
                    savedSections.getChildren().add(card("plan.card." + section, "plan-saved-" + section, PlanPresentation.section(plan, section, localization)));
            });
        }
        results.getChildren().clear();
        if (preview == null) return;
        results.getChildren().add(FeaturePane.label(PlanPresentation.heading(preview.plan(), localization), "empty-title"));
        if (preview.revision()) results.getChildren().add(card("plan.card.compare", "plan-compare", PlanPresentation.comparison(preview, localization)));
        results.getChildren().add(card("plan.card.validation", "plan-validation", PlanPresentation.validation(preview, localization)));
        for (String section : PlanPresentation.SECTIONS)
            results.getChildren().add(card("plan.card." + section, "plan-section-" + section, PlanPresentation.section(preview.plan(), section, localization)));
        results.getChildren().add(card("plan.card.evidence", "plan-evidence", PlanPresentation.evidence(preview, localization)));
        approve.setDisable(!preview.approvable());
        var actions = new VBox(10, texts.label(preview.approvable() ? "plan.approve.prompt" : "plan.approve.blocked", "feedback-text"),
            new FlowPane(12, 10, approve, button("plan.reject", "plan-reject", this::reject, false)));
        actions.getStyleClass().add("feedback-box");
        results.getChildren().add(actions);
    }
    private VBox card(String titleKey, String id, List<String> lines) {
        var body = FeaturePane.label(String.join("\n", lines), "secondary-text"); body.setId(id);
        var box = new VBox(10, FeaturePane.label(localization.text(titleKey), "section-title"), body); box.getStyleClass().add("feedback-box");
        return box;
    }

    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy = true; controls.setDisable(true); results.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            done(); var result = task.getValue();
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else if (failure != null) failure.accept(result);
            else message(() -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; done(); message(() -> localization.text("plan.failed")); });
        operations.execute(task);
    }
    private void done() { busy = false; controls.setDisable(false); results.setDisable(false); }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
