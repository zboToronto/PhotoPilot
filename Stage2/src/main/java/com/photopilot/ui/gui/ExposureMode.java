package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.ExposurePresentation;
import com.photopilot.application.ExposureSolver;
import com.photopilot.application.ExposureSolver.Equivalent;
import com.photopilot.application.ExposureSolver.Solution;
import com.photopilot.application.ExposureStops;
import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Step;
import com.photopilot.i18n.LocalizedMessage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

/**
 * Exposure value and exposure relationships. Inputs: aperture, exposure time, ISO. Results: the settings EV (aperture
 * and time only) and, separately, its ISO-normalized equivalent EV100. The exposure can be held while one named setting
 * is solved from the other two, the current settings can be captured as a reference to compare against in stops, and
 * the equivalent aperture and time pairs can be applied with a click. No sensor or lens data is involved, so none is shown.
 * <p>
 * Exposure step (1/3 EV by default, 1/2 EV, 1 EV or Exact) decides which settings can be applied: in a standard step the
 * sliders, typed values, the solved setting, the scales and the equivalent pairs all use standard stops
 * ({@link ExposureStops}); Exact keeps continuous values. The solving itself is {@link ExposureSolver}, shared with the
 * command line. While holding, the target EV100 stays as captured; the result is never fed back into it, so moving
 * settings back and forth cannot drift.
 */
final class ExposureMode extends CalculatorMode {
    /** The three exposure settings, in the order of the diagram's scales. */
    enum Setting {
        APERTURE, SHUTTER, ISO;
        Parameter parameter() { return Parameter.valueOf(name()); }
    }
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final int EQUIVALENTS_EACH_SIDE = 3;

    private final CalculatorField aperture, shutter, iso;
    private final ToggleGroup stepChoice = new ToggleGroup();
    private final Map<Step, ToggleButton> stepButtons = new EnumMap<>(Step.class);
    private final ToggleGroup holdChoice = new ToggleGroup();
    private final ToggleButton holdOff = new ToggleButton();
    private final Map<Setting, ToggleButton> holdButtons = new EnumMap<>(Setting.class);
    private final Label holdNote = FeaturePane.label("", "calc-note"), referenceNote = FeaturePane.label("", "calc-note");
    private final Label equivalentsTitle = FeaturePane.label("", "section-title");
    private final Button clearReference;
    private final List<Button> pairs = new ArrayList<>();
    private final List<Equivalent> pairValues = new ArrayList<>();
    private final Tile settingsEv, ev100, relative, deviation;
    private final ExposureDiagram diagram;
    private Step step = Step.THIRD;
    /** The setting being solved while the exposure is held, or null. */
    private Setting held;
    /** The target EV100 while holding: captured when the hold starts, changed only by starting a new hold. */
    private double heldEv100;
    private OptionalDouble reference = OptionalDouble.empty();
    private String referenceSettings = "";
    private double currentEv100 = Double.NaN;
    /** The last calculated result, for the details section; null while no result is available. */
    private Solution last;

