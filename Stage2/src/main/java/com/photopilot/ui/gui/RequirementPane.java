package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * F03 content in the existing feature surface: request -> Gemini draft -> review/clarify/edit -> explicit
 * confirmation. Reuses SessionPane's controls, feedback cards, discard confirmation and background Task pattern.
 */
final class RequirementPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final TextArea request = new TextArea(), answers = new TextArea();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final Label original = FeaturePane.label("", "secondary-text"), unspecified = FeaturePane.label("", "secondary-text");
    private final Label flaggedList = FeaturePane.label("", "feedback-text"), validation = FeaturePane.label("", "feedback-text");
    private final Label replaceNotice = FeaturePane.label("", "feedback-text");
    private final VBox controls = new VBox(16), saved = new VBox(10), review = new VBox(14), questions = new VBox(10),
        questionList = new VBox(6), confirmation = new VBox(12), feedbackBox = new VBox(8);
    private final FlowPane fields = new FlowPane(16, 14);
    private final Map<String, Control> inputs = new LinkedHashMap<>();
    private ShootRequirement draft;
    private PhotographySession draftSession;
    private boolean busy, disposed;
    private Runnable pendingSubmit;

    RequirementPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        session.setId("requirement-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "requirement.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> { if (draft == null) showSaved(); });
        area(request, "requirement-request", "requirement.request", 4); texts.text(request.promptTextProperty(), "requirement.requestPrompt");
        area(answers, "requirement-answers", "requirement.answers", 2); texts.text(answers.promptTextProperty(), "requirement.answersPrompt");
        Label sessionCaption = texts.label("requirement.session", "section-title"); sessionCaption.setLabelFor(session);
        Label requestCaption = texts.label("requirement.request", "section-title"); requestCaption.setLabelFor(request);
        Button refresh = button("gear.refresh", "requirement-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), sessionHint),
            new VBox(7, requestCaption, request, texts.label("requirement.privacy", "secondary-text")),
            new FlowPane(12, 10, button("requirement.analyze", "requirement-analyze", this::analyze, true),
                button("requirement.manual", "requirement-manual", this::manual, false)), saved);
        Label answersCaption = texts.label("requirement.answers", "section-title"); answersCaption.setLabelFor(answers);
        questions.getChildren().addAll(texts.label("requirement.questions", "section-title"), questionList, answersCaption, answers,
            new FlowPane(12, 10, button("requirement.sendAnswers", "requirement-clarify", this::clarify, false)));
        questions.getStyleClass().add("feedback-box");
        review.getChildren().addAll(texts.label("requirement.review", "empty-title"), texts.label("requirement.reviewHint", "secondary-text"),
            original, fields, unspecified, flaggedList, questions, validation, replaceNotice,
            new FlowPane(12, 10, button("requirement.confirm", "requirement-confirm", this::confirm, true),
                button("button.cancel", "requirement-cancel", this::cancel, false)), confirmation);
        for (var box : List.of(saved, review, confirmation, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("requirement.feedback", "section-title"), feedback);
        controls.getChildren().add(review);
        hide(review); hide(confirmation); hide(feedbackBox); hide(saved); hide(sessionHint);
        getChildren().addAll(controls, feedbackBox);
    }
    private void area(TextArea area, String id, String accessible, int rows) {
        area.setId(id); area.setWrapText(true); area.setPrefRowCount(rows); area.getStyleClass().add("session-notes");
        texts.inputMenu(area); texts.text(area.accessibleTextProperty(), accessible);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; texts.close(); }
    boolean dirty() { return draft != null || !request.getText().isBlank(); }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    ShootRequirement draft() { return draft; }

    /** Editable sessions only: completed/reviewed sessions keep their historical requirements. */
    void refresh() {
        UUID selected = draftSession != null ? draftSession.id() : session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var editable = found.stream().filter(s -> s.state().canEditPlan()).toList();
            // An open draft is rebased onto the latest stored session so a stale-save rejection can be resolved
            // explicitly; the replace notice is re-evaluated against what is stored now.
            if (draftSession != null) editable.stream().filter(s -> s.id().equals(draftSession.id())).findFirst().ifPresent(fresh -> {
                draftSession = fresh; if (fresh.requirements().isPresent()) show(replaceNotice); else hide(replaceNotice);
            });
            session.getItems().setAll(editable);
            editable.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresentOrElse(session::setValue,
                () -> { if (!editable.isEmpty()) session.setValue(editable.getFirst()); });
            texts.text(sessionHint.textProperty(), "requirement.noSessions");
            if (editable.isEmpty()) show(sessionHint); else hide(sessionHint);
            if (draft == null) showSaved();
            if (pendingSubmit != null) { var next = pendingSubmit; pendingSubmit = null; next.run(); }
        }, null);
    }
    /** Entry from PhotoPilotGUI.submitBrief: after the session list loads, selects the session and analyzes. */
    void submit(UUID id, String text) {
        if (draft != null) { message(() -> localization.text("session.wait")); return; }
        request.setText(text);
        pendingSubmit = () -> session.getItems().stream().filter(s -> s.id().equals(id)).findFirst().ifPresent(s -> { session.setValue(s); analyze(); });
        if (!busy) refresh();
    }

    private void showSaved() {
        var selected = session.getValue();
        if (selected == null || draft != null) { hide(saved); return; }
        saved.getChildren().setAll(texts.label("requirement.savedTitle", "section-title"));
        var current = selected.requirements().filter(ShootRequirement::confirmed);
        if (current.isEmpty()) saved.getChildren().add(texts.label("requirement.noneSaved", "secondary-text"));
        else {
            Label lines = FeaturePane.label("", "secondary-text"); lines.setId("requirement-saved");
            texts.bind(lines.textProperty(), () -> String.join("\n", RequirementForm.describe(current.get(), localization)));
            saved.getChildren().addAll(lines, new FlowPane(12, 10, button("requirement.edit", "requirement-edit", () -> open(selected, current.get().asDraft(), "requirement.editing"), false)));
        }
        show(saved);
    }

    private void analyze() {
        var selected = session.getValue(); String text = request.getText();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        if (text.isBlank()) { message(() -> localization.text("requirement.error.request")); return; }
        message(() -> localization.text("requirement.analyzing"));
        // On failure the request text and any open draft stay exactly as they were.
        run(() -> app.parse(selected.id(), text), parsed -> open(selected, parsed, "requirement.parsed"),
            failure -> message(() -> localization.text("requirement.aiFailed", String.join("; ", failure.errors(localization)))));
    }
    private void manual() {
        var selected = session.getValue();
        if (selected == null) { message(() -> localization.text("requirement.noSessions")); return; }
        if (draft != null) return;
        open(selected, RequirementForm.blank(request.getText()), "requirement.manualStarted");
    }
    private void clarify() {
        var current = current(); if (current == null) return;
        PhotographySession target = draftSession; String text = answers.getText();
        message(() -> localization.text("requirement.clarifying"));
        run(() -> app.planning().clarify(target.id(), current, text), updated -> { answers.clear(); open(target, updated, "requirement.parsed"); },
            failure -> message(() -> localization.text("requirement.aiFailed", String.join("; ", failure.errors(localization)))));
    }
    private void confirm() {
        var current = current(); if (current == null) return;
        if (!current.problems().isEmpty()) { validate(); message(() -> validation.getText()); return; }
        PhotographySession expected = draftSession;
        message(() -> localization.text("requirement.saving"));
        run(() -> app.planning().confirm(expected, current), stored -> {
            close(); request.clear();
            message(() -> localization.text("requirement.saved", expected.title())); refresh();
        }, null);
    }
    private void cancel() {
        if (draft == null) return;
        confirmation.getChildren().setAll(texts.label("session.discardPrompt", "feedback-text"), new FlowPane(12, 10,
            button("session.confirm", "requirement-discard", () -> { hide(confirmation); close(); message(() -> localization.text("requirement.cancelled")); showSaved(); }, true),
            button("button.cancel", "requirement-keep", () -> hide(confirmation), false)));
        show(confirmation); SessionPane.reveal(this, confirmation);
    }

    /** Current field text applied to the draft; null (with feedback) if any field is unreadable. */
    private ShootRequirement current() {
        if (draft == null) { message(() -> localization.text("requirement.error.noDraft")); return null; }
        var applied = RequirementForm.apply(draft, values(), ZoneId.systemDefault());
        if (applied.isSuccess()) return applied.value().orElseThrow();
        validate(); message(() -> validation.getText()); return null;
    }

    private void open(PhotographySession target, ShootRequirement requirement, String key) {
        draft = requirement; draftSession = target; session.setDisable(true);
        fields.getChildren().clear(); inputs.clear();
        var values = RequirementForm.format(requirement);
        for (String field : ShootRequirement.FIELDS) field(field, values.get(field), requirement.reviewFields().contains(field));
        texts.bind(original.textProperty(), () -> localization.text("requirement.original",
            requirement.originalText().isBlank() ? localization.text("requirement.unspecified") : requirement.originalText()));
        questionList.getChildren().clear();
        var asked = requirement.clarificationQuestions();
        for (int i = 0; i < asked.size(); i++) questionList.getChildren().add(FeaturePane.label((i + 1) + ". " + asked.get(i), "secondary-text"));
        if (asked.isEmpty()) hide(questions); else show(questions);
        var flagged = requirement.reviewFields();
        texts.bind(flaggedList.textProperty(), () -> localization.text("requirement.flaggedList", names(flagged)));
        if (flagged.isEmpty()) hide(flaggedList); else show(flaggedList);
        texts.text(replaceNotice.textProperty(), "requirement.replaceNotice");
        if (target.requirements().isPresent()) show(replaceNotice); else hide(replaceNotice);
        hide(confirmation); hide(saved); show(review); validate();
        message(() -> localization.text(key)); SessionPane.reveal(this, review);
    }
    private void close() { draft = null; draftSession = null; session.setDisable(false); hide(review); hide(confirmation); fields.getChildren().clear(); inputs.clear(); }

    private void field(String key, String value, boolean flagged) {
        Control input;
        if (key.equals("lensChanges")) {
            ComboBox<String> choice = new ComboBox<>(); choice.getItems().addAll("", "allowed", "avoid"); choice.setValue(value);
            texts.choices(choice, v -> localization.text(v.isEmpty() ? "requirement.unspecified" : "requirement.lens." + v), () -> localization.text("requirement.unspecified"));
            choice.valueProperty().addListener((o, old, now) -> validate()); choice.setMaxWidth(Double.MAX_VALUE); input = choice;
        } else {
            TextField text = new TextField(value); texts.inputMenu(text); text.textProperty().addListener((o, old, now) -> validate());
            if (Set.of("goals", "date", "time", "timeWindow", "duration", "maxWeight", "priorities", "constraints").contains(key))
                texts.text(text.promptTextProperty(), "requirement.prompt." + key);
            input = text;
        }
        input.setId("requirement-field-" + key); texts.bind(input.accessibleTextProperty(), () -> localization.text("requirement.field." + key));
        Label caption = FeaturePane.label("", "section-title"); caption.setLabelFor(input);
        texts.bind(caption.textProperty(), () -> key.equals("subject") ? localization.text("requirement.required", localization.text("requirement.field.subject"))
            : localization.text("requirement.field." + key));
        VBox box = new VBox(7, caption, input); box.setMinWidth(0);
        if (flagged) box.getChildren().add(texts.label("requirement.flagged", "feedback-text"));
        box.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth() < 560 ? Math.max(160, fields.getWidth()) : (fields.getWidth() - 16) / 2, fields.widthProperty()));
        fields.getChildren().add(box); inputs.put(key, input);
    }
    @SuppressWarnings("unchecked")
    private Map<String, String> values() {
        var result = new LinkedHashMap<String, String>();
        inputs.forEach((key, input) -> result.put(key, input instanceof TextInputControl text ? text.getText()
            : Objects.toString(((ComboBox<String>) input).getValue(), "")));
        return result;
    }
    /** Live deterministic validation of the visible fields; the model's reasoning is never consulted. */
    private void validate() {
        if (draft == null) return;
        var applied = RequirementForm.apply(draft, values(), ZoneId.systemDefault());
        if (!applied.isSuccess()) {
            texts.bind(validation.textProperty(), () -> localization.text("requirement.problems", String.join(" ", applied.errors(localization))));
            hide(unspecified); return;
        }
        var requirement = applied.value().orElseThrow(); var problems = requirement.problems();
        texts.bind(validation.textProperty(), () -> problems.isEmpty() ? localization.text("requirement.valid")
            : localization.text("requirement.problems", String.join(" ", problems.stream().map(p -> p.message().render(localization)).toList())));
        var missing = requirement.unspecifiedFields();
        texts.bind(unspecified.textProperty(), () -> localization.text("requirement.unspecifiedList", names(missing)));
        if (missing.isEmpty()) hide(unspecified); else show(unspecified);
    }
    private String names(Collection<String> fieldNames) {
        return String.join(", ", fieldNames.stream().map(name -> localization.text("requirement.field." + name)).toList());
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
            else message(() -> String.join("; ", result.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; busy = false; controls.setDisable(false); message(() -> localization.text("requirement.failed")); });
        operations.execute(task);
    }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
