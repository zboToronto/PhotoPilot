package com.photopilot.ui.gui;

import com.photopilot.application.*;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.CameraSetup;
import com.photopilot.model.Equipment;
import com.photopilot.model.GearInventory;
import java.util.*;
import java.util.concurrent.ExecutorService;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * F08 calculator workspace in the existing feature flow: six calculations (exposure, ND filter, depth of field,
 * hyperfocal distance, field of view, motion blur) on five pages behind one selector. Depth of field and hyperfocal
 * distance share a page, whose focus is either set by hand or locked at the hyperfocal distance. Each page keeps its own
 * inputs for the session while another is used; only the camera (sensor, pixel dimensions, circle of confusion) and the
 * distance unit are shared, and only by the calculators that use them. Every result is calculated locally through the
 * application facade as the inputs change: no Calculate button, no network and no AI request.
 */
final class CalculatorPane extends VBox {
    /** The pages in selector order. The hyperfocal calculation is the depth of field page with hyperfocal focus. */
    static final List<CalculationForm.Tool> PAGES = List.of(CalculationForm.Tool.EXPOSURE, CalculationForm.Tool.ND,
        CalculationForm.Tool.DOF, CalculationForm.Tool.FOV, CalculationForm.Tool.MOTION);
    private final LocalizationService localization;
    private final FxLocalizer texts;
    private final PhotoPilotFacade app;
    private final ExecutorService operations;
    private final ObjectProperty<CameraSetup> camera = new SimpleObjectProperty<>(CameraSetup.DEFAULT);
    private final ObjectProperty<LengthUnit> lengthUnit = new SimpleObjectProperty<>(LengthUnit.METRES);
    private final Map<CalculationForm.Tool, CalculatorMode> modes = new EnumMap<>(CalculationForm.Tool.class);
    private final Map<CalculationForm.Tool, ToggleButton> selectors = new EnumMap<>(CalculationForm.Tool.class);
    private final ToggleGroup selection = new ToggleGroup(), unitChoice = new ToggleGroup();
    private final VBox content = new VBox();
    private final HBox unitRow = new HBox(10);
    private final VBox gearRow = new VBox(8);
    private final ComboBox<Equipment> gear = new ComboBox<>();
    private final Button loadGear = new Button();
    private final Label gearFeedback = FeaturePane.label("", "calc-note");
    private CalculatorMode current;
    private long generation;
    private boolean active;

    CalculatorPane(PhotoPilotFacade app, ExecutorService operations) { this(app, operations, LocalizationService.english()); }
    CalculatorPane(PhotoPilotFacade app, ExecutorService operations, LocalizationService localization) {
        super(16);
        this.app = app; this.operations = operations; this.localization = localization;
        texts = new FxLocalizer(this, localization);
        getStyleClass().addAll("inventory-content", "calc-content"); setMinWidth(0);

        var context = new CalculatorMode.Context(app, texts, camera, lengthUnit);
        modes.put(CalculationForm.Tool.EXPOSURE, new ExposureMode(context));
        modes.put(CalculationForm.Tool.ND, new NdFilterMode(context));
        modes.put(CalculationForm.Tool.DOF, new DepthOfFieldMode(context));
        modes.put(CalculationForm.Tool.FOV, new FieldOfViewMode(context));
        modes.put(CalculationForm.Tool.MOTION, new MotionBlurMode(context));

        // Page selector: one segment per page; it wraps onto a second line in a narrow window.
        FlowPane selector = new FlowPane(2, 2); selector.setId("calc-modes"); selector.getStyleClass().add("calc-segments");
        texts.text(selector.accessibleTextProperty(), "calc.calculator");
        for (var tool : PAGES) {
            ToggleButton segment = new ToggleButton(); segment.setId("calc-mode-" + tool.name()); segment.getStyleClass().setAll("segment");
            segment.setToggleGroup(selection); segment.setMinWidth(Region.USE_PREF_SIZE);
            // The combined page has a name of its own; the command line keeps one name per calculation.
            texts.bind(segment.textProperty(), () -> localization.text(tool == CalculationForm.Tool.DOF ? "calc.page.DOF" : "calc.tool." + tool.name()));
            segment.setOnAction(event -> { segment.setSelected(true); select(tool); });
            selectors.put(tool, segment); selector.getChildren().add(segment);
        }

        // Distance unit of inputs, results and diagram axes; shown only for calculators that have distances.
        HBox units = new HBox(); units.setId("calc-units"); units.getStyleClass().add("segmented"); units.setMaxWidth(Region.USE_PREF_SIZE);
        for (LengthUnit unit : LengthUnit.values()) {
            ToggleButton segment = new ToggleButton(); segment.setId("calc-unit-" + unit.name()); segment.getStyleClass().setAll("segment");
            segment.setToggleGroup(unitChoice); segment.setSelected(unit == lengthUnit.get()); segment.setMinWidth(Region.USE_PREF_SIZE);
            texts.bind(segment.textProperty(), () -> localization.text("calc.units." + unit.name()));
            segment.setOnAction(event -> { segment.setSelected(true); lengthUnit.set(unit); });
            units.getChildren().add(segment);
        }
        unitRow.getChildren().setAll(texts.label("calc.units.distance", "settings-row-label"), units); unitRow.setAlignment(Pos.CENTER_LEFT);

        // Optional: copy known values of an inventory item into the selected calculator. Loaded only on request.
        texts.text(loadGear.textProperty(), "calc.loadInventory");
        loadGear.setId("calc-load-gear"); loadGear.getStyleClass().add("secondary-button"); loadGear.setOnAction(event -> loadInventory());
        gear.setId("calc-gear"); gear.setPrefWidth(260);
        texts.text(gear.accessibleTextProperty(), "calc.optionalGear"); texts.text(gear.promptTextProperty(), "calc.optionalGear");
        texts.choices(gear, item -> localization.text("calc.gearChoice", item.brand(), item.model(), localization.text("gear.type." + item.type().name())), () -> localization.text("calc.optionalGear"));
        Button applyGear = texts.button("calc.applyGear"); applyGear.setId("calc-use-gear"); applyGear.getStyleClass().add("secondary-button");
        applyGear.disableProperty().bind(gear.valueProperty().isNull());
        applyGear.setOnAction(event -> applyGear());
        gearFeedback.setId("calc-gear-feedback"); gearFeedback.managedProperty().bind(gearFeedback.textProperty().isNotEmpty()); gearFeedback.visibleProperty().bind(gearFeedback.managedProperty());
        gearRow.setId("calc-gear-row");
        gearRow.getChildren().setAll(new Separator(), texts.label("calc.gear.title", "section-title"), new FlowPane(12, 10, loadGear, gear, applyGear), gearFeedback);

        getChildren().addAll(selector, unitRow, content, gearRow);
        select(CalculationForm.Tool.EXPOSURE);
    }

