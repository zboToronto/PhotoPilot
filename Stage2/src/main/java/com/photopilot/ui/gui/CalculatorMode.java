package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationResult;
import com.photopilot.model.CameraSetup;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * One calculator of the workspace: its own inputs (kept for the session while other calculators are used), live results
 * and a visualization. Every change of an input recalculates through the shared application facade, so the numbers are
 * the ones the command line and the planning tool get; nothing here holds a formula.
 * While an input cannot be used the results show a dash and say why, and the drawing is marked as not current.
 * The working view carries labels, values and units only: formula and assumptions are a few lines in a section that is
 * closed by default, and only a caution that changes how a result should be read is shown with the results.
 */
abstract class CalculatorMode extends VBox {
    /** What every calculator shares with the workspace: services, texts, the one camera and the distance unit. */
    record Context(PhotoPilotFacade app, FxLocalizer texts, ObjectProperty<CameraSetup> camera, ObjectProperty<LengthUnit> lengthUnit) { }

    /** One labelled result. */
    static final class Tile extends VBox {
        private static final PseudoClass UNAVAILABLE = PseudoClass.getPseudoClass("unavailable");
        private final Label value = FeaturePane.label("", "calc-value"), detail = FeaturePane.label("", "calc-detail");
        Tile(FxLocalizer texts, String id, String captionKey, boolean primary) {
            super(2);
            getStyleClass().add("calc-tile"); primary(primary);
            setId("calc-tile-" + id); value.setId("calc-result-" + id); detail.setId("calc-detail-" + id);
            setMinWidth(132); setMaxWidth(Double.MAX_VALUE);
            detail.managedProperty().bind(detail.textProperty().isNotEmpty()); detail.visibleProperty().bind(detail.managedProperty());
            getChildren().addAll(texts.label(captionKey, "calc-tile-caption"), value, detail);
        }
        void show(String text, String note) { value.setText(text); detail.setText(note); pseudoClassStateChanged(UNAVAILABLE, false); }
        void show(String text) { show(text, ""); }
        /** No current value: a dash, never the previous number. */
        void none(String note) { value.setText(CalculatorPresentation.NONE); detail.setText(note); pseudoClassStateChanged(UNAVAILABLE, true); }
        String text() { return value.getText(); }
        String detail() { return detail.getText(); }
        /** The leading result of the view, emphasized; a calculator whose emphasis depends on a mode changes it. */
        void primary(boolean value) { if (!value) getStyleClass().remove("calc-tile-primary"); else if (!primary()) getStyleClass().add("calc-tile-primary"); }
        boolean primary() { return getStyleClass().contains("calc-tile-primary"); }
    }

    private static final double TWO_COLUMNS = 560;
    protected final PhotoPilotFacade app;
    protected final FxLocalizer texts;
    protected final LocalizationService localization;
    protected final Context context;
    private final CalculationForm.Tool tool;
    protected final VBox controls = new VBox(14);
    private final FlowPane tiles = new FlowPane(10, 10);
    private final Label status = FeaturePane.label("", "calc-status");
    private final VBox cautions = new VBox(4), notes = new VBox(4);
    private final TitledPane details = new TitledPane();
    private final VBox output = new VBox(12);
    private final GridPane layout = new GridPane();
    private final List<CalculatorField> fields = new ArrayList<>(), distances = new ArrayList<>();
    private final List<Tile> tileList = new ArrayList<>();
    private CalculatorDiagram diagram;
    private CameraSetupEditor cameraEditor;
    private Boolean wide;
    private boolean ready, updating;

    CalculatorMode(Context context, CalculationForm.Tool tool) {
        super(14);
        this.context = context; this.app = context.app(); this.texts = context.texts(); this.localization = texts.service(); this.tool = tool;
        setId("calc-mode-view-" + tool.name()); setMinWidth(0);
        status.setId("calc-status"); status.managedProperty().bind(status.textProperty().isNotEmpty()); status.visibleProperty().bind(status.managedProperty());
        tiles.setId("calc-results"); notes.setId("calc-notes");
        cautions.setId("calc-cautions"); cautions.managedProperty().bind(Bindings.isNotEmpty(cautions.getChildren())); cautions.visibleProperty().bind(cautions.managedProperty());
    }
    CalculationForm.Tool tool() { return tool; }