    ExposureMode(Context context) {
        super(context, CalculationForm.Tool.EXPOSURE);
        aperture = aperture(8);
        shutter = shutter("shutter", "calc.input.shutter", 1 / 125.0);
        iso = field("iso", "calc.input.iso", ExposureStops.scale(Parameter.ISO, Step.THIRD), Parameter.ISO.min, Parameter.ISO.max, 100, CalculatorField.Format.NUMBER);

        // Exposure step, above the settings it governs.
        FlowPane steps = new FlowPane(2, 2); steps.setId("calc-step"); steps.getStyleClass().add("calc-segments");
        for (Step each : Step.values()) {
            var button = new ToggleButton(); button.setId("calc-step-" + each.name()); button.getStyleClass().setAll("segment"); button.setToggleGroup(stepChoice);
            texts.text(button.textProperty(), each.labelKey());
            button.setOnAction(event -> { button.setSelected(true); step(each); });
            stepButtons.put(each, button); steps.getChildren().add(button);
        }
        stepButtons.get(step).setSelected(true);
        controls.getChildren().add(0, new VBox(5, texts.label("calc.exposure.step", "section-title"), steps));
        configure();

        // Hold: explicit about which one setting is solved; the other two stay exactly as the user set them.
        FlowPane holds = new FlowPane(2, 2); holds.setId("calc-hold"); holds.getStyleClass().add("calc-segments");
        holds.getChildren().add(segment(holdOff, "calc-hold-OFF", "calc.exposure.hold.off", null));
        for (Setting setting : Setting.values()) {
            var button = new ToggleButton(); holdButtons.put(setting, button);
            holds.getChildren().add(segment(button, "calc-hold-" + setting.name(), "calc.exposure.hold." + setting.name(), setting));
        }
        holdOff.setSelected(true); holdNote.setId("calc-hold-note");
        // The two notes say something only while a hold or a reference is in use; otherwise they take no room.
        for (Label note : List.of(holdNote, referenceNote)) { note.managedProperty().bind(note.textProperty().isNotEmpty()); note.visibleProperty().bind(note.managedProperty()); }
        controls.getChildren().add(new VBox(5, texts.label("calc.exposure.hold", "section-title"), holds, holdNote));

        Button capture = texts.button("calc.exposure.capture"); capture.setId("calc-reference-capture"); capture.getStyleClass().add("secondary-button");
        capture.setOnAction(event -> {
            if (Double.isNaN(currentEv100)) return;
            reference = OptionalDouble.of(currentEv100); referenceSettings = settingsText(); update();
        });
        clearReference = texts.button("calc.exposure.clearReference"); clearReference.setId("calc-reference-clear"); clearReference.getStyleClass().add("secondary-button");
        clearReference.setOnAction(event -> { reference = OptionalDouble.empty(); update(); });
        referenceNote.setId("calc-reference-note");
        // Stacked and wrapping, so a long caption (large text, a narrow column) is never cut short.
        capture.setWrapText(true); clearReference.setWrapText(true);
        controls.getChildren().add(new VBox(5, texts.label("calc.exposure.reference", "section-title"), new VBox(6, capture, clearReference), referenceNote));

        settingsEv = tile("settingsEv", "calc.exposure.settingsEv", true);
        ev100 = tile("ev100", "calc.exposure.ev100", false);
        relative = tile("relative", "calc.exposure.relative", false);
        // Only while a setting is solved: how far the applied stops are from the target.
        deviation = tile("deviation", "calc.exposure.deviation", false);
        deviation.managedProperty().bind(deviation.visibleProperty()); deviation.setVisible(false);
        diagram = new ExposureDiagram(localization);
        diagram.onAdjust((setting, value) -> { field(setting).set(value); update(); });

        FlowPane chips = new FlowPane(6, 6); chips.setId("calc-equivalents");
        for (int i = 0; i <= 2 * EQUIVALENTS_EACH_SIDE; i++) {
            int index = i;
            Button chip = new Button(); chip.setId("calc-equivalent-" + i); chip.getStyleClass().add("calc-chip"); chip.setTooltip(new Tooltip());
            chip.setOnAction(event -> {
                if (index >= pairValues.size()) return;
                var pair = pairValues.get(index);
                // Applied like any other change: a solved setting is solved again from the target.
                aperture.set(pair.aperture()); shutter.set(pair.shutter().applied()); update();
            });
            pairs.add(chip); chips.getChildren().add(chip);
        }
        equivalentsTitle.setId("calc-equivalents-title");
        VBox equivalents = new VBox(6, equivalentsTitle, chips);
        compose(diagram, equivalents);
    }

    private ToggleButton segment(ToggleButton button, String id, String key, Setting setting) {
        button.setId(id); button.getStyleClass().setAll("segment"); button.setToggleGroup(holdChoice);
        texts.text(button.textProperty(), key);
        button.setOnAction(event -> { button.setSelected(true); hold(setting); });
        return button;
    }
    private CalculatorField field(Setting setting) { return switch (setting) { case APERTURE -> aperture; case SHUTTER -> shutter; case ISO -> iso; }; }
    private String settingsText() {
        return localization.text("calc.exposure.settings", CalculatorPresentation.aperture(aperture.value().orElse(Double.NaN)),
            CalculatorPresentation.exposureTime(shutter.value().orElse(Double.NaN), localization), CalculatorPresentation.number(iso.value().orElse(Double.NaN), 6));
    }

