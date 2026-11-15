package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.service.PhotoFormulas;
import com.photopilot.service.TechnicalCalculator;
import java.util.List;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * Neutral density filter exposure. Inputs: the exposure time without the filter and the filter's strength in stops.
 * Result: the compensated exposure time, base x 2^stops. The same strength is also shown, and can be typed, as a filter
 * factor and as an optical density; those are exact conversions of the stops value, kept apart from the rounded names
 * filters are sold under. Aperture, ISO and the sensor play no part, so they are not shown.
 */
final class NdFilterMode extends CalculatorMode {
    private static final double[] PICKS = { 1, 2, 3, 6, 10, 15 };
    private final CalculatorField base, stops, factor, density;
    private final Tile adjusted, longer;
    private final Label commercial = FeaturePane.label("", "calc-note");
    private final NdFilterDiagram diagram;
    /** The strength field being typed in; its text is left alone while the other two follow it. */
    private CalculatorField editing;

    NdFilterMode(Context context) {
        super(context, CalculationForm.Tool.ND);
        base = shutter("baseShutter", "calc.nd.base", 1 / 125.0);
        stops = field("ndStops", "calc.nd.stops", Scale.linear(0, 20, 1), 0, 40, 10, CalculatorField.Format.NUMBER);
        stops.unit(() -> localization.text("calc.unit.stops"));

        FlowPane picks = new FlowPane(6, 6); picks.setId("calc-nd-picks");
        for (double pick : PICKS) {
            Button chip = new Button(); chip.setId("calc-nd-pick-" + (int) pick); chip.getStyleClass().add("calc-chip");
            texts.bind(chip.textProperty(), () -> localization.text(pick == 1 ? "calc.nd.pickOne" : "calc.nd.pick", CalculatorPresentation.number(pick, 3)));
            chip.setOnAction(event -> { stops.set(pick); update(); });
            picks.getChildren().add(chip);
        }
        controls.getChildren().add(new VBox(5, texts.label("calc.nd.picks", "section-title"), picks));

        // The same strength on two other scales. Editing either sets the stops; they are never independent inputs.
        factor = linked("ndFactor", "calc.nd.factor", 1, PhotoFormulas.factorFromStops(40), PhotoFormulas.factorFromStops(10));
        factor.unit(() -> localization.text("calc.unit.factor"));
        density = linked("opticalDensity", "calc.nd.density", 0, PhotoFormulas.densityFromStops(40), PhotoFormulas.densityFromStops(10));
        density.unit(() -> localization.text("calc.unit.density"));
        factor.onChange(() -> convert(factor, PhotoFormulas::stopsFromFactor));
        density.onChange(() -> convert(density, PhotoFormulas::stopsFromDensity));

        // Results only: the strength in stops, as a factor and as a density is the three inputs, not a result to repeat.
        adjusted = tile("adjusted", "calc.nd.adjusted", true);
        longer = tile("longer", "calc.nd.longer", false);
        // The line about the name a filter is sold under appears only when the strength has one.
        commercial.setId("calc-nd-commercial"); commercial.managedProperty().bind(commercial.textProperty().isNotEmpty()); commercial.visibleProperty().bind(commercial.managedProperty());
        diagram = new NdFilterDiagram(localization);
        compose(diagram, commercial);
    }

    private void convert(CalculatorField source, java.util.function.DoubleUnaryOperator toStops) {
        var value = source.value();
        if (value.isPresent()) stops.set(Math.max(0, Math.min(stops.max(), toStops.applyAsDouble(value.getAsDouble()))));
        editing = source;
        try { update(); } finally { editing = null; }
    }

    @Override protected void compute() {
        var k = stops.value();
        if (k.isPresent()) {
            if (editing != factor) factor.set(PhotoFormulas.factorFromStops(k.getAsDouble()));
            if (editing != density) density.set(PhotoFormulas.densityFromStops(k.getAsDouble()));
        }
        commercial.setText("");
        if (!usable(base, stops, factor, density)) return;
        double t = base.value().orElseThrow(), strengthStops = k.orElseThrow();
        var result = calculate(new CalculationInput(CalculationKind.EXPOSURE_ND, Map.of("shutter", t, "ndStops", strengthStops), Map.of("shutter", "s", "ndStops", "stops")));
        if (result.isEmpty()) return;
        double time = result.get().values().get(TechnicalCalculator.ADJUSTED_SHUTTER), ratio = time / t;
        // The unrounded seconds under the headline, unless they would only repeat it.
        String headline = CalculatorPresentation.exposureTime(time, localization), exact = localization.text("calc.nd.exact", CalculatorPresentation.number(time, 6));
        adjusted.show(headline, exact.equals(headline) ? "" : exact);
        longer.show(localization.text("calc.nd.times", CalculatorPresentation.number(ratio, 4)),
            localization.text("calc.nd.from", CalculatorPresentation.exposureTime(t, localization)));
        // Commercial names are labels: exact for the powers of two, rounded from ND500 upwards. For a rounded name the
        // filter's own factor is given with its stops to two decimals, so it never reads as the advertised whole number
        // (ND32000 is 14.97 stops, not 15).
        commercial.setText(CalculatorPresentation.commercialFilter(strengthStops).map(filter -> filter.rounded()
            ? localization.text("calc.nd.commercialRounded", filter.name(), CalculatorPresentation.number(filter.factor(), 9), CalculatorPresentation.decimals(filter.exactStops(), 2))
            : localization.text("calc.nd.commercialExact", filter.name()))
            .orElse(""));
        diagram.show(t, strengthStops, time);
        available();
    }
    @Override protected List<String> details() {
        return List.of(localization.text("calc.details.nd.formula"), localization.text("calc.details.nd.assumption"));
    }

    @Override int apply(Map<String, String> known) { return applyNumber(known, "ndStops", stops) ? 1 : 0; }
}
