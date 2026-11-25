package com.photopilot.ui.gui;

import com.photopilot.application.OperationKind;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.progress.*;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * Shared presentation of one operation's {@link ExecutionProgressEvent}s inside a page's feedback card: title, running
 * clock, the stages actually entered (done, active, failed) and the declared ones still pending, retry and file counts,
 * the persistence state and the deterministic next step (with an Open button when it lives on another page). It renders
 * what the operation published and nothing else: the clock shows real elapsed time, an unknown duration is an
 * indeterminate indicator, and a bar appears only for counted work. No business rules live here.
 */
final class OperationProgressView extends VBox {
    private static final Duration TICK = Duration.millis(100);
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label title = FeaturePane.label("", "progress-title");
    private final Label clock = FeaturePane.label("", "progress-elapsed");
    private final Button cancel = new Button();
    private final VBox stages = new VBox(5);
    private final Label detail = FeaturePane.label("", "progress-detail");
    private final ProgressBar counted = new ProgressBar();
    private final Label persistence = FeaturePane.label("", "progress-persistence");
    private final Label next = FeaturePane.label("", "progress-next");
    private final Button open = new Button();
    private final HBox nextRow = new HBox(10, next, open);
    private final Timeline ticker = new Timeline(new KeyFrame(TICK, event -> tick()));
    private OperationKind kind;
    private OperationContext context;
    private ExecutionProgressEvent last;
    /** The session a completed operation's continuation belongs to (its Continue opens the page with that session). */
    private java.util.UUID continuation;

    OperationProgressView(LocalizationService localization) {
        super(8);
        this.localization = localization;
        texts = new FxLocalizer(this, localization);
        getStyleClass().add("operation-progress");
        setId("operation-progress");
        spinner.getStyleClass().add("progress-spinner"); spinner.setPrefSize(16, 16); spinner.setMaxSize(16, 16); spinner.setMinSize(16, 16);
        spinner.setFocusTraversable(false);
        title.setId("progress-title"); clock.setId("progress-elapsed"); stages.setId("progress-stages"); detail.setId("progress-detail");
        persistence.setId("progress-persistence"); next.setId("progress-next");
        clock.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setId("progress-cancel"); cancel.getStyleClass().addAll("secondary-button", "progress-cancel");
        texts.text(cancel.textProperty(), "progress.cancel");
        cancel.setOnAction(event -> { if (context != null && context.cancel()) cancel.setDisable(true); });
        open.setId("progress-open"); open.getStyleClass().add("secondary-button"); open.setMinWidth(Region.USE_PREF_SIZE);
        nextRow.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(next, Priority.ALWAYS); next.setMaxWidth(Double.MAX_VALUE);
        counted.setMaxWidth(Double.MAX_VALUE); counted.getStyleClass().add("progress-count");
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        var header = new HBox(10, spinner, title, space, clock, cancel); header.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(header, stages, detail, counted, persistence, nextRow);
        ticker.setCycleCount(Timeline.INDEFINITE);
        hide(this);
        texts.onRefresh(() -> { if (last != null) render(last); });
    }

    /** Starts showing {@code context} at once (same UI pulse as the user's action), with its declared stages pending. */
    void track(OperationKind operation, OperationContext operationContext) {
        kind = operation; context = operationContext; last = null; continuation = null;
        render(operationContext.snapshot());
        show(this);
        if (getParent() != null) { getParent().setVisible(true); getParent().setManaged(true); }
        ticker.playFromStart();
    }

    /** The observer this view registers on its operation: events from any thread are rendered on the FX thread. */
    ExecutionProgressListener listener(OperationContext owner) {
        return event -> {
            if (Platform.isFxApplicationThread()) accept(owner, event);
            else Platform.runLater(() -> accept(owner, event));
        };
    }
    private void accept(OperationContext owner, ExecutionProgressEvent event) {
        if (owner != context) return;                                  // an older operation of this page
        if (last != null && last.status().finished()) return;          // the final state is never overwritten
        render(event);
    }

    /** Set before the operation finishes: its continuation opens the next page with {@code session} (null: as it is). */
    void continueFor(java.util.UUID session) { continuation = session; }
    /** Hides the view (the page cleared its results or switched session). */
    void clear() { ticker.stop(); kind = null; context = null; last = null; hide(this); }
    void dispose() { ticker.stop(); texts.close(); }

    ExecutionProgressEvent lastEvent() { return last; }
    boolean running() { return last != null && !last.status().finished(); }

    private void tick() {
        if (context == null) { ticker.stop(); return; }
        clock.setText(localization.text("progress.elapsed", seconds(context.elapsed())));
        if (context.finished()) ticker.stop();
    }

