package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.ui.privacy.ImageReview;
import com.photopilot.ui.privacy.SanitizedImage;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

/**
 * F04 content in the existing feature surface: select -> local privacy review (drag to black out or crop) -> sanitized
 * copy shown as it will be sent -> explicit approval -> Gemini recognition and external verification -> review,
 * candidate choice and correction -> duplicate decision -> explicit import through F05. Reuses the shared cards,
 * buttons, field layout, feedback and background Task pattern; business rules stay in the services.
 */
final class GearImportPane extends VBox {
    private static final double PREVIEW_WIDTH = 620;
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final ImagePrivacyBoundary privacy;
    private final Runnable openInventory;
    private final ComboBox<ImageReview.Kind> kind = new ComboBox<>();
    private final TextField path = new TextField();
    private final VBox controls = new VBox(16), selectBox = new VBox(12), privacyBox = new VBox(12), approveBox = new VBox(12),
        reviewBox = new VBox(14), candidatesBox = new VBox(10), duplicatesBox = new VBox(8), resultBox = new VBox(10), feedbackBox = new VBox(8);
    private final Label loaded = FeaturePane.label("", "secondary-text"), screenshotWarning = FeaturePane.label("", "feedback-text"),
        regions = FeaturePane.label("", "secondary-text"), sanitizedInfo = FeaturePane.label("", "secondary-text"),
        summary = FeaturePane.label("", "secondary-text"), conflicts = FeaturePane.label("", "feedback-text"),
        notices = FeaturePane.label("", "feedback-text"), evidence = FeaturePane.label("", "secondary-text"),
        validation = FeaturePane.label("", "feedback-text"), result = FeaturePane.label("", "secondary-text"), feedback = FeaturePane.label("", "feedback-text");
    private final ImageView original = new ImageView(), sanitized = new ImageView();
    private final Pane overlay = new Pane();
    private final RadioButton redactMode = new RadioButton(), cropMode = new RadioButton();
    private final CheckBox approve = new CheckBox(), acceptUnverified = new CheckBox();
    private final ComboBox<EquipmentType> type = new ComboBox<>();
    private final FlowPane fields = new FlowPane(16, 14);
    private final Map<String, TextField> inputs = new LinkedHashMap<>();
    private final ToggleGroup duplicateChoice = new ToggleGroup();
    private ImageReview review;
    private Path copy;
    private GearImportDraft draft;
    private List<Equipment> duplicates = List.of();
    private double scale = 1;
    private Point2D dragStart;
    private Rectangle dragShape;
    private boolean busy, disposed;

    GearImportPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization, ImagePrivacyBoundary privacy, Runnable openInventory) {
        super(20); this.app = app; this.operations = operations; this.localization = localization; this.privacy = privacy; this.openInventory = openInventory;
        texts = new FxLocalizer(this, localization); getStyleClass().addAll("inventory-content", "import-content");
        kind.getItems().setAll(ImageReview.Kind.values()); kind.setValue(ImageReview.Kind.PHOTO); kind.setId("import-kind");
        texts.choices(kind, value -> localization.text("import.kind." + value.name())); texts.text(kind.accessibleTextProperty(), "import.kind");
        path.setId("import-path"); texts.inputMenu(path); texts.text(path.promptTextProperty(), "import.pathPrompt"); texts.text(path.accessibleTextProperty(), "import.path");
        Button browse = button("import.browse", "import-browse", this::browse, false), load = button("import.load", "import-load", this::load, true);
        Label kindCaption = texts.label("import.kind", "section-title"); kindCaption.setLabelFor(kind);
        Label pathCaption = texts.label("import.path", "section-title"); pathCaption.setLabelFor(path);
        HBox.setHgrow(path, Priority.ALWAYS); browse.setMinWidth(Region.USE_PREF_SIZE);
        selectBox.getChildren().addAll(texts.label("import.step.select", "empty-title"), texts.label("import.selectHint", "secondary-text"),
            new VBox(7, kindCaption, kind), new VBox(7, pathCaption, new HBox(12, path, browse)), new FlowPane(12, 10, load),
            texts.label("import.sendsNote", "secondary-text"));
        // Privacy review: the overlay records drags in original-image pixels; the copy, not the overlay, removes pixels.
        var modes = new ToggleGroup(); redactMode.setToggleGroup(modes); cropMode.setToggleGroup(modes); redactMode.setSelected(true);
        redactMode.setId("import-mode-redact"); cropMode.setId("import-mode-crop");
        texts.text(redactMode.textProperty(), "import.mode.redact"); texts.text(cropMode.textProperty(), "import.mode.crop");
        original.setPreserveRatio(true); original.setId("import-original");
        overlay.setId("import-overlay"); overlay.setPickOnBounds(true);
        overlay.setOnMousePressed(event -> { dragStart = new Point2D(event.getX(), event.getY()); dragShape = shape(event.getX(), event.getY(), 0, 0, redactMode.isSelected()); overlay.getChildren().add(dragShape); });
        overlay.setOnMouseDragged(event -> { if (dragShape != null) resize(dragShape, dragStart, event.getX(), event.getY()); });
        overlay.setOnMouseReleased(event -> {
            if (dragStart == null) return;
            double x = Math.min(dragStart.getX(), event.getX()), y = Math.min(dragStart.getY(), event.getY());
            region(redactMode.isSelected(), (int) Math.floor(x * scale), (int) Math.floor(y * scale),
                (int) Math.ceil(Math.abs(event.getX() - dragStart.getX()) * scale), (int) Math.ceil(Math.abs(event.getY() - dragStart.getY()) * scale));
            dragStart = null; dragShape = null;
        });
        var stack = new StackPane(original, overlay); stack.getStyleClass().add("import-preview"); stack.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane.setAlignment(overlay, javafx.geometry.Pos.TOP_LEFT);
        privacyBox.getChildren().addAll(texts.label("import.step.privacy", "empty-title"), loaded, screenshotWarning,
            texts.label("import.privacyHint", "secondary-text"), new FlowPane(16, 10, redactMode, cropMode), stack, regions,
            new FlowPane(12, 10, button("import.sanitize", "import-sanitize", this::sanitize, true), button("import.undo", "import-undo", this::undo, false),
                button("import.clearRegions", "import-clear", this::clearRegions, false)));
        sanitized.setPreserveRatio(true); sanitized.setId("import-sanitized");
        var sanitizedFrame = new StackPane(sanitized); sanitizedFrame.getStyleClass().add("import-preview"); sanitizedFrame.setMaxWidth(Region.USE_PREF_SIZE);
        approve.setId("import-approve-check"); approve.setWrapText(true); texts.text(approve.textProperty(), "import.approveCheck");
        Button recognize = button("import.recognize", "import-recognize", this::recognize, true);
        recognize.disableProperty().bind(approve.selectedProperty().not());
        approveBox.getChildren().addAll(texts.label("import.step.approve", "empty-title"), sanitizedFrame, sanitizedInfo, approve, new FlowPane(12, 10, recognize));
        type.getItems().setAll(EquipmentType.values()); type.setId("import-type"); texts.choices(type, value -> localization.text("gear.type." + value.name()));
        texts.text(type.accessibleTextProperty(), "gear.type");
        type.setOnAction(event -> { if (draft != null && type.getValue() != null && type.getValue() != draft.type()) edit(); });
        Label typeCaption = texts.label("gear.type", "section-title"); typeCaption.setLabelFor(type);
        acceptUnverified.setId("import-accept-unverified"); acceptUnverified.setWrapText(true);
        summary.setId("import-summary"); evidence.setId("import-evidence"); conflicts.setId("import-conflicts"); validation.setId("import-validation");
        reviewBox.getChildren().addAll(texts.label("import.step.review", "empty-title"), texts.label("import.reviewHint", "secondary-text"), summary,
            texts.label("import.candidates", "section-title"), candidatesBox, new VBox(7, typeCaption, type), fields, conflicts,
            texts.label("import.evidenceTitle", "section-title"), evidence, notices, duplicatesBox, acceptUnverified, validation,
            new FlowPane(12, 10, button("import.confirm", "import-confirm", this::confirm, true), button("import.verify", "import-verify", this::verify, false)));
        result.setId("import-result");
        resultBox.getChildren().addAll(texts.label("import.feedback", "section-title"), result, new FlowPane(12, 10,
            button("import.openInventory", "import-open-inventory", () -> { reset(); openInventory.run(); }, true),
            button("import.restart", "import-restart", () -> { reset(); hide(feedbackBox); }, false)));
        Button cancel = button("import.cancel", "import-cancel", this::cancel, false);
        Button manual = button("import.manual", "import-manual", () -> { reset(); openInventory.run(); }, false);
        for (var box : List.of(selectBox, privacyBox, approveBox, reviewBox, resultBox, feedbackBox)) box.getStyleClass().add("feedback-box");
        feedbackBox.getChildren().addAll(texts.label("import.feedback", "section-title"), feedback, new FlowPane(12, 10, manual));
        var cancelRow = new FlowPane(12, 10, cancel);
        controls.getChildren().addAll(selectBox, privacyBox, approveBox, reviewBox, cancelRow);
        cancelRow.visibleProperty().bind(Bindings.createBooleanBinding(() -> privacyBox.isVisible() || reviewBox.isVisible(), privacyBox.visibleProperty(), reviewBox.visibleProperty()));
        cancelRow.managedProperty().bind(cancelRow.visibleProperty());
        manual.visibleProperty().bind(resultBox.visibleProperty().not()); manual.managedProperty().bind(manual.visibleProperty());
        hide(privacyBox); hide(approveBox); hide(reviewBox); hide(resultBox); hide(feedbackBox); hide(screenshotWarning);
        texts.onRefresh(this::render);
        getChildren().addAll(controls, resultBox, feedbackBox);
    }

    void activate(boolean active) { }
    void dispose() { disposed = true; release(); texts.close(); }
    boolean dirty() { return review != null || draft != null; }
    boolean busy() { return busy; }
    String feedbackText() { return feedback.getText(); }
    GearImportDraft draft() { return draft; }
    Path sanitizedCopy() { return copy; }
    /** Test hook equivalent to dragging on the preview, in original-image pixels. */
    boolean addRegion(boolean redact, int x, int y, int w, int h) { return region(redact, x, y, w, h); }

    private void browse() {
        var chooser = new FileChooser(); chooser.setTitle(localization.text("import.browseTitle"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG / JPEG", "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
        var chosen = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) path.setText(chosen.getAbsolutePath());
    }
    private void load() {
        if (review != null || draft != null) { message(() -> localization.text("import.cli.busy")); return; }
        String text = path.getText().strip();
        if (text.isEmpty()) { message(() -> localization.text("import.error.noFile")); return; }
        Path file; try { file = Path.of(text); } catch (RuntimeException invalid) { message(() -> localization.text("import.error.missing", text)); return; }
        var chosen = kind.getValue();
        message(() -> localization.text("import.loading"));
        // Validation and decoding run off the UI thread; nothing is sent anywhere.
        run(() -> privacy.open(file, chosen), opened -> {
            review = opened;
            var preview = opened.preview((int) PREVIEW_WIDTH).orElseThrow();
            original.setImage(fx(preview)); original.setFitWidth(preview.getWidth());
            scale = (double) opened.width() / preview.getWidth();
            overlay.setPrefSize(preview.getWidth(), preview.getHeight()); overlay.setMaxSize(preview.getWidth(), preview.getHeight());
            texts.text(loaded.textProperty(), "import.loaded", opened.fileName(), opened.width(), opened.height(), opened.format());
            texts.text(screenshotWarning.textProperty(), "import.screenshotWarning");
            if (opened.kind() == ImageReview.Kind.SCREENSHOT) show(screenshotWarning); else hide(screenshotWarning);
            selectBox.setDisable(true); show(privacyBox); hide(approveBox); drawRegions();
            message(() -> localization.text("import.loaded", opened.fileName(), opened.width(), opened.height(), opened.format()));
            SessionPane.reveal(this, privacyBox);
        }, null);
    }
    private boolean region(boolean redact, int x, int y, int w, int h) {
        if (review == null) return false;
        boolean added = redact ? review.redact(x, y, w, h) : review.crop(x, y, w, h);
        invalidateCopy(); drawRegions();
        return added;
    }
    private void undo() { if (review != null) { review.undo(); invalidateCopy(); drawRegions(); } }
    private void clearRegions() { if (review != null) { review.clearRegions(); invalidateCopy(); drawRegions(); } }
    private void drawRegions() {
        overlay.getChildren().clear();
        if (review == null) return;
        review.cropRegion().ifPresent(crop -> overlay.getChildren().add(shape(crop.x / scale, crop.y / scale, crop.width / scale, crop.height / scale, false)));
        for (var area : review.redactions()) overlay.getChildren().add(shape(area.x / scale, area.y / scale, area.width / scale, area.height / scale, true));
        var current = review;
        texts.bind(regions.textProperty(), () -> localization.text("import.regions", current.redactions().size(),
            localization.text(current.cropRegion().isPresent() ? "import.cropYes" : "import.cropNo")));
    }
    private static Rectangle shape(double x, double y, double w, double h, boolean redaction) {
        var shape = new Rectangle(x, y, w, h); shape.getStyleClass().add(redaction ? "import-redaction" : "import-crop"); shape.setMouseTransparent(true);
        return shape;
    }
    private static void resize(Rectangle shape, Point2D start, double x, double y) {
        shape.setX(Math.min(start.getX(), x)); shape.setY(Math.min(start.getY(), y));
        shape.setWidth(Math.abs(x - start.getX())); shape.setHeight(Math.abs(y - start.getY()));
    }
    /** Any change after the copy was made requires a new copy and a new approval. */
    private void invalidateCopy() {
        if (copy != null) { privacy.discard(copy); copy = null; }
        approve.setSelected(false); sanitized.setImage(null); hide(approveBox);
    }
    private void sanitize() {
        if (review == null) return;
        var current = review;
        message(() -> localization.text("import.sanitizing"));
        run(() -> {
            var written = privacy.sanitize(current);
            if (!written.isSuccess()) return Result.<Object[]>failure(written);
            try { return Result.success(new Object[]{written.value().orElseThrow(), Files.readAllBytes(written.value().orElseThrow())}); }
            catch (java.io.IOException unreadable) { return Result.<Object[]>failureKey("import.error.sanitize"); }
        }, pair -> {
            copy = (Path) pair[0]; byte[] bytes = (byte[]) pair[1];
            // The preview decodes the written file itself: exactly the bytes that approval would send.
            var image = new Image(new java.io.ByteArrayInputStream(bytes));
            Arrays.fill(bytes, (byte) 0);
            sanitized.setImage(image); sanitized.setFitWidth(Math.min(PREVIEW_WIDTH, image.getWidth()));
            texts.text(sanitizedInfo.textProperty(), "import.sanitizedInfo", (int) image.getWidth(), (int) image.getHeight(), Math.max(1, sizeKb(copy)));
            approve.setSelected(false); show(approveBox);
            message(() -> localization.text("import.sanitizedReady")); SessionPane.reveal(this, approveBox);
        }, null);
    }
    private void recognize() {
        if (copy == null || !approve.isSelected()) { message(() -> localization.text("import.error.approvalRequired")); return; }
        var reviewed = copy;
        message(() -> localization.text("import.recognizing"));
        run(() -> {
            var approved = privacy.approveRedacted(reviewed, true);
            if (!approved.isSuccess()) return Result.<GearImportDraft>failure(approved);
            SanitizedImage image = approved.value().orElseThrow();
            try { return app.recognize(image); } finally { privacy.discard(image); }
        }, recognized -> {
            copy = null; privacy.discard(review); review = null;
            original.setImage(null); sanitized.setImage(null); hide(privacyBox); hide(approveBox);
            open(recognized);
            message(() -> localization.text("import.released"));
        }, failure -> {
            copy = null; approve.setSelected(false); sanitized.setImage(null); hide(approveBox);
            message(() -> localization.text("import.aiFailed", String.join(" ", failure.errors(localization))));
        });
    }
    private void open(GearImportDraft value) {
        draft = value; show(reviewBox); render();
        var current = value;
        run(() -> app.inventory().importDuplicates(current.type(), current.value("brand"), current.value("model")), found -> { duplicates = found; render(); }, null);
        SessionPane.reveal(this, reviewBox);
    }
    private void select(int index) {
        if (draft == null) return;
        draft = draft.select(index); duplicates = List.of(); render(); verify();
    }
    private void verify() {
        if (!applyInputs()) return;
        var current = draft;
        message(() -> localization.text("import.verifying"));
        run(() -> app.inventory().verify(current), verified -> { open(verified); message(() -> localization.text("import.verified")); }, null);
    }
    /** Applies the visible inputs and category to the draft; false (with feedback) when an edit is rejected. */
    private boolean applyInputs() {
        if (draft == null) return false;
        var edits = new LinkedHashMap<String, String>();
        if (type.getValue() != null) edits.put("type", type.getValue().name());
        inputs.forEach((key, input) -> edits.put(key, input.getText()));
        var edited = draft.withEdits(edits);
        if (!edited.isSuccess()) { message(() -> String.join(" ", edited.errors(localization))); return false; }
        draft = edited.value().orElseThrow();
        return true;
    }
    private void edit() {
        if (!applyInputs()) return;
        render();
    }
    private void confirm() {
        if (!applyInputs()) return;
        render();
        if (!draft.problems().isEmpty()) { message(() -> GearImportPresentation.problems(draft, localization)); return; }
        Optional<Equipment> increase = Optional.empty(); boolean separate = false;
        if (duplicateChoice.getSelectedToggle() != null) {
            Object choice = duplicateChoice.getSelectedToggle().getUserData();
            if (choice instanceof Equipment target) increase = Optional.of(target); else separate = true;
        }
        var decision = new GearImportDraft.Decision(acceptUnverified.isSelected(), separate, increase);
        var current = draft; boolean increasing = increase.isPresent();
        message(() -> localization.text("import.saving"));
        run(() -> app.inventory().confirm(current, decision), saved -> {
            texts.bind(result.textProperty(), () -> increasing ? localization.text("import.savedQuantity", saved.brand(), saved.model(), saved.quantity(), saved.id())
                : localization.text("import.saved", saved.brand(), saved.model(), saved.quantity(), saved.id()));
            release(); draft = null; hide(reviewBox); selectBox.setDisable(false); path.clear();
            show(resultBox); hide(feedbackBox); SessionPane.reveal(this, resultBox);
        }, failure -> {
            message(() -> String.join(" ", failure.errors(localization)));
            if (failure.hasError("error.gear.duplicate")) {
                var refreshed = draft;
                run(() -> app.inventory().importDuplicates(refreshed.type(), refreshed.value("brand"), refreshed.value("model")), found -> { duplicates = found; render(); }, null);
            }
        });
    }
    private void cancel() {
        release(); message(() -> localization.text("import.cancelled"));
    }
    private void reset() { release(); hide(resultBox); }
    /** Releases all temporary image data and returns to the first step; the original file is never touched. */
    private void release() {
        if (copy != null) { privacy.discard(copy); copy = null; }
        if (review != null) { privacy.discard(review); review = null; }
        draft = null; duplicates = List.of(); original.setImage(null); sanitized.setImage(null); overlay.getChildren().clear();
        approve.setSelected(false); acceptUnverified.setSelected(false); inputs.clear(); fields.getChildren().clear();
        hide(privacyBox); hide(approveBox); hide(reviewBox); selectBox.setDisable(false);
    }

    /** Rebuilds the review from the current draft (also on language change). */
    private void render() {
        if (draft == null) return;
        var current = draft;
        texts.bind(summary.textProperty(), () -> String.join("\n", GearImportPresentation.summary(current, localization)));
        candidatesBox.getChildren().clear();
        var candidates = current.recognizedCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            int index = i; var candidate = candidates.get(i);
            boolean chosen = i == current.selectedIndex() && current.selectionMade();
            var box = new VBox(8); box.getStyleClass().add("feedback-box"); box.setId("import-candidate-" + (i + 1));
            Label heading = FeaturePane.label("", "section-title");
            texts.bind(heading.textProperty(), () -> GearImportPresentation.candidate(index, candidate, localization)
                + (chosen ? " · " + localization.text("import.selected") : ""));
            Label details = FeaturePane.label("", "secondary-text");
            texts.bind(details.textProperty(), () -> String.join("\n", GearImportPresentation.candidateDetails(candidate, localization)));
            box.getChildren().addAll(heading, details);
            if (!chosen) box.getChildren().add(new FlowPane(12, 10, button("import.useCandidate", "import-use-" + (i + 1), () -> select(index), false)));
            candidatesBox.getChildren().add(box);
        }
        type.setValue(current.type());
        fields.getChildren().clear(); inputs.clear();
        current.fields().forEach((key, field) -> field(key, field));
        var conflictLines = GearImportPresentation.conflicts(current, localization);
        texts.bind(conflicts.textProperty(), () -> String.join("\n", GearImportPresentation.conflicts(current, localization)));
        if (conflictLines.isEmpty()) hide(conflicts); else show(conflicts);
        texts.bind(evidence.textProperty(), () -> String.join("\n", GearImportPresentation.evidence(current, localization)));
        texts.bind(notices.textProperty(), () -> String.join("\n", GearImportPresentation.notices(current, localization)));
        if (current.notices().isEmpty()) hide(notices); else show(notices);
        renderDuplicates();
        boolean unverified = !current.unverifiedCritical().isEmpty();
        texts.bind(acceptUnverified.textProperty(), () -> localization.text("import.acceptUnverified", GearImportPresentation.unverified(current, localization)));
        if (unverified) show(acceptUnverified); else { acceptUnverified.setSelected(false); hide(acceptUnverified); }
        texts.bind(validation.textProperty(), () -> GearImportPresentation.problems(current, localization));
    }
    private void renderDuplicates() {
        Object previous = duplicateChoice.getSelectedToggle() == null ? null : duplicateChoice.getSelectedToggle().getUserData();
        duplicatesBox.getChildren().clear();
        duplicateChoice.getToggles().clear();
        if (duplicates.isEmpty()) { hide(duplicatesBox); return; }
        duplicatesBox.getChildren().add(texts.label("import.duplicatesTitle", "section-title"));
        var separate = new RadioButton(); separate.setId("import-dup-separate"); separate.setToggleGroup(duplicateChoice); separate.setWrapText(true);
        separate.setUserData("separate"); separate.setSelected("separate".equals(previous));
        texts.text(separate.textProperty(), "import.duplicateSeparate");
        duplicatesBox.getChildren().add(separate);
        for (int i = 0; i < duplicates.size(); i++) {
            var item = duplicates.get(i);
            var increase = new RadioButton(); increase.setId("import-dup-increase-" + (i + 1)); increase.setToggleGroup(duplicateChoice);
            increase.setUserData(item); increase.setWrapText(true);
            increase.setSelected(previous instanceof Equipment chosen && chosen.id().equals(item.id()));
            texts.text(increase.textProperty(), "import.duplicateIncrease", item.brand(), item.model(), item.id());
            Label line = FeaturePane.label("", "secondary-text"); texts.bind(line.textProperty(), () -> GearImportPresentation.duplicate(item, localization));
            duplicatesBox.getChildren().addAll(increase, line);
        }
        show(duplicatesBox);
    }
    private void field(String key, GearImportDraft.Field value) {
        TextField input = new TextField(value.value()); input.setId("import-field-" + key); texts.inputMenu(input);
        boolean required = Set.of("brand", "model", "quantity").contains(key) || (draft.type() != null
            && draft.type().fields().stream().anyMatch(spec -> spec.key().equals(key) && spec.required()));
        texts.bind(input.accessibleTextProperty(), () -> GearImportPresentation.fieldName(key, localization) + (required ? " *" : ""));
        texts.text(input.promptTextProperty(), required ? "gear.required" : "gear.unknown");
        Label caption = FeaturePane.label("", "section-title"); caption.setLabelFor(input);
        texts.bind(caption.textProperty(), () -> required ? localization.text("gear.requiredLabel", GearImportPresentation.fieldName(key, localization))
            : GearImportPresentation.fieldName(key, localization));
        Label status = FeaturePane.label("", value.status() == SpecificationStatus.UNKNOWN_OR_CONFLICTING && draft.fieldConflicts().stream()
            .anyMatch(conflict -> conflict.field().equals(key)) ? "feedback-text" : "secondary-text");
        status.setId("import-status-" + key);
        texts.bind(status.textProperty(), () -> GearImportPresentation.status(value, localization));
        // Enter re-evaluates statuses; Verify and Confirm always apply every visible value first.
        input.setOnAction(event -> edit());
        VBox box = new VBox(6, caption, input, status); box.setMinWidth(0);
        box.prefWidthProperty().bind(Bindings.createDoubleBinding(() -> fields.getWidth() < 560 ? Math.max(160, fields.getWidth()) : (fields.getWidth() - 16) / 2, fields.widthProperty()));
        fields.getChildren().add(box); inputs.put(key, input);
    }

    private static WritableImage fx(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < pixels.length; i++) pixels[i] |= 0xFF000000;
        var target = new WritableImage(w, h);
        target.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
        return target;
    }
    private static long sizeKb(Path file) {
        try { return Files.size(file) / 1024; } catch (java.io.IOException | RuntimeException unknown) { return 0; }
    }
    private Button button(String key, String id, Runnable action, boolean primary) {
        Button button = texts.button(key); button.setId(id); button.getStyleClass().add(primary ? "primary-button" : "secondary-button");
        button.setOnAction(e -> action.run()); return button;
    }
    private <T> void run(Supplier<Result<T>> operation, Consumer<T> success, Consumer<Result<T>> failure) {
        if (busy) return;
        busy = true; controls.setDisable(true);
        Task<Result<T>> task = new Task<>() { protected Result<T> call() { return operation.get(); } };
        task.setOnSucceeded(e -> {
            if (disposed) return;
            busy = false; controls.setDisable(false); var outcome = task.getValue();
            if (outcome.isSuccess()) success.accept(outcome.value().orElseThrow());
            else if (failure != null) failure.accept(outcome);
            else message(() -> String.join(" ", outcome.errors(localization)));
        });
        task.setOnFailed(e -> { if (disposed) return; busy = false; controls.setDisable(false); message(() -> localization.text("import.failed")); });
        operations.execute(task);
    }
    private void message(Supplier<String> message) { texts.bind(feedback.textProperty(), message); show(feedbackBox); SessionPane.reveal(this, feedbackBox); }
    private static void show(Node node) { node.setVisible(true); node.setManaged(true); }
    private static void hide(Node node) { node.setVisible(false); node.setManaged(false); }
}