    /** Gives the three fields the sliders and the snap rule of the current step. */
    private void configure() {
        for (Setting each : Setting.values()) {
            Parameter parameter = each.parameter(); Step current = step;
            var field = field(each);
            field.rescale(ExposureStops.scale(parameter, current));
            field.snap(current.standard() ? value -> ExposureStops.quantize(parameter, current, value).applied() : null,
                value -> ExposurePresentation.setting(parameter, value, localization));
        }
    }
    /**
     * Changes the exposure step. The settings the user controls keep their applied values, matched to the stops of the
     * new step (in Exact they stay exactly as they are); a solved setting is solved again from the unchanged target.
     */
    private void step(Step next) {
        if (next == step) return;
        step = next; configure();
        for (Setting each : Setting.values()) {
            var field = field(each);
            if (each != held && field.valid() && field.value().isPresent()) field.set(field.value().getAsDouble());
        }
        update();
    }

    /** Starts or stops holding the exposure. Holding needs a current exposure to hold; without one the choice returns to Off. */
    private void hold(Setting setting) {
        if (setting != null && Double.isNaN(currentEv100)) { holdOff.setSelected(true); setting = null; }
        // A new target only when a hold starts; switching the solved setting keeps the target.
        if (setting != null && held == null) heldEv100 = currentEv100;
        held = setting;
        for (Setting each : Setting.values()) field(each).automatic(each == held);
        update();
    }

