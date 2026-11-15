package com.photopilot.calculation;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.model.CalculationResult;
import com.photopilot.model.CameraSetup;
import com.photopilot.service.TechnicalCalculator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static com.photopilot.service.TechnicalCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The calculations behind the combined depth of field and hyperfocal page (no display needed). Both focus modes use the
 * same validated operations: manual focus is {@code DEPTH_OF_FIELD} at the distance set, hyperfocal focus is
 * {@code HYPERFOCAL} followed by {@code DEPTH_OF_FIELD} at exactly that distance. Expected values are worked out here
 * from the thin-lens definitions in decimal arithmetic (q = f²/(N·c), H = q + f, near = q·s/(q + s − f),
 * far = q·s/(q − s + f)), not taken from the code under test.
 */
class FocusCalculatorTest {
    private static final MathContext PRECISE = MathContext.DECIMAL128;
    private final TechnicalCalculator service = new TechnicalCalculator();

    private CalculationResult ok(CalculationInput input) {
        var result = service.calculate(input);
        assertTrue(result.isSuccess(), result.errors().toString());
        return result.value().orElseThrow();
    }
    private CalculationResult depthOfField(double focalMm, double fNumber, double cocMm, double distance, String unit) {
        Map<String, Double> parameters = new LinkedHashMap<>(Map.of("focalLength", focalMm, "aperture", fNumber, "coc", cocMm, "distance", distance));
        return ok(new CalculationInput(CalculationKind.DEPTH_OF_FIELD, parameters, Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm", "distance", unit)));
    }
    private CalculationResult hyperfocal(double focalMm, double fNumber, double cocMm) {
        return ok(new CalculationInput(CalculationKind.HYPERFOCAL, Map.of("focalLength", focalMm, "aperture", fNumber, "coc", cocMm),
            Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm")));
    }
    /** Hyperfocal focus as the page calculates it: the hyperfocal distance, then depth of field focused there. */
    private CalculationResult lockedAtHyperfocal(double focalMm, double fNumber, double cocMm) {
        return ok(CalculationForm.atHyperfocal(hyperfocal(focalMm, fNumber, cocMm)));
    }
    private static BigDecimal q(double f, double n, double c) { return BigDecimal.valueOf(f).pow(2).divide(BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(c)), PRECISE); }
    private static double metres(BigDecimal millimetres) { return millimetres.divide(BigDecimal.valueOf(1000), PRECISE).doubleValue(); }

    @Test void depthOfFieldAndHyperfocalResultsAreUnchangedForTheReferenceInputs() {
        // 50 mm, f/8, c = 0.03 mm, focused at 3 m.
        BigDecimal q = q(50, 8, 0.03), s = BigDecimal.valueOf(3000), f = BigDecimal.valueOf(50);
        double near = metres(q.multiply(s).divide(q.add(s).subtract(f), PRECISE)), far = metres(q.multiply(s).divide(q.subtract(s).add(f), PRECISE));
        double h = metres(q.add(f));
        assertEquals(2.3379052369, near, 1e-10); assertEquals(4.1852678571, far, 1e-10); assertEquals(10.4666666667, h, 1e-10);

        var dof = depthOfField(50, 8, 0.03, 3, "m");
        assertEquals(near, dof.values().get(NEAR_LIMIT), 1e-12); assertEquals(far, dof.values().get(FAR_LIMIT), 1e-12);
        assertEquals(far - near, dof.values().get(TOTAL_DEPTH), 1e-12); assertEquals(h, dof.values().get(HYPERFOCAL_DISTANCE), 1e-12);
        assertEquals(h, hyperfocal(50, 8, 0.03).values().get(HYPERFOCAL_DISTANCE), 1e-12);
        // F10 compares stored plan calculations key for key: the merge adds no output to either operation.
        assertEquals(Set.of(HYPERFOCAL_DISTANCE, NEAR_LIMIT, FAR_LIMIT, TOTAL_DEPTH), dof.values().keySet());
        assertEquals(Set.of(HYPERFOCAL_DISTANCE), hyperfocal(50, 8, 0.03).values().keySet());
        assertEquals(Map.of(HYPERFOCAL_DISTANCE, "m", NEAR_LIMIT, "m", FAR_LIMIT, "m", TOTAL_DEPTH, "m"), dof.units());
    }

    @Test void bothFocusModesAgreeWhenFocusedAtTheHyperfocalDistance() {
        for (double[] lens : List.of(new double[] { 50, 8, 0.03 }, new double[] { 24, 11, 0.02884 }, new double[] { 14, 2.8, 0.015 }, new double[] { 85, 1.4, 0.03 }, new double[] { 800, 5.6, 0.03 })) {
            double f = lens[0], n = lens[1], c = lens[2];
            var locked = lockedAtHyperfocal(f, n, c);
            double h = hyperfocal(f, n, c).values().get(HYPERFOCAL_DISTANCE);
            // H = f²/(N·c) + f, worked out independently.
            assertEquals(metres(q(f, n, c).add(BigDecimal.valueOf(f))), h, h * 1e-14, "H for " + f + " mm f/" + n);
            // Under this convention the near limit at s = H is exactly H/2 and the far limit is unbounded.
            assertEquals(h / 2, locked.values().get(NEAR_LIMIT), h * 1e-14);
            assertEquals(Double.POSITIVE_INFINITY, locked.values().get(FAR_LIMIT)); assertEquals(Double.POSITIVE_INFINITY, locked.values().get(TOTAL_DEPTH));
            assertEquals(h, locked.values().get(HYPERFOCAL_DISTANCE), 0.0, "the depth of field operation reports the same H");
            // Manual focus at the distance shown for H (as the page and the old button set it) is the same calculation.
            assertEquals(locked.values(), depthOfField(f, n, c, h, "m").values());
        }
    }

    @Test void theFarLimitBecomesUnboundedExactlyAtTheHyperfocalDistance() {
        double h = hyperfocal(50, 8, 0.03).values().get(HYPERFOCAL_DISTANCE);
        assertEquals(Double.POSITIVE_INFINITY, depthOfField(50, 8, 0.03, h, "m").values().get(FAR_LIMIT));
        assertEquals(Double.POSITIVE_INFINITY, depthOfField(50, 8, 0.03, h * 1.01, "m").values().get(FAR_LIMIT), "beyond H as well");
        assertEquals(Double.POSITIVE_INFINITY, depthOfField(50, 8, 0.03, 1000, "m").values().get(FAR_LIMIT));
        // Immediately below: one representable step under H is still a finite (very large) far limit, never infinity.
        var justBelow = depthOfField(50, 8, 0.03, Math.nextDown(h), "m");
        assertTrue(Double.isFinite(justBelow.values().get(FAR_LIMIT)) && justBelow.values().get(FAR_LIMIT) > 1e6, justBelow.values().toString());
        assertTrue(Double.isFinite(justBelow.values().get(TOTAL_DEPTH)));
        // Slightly below: the far limit is the finite thin-lens value, worked out in decimal arithmetic.
        BigDecimal q = q(50, 8, 0.03), f = BigDecimal.valueOf(50);
        for (double fraction : new double[] { 0.5, 0.9, 0.99, 0.999999 }) {
            double s = h * fraction;
            BigDecimal sMm = new BigDecimal(s).multiply(BigDecimal.valueOf(1000));
            double expected = metres(q.multiply(sMm).divide(q.subtract(sMm).add(f), PRECISE));
            double far = depthOfField(50, 8, 0.03, s, "m").values().get(FAR_LIMIT);
            assertEquals(expected, far, expected * 1e-6, "far limit at " + fraction + " H");
            assertTrue(Double.isFinite(far));
        }
        // The far limit grows without bound as the focus approaches H from below.
        assertTrue(depthOfField(50, 8, 0.03, h * 0.999, "m").values().get(FAR_LIMIT) < depthOfField(50, 8, 0.03, h * 0.9999, "m").values().get(FAR_LIMIT));
    }

    @Test void sensorAndCircleOfConfusionReachBothCalculationsTheSameWay() {
        for (var format : CameraSetup.FORMATS) {
            for (var policy : CameraSetup.CocPolicy.values()) {
                var camera = CameraSetup.of(format).withCoc(policy, 0.02);
                double expectedCoc = policy == CameraSetup.CocPolicy.CUSTOM ? 0.02 : Math.sqrt(format.widthMm() * format.widthMm() + format.heightMm() * format.heightMm()) / policy.divisor();
                assertEquals(expectedCoc, camera.cocMm(), 1e-15, format.id() + " " + policy);
                double h = hyperfocal(35, 8, camera.cocMm()).values().get(HYPERFOCAL_DISTANCE);
                assertEquals(h, depthOfField(35, 8, camera.cocMm(), 2, "m").values().get(HYPERFOCAL_DISTANCE), 0.0, "one H for both modes");
                assertEquals(metres(q(35, 8, camera.cocMm()).add(BigDecimal.valueOf(35))), h, h * 1e-14);
                assertEquals(Double.POSITIVE_INFINITY, lockedAtHyperfocal(35, 8, camera.cocMm()).values().get(FAR_LIMIT));
            }
        }
        // A smaller sensor (a smaller circle of confusion under the same policy) moves H further away, for both modes.
        double fullFrame = hyperfocal(35, 8, CameraSetup.DEFAULT.cocMm()).values().get(HYPERFOCAL_DISTANCE);
        double apsc = hyperfocal(35, 8, CameraSetup.DEFAULT.withFormat(CameraSetup.FORMATS.get(1)).cocMm()).values().get(HYPERFOCAL_DISTANCE);
        assertTrue(apsc > fullFrame);
        // (H − f) is inversely proportional to c.
        double h1 = hyperfocal(35, 8, 0.03).values().get(HYPERFOCAL_DISTANCE), h2 = hyperfocal(35, 8, 0.015).values().get(HYPERFOCAL_DISTANCE);
        assertEquals(2, (h2 - 0.035) / (h1 - 0.035), 1e-12);
    }

    @Test void feetAndMetresDescribeTheSamePhysicalDistance() {
        // 10 ft is exactly 3.048 m: the same calculation whichever unit the distance arrives in.
        var inFeet = depthOfField(50, 8, 0.03, 10, "ft"); var inMetres = depthOfField(50, 8, 0.03, 3.048, "m");
        for (String output : List.of(NEAR_LIMIT, FAR_LIMIT, TOTAL_DEPTH, HYPERFOCAL_DISTANCE))
            assertEquals(inMetres.values().get(output), inFeet.values().get(output), 1e-12, output);
        BigDecimal q = q(50, 8, 0.03), s = BigDecimal.valueOf(3048), f = BigDecimal.valueOf(50);
        assertEquals(metres(q.multiply(s).divide(q.add(s).subtract(f), PRECISE)), inFeet.values().get(NEAR_LIMIT), 1e-12);
        // Conversions to show a distance in feet and to read it back lose at most rounding in the last digit. (The page
        // itself keeps metres and never converts back, so a unit change leaves the value exactly as it was.)
        for (double metres : new double[] { 0.1, 0.3048, 3, 10.466666666666667, 15873.015873015873, 1000 })
            assertEquals(metres, LengthUnit.FEET.toMetres(LengthUnit.FEET.fromMetres(metres)), Math.ulp(metres) * 2, "round trip through feet");
        assertEquals("10 ft", CalculatorPresentation.distance(3.048, LengthUnit.FEET));
        assertEquals("34.3 ft", CalculatorPresentation.distance(10.466666666666667, LengthUnit.FEET));
    }

    @Test void invalidInputsAreRejectedBeforeAnyResult() {
        for (var tool : List.of(CalculationForm.Tool.DOF, CalculationForm.Tool.HYPERFOCAL)) {
            Map<String, String> good = new LinkedHashMap<>(Map.of("focalLength", "50", "aperture", "8", "coc", "0.03"));
            if (tool == CalculationForm.Tool.DOF) good.put("distance", "3");
            assertTrue(CalculationForm.parse(tool, good).isSuccess());
            for (String key : good.keySet()) {
                for (String bad : List.of("", "abc", "NaN", "Infinity", "-1", "0", "1/0")) {
                    var fields = new LinkedHashMap<>(good); fields.put(key, bad);
                    var parsed = CalculationForm.parse(tool, fields);
                    assertTrue(!parsed.isSuccess() || !service.calculate(parsed.value().orElseThrow()).isSuccess(), tool + " " + key + "=" + bad);
                }
                var missing = new LinkedHashMap<>(good); missing.remove(key);
                assertFalse(CalculationForm.parse(tool, missing).isSuccess(), tool + " without " + key);
            }
        }
        // The optical model needs the focus beyond one focal length; 5 cm with a 50 mm lens is refused, not clamped.
        assertFalse(CalculationForm.parse(CalculationForm.Tool.DOF, Map.of("focalLength", "50", "aperture", "8", "coc", ".03", "distance", ".05")).isSuccess());
        // Values that are not numbers are refused by the service too, whatever path they come from.
        var nan = new CalculationInput(CalculationKind.HYPERFOCAL, Map.of("focalLength", Double.NaN, "aperture", 8.0, "coc", 0.03), Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm"));
        assertFalse(service.calculate(nan).isSuccess());
    }
}
