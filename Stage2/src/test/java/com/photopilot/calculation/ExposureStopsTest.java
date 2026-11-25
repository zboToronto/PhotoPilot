package com.photopilot.calculation;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.application.ExposureStops;
import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Quantized;
import com.photopilot.application.ExposureStops.Step;
import com.photopilot.i18n.LocalizedException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Standard exposure stops and the rule that picks one for a precise value. Expected values come from the stated rule
 * (least exposure error in stops, ties to less exposure) and the conventional markings, not from the code under test.
 * No JavaFX.
 */
class ExposureStopsTest {
    static final List<Step> STANDARD = List.of(Step.THIRD, Step.HALF, Step.FULL);

    static double applied(Parameter parameter, Step step, double exact) { return ExposureStops.quantize(parameter, step, exact).applied(); }

    @Test void sequencesArePositiveAscendingDistinctAndCoverTheAcceptedRange() {
        for (Parameter parameter : Parameter.values()) {
            assertTrue(ExposureStops.sequence(parameter, Step.EXACT).isEmpty(), "Exact has no stops");
            for (Step step : STANDARD) {
                var stops = ExposureStops.sequence(parameter, step);
                assertEquals(stops.size(), new HashSet<>(stops).size(), parameter + " " + step + " has no duplicates");
                for (int i = 0; i < stops.size(); i++) {
                    double value = stops.get(i);
                    assertTrue(value > 0 && Double.isFinite(value), parameter + " " + step + " " + value);
                    assertTrue(parameter.accepts(value), parameter + " " + step + ": " + value + " is inside the accepted range");
                    if (i == 0) continue;
                    assertTrue(value > stops.get(i - 1), parameter + " " + step + " ascending at " + value);
                    // Nominal markings are close to, not exactly, the ideal 1/perStop stop apart (the least even pair is
                    // the half-stop 1/4 s, 0.3 s, 0.5 s: 0.26 and 0.74 stops, as cameras mark them).
                    double apart = Math.abs(parameter.ev100Shift(stops.get(i - 1), value));
                    assertEquals(1.0 / step.perStop, apart, 0.25, parameter + " " + step + " between " + stops.get(i - 1) + " and " + value);
                }
                // The ends reach the accepted range to within half a step, so a typed value never needs the limit there.
                double half = 0.5 / step.perStop + 1e-9;
                assertTrue(Math.abs(parameter.ev100Shift(parameter.max, stops.getLast())) <= half, parameter + " " + step + " reaches the maximum");
                if (parameter != Parameter.ISO) assertEquals(parameter.min, stops.getFirst(), 0.0, parameter + " " + step + " starts at the minimum");
            }
        }
        // ISO stops start at the lowest arithmetic film speed with whole-number third stops; below it the end stop is a limit.
        for (Step step : STANDARD) assertEquals(3.0, ExposureStops.sequence(Parameter.ISO, step).getFirst());
    }

    @Test void fullStopsAreEveryThirdAndEverySecondStopOfTheFinerSequences() {
        for (Parameter parameter : Parameter.values()) {
            var full = ExposureStops.sequence(parameter, Step.FULL);
            var half = ExposureStops.sequence(parameter, Step.HALF);
            var third = ExposureStops.sequence(parameter, Step.THIRD);
            for (int k = 0; k < full.size(); k++) {
                assertEquals(full.get(k), third.get(3 * k), 0.0, parameter + " third-stop position " + 3 * k);
                assertEquals(full.get(k), half.get(2 * k), 0.0, parameter + " half-stop position " + 2 * k);
                assertTrue(ExposureStops.fullStop(parameter, full.get(k)));
            }
            assertFalse(ExposureStops.fullStop(parameter, third.get(1)));
        }
    }

