package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.service.ExifAnalysis;
import com.photopilot.service.ReviewDraft;
import com.photopilot.service.ReviewInputs;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

/**
 * F11 content in the existing feature surface: completed session -> select JPEG photos / ExifTool JSON exports ->
 * Analyze metadata (local, off the FX thread, no AI) -> per-file results, saved statistics, limits and plan
 * comparison -> Generate AI review (Gemini, off the FX thread) -> draft with evidence limits -> explicit Save review
 * (Completed -> Reviewed) or Discard; saved reviews reopen read-only. Reuses PlanPane's controls, cards and Task
 * lifecycle; all rules live in ReviewService.
 */
final class ReviewPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ComboBox<PhotographySession> session = new ComboBox<>();
    private final TextField path = new TextField();
    private final CheckBox replace = new CheckBox();
    private final Label sessionHint = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final Label inputLines = FeaturePane.label("", "secondary-text"), fileLines = FeaturePane.label("", "secondary-text");
    private final VBox controls = new VBox(16), inputsBox = new VBox(10), importBox = new VBox(12), analysisBox = new VBox(12), statsBox = new VBox(12),
        generateBox = new VBox(12), savedBox = new VBox(12), results = new VBox(18), feedbackBox = new VBox(8);
    private final Button analyze, generate, save;
    private final Set<Path> selected = new LinkedHashSet<>();
    private ReviewInputs inputs;
    private ExifAnalysis analysis;
    private ReviewDraft draft;
    private boolean busy, disposed;

    ReviewPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        session.setId("review-session"); session.setMaxWidth(Double.MAX_VALUE); texts.text(session.accessibleTextProperty(), "review.session");
        texts.choices(session, s -> localization.text("requirement.sessionOption", s.title(), s.date(), localization.text("session.state." + s.status())));
        session.valueProperty().addListener((o, old, value) -> { if (draft == null) { analysis = null; showSession(); } });
        Label sessionCaption = texts.label("review.session", "section-title"); sessionCaption.setLabelFor(session);
        Button refresh = button("gear.refresh", "review-refresh", this::refresh, false); refresh.setMinWidth(Region.USE_PREF_SIZE);

        inputLines.setId("review-inputs");
        inputsBox.getChildren().addAll(texts.label("review.inputs.title", "section-title"), inputLines);
        path.setId("review-path"); texts.inputMenu(path); texts.text(path.promptTextProperty(), "review.pathPrompt"); texts.text(path.accessibleTextProperty(), "review.path");
        Label pathCaption = texts.label("review.path", "section-title"); pathCaption.setLabelFor(path);
        Button add = button("review.add", "review-add", this::addPath, false), browse = button("review.browse", "review-browse", this::browse, false);
        add.setMinWidth(Region.USE_PREF_SIZE); browse.setMinWidth(Region.USE_PREF_SIZE); HBox.setHgrow(path, Priority.ALWAYS);
        fileLines.setId("review-selected");
        replace.setId("review-replace"); replace.setWrapText(true); texts.text(replace.textProperty(), "review.replace");
        analyze = button("review.analyze", "review-analyze", this::analyze, true);
        importBox.getChildren().addAll(texts.label("review.import.title", "section-title"), texts.label("review.import.hint", "secondary-text"),
            new VBox(7, pathCaption, new HBox(12, path, add, browse)), fileLines, replace,
            new FlowPane(12, 10, analyze, button("review.clear", "review-clear", this::clearSelection, false)));
        generate = button("review.generate", "review-generate", this::generate, true);
        generateBox.getChildren().addAll(texts.label("review.generate.title", "section-title"), texts.label("review.ai.notice", "secondary-text"), new FlowPane(12, 10, generate));
        save = button("review.save", "review-save", this::save, true);
        for (var box : List.of(inputsBox, importBox, analysisBox, statsBox, generateBox, savedBox, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("review.feedback", "section-title"), feedback);
        controls.getChildren().addAll(new VBox(7, sessionCaption, new HBox(12, grow(session), refresh), sessionHint), inputsBox, importBox, analysisBox, statsBox, generateBox, savedBox);
        for (var node : List.<Node>of(feedbackBox, sessionHint, inputsBox, importBox, analysisBox, statsBox, generateBox, savedBox, results, replace)) hide(node);
        getChildren().addAll(controls, results, feedbackBox);
        renderSelection();
        texts.onRefresh(this::render);
    }
    private static Region grow(Region region) { HBox.setHgrow(region, Priority.ALWAYS); return region; }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }

    void activate(boolean active) { if (active && !busy) refresh(); }
    void dispose() { disposed = true; texts.close(); }
    boolean dirty() { return draft != null || !selected.isEmpty(); }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    ReviewDraft draft() { return draft; }
    ExifAnalysis analysis() { return analysis; }
    /** Test hook equivalent to typing a path and pressing Add. */
    void select(Path file) { selected.add(file.toAbsolutePath().normalize()); renderSelection(); }

    /** Completed sessions accept imports and reviews; reviewed ones reopen their saved evidence and reviews. */
    void refresh() {
        UUID current = draft != null ? draft.sessionId() : session.getValue() == null ? null : session.getValue().id();
        run(() -> app.sessions().search(""), found -> {
            var eligible = found.stream().filter(s -> s.status().equals("COMPLETED") || s.status().equals("REVIEWED")).toList();
            session.getItems().setAll(eligible);
            eligible.stream().filter(s -> s.id().equals(current)).findFirst().ifPresentOrElse(session::setValue,
                () -> { if (!eligible.isEmpty()) session.setValue(eligible.getFirst()); });
            texts.text(sessionHint.textProperty(), "review.noSessions");
            if (eligible.isEmpty()) show(sessionHint); else hide(sessionHint);
            showSession();
        }, null);
    }

    private void showSession() {
        var chosen = session.getValue();
        inputs = null;
        if (chosen == null) { for (var box : List.of(inputsBox, importBox, analysisBox, statsBox, generateBox, savedBox)) hide(box); return; }
        UUID id = chosen.id();
        run(() -> app.reviews().inputs(id), found -> {
            if (session.getValue() == null || !session.getValue().id().equals(id)) return;
            inputs = found; render();
            show(inputsBox);
            if (found.canAnalyze()) show(importBox); else hide(importBox);
            if (found.statistics().isPresent() || analysis != null) show(statsBox); else hide(statsBox);
            if (analysis != null) show(analysisBox); else hide(analysisBox);
            if (found.session().status().equals("COMPLETED")) show(generateBox); else hide(generateBox);
            generate.setDisable(!found.canReview());
            if (found.saved().isEmpty()) hide(savedBox); else show(savedBox);
        }, failure -> message(() -> errors(failure)));
    }

    private void addPath() {
        String text = path.getText().strip();
        if (text.isEmpty()) { message(() -> localization.text("review.error.noFiles")); return; }
        try { selected.add(Path.of(text).toAbsolutePath().normalize()); }
        catch (InvalidPathException invalid) { message(() -> localization.text("review.cli.badPath", text)); return; }
        path.clear(); renderSelection();
    }
    private void browse() {
        var chooser = new FileChooser(); chooser.setTitle(localization.text("review.browseTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(localization.text("review.browseFilter"), "*.jpg", "*.jpeg", "*.JPG", "*.JPEG", "*.json", "*.JSON"));
        var chosen = chooser.showOpenMultipleDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) { chosen.forEach(file -> selected.add(file.toPath().toAbsolutePath().normalize())); renderSelection(); }
    }
    private void clearSelection() { selected.clear(); replace.setSelected(false); hide(replace); renderSelection(); }
    private void renderSelection() {
        // File names only: the folders of the user's file system are not displayed.
        texts.bind(fileLines.textProperty(), () -> selected.isEmpty() ? localization.text("review.selected.none")
            : localization.text("review.selected", selected.size()) + "\n" + String.join("\n", selected.stream()
                .map(p -> "  " + (p.getFileName() == null ? p.toString() : p.getFileName().toString())).toList()));
    }

    private void analyze() {
        var chosen = session.getValue();
        if (chosen == null) { message(() -> localization.text("review.noSessions")); return; }
        if (selected.isEmpty()) { message(() -> localization.text("review.error.noFiles")); return; }
        var files = List.copyOf(selected); boolean replacing = replace.isSelected(); UUID id = chosen.id();
        message(() -> localization.text("review.analyzing", files.size()));
        run(() -> app.reviews().analysis(id, files, replacing), found -> {
            analysis = found;
            if (found.status().persisted()) { selected.clear(); replace.setSelected(false); hide(replace); renderSelection(); }
            if (found.status() == ExifAnalysis.Status.REPLACE_REQUIRED) show(replace);
            message(() -> ReviewPresentation.analysis(found, localization).getFirst());
            showSession();
        }, failure -> message(() -> errors(failure)));
    }
    private void generate() {
        var chosen = session.getValue();
        if (chosen == null) return;
        clearDraft(); message(() -> localization.text("review.generating"));
        UUID id = chosen.id();
        run(() -> app.reviews().draft(id), found -> {
            draft = found; session.setDisable(true); render(); show(results);
            message(() -> localization.text("review.ready"));
            SessionPane.reveal(this, results);
        }, failure -> message(() -> errors(failure) + " " + localization.text("review.cli.statisticsKept")));
    }
    private void save() {
        if (draft == null) return;
        var reviewed = draft;
        message(() -> localization.text("review.saving"));
        run(() -> app.reviews().save(reviewed.sessionId(), reviewed.review()), saved -> {
            clearDraft(); session.setDisable(false);
            message(() -> localization.text("review.savedGui"));
            refresh();
        }, failure -> message(() -> localization.text("review.saveFailed") + " " + errors(failure)));
    }
    private void discard() {
        if (draft == null) return;
        app.reviews().discard(draft.sessionId(), draft.review());
        clearDraft(); session.setDisable(false);
        message(() -> localization.text("review.discarded"));
        showSession();
    }
    private void clearDraft() { draft = null; results.getChildren().clear(); hide(results); }

    /** Rebuilds the localized cards from the last service results (also on language change). */
    private void render() {
        if (inputs != null) {
            inputLines.setText(String.join("\n", ReviewPresentation.inputs(inputs, localization)));
            analysisBox.getChildren().clear();
            if (analysis != null && analysis.sessionId().equals(inputs.session().id())) {
                analysisBox.getChildren().addAll(FeaturePane.label(localization.text("review.analysis.title"), "section-title"),
                    body("review-analysis-status", ReviewPresentation.analysis(analysis, localization)),
                    card("review.card.files", "review-files", ReviewPresentation.files(analysis.scan(), localization)));
            }
            statsBox.getChildren().clear();
            // A replace-required analysis shows the new, unsaved statistics; otherwise the saved ones.
            boolean unsaved = analysis != null && analysis.sessionId().equals(inputs.session().id())
                && (analysis.status() == ExifAnalysis.Status.REPLACE_REQUIRED || analysis.status() == ExifAnalysis.Status.SAVE_FAILED);
            var shown = unsaved ? analysis.statistics() : inputs.statistics();
            shown.ifPresent(stats -> {
                statsBox.getChildren().add(FeaturePane.label(localization.text(unsaved ? "review.stats.unsavedTitle" : "review.stats.savedTitle"), "section-title"));
                for (String section : ReviewPresentation.STATISTICS)
                    statsBox.getChildren().add(card("review.stats." + section, "review-stats-" + section, ReviewPresentation.statistics(stats, section, localization)));
                if (!unsaved) {
                    statsBox.getChildren().add(card("review.card.limits", "review-limits", ReviewPresentation.lines(inputs.limitations(), localization)));
                    if (!inputs.differences().isEmpty())
                        statsBox.getChildren().add(card("review.card.comparison", "review-comparison", ReviewPresentation.lines(inputs.differences(), localization)));
                }
            });
            savedBox.getChildren().clear();
            if (!inputs.saved().isEmpty()) {
                savedBox.getChildren().add(FeaturePane.label(localization.text("review.saved.title"), "section-title"));
                for (var review : inputs.saved()) {
                    savedBox.getChildren().add(FeaturePane.label(ReviewPresentation.heading(review, localization), "section-title"));
                    for (String section : ReviewPresentation.SECTIONS)
                        savedBox.getChildren().add(card("review.section." + section, "review-saved-" + section, ReviewPresentation.section(review, section, localization)));
                }
            }
        }
        results.getChildren().clear();
        if (draft == null) return;
        results.getChildren().add(FeaturePane.label(ReviewPresentation.heading(draft.review(), localization), "empty-title"));
        for (String section : ReviewPresentation.SECTIONS) {
            var lines = section.equals("differences") ? ReviewPresentation.lines(draft.differences(), localization)
                : ReviewPresentation.section(draft.review(), section, localization);
            results.getChildren().add(card("review.section." + section, "review-draft-" + section, lines));
        }
        results.getChildren().add(card("review.card.notes", "review-notes", ReviewPresentation.lines(draft.notes(), localization)));
        var actions = new VBox(10, texts.label("review.save.prompt", "feedback-text"),
            new FlowPane(12, 10, save, button("review.discard", "review-discard", this::discard, false)));
        actions.getStyleClass().add("feedback-box");
        results.getChildren().add(actions);
    }
    private VBox card(String titleKey, String id, List<String> lines) {
        var box = new VBox(10, FeaturePane.label(localization.text(titleKey), "section-title"), body(id, lines)); box.getStyleClass().add("feedback-box");
        return box;
    }
    private static Label body(String id, List<String> lines) {
        var body = FeaturePane.label(String.join("\n", lines), "secondary-text"); body.setId(id); return body;
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
            else message(() -> errors(result));
        });
        task.setOnFailed(e -> { if (disposed) return; done(); message(() -> localization.text("review.failed")); });
        operations.execute(task);
    }
    private String errors(Result<?> result) { return String.join(" ", result.messages().stream().map(m -> ReviewPresentation.render(m, localization)).toList()); }
    private void done() { busy = false; controls.setDisable(false); results.setDisable(false); }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
