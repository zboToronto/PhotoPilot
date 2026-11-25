package com.photopilot.application;

import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.service.PhotoFormulas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Standard photographic exposure stops of the exposure value calculator and the one rule that picks a stop for a precise
 * value. Plain Java (no JavaFX) and shared by the GUI page and the command line, so both offer the same settings.
 * <p>
 * <b>Nominal values.</b> A stop is stored as the number a camera shows: f/5.6 is N = 5.6, 1/125 s is 0.008 s and ISO 125 is
 * 125. Calculations use these numbers, never the ideal power-of-two series behind them, so a displayed setting and the
 * EV computed from it always agree. The sequences are generic settings, not a statement that a particular camera or lens
 * offers every one of them.
 * <p>
 * <b>Coverage.</b> Every sequence spans the range the calculator accepts for typed values (f/0.5 to f/128, 1/64000 s to
 * 24 h, ISO 1 to 4,096,000) as far as standard markings exist: ISO sequences start at ISO 3, the lowest arithmetic film
 * speed with whole-number third stops; the 1/3 and 1 EV time sequences end within half a step of 24 h.
 * <p>
 * <b>Half-stop ISO convention.</b> Between two full stops (100 × 2^k, and ISO 50, 25, 12, 6, 3 below 100) the half stop is
 * 1.4 × the lower full stop rounded to two significant digits and to a whole number: 140, 280, 560, 1100, 2200, 4500, ...;
 * the half-stop ISO and time sequences end at the accepted maximum (ISO 4,096,000; 24 h).
 * <p>
 * <b>Selection.</b> {@link #quantize} picks the stop with the smallest exposure difference measured in stops (2·|log2(c/e)|
 * for an f-number, |log2(c/e)| for a time or ISO), not the smallest numerical difference. Differences within
 * {@link #TIE} stops count as equal; such a tie goes to the stop that gives less exposure (the larger f-number, the shorter
 * time, the lower ISO). The result is deterministic for every positive finite input.
 */
public final class ExposureStops {
    private ExposureStops() { }

    /** Exposure differences closer than this many stops are a tie (keeps floating-point noise from deciding). */
    public static final double TIE = 1e-9;

    /** How finely the exposure parameters are stepped. EXACT keeps the continuous values. */
    public enum Step {
        THIRD("third", 3), HALF("half", 2), FULL("full", 1), EXACT("exact", 0);
        /** The command-line name of the step (step=third). */
        public final String command;
        /** Steps per full stop; 0 for EXACT. */
        public final int perStop;
        Step(String command, int perStop) { this.command = command; this.perStop = perStop; }
        public boolean standard() { return this != EXACT; }
        /** Message key of the step's short name (1/3 EV, Exact). */
        public String labelKey() { return "calc.exposure.step." + name(); }
        /** A step from its command-line name: third, half, full or exact (also 1/3, 1/2, 1). */
        public static Optional<Step> parse(String text) {
            if (text == null) return Optional.empty();
            String key = text.strip().toLowerCase(Locale.ROOT);
            for (Step step : values()) if (step.command.equals(key)) return Optional.of(step);
            return switch (key) { case "1/3" -> Optional.of(THIRD); case "1/2" -> Optional.of(HALF); case "1" -> Optional.of(FULL); default -> Optional.empty(); };
        }
    }

    /** The three exposure parameters, with the range the calculator accepts for each. */
    public enum Parameter {
        APERTURE("aperture", 0.5, 128), SHUTTER("shutter", 1 / 64000.0, 86400), ISO("iso", 1, 4_096_000);
        /** The calculation and command-line key of the parameter. */
        public final String key;
        public final double min, max;
        Parameter(String key, double min, double max) { this.key = key; this.min = min; this.max = max; }
        /** Message key of the parameter's name (Aperture, Exposure time, ISO). */
        public String nameKey() { return "calc.input." + key; }
        /**
         * How much EV100 changes when this parameter goes from one value to another, in stops: 2·log2(to/from) for an
         * f-number, −log2(to/from) for a time or ISO. Positive means less exposure.
         */
        public double ev100Shift(double from, double to) {
            // A difference of logarithms, not the logarithm of a ratio: finite for every pair of positive finite
            // values, even when the ratio itself would overflow (a subnormal solution far beyond the stops).
            double stops = PhotoFormulas.log2(to) - PhotoFormulas.log2(from);
            return this == APERTURE ? 2 * stops : -stops;
        }
        public boolean accepts(double value) { return Double.isFinite(value) && value >= min * (1 - 1e-12) && value <= max * (1 + 1e-12); }
        public static Optional<Parameter> parse(String text) {
            if (text == null) return Optional.empty();
            String key = text.strip().toLowerCase(Locale.ROOT);
            for (Parameter parameter : values()) if (parameter.key.equals(key)) return Optional.of(parameter);
            return key.equals("time") ? Optional.of(SHUTTER) : Optional.empty();
        }
    }