    @Override protected void compute() {
        currentEv100 = Double.NaN; last = null;
        deviation.setVisible(held != null);
        for (Setting each : Setting.values()) field(each).note("");
        equivalentsTitle.setText(ExposurePresentation.equivalentsTitle(step, localization));
        describe();
        // The pairs belong to a current exposure; without one they cannot be applied.
        pairs.forEach(chip -> chip.setDisable(true));
        // Only the named setting is solved, from the two the user controls, so that EV100 stays at the held value.
        var free = new ArrayList<CalculatorField>();
        for (Setting each : Setting.values()) if (each != held) free.add(field(each));
        if (!usable(free.toArray(CalculatorField[]::new))) { describe(); return; }
        double n = held == Setting.APERTURE ? Double.NaN : aperture.value().orElseThrow(), t = held == Setting.SHUTTER ? Double.NaN : shutter.value().orElseThrow(),
            s = held == Setting.ISO ? Double.NaN : iso.value().orElseThrow();
        var request = ExposureSolver.Request.of(step, n, t, s);
        if (held != null) request = request.solving(held.parameter(), heldEv100);
        var outcome = ExposureSolver.solve(request, app::calculate);
        if (!outcome.isSuccess()) { unavailable(outcome.messages().toArray(LocalizedMessage[]::new)); describe(); return; }
        var solution = outcome.value().orElseThrow();
        last = solution;
        if (held != null) {
            var solved = field(held); solved.set(solution.value(held.parameter()));
            if (step.standard()) solved.note(ExposurePresentation.selection(solution.setting(held.parameter()), localization));
        }
        n = solution.value(Parameter.APERTURE); t = solution.value(Parameter.SHUTTER); s = solution.value(Parameter.ISO);
        double ev = solution.settingsEv(), normalized = solution.ev100();
        currentEv100 = normalized;
        settingsEv.show(CalculatorPresentation.decimals(ev, 2), localization.text("calc.exposure.settingsEvDetail",
            CalculatorPresentation.aperture(n), CalculatorPresentation.exposureTime(t, localization)));
        ev100.show(CalculatorPresentation.decimals(normalized, 2), localization.text("calc.exposure.ev100Detail", CalculatorPresentation.number(s, 6)));
        // More exposure than the reference means these settings suit a darker scene: a lower EV100.
        OptionalDouble stops = reference.isPresent() ? OptionalDouble.of(reference.getAsDouble() - normalized) : OptionalDouble.empty();
        if (stops.isPresent()) {
            double difference = stops.getAsDouble();
            relative.show(localization.text("calc.exposure.relativeValue", CalculatorPresentation.signedStops(difference)),
                localization.text(difference > 0.05 ? "calc.exposure.moreThanReference" : difference < -0.05 ? "calc.exposure.lessThanReference" : "calc.exposure.sameAsReference"));
        } else relative.show(CalculatorPresentation.NONE, localization.text("calc.exposure.noReference"));
        // Deviation from the target: actual − target EV100, so + is less exposure; the words say which.
        if (solution.solving()) deviation.show(ExposurePresentation.signedEv(solution.deviation()), ExposurePresentation.deviationText(solution, localization));

        showPairs(ExposureSolver.equivalents(solution, EQUIVALENTS_EACH_SIDE));
        diagram.show(n, t, s, stops, held, step);
        describe();
        // Ordinary rounding to a stop is normal; only a setting stopped at the end of its range is a caution.
        if (solution.limited()) available(localization.text("calc.exposure.caution.limit",
            ExposurePresentation.setting(solution.solved(), solution.value(solution.solved()), localization), ExposurePresentation.signedEv(solution.deviation()),
            ExposurePresentation.direction(solution.deviation(), localization)));
        else available();
    }
    /** The equivalent pairs: in a standard step only those within the stops; in Exact all, those out of range disabled. */
    private void showPairs(List<Equivalent> equivalents) {
        pairValues.clear();
        for (Equivalent pair : equivalents) if (pair.supported() || !step.standard()) pairValues.add(pair);
        for (int i = 0; i < pairs.size(); i++) {
            Button chip = pairs.get(i);
            boolean shown = i < pairValues.size(); chip.setVisible(shown); chip.setManaged(shown);
            if (!shown) continue;
            var pair = pairValues.get(i);
            String fNumber = CalculatorPresentation.aperture(pair.aperture()), time = CalculatorPresentation.exposureTime(pair.shutter().applied(), localization);
            chip.setText(localization.text(pair.current() ? "calc.exposure.pairCurrent" : "calc.exposure.pair", fNumber, time));
            chip.pseudoClassStateChanged(CURRENT, pair.current());
            chip.setDisable(!pair.supported());
            chip.getTooltip().setText(localization.text("calc.exposure.pairTip") + "\n" + localization.text("calc.exposure.pairDetail",
                ExposurePresentation.precise(Parameter.SHUTTER, pair.shutter().exact(), localization), time,
                CalculatorPresentation.decimals(pair.ev100(), 2), ExposurePresentation.signedEv(pair.deviation())));
        }
    }
    /** What is being held and what the reference is; nothing while neither is in use. */
    private void describe() {
        holdNote.setText(held == null ? "" : localization.text("calc.exposure.holdOn", CalculatorPresentation.decimals(heldEv100, 2)));
        referenceNote.setText(reference.isEmpty() ? ""
            : localization.text("calc.exposure.referenceSet", referenceSettings, CalculatorPresentation.decimals(reference.getAsDouble(), 2)));
        clearReference.setDisable(reference.isEmpty());
    }
    /**
     * Formula, then how values are applied in this step; while a setting is solved, also its precise solution, the
     * applied value and the deviation, with more digits than the working view.
     */
    @Override protected List<String> details() {
        var lines = new ArrayList<String>(List.of(localization.text("calc.details.exposure.formula"),
            step.standard() ? localization.text("calc.details.exposure.standard", localization.text(step.labelKey())) : localization.text("calc.details.exposure.assumption")));
        if (last != null && last.solving()) {
            var solved = last.setting(last.solved());
            String name = localization.text(solved.parameter().nameKey()), exact = ExposurePresentation.precise(solved.parameter(), solved.exact(), localization);
            lines.add(!step.standard() ? localization.text("calc.details.exposure.solvedExact", name, exact)
                : localization.text(solved.limited() ? "calc.details.exposure.solvedLimit" : "calc.details.exposure.solved", name, exact,
                    ExposurePresentation.setting(solved.parameter(), solved.applied(), localization), ExposurePresentation.signedEv(solved.shift(), 4)));
            lines.add(localization.text("calc.details.exposure.target", CalculatorPresentation.decimals(last.target(), 4), CalculatorPresentation.decimals(last.ev100(), 4),
                ExposurePresentation.signedEv(last.deviation(), 4)));
        }
        return lines;
    }

    @Override protected void resetExtras() {
        held = null; holdOff.setSelected(true);
        for (Setting each : Setting.values()) field(each).automatic(false);
        reference = OptionalDouble.empty(); referenceSettings = "";
        // Reset restores the default step, as the depth of field page restores manual focus.
        step = Step.THIRD; stepButtons.get(step).setSelected(true); configure();
    }
    @Override protected boolean extrasChanged() { return held != null || reference.isPresent() || step != Step.THIRD; }
    Setting held() { return held; }
    Step step() { return step; }
    /** The result shown, for tests that compare it with the command line; null while unavailable. */
    Solution solution() { return last; }
    /** The equivalent pairs shown, in order. */
    List<Equivalent> pairsShown() { return List.copyOf(pairValues); }
}
