package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.PhotographySession;
import com.photopilot.state.SessionEvent;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** F01 content uses the existing shell, styles, feedback cards and background Task convention. */
final class SessionPane extends VBox {
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final TableView<PhotographySession> table = new TableView<>();
    private final TextField search = new TextField(), dateFilter = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final ComboBox<SessionEvent> event = new ComboBox<>();
    private final Label count = FeaturePane.label("", "secondary-text");
    private final Label feedback = FeaturePane.label("", "feedback-text");
    private final VBox feedbackBox = new VBox(8), confirmation = new VBox(12), editor = new VBox(16), controls = new VBox(16);
    private final FlowPane fields = new FlowPane(16, 14);
    private final Map<String, TextInputControl> inputs = new LinkedHashMap<>();
    private Map<String, String> baseline = Map.of();
    private PhotographySession editing;
    private boolean busy;
    private boolean disposed;
    void dispose() { disposed = true; pendingOpen = null; pendingLifecycle = false; texts.close(); }
    private UUID pendingOpen;
    /** Whether the pending open came from the Shooting Completion entry (reveal its lifecycle action once opened). */
    private boolean pendingLifecycle;
    /** The open session's recommended next milestone from the shared workflow, with a Continue button. */
    private final NextStepHint nextStep;
    /** "Ready to shoot · 57% · 4 of 7 milestones" for the open session. */
    private final Label progress = FeaturePane.label("", "secondary-text");
    private final Button resume;
    private SessionWorkflowModel workflow;
    private java.util.function.Consumer<UUID> resumeAction = id -> { };
    private VBox lifecycleRow;

    SessionPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        nextStep = new NextStepHint(localization, "session-next");
        texts.onRefresh(nextStep::render);
        search.setId("session-search"); search.setPrefWidth(240); texts.text(search.promptTextProperty(), "session.search");
        texts.text(search.accessibleTextProperty(), "session.search"); texts.inputMenu(search);
        dateFilter.setId("session-date-filter"); dateFilter.setPrefWidth(175); texts.text(dateFilter.promptTextProperty(), "session.dateFilter");
        texts.text(dateFilter.accessibleTextProperty(), "session.dateFilter"); texts.inputMenu(dateFilter);
        statusFilter.getItems().addAll("", "DRAFT", "PLANNED", "COMPLETED", "REVIEWED"); statusFilter.setValue("");
        statusFilter.setId("session-status-filter"); texts.text(statusFilter.accessibleTextProperty(), "session.statusFilter");
        texts.choices(statusFilter, value -> localization.text(value.isEmpty() ? "session.allStates" : "session.state." + value));
        table.setId("session-table"); texts.text(table.accessibleTextProperty(), "session.history");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("session.titleColumn", 200, PhotographySession::title); column("session.dateColumn", 100, s -> s.date().toString());
        column("session.status", 100, s -> localization.text("session.state." + s.status()));
        progressColumn();
        // Workflow columns come from the window's workflow model (the sidebar's source); blank until it has the session.
        column("session.nextColumn", 160, s -> workflowOf(s).map(w -> w.next().map(m -> localization.text(m.milestone().titleKey()))
            .orElseGet(() -> localization.text("workflow.complete"))).orElse(""));
        texts.onRefresh(table::refresh); table.setPrefHeight(220); table.setMinHeight(180);
        table.setPlaceholder(texts.label("session.empty", "secondary-text"));
        resume = button("session.resume", "session-resume", this::resumeSelected, true);
        Button open = button("session.open", "session-open", this::openSelected, false);
        Button delete = button("session.delete", "session-delete", this::deleteSelected, false);
        resume.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        open.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        // A reviewed session has nothing left to do: its action opens the saved review instead of resuming planning.
        table.getSelectionModel().selectedItemProperty().addListener((o, old, value) -> resumeLabel());
        resumeLabel();
        progress.setId("session-progress");
        search.setOnAction(e -> refresh()); dateFilter.setOnAction(e -> refresh()); statusFilter.setOnAction(e -> refresh());
        confirmation.getStyleClass().add("feedback-box"); editor.getStyleClass().add("feedback-box"); feedbackBox.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("session.details", "section-title"), feedback);
        controls.getChildren().addAll(new FlowPane(12, 10, search, statusFilter, dateFilter, button("gear.refresh", "session-refresh", this::refresh, true)),
            count, table, new FlowPane(12, 10, resume, open, button("session.create", "session-create", () -> guard(() -> openEditor(null)), false), delete),
            confirmation, editor);
        hide(editor); hide(confirmation); hide(feedbackBox); getChildren().addAll(controls, feedbackBox);
    }
    private void column(String key, double width, Function<PhotographySession, String> value) {
        TableColumn<PhotographySession, String> column = new TableColumn<>(); texts.text(column.textProperty(), key);
        column.setPrefWidth(width); column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue()))); table.getColumns().add(column);
    }
    /** Session progress per row: a thin bar and "57% · 4/7" (the bar never stands alone). */
    private void progressColumn() {
        TableColumn<PhotographySession, PhotographySession> column = new TableColumn<>(); texts.text(column.textProperty(), "session.progressColumn");
        column.setPrefWidth(130); column.setMinWidth(110);
        column.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        column.setCellFactory(c -> new TableCell<>() {
            private final ProgressBar bar = WorkflowGlyphs.bar(null);
            private final Label text = FeaturePane.label("", "session-progress-text");
            private final HBox box = new HBox(8, bar, text);
            {
                bar.setPrefWidth(46); bar.setMinWidth(30); bar.setMaxWidth(46); text.setWrapText(false); text.setMinWidth(Region.USE_PREF_SIZE);
                box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            }
            @Override protected void updateItem(PhotographySession item, boolean empty) {
                super.updateItem(item, empty);
                var shown = empty || item == null ? Optional.<com.photopilot.workflow.SessionWorkflow>empty() : workflowOf(item);
                if (shown.isEmpty()) { setGraphic(null); setAccessibleText(null); return; }
                var w = shown.get();
                bar.setProgress(w.percent() / 100.0);
                text.setText(localization.text("session.progressCell", w.percent(), w.completed(), w.applicable()));
                setAccessibleText(w.progress().render(localization));
                setGraphic(box);
            }
        });
        table.getColumns().add(column);
    }
    private Optional<com.photopilot.workflow.SessionWorkflow> workflowOf(PhotographySession session) {
        return workflow == null || session == null ? Optional.empty() : workflow.workflow(session.id());
    }
    /**
     * Joins the window's workflow model: progress columns and the open session's progress follow it, and opening or
     * creating a session makes it the chosen one. {@code resume} opens a session at its recommended page.
     */
    void useSessions(SessionWorkflowModel model, java.util.function.Consumer<UUID> resume) {
        workflow = model; resumeAction = java.util.Objects.requireNonNull(resume);
        model.listen(() -> { table.refresh(); resumeLabel(); renderProgress(); });
    }
    private void resumeSelected() {
        var selected = table.getSelectionModel().getSelectedItem();
        if (selected != null && !busy) resumeAction.accept(selected.id());
    }
    private void resumeLabel() {
        var selected = table.getSelectionModel().getSelectedItem();
        boolean reviewed = selected != null && selected.status().equals("REVIEWED");
        texts.text(resume.textProperty(), reviewed ? "session.viewReview" : "session.resume");
    }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }
    void activate(boolean active) { if (active && !busy) refresh(); }
    void refresh() {
        if (busy) return;
        // The list and its progress columns read the same stored state (another window or the CLI may have changed it).
        if (workflow != null) workflow.refresh();
        String query = search.getText(), status = statusFilter.getValue(), date = dateFilter.getText();
        run(() -> {
            try { return app.sessions().search(query, status, date.isBlank() ? null : SessionForm.date(date)); }
            catch (IllegalArgumentException error) { return Result.failure(error); }
        }, sessions -> {
            UUID selected = table.getSelectionModel().getSelectedItem() == null ? null : table.getSelectionModel().getSelectedItem().id();
            table.getItems().setAll(sessions);
            sessions.stream().filter(s -> s.id().equals(selected)).findFirst().ifPresent(s -> table.getSelectionModel().select(s));
            texts.text(count.textProperty(), "session.count", sessions.size());
        });
    }
    void open(UUID id) { open(id, false); }
    /** {@code lifecycle}: reveal the lifecycle action once this open succeeded (the intent travels with the request). */
    private void open(UUID id, boolean lifecycle) {
        if (busy) { pendingOpen = id; pendingLifecycle = lifecycle; return; }
        guard(() -> run(() -> app.sessions().open(id), session -> {
            // Opening a session is choosing it: the sidebar and the session pages follow.
            if (workflow != null) workflow.activate(session.id());
            openEditor(session);
            if (lifecycle) revealLifecycle();
        }));
    }
    /**
     * Shooting Completion: the session's editor at its lifecycle action. The action is never applied here; it gets the
     * keyboard focus only when the session is Planned (otherwise its own message explains why it is not allowed). An
     * unsaved edit of another session is kept: then only the page is shown.
     */
    void openForCompletion(UUID id) {
        if (id == null) return;
        if (editing != null && editing.id().equals(id) && editor.isVisible()) { revealLifecycle(); return; }
        if (dirty()) return;
        open(id, true);
    }
    private void revealLifecycle() {
        if (lifecycleRow == null || editing == null) return;
        reveal(lifecycleRow);
        // Recording the shoot freezes its records, so the action only takes the focus once the shoot day has come.
        if (editing.status().equals("PLANNED") && !editing.date().isAfter(java.time.LocalDate.now()))
            javafx.application.Platform.runLater(() -> { var apply = lifecycleRow.lookup("#session-transition"); if (apply != null) apply.requestFocus(); });
    }
    private void openSelected() { var selected = table.getSelectionModel().getSelectedItem(); if (selected != null) open(selected.id()); }
    private void openEditor(PhotographySession session) {
        editing = session; hide(confirmation); fields.getChildren().clear(); inputs.clear();
        field("title", session == null ? "" : session.title());
        field("date", session == null ? "" : session.date().toString());
        field("location", session == null ? "" : session.location().map(l -> l.label()).orElse(""));
        field("notes", session == null ? "" : session.notes());
        baseline = values();
        editor.getChildren().setAll(texts.label(session == null ? "session.create" : "session.edit", "empty-title"), fields);
        if (session != null) {
            Label metadata = FeaturePane.label("", "secondary-text");
            // PP-01: like the CLI, the scheduled date is told apart from the photos' camera-clock dates when both exist.
            texts.bind(metadata.textProperty(), () -> localization.text("session.summary", session.id(), session.title(), session.date(),
                localization.text("session.state." + session.status()))
                + com.photopilot.application.SessionDates.detail(session, localization).map(line -> "\n" + line).orElse("")
                + "\n" + localization.text("session.timestamps", session.createdAt(), session.updatedAt()));
            editor.getChildren().addAll(metadata, texts.label("session.links", "secondary-text", session.requirements().isPresent() ? 1 : 0,
                session.plans().size(), session.reviews().size(), session.statistics().isPresent() ? 1 : 0, session.savedIdeas().size()));
            for (var plan : session.plans()) {
                editor.getChildren().add(texts.label("session.plan", "secondary-text", plan.id(), plan.revision()));
                plan.loadout().ifPresent(loadout -> loadout.gear().forEach(gear -> editor.getChildren().add(
                    texts.label("session.gearSnapshot", "secondary-text", gear.sourceGearId(), gear.brand(), gear.model(), gear.selectedQuantity()))));
            }
        }
        if (session != null) editor.getChildren().addAll(progress, nextStep);
        renderProgress();
        editor.getChildren().add(new FlowPane(12, 10, button("session.save", "session-save", this::save, true),
            button("button.cancel", "session-cancel", () -> guard(() -> { hide(editor); editing = null; }), false)));
        lifecycleRow = null;
        if (session != null) {
            event.getItems().setAll(com.photopilot.service.SessionService.MANUAL_EVENTS); event.setValue(SessionEvent.SHOOT_COMPLETED); event.setId("session-event");
            texts.choices(event, e -> localization.text("session.event." + e.name())); texts.text(event.accessibleTextProperty(), "session.transition");
            lifecycleRow = new VBox(8, texts.label("workflow.milestone.SHOOT", "section-title"), texts.label("session.lifecycleHelp", "secondary-text"),
                new FlowPane(12, 10, event, button("session.transition", "session-transition", this::transition, false)));
            lifecycleRow.setId("session-lifecycle");
            editor.getChildren().add(lifecycleRow);
        }
        show(editor);
        reveal(editor);
    }
    /**
     * The open session's place in the workflow and its recommended next milestone, from the window's workflow model
     * (the same source as the sidebar); without the model, the step its stored records allow.
     */
    private void renderProgress() {
        if (editing == null) { nextStep.show(Optional.empty()); hide(progress); return; }
        var shown = workflowOf(editing);
        if (shown.isEmpty()) {
            hide(progress);
            nextStep.continueWith(com.photopilot.application.NextSteps.session(editing), editing.id());
            return;
        }
        var w = shown.get();
        texts.bind(progress.textProperty(), () -> WorkflowTexts.context(w, localization));
        show(progress);
        nextStep.continueWith(w.nextStep(), editing.id());
    }
    private void field(String key, String value) {
        TextInputControl input;
        if (key.equals("notes")) { TextArea area = new TextArea(value); area.setWrapText(true); area.setPrefRowCount(3); area.getStyleClass().add("session-notes"); input = area; }
        else input = new TextField(value);
        input.setId("session-field-" + key); texts.inputMenu(input); texts.text(input.accessibleTextProperty(), "session." + key);
        if (key.equals("date")) texts.text(input.promptTextProperty(), "session.datePrompt");
        Label caption = texts.label("session." + key, "section-title"); caption.setLabelFor(input);
        VBox field = new VBox(7, caption, input); field.setMinWidth(0);
        field.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth() < 560 ? Math.max(160, fields.getWidth()) : (fields.getWidth() - 16) / 2, fields.widthProperty()));
        fields.getChildren().add(field); inputs.put(key, input);
    }
    private Map<String, String> values() { Map<String, String> result = new LinkedHashMap<>(); inputs.forEach((k, v) -> result.put(k, v.getText())); return result; }
    boolean dirty() { return editor.isVisible() && !values().equals(baseline); }
    private void save() {
        var parsed = SessionForm.parse(values(), editing);
        if (!parsed.isSuccess()) { message(() -> String.join("; ", parsed.errors(localization))); return; }
        var d = parsed.value().orElseThrow(); PhotographySession expected = editing;
        run(() -> expected == null ? app.sessions().create(d.title(), d.date(), d.location(), d.notes())
            : app.sessions().update(expected, d.title(), d.date(), d.location(), d.notes()), saved -> {
                // A created (or edited) session is the one being worked on.
                if (workflow != null) workflow.activate(saved.id());
                openEditor(saved); message(() -> localization.text("session.saved")); refresh();
            });
    }
    private void transition() {
        if (dirty()) { message(() -> localization.text("session.saveFirst")); return; }
        PhotographySession expected = editing; SessionEvent selected = event.getValue();
        run(() -> app.sessions().recordEvent(expected, selected), saved -> { openEditor(saved); message(() -> localization.text("session.saved")); refresh(); });
    }
    private void deleteSelected() {
        PhotographySession selected = table.getSelectionModel().getSelectedItem(); if (selected == null) return;
        guard(() -> confirm("session.confirmDelete", () -> run(() -> app.sessions().delete(selected, true), removed -> {
            if (editing != null && editing.id().equals(selected.id())) { hide(editor); editing = null; }
            message(() -> localization.text("session.deleted")); refresh();
        }), selected.title()));
    }
    private void guard(Runnable action) {
        if (busy) return;
        if (dirty()) confirm("session.discardPrompt", action); else action.run();
    }
    private void confirm(String key, Runnable accepted, Object... args) {
        confirmation.getChildren().setAll(texts.label(key, "feedback-text", args), new FlowPane(12, 10,
            button("session.confirm", "session-confirm", () -> { hide(confirmation); accepted.run(); }, true),
            button("button.cancel", "session-confirm-cancel", () -> hide(confirmation), false)));
        show(confirmation);
        reveal(confirmation);
    }
    boolean preventClose(Runnable close) {
        if (busy) { message(() -> localization.text("session.wait")); return true; }
        if (!dirty()) return false;
        confirm("session.discardPrompt", close); return true;
    }
    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success) {
        if (busy) return;
        busy = true; controls.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            busy = false; controls.setDisable(false); var result = task.getValue();
            // Only this retained pane is updated. Navigation cannot redirect a result into another feature.
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else message(() -> String.join("; ", result.errors(localization)));
            drainOpen();
        });
        task.setOnFailed(e -> { if (disposed) return; busy = false; controls.setDisable(false); message(() -> localization.text("session.failed")); drainOpen(); });
        operations.execute(task);
    }
    private void drainOpen() { if (!busy && pendingOpen != null) { UUID id = pendingOpen; boolean lifecycle = pendingLifecycle; pendingOpen = null; pendingLifecycle = false; open(id, lifecycle); } }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); reveal(feedbackBox); }
    private void reveal(Node target) { reveal(this, target); }
    /** Scrolls the shared content viewport so feedback is visible; also used by other feature panes. */
    static void reveal(Node owner, Node target) {
        javafx.application.Platform.runLater(() -> {
            if (owner.getScene() == null || !owner.isVisible()) return;
            if (owner.getScene().lookup(".content-scroll") instanceof ScrollPane scroll) {
                scroll.applyCss(); scroll.layout();
                Node content = scroll.getContent();
                double top = content.sceneToLocal(target.localToScene(target.getBoundsInLocal())).getMinY();
                double overflow = content.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
                if (overflow > 0) scroll.setVvalue(Math.max(0, Math.min(1, (top - 12) / overflow)));
            }
        });
    }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    TableView<PhotographySession> table() { return table; }
}
