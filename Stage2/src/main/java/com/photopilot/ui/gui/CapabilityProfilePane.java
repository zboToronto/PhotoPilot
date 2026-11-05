package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.service.CapabilityProposal;
import com.photopilot.service.PlanningService;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * F06/UC06 content in the existing feature surface: session -> confirmed F03 requirement -> Gemini capability analysis
 * (or manual entry) -> review/edit/clarify the unreviewed draft -> explicit confirmation (replacement only by an explicit
 * action). Reuses RequirementPane's controls, feedback cards, discard confirmation and background Task pattern; every
 * rule lives in PlanningService/RequirementService and the domain.
 */
final class CapabilityProfilePane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final TextArea answers = new TextArea();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final Label requirementLines = FeaturePane.label("", "secondary-text"), savedLines = FeaturePane.label("", "secondary-text");
    private final Label origin = FeaturePane.label("", "secondary-text"), reviewLines = FeaturePane.label("", "secondary-text");
    private final Label tradeOffs = FeaturePane.label("", "secondary-text"), questionList = FeaturePane.label("", "secondary-text");
    private final Label replaceNotice = FeaturePane.label("", "feedback-text");
    private final VBox controls = new VBox(16), requirementBox = new VBox(10), savedBox = new VBox(10), review = new VBox(14),
        questions = new VBox(10), confirmation = new VBox(12), feedbackBox = new VBox(8);
    private final FlowPane actions;
    private final Button analyze, manual, confirm;
    private final CapabilityNeedEditor editor;
    private CapabilityProposal proposal;
    private PhotographySession draftSession;
    private boolean editing, busy, disposed;

    CapabilityProfilePane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization);
        session.setId("needs-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "needs.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> { if (proposal == null) showSession(); });
        Label sessionCaption = texts.label("needs.session", "section-title"); sessionCaption.setLabelFor(session);
        Button refresh = button("gear.refresh", "needs-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);
        requirementLines.setId("needs-requirement"); savedLines.setId("needs-saved");
        requirementBox.getChildren().addAll(texts.label("needs.requirementTitle", "section-title"), requirementLines);
        savedBox.getChildren().addAll(texts.label("needs.savedTitle", "section-title"), savedLines);
        analyze = button("needs.analyze", "needs-analyze", this::analyze, true);
        manual = button("needs.enter", "needs-manual", this::manual, false);
        actions = new FlowPane(12, 10, analyze, manual);
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), sessionHint),
            requirementBox, savedBox, new VBox(7, actions, texts.label("needs.privacy", "secondary-text")));

        answers.setId("needs-answers"); answers.setWrapText(true); answers.setPrefRowCount(2); answers.getStyleClass().add("session-notes");
        texts.inputMenu(answers); texts.text(answers.accessibleTextProperty(), "requirement.answers"); texts.text(answers.promptTextProperty(), "needs.answersPrompt");
        Label answersCaption = texts.label("requirement.answers", "section-title"); answersCaption.setLabelFor(answers);
        questionList.setId("needs-questions");
        questions.getChildren().addAll(texts.label("needs.clarifyTitle", "section-title"), questionList, answersCaption, answers,
            new FlowPane(12, 10, button("requirement.sendAnswers", "needs-clarify", this::clarify, false)));
        origin.setId("needs-origin"); reviewLines.setId("needs-review"); tradeOffs.setId("needs-tradeoffs"); replaceNotice.setId("needs-replace-notice");
        editor = new CapabilityNeedEditor("needs", texts, localization, this::message);
        confirm = button("needs.confirm", "needs-confirm", this::confirm, true);
        review.getChildren().addAll(texts.label("needs.reviewTitle", "empty-title"), texts.label("needs.reviewHint", "secondary-text"),
            origin, reviewLines, tradeOffs, editor, questions, replaceNotice,
            new FlowPane(12, 10, confirm, button("button.cancel", "needs-cancel", this::cancel, false)), confirmation);
        for (var box : List.of(requirementBox, savedBox, review, questions, confirmation, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("needs.feedback", "section-title"), feedback);
        controls.getChildren().add(review);
        hide(review); hide(confirmation); hide(feedbackBox); hide(sessionHint); hide(requirementBox); hide(savedBox);
        getChildren().addAll(controls, feedbackBox);
        texts.onRefresh(this::render);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; texts.close(); }
    boolean dirty() { return proposal != null; }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    CapabilityProposal proposal() { return proposal; }
    EquipmentRequirementProfile draft() { return proposal == null ? null : editor.draft(); }

    /** Editable sessions only: completed/reviewed sessions keep their historical capability profile. */
    void refresh() {
        UUID selected = draftSession != null ? draftSession.id() : session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            // An open draft is rebased onto the latest stored session so a stale-save rejection can be resolved explicitly.
            if (draftSession != null) editable.stream().filter(s -> s.id().equals(draftSession.id())).findFirst().ifPresent(fresh -> draftSession = fresh);
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresentOrElse(session::setValue,
                () -> { if (!editable.isEmpty()) session.setValue(editable.getFirst()); });
            texts.text(sessionHint.textProperty(), "requirement.noSessions");
            if (editable.isEmpty()) show(sessionHint); else hide(sessionHint);
            showSession(); render();
        }, null);
    }

    private void showSession() {
        var selected = session.getValue();
        if (selected == null) { hide(requirementBox); hide(savedBox); analyze.setDisable(true); manual.setDisable(true); return; }
        show(requirementBox); show(savedBox);
        var requirement = selected.requirements().filter(ShootRequirement::confirmed);
        texts.bind(requirementLines.textProperty(), () -> requirement.map(r -> String.join("\n", RequirementForm.describe(r, localization)))
            .orElse(localization.text("match.noRequirement")));
        var saved = selected.profile().filter(EquipmentRequirementProfile::reviewed);
        var usable = PlanningService.usableProfile(selected);
        texts.bind(savedLines.textProperty(), () -> saved.map(p -> String.join("\n", CapabilityNeedForm.profile(p, localization))
                + (usable.isSuccess() ? "" : "\n" + localization.text("needs.inconsistent", String.join(" ", usable.errors(localization)))))
            .orElse(localization.text("needs.noneSaved")));
        texts.text(manual.textProperty(), saved.isPresent() ? "needs.edit" : "needs.enter");
        analyze.setDisable(requirement.isEmpty()); manual.setDisable(requirement.isEmpty());
    }

    private void analyze() {
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        if (proposal != null) return;
        message(() -> localization.text("needs.analyzing"));
        // On failure nothing changes: no draft is opened and the saved profile stays as it was.
        run(() -> app.proposeNeeds(selected.id()), proposed -> open(selected, proposed, false, "needs.proposed"),
            failure -> message(() -> localization.text("needs.aiFailed", String.join(" ", failure.errors(localization)))));
    }
    private void manual() {
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        if (proposal != null) return;
        boolean fromSaved = selected.profile().filter(EquipmentRequirementProfile::reviewed).isPresent();
        run(() -> app.planning().manualNeeds(selected.id()), started -> open(selected, started, fromSaved, "needs.editing"), null);
    }
    private void clarify() {
        if (proposal == null) return;
        PhotographySession target = draftSession; var current = proposal.withProfile(editor.draft()); String text = answers.getText();
        message(() -> localization.text("needs.clarifying"));
        // On failure the open draft, including manual edits, stays exactly as it was.
        run(() -> app.planning().clarifyNeeds(target.id(), current, text), updated -> { answers.clear(); open(target, updated, editing, "needs.proposed"); },
            failure -> message(() -> localization.text("needs.aiFailed", String.join(" ", failure.errors(localization)))));
    }
    private void confirm() {
        if (proposal == null) return;
        var checked = editor.check();
        if (!checked.isSuccess()) { editor.render(); message(() -> localization.text("needs.problems", String.join(" ", checked.errors(localization)))); return; }
        PhotographySession expected = draftSession; var edited = editor.draft();
        boolean replace = expected.profile().filter(EquipmentRequirementProfile::reviewed).isPresent();
        message(() -> localization.text("needs.saving"));
        run(() -> app.planning().confirmProfile(expected, edited, replace), stored -> {
            close(); message(() -> localization.text("needs.saved", expected.title())); refresh();
        }, failure -> { editor.render(); message(() -> String.join(" ", failure.errors(localization))); });
    }
    private void cancel() {
        if (proposal == null) return;
        confirmation.getChildren().setAll(texts.label("session.discardPrompt", "feedback-text"), new FlowPane(12, 10,
            button("session.confirm", "needs-discard", () -> { close(); message(() -> localization.text("needs.cancelled")); showSession(); }, true),
            button("button.cancel", "needs-keep", () -> hide(confirmation), false)));
        show(confirmation); SessionPane.reveal(this, confirmation);
    }

    private void open(PhotographySession target, CapabilityProposal opened, boolean fromSaved, String key) {
        proposal = opened; draftSession = target; editing = fromSaved; session.setDisable(true); hide(actions);
        editor.open(opened.profile(), target.requirements().filter(ShootRequirement::confirmed));
        hide(confirmation); show(review); render();
        message(() -> localization.text(key)); SessionPane.reveal(this, review);
    }
    private void close() {
        proposal = null; draftSession = null; editing = false; editor.close(); answers.clear();
        session.setDisable(false); show(actions); hide(review); hide(confirmation);
    }

    /** Rebuilds the localized review texts from the open proposal (also on language change). */
    private void render() {
        if (proposal == null) return;
        var draft = editor.draft();
        texts.bind(origin.textProperty(), () -> localization.text("needs.origin." + draft.origin()));
        var lines = CapabilityNeedForm.review(proposal, localization);
        texts.bind(reviewLines.textProperty(), () -> String.join("\n", CapabilityNeedForm.review(proposal, localization)));
        if (lines.isEmpty()) hide(reviewLines); else show(reviewLines);
        texts.bind(tradeOffs.textProperty(), () -> localization.text("needs.tradeOffs") + "\n" + String.join("\n", draft.tradeOffs().stream().map(t -> "- " + t).toList()));
        if (draft.tradeOffs().isEmpty()) hide(tradeOffs); else show(tradeOffs);
        var asked = CapabilityNeedForm.questions(proposal);
        questionList.setText(String.join("\n", asked));
        if (asked.isEmpty()) hide(questionList); else show(questionList);
        boolean replacing = draftSession.profile().filter(EquipmentRequirementProfile::reviewed).isPresent() && !editing;
        texts.text(replaceNotice.textProperty(), "needs.replaceNotice");
        if (replacing) show(replaceNotice); else hide(replaceNotice);
        texts.text(confirm.textProperty(), replacing ? "needs.confirmReplace" : "needs.confirm");
        editor.render();
    }

    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy = true; controls.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            busy = false; controls.setDisable(false); var result = task.getValue();
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else if (failure != null) failure.accept(result);
            else message(() -> String.join(" ", result.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; busy = false; controls.setDisable(false); message(() -> localization.text("needs.failed")); });
        operations.execute(task);
    }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