    @Test void conventionalMarkingsAreStoredAsMarked() {
        var apertures = ExposureStops.sequence(Parameter.APERTURE, Step.THIRD);
        assertTrue(apertures.containsAll(List.of(1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0, 11.0,
            13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0)), apertures.toString());
        var times = ExposureStops.sequence(Parameter.SHUTTER, Step.THIRD);
        for (double reciprocal : new double[] { 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80,
            60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4 }) assertTrue(times.contains(1 / reciprocal), "1/" + reciprocal);
        for (double seconds : new double[] { 0.3, 0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30 }) assertTrue(times.contains(seconds), seconds + " s");
        assertTrue(ExposureStops.sequence(Parameter.ISO, Step.THIRD).containsAll(List.of(50.0, 64.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 320.0, 400.0, 500.0, 640.0,
            800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3200.0, 4000.0, 5000.0, 6400.0, 8000.0, 10000.0, 12800.0, 16000.0, 20000.0, 25600.0, 32000.0, 40000.0,
            51200.0, 64000.0, 80000.0, 102400.0)));
        // Half-stop ISO: 1.4 × the lower full stop, two significant digits, whole numbers.
        assertTrue(ExposureStops.sequence(Parameter.ISO, Step.HALF).containsAll(List.of(70.0, 140.0, 280.0, 560.0, 1100.0, 2200.0, 4500.0, 9000.0, 18000.0, 36000.0, 72000.0)));
        assertTrue(ExposureStops.sequence(Parameter.SHUTTER, Step.HALF).containsAll(List.of(1 / 750.0, 1 / 350.0, 1 / 180.0, 1 / 90.0, 1 / 45.0, 0.7, 1.5)));
        assertTrue(ExposureStops.sequence(Parameter.APERTURE, Step.HALF).containsAll(List.of(1.2, 1.7, 2.4, 3.3, 4.8, 6.7, 9.5, 13.0, 19.0, 27.0)));
        // A standard ISO is always a whole number.
        for (Step step : STANDARD) for (double iso : ExposureStops.sequence(Parameter.ISO, step)) assertEquals(Math.rint(iso), iso, 0.0, step + " ISO " + iso);
        // f/5.6 is N = 5.6 and 1/125 s is 0.008 s: the ideal series is not substituted.
        assertTrue(apertures.contains(5.6)); assertFalse(apertures.contains(Math.pow(2, 2.5)));
        assertTrue(times.contains(0.008)); assertFalse(times.contains(1 / 128.0));
    }

    @Test void theTaskExamplesGoToTheNearestStopByExposure() {
        assertEquals(18.0, applied(Parameter.APERTURE, Step.THIRD, 17.8885));
        assertEquals(1 / 160.0, applied(Parameter.SHUTTER, Step.THIRD, 0.00630125));
        assertEquals(200.0, applied(Parameter.ISO, Step.THIRD, 198.373));
        assertEquals(1 / 1000.0, applied(Parameter.SHUTTER, Step.THIRD, 1 / 992.0));
        assertEquals(1 / 1000.0, applied(Parameter.SHUTTER, Step.THIRD, 1 / 1020.0));
        assertEquals(1 / 125.0, applied(Parameter.SHUTTER, Step.THIRD, 1 / 131.0));
        assertEquals(1 / 60.0, applied(Parameter.SHUTTER, Step.THIRD, 1 / 66.1));
        assertEquals(1 / 30.0, applied(Parameter.SHUTTER, Step.THIRD, 1 / 31.3));
        assertEquals(1 / 8.0, applied(Parameter.SHUTTER, Step.THIRD, 1 / 7.81));
        // The same precise time in the other steps.
        assertEquals(1 / 180.0, applied(Parameter.SHUTTER, Step.HALF, 0.00630125));
        assertEquals(1 / 125.0, applied(Parameter.SHUTTER, Step.FULL, 0.00630125));
        assertEquals(0.00630125, applied(Parameter.SHUTTER, Step.EXACT, 0.00630125), 0.0, "Exact is the precise value");
        // The difference that matters is in stops, not in numbers: ISO 3590 is numerically nearer 3200 but exposure-wise
        // nearer 4000 (log2(3590/3200) = 0.166 stops, log2(4000/3590) = 0.156).
        assertEquals(4000.0, applied(Parameter.ISO, Step.THIRD, 3590));
        // f/12 is 0.25 stops from f/11 and 0.23 stops from f/13. f/11.98 is numerically nearer f/11 (0.98 against 1.02)
        // but exposure-wise nearer f/13 (2·log2(11.98/11) = 0.246, 2·log2(13/11.98) = 0.236).
        assertEquals(13.0, applied(Parameter.APERTURE, Step.THIRD, 12));
        assertEquals(13.0, applied(Parameter.APERTURE, Step.THIRD, 11.98));
        assertEquals(11.0, applied(Parameter.APERTURE, Step.THIRD, 11.95), "2·log2(11.95/11) = 0.239 < 2·log2(13/11.95) = 0.243");
    }