    /**
     * Shows the page of one calculation. The others keep their inputs and results exactly as they were left. The
     * hyperfocal calculation opens the depth of field page with the focus locked at the hyperfocal distance.
     */
    void select(CalculationForm.Tool tool) {
        if (tool == CalculationForm.Tool.HYPERFOCAL) {
            select(CalculationForm.Tool.DOF);
            ((DepthOfFieldMode) current).focusMode(DepthOfFieldMode.FocusMode.HYPERFOCAL);
            return;
        }
        current = modes.get(tool);
        selectors.get(tool).setSelected(true);
        content.getChildren().setAll(current);
        // Only what the selected calculator uses is on screen: no distance unit or gear values for exposure, for example.
        boolean distances = current.usesDistance(), gearValues = tool != CalculationForm.Tool.EXPOSURE;
        unitRow.setVisible(distances); unitRow.setManaged(distances);
        gearRow.setVisible(gearValues); gearRow.setManaged(gearValues);
        gearMessage(() -> "");
        current.update();
    }
    CalculatorMode mode() { return current; }
    /** The page that provides a calculation (the depth of field page for the hyperfocal distance). */
    CalculatorMode mode(CalculationForm.Tool tool) { return modes.get(tool == CalculationForm.Tool.HYPERFOCAL ? CalculationForm.Tool.DOF : tool); }
    CameraSetup camera() { return camera.get(); }
    LengthUnit lengthUnit() { return lengthUnit.get(); }

    /** True when any calculator's inputs or the shared camera differ from their starting values (session-only state). */
    boolean dirty() { return modes.values().stream().anyMatch(CalculatorMode::changed) || !camera.get().equals(CameraSetup.DEFAULT); }
    void dispose() { active = false; generation++; modes.values().forEach(CalculatorMode::dispose); texts.close(); }
    void activate(boolean value) {
        active = value; generation++; loadGear.setDisable(false);
        // Cached inventory is invalidated when reopening; the calculators' own inputs stay as they are.
        if (active) { gear.getItems().clear(); gearMessage(() -> ""); }
    }

    private void loadInventory() {
        long request = ++generation; loadGear.setDisable(true);
        Task<Result<GearInventory>> task = new Task<>() { @Override protected Result<GearInventory> call() { return app.inventory().list(""); } };
        task.setOnSucceeded(event -> {
            if (!active || request != generation) return;
            loadGear.setDisable(false);
            var result = task.getValue();
            if (!result.isSuccess()) gearMessage(() -> String.join("; ", result.errors(localization)));
            else { gear.getItems().setAll(result.value().orElseThrow().items()); gearMessage(() -> localization.text(gear.getItems().isEmpty() ? "calc.emptyInventory" : "calc.selectGear")); }
        });
        task.setOnFailed(event -> { if (active && request == generation) { loadGear.setDisable(false); gearMessage(() -> localization.text("calc.loadFailed")); } });
        operations.execute(task);
    }
    /**
     * Copies the item's known values into the selected calculator: a fixed focal length or ND strength into its own
     * input, sensor dimensions into the shared camera. Nothing is inferred (no zoom focal length, taking aperture,
     * circle of confusion or pixel dimensions from megapixels), and the inventory is never changed.
     */
    private void applyGear() {
        var known = CalculationForm.knownValues(gear.getValue());
        int used = current.apply(known);
        if (current.usesCamera()) {
            var setup = camera.get();
            double width = number(known.get("sensorWidth")).orElse(setup.sensorWidthMm()), height = number(known.get("sensorHeight")).orElse(setup.sensorHeightMm());
            if ((known.containsKey("sensorWidth") || known.containsKey("sensorHeight")) && width >= 1 && width <= 300 && height >= 1 && height <= 300) {
                camera.set(setup.withSensor(width, height)); used++;
            }
        }
        current.update();
        boolean copied = used > 0;
        gearMessage(() -> localization.text(copied ? "calc.copied" : "calc.noValues"));
    }
    private static OptionalDouble number(String text) {
        try { return text == null ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(text)); }
        catch (NumberFormatException unknown) { return OptionalDouble.empty(); }
    }
    private void gearMessage(java.util.function.Supplier<String> value) { texts.bind(gearFeedback.textProperty(), value); }
    String gearFeedback() { return gearFeedback.getText(); }
}
