package com.photopilot.calculation;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.ExposureSolver;
import com.photopilot.application.ExposureSolver.Equivalent;
import com.photopilot.application.ExposureSolver.Request;
import com.photopilot.application.ExposureSolver.Solution;
import com.photopilot.application.ExposureStops;
import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Step;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.service.TechnicalCalculator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The exposure value calculator's solving path: standard stops applied to the controlled settings, the solved setting
 * from a fixed target, actual EV100 from the applied values, the deviation, parameter limits, no drift, and the
 * equivalent pairs. Expected values are worked out from EV = log2(N²/t) and EV100 = EV − log2(ISO/100). No JavaFX.
 */
class ExposureSolverTest {
    final TechnicalCalculator calculator = new TechnicalCalculator();
    static final double TARGET = log2(64 / 0.008); // f/8, 1/125 s, ISO 100: 12.965784284662087

    static double log2(double value) { return Math.log(value) / Math.log(2); }
    static double ev100(double n, double t, double iso) { return log2(n * n / t) - log2(iso / 100); }
    Solution solve(Request request) {
        var result = ExposureSolver.solve(request, calculator::calculate);
        assertTrue(result.isSuccess(), String.valueOf(result.errors()));
        return result.value().orElseThrow();
    }
    String refusal(Request request) {
        var result = ExposureSolver.solve(request, calculator::calculate);
        assertFalse(result.isSuccess()); return result.messages().getFirst().key();
    }

    @Test void controlledSettingsAreAppliedAsStopsAndTheResultDescribesWhatIsApplied() {
        var solution = solve(Request.of(Step.THIRD, 17.8885, 0.00630125, 198.373));
        assertEquals(18.0, solution.value(Parameter.APERTURE)); assertEquals(1 / 160.0, solution.value(Parameter.SHUTTER)); assertEquals(200.0, solution.value(Parameter.ISO));
        assertEquals(17.8885, solution.aperture().exact(), 0.0, "what was entered is kept beside what is applied");
        assertEquals(log2(18 * 18 * 160.0), solution.settingsEv(), 1e-12);
        assertEquals(ev100(18, 1 / 160.0, 200), solution.ev100(), 1e-12);
        // Identical to the existing validated calculation of the applied values (the engine is not duplicated).
        var direct = calculator.calculate(new CalculationInput(CalculationKind.EXPOSURE_ND, Map.of("aperture", 18.0, "shutter", 1 / 160.0, "iso", 200.0),
            Map.of("aperture", "f-number", "shutter", "s", "iso", "ISO"))).value().orElseThrow().values();
        assertEquals(direct.get(TechnicalCalculator.SETTING_EV), solution.settingsEv(), 0.0); assertEquals(direct.get(TechnicalCalculator.EV100), solution.ev100(), 0.0);
        assertFalse(solution.solving()); assertTrue(Double.isNaN(solution.deviation())); assertEquals(solution.ev100(), solution.reference(), 0.0);
        // Exact uses the values as entered.
        var exact = solve(Request.of(Step.EXACT, 17.8885, 0.00630125, 198.373));
        assertEquals(17.8885, exact.value(Parameter.APERTURE), 0.0); assertEquals(ev100(17.8885, 0.00630125, 198.373), exact.ev100(), 1e-12);
        // Each step applies its own stops.
        assertEquals(1 / 180.0, solve(Request.of(Step.HALF, 8, 0.00630125, 100)).value(Parameter.SHUTTER));
        assertEquals(1 / 125.0, solve(Request.of(Step.FULL, 8, 0.00630125, 100)).value(Parameter.SHUTTER));
    }

