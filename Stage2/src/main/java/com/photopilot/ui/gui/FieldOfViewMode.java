package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.MotionBlurPreview;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.model.CameraSetup;
import com.photopilot.service.PhotoFormulas;
import com.photopilot.service.TechnicalCalculator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javafx.scene.control.CheckBox;

/**
 * Field of view. Inputs: actual focal length and the sensor's width and height; optionally a subject distance for the
 * scene covered there. Results: horizontal, vertical and diagonal angles of view for an ideal rectilinear lens, the
 * scene's width and height at the distance, and, as additional information only, the 35 mm equivalent focal length.
 * The calculation always uses the actual focal length with the actual sensor dimensions.
 */
final class FieldOfViewMode extends CalculatorMode {
    private final CalculatorField focal, distance;
    private final CheckBox coverage = new CheckBox();
    private final Tile horizontal, vertical, diagonal, scene, equivalent;
    private final FieldOfViewDiagram diagram;

    FieldOfViewMode(Context context) {
        super(context, CalculationForm.Tool.FOV);
        focal = focalLength(50);
        coverage.setId("calc-fov-coverage"); coverage.setSelected(true); coverage.setWrapText(true);
        texts.text(coverage.textProperty(), "calc.fov.coverage");
        controls.getChildren().add(coverage);
        distance = distance("distance", "calc.fov.distance", 0.1, 10000, 10);
        coverage.selectedProperty().addListener((property, was, selected) -> { distance.setDisable(!selected); update(); });
        camera(false, false);

        horizontal = tile("horizontal", "calc.fov.horizontal", true);
        vertical = tile("vertical", "calc.fov.vertical", false);
        diagonal = tile("diagonal", "calc.fov.diagonal", false);
        scene = tile("scene", "calc.fov.scene", false);
        equivalent = tile("equivalent", "calc.fov.equivalent", false);
        diagram = new FieldOfViewDiagram(localization);
        compose(diagram);
    }

    @Override protected void compute() {
        boolean covered = coverage.isSelected();
        if (!(covered ? usable(focal, distance) : usable(focal))) return;
        CameraSetup camera = context.camera().get();
        double f = focal.value().orElseThrow();
        Map<String, Double> parameters = new LinkedHashMap<>(); Map<String, String> units = new LinkedHashMap<>();
        parameters.put("focalLength", f); units.put("focalLength", "mm");
        parameters.put("sensorWidth", camera.sensorWidthMm()); units.put("sensorWidth", "mm");
        parameters.put("sensorHeight", camera.sensorHeightMm()); units.put("sensorHeight", "mm");
        if (covered) putDistance(parameters, units, distance);
        var result = calculate(new CalculationInput(CalculationKind.FIELD_OF_VIEW, parameters, units));
        if (result.isEmpty()) return;
        var values = result.get().values();
        double h = values.get(TechnicalCalculator.HORIZONTAL_FOV), v = values.get(TechnicalCalculator.VERTICAL_FOV), d = values.get(TechnicalCalculator.DIAGONAL_FOV);
        horizontal.show(CalculatorPresentation.angle(h), localization.text("calc.fov.horizontalDetail", CalculatorPresentation.number(camera.sensorWidthMm(), 4)));
        vertical.show(CalculatorPresentation.angle(v), localization.text("calc.fov.verticalDetail", CalculatorPresentation.number(camera.sensorHeightMm(), 4)));
        diagonal.show(CalculatorPresentation.angle(d), localization.text("calc.fov.diagonalDetail", CalculatorPresentation.number(camera.diagonalMm(), 4)));
        double width = covered ? values.get(TechnicalCalculator.SCENE_WIDTH) : 0, height = covered ? values.get(TechnicalCalculator.SCENE_HEIGHT) : 0;
        if (covered) scene.show(localization.text("calc.fov.sceneValue", distanceText(width), distanceText(height)), localization.text("calc.fov.sceneDetail"));
        else scene.show(CalculatorPresentation.NONE, localization.text("calc.fov.sceneOff"));
        // Additional information: which focal length frames the same diagonal angle on 36 x 24 mm. Not used above.
        equivalent.show(CalculatorPresentation.number(PhotoFormulas.fullFrameEquivalentFocal(f, camera.sensorWidthMm(), camera.sensorHeightMm()), 3) + " mm",
            localization.text("calc.fov.equivalentDetail", CalculatorPresentation.number(PhotoFormulas.cropFactor(camera.sensorWidthMm(), camera.sensorHeightMm()), 3)));
        diagram.show(h, v, d, camera.sensorWidthMm(), camera.sensorHeightMm(), covered ? distance.value() : OptionalDouble.empty(), width, height, lengthUnit());
        available();
    }
    @Override protected List<String> details() {
        var lines = new java.util.ArrayList<String>(List.of(localization.text("calc.details.fov.formula"), localization.text("calc.details.fov.assumption"),
            localization.text("calc.details.fov.equivalent")));
        // The figure appears only with a subject distance; its height is the scale of the frame.
        if (coverage.isSelected()) lines.add(localization.text("calc.details.figure", CalculatorPresentation.number(MotionBlurPreview.REFERENCE_HEIGHT_METRES, 2)));
        return lines;
    }

    @Override protected void resetExtras() { coverage.setSelected(true); }
    @Override protected boolean extrasChanged() { return !coverage.isSelected(); }
    @Override int apply(Map<String, String> known) { return applyNumber(known, "focalLength", focal) ? 1 : 0; }
}
