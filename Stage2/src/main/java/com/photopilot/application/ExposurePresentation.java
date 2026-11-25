package com.photopilot.application;

import com.photopilot.application.ExposureSolver.Equivalent;
import com.photopilot.application.ExposureSolver.Solution;
import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Quantized;
import com.photopilot.i18n.LocalizationService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Text of the exposure value calculator, shared by the GUI page and the command line so both show the same settings in
 * the same notation: f/18, 1/160 s, ISO 200 for applied settings; more digits for precise values; signed EV differences
 * that never read as zero when they are not. It rounds text only; values keep their precision. No JavaFX.
 */
public final class ExposurePresentation {
    private ExposurePresentation() { }

    /** An applied setting as a camera shows it: f/18, 1/160 s, ISO 200. */
    public static String setting(Parameter parameter, double value, LocalizationService localization) {
        return switch (parameter) {
            case APERTURE -> CalculatorPresentation.aperture(value);
            case SHUTTER -> CalculatorPresentation.exposureTime(value, localization);
            case ISO -> localization.text("calc.exposure.isoValue", CalculatorPresentation.number(value, 6));
        };
    }
    /**
     * A precise value with six significant digits: f/17.8885, ISO 198.373, 0.00630125 s; an exposure time below 0.3 s
     * also as an approximate fraction (≈1/158.7 s), which is only a reading aid.
     */
    public static String precise(Parameter parameter, double value, LocalizationService localization) {
        return switch (parameter) {
            case APERTURE -> "f/" + CalculatorPresentation.number(value, 6);
            case SHUTTER -> value < 0.3 ? CalculatorPresentation.number(value, 6) + " s (≈1/" + CalculatorPresentation.number(1 / value, 4) + " s)"
                : CalculatorPresentation.number(value, 6) + " s";
            case ISO -> localization.text("calc.exposure.isoValue", CalculatorPresentation.number(value, 6));
        };
    }
    /** A signed EV difference with two decimals (+0.01 EV, −0.03 EV); see {@link #signedEv(double, int)}. */
    public static String signedEv(double stops) { return signedEv(stops, 2); }
    /**
     * A signed EV difference with a number of decimals. A difference that would round to zero is shown with two
     * significant digits instead (+0.0012 EV), and only a difference within {@link ExposureStops#TIE} is 0 EV.
     */
    public static String signedEv(double stops, int decimals) {
        if (Double.isNaN(stops)) return CalculatorPresentation.NONE;
        if (Math.abs(stops) <= ExposureStops.TIE) return "0 EV";
        double size = Math.abs(stops);
        if (Double.isInfinite(size)) return (stops > 0 ? "+" : "−") + CalculatorPresentation.INFINITY + " EV";
        String digits = CalculatorPresentation.decimals(size, decimals);
        // Two significant digits in plain notation, however small (never the scientific notation of CalculationForm).
        if (new BigDecimal(digits).signum() == 0) digits = new BigDecimal(size).round(new MathContext(2, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString();
        return (stops > 0 ? "+" : "−") + digits + " EV";
    }
    /** What a deviation (actual − target EV100) means: less exposure, more exposure, or on target. */
    public static String direction(double deviation, LocalizationService localization) {
        return localization.text(Math.abs(deviation) <= ExposureStops.TIE ? "calc.exposure.deviationNone" : deviation > 0 ? "calc.exposure.deviationLess" : "calc.exposure.deviationMore");
    }
    /**
     * The words under a deviation while solving: its direction, after "Parameter limit reached" when the solved setting
     * stopped at an end stop (the case where the deviation is large and its direction matters most).
     */
    public static String deviationText(Solution solution, LocalizationService localization) {
        String direction = direction(solution.deviation(), localization);
        return solution.limited() ? localization.text("calc.exposure.limitDetail", direction) : direction;
    }
    /** How the solved setting was chosen: the nearest stop, the end stop at a limit, or the precise value. */
    public static String selection(Quantized solved, LocalizationService localization) {
        return localization.text(!solved.step().standard() ? "calc.exposure.noRounding" : solved.limited() ? "calc.exposure.limit" : "calc.exposure.nearest");
    }
    /** The title of the equivalent pairs: approximate in a standard step, where each time is a stop. */
    public static String equivalentsTitle(ExposureStops.Step step, LocalizationService localization) {
        return localization.text(step.standard() ? "calc.exposure.equivalentsApprox" : "calc.exposure.equivalents");
    }

    // ---- Command-line report ---------------------------------------------------------------------------------------

    private static final String RULE = "-".repeat(44);
    private static final int LABEL = 28;

    /** The structured report of {@code calc exposure ... step=...}: plain text, readable without colour or a terminal. */
    public static String report(Solution solution, List<Equivalent> equivalents, LocalizationService localization) {
        var out = new StringBuilder();
        out.append(localization.text("cli.calc.exposure.title")).append("\n\n");
        line(out, localization.text("calc.exposure.step"), localization.text(solution.step().labelKey()));
        line(out, localization.text("cli.calc.exposure.solve"), solution.solving() ? localization.text("calc.exposure.hold." + holdKey(solution.solved())) : localization.text("calc.exposure.hold.off"));

        section(out, localization.text("cli.calc.exposure.applied"));
        line(out, localization.text("calc.input.aperture"), CalculatorPresentation.aperture(solution.value(Parameter.APERTURE)));
        line(out, localization.text("calc.input.shutter"), CalculatorPresentation.exposureTime(solution.value(Parameter.SHUTTER), localization));
        line(out, localization.text("calc.input.iso"), CalculatorPresentation.number(solution.value(Parameter.ISO), 6));

        section(out, localization.text("cli.calc.exposure.results"));
        line(out, localization.text("calc.output.settingEV"), CalculatorPresentation.decimals(solution.settingsEv(), 2));
        if (solution.solving()) {
            line(out, localization.text("calc.exposure.target"), CalculatorPresentation.decimals(solution.target(), 2));
            line(out, localization.text("calc.exposure.actual"), CalculatorPresentation.decimals(solution.ev100(), 2));
            line(out, localization.text("calc.exposure.deviation"), signedEv(solution.deviation()) + " (" + deviationText(solution, localization) + ")");
        } else line(out, localization.text("calc.output.ev100"), CalculatorPresentation.decimals(solution.ev100(), 2));

        if (solution.solving()) {
            Quantized solved = solution.setting(solution.solved());
            section(out, localization.text("cli.calc.exposure.solution"));
            line(out, localization.text("calc.exposure.exact"), precise(solved.parameter(), solved.exact(), localization));
            // In Exact the precise solution is what is applied: no stop, so no "applied standard value" line.
            if (solution.step().standard()) line(out, localization.text("calc.exposure.appliedValue"), setting(solved.parameter(), solved.applied(), localization));
            line(out, localization.text("cli.calc.exposure.selection"), selection(solved, localization));
            if (solution.step().standard()) line(out, localization.text("cli.calc.exposure.quantization"), signedEv(solved.shift(), 4));
        }
        // Settings typed between two stops: what was entered and what is applied instead.
        var moved = java.util.Arrays.stream(Parameter.values()).filter(parameter -> parameter != solution.solved() && solution.setting(parameter).moved()).toList();
        if (!moved.isEmpty()) {
            section(out, localization.text("cli.calc.exposure.matched"));
            for (Parameter parameter : moved) line(out, localization.text(parameter.nameKey()),
                precise(parameter, solution.setting(parameter).exact(), localization) + " → " + setting(parameter, solution.value(parameter), localization));
        }

        section(out, equivalentsTitle(solution.step(), localization));
        var shown = equivalents.stream().filter(pair -> pair.supported() || !solution.step().standard()).toList();
        if (shown.isEmpty()) out.append(localization.text("cli.calc.exposure.noPairs")).append('\n');
        for (Equivalent pair : shown) {
            out.append(pad(CalculatorPresentation.aperture(pair.aperture()), 10)).append(pad(CalculatorPresentation.exposureTime(pair.shutter().applied(), localization), 12))
                .append("EV100 ").append(CalculatorPresentation.decimals(pair.ev100(), 2));
            if (solution.step().standard()) out.append("  ").append(signedEv(pair.deviation()));
            if (pair.current()) out.append("  (").append(localization.text("cli.calc.exposure.current")).append(')');
            if (!pair.supported()) out.append("  (").append(localization.text("cli.calc.exposure.outside")).append(')');
            out.append('\n');
        }
        out.append('\n').append(localization.text(solution.step().standard() ? "cli.calc.exposure.genericStops" : "cli.calc.exposure.exactNote"));
        return out.toString();
    }
    private static String holdKey(Parameter parameter) { return parameter.name(); }
    private static void section(StringBuilder out, String title) { out.append('\n').append(title).append('\n').append(RULE).append('\n'); }
    private static void line(StringBuilder out, String label, String value) { out.append(pad(label + ":", LABEL)).append(value).append('\n'); }
    /** Pads to a column, counting CJK characters as two columns so Chinese labels line up too. */
    static String pad(String text, int width) {
        int columns = 0;
        for (int i = 0; i < text.length(); i++) columns += Character.UnicodeScript.of(text.codePointAt(i)) == Character.UnicodeScript.HAN || text.charAt(i) >= '＀' && text.charAt(i) <= '￯' ? 2 : 1;
        return text + " ".repeat(Math.max(1, width - columns));
    }
}