    private void render(ExecutionProgressEvent event) {
        last = event;
        boolean finished = event.status().finished();
        if (finished) ticker.stop();
        title.setText(kind == null ? "" : localization.text(kind.titleKey()) + (finished ? " · " + localization.text("progress.status." + event.status()) : ""));
        title.getStyleClass().removeAll("progress-failed", "progress-done");
        if (event.status() == OperationStatus.FAILED) title.getStyleClass().add("progress-failed");
        else if (finished) title.getStyleClass().add("progress-done");
        spinner.setVisible(!finished); spinner.setManaged(!finished);
        clock.setText(localization.text("progress.elapsed", seconds(event.elapsed())));
        cancel.setVisible(!finished && (event.cancellable() || event.cancelling())); cancel.setManaged(cancel.isVisible());
        cancel.setDisable(!event.cancellable());
        if (event.cancelling()) cancel.setText(localization.text("progress.cancelling"));
        else texts.text(cancel.textProperty(), "progress.cancel");

        stages.getChildren().clear();
        for (var stage : event.stages()) stages.getChildren().add(row(stage.label().render(localization), stage.state().name().toLowerCase(Locale.ROOT)));
        if (!finished) for (String key : event.pending()) stages.getChildren().add(row(com.photopilot.progress.OperationStage.queued(key).render(localization), "pending"));

        String line = "";
        if (!finished && event.maxAttempts() > 1 && event.attempt() > 1) line = localization.text("progress.attempt", event.attempt(), event.maxAttempts());
        else if (!finished && event.upcoming().isPresent() && event.current().isPresent())
            line = localization.text("progress.then", com.photopilot.progress.OperationStage.queued(event.upcoming().get()).render(localization));
        if (event.measurable()) line = (line.isEmpty() ? "" : line + "  ·  ") + localization.text("progress.files", event.done(), event.total());
        detail.setText(line); show(detail, !line.isEmpty());
        counted.setProgress(event.measurable() ? (double) event.done() / event.total() : 0);
        show(counted, event.measurable() && !finished);

        String saved = switch (event.persistence()) {
            case NOT_APPLICABLE -> "";
            default -> localization.text("progress.persistence." + event.persistence());
        };
        if (event.status() == OperationStatus.CANCELLED) saved = "";
        persistence.setText(saved); show(persistence, !saved.isEmpty());
        persistence.getStyleClass().removeAll("progress-saved", "progress-unsaved");
        if (event.persistence() == PersistenceStatus.SAVED) persistence.getStyleClass().add("progress-saved");
        else if (event.persistence() != PersistenceStatus.SAVING) persistence.getStyleClass().add("progress-unsaved");

        var step = finished ? event.next() : java.util.Optional.<NextStep>empty();
        next.setText(step.map(s -> localization.text("progress.next", s.message().render(localization))).orElse(""));
        show(nextRow, step.isPresent());
        var target = step.flatMap(NextStep::target);
        show(open, target.isPresent());
        // After a completed operation the step continues the workflow; after a failure or cancellation it opens the page
        // where the alternative or the prerequisite lives.
        boolean completed = event.status() == OperationStatus.COMPLETED;
        String action = completed ? "progress.continue" : "progress.open";
        var forSession = completed ? continuation : null;
        target.ifPresent(page -> {
            open.setText(localization.text(action, localization.text(NavigationRequest.titleKey(page))));
            open.setOnAction(event1 -> open.fireEvent(new NavigationRequest(page, forSession)));
        });
        setAccessibleText(localization.text("progress.accessible", title.getText(), event.current().map(s -> s.label().render(localization)).orElse(detail.getText())));
    }

    private HBox row(String text, String state) {
        var label = FeaturePane.label(text, "progress-stage"); label.getStyleClass().add("progress-stage-" + state);
        var row = new HBox(8, icon(state), label); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("progress-row");
        return row;
    }
    /** 12 px stage marks drawn as shapes (no font glyphs), coloured by the theme tokens. */
    private static Node icon(String state) {
        Node mark = switch (state) {
            case "completed" -> path("M2 6.5 L5 9.5 L10.5 3", "progress-icon-done");
            case "failed" -> path("M3 3 L9 9 M9 3 L3 9", "progress-icon-failed");
            case "cancelled" -> path("M3 6 L9 6", "progress-icon-cancelled");
            case "active" -> circle(4, "progress-icon-active");
            default -> circle(3.5, "progress-icon-pending");
        };
        var box = new StackPane(mark); box.setMinSize(12, 12); box.setPrefSize(12, 12); box.setMaxSize(12, 12);
        return box;
    }
    private static SVGPath path(String content, String style) { var path = new SVGPath(); path.setContent(content); path.getStyleClass().add(style); return path; }
    private static Circle circle(double radius, String style) { var circle = new Circle(radius); circle.getStyleClass().add(style); return circle; }

    static String seconds(java.time.Duration elapsed) {
        return String.format(Locale.ROOT, "%.1f", elapsed.toMillis() / 1000.0);
    }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
    private static void show(Node node, boolean visible) { node.setVisible(visible); node.setManaged(visible); }
}