    // ---- Construction helpers for subclasses -----------------------------------------------------------------------

    protected final CalculatorField field(String key, String captionKey, Scale scale, double min, double max, double initial, CalculatorField.Format format) {
        var field = new CalculatorField(texts, "calc-" + key, captionKey, scale, min, max, OptionalDouble.of(initial), format, false);
        fields.add(field); field.onChange(this::update); controls.getChildren().add(field);
        return field;
    }
    /**
     * An input without a slider that the subclass wires itself (for example one of several fields showing the same
     * quantity on different scales). It takes part in reset and in the session's changed state like any other input.
     */
    protected final CalculatorField linked(String key, String captionKey, double min, double max, double initial) {
        var field = new CalculatorField(texts, "calc-" + key, captionKey, null, min, max, OptionalDouble.of(initial), CalculatorField.Format.NUMBER, false);
        fields.add(field); controls.getChildren().add(field);
        return field;
    }
    /** A distance input: kept in metres, shown and typed in the workspace's distance unit. */
    protected final CalculatorField distance(String key, String captionKey, double minMetres, double maxMetres, double initialMetres) {
        var field = field(key, captionKey, Scale.logarithmic(minMetres, maxMetres), minMetres, maxMetres, initialMetres, CalculatorField.Format.NUMBER);
        distances.add(field); showLengthUnit(field);
        return field;
    }
    protected final CalculatorField focalLength(double initial) {
        var field = field("focalLength", "calc.input.focalLength", Scale.nominal(CalculatorPresentation.FOCAL_LENGTHS), 1, 2000, initial, CalculatorField.Format.NUMBER);
        field.unit(() -> "mm"); return field;
    }
    protected final CalculatorField aperture(double initial) {
        var field = field("aperture", "calc.input.aperture", Scale.nominal(CalculatorPresentation.APERTURES), 0.5, 128, initial, CalculatorField.Format.NUMBER);
        field.unit(() -> localization.text("calc.unit.fNumber")); return field;
    }
    protected final CalculatorField shutter(String key, String captionKey, double initial) {
        var field = field(key, captionKey, Scale.nominal(CalculatorPresentation.SHUTTERS), 1 / 64000.0, 86400, initial, CalculatorField.Format.SHUTTER);
        field.unit(() -> localization.text("calc.unit.seconds")); return field;
    }
    protected final CameraSetupEditor camera(boolean pixels, boolean circleOfConfusion) {
        cameraEditor = new CameraSetupEditor(texts, context.camera(), pixels, circleOfConfusion);
        cameraEditor.onChange(this::update); controls.getChildren().add(cameraEditor);
        return cameraEditor;
    }
    protected final Tile tile(String id, String captionKey, boolean primary) {
        var tile = new Tile(texts, id, captionKey, primary); tileList.add(tile); tiles.getChildren().add(tile); return tile;
    }
    /** Shows this calculator's result tiles in a new order (all of them, each once), without recreating them. */
    protected final void order(Tile... shown) {
        if (shown.length != tileList.size() || !List.of(shown).containsAll(tileList)) throw new IllegalArgumentException("every tile once");
        if (!tiles.getChildren().equals(List.of(shown))) tiles.getChildren().setAll(shown);
    }
    /** The result tiles in the order they are shown. */
    final List<Tile> tilesShown() { return tiles.getChildren().stream().map(Tile.class::cast).toList(); }
    /**
     * Assembles the view once the subclass has created its inputs: the inputs beside the visualization and the results
     * (stacked when the window is narrow), any caution that changes how the results should be read, and a section with
     * the formula and assumptions that stays closed until it is asked for. Each calculator has its own reset.
     * @param afterResults further content of the calculator, placed after the result tiles
     */
    protected final void compose(CalculatorDiagram diagram, Node... afterResults) {
        this.diagram = diagram;
        Button reset = texts.button("calc.reset"); reset.setId("calc-reset"); reset.getStyleClass().add("secondary-button");
        var resetTip = new javafx.scene.control.Tooltip(); texts.text(resetTip.textProperty(), "calc.reset.tip"); reset.setTooltip(resetTip);
        reset.setOnAction(event -> reset()); reset.setWrapText(true);
        controls.getChildren().add(reset); controls.setMinWidth(0); controls.getStyleClass().add("calc-controls");
        StackPane frame = new StackPane(diagram); frame.getStyleClass().add("calc-diagram-box"); frame.setMinWidth(0);
        // Diagram, the results it shows, anything that qualifies them, then whatever else the calculator offers.
        output.getChildren().addAll(frame, tiles, status, cautions); output.getChildren().addAll(afterResults);
        // Formula and assumptions: available, closed by default, never part of the working view.
        details.setId("calc-details"); details.getStyleClass().add("calc-details"); details.setContent(notes);
        details.setExpanded(false); details.setAnimated(false); details.setMinWidth(0); details.setMaxWidth(Double.MAX_VALUE);
        texts.text(details.textProperty(), "calc.details");
        // The standard control opens on Space; Enter does the same here, as on a button.
        details.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && details.isFocused()) { details.setExpanded(!details.isExpanded()); event.consume(); }
        });
        output.getChildren().add(details); output.setMinWidth(0);
        layout.setHgap(20); layout.setVgap(16);
        widthProperty().addListener((property, was, now) -> arrange(now.doubleValue() >= TWO_COLUMNS));
        arrange(true);
        getChildren().add(layout);
        // A language change re-renders every text this calculator has put on screen.
        texts.onRefresh(this::update);
        context.lengthUnit().addListener((property, old, unit) -> { distances.forEach(this::showLengthUnit); update(); });
        ready = true;
        update();
    }
    private void arrange(boolean twoColumns) {
        if (wide != null && wide == twoColumns) return;
        wide = twoColumns;
        layout.getChildren().clear();
        if (twoColumns) {
            layout.addRow(0, controls, output);
            var inputs = new ColumnConstraints(); inputs.setPercentWidth(36); inputs.setFillWidth(true);
            var results = new ColumnConstraints(); results.setPercentWidth(64); results.setFillWidth(true); results.setHgrow(Priority.ALWAYS);
            layout.getColumnConstraints().setAll(inputs, results);
        } else {
            layout.addColumn(0, output, controls);
            var only = new ColumnConstraints(); only.setPercentWidth(100); only.setFillWidth(true);
            layout.getColumnConstraints().setAll(only);
        }
        for (Node area : List.of(controls, output)) { GridPane.setValignment(area, VPos.TOP); GridPane.setHalignment(area, HPos.LEFT); }
    }
    private void showLengthUnit(CalculatorField field) { var unit = context.lengthUnit().get(); field.display(unit.metres, () -> unit.symbol); }

    // ---- Calculation -------------------------------------------------------------------------------------------------

    /** Recalculates and redraws from the current inputs. Runs on every slider position and every usable keystroke. */
    final void update() {
        if (!ready || updating) return;
        updating = true;
        try { compute(); describeDetails(); } finally { updating = false; }
    }
    /** Reads the inputs, calculates through {@link #calculate} and shows the outcome with {@link #available} or {@link #unavailable}. */
    protected abstract void compute();
    /**
     * The formula and the assumptions of this calculator in a few short lines, for the section that is closed by default.
     * Nothing that is already on screen, and nothing a user needs in order to read a result (that is a caution).
     */
    protected abstract List<String> details();
    private void describeDetails() {
        var lines = details();
        // Rebuilt only when the text changes (a language change, an option that adds a line).
        if (!lines.equals(detailTexts())) notes.getChildren().setAll(lines.stream().map(line -> (Node) FeaturePane.label(line, "calc-note")).toList());
    }

    /** True when every needed input can be used; otherwise the first one that cannot is reported and no result is shown. */
    protected final boolean usable(CalculatorField... needed) {
        for (CalculatorField field : needed) if (!field.valid()) {
            unavailable(LocalizedMessage.of("calc.status.input", LocalizedMessage.of(field.captionKey()), field.problem())); return false;
        }
        if (cameraEditor != null && !cameraEditor.valid()) { unavailable(LocalizedMessage.of("calc.status.camera")); return false; }
        return true;
    }
    /** One calculation through the shared facade; a refusal is shown as the reason no result is available. */
    protected final Optional<CalculationResult> calculate(CalculationInput input) {
        var result = app.calculate(input);
        if (!result.isSuccess()) { unavailable(result.messages().toArray(LocalizedMessage[]::new)); return Optional.empty(); }
        return result.value();
    }
    /**
     * Results are current: clears the notice.
     * @param warnings short cautions that change how these results should be read (an approximation outside its range);
     *                 they stay visible under the results, unlike the general assumptions in the details section
     */
    protected final void available(String... warnings) {
        status.setText("");
        var lines = List.of(warnings);
        // Cautions rarely change while a slider moves, so their labels are rebuilt only when the text does.
        if (!lines.equals(cautionTexts())) cautions.getChildren().setAll(lines.stream().map(line -> (Node) FeaturePane.label(line, "calc-caution")).toList());
        diagram.stale(false, "");
    }
    /** No current result: every value becomes a dash, the reason is shown, and the drawing is marked as the last valid one. */
    protected final void unavailable(LocalizedMessage... reasons) {
        var parts = new ArrayList<String>();
        for (var reason : reasons) parts.add(reason.render(localization));
        status.setText(localization.text("calc.status.unavailable", String.join(" ", parts)));
        cautions.getChildren().clear();
        tileList.forEach(tile -> tile.none(""));
        diagram.stale(true, localization.text("calc.diagram.stale"));
    }
    protected final LengthUnit lengthUnit() { return context.lengthUnit().get(); }
    protected final String distanceText(double metres) { return CalculatorPresentation.distance(metres, lengthUnit()); }
    /**
     * The distance as the calculator receives it. The field keeps metres (feet are converted where they are typed and
     * shown), so a distance set exactly, such as the hyperfocal distance, reaches the calculation unchanged.
     */
    protected final void putDistance(Map<String, Double> parameters, Map<String, String> units, CalculatorField field) {
        parameters.put("distance", field.value().orElseThrow()); units.put("distance", "m");
    }

    // ---- Session state -----------------------------------------------------------------------------------------------

    /** Returns this calculator's own inputs to their starting values. The shared camera has its own reset. */
    final void reset() {
        fields.forEach(CalculatorField::reset);
        resetExtras();
        update();
    }
    /** Inputs a subclass keeps outside the registered fields (switches, a captured reference). */
    protected void resetExtras() { }
    protected boolean extrasChanged() { return false; }
    final boolean changed() { return fields.stream().anyMatch(CalculatorField::changed) || extrasChanged(); }
    /**
     * Copies values known from an inventory item (keys of {@link CalculationForm#knownValues}) into this calculator;
     * returns how many were used. Sensor dimensions go to the shared camera, handled by the workspace.
     */
    int apply(Map<String, String> known) { return 0; }
    protected final boolean applyNumber(Map<String, String> known, String key, CalculatorField field) {
        try {
            double value = Double.parseDouble(known.getOrDefault(key, ""));
            if (!Double.isFinite(value) || value < field.min() || value > field.max()) return false;
            field.set(value); return true;
        } catch (NumberFormatException unknown) { return false; }
    }
    boolean usesCamera() { return cameraEditor != null; }
    boolean usesDistance() { return !distances.isEmpty(); }
    String statusText() { return status.getText(); }
    /** The lines of the calculation details section (closed by default). */
    List<String> detailTexts() { return notes.getChildren().stream().map(node -> ((Label) node).getText()).toList(); }
    /** The cautions shown under the current results. */
    List<String> cautionTexts() { return cautions.getChildren().stream().map(node -> ((Label) node).getText()).toList(); }
    TitledPane detailsSection() { return details; }
    CalculatorDiagram diagram() { return diagram; }
    void dispose() { if (cameraEditor != null) cameraEditor.dispose(); }
}
