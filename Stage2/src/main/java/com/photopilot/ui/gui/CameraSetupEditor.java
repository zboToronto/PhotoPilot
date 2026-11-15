package com.photopilot.ui.gui;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.CameraSetup;
import com.photopilot.model.CameraSetup.CocPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Editor of the camera parameters an optical calculator needs: sensor format or custom dimensions, and, only where the
 * calculator uses them, the image's pixel dimensions (motion blur) or the circle of confusion (depth of field,
 * hyperfocal distance). Every editor edits the one {@link CameraSetup} of the workspace, so the camera is entered once
 * and nothing else is shared between calculators. A camera model is never needed.
 */
final class CameraSetupEditor extends VBox {
    private final FxLocalizer texts;
    private final LocalizationService localization;
    private final ObjectProperty<CameraSetup> camera;
    private final ComboBox<String> format = new ComboBox<>();
    private final CalculatorField width, height;
    private final CalculatorField imageWidth, imageHeight, coc;
    private final ComboBox<CocPolicy> policy = new ComboBox<>();
    private final Label cocInUse = FeaturePane.label("", "calc-note"), aspect = FeaturePane.label("", "calc-note");
    private final List<Runnable> listeners = new ArrayList<>();
    private final ChangeListener<CameraSetup> cameraChanged = (property, old, value) -> { synchronize(); notifyListeners(); };
    private boolean synchronizing;

    CameraSetupEditor(FxLocalizer texts, ObjectProperty<CameraSetup> camera, boolean pixels, boolean circleOfConfusion) {
        super(10);
        this.texts = texts; this.localization = texts.service(); this.camera = camera;
        getStyleClass().add("calc-camera"); setMinWidth(0);
        Label title = texts.label("calc.camera.title", "group-title");
        Label shared = texts.label("calc.camera.shared", "calc-note");

        format.setId("calc-camera-format"); format.setMaxWidth(Double.MAX_VALUE);
        CameraSetup.FORMATS.forEach(preset -> format.getItems().add(preset.id())); format.getItems().add(CameraSetup.CUSTOM);
        texts.choices(format, this::formatLabel);
        texts.text(format.accessibleTextProperty(), "calc.camera.format");
        Label formatCaption = texts.label("calc.camera.format", "section-title"); formatCaption.setLabelFor(format);
        format.setOnAction(event -> {
            if (synchronizing || format.getValue() == null) return;
            var current = camera.get();
            var preset = CameraSetup.FORMATS.stream().filter(candidate -> candidate.id().equals(format.getValue())).findFirst();
            // Custom keeps the numbers shown, ready to be edited.
            camera.set(preset.map(current::withFormat).orElseGet(() -> current.withSensor(current.sensorWidthMm(), current.sensorHeightMm())));
        });

        width = dimension("calc-camera-width", "calc.camera.width"); height = dimension("calc-camera-height", "calc.camera.height");
        Runnable sensorEdited = () -> {
            if (synchronizing) return;
            var w = width.value(); var h = height.value();
            var current = camera.get();
            if (w.isPresent() && h.isPresent() && (w.getAsDouble() != current.sensorWidthMm() || h.getAsDouble() != current.sensorHeightMm()))
                camera.set(current.withSensor(w.getAsDouble(), h.getAsDouble()));
            else notifyListeners();
        };
        width.onChange(sensorEdited); height.onChange(sensorEdited);
        HBox sensor = pair(width, height);
        // That presets are typical dimensions is a tooltip of the format choice, not a paragraph under it.
        var formatTip = new Tooltip(); texts.text(formatTip.textProperty(), "calc.camera.formatTip"); format.setTooltip(formatTip);
        getChildren().addAll(new VBox(3, title, shared), new VBox(5, formatCaption, format), sensor);

        if (pixels) {
            imageWidth = pixelCount("calc-camera-image-width", "calc.camera.imageWidth"); imageHeight = pixelCount("calc-camera-image-height", "calc.camera.imageHeight");
            Runnable imageEdited = () -> {
                if (synchronizing) return;
                if (imageWidth.valid() && imageHeight.valid()) {
                    var next = camera.get().withImage(whole(imageWidth.value()), whole(imageHeight.value()));
                    if (!next.equals(camera.get())) { camera.set(next); return; }
                }
                notifyListeners();
            };
            imageWidth.onChange(imageEdited); imageHeight.onChange(imageEdited);
            aspect.setId("calc-camera-aspect"); aspect.managedProperty().bind(aspect.textProperty().isNotEmpty()); aspect.visibleProperty().bind(aspect.managedProperty());
            getChildren().addAll(pair(imageWidth, imageHeight), texts.label("calc.camera.imageNote", "calc-note"), aspect);
        } else { imageWidth = null; imageHeight = null; }

        if (circleOfConfusion) {
            policy.setId("calc-camera-coc-policy"); policy.setMaxWidth(Double.MAX_VALUE); policy.getItems().setAll(CocPolicy.values());
            texts.choices(policy, value -> localization.text("calc.camera.coc." + value.name()));
            texts.text(policy.accessibleTextProperty(), "calc.camera.cocPolicy");
            Label policyCaption = texts.label("calc.camera.cocPolicy", "section-title"); policyCaption.setLabelFor(policy);
            coc = new CalculatorField(texts, "calc-camera-coc", "calc.camera.cocCustom", null, 0.001, 1, OptionalDouble.of(CameraSetup.DEFAULT_CUSTOM_COC_MM), CalculatorField.Format.NUMBER, false);
            coc.unit(() -> "mm");
            policy.setOnAction(event -> {
                if (synchronizing || policy.getValue() == null) return;
                var current = camera.get();
                // Switching to a custom value starts from the one in use, so the result does not jump.
                double custom = policy.getValue() == CocPolicy.CUSTOM && current.cocPolicy() != CocPolicy.CUSTOM ? CalculatorPresentation.significant(current.cocMm(), 3) : current.customCocMm();
                camera.set(current.withCoc(policy.getValue(), custom));
            });
            coc.onChange(() -> {
                if (synchronizing) return;
                var value = coc.value(); var current = camera.get();
                if (value.isPresent() && value.getAsDouble() != current.customCocMm()) camera.set(current.withCoc(CocPolicy.CUSTOM, value.getAsDouble()));
                else notifyListeners();
            });
            cocInUse.setId("calc-camera-coc-in-use");
            getChildren().addAll(new VBox(5, policyCaption, policy), coc, cocInUse);
        } else coc = null;

        Button reset = texts.button("calc.camera.reset"); reset.setId("calc-camera-reset"); reset.getStyleClass().add("secondary-button");
        reset.setOnAction(event -> {
            if (camera.get().equals(CameraSetup.DEFAULT)) { synchronize(); notifyListeners(); } else camera.set(CameraSetup.DEFAULT);
        });
        getChildren().add(reset);
        camera.addListener(cameraChanged);
        texts.onRefresh(this::describe);
        synchronize();
    }