    // ---- Sequences -------------------------------------------------------------------------------------------------

    private static final List<Double> APERTURE_THIRD = List.of(0.5, 0.56, 0.63, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0,
        4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0, 36.0, 40.0, 45.0, 51.0, 57.0, 64.0, 72.0, 80.0, 90.0,
        101.0, 114.0, 128.0);
    private static final List<Double> APERTURE_HALF = List.of(0.5, 0.6, 0.7, 0.84, 1.0, 1.2, 1.4, 1.7, 2.0, 2.4, 2.8, 3.3, 4.0, 4.8, 5.6, 6.7, 8.0, 9.5, 11.0,
        13.0, 16.0, 19.0, 22.0, 27.0, 32.0, 38.0, 45.0, 54.0, 64.0, 76.0, 90.0, 107.0, 128.0);
    private static final List<Double> APERTURE_FULL = List.of(0.5, 0.7, 1.0, 1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0, 32.0, 45.0, 64.0, 90.0, 128.0);

    private static final List<Double> SHUTTER_THIRD = times(new double[] { 64000, 50000, 40000, 32000, 25600, 20000, 16000, 12800, 10000, 8000, 6400, 5000, 4000,
        3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4 },
        new double[] { 0.3, 0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 160, 200, 240, 320, 400,
            480, 640, 800, 960, 1280, 1600, 1920, 2560, 3200, 3840, 5120, 6400, 7680, 10240, 12800, 15360, 20480, 25600, 30720, 40960, 51200, 61440, 81920 });
    private static final List<Double> SHUTTER_HALF = times(new double[] { 64000, 48000, 32000, 24000, 16000, 12000, 8000, 6000, 4000, 3000, 2000, 1500, 1000, 750,
        500, 350, 250, 180, 125, 90, 60, 45, 30, 20, 15, 10, 8, 6, 4 },
        new double[] { 0.3, 0.5, 0.7, 1, 1.5, 2, 3, 4, 6, 8, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 960, 1440, 1920, 2880, 3840, 5760, 7680,
            11520, 15360, 23040, 30720, 46080, 61440, 86400 });
    private static final List<Double> SHUTTER_FULL = times(new double[] { 64000, 32000, 16000, 8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4 },
        new double[] { 0.5, 1, 2, 4, 8, 15, 30, 60, 120, 240, 480, 960, 1920, 3840, 7680, 15360, 30720, 61440 });

    private static final List<Double> ISO_THIRD = List.of(3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 20.0, 25.0, 32.0, 40.0, 50.0, 64.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 320.0, 400.0, 500.0, 640.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3200.0, 4000.0, 5000.0, 6400.0, 8000.0, 10000.0, 12800.0, 16000.0,
        20000.0, 25600.0, 32000.0, 40000.0, 51200.0, 64000.0, 80000.0, 102400.0, 128000.0, 160000.0, 204800.0, 256000.0, 320000.0, 409600.0, 512000.0, 640000.0,
        819200.0, 1024000.0, 1280000.0, 1638400.0, 2048000.0, 2560000.0, 3276800.0, 4096000.0);
    private static final List<Double> ISO_FULL = List.of(3.0, 6.0, 12.0, 25.0, 50.0, 100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0, 12800.0, 25600.0, 51200.0,
        102400.0, 204800.0, 409600.0, 819200.0, 1638400.0, 3276800.0);
    private static final List<Double> ISO_HALF = halfStopIsos();

    private static List<Double> times(double[] reciprocals, double[] seconds) {
        var values = new ArrayList<Double>();
        for (double reciprocal : reciprocals) values.add(1 / reciprocal);
        for (double value : seconds) values.add(value);
        return List.copyOf(values);
    }
    /**
     * The full-stop ISOs with 1.4 × each lower full stop between them, rounded to two significant digits and a whole
     * number; the next one after ISO 3,276,800 would exceed the accepted maximum, so the sequence ends at that maximum.
     */
    private static List<Double> halfStopIsos() {
        var values = new ArrayList<Double>();
        for (int i = 0; i < ISO_FULL.size(); i++) {
            values.add(ISO_FULL.get(i));
            if (i + 1 < ISO_FULL.size()) values.add((double) Math.round(CalculatorPresentation.significant(1.4 * ISO_FULL.get(i), 2)));
        }
        values.add(Parameter.ISO.max);
        return List.copyOf(values);
    }