    @Test void eachSolvedSettingIsTheNearestStopToItsPreciseSolution() {
        double target = 12.97;
        // ISO from f/10 and 1/160 s: 100 × 2^(EV − target).
        var iso = solve(Request.of(Step.THIRD, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, target));
        double exactIso = 100 * Math.pow(2, log2(100 * 160.0) - target);
        assertEquals(exactIso, iso.iso().exact(), 1e-9); assertEquals(200.0, iso.value(Parameter.ISO));
        assertEquals(ev100(10, 1 / 160.0, 200), iso.ev100(), 1e-12); assertEquals(iso.ev100() - target, iso.deviation(), 0.0);
        assertEquals(-log2(200 / exactIso), iso.iso().shift(), 1e-12); assertEquals(iso.deviation(), iso.iso().shift(), 1e-9, "the only rounding is the solved setting's");
        assertFalse(iso.limited()); assertEquals(target, iso.target(), 0.0); assertEquals(target, iso.reference(), 0.0);
        // Aperture from 1/250 s and ISO 200: sqrt(t × 2^(target + log2(ISO/100))).
        var aperture = solve(Request.of(Step.THIRD, Double.NaN, 1 / 250.0, 200).solving(Parameter.APERTURE, 13));
        assertEquals(Math.sqrt(0.004 * Math.pow(2, 14)), aperture.aperture().exact(), 1e-12); assertEquals(8.0, aperture.value(Parameter.APERTURE));
        assertEquals(ev100(8, 0.004, 200) - 13, aperture.deviation(), 1e-12);
        // Time from f/5.6 and ISO 100: N² / 2^(target).
        var time = solve(Request.of(Step.THIRD, 5.6, Double.NaN, 100).solving(Parameter.SHUTTER, TARGET));
        assertEquals(5.6 * 5.6 / Math.pow(2, TARGET), time.shutter().exact(), 1e-15); assertEquals(1 / 250.0, time.value(Parameter.SHUTTER));
        // Values given for the solved setting are ignored: only the target decides it.
        assertEquals(time, solve(Request.of(Step.THIRD, 5.6, 99, 100).solving(Parameter.SHUTTER, TARGET)));
        // The other steps: their own stops; Exact: the precise solution and no deviation.
        // 100 × 2^(13.9658 − 13.25) = ISO 164.2: 0.23 stops from 140 and 0.28 from 200.
        assertEquals(140.0, solve(Request.of(Step.HALF, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, 13.25)).value(Parameter.ISO));
        assertEquals(200.0, solve(Request.of(Step.FULL, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, 13.25)).value(Parameter.ISO), "0.72 stops from 100, 0.28 from 200");
    }

    @Test void exactModeSolvesWithoutRoundingAndRefusesSolutionsOutsideTheRange() {
        for (Parameter solved : Parameter.values()) {
            var request = Request.of(Step.EXACT, solved == Parameter.APERTURE ? Double.NaN : 10, solved == Parameter.SHUTTER ? Double.NaN : 1 / 160.0,
                solved == Parameter.ISO ? Double.NaN : 100).solving(solved, 12.97);
            var solution = solve(request);
            assertEquals(solution.setting(solved).exact(), solution.value(solved), 0.0, solved + " is applied as solved");
            assertEquals(0, solution.deviation(), 1e-12, solved + ": the target is met exactly");
            assertFalse(solution.limited());
        }
        // An hour at ISO 400 would need about f/10733: outside f/0.5 to f/128.
        assertEquals("calc.exposure.holdRange", refusal(Request.of(Step.EXACT, Double.NaN, 3600, 400).solving(Parameter.APERTURE, TARGET)));
        assertEquals("calc.exposure.holdRange", refusal(Request.of(Step.EXACT, 1, 1 / 8000.0, Double.NaN).solving(Parameter.ISO, 20)));
    }

    @Test void aStandardStepStopsAtTheEndOfTheRangeAndReportsWhatIsMissing() {
        var limited = solve(Request.of(Step.THIRD, Double.NaN, 3600, 400).solving(Parameter.APERTURE, TARGET));
        assertEquals(128.0, limited.value(Parameter.APERTURE)); assertTrue(limited.limited());
        assertEquals(3840.0, limited.value(Parameter.SHUTTER), "the hour itself is matched to its nearest stop, 64 min");
        assertEquals(ev100(128, 3840, 400) - TARGET, limited.deviation(), 1e-12);
        assertTrue(limited.deviation() < -12, "far more exposure than the target: " + limited.deviation());
        var iso = solve(Request.of(Step.FULL, 1, 1 / 8000.0, Double.NaN).solving(Parameter.ISO, 20));
        assertEquals(3.0, iso.value(Parameter.ISO)); assertTrue(iso.limited()); assertTrue(iso.deviation() < 0);
        // An ordinary rounding is not a limit.
        assertFalse(solve(Request.of(Step.FULL, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, 12.97)).limited());
    }

