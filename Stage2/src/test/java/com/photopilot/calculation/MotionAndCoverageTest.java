package com.photopilot.calculation;

import com.google.gson.JsonParser;
import com.photopilot.agent.CalculationTool;
import com.photopilot.application.CalculationForm;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.photopilot.service.TechnicalCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The validated calculator entry point shared by the Facade, CLI, agent tool and GUI: the new motion blur operation,
 * optional scene coverage, and the hyperfocal boundary across the metre / millimetre conversion.
 */
class MotionAndCoverageTest {
    final TechnicalCalculator service = new TechnicalCalculator();
    static final Map<String, String> MOTION_UNITS = Map.of("shutter", "s", "focalLength", "mm", "distance", "m", "speed", "m/s", "sensorWidth", "mm", "imageWidth", "px", "blurLimit", "px");

    CalculationInput motion(Map<String, Double> parameters) {
        var units = new HashMap<String, String>(); parameters.keySet().forEach(key -> units.put(key, MOTION_UNITS.get(key)));
        return new CalculationInput(CalculationKind.MOTION_BLUR, parameters, units);
    }
    Map<String, Double> reference() { return new HashMap<>(Map.of("shutter", 0.001, "focalLength", 50.0, "distance", 20.0, "speed", 10.0, "sensorWidth", 36.0)); }
    CalculationResult ok(CalculationInput input) { var result = service.calculate(input); assertTrue(result.isSuccess(), result.errors().toString()); return result.value().orElseThrow(); }