    @Test void everyStopMapsToItselfAndNearbyValuesDoNotFlip() {
        for (Parameter parameter : Parameter.values()) for (Step step : STANDARD) for (double stop : ExposureStops.sequence(parameter, step)) {
            Quantized q = ExposureStops.quantize(parameter, step, stop);
            assertEquals(stop, q.applied(), 0.0); assertEquals(0, q.shift(), 0.0); assertFalse(q.moved()); assertFalse(q.limited());
            // Floating-point noise around a stop never moves it to a neighbour.
            assertEquals(stop, applied(parameter, step, stop * (1 + 1e-12)), 0.0);
            assertEquals(stop, applied(parameter, step, stop * (1 - 1e-12)), 0.0);
        }
        // Typed decimal seconds and the fraction are the same stop.
        assertEquals(1 / 160.0, applied(Parameter.SHUTTER, Step.THIRD, 0.00625), 0.0);
    }

    @Test void theChosenStopMinimizesTheExposureErrorForAnyValue() {
        var random = new Random(3311);
        for (Parameter parameter : Parameter.values()) for (Step step : STANDARD) {
            var stops = ExposureStops.sequence(parameter, step);
            for (int i = 0; i < 4000; i++) {
                // Log-uniform across the accepted range and a little beyond it.
                double exact = parameter.min * Math.pow(parameter.max / parameter.min, -0.05 + 1.1 * random.nextDouble());
                Quantized q = ExposureStops.quantize(parameter, step, exact);
                assertTrue(stops.contains(q.applied()));
                assertEquals(parameter.ev100Shift(exact, q.applied()), q.shift(), 1e-12);
                for (double candidate : stops)
                    assertTrue(Math.abs(parameter.ev100Shift(exact, candidate)) >= Math.abs(q.shift()) - ExposureStops.TIE, parameter + " " + step + " " + exact + ": " + candidate + " is nearer than " + q.applied());
                assertEquals(q, ExposureStops.quantize(parameter, step, exact), "deterministic");
            }
        }
    }

    @Test void anExactTieGoesToLessExposure() {
        // Geometric means of two full stops: the exposure error is the same both ways.
        assertEquals(100.0, applied(Parameter.ISO, Step.FULL, Math.sqrt(100 * 200)), "the lower ISO");
        assertEquals(11.0, applied(Parameter.APERTURE, Step.FULL, Math.sqrt(8 * 11)), "the larger f-number");
        assertEquals(1 / 125.0, applied(Parameter.SHUTTER, Step.FULL, Math.sqrt(1 / 125.0 * 1 / 60.0)), "the shorter time");
        // Just off the midpoint the nearer stop wins on either side.
        assertEquals(200.0, applied(Parameter.ISO, Step.FULL, Math.sqrt(100 * 200) * (1 + 1e-6)));
        assertEquals(8.0, applied(Parameter.APERTURE, Step.FULL, Math.sqrt(8 * 11) * (1 - 1e-6)));
        assertEquals(1 / 60.0, applied(Parameter.SHUTTER, Step.FULL, Math.sqrt(1 / 125.0 * 1 / 60.0) * (1 + 1e-6)));
    }

    @Test void valuesBeyondTheStopsGoToTheEndStopAndAreMarkedAsALimit() {
        Quantized iso1 = ExposureStops.quantize(Parameter.ISO, Step.THIRD, 1);
        assertEquals(3.0, iso1.applied()); assertTrue(iso1.limited()); assertEquals(-Math.log(3) / Math.log(2), iso1.shift(), 1e-12, "more ISO than solved: more exposure, a lower EV100");
        assertFalse(ExposureStops.quantize(Parameter.ISO, Step.THIRD, 2.9).limited(), "within half a step of the end stop");
        Quantized wide = ExposureStops.quantize(Parameter.APERTURE, Step.THIRD, 10733);
        assertEquals(128.0, wide.applied()); assertTrue(wide.limited()); assertEquals(2 * Math.log(128 / 10733.0) / Math.log(2), wide.shift(), 1e-12);
        assertEquals(1 / 64000.0, applied(Parameter.SHUTTER, Step.THIRD, 1e-7));
        assertTrue(ExposureStops.quantize(Parameter.SHUTTER, Step.FULL, 86400).applied() == 61440 && !ExposureStops.quantize(Parameter.SHUTTER, Step.FULL, 86400).limited());
        assertFalse(ExposureStops.quantize(Parameter.APERTURE, Step.EXACT, 10733).limited(), "Exact does not round, the solver checks its range");
    }