    @Test void unusableRequestsAreRefusedWithTheFieldMessages() {
        assertEquals("calc.status.input", refusal(Request.of(Step.THIRD, 500, 0.008, 100)), "aperture outside f/0.5 to f/128");
        assertEquals("calc.status.input", refusal(Request.of(Step.THIRD, 8, 0, 100)));
        assertEquals("calc.status.input", refusal(Request.of(Step.EXACT, 8, 0.008, Double.NaN)));
        assertEquals("calc.status.input", refusal(Request.of(Step.EXACT, Double.POSITIVE_INFINITY, 0.008, 100)));
        assertEquals("error.cli.exposureTarget", refusal(Request.of(Step.THIRD, 8, Double.NaN, 100).solving(Parameter.SHUTTER, Double.NaN)));
        // A time of 64 × 2^5000 s is not a number a step can be matched to.
        assertEquals("calc.exposure.holdRange", refusal(Request.of(Step.THIRD, 8, Double.NaN, 100).solving(Parameter.SHUTTER, -5000)));
        // A subnormal solution (ISO ≈ 2^-1024) is still a number: the end stop with a finite, very large deviation.
        var subnormal = solve(Request.of(Step.THIRD, 1, 1, Double.NaN).solving(Parameter.ISO, 1031));
        assertTrue(subnormal.iso().exact() > 0 && subnormal.iso().exact() < Double.MIN_NORMAL);
        assertEquals(3.0, subnormal.value(Parameter.ISO)); assertTrue(subnormal.limited()); assertTrue(Double.isFinite(subnormal.iso().shift()) && Double.isFinite(subnormal.deviation()));
    }

    @Test void theTargetNeverDriftsThroughRepeatedChangesOrStepSwitches() {
        // Hold the exposure of f/8, 1/125 s, ISO 100 by solving ISO, then move the aperture around and back.
        double[] apertures = { 8, 9, 11, 5.6, 7.1, 8, 13, 4.5, 22, 6.3, 8, 10, 8 };
        var first = new java.util.HashMap<Double, Solution>();
        for (int round = 0; round < 3; round++) for (double n : apertures) {
            var solution = solve(Request.of(Step.THIRD, n, 1 / 125.0, Double.NaN).solving(Parameter.ISO, TARGET));
            assertEquals(TARGET, solution.target(), 0.0, "the target is the one captured");
            double exactIso = 100 * Math.pow(2, log2(n * n * 125) - TARGET);
            assertEquals(exactIso, solution.iso().exact(), 1e-9 * exactIso, "each solution is calculated against the original target");
            assertTrue(Math.abs(solution.deviation()) <= 0.25 && !solution.limited(), "at most half the gap to a neighbouring stop: " + solution.deviation());
            var earlier = first.putIfAbsent(n, solution);
            if (earlier != null) assertEquals(earlier, solution, "the same settings give the same result every time");
        }
        assertEquals(TARGET, first.get(8.0).ev100(), 1e-12, "back at the starting settings, the target is met exactly");
        // Switching steps repeatedly while solving: each step gives its own stable result, and Exact meets the target.
        var byStep = new java.util.EnumMap<Step, Solution>(Step.class);
        for (int round = 0; round < 4; round++) for (Step step : List.of(Step.THIRD, Step.HALF, Step.FULL, Step.EXACT, Step.FULL, Step.THIRD)) {
            var solution = solve(Request.of(step, 9, 1 / 100.0, Double.NaN).solving(Parameter.ISO, TARGET));
            var earlier = byStep.putIfAbsent(step, solution);
            if (earlier != null) assertEquals(earlier, solution, step + " after other steps");
            if (step == Step.EXACT) assertEquals(0, solution.deviation(), 1e-12);
        }
    }

