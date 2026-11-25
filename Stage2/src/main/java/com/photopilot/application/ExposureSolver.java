package com.photopilot.application;

import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Quantized;
import com.photopilot.application.ExposureStops.Step;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.model.CalculationResult;
import com.photopilot.service.PhotoFormulas;
import com.photopilot.service.TechnicalCalculator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The exposure value calculator's one solving path, shared by the GUI page and the command line:
 * <ol>
 *   <li>the settings the user controls are matched to the stops of the chosen step ({@link ExposureStops#quantize});</li>
 *   <li>when one setting is solved, its precise value is calculated from the target EV100 and the two applied settings,
 *       then matched to a stop the same way;</li>
 *   <li>the settings EV and EV100 of the applied settings come from the existing validated calculation
 *       ({@link TechnicalCalculator} through the facade), so they always describe what is applied;</li>
 *   <li>the deviation is actual EV100 − target EV100 (positive: less exposure than the target).</li>
 * </ol>
 * The target is never replaced by the result, so repeated changes cannot accumulate rounding. In {@link Step#EXACT}
 * nothing is rounded and a solution outside the accepted range is refused, as before. In a standard step a solution
 * beyond the stops is set to the end stop and reported as {@link Quantized#limited()}, with its remaining deviation.
 */
public final class ExposureSolver {
    private ExposureSolver() { }

    /**
     * What is asked: the step, the three settings (the solved one is ignored and may be NaN) and, when one setting is
     * solved, which one and the target EV100 it has to hold.
     */
    public record Request(Step step, double aperture, double shutter, double iso, Parameter solve, double target) {
        public static Request of(Step step, double aperture, double shutter, double iso) { return new Request(step, aperture, shutter, iso, null, Double.NaN); }
        public Request solving(Parameter parameter, double targetEv100) { return new Request(step, aperture, shutter, iso, parameter, targetEv100); }
        double value(Parameter parameter) { return switch (parameter) { case APERTURE -> aperture; case SHUTTER -> shutter; case ISO -> iso; }; }
    }

    /**
     * The applied settings and what they give.
     * @param solved the solved setting, or null when nothing is solved
     * @param target the target EV100 while a setting is solved, otherwise NaN
     */
    public record Solution(Step step, Quantized aperture, Quantized shutter, Quantized iso, double settingsEv, double ev100, Parameter solved, double target) {
        public Quantized setting(Parameter parameter) { return switch (parameter) { case APERTURE -> aperture; case SHUTTER -> shutter; case ISO -> iso; }; }
        /** The applied value of a setting (what the camera is set to and what was calculated with). */
        public double value(Parameter parameter) { return setting(parameter).applied(); }
        public boolean solving() { return solved != null; }
        /** Actual EV100 − target EV100 while solving (positive: less exposure than the target), otherwise NaN. */
        public double deviation() { return solving() ? ev100 - target : Double.NaN; }
        /** True when the solved setting had to stop at the end of its stops. */
        public boolean limited() { return solving() && setting(solved).limited(); }
        /** The exposure the equivalent pairs keep: the target while solving, otherwise the current EV100. */
        public double reference() { return solving() ? target : ev100; }
    }

    /** One aperture with the exposure time that keeps the reference EV100 at the same ISO. */
    public record Equivalent(double aperture, Quantized shutter, double ev100, double deviation, boolean current, boolean supported) { }

    /**
     * Applies the step to the request and calculates the result through {@code calculator} (the facade's calculation).
     * Refusals: a setting that is not a usable number or outside its accepted range, a missing target, and in EXACT a
     * solution outside the accepted range.
     */
    public static Result<Solution> solve(Request request, Function<CalculationInput, Result<CalculationResult>> calculator) {
        try {
            var applied = new java.util.EnumMap<Parameter, Quantized>(Parameter.class);
            for (Parameter parameter : Parameter.values()) {
                if (parameter == request.solve()) continue;
                double value = request.value(parameter);
                if (!Double.isFinite(value) || value <= 0) throw new LocalizedException("calc.status.input", LocalizedMessage.of(parameter.nameKey()), LocalizedMessage.of("error.calc.number"));
                if (!parameter.accepts(value)) throw new LocalizedException("calc.status.input", LocalizedMessage.of(parameter.nameKey()), range(parameter));
                applied.put(parameter, ExposureStops.quantize(parameter, request.step(), value));
            }
            if (request.solve() != null) {
                if (!Double.isFinite(request.target())) throw new LocalizedException("error.cli.exposureTarget");
                Parameter solved = request.solve();
                double n = request.solve() == Parameter.APERTURE ? Double.NaN : applied.get(Parameter.APERTURE).applied();
                double t = request.solve() == Parameter.SHUTTER ? Double.NaN : applied.get(Parameter.SHUTTER).applied();
                double s = request.solve() == Parameter.ISO ? Double.NaN : applied.get(Parameter.ISO).applied();
                double exact = switch (solved) {
                    case SHUTTER -> CalculatorPresentation.heldShutter(request.target(), n, s);
                    case APERTURE -> CalculatorPresentation.heldAperture(request.target(), t, s);
                    case ISO -> CalculatorPresentation.heldIso(request.target(), n, t);
                };
                boolean usable = exact > 0 && Double.isFinite(exact) && (request.step().standard() || solved.accepts(exact));
                if (!usable) throw new LocalizedException("calc.exposure.holdRange", LocalizedMessage.of(solved.nameKey()));
                applied.put(solved, ExposureStops.quantize(solved, request.step(), exact));
            }
            double n = applied.get(Parameter.APERTURE).applied(), t = applied.get(Parameter.SHUTTER).applied(), s = applied.get(Parameter.ISO).applied();
            var result = calculator.apply(new CalculationInput(CalculationKind.EXPOSURE_ND, Map.of("aperture", n, "shutter", t, "iso", s),
                Map.of("aperture", "f-number", "shutter", "s", "iso", "ISO")));
            if (!result.isSuccess()) return Result.failure(result);
            var values = result.value().orElseThrow().values();
            return Result.success(new Solution(request.step(), applied.get(Parameter.APERTURE), applied.get(Parameter.SHUTTER), applied.get(Parameter.ISO),
                values.get(TechnicalCalculator.SETTING_EV), values.get(TechnicalCalculator.EV100), request.solve(), request.solve() == null ? Double.NaN : request.target()));
        } catch (IllegalArgumentException refused) { return Result.failure(refused); }
    }

    /** "Enter a value from 1/64000 to 86400." in the notation of the parameter's field. */
    public static LocalizedMessage range(Parameter parameter) {
        return parameter == Parameter.SHUTTER
            ? LocalizedMessage.of("calc.error.range", CalculatorPresentation.shutterField(parameter.min), CalculatorPresentation.fieldNumber(parameter.max))
            : LocalizedMessage.of("calc.error.range", CalculatorPresentation.fieldNumber(parameter.min), CalculatorPresentation.fieldNumber(parameter.max));
    }

    /**
     * Full-stop apertures around the applied one, each with the exposure time that keeps {@link Solution#reference()}
     * at the applied ISO. In a standard step the apertures are the step tables' full stops (f/0.5 to f/128), the time is
     * matched to a stop and the pair's own EV100 and deviation are reported; a pair whose time would lie beyond the stops
     * is marked unsupported. EXACT keeps the previous table exactly: full stops f/1 to f/64
     * ({@link CalculatorPresentation#FULL_STOP_APERTURES}) with precise times, unsupported only outside the accepted
     * range. Apertures are distinct, so no pair repeats.
     */
    public static List<Equivalent> equivalents(Solution solution, int eachSide) {
        var apertures = solution.step().standard() ? ExposureStops.sequence(Parameter.APERTURE, Step.FULL) : CalculatorPresentation.FULL_STOP_APERTURES;
        double n = solution.value(Parameter.APERTURE), t = solution.value(Parameter.SHUTTER), s = solution.value(Parameter.ISO), reference = solution.reference();
        int centre = 0;
        for (int i = 1; i < apertures.size(); i++)
            if (Math.abs(Parameter.APERTURE.ev100Shift(n, apertures.get(i))) < Math.abs(Parameter.APERTURE.ev100Shift(n, apertures.get(centre))) - ExposureStops.TIE) centre = i;
        int from = Math.max(0, Math.min(centre - eachSide, apertures.size() - 1 - 2 * eachSide)), to = Math.min(apertures.size() - 1, from + 2 * eachSide);
        var pairs = new ArrayList<Equivalent>();
        for (int i = from; i <= to; i++) {
            double aperture = apertures.get(i), exact = CalculatorPresentation.heldShutter(reference, aperture, s);
            if (!(exact > 0) || !Double.isFinite(exact)) continue;
            Quantized time = ExposureStops.quantize(Parameter.SHUTTER, solution.step(), exact);
            double ev100 = PhotoFormulas.ev100(PhotoFormulas.settingsEv(aperture, time.applied()), s);
            boolean supported = solution.step().standard() ? !time.limited() : Parameter.SHUTTER.accepts(exact);
            boolean current = same(aperture, n) && same(time.applied(), t);
            pairs.add(new Equivalent(aperture, time, ev100, ev100 - reference, current, supported));
        }
        return pairs;
    }
    private static boolean same(double a, double b) { return Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b)); }
}
