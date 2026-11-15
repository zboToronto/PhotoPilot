package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.application.CalculatorPresentation.SpeedUnit;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.model.CameraSetup;
import com.photopilot.service.TechnicalCalculator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;

/**
 * Motion blur for still photography, deliberately simplified: a stationary camera and a subject moving at constant
 * speed across the frame, far enough away that distance is much larger than the focal length. Inputs: exposure time,
 * actual focal length, subject distance, lateral speed and the sensor; optionally the image's pixel dimensions and a
 * blur tolerance. Results: blur length on the sensor, in image pixels when the image width is known (never guessed
 * from megapixels), as a share of the image width, and the longest exposure within the tolerance.
 */
final class MotionBlurMode extends CalculatorMode {
    /** Unit of the blur tolerance; {@link #service} is the unit name the calculator accepts. */
    enum LimitUnit {
        MICROMETRES("um", "µm"), PIXELS("px", "px");
        final String service, symbol;
        LimitUnit(String service, String symbol) { this.service = service; this.symbol = symbol; }
    }
    private final CalculatorField exposure, focal, distance, speed, limit;
    private final ComboBox<SpeedUnit> speedUnit = new ComboBox<>();
    private final ComboBox<LimitUnit> limitUnit = new ComboBox<>();
    private final CheckBox limited = new CheckBox();
    private final Tile sensorBlur, imageBlur, share, travel, longest;
    private final MotionBlurDiagram diagram;

    MotionBlurMode(Context context) {
        super(context, CalculationForm.Tool.MOTION);
        exposure = shutter("shutter", "calc.motion.exposure", 1 / 1000.0);
        focal = focalLength(50);
        distance = distance("distance", "calc.motion.distance", 0.1, 10000, 20);
        speed = field("speed", "calc.motion.speed", Scale.fromZero(0.1, 400), 0, 2000, 10, CalculatorField.Format.NUMBER);
        speedUnit.setId("calc-speed-unit"); speedUnit.getItems().setAll(SpeedUnit.values()); speedUnit.setValue(SpeedUnit.METRES_PER_SECOND);
        texts.choices(speedUnit, unit -> unit.symbol); texts.text(speedUnit.accessibleTextProperty(), "calc.motion.speedUnit");
        // The speed is kept in metres per second; another unit only changes how it is shown and typed.
        speedUnit.setOnAction(event -> { if (speedUnit.getValue() != null) { speed.display(speedUnit.getValue().metresPerSecond, () -> ""); update(); } });
        speed.accessory(speedUnit);

        // Optional blur tolerance, with the exposure inputs and above the shared camera section.
        limited.setId("calc-motion-limited"); limited.setSelected(true); limited.setWrapText(true);
        texts.text(limited.textProperty(), "calc.motion.limited");
        controls.getChildren().add(limited);
        limit = linked("blurLimit", "calc.motion.limit", 0.01, 100000, 10);
        limit.onChange(this::update);
        limitUnit.setId("calc-motion-limit-unit"); limitUnit.getItems().setAll(LimitUnit.values()); limitUnit.setValue(LimitUnit.MICROMETRES);
        texts.choices(limitUnit, unit -> unit.symbol); texts.text(limitUnit.accessibleTextProperty(), "calc.motion.limitUnit");
        limitUnit.setOnAction(event -> update());
        limit.accessory(limitUnit);
        limited.selectedProperty().addListener((property, was, selected) -> { limit.setDisable(!selected); update(); });
        camera(true, false);

        sensorBlur = tile("sensorBlur", "calc.motion.sensorBlur", true);
        imageBlur = tile("imageBlur", "calc.motion.imageBlur", false);
        share = tile("share", "calc.motion.share", false);
        travel = tile("travel", "calc.motion.travel", false);
        longest = tile("longest", "calc.motion.longest", false);
        diagram = new MotionBlurDiagram(localization);
        compose(diagram);
    }

