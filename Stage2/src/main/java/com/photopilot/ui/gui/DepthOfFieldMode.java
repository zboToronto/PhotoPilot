package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.model.CalculationResult;
import com.photopilot.model.CameraSetup;
import com.photopilot.service.TechnicalCalculator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Depth of field and hyperfocal distance, one page for both calculations. Inputs: actual focal length, aperture, the
 * focus distance, and the camera's sensor with its circle of confusion (the camera shared with the other optical
 * calculators). Results: near and far limits of acceptable sharpness, total depth of field and the hyperfocal distance,
 * all from the shared thin-lens calculation through the application facade; this class holds no optical formula.
 * <p>
 * Two focus modes, one set of inputs, one diagram and one set of result tiles:
 * <ul>
 *   <li>{@link FocusMode#MANUAL}: the user sets the focus distance with its slider, its number field or by dragging the
 *   subject in the diagram. One {@code DEPTH_OF_FIELD} calculation at that distance.</li>
 *   <li>{@link FocusMode#HYPERFOCAL}: the focus is locked at the hyperfocal distance of the current lens and camera
 *   ({@code HYPERFOCAL}, then {@code DEPTH_OF_FIELD} at exactly that distance), recalculated on every change of focal
 *   length, aperture, sensor or circle of confusion. The focus distance input shows the locked value and cannot be
 *   edited, and the subject cannot be dragged: while locked there is one source of the focus distance.</li>
 * </ul>
 * The manual focus distance is set aside while the focus is locked and comes back exactly as it was with manual focus;
 * a calculated distance never replaces it. Reset returns the inputs and manual focus; the camera has its own reset.
 */
final class DepthOfFieldMode extends CalculatorMode {
    /** Where the lens is focused. */
    enum FocusMode { MANUAL, HYPERFOCAL }
    /**
     * The manual focus distance set aside while the focus is locked: metres when it was usable; otherwise the text as it
     * was typed, the unit it was typed in and the last usable value underneath it (the field's, never a locked one).
     */
    private record ManualFocus(OptionalDouble metres, String text, LengthUnit unit, OptionalDouble lastUsable) { }
    private static final PseudoClass LOCKED = PseudoClass.getPseudoClass("locked");

    private final CalculatorField focal, aperture, distance;
    private final Map<FocusMode, ToggleButton> modeButtons = new EnumMap<>(FocusMode.class);
    private final ToggleGroup modeChoice = new ToggleGroup();
    private final Label modeNote = FeaturePane.label("", "calc-note");
    private final Tile total, near, far, hyperfocal;
    private final FocusDiagram diagram;
    private FocusMode focusMode = FocusMode.MANUAL;
    /** Set while the focus is locked; null with manual focus, where the focus distance input is the manual distance. */
    private ManualFocus manual;

    DepthOfFieldMode(Context context) {
        super(context, CalculationForm.Tool.DOF);
        focal = focalLength(50);
        aperture = aperture(8);

        // The focus mode, directly above the focus distance it decides.
        FlowPane modes = new FlowPane(2, 2); modes.setId("calc-focus-mode"); modes.getStyleClass().add("calc-segments");
        texts.text(modes.accessibleTextProperty(), "calc.dof.focusMode");
        for (FocusMode mode : FocusMode.values()) {
            ToggleButton button = new ToggleButton(); button.setId("calc-focus-" + mode.name()); button.getStyleClass().setAll("segment");
            button.setToggleGroup(modeChoice); button.setMinWidth(Region.USE_PREF_SIZE);
            texts.text(button.textProperty(), "calc.dof.focusMode." + mode.name());
            button.setOnAction(event -> focusMode(mode));
            modeButtons.put(mode, button); modes.getChildren().add(button);
        }
        modeButtons.get(FocusMode.MANUAL).setSelected(true);
        // Shown only while the focus is locked: that it is, and that the manual distance is kept.
        modeNote.setId("calc-focus-note"); modeNote.managedProperty().bind(modeNote.textProperty().isNotEmpty()); modeNote.visibleProperty().bind(modeNote.managedProperty());
        controls.getChildren().add(new VBox(5, texts.label("calc.dof.focusMode", "section-title"), modes, modeNote));
        distance = distance("distance", "calc.dof.focus", 0.1, 1000, 3);
        camera(false, true);

        total = tile("total", "calc.dof.total", true);
        near = tile("near", "calc.dof.near", false);
        far = tile("far", "calc.dof.far", false);
        hyperfocal = tile("hyperfocal", "calc.dof.hyperfocal", false);
        diagram = new FocusDiagram(localization);
        diagram.onFocus(metres -> { if (focusMode == FocusMode.MANUAL) { distance.set(metres); update(); } });
        compose(diagram);
    }

    /**
     * Chooses the focus mode. Locking sets the manual distance aside (as a value, or as typed when it was not usable);
     * unlocking puts it back exactly as it was. Choosing the mode already in use changes nothing.
     */
    void focusMode(FocusMode next) {
        modeButtons.get(next).setSelected(true);
        if (next == focusMode) return;
        focusMode = next;
        if (next == FocusMode.HYPERFOCAL) {
            manual = new ManualFocus(distance.value(), distance.input().getText(), lengthUnit(), distance.lastValue());
            distance.automatic(true, "calc.dof.focusLockedCaption");
        } else {
            distance.automatic(false);
            var saved = manual; manual = null;
            if (saved.metres().isPresent()) distance.set(saved.metres().getAsDouble());
            else {
                // Unusable text goes back on top of the last usable manual value, so the slider and any later unit change
                // fall back to the user's distance and never to the locked one. It is read again only in the unit it was
                // typed in; after a unit change it is dropped, as a unit change drops unreadable text anyway.
                saved.lastUsable().ifPresentOrElse(distance::set, distance::reset);
                if (saved.unit() == lengthUnit()) distance.retype(saved.text());
            }
        }
        update();
    }
    FocusMode focusMode() { return focusMode; }
    /** The manual focus distance in metres: the input with manual focus, the one set aside while locked; empty if not usable. */
    OptionalDouble manualDistance() { return manual == null ? distance.value() : manual.metres(); }

    @Override protected void compute() {
        describe();
        if (focusMode == FocusMode.HYPERFOCAL) hyperfocalFocus(); else manualFocus();
    }

    /** Manual focus: depth of field at the distance the user set. */
    private void manualFocus() {
        emphasize(total, near, far, hyperfocal);
        if (!usable(focal, aperture, distance)) return;
        CameraSetup camera = context.camera().get();
        double f = focal.value().orElseThrow();
        Map<String, Double> parameters = new LinkedHashMap<>(); Map<String, String> units = new LinkedHashMap<>();
        parameters.put("focalLength", f); units.put("focalLength", "mm");
        parameters.put("aperture", aperture.value().orElseThrow()); units.put("aperture", "f-number");
        parameters.put("coc", camera.cocMm()); units.put("coc", "mm");
        putDistance(parameters, units, distance);
        var result = calculate(new CalculationInput(CalculationKind.DEPTH_OF_FIELD, parameters, units));
        if (result.isEmpty()) return;
        show(result.get(), distance.value().orElseThrow(), f);
    }

    /**
     * Hyperfocal focus: the hyperfocal distance of the current lens and camera, then depth of field focused at exactly
     * that distance (one optical model for both). The distance is used as calculated; it is never limited to the range
     * of the slider or the diagram. Without a current hyperfocal distance the focus distance input is left empty.
     */
    private void hyperfocalFocus() {
        emphasize(hyperfocal, near, far, total);
        if (!usable(focal, aperture)) { distance.clear(); return; }
        CameraSetup camera = context.camera().get();
        double f = focal.value().orElseThrow();
        var lens = calculate(new CalculationInput(CalculationKind.HYPERFOCAL,
            Map.of("focalLength", f, "aperture", aperture.value().orElseThrow(), "coc", camera.cocMm()),
            Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm")));
        if (lens.isEmpty()) { distance.clear(); return; }
        var focused = calculate(CalculationForm.atHyperfocal(lens.get()));
        if (focused.isEmpty()) { distance.clear(); return; }
        double locked = lens.get().values().get(TechnicalCalculator.HYPERFOCAL_DISTANCE);
        distance.set(locked);
        show(focused.get(), locked, f);
    }

    /** The order of the result tiles, the first one emphasized: what the focus mode is about comes first. */
    private void emphasize(Tile... order) {
        order(order);
        for (Tile tile : order) tile.primary(tile == order[0]);
    }

    /** Shows one depth of field result focused at {@code focusMetres}, in the tiles and the diagram. */
    private void show(CalculationResult result, double focusMetres, double focalMm) {
        var values = result.values();
        boolean locked = focusMode == FocusMode.HYPERFOCAL;
        double nearLimit = values.get(TechnicalCalculator.NEAR_LIMIT), farLimit = values.get(TechnicalCalculator.FAR_LIMIT), depth = values.get(TechnicalCalculator.TOTAL_DEPTH);
        double hyperfocalMetres = values.get(TechnicalCalculator.HYPERFOCAL_DISTANCE);

        // Focused at the hyperfocal distance the near limit is half of it under this model (the calculation says so too).
        near.show(distanceText(nearLimit), locked ? localization.text("calc.dof.nearHalf") : localization.text("calc.dof.inFront", distanceText(focusMetres - nearLimit)));
        if (Double.isInfinite(farLimit)) {
            // Unbounded under this model: stated in words as well as with the sign.
            far.show(CalculatorPresentation.INFINITY, localization.text("calc.dof.unbounded"));
            total.show(CalculatorPresentation.INFINITY, localization.text("calc.dof.unboundedTotal", distanceText(nearLimit)));
        } else {
            far.show(distanceText(farLimit), localization.text("calc.dof.behind", distanceText(farLimit - focusMetres)));
            total.show(distanceText(depth), localization.text("calc.dof.split", CalculatorPresentation.number((focusMetres - nearLimit) / depth * 100, 2), CalculatorPresentation.number((farLimit - focusMetres) / depth * 100, 2)));
        }
        hyperfocal.show(distanceText(hyperfocalMetres), localization.text(locked ? "calc.dof.hyperfocalLocked" : "calc.dof.hyperfocalDetail"));
        // The subject cannot be dragged closer than the lens can focus in this model (beyond one focal length).
        diagram.focusRange(Math.max(distance.min(), focalMm / 1000 * 1.05), distance.max());
        diagram.show(focusMetres, nearLimit, farLimit, hyperfocalMetres, lengthUnit(), locked);
        available();
    }

    /**
     * What the lock means, beside the selector, only while the focus is locked. How the diagram is operated is not
     * written on the page (the handle shows it); it is the diagram's accessible help.
     */
    private void describe() {
        boolean locked = focusMode == FocusMode.HYPERFOCAL;
        modeNote.setText(!locked ? ""
            : manual != null && manual.metres().isPresent() ? localization.text("calc.dof.focusMode.lockedNote", distanceText(manual.metres().getAsDouble()))
            : localization.text("calc.dof.focusMode.lockedNoteEntry"));
        modeNote.pseudoClassStateChanged(LOCKED, locked);
        diagram.setAccessibleHelp(localization.text(locked ? "calc.dof.lockedHint" : "calc.dof.dragHint"));
    }
    /**
     * The formula with what its letters stand for, the model's limits, and that the results depend on the circle of
     * confusion (its value in use is shown with the camera).
     */
    @Override protected List<String> details() {
        return List.of(localization.text("calc.details.dof.formula"), localization.text("calc.details.dof.symbols"),
            localization.text("calc.details.dof.assumption"), localization.text("calc.details.dof.coc"));
    }

    /** Reset returns manual focus too; the manual distance set aside is dropped with the other inputs. */
    @Override protected void resetExtras() {
        focusMode = FocusMode.MANUAL; manual = null;
        distance.automatic(false); modeButtons.get(FocusMode.MANUAL).setSelected(true);
    }
    @Override protected boolean extrasChanged() { return focusMode != FocusMode.MANUAL; }
    @Override int apply(Map<String, String> known) { return applyNumber(known, "focalLength", focal) ? 1 : 0; }
    FocusDiagram focusDiagram() { return diagram; }
}