    @Test void unusableValuesAreRefused() {
        for (double bad : new double[] { 0, -1, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY })
            for (Step step : Step.values()) assertThrows(LocalizedException.class, () -> ExposureStops.quantize(Parameter.ISO, step, bad), step + " " + bad);
    }

    @Test void theExposureErrorIsFiniteForEveryPositiveFiniteValue() {
        // A subnormal value (as a solution for an absurd target can be) is far beyond the stops, but its distance in
        // stops is still a number: the ratio stop / value would overflow, the difference of logarithms does not.
        for (Parameter parameter : Parameter.values()) for (Step step : STANDARD)
            for (double value : new double[] { Double.MIN_VALUE, 1e-320, Double.MIN_NORMAL, 1e-300, 1e300, Double.MAX_VALUE }) {
                Quantized q = ExposureStops.quantize(parameter, step, value);
                assertTrue(Double.isFinite(q.shift()), parameter + " " + step + " " + value + ": " + q.shift());
                assertTrue(q.limited()); assertEquals(value < 1 ? ExposureStops.sequence(parameter, step).getFirst() : ExposureStops.sequence(parameter, step).getLast(), q.applied());
            }
        assertEquals(-(Math.log(3) / Math.log(2) - Math.log(1e-320) / Math.log(2)), ExposureStops.quantize(Parameter.ISO, Step.THIRD, 1e-320).shift(), 1e-9);
    }

    @Test void slidersMoveOneStopPerPositionAndAreContinuousInExact() {
        for (Parameter parameter : Parameter.values()) {
            for (Step step : STANDARD) {
                Scale scale = ExposureStops.scale(parameter, step); var stops = ExposureStops.sequence(parameter, step);
                assertTrue(scale.snaps()); assertEquals(stops.size() - 1, scale.positions());
                for (int i = 0; i < stops.size(); i++) { assertEquals(stops.get(i), scale.value(i), 0.0); assertEquals(i, scale.position(stops.get(i)), 1e-9); }
            }
            Scale exact = ExposureStops.scale(parameter, Step.EXACT); var thirds = ExposureStops.sequence(parameter, Step.THIRD);
            assertFalse(exact.snaps()); assertEquals(thirds.size() - 1, exact.positions());
            assertEquals(thirds.get(10), exact.value(10), 0.0, "whole positions are the 1/3 EV stops");
            double between = exact.value(10.37);
            assertTrue(between > thirds.get(10) && between < thirds.get(11));
            assertEquals(CalculatorPresentation.significant(between, 3), between, 0.0, "continuous values read in three significant digits");
            // Moving forward never lowers the value, also right beside stops with more digits (ISO 102400, 1/25600 s).
            double previous = exact.value(0);
            for (int i = 1; i <= exact.positions() * 1000; i++) {
                double value = exact.value(i / 1000.0);
                assertTrue(value >= previous, parameter + " at position " + i / 1000.0 + ": " + value + " after " + previous);
                assertTrue(value >= thirds.get((int) Math.floor(i / 1000.0)) && value <= thirds.get((int) Math.ceil(i / 1000.0)), parameter + " stays between its stops at " + i / 1000.0);
                previous = value;
            }
        }
    }

    @Test void stepsAndParametersAreReadFromTheirCommandLineNames() {
        assertEquals(Step.THIRD, Step.parse("third").orElseThrow()); assertEquals(Step.HALF, Step.parse("HALF").orElseThrow());
        assertEquals(Step.FULL, Step.parse("1").orElseThrow()); assertEquals(Step.THIRD, Step.parse("1/3").orElseThrow()); assertEquals(Step.EXACT, Step.parse(" exact ").orElseThrow());
        assertTrue(Step.parse("quarter").isEmpty()); assertTrue(Step.parse(null).isEmpty());
        assertEquals(Parameter.SHUTTER, Parameter.parse("shutter").orElseThrow()); assertEquals(Parameter.ISO, Parameter.parse("ISO").orElseThrow());
        assertTrue(Parameter.parse("speed").isEmpty());
        // The accepted ranges are those of the calculator's input fields.
        assertEquals(0.5, Parameter.APERTURE.min); assertEquals(128, Parameter.APERTURE.max);
        assertEquals(1 / 64000.0, Parameter.SHUTTER.min); assertEquals(86400, Parameter.SHUTTER.max);
        assertEquals(1, Parameter.ISO.min); assertEquals(4_096_000, Parameter.ISO.max);
    }
}