    @Test void motionBlurReferenceCaseWithAndWithoutResolution() {
        var physical = ok(motion(reference()));
        assertEquals(0.025, physical.values().get(SENSOR_BLUR), 1e-15);
        assertEquals(0.025 / 36 * 100, physical.values().get(BLUR_SHARE), 1e-12);
        assertEquals(0.01, physical.values().get(SUBJECT_TRAVEL), 1e-15, "10 m/s for 1/1000 s");
        assertEquals(Map.of(SENSOR_BLUR, "mm", BLUR_SHARE, "%", SUBJECT_TRAVEL, "m"), physical.units());
        // Without the image width there is no pixel figure at all, and the result says why.
        assertFalse(physical.values().containsKey(IMAGE_BLUR));
        assertTrue(physical.assumptions().stream().anyMatch(note -> note.contains("megapixel")));
        assertTrue(physical.assumptions().getFirst().contains("stationary camera"));
        assertTrue(physical.estimated());

        var withPixels = reference(); withPixels.put("imageWidth", 6000.0);
        var image = ok(motion(withPixels));
        assertEquals(4.1666666667, image.values().get(IMAGE_BLUR), 1e-9);
        assertEquals("px", image.units().get(IMAGE_BLUR));
        assertEquals(physical.values().get(SENSOR_BLUR), image.values().get(SENSOR_BLUR), 0.0, "resolution does not change the physical blur");
        assertSame(service.motionBlur(motion(withPixels)).value().orElseThrow().input().operation(), CalculationKind.MOTION_BLUR);
        assertFalse(service.motionBlur(new CalculationInput(CalculationKind.HYPERFOCAL, Map.of(), Map.of())).isSuccess());
    }
    @Test void motionBlurScalesAndZeroSpeedGivesNoBlurAndNoLimit() {
        double base = ok(motion(reference())).values().get(SENSOR_BLUR);
        var doubled = reference(); doubled.put("shutter", 0.002);
        assertEquals(2 * base, ok(motion(doubled)).values().get(SENSOR_BLUR), 1e-15);
        var farther = reference(); farther.put("distance", 40.0);
        assertEquals(base / 2, ok(motion(farther)).values().get(SENSOR_BLUR), 1e-15);

        var still = reference(); still.put("speed", 0.0); still.put("imageWidth", 6000.0); still.put("blurLimit", 2.0);
        var result = ok(motion(still));
        assertEquals(0, result.values().get(SENSOR_BLUR), 0.0);
        assertEquals(0, result.values().get(IMAGE_BLUR), 0.0);
        assertEquals(0, result.values().get(BLUR_SHARE), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, result.values().get(LONGEST_EXPOSURE), "no motion-imposed upper limit, not a division by zero");
        assertTrue(result.assumptions().stream().anyMatch(note -> note.contains("no upper limit")));
        assertTrue(CalculationForm.format(result).contains("Infinity s"));
    }
    @Test void longestExposureForABlurLimitInPixelsOrOnTheSensor() {
        // 2 px of 6000 px on 36 mm is 0.012 mm: 0.012 * 20 / (50 * 10) = 0.00048 s.
        var pixels = reference(); pixels.put("imageWidth", 6000.0); pixels.put("blurLimit", 2.0);
        assertEquals(0.00048, ok(motion(pixels)).values().get(LONGEST_EXPOSURE), 1e-15);
        var onSensor = new CalculationInput(CalculationKind.MOTION_BLUR, Map.of("shutter", 0.001, "focalLength", 50.0, "distance", 20.0, "speed", 10.0, "sensorWidth", 36.0, "blurLimit", 12.0),
            Map.of("shutter", "s", "focalLength", "mm", "distance", "m", "speed", "m/s", "sensorWidth", "mm", "blurLimit", "um"));
        assertEquals(0.00048, ok(onSensor).values().get(LONGEST_EXPOSURE), 1e-15, "12 um on the sensor is the same limit");
        // A pixel limit has no physical size without the image width.
        var missing = reference(); missing.put("blurLimit", 2.0);
        var refused = service.calculate(motion(missing));
        assertFalse(refused.isSuccess()); assertTrue(refused.hasError("error.calc.blurLimitPixels"));
    }
    @Test void motionBlurUnitConversionsAndInvalidInputs() {
        // 36 km/h = 10 m/s; 20 m = 65.6168 ft; 1 ms = 0.001 s; 22.3694 mph = 10 m/s.
        var mixed = new CalculationInput(CalculationKind.MOTION_BLUR, Map.of("shutter", 1.0, "focalLength", 5.0, "distance", 20 / 0.3048, "speed", 36.0, "sensorWidth", 3.6),
            Map.of("shutter", "ms", "focalLength", "cm", "distance", "ft", "speed", "km/h", "sensorWidth", "cm"));
        assertEquals(0.025, ok(mixed).values().get(SENSOR_BLUR), 1e-12);
        var mph = new CalculationInput(CalculationKind.MOTION_BLUR, Map.of("shutter", 0.001, "focalLength", 50.0, "distance", 20.0, "speed", 10 / 0.44704, "sensorWidth", 36.0),
            Map.of("shutter", "s", "focalLength", "mm", "distance", "m", "speed", "mph", "sensorWidth", "mm"));
        assertEquals(0.025, ok(mph).values().get(SENSOR_BLUR), 1e-12);
        for (String key : List.of("shutter", "focalLength", "distance", "sensorWidth")) for (double bad : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            var values = reference(); values.put(key, bad);
            assertFalse(service.calculate(motion(values)).isSuccess(), key + "=" + bad);
        }
        for (double bad : new double[] { -0.1, Double.NaN, Double.POSITIVE_INFINITY }) { var values = reference(); values.put("speed", bad); assertFalse(service.calculate(motion(values)).isSuccess()); }
        for (double bad : new double[] { 0, -6000, 6000.5, Double.NaN }) { var values = reference(); values.put("imageWidth", bad); assertFalse(service.calculate(motion(values)).isSuccess(), "imageWidth=" + bad); }
        for (String missing : List.of("shutter", "focalLength", "distance", "speed", "sensorWidth")) { var values = reference(); values.remove(missing); assertFalse(service.calculate(motion(values)).isSuccess(), missing); }
        var unknown = new CalculationInput(CalculationKind.MOTION_BLUR, Map.of("shutter", 0.001, "focalLength", 50.0, "distance", 20.0, "speed", 10.0, "sensorWidth", 36.0, "fps", 60.0),
            Map.of("shutter", "s", "focalLength", "mm", "distance", "m", "speed", "m/s", "sensorWidth", "mm", "fps", "fps"));
        assertFalse(service.calculate(unknown).isSuccess(), "no video parameters");
        var wrongUnit = new CalculationInput(CalculationKind.MOTION_BLUR, reference(), Map.of("shutter", "s", "focalLength", "mm", "distance", "m", "speed", "knots", "sensorWidth", "mm"));
        assertFalse(service.calculate(wrongUnit).isSuccess());
        // The approximation needs the subject beyond the focal length, and says so when it is close.
        var inside = reference(); inside.put("distance", 0.05);
        assertTrue(service.calculate(motion(inside)).hasError("error.calc.distance"));
        var close = reference(); close.put("distance", 0.3);
        assertTrue(ok(motion(close)).assumptions().stream().anyMatch(note -> note.contains("closer than 10 focal lengths")));
        // Huge values fail cleanly instead of presenting an overflow as a measurement.
        var huge = reference(); huge.put("speed", 1e308); huge.put("focalLength", 1e300);
        assertFalse(service.calculate(motion(huge)).isSuccess());
    }
    @Test void fieldOfViewKeepsItsThreeAnglesAndAddsCoverageOnlyWithADistance() {
        var units = Map.of("focalLength", "mm", "sensorWidth", "mm", "sensorHeight", "mm");
        var angles = ok(new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", 50.0, "sensorWidth", 36.0, "sensorHeight", 24.0), units));
        // Unchanged output for an unchanged input: stored plan calculations are recomputed and compared key for key.
        assertEquals(Set.of(HORIZONTAL_FOV, VERTICAL_FOV, DIAGONAL_FOV), angles.values().keySet());
        assertEquals(39.597752709, angles.values().get(HORIZONTAL_FOV), 1e-9);
        var withDistance = new HashMap<>(units); withDistance.put("distance", "m");
        var coverage = ok(new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", 50.0, "sensorWidth", 36.0, "sensorHeight", 24.0, "distance", 20.0), withDistance));
        assertEquals(14.4, coverage.values().get(SCENE_WIDTH), 1e-12);
        assertEquals(9.6, coverage.values().get(SCENE_HEIGHT), 1e-12);
        assertEquals(angles.values().get(HORIZONTAL_FOV), coverage.values().get(HORIZONTAL_FOV), 0.0, "the distance does not change the angles");
        assertEquals("m", coverage.units().get(SCENE_WIDTH));
        withDistance.put("distance", "ft");
        var feet = ok(new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", 50.0, "sensorWidth", 36.0, "sensorHeight", 24.0, "distance", 20 / 0.3048), withDistance));
        assertEquals(14.4, feet.values().get(SCENE_WIDTH), 1e-9);
        // Narrower with a longer lens, for unchanged sensor dimensions.
        var longer = ok(new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", 85.0, "sensorWidth", 36.0, "sensorHeight", 24.0), units));
        assertTrue(longer.values().get(HORIZONTAL_FOV) < angles.values().get(HORIZONTAL_FOV));
        for (double bad : new double[] { 0, -3, Double.NaN, Double.POSITIVE_INFINITY })
            assertFalse(service.calculate(new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", 50.0, "sensorWidth", 36.0, "sensorHeight", 24.0, "distance", bad), withDistance)).isSuccess());
    }
    @Test void outputsOfTheOriginalOperationsKeepTheirNames() {
        var exposure = ok(CalculationForm.parse(CalculationForm.Tool.EXPOSURE, Map.of("aperture", "8", "shutter", "1/125", "iso", "200")).value().orElseThrow());
        assertEquals(Set.of("Camera setting EV", "ISO-normalized EV100"), exposure.values().keySet());
        assertEquals(12.9657842847, exposure.values().get(SETTING_EV), 1e-9);
        assertEquals(11.9657842847, exposure.values().get(EV100), 1e-9);
        var nd = ok(CalculationForm.parse(CalculationForm.Tool.ND, Map.of("shutter", "1/125", "ndStops", "0")).value().orElseThrow());
        assertEquals(Set.of("ND strength", "Adjusted shutter"), nd.values().keySet());
        assertEquals(0.008, nd.values().get(ADJUSTED_SHUTTER), 0.0, "zero stops returns the original exposure time exactly");
        var hyperfocal = ok(CalculationForm.parse(CalculationForm.Tool.HYPERFOCAL, Map.of("focalLength", "50", "aperture", "8", "coc", ".03")).value().orElseThrow());
        assertEquals(Set.of("Hyperfocal distance"), hyperfocal.values().keySet());
        var dof = ok(CalculationForm.parse(CalculationForm.Tool.DOF, Map.of("focalLength", "50", "aperture", "8", "coc", ".03", "distance", "3")).value().orElseThrow());
        assertEquals(Set.of("Hyperfocal distance", "Near focus limit", "Far focus limit", "Total depth of field"), dof.values().keySet());
    }
    @Test void hyperfocalCalculatorAgreesWithDepthOfFieldFocusedAtItsResult() {
        for (double f : new double[] { 8, 14, 24, 35, 50, 85, 135, 200, 400, 800 }) for (double n : new double[] { 1.4, 2.8, 5.6, 8, 11, 16, 22 })
            for (double c : new double[] { 0.015, 0.0288444, 0.03 }) {
                var parameters = Map.of("focalLength", f, "aperture", n, "coc", c);
                var hyperfocal = ok(new CalculationInput(CalculationKind.HYPERFOCAL, parameters, Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm")));
                double h = hyperfocal.values().get(HYPERFOCAL_DISTANCE);
                assertEquals((f * f / (n * c) + f) / 1000, h, 1e-12 * h);
                // The hyperfocal calculator's near limit is the depth of field operation at exactly that distance.
                var focused = ok(CalculationForm.atHyperfocal(hyperfocal));
                assertEquals(CalculationKind.DEPTH_OF_FIELD, focused.input().operation());
                assertEquals(h, focused.values().get(HYPERFOCAL_DISTANCE), 0.0);
                assertEquals(Double.POSITIVE_INFINITY, focused.values().get(FAR_LIMIT), f + " mm f/" + n + " c=" + c);
                assertEquals(Double.POSITIVE_INFINITY, focused.values().get(TOTAL_DEPTH));
                assertEquals(h / 2, focused.values().get(NEAR_LIMIT), 1e-9 * h);
                // Just below it the far limit is finite, however large; just above it is unbounded.
                CalculationResult below = ok(dof(f, n, c, Math.nextDown(h))), above = ok(dof(f, n, c, Math.nextUp(h)));
                assertTrue(Double.isFinite(below.values().get(FAR_LIMIT)) && below.values().get(FAR_LIMIT) > 1000 * h, "below H: " + below.values().get(FAR_LIMIT));
                assertEquals(Double.POSITIVE_INFINITY, above.values().get(FAR_LIMIT));
            }
    }
    private static CalculationInput dof(double f, double n, double c, double metres) {
        return new CalculationInput(CalculationKind.DEPTH_OF_FIELD, Map.of("focalLength", f, "aperture", n, "coc", c, "distance", metres),
            Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm", "distance", "m"));
    }
    @Test void commandLineAndAgentToolUseTheSameCore(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        try (var bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            var bytes = new ByteArrayOutputStream(); var cli = new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes));
            assertEquals(0, cli.execute("calc help"));
            assertTrue(bytes.toString().contains("calc motion shutter=<s> focalLength=<mm> distance=<m> speed=<m/s> sensorWidth=<mm> [imageWidth=<px>] [blurLimit=<px>]"), bytes.toString());
            assertTrue(bytes.toString().contains("calc fov focalLength=<mm> sensorWidth=<mm> sensorHeight=<mm> [distance=<m>]"));
            bytes.reset();
            assertEquals(0, cli.execute("calc motion shutter=1/1000 focalLength=50 distance=20 speed=10 sensorWidth=36"));
            assertTrue(bytes.toString().contains("Blur on sensor: 0.025 mm"), bytes.toString());
            assertFalse(bytes.toString().contains("Blur in image"));
            bytes.reset();
            assertEquals(0, cli.execute("calc motion shutter=1/1000 focalLength=50 distance=20 speed=10 sensorWidth=36 imageWidth=6000 blurLimit=2"));
            assertTrue(bytes.toString().contains("Blur in image: 4.166667 px"), bytes.toString());
            assertTrue(bytes.toString().contains("Longest exposure within blur limit: 0.00048 s"));
            assertEquals(2, cli.execute("calc motion shutter=1/1000 focalLength=50 distance=20 speed=-1 sensorWidth=36"));
            assertEquals(2, cli.execute("calc motion shutter=1/1000 focalLength=50 distance=20 speed=10 sensorWidth=36 blurLimit=2"));
            assertEquals(2, cli.execute("calc motion shutter=1/1000 focalLength=50 distance=20"));
            bytes.reset();
            assertEquals(0, cli.execute("calc fov focalLength=50 sensorWidth=36 sensorHeight=24 distance=20"));
            assertTrue(bytes.toString().contains("Scene width at distance: 14.4 m") && bytes.toString().contains("Scene height at distance: 9.6 m"), bytes.toString());
            bytes.reset();
            assertEquals(0, cli.execute("calc hyperfocal focalLength=50 aperture=8 coc=.03"));
            assertTrue(bytes.toString().contains("10.466667 m"));
            assertTrue(bytes.toString().contains("near limit 5.233333 m, far limit Infinity"), bytes.toString());
        }
        var tool = new CalculationTool(service);
        assertTrue(tool.inputSchema().contains("MOTION_BLUR"));
        var result = tool.execute("""
            {"operation":"MOTION_BLUR","parameters":{"shutter":0.001,"focalLength":50,"distance":20,"speed":0,"sensorWidth":36,"imageWidth":6000,"blurLimit":2},
             "units":{"shutter":"s","focalLength":"mm","distance":"m","speed":"m/s","sensorWidth":"mm","imageWidth":"px","blurLimit":"px"}}
            """);
        assertTrue(result.isSuccess(), result.errors().toString());
        var values = JsonParser.parseString(result.value().orElseThrow()).getAsJsonObject().getAsJsonObject("values");
        assertEquals(0, values.get(SENSOR_BLUR).getAsDouble());
        assertEquals("Infinity", values.get(LONGEST_EXPOSURE).getAsString(), "an explicit string, never an invalid JSON number");
        assertFalse(tool.execute("{\"operation\":\"MOTION_BLUR\",\"parameters\":{\"a\":1,\"b\":1,\"c\":1,\"d\":1,\"e\":1,\"f\":1,\"g\":1,\"h\":1,\"i\":1},\"units\":{}}").isSuccess(), "bounded parameter count");
    }
}