    /** The nominal stops of a parameter in a standard step, smallest number first. EXACT has no stops. */
    public static List<Double> sequence(Parameter parameter, Step step) {
        return switch (step) {
            case THIRD -> switch (parameter) { case APERTURE -> APERTURE_THIRD; case SHUTTER -> SHUTTER_THIRD; case ISO -> ISO_THIRD; };
            case HALF -> switch (parameter) { case APERTURE -> APERTURE_HALF; case SHUTTER -> SHUTTER_HALF; case ISO -> ISO_HALF; };
            case FULL -> switch (parameter) { case APERTURE -> APERTURE_FULL; case SHUTTER -> SHUTTER_FULL; case ISO -> ISO_FULL; };
            case EXACT -> List.of();
        };
    }
    /** True when a value is one of the full stops of the parameter (major ticks, labels). */
    public static boolean fullStop(Parameter parameter, double value) { return sequence(parameter, Step.FULL).contains(value); }

    // ---- Selection -------------------------------------------------------------------------------------------------

    /**
     * A precise value and the setting applied for it.
     * @param shift EV100 change caused by applying the stop instead of the precise value, in stops (positive is less exposure)
     * @param limited true when the precise value lies beyond the first or last stop by more than half a step, so the end
     *                stop is a boundary rather than a nearby setting
     */
    public record Quantized(Parameter parameter, Step step, double exact, double applied, double shift, boolean limited) {
        public boolean moved() { return Math.abs(shift) > TIE; }
    }

    /**
     * The setting applied for a precise value: the nearest stop of the step (exposure-wise), or the value itself in EXACT.
     * @throws LocalizedException for zero, negative, NaN or infinite values (error.calc.number)
     */
    public static Quantized quantize(Parameter parameter, Step step, double exact) {
        if (!(exact > 0) || !Double.isFinite(exact)) throw new LocalizedException("error.calc.number");
        if (!step.standard()) return new Quantized(parameter, step, exact, exact, 0, false);
        var stops = sequence(parameter, step);
        int best = 0; double bestError = Math.abs(parameter.ev100Shift(exact, stops.getFirst()));
        for (int i = 1; i < stops.size(); i++) {
            double error = Math.abs(parameter.ev100Shift(exact, stops.get(i)));
            // A tie goes to less exposure: the candidate whose EV100 shift is larger.
            boolean tie = Math.abs(error - bestError) <= TIE;
            if (!tie && error < bestError || tie && parameter.ev100Shift(exact, stops.get(i)) > parameter.ev100Shift(exact, stops.get(best))) { best = i; bestError = error; }
        }
        double applied = stops.get(best), shift = parameter.ev100Shift(exact, applied);
        boolean outside = exact < stops.getFirst() || exact > stops.getLast();
        return new Quantized(parameter, step, exact, applied, shift, outside && bestError > 0.5 / step.perStop + TIE);
    }

    // ---- Slider scales ---------------------------------------------------------------------------------------------

    /**
     * The slider scale of a parameter: in a standard step every whole position is one stop (arrow keys move one stop);
     * in EXACT the same positions as the 1/3 EV stops, continuous between them, with values rounded to three
     * significant digits (whole positions are the stops themselves). A rounded value is kept between the two stops it
     * lies between, so moving the slider forward never lowers the value next to a stop such as ISO 102400.
     */
    public static Scale scale(Parameter parameter, Step step) {
        if (step.standard()) return Scale.nominal(sequence(parameter, step));
        Scale thirds = Scale.nominal(sequence(parameter, Step.THIRD));
        return new Scale() {
            public double positions() { return thirds.positions(); }
            public boolean snaps() { return false; }
            public double value(double position) {
                double clamped = Math.max(0, Math.min(positions(), position));
                if (clamped == Math.rint(clamped)) return thirds.value(clamped);
                double low = thirds.value(Math.floor(clamped)), high = thirds.value(Math.ceil(clamped));
                return Math.max(low, Math.min(high, CalculatorPresentation.significant(thirds.value(clamped), 3)));
            }
            public double position(double value) { return thirds.position(value); }
        };
    }
}