    @Override protected void compute() {
        boolean withLimit = limited.isSelected();
        if (!(withLimit ? usable(exposure, focal, distance, speed, limit) : usable(exposure, focal, distance, speed))) return;
        CameraSetup camera = context.camera().get();
        Map<String, Double> parameters = new LinkedHashMap<>(); Map<String, String> units = new LinkedHashMap<>();
        parameters.put("shutter", exposure.value().orElseThrow()); units.put("shutter", "s");
        parameters.put("focalLength", focal.value().orElseThrow()); units.put("focalLength", "mm");
        putDistance(parameters, units, distance);
        parameters.put("speed", speed.value().orElseThrow()); units.put("speed", "m/s");
        parameters.put("sensorWidth", camera.sensorWidthMm()); units.put("sensorWidth", "mm");
        // Pixels are used only when the image width for this sensor was entered; nothing is inferred.
        boolean pixels = camera.imageWidthPx().isPresent();
        if (pixels) { parameters.put("imageWidth", (double) camera.imageWidthPx().getAsInt()); units.put("imageWidth", "px"); }
        LimitUnit toleranceUnit = limitUnit.getValue();
        boolean limitUsable = withLimit && (toleranceUnit != LimitUnit.PIXELS || pixels);
        if (limitUsable) { parameters.put("blurLimit", limit.value().orElseThrow()); units.put("blurLimit", toleranceUnit.service); }
        var result = calculate(new CalculationInput(CalculationKind.MOTION_BLUR, parameters, units));
        if (result.isEmpty()) return;
        // The frame the preview draws: the scene covered at the subject distance, from the field of view operation.
        var coverage = calculate(new CalculationInput(CalculationKind.FIELD_OF_VIEW,
            Map.of("focalLength", focal.value().orElseThrow(), "sensorWidth", camera.sensorWidthMm(), "sensorHeight", camera.sensorHeightMm(), "distance", distance.value().orElseThrow()),
            Map.of("focalLength", "mm", "sensorWidth", "mm", "sensorHeight", "mm", "distance", "m")));
        if (coverage.isEmpty()) return;
        var values = result.get().values();
        double blur = values.get(TechnicalCalculator.SENSOR_BLUR);
        // Physical blur on the sensor and blur in image pixels are separate results; captions say which is which.
        sensorBlur.show(CalculatorPresentation.sensorLength(blur), blur == 0 ? localization.text("calc.motion.noBlur") : "");
        if (pixels) imageBlur.show(localization.text("calc.motion.pixels", CalculatorPresentation.number(values.get(TechnicalCalculator.IMAGE_BLUR), 3)),
            localization.text("calc.motion.imageBlurDetail", Integer.toString(camera.imageWidthPx().getAsInt())));
        else imageBlur.show(CalculatorPresentation.NONE, localization.text("calc.motion.imageBlurMissing"));
        share.show(CalculatorPresentation.percent(values.get(TechnicalCalculator.BLUR_SHARE)));
        travel.show(distanceText(values.get(TechnicalCalculator.SUBJECT_TRAVEL)));
        if (!withLimit) longest.show(CalculatorPresentation.NONE, localization.text("calc.motion.longestOff"));
        else if (!limitUsable) longest.show(CalculatorPresentation.NONE, localization.text("calc.motion.longestNeedsPixels"));
        else {
            double seconds = values.get(TechnicalCalculator.LONGEST_EXPOSURE);
            String tolerance = CalculatorPresentation.number(limit.value().orElseThrow(), 4) + " " + toleranceUnit.symbol;
            // No lateral motion: motion sets no upper limit. Said in words; the infinity sign alone could read as a time.
            if (Double.isInfinite(seconds)) longest.show(localization.text("calc.motion.noLimit"), localization.text("calc.motion.noLimitDetail"));
            else longest.show(CalculatorPresentation.exposureTime(seconds, localization), localization.text("calc.motion.longestDetail", tolerance));
        }
        var scene = coverage.get().values();
        diagram.show(blur, camera.sensorWidthMm(), camera.sensorHeightMm(), camera.imageWidthPx(), scene.get(TechnicalCalculator.SCENE_WIDTH), scene.get(TechnicalCalculator.SCENE_HEIGHT), lengthUnit());
        // The one caution that changes how to read the numbers: the calculation says the subject is too close for its
        // approximation. It stays with the results; the general assumptions are in the details section.
        boolean tooClose = result.get().localizedAssumptions().stream().anyMatch(note -> note.key().equals("calc.assumption.motionClose"));
        if (tooClose) available(localization.text("calc.motion.closeWarning")); else available();
    }
    @Override protected List<String> details() {
        return List.of(localization.text("calc.details.motion.formula"), localization.text("calc.details.motion.assumption"), localization.text("calc.details.motion.preview"));
    }

    @Override protected void resetExtras() {
        speedUnit.setValue(SpeedUnit.METRES_PER_SECOND); limitUnit.setValue(LimitUnit.MICROMETRES); limited.setSelected(true);
    }
    @Override protected boolean extrasChanged() { return !limited.isSelected() || speedUnit.getValue() != SpeedUnit.METRES_PER_SECOND || limitUnit.getValue() != LimitUnit.MICROMETRES; }
    @Override int apply(Map<String, String> known) { return applyNumber(known, "focalLength", focal) ? 1 : 0; }
    MotionBlurDiagram blurDiagram() { return diagram; }
}
