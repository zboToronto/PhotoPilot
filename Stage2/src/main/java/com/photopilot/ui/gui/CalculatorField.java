package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.i18n.LocalizedMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.DoubleFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One calculator input: a caption with its unit, an optional slider on a photographic scale and a text field for a
 * precise value. Both edit the same number and stay in step without feeding each other's change back. The slider
 * reports every position while it is dragged. A typed value is used as soon as it is valid; partial or invalid text
 * is left exactly as typed and explained on the line below, never corrected or replaced.
 * <p>
 * The value is kept in the calculator's unit (seconds, metres, ...). {@link #display} only changes what is shown and
 * typed, so switching between metres and feet converts the text and leaves the value where it was.
 * <p>
 * A field can be given a {@link #snap} rule (the exposure calculator's standard stops): then every value it takes, typed,
 * slid or set, is the value the rule applies. Text being typed is not rewritten; while it differs from the applied value
 * a note under the field names the value in use, and pressing Enter or leaving the field writes the applied value into it.
 * Without a rule (every other calculator) values are used as typed, as before.
 */
final class CalculatorField extends VBox {
    /** How the text is read and written. */
    enum Format { NUMBER, SHUTTER, WHOLE }
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    private final FxLocalizer texts;
    private final LocalizationService localization;
    private final String captionKey;
    private Scale scale;
    private final double min, max, initial;
    private final Format format;
    private final boolean optional;
    private final Label caption = FeaturePane.label("", "section-title"), unitLabel = FeaturePane.label("", "calc-field-unit"), error = FeaturePane.label("", "calc-field-error");
    private final Label hint = FeaturePane.label("", "calc-note");
    private final TextField input = new TextField();
    private final Slider slider;
    private final HBox heading = new HBox(6);
    private final List<Runnable> listeners = new ArrayList<>();
    private Supplier<String> unit = () -> "";
    private String automaticCaption = "calc.field.solved";
    private double factor = 1;
    private double value;
    private boolean present, valid = true, updating, automatic;
    private LocalizedMessage problem;
    /** Maps a value to the value applied (null: used as typed), and names an applied value for the note. */
    private DoubleUnaryOperator snap;
    private DoubleFunction<String> snapText = applied -> "";
    /** True while typed text holds a value other than the applied one (until Enter or leaving the field). */
    private boolean pending;
    private String note = "";

    /**
     * @param scale slider scale in the calculator's unit, or null for a field without a slider
     * @param optional true when an empty field means "not known" rather than an error
     */
    CalculatorField(FxLocalizer texts, String id, String captionKey, Scale scale, double min, double max, OptionalDouble initial, Format format, boolean optional) {
        super(5);
        this.texts = texts; this.localization = texts.service(); this.captionKey = captionKey; this.scale = scale; this.min = min; this.max = max;
        this.initial = initial.orElse(Double.NaN); this.format = format; this.optional = optional;
        getStyleClass().add("calc-field"); setMinWidth(0);
        caption.setLabelFor(input); caption.setMinWidth(0);
        unitLabel.setMinWidth(Region.USE_PREF_SIZE);
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS);
        heading.getChildren().setAll(caption, space, unitLabel); heading.setAlignment(Pos.BASELINE_LEFT);
        input.setId(id); input.setPrefColumnCount(7); input.setMinWidth(72); texts.inputMenu(input);
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT);
        if (scale != null) {
            slider = new Slider(0, scale.positions(), 0);
            slider.setId(id + "-slider"); slider.getStyleClass().add("appearance-slider"); slider.setMinWidth(90);
            slider.setBlockIncrement(scale.snaps() ? 1 : scale.positions() / 100);
            if (scale.snaps()) { slider.setMajorTickUnit(1); slider.setMinorTickCount(0); slider.setSnapToTicks(true); }
            HBox.setHgrow(slider, Priority.ALWAYS); input.setMaxWidth(104);
            slider.valueProperty().addListener((property, old, position) -> fromSlider(position.doubleValue()));
            row.getChildren().addAll(slider, input);
        } else {
            slider = null; input.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(input, Priority.ALWAYS);
            row.getChildren().add(input);
        }
        error.setId(id + "-error"); error.managedProperty().bind(error.textProperty().isNotEmpty()); error.visibleProperty().bind(error.managedProperty());
        hint.setId(id + "-hint"); hint.managedProperty().bind(hint.textProperty().isNotEmpty()); hint.visibleProperty().bind(hint.managedProperty());
        getChildren().addAll(heading, row, error, hint);
        input.textProperty().addListener((property, old, text) -> fromText(text));
        // Enter or leaving the field commits typed text: with a snap rule, the applied value replaces it.
        input.setOnAction(event -> commit());
        input.focusedProperty().addListener((property, was, focused) -> { if (!focused) commit(); });
        // The caption, unit, accessible names and any validation message follow the language.
        texts.onRefresh(this::describe);
        present = initial.isPresent(); value = this.initial;
        show();
    }

    // ---- State ---------------------------------------------------------------------------------------------------

    /** The current value in the calculator's unit; empty while the text is invalid or an optional field is blank. */
    OptionalDouble value() { return valid && present ? OptionalDouble.of(value) : OptionalDouble.empty(); }
    /** False only while the text cannot be used (an empty optional field is valid). */
    boolean valid() { return valid; }
    /** The last usable value, also while the text shown cannot be used; empty when the field holds no value at all. */
    OptionalDouble lastValue() { return present ? OptionalDouble.of(value) : OptionalDouble.empty(); }
    /** What is wrong with the text, for the calculator's summary line. */
    LocalizedMessage problem() { return problem; }
    String captionKey() { return captionKey; }
    /** True when the user has moved the value away from where the session started. */
    boolean changed() { return !valid || present != !Double.isNaN(initial) || present && Math.abs(value - initial) > 1e-12 * Math.max(1, Math.abs(initial)); }
    void onChange(Runnable listener) { listeners.add(listener); }

    /**
     * Sets the value from outside (a diagram drag, a solved parameter, a reset), through the snap rule if there is one.
     * Listeners are not notified.
     */
    void set(double next) { value = applied(next); present = true; valid = true; problem = null; pending = false; show(); }
    void clear() { present = false; valid = true; problem = null; pending = false; show(); }
    /**
     * Applies values through a rule from now on (null: values are used as typed) and names an applied value for the
     * note shown while typed text differs from it. The current value is not changed; the caller sets it again if needed.
     */
    void snap(DoubleUnaryOperator rule, DoubleFunction<String> appliedText) { snap = rule; snapText = appliedText; pending = false; mark(); }
    /** Gives the slider another scale (positions, snapping and arrow-key step) and moves it to the current value. */
    void rescale(Scale next) {
        if (slider == null) return;
        scale = next;
        updating = true;
        try {
            slider.setSnapToTicks(false);
            slider.setMax(next.positions());
            slider.setBlockIncrement(next.snaps() ? 1 : next.positions() / 100);
            if (next.snaps()) { slider.setMajorTickUnit(1); slider.setMinorTickCount(0); slider.setSnapToTicks(true); }
            if (present) slider.setValue(next.position(value));
        } finally { updating = false; }
    }
    /** A short line under the field (why a solved value is what it is); empty for none. Typed text and errors come first. */
    void note(String text) { note = text == null ? "" : text; mark(); }
    private double applied(double next) { return snap == null ? next : snap.applyAsDouble(next); }
    /** Enter or leaving the field: with a snap rule, typed text is replaced by the applied value it stands for. */
    void commit() {
        if (snap == null || !valid || !present) return;
        String applied = text(value);
        if (!applied.equals(input.getText())) {
            updating = true;
            try { input.setText(applied); } finally { updating = false; }
        }
        pending = false; mark();
    }
    /**
     * Puts text back as if it had been typed: read, validated, explained if unusable and reported to listeners, even
     * when the field already shows exactly that text.
     */
    void retype(String text) { if (text.equals(input.getText())) fromText(text); else input.setText(text); }
    void reset() { if (Double.isNaN(initial)) clear(); else set(initial); }
    /** Shows and reads the value in another unit: one display unit is {@code unitsOfCalculator} calculator units. */
    void display(double unitsOfCalculator, Supplier<String> unitSymbol) {
        factor = unitsOfCalculator; unit = unitSymbol;
        // The text is rewritten from the last valid value; text that could not be read has nothing to convert. A required
        // field without any value stays unusable: there is nothing to show in the other unit either.
        if (!valid && (present || optional)) { valid = true; problem = null; }
        show(); describe();
    }
    void unit(Supplier<String> unitSymbol) { unit = unitSymbol; describe(); }
    /** A value the calculator sets itself (a held exposure): shown and kept current, not editable while it is. */
    void automatic(boolean value) { automatic(value, "calc.field.solved"); }
    /** As {@link #automatic(boolean)}, captioned by a pattern taking the field's name as {0} (why it is set automatically). */
    void automatic(boolean value, String captionPatternKey) {
        automatic = value; automaticCaption = captionPatternKey; input.setEditable(!value); if (slider != null) slider.setDisable(value);
        pseudoClassStateChanged(PseudoClass.getPseudoClass("automatic"), value); describe();
    }
    boolean automatic() { return automatic; }
    /** A control placed beside the unit, such as a unit selector. */
    void accessory(Node node) { heading.getChildren().add(node); }
    TextField input() { return input; }
    double min() { return min; }
    double max() { return max; }

    // ---- Slider and text ---------------------------------------------------------------------------------------------

    private void fromSlider(double position) {
        if (updating) return;
        double next = scale.value(scale.snaps() ? Math.rint(position) : position);
        // A continuous scale gives round numbers in the unit the user reads (3.2 ft, not 3.21869 ft).
        if (!scale.snaps() && factor != 1) next = CalculatorPresentation.significant(next / factor, 3) * factor;
        next = applied(Math.max(min, Math.min(max, next)));
        if (present && valid && next == value && !pending) return;
        value = next; present = true; valid = true; problem = null; pending = false;
        updating = true;
        try { input.setText(text(value)); } finally { updating = false; }
        mark(); notifyListeners();
    }
    private void fromText(String text) {
        if (updating) return;
        pending = false;
        try {
            if (text == null || text.isBlank()) {
                if (!optional) throw new LocalizedException("calc.error.required");
                present = false; valid = true; problem = null;
            } else {
                double shown = CalculationForm.number(text, format == Format.SHUTTER), next = shown * factor;
                if (!Double.isFinite(next)) throw new LocalizedException("error.calc.number");
                if (format == Format.WHOLE && shown != Math.rint(shown)) throw new LocalizedException("calc.error.whole");
                if (next < min * (1 - 1e-12) || next > max * (1 + 1e-12)) throw new LocalizedException("calc.error.range", text(min), text(max));
                // With a snap rule the applied value is the rule's; the text stays as typed until it is committed.
                double applied = applied(next);
                value = applied; present = true; valid = true; problem = null;
                pending = snap != null && Math.abs(applied - next) > 1e-12 * Math.max(applied, next);
            }
        } catch (IllegalArgumentException invalid) {
            valid = false; problem = Result.failure(invalid).messages().getFirst();
        }
        if (valid && present && slider != null) {
            updating = true;
            try { slider.setValue(scale.position(value)); } finally { updating = false; }
        }
        mark(); notifyListeners();
    }
    private void show() {
        updating = true;
        try {
            input.setText(present ? text(value) : "");
            if (slider != null && present) slider.setValue(scale.position(value));
        } finally { updating = false; }
        mark();
    }
    private String text(double calculatorValue) {
        double shown = calculatorValue / factor;
        return switch (format) {
            case SHUTTER -> CalculatorPresentation.shutterField(shown);
            case WHOLE -> CalculatorPresentation.number(Math.rint(shown), 15);
            default -> CalculatorPresentation.fieldNumber(shown);
        };
    }
    private void mark() {
        input.pseudoClassStateChanged(INVALID, !valid);
        error.setText(valid || problem == null ? "" : problem.render(localization));
        hint.setText(!valid ? "" : pending ? localization.text("calc.field.applied", snapText.apply(value)) : note);
    }
    private void describe() {
        String symbol = unit.get(), name = localization.text(captionKey);
        caption.setText(automatic ? localization.text(automaticCaption, name) : name);
        unitLabel.setText(symbol);
        String accessible = symbol.isEmpty() ? name : localization.text("calc.fieldAccessible", name, symbol);
        input.setAccessibleText(accessible);
        if (slider != null) slider.setAccessibleText(accessible);
        mark();
    }
    private void notifyListeners() { List.copyOf(listeners).forEach(Runnable::run); }
}