    @Test void equivalentPairsUseStandardStopsAndReportTheirOwnDeviation() {
        var solution = solve(Request.of(Step.THIRD, 8, 1 / 125.0, 100));
        var pairs = ExposureSolver.equivalents(solution, 3);
        assertEquals(List.of(2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0), pairs.stream().map(Equivalent::aperture).toList());
        assertEquals(List.of(1 / 1000.0, 1 / 500.0, 1 / 250.0, 1 / 125.0, 1 / 60.0, 1 / 30.0, 1 / 15.0), pairs.stream().map(pair -> pair.shutter().applied()).toList());
        var stops = ExposureStops.sequence(Parameter.SHUTTER, Step.THIRD);
        var seen = new HashSet<List<Double>>();
        for (Equivalent pair : pairs) {
            assertTrue(stops.contains(pair.shutter().applied())); assertTrue(ExposureStops.fullStop(Parameter.APERTURE, pair.aperture())); assertTrue(pair.supported());
            assertEquals(pair.aperture() * pair.aperture() / Math.pow(2, TARGET), pair.shutter().exact(), 1e-15, "the precise time for the same EV100 at ISO 100");
            assertEquals(ev100(pair.aperture(), pair.shutter().applied(), 100), pair.ev100(), 1e-12, "the pair's own EV100 from its applied values");
            assertEquals(pair.ev100() - solution.ev100(), pair.deviation(), 0.0);
            assertTrue(Math.abs(pair.deviation()) <= 1 / 6.0 + 1e-9);
            assertTrue(seen.add(List.of(pair.aperture(), pair.shutter().applied())), "no pair repeats");
            assertEquals(pair.aperture() == 8.0, pair.current());
        }
        // Exact keeps the previous table: full stops f/1 to f/64 with the precise reciprocity times, at every aperture,
        // including the ends where the standard steps' wider table (f/0.5 to f/128) would offer other apertures.
        for (double n : new double[] { 0.5, 1, 1.4, 2, 2.8, 8, 22, 32, 45, 64, 128 }) {
            var precise = solve(Request.of(Step.EXACT, n, 1 / 125.0, 100));
            var exact = ExposureSolver.equivalents(precise, 3);
            var original = CalculatorPresentation.equivalents(precise.settingsEv(), n, 3);
            assertEquals(original.size(), exact.size(), "f/" + n);
            for (int i = 0; i < exact.size(); i++) {
                assertEquals(original.get(i).fNumber(), exact.get(i).aperture(), 0.0, "f/" + n);
                assertEquals(original.get(i).seconds(), exact.get(i).shutter().applied(), 1e-12 * original.get(i).seconds(), "f/" + n);
                assertEquals(0, exact.get(i).deviation(), 1e-12);
            }
        }
        // While solving, the pairs keep the target, not the rounded result.
        var held = solve(Request.of(Step.THIRD, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, 12.97));
        for (Equivalent pair : ExposureSolver.equivalents(held, 3)) assertEquals(pair.ev100() - 12.97, pair.deviation(), 1e-12);
    }

    @Test void pairsBeyondTheStopsAreMarkedAndNeverRepeat() {
        // f/8 at 1/64000 s, ISO 100 (EV100 about 22, beyond sunlit snow): the wider apertures' times run past 1/64000 s.
        for (Step step : Step.values()) {
            var solution = solve(Request.of(step, 8, 1 / 64000.0, 100));
            var pairs = ExposureSolver.equivalents(solution, 3);
            assertEquals(7, pairs.size());
            assertTrue(pairs.stream().anyMatch(pair -> !pair.supported()), step + ": some pairs are outside the range");
            var keys = new ArrayList<List<Double>>();
            for (Equivalent pair : pairs) {
                keys.add(List.of(pair.aperture(), pair.shutter().applied()));
                if (pair.supported()) assertTrue(Parameter.SHUTTER.accepts(pair.shutter().applied()));
                if (step.standard() && pair.supported()) assertTrue(Math.abs(pair.deviation()) <= 0.5 / step.perStop + 0.1, step + " " + pair);
            }
            assertEquals(keys.size(), new HashSet<>(keys).size());
        }
        // At the widest end the standard steps' table starts at f/0.5; Exact keeps f/1 as before.
        assertEquals(0.5, ExposureSolver.equivalents(solve(Request.of(Step.THIRD, 0.5, 1 / 8000.0, 100)), 3).getFirst().aperture());
        assertEquals(1.0, ExposureSolver.equivalents(solve(Request.of(Step.EXACT, 0.5, 1 / 8000.0, 100)), 3).getFirst().aperture());
    }
}
