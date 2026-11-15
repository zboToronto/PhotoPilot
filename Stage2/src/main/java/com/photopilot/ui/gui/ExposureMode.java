package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.ExposurePair;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.service.TechnicalCalculator;
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
 */
final class ExposureMode extends CalculatorMode {
    /** The three exposure settings, in the order of the diagram's scales. */
    enum Setting { APERTURE, SHUTTER, ISO }
    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final int EQUIVALENTS_EACH_SIDE = 3;

    private final CalculatorField aperture, shutter, iso;
    private final ToggleGroup holdChoice = new ToggleGroup();
    private final ToggleButton holdOff = new ToggleButton();
    private final Map<Setting, ToggleButton> holdButtons = new EnumMap<>(Setting.class);
    private final Label holdNote = FeaturePane.label("", "calc-note"), referenceNote = FeaturePane.label("", "calc-note");
    private final Button clearReference;
    private final List<Button> pairs = new ArrayList<>();
    private final List<ExposurePair> pairValues = new ArrayList<>();
    private final Tile settingsEv, ev100, relative;
    private final ExposureDiagram diagram;
    /** The setting being solved while the exposure is held, or null. */
    private Setting held;
    private double heldEv100;
    private OptionalDouble reference = OptionalDouble.empty();
    private String referenceSettings = "";
    private double currentEv100 = Double.NaN;

    ExposureMode(Context context) {
        super(context, CalculationForm.Tool.EXPOSURE);
        aperture = aperture(8);
        shutter = shutter("shutter", "calc.input.shutter", 1 / 125.0);
        iso = field("iso", "calc.input.iso", Scale.nominal(CalculatorPresentation.ISOS), 1, 4_096_000, 100, CalculatorField.Format.NUMBER);

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
        diagram = new ExposureDiagram(localization);
        diagram.onAdjust((setting, value) -> { field(setting).set(value); update(); });

        FlowPane chips = new FlowPane(6, 6); chips.setId("calc-equivalents");
        var pairTip = new Tooltip(); texts.text(pairTip.textProperty(), "calc.exposure.pairTip");
        for (int i = 0; i <= 2 * EQUIVALENTS_EACH_SIDE; i++) {
            int index = i;
            Button chip = new Button(); chip.setId("calc-equivalent-" + i); chip.getStyleClass().add("calc-chip"); chip.setTooltip(pairTip);
            chip.setOnAction(event -> {
                if (index >= pairValues.size()) return;
                var pair = pairValues.get(index);
                aperture.set(pair.fNumber()); shutter.set(pair.seconds()); update();
            });
            pairs.add(chip); chips.getChildren().add(chip);
        }
        VBox equivalents = new VBox(6, texts.label("calc.exposure.equivalents", "section-title"), chips);
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

    /** Starts or stops holding the exposure. Holding needs a current exposure to hold; without one the choice returns to Off. */
    private void hold(Setting setting) {
        if (setting != null && Double.isNaN(currentEv100)) { holdOff.setSelected(true); setting = null; }
        if (setting != null && held == null) heldEv100 = currentEv100;
        held = setting;
        for (Setting each : Setting.values()) field(each).automatic(each == held);
        update();
    }

    @Override protected void compute() {
        currentEv100 = Double.NaN;
        describe();
        // The pairs belong to a current exposure; without one they cannot be applied.
        pairs.forEach(chip -> chip.setDisable(true));
        if (held != null) {
            // Only the named setting is solved, from the two the user controls, so that EV100 stays at the held value.
            var free = new ArrayList<CalculatorField>();
            for (Setting each : Setting.values()) if (each != held) free.add(field(each));
            if (!usable(free.toArray(CalculatorField[]::new))) return;
            double n = aperture.value().orElse(Double.NaN), t = shutter.value().orElse(Double.NaN), s = iso.value().orElse(Double.NaN);
            double solved = switch (held) {
                case SHUTTER -> CalculatorPresentation.heldShutter(heldEv100, n, s);
                case APERTURE -> CalculatorPresentation.heldAperture(heldEv100, t, s);
                case ISO -> CalculatorPresentation.heldIso(heldEv100, n, t);
            };
            var target = field(held);
            if (!(solved >= target.min() && solved <= target.max())) {
                unavailable(LocalizedMessage.of("calc.exposure.holdRange", LocalizedMessage.of(target.captionKey()))); describe(); return;
            }
            target.set(solved);
        }
        if (!usable(aperture, shutter, iso)) { describe(); return; }
        double n = aperture.value().orElseThrow(), t = shutter.value().orElseThrow(), s = iso.value().orElseThrow();
        var result = calculate(new CalculationInput(CalculationKind.EXPOSURE_ND, Map.of("aperture", n, "shutter", t, "iso", s),
            Map.of("aperture", "f-number", "shutter", "s", "iso", "ISO")));
        if (result.isEmpty()) { describe(); return; }
        double ev = result.get().values().get(TechnicalCalculator.SETTING_EV), normalized = result.get().values().get(TechnicalCalculator.EV100);
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

        pairValues.clear(); pairValues.addAll(CalculatorPresentation.equivalents(ev, n, EQUIVALENTS_EACH_SIDE));
        for (int i = 0; i < pairs.size(); i++) {
            Button chip = pairs.get(i);
            boolean shown = i < pairValues.size(); chip.setVisible(shown); chip.setManaged(shown);
            if (!shown) continue;
            var pair = pairValues.get(i);
            boolean current = Math.abs(pair.fNumber() - n) <= 0.005 * n;
            chip.setText(localization.text(current ? "calc.exposure.pairCurrent" : "calc.exposure.pair",
                CalculatorPresentation.aperture(pair.fNumber()), CalculatorPresentation.exposureTime(pair.seconds(), localization)));
            chip.pseudoClassStateChanged(CURRENT, current);
            chip.setDisable(pair.seconds() < shutter.min() || pair.seconds() > shutter.max());
        }
        diagram.show(n, t, s, stops, held);
        describe();
        available();
    }
    /** What is being held and what the reference is; nothing while neither is in use. */
    private void describe() {
        holdNote.setText(held == null ? "" : localization.text("calc.exposure.holdOn", CalculatorPresentation.decimals(heldEv100, 2)));
        referenceNote.setText(reference.isEmpty() ? ""
            : localization.text("calc.exposure.referenceSet", referenceSettings, CalculatorPresentation.decimals(reference.getAsDouble(), 2)));
        clearReference.setDisable(reference.isEmpty());
    }
    @Override protected List<String> details() {
        return List.of(localization.text("calc.details.exposure.formula"), localization.text("calc.details.exposure.assumption"));
    }

    @Override protected void resetExtras() {
        held = null; holdOff.setSelected(true);
        for (Setting each : Setting.values()) field(each).automatic(false);
        reference = OptionalDouble.empty(); referenceSettings = "";
    }
    @Override protected boolean extrasChanged() { return held != null || reference.isPresent(); }
    Setting held() { return held; }
}