    private CalculatorField dimension(String id, String key) {
        var field = new CalculatorField(texts, id, key, null, 1, 300, OptionalDouble.empty(), CalculatorField.Format.NUMBER, false);
        field.unit(() -> "mm"); HBox.setHgrow(field, Priority.ALWAYS); return field;
    }
    private CalculatorField pixelCount(String id, String key) {
        var field = new CalculatorField(texts, id, key, null, 1, 200_000, OptionalDouble.empty(), CalculatorField.Format.WHOLE, true);
        field.unit(() -> "px"); HBox.setHgrow(field, Priority.ALWAYS); return field;
    }
    private static HBox pair(CalculatorField first, CalculatorField second) {
        HBox row = new HBox(10, first, second); row.setFillHeight(false);
        first.setMaxWidth(Double.MAX_VALUE); second.setMaxWidth(Double.MAX_VALUE); first.setPrefWidth(120); second.setPrefWidth(120);
        return row;
    }
    private static OptionalInt whole(OptionalDouble value) { return value.isPresent() ? OptionalInt.of((int) Math.rint(value.getAsDouble())) : OptionalInt.empty(); }
    private String formatLabel(String id) {
        var preset = CameraSetup.FORMATS.stream().filter(candidate -> candidate.id().equals(id)).findFirst();
        if (preset.isEmpty()) return localization.text("calc.camera.format.custom");
        return localization.text("calc.camera.formatLabel", localization.text("calc.camera.format." + id),
            CalculatorPresentation.number(preset.get().widthMm(), 4), CalculatorPresentation.number(preset.get().heightMm(), 4));
    }

    /** Shows the shared camera in this editor's controls. A field already showing a value is left as the user typed it. */
    private void synchronize() {
        synchronizing = true;
        try {
            var current = camera.get();
            format.setValue(current.format().map(CameraSetup.Format::id).orElse(CameraSetup.CUSTOM));
            show(width, current.sensorWidthMm()); show(height, current.sensorHeightMm());
            if (imageWidth != null) { show(imageWidth, current.imageWidthPx()); show(imageHeight, current.imageHeightPx()); }
            if (coc != null) {
                policy.setValue(current.cocPolicy());
                show(coc, current.customCocMm());
                boolean custom = current.cocPolicy() == CocPolicy.CUSTOM;
                coc.setVisible(custom); coc.setManaged(custom);
                // A custom value is already on screen in its own field; the derived one is stated once, below the policy.
                cocInUse.setVisible(!custom); cocInUse.setManaged(!custom);
            }
        } finally { synchronizing = false; }
        describe();
    }
    private static void show(CalculatorField field, double value) {
        var shown = field.value();
        if (shown.isEmpty() || shown.getAsDouble() != value) field.set(value);
    }
    private static void show(CalculatorField field, OptionalInt value) {
        if (value.isEmpty()) { if (field.value().isPresent() || !field.valid()) field.clear(); }
        else show(field, value.getAsInt());
    }
    private void describe() {
        var current = camera.get();
        // The value the results are calculated with; where it comes from is the choice shown directly above it.
        if (coc != null) cocInUse.setText(localization.text("calc.camera.cocInUse", CalculatorPresentation.number(current.cocMm(), 3)));
        if (imageWidth != null) {
            boolean mismatch = current.imageWidthPx().isPresent() && current.imageHeightPx().isPresent()
                && Math.abs((double) current.imageWidthPx().getAsInt() / current.imageHeightPx().getAsInt() / (current.sensorWidthMm() / current.sensorHeightMm()) - 1) > 0.03;
            aspect.setText(mismatch ? localization.text("calc.camera.aspectNote") : "");
        }
    }

    /** False while a camera field holds text that cannot be used; the calculators then show no result. */
    boolean valid() {
        return width.valid() && height.valid() && (imageWidth == null || imageWidth.valid() && imageHeight.valid())
            && (coc == null || camera.get().cocPolicy() != CocPolicy.CUSTOM || coc.valid());
    }
    /** Called after every edit here and every change of the shared camera. */
    void onChange(Runnable listener) { listeners.add(listener); }
    private void notifyListeners() { List.copyOf(listeners).forEach(Runnable::run); }
    void dispose() { camera.removeListener(cameraChanged); }
}
