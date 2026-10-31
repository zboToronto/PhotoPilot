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
    private UUID pendingOpen;

    SessionPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(20); this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization); getStyleClass().add("inventory-content");
        search.setId("session-search"); search.setPrefWidth(240); texts.text(search.promptTextProperty(), "session.search");
        texts.text(search.accessibleTextProperty(), "session.search"); texts.inputMenu(search);
        dateFilter.setId("session-date-filter"); dateFilter.setPrefWidth(175); texts.text(dateFilter.promptTextProperty(), "session.dateFilter");
        texts.text(dateFilter.accessibleTextProperty(), "session.dateFilter"); texts.inputMenu(dateFilter);
        statusFilter.getItems().addAll("", "DRAFT", "PLANNED", "COMPLETED", "REVIEWED"); statusFilter.setValue("");
        statusFilter.setId("session-status-filter"); texts.text(statusFilter.accessibleTextProperty(), "session.statusFilter");
        texts.choices(statusFilter, value -> localization.text(value.isEmpty() ? "session.allStates" : "session.state." + value));
        table.setId("session-table"); texts.text(table.accessibleTextProperty(), "session.history");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column("session.titleColumn", 220, PhotographySession::title); column("session.dateColumn", 115, s -> s.date().toString());
        column("session.status", 115, s -> localization.text("session.state." + s.status()));
        texts.onRefresh(table::refresh); table.setPrefHeight(220); table.setMinHeight(180);
        table.setPlaceholder(texts.label("session.empty", "secondary-text"));
        Button open = button("session.open", "session-open", this::openSelected, false);
        Button delete = button("session.delete", "session-delete", this::deleteSelected, false);
        open.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        search.setOnAction(e -> refresh()); dateFilter.setOnAction(e -> refresh()); statusFilter.setOnAction(e -> refresh());
        confirmation.getStyleClass().add("feedback-box"); editor.getStyleClass().add("feedback-box"); feedbackBox.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("session.details", "section-title"), feedback);
        controls.getChildren().addAll(new FlowPane(12, 10, search, statusFilter, dateFilter, button("gear.refresh", "session-refresh", this::refresh, true)),
            count, table, new FlowPane(12, 10, button("session.create", "session-create", () -> guard(() -> openEditor(null)), true), open, delete),
            confirmation, editor);
        hide(editor); hide(confirmation); hide(feedbackBox); getChildren().addAll(controls, feedbackBox);
    }
    private void column(String key, double width, Function<PhotographySession, String> value) {
        TableColumn<PhotographySession, String> column = new TableColumn<>(); texts.text(column.textProperty(), key);
        column.setPrefWidth(width); column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue()))); table.getColumns().add(column);
    }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }
    void activate(boolean active) { if (active && !busy) refresh(); }
    void refresh() {
        if (busy) return;
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
    void open(UUID id) {
        if (busy) { pendingOpen = id; return; }
        guard(() -> run(() -> app.sessions().open(id), this::openEditor));
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
            texts.bind(metadata.textProperty(), () -> localization.text("session.summary", session.id(), session.title(), session.date(),
                localization.text("session.state." + session.status())) + "\n" + localization.text("session.timestamps", session.createdAt(), session.updatedAt()));
            editor.getChildren().addAll(metadata, texts.label("session.links", "secondary-text", session.requirements().isPresent() ? 1 : 0,
                session.plans().size(), session.reviews().size(), session.statistics().isPresent() ? 1 : 0, session.savedIdeas().size()));
            for (var plan : session.plans()) {
                editor.getChildren().add(texts.label("session.plan", "secondary-text", plan.id(), plan.revision()));
                plan.loadout().ifPresent(loadout -> loadout.gear().forEach(gear -> editor.getChildren().add(
                    texts.label("session.gearSnapshot", "secondary-text", gear.sourceGearId(), gear.brand(), gear.model(), gear.selectedQuantity()))));
            }
        }
        editor.getChildren().add(new FlowPane(12, 10, button("session.save", "session-save", this::save, true),
            button("button.cancel", "session-cancel", () -> guard(() -> { hide(editor); editing = null; }), false)));
        if (session != null) {
            event.getItems().setAll(SessionEvent.values()); event.setValue(SessionEvent.SHOOT_COMPLETED); event.setId("session-event");
            texts.choices(event, e -> localization.text("session.event." + e.name())); texts.text(event.accessibleTextProperty(), "session.transition");
            editor.getChildren().addAll(texts.label("session.lifecycleHelp", "secondary-text"),
                new FlowPane(12, 10, event, button("session.transition", "session-transition", this::transition, false)));
        }
        show(editor);
        reveal(editor);
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
                openEditor(saved); message(() -> localization.text("session.saved")); refresh();
            });
    }
    private void transition() {
        if (dirty()) { message(() -> localization.text("session.saveFirst")); return; }
        PhotographySession expected = editing; SessionEvent selected = event.getValue();
        run(() -> app.sessions().transition(expected, selected), saved -> { openEditor(saved); message(() -> localization.text("session.saved")); refresh(); });
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
            busy = false; controls.setDisable(false); var result = task.getValue();
            // Only this retained pane is updated. Navigation cannot redirect a result into another feature.
            if (result.isSuccess()) success.accept(result.value().orElseThrow());
            else message(() -> String.join("; ", result.errors(localization)));
            drainOpen();
        });
        task.setOnFailed(e -> { busy = false; controls.setDisable(false); message(() -> localization.text("session.failed")); drainOpen(); });
        operations.execute(task);
    }
    private void drainOpen() { if (!busy && pendingOpen != null) { UUID id = pendingOpen; pendingOpen = null; open(id); } }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); reveal(feedbackBox); }
    private void reveal(Node target) {
        javafx.application.Platform.runLater(() -> {
            if (getScene() == null || !isVisible()) return;
            if (getScene().lookup(".content-scroll") instanceof ScrollPane scroll) {
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
