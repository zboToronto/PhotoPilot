package com.photopilot.ui.gui;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.application.ShutterCountPresentation;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CameraModel.Brand;
import com.photopilot.model.ShutterCountResult;
import com.photopilot.model.ShutterCountResult.CounterReading;
import com.photopilot.service.ShutterCountService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

/**
 * Standalone shutter count checker under Photography Tools. The user chooses or drops
 * a photo (a {@link FileDropZone}); the shared ShutterCountService identifies the camera from the photo's metadata and
 * reads its counter with the bundled ExifTool on the window's worker queue, never on the JavaFX thread. A new check
 * clears the previous result before it starts, so an earlier photo's count is never shown for another file. No photo is
 * modified or stored. The "supported models" link opens a lookup-only list of the camera catalog
 * ({@link SupportedModelsDialog}); nothing is selected for the check.
 */
final class ShutterCountPane extends VBox {
    private enum Phase { IDLE, RUNNING, DONE, FAILED }
    /** Shown wherever a result has no value. */
    private static final String NONE = "—";
    /** Below this width the three result areas stack in one column. */
    private static final double THREE_COLUMNS = 720;

    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final Button choose;
    private final FileDropZone importArea;
    private final GridPane results = new GridPane();
    private final Region file, count, summary;
    private final Label fileName = FeaturePane.label("", "card-title"), fileDetails = FeaturePane.label("", "secondary-text");
    private final Label fileStatus = FeaturePane.label("", "shutter-status");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label countValue = FeaturePane.label("", "shutter-count-value"), countCaption = FeaturePane.label("", "secondary-text");
    private final Map<String, Label> meta = new LinkedHashMap<>();
    private final VBox messages = new VBox(6);
    private Boolean wide;

    private Phase phase = Phase.IDLE;
    private long generation, checks;
    private String pendingName = "";
    private long pendingSize = -1;
    private ShutterCountResult result;
    private List<LocalizedMessage> errors = List.of();
    private File lastFolder;

    ShutterCountPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization);
        setId("shutter-count");
        getStyleClass().addAll("surface-panel", "shutter-content");
        setMinWidth(0);

        // Header: the same eyebrow, title and description treatment as the feature card above, with a link after the
        // description to the lookup-only list of supported models. The camera itself always comes from the photo.
        Label description = texts.label("shutter.description", "description"); description.setMinWidth(0);
        Hyperlink models = new Hyperlink(); models.setId("shutter-models-link"); models.getStyleClass().add("shutter-link");
        texts.text(models.textProperty(), "shutter.models.link");
        models.setMinWidth(Region.USE_PREF_SIZE);
        models.setOnAction(event -> {
            models.setVisited(false);
            SupportedModelsDialog.show(getScene() == null ? null : getScene().getWindow(), app.shutterCount().catalog(), localization);
        });
        HBox about = new HBox(8, description, models); about.setAlignment(Pos.BASELINE_LEFT); about.setId("shutter-about");
        VBox heading = new VBox(12, texts.label("shutter.eyebrow", "eyebrow"), texts.label("shutter.title", "feature-title"), about);
        heading.getStyleClass().add("feature-heading");

        // Photo import: the file chooser and drag and drop both go through the shared drop zone's validation (one photo,
        // a file, a supported extension) to check(), the single import handler.
        Label importTitle = texts.label("shutter.import.title", "shutter-import-title");
        HBox importHeading = new HBox(10, AppIcons.slot(AppIcons.IMAGE, "shutter-icon", 32), importTitle);
        importHeading.setAlignment(Pos.CENTER);
        choose = texts.button("shutter.import.choose"); choose.setId("shutter-choose");
        choose.getStyleClass().add("primary-button");
        choose.setOnAction(event -> chooseFile());
        texts.text(choose.accessibleHelpProperty(), "shutter.import.privacy");
        Label formats = centred(texts.label("shutter.import.formats", "secondary-text"));
        Label privacy = centred(texts.label("shutter.import.privacy", "shutter-caption")); privacy.setId("shutter-privacy");
        VBox importContent = new VBox(8, importHeading, formats, choose, privacy);
        importContent.setAlignment(Pos.CENTER);
        importArea = new FileDropZone(texts, importContent, new FileDropZone.Options()
            .extensions(ShutterCountService.EXTENSIONS)
            .release(LocalizedMessage.of("shutter.import.release"))
            .busy(LocalizedMessage.of("shutter.import.busy"))
            .message(FileDropZone.Problem.MULTIPLE, files -> LocalizedMessage.of("shutter.import.oneAtATime", files))
            .message(FileDropZone.Problem.EXTENSION, name -> LocalizedMessage.of("shutter.error.unsupportedFormat", name)),
            photos -> check(photos.getFirst()));
        importArea.setId("shutter-import");

        // Results: file, count and metadata areas.
        file = fileArea(); count = countArea(); summary = summaryArea();
        results.setId("shutter-results"); results.setHgap(12); results.setVgap(12);
        results.widthProperty().addListener((property, was, now) -> arrange(now.doubleValue() >= THREE_COLUMNS));
        arrange(true);
        messages.setId("shutter-messages"); messages.getStyleClass().add("shutter-messages");
        VBox resultSection = new VBox(10, texts.label("shutter.results", "group-title"), results, messages);

        // Footer: what the count means depends on the camera. (Where the model list comes from is shown with the list.)
        Label note = texts.label("shutter.note", "secondary-text"); note.setMinWidth(0); note.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(note, Priority.ALWAYS);
        HBox footer = new HBox(10, AppIcons.slot(AppIcons.INFO, "shutter-muted-icon", 18), note);
        footer.setAlignment(Pos.CENTER_LEFT); footer.setId("shutter-note"); footer.getStyleClass().add("shutter-note");

        VBox content = new VBox(16, importArea, resultSection, footer);
        getChildren().addAll(heading, content);
        texts.onRefresh(this::render);
    }

    private Region fileArea() {
        StackPane icon = new StackPane(AppIcons.outline(AppIcons.DOCUMENT, "shutter-muted-icon"));
        icon.getStyleClass().add("shutter-file-icon"); icon.setMinSize(40, 40); icon.setPrefSize(40, 40); icon.setMaxSize(40, 40);
        fileName.setId("shutter-file-name"); fileDetails.setId("shutter-file-details");
        VBox text = new VBox(3, fileName, fileDetails); text.setMinWidth(0); text.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(text, Priority.ALWAYS);
        fileStatus.setId("shutter-file-status"); fileStatus.setMinWidth(Region.USE_PREF_SIZE);
        progress.setId("shutter-progress"); progress.setPrefSize(18, 18); progress.setMaxSize(18, 18); progress.getStyleClass().add("shutter-progress");
        HBox area = new HBox(12, icon, text, progress, fileStatus);
        area.setAlignment(Pos.CENTER_LEFT); area.setId("shutter-file"); area.getStyleClass().add("shutter-tile");
        return area;
    }

    private Region countArea() {
        Label caption = texts.label("shutter.count", "section-title");
        StackPane help = AppIcons.slot(AppIcons.INFO, "shutter-muted-icon", 16);
        Tooltip explanation = new Tooltip(); texts.text(explanation.textProperty(), "shutter.count.help");
        explanation.setWrapText(true); explanation.setMaxWidth(320); Tooltip.install(help, explanation);
        HBox title = new HBox(6, caption, help); title.setAlignment(Pos.CENTER_LEFT);
        countValue.setId("shutter-count-value"); countCaption.setId("shutter-count-caption");
        VBox area = new VBox(6, title, countValue, countCaption);
        area.setAlignment(Pos.CENTER_LEFT); area.setId("shutter-count-area"); area.getStyleClass().addAll("shutter-tile", "shutter-count-tile");
        return area;
    }

    private Region summaryArea() {
        GridPane area = new GridPane(); area.setHgap(16); area.setVgap(6);
        int row = 0;
        for (String key : List.of("shutter.meta.brand", "shutter.meta.model", "shutter.meta.counter", "shutter.meta.source", "shutter.meta.date", "shutter.meta.format")) {
            Label caption = texts.label(key, "secondary-text");
            String field = key.substring(key.lastIndexOf('.') + 1);
            Label value = FeaturePane.label(NONE, "shutter-value"); value.setId("shutter-meta-" + field); value.setMinWidth(0);
            meta.put(field, value);
            area.addRow(row++, caption, value);
        }
        ColumnConstraints captions = new ColumnConstraints(); captions.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints(); values.setHgrow(Priority.ALWAYS); values.setMinWidth(0);
        area.getColumnConstraints().setAll(captions, values);
        area.setAlignment(Pos.CENTER_LEFT); area.setId("shutter-summary"); area.getStyleClass().add("shutter-tile");
        return area;
    }

    /** Three columns side by side when there is room; otherwise one column, in the same order. */
    private void arrange(boolean threeColumns) {
        if (wide != null && wide == threeColumns) return;
        wide = threeColumns;
        results.getChildren().clear();
        if (threeColumns) {
            results.addRow(0, file, count, summary);
            results.getColumnConstraints().setAll(column(38), column(24), column(38));
        } else {
            results.addColumn(0, file, count, summary);
            results.getColumnConstraints().setAll(column(100));
        }
        for (Node area : List.of(file, count, summary)) { GridPane.setValignment(area, VPos.TOP); GridPane.setHalignment(area, HPos.LEFT); }
    }
    private static ColumnConstraints column(double percent) {
        ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(percent); column.setFillWidth(true); return column;
    }
    private static Label centred(Label label) { label.setAlignment(Pos.CENTER); label.setStyle("-fx-text-alignment: center;"); return label; }

    // ---- Checking a photo ----------------------------------------------------------------------------------------

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(localization.text("shutter.chooserTitle"));
        List<String> patterns = new ArrayList<>();
        for (String extension : List.of("jpg", "jpeg", "arw", "nef", "dng")) { patterns.add("*." + extension); patterns.add("*." + extension.toUpperCase(Locale.ROOT)); }
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter(localization.text("shutter.chooserFilter"), patterns),
            new FileChooser.ExtensionFilter(localization.text("shutter.chooserAll"), "*.*"));
        if (lastFolder != null && lastFolder.isDirectory()) chooser.setInitialDirectory(lastFolder);
        File chosen = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) { lastFolder = chosen.getParentFile(); importArea.submit(List.of(chosen.toPath())); }
    }

    /**
     * The import handler for both the file chooser and drag and drop, after the drop zone's validation: starts a check
     * with the shared service. The previous result is cleared first.
     */
    void check(Path photo) {
        long request = ++generation; checks++;
        phase = Phase.RUNNING; result = null; errors = List.of();
        pendingName = photo.getFileName() == null ? photo.toString() : photo.getFileName().toString();
        try { pendingSize = Files.isRegularFile(photo) ? Files.size(photo) : -1; } catch (Exception unreadable) { pendingSize = -1; }
        render();
        Task<Result<ShutterCountResult>> task = new Task<>() {
            @Override protected Result<ShutterCountResult> call() { return app.shutterCount().check(photo); }
        };
        task.setOnSucceeded(event -> {
            if (request != generation) return;
            var outcome = task.getValue();
            if (outcome.isSuccess()) { phase = Phase.DONE; result = outcome.value().orElseThrow(); }
            else { phase = Phase.FAILED; errors = outcome.messages(); }
            render();
        });
        task.setOnFailed(event -> {
            if (request != generation) return;
            phase = Phase.FAILED; errors = List.of(LocalizedMessage.of("shutter.error.unexpected")); render();
        });
        // The first check starts (and from a JAR extracts) the bundled ExifTool, which can take seconds: it runs beside
        // the window's local lane, so other pages stay responsive. ExifTool itself serves one request at a time.
        UiOperations.background(operations, task);
    }

    private void render() {
        boolean running = phase == Phase.RUNNING;
        choose.setDisable(running); importArea.setBusy(running);
        progress.setVisible(running); progress.setManaged(running);
        fileStatus.getStyleClass().removeAll("shutter-status-running", "shutter-status-success", "shutter-status-failure");
        meta.values().forEach(label -> label.setText(NONE));
        countValue.setText(NONE);
        switch (phase) {
            case IDLE -> {
                fileName.setText(localization.text("shutter.file.none"));
                fileDetails.setText(localization.text("shutter.file.details", NONE, NONE));
                fileStatus.setText(localization.text("shutter.file.waiting"));
                countCaption.setText(localization.text("shutter.count.empty"));
            }
            case RUNNING, FAILED -> {
                fileName.setText(pendingName);
                fileDetails.setText(localization.text("shutter.file.details", pendingSize < 0 ? NONE : ShutterCountPresentation.size(pendingSize, localization), extension(pendingName)));
                fileStatus.setText(localization.text(running ? "shutter.file.reading" : "shutter.file.failed"));
                fileStatus.getStyleClass().add(running ? "shutter-status-running" : "shutter-status-failure");
                countCaption.setText(localization.text(running ? "shutter.count.reading" : "shutter.count.failed"));
            }
            case DONE -> {
                fileName.setText(result.fileName());
                fileDetails.setText(localization.text("shutter.file.details", ShutterCountPresentation.size(result.fileSize(), localization), result.fileType()));
                boolean success = result.status() == ShutterCountResult.Status.SUCCESS;
                fileStatus.setText(ShutterCountPresentation.status(result.status(), localization));
                fileStatus.getStyleClass().add(success ? "shutter-status-success" : "shutter-status-failure");
                result.counter().ifPresent(counter -> countValue.setText(ShutterCountPresentation.count(counter.value(), localization)));
                countCaption.setText(result.counter().map(counter -> ShutterCountPresentation.kind(counter.kind(), localization))
                    .orElse(localization.text("shutter.count.unavailable")));
                result.brand().map(Brand::label).or(result::make).ifPresent(meta.get("brand")::setText);
                String detected = ShutterCountPresentation.model(result);
                if (!detected.isEmpty()) meta.get("model").setText(detected);
                result.counter().ifPresent(counter -> {
                    meta.get("counter").setText(ShutterCountPresentation.kind(counter.kind(), localization));
                    meta.get("source").setText(counter.source());
                });
                result.captureDate().map(ShutterCountPresentation::date).ifPresent(meta.get("date")::setText);
                meta.get("format").setText(result.fileType());
            }
        }
        renderMessages();
    }

    private void renderMessages() {
        messages.getChildren().clear();
        if (phase == Phase.FAILED) errors.forEach(error -> message(error.render(localization), "shutter-message-error"));
        if (phase == Phase.DONE) {
            result.reason().ifPresent(reason -> message(reason.render(localization), "shutter-message-warning"));
            result.counter().ifPresent(counter -> message(localization.text("shutter.message.meaning",
                ShutterCountPresentation.meaning(counter.kind(), localization), counter.original()), "shutter-message"));
            // What the catalog knows about the camera identified from the photo.
            result.catalogModel().ifPresent(found -> message(localization.text("shutter.message.support",
                found.label(), ShutterCountPresentation.support(found, localization, result.counter().isEmpty())), "shutter-message"));
            for (CounterReading other : result.otherCounters()) message(ShutterCountPresentation.other(other, localization), "shutter-message");
            for (String note : ShutterCountPresentation.notes(result, localization)) message(note, "shutter-message");
        }
        boolean shown = !messages.getChildren().isEmpty();
        messages.setVisible(shown); messages.setManaged(shown);
    }
    private void message(String text, String style) {
        Label line = FeaturePane.label(text, style); line.setMaxWidth(Double.MAX_VALUE); messages.getChildren().add(line);
    }
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? NONE : name.substring(dot + 1).toUpperCase(Locale.ROOT);
    }

    void dispose() { generation++; texts.close(); }

    // Package-private test observations; use the real controls for actions.
    boolean busy() { return phase == Phase.RUNNING; }
    /** Checks started so far, by the chooser or a drop. */
    long checks() { return checks; }
    boolean threeColumns() { return Boolean.TRUE.equals(wide); }
    List<String> messageTexts() { return messages.getChildren().stream().map(node -> ((Label) node).getText()).toList(); }
}
