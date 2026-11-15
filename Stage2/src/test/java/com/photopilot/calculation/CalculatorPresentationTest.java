package com.photopilot.calculation;

import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.DistanceAxis;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.application.CalculatorPresentation.Scale;
import com.photopilot.application.CalculatorPresentation.SpeedUnit;
import com.photopilot.application.MotionBlurPreview;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.CameraSetup;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Display conventions of the calculator workspace: scales, units, text, diagram axis and preview geometry. No JavaFX. */
class CalculatorPresentationTest {
    final LocalizationService english = LocalizationService.english();

    @Test void nominalMarkingsAreStoredLiterallyAndRoundTripThroughTheSlider() {
        for (List<Double> markings : List.of(CalculatorPresentation.APERTURES, CalculatorPresentation.SHUTTERS, CalculatorPresentation.ISOS, CalculatorPresentation.FOCAL_LENGTHS)) {
            Scale scale = Scale.nominal(markings);
            assertTrue(scale.snaps()); assertEquals(markings.size() - 1, scale.positions());
            for (int i = 0; i < markings.size(); i++) {
                if (i > 0) assertTrue(markings.get(i) > markings.get(i - 1), "ascending");
                assertEquals(markings.get(i), scale.value(i), 0.0, "a whole position is the marking itself");
                assertEquals(i, scale.position(markings.get(i)), 1e-9);
            }
        }
        // f/5.6 is 5.6 and 1/125 s is 0.008 s: the exact power-of-two series is not substituted.
        assertTrue(CalculatorPresentation.APERTURES.contains(5.6)); assertFalse(CalculatorPresentation.APERTURES.contains(Math.pow(2, 2.5)));
        assertTrue(CalculatorPresentation.SHUTTERS.contains(1 / 125.0)); assertTrue(CalculatorPresentation.ISOS.contains(125.0));
        // Neighbouring third-stop markings are about a third of a stop apart (aperture: a sixth of a doubling of N).
        var apertures = CalculatorPresentation.APERTURES;
        for (int i = 1; i < apertures.size(); i++) assertEquals(1 / 3.0, 2 * Math.log(apertures.get(i) / apertures.get(i - 1)) / Math.log(2), 0.17);
        // A typed value between two markings sits between them and comes back unchanged.
        Scale aperture = Scale.nominal(apertures);
        double between = aperture.position(6);
        assertTrue(between > apertures.indexOf(5.6) && between < apertures.indexOf(6.3));
        assertEquals(6, aperture.value(between), 1e-9);
        assertEquals(0, aperture.position(0.1), 0.0); assertEquals(aperture.positions(), aperture.position(500), 0.0);
        assertEquals(apertures.indexOf(8.0), CalculatorPresentation.nearest(apertures, 8.3));
        assertTrue(CalculatorPresentation.FULL_STOP_APERTURES.stream().allMatch(apertures::contains));
        assertTrue(CalculatorPresentation.FULL_STOP_SHUTTERS.stream().allMatch(CalculatorPresentation.SHUTTERS::contains));
        assertTrue(CalculatorPresentation.FULL_STOP_ISOS.stream().allMatch(CalculatorPresentation.ISOS::contains));
    }
    @Test void continuousScalesCoverTheirRangeAndGiveReadableValues() {
        Scale distance = Scale.logarithmic(0.1, 1000);
        assertEquals(0.1, distance.value(0), 0.0); assertEquals(1000, distance.value(distance.positions()), 0.0);
        double previous = 0;
        for (int position = 0; position <= distance.positions(); position += 10) {
            double value = distance.value(position);
            assertTrue(value >= previous && value >= 0.1 && value <= 1000); previous = value;
            assertEquals(CalculatorPresentation.significant(value, 3), value, 0.0, "three significant digits");
            assertEquals(position, distance.position(value), 1.5, "the slider stays where the user put it");
        }
        Scale stops = Scale.linear(0, 20, 0.1);
        assertEquals(200, stops.positions(), 0.0);
        assertEquals(0.3, stops.value(3), 0.0, "no binary noise in stepped values"); assertEquals(10, stops.value(100), 0.0);
        assertEquals(64, stops.position(6.4), 1e-9);
    }
    @Test void unitsConvertExactlyAtTheirBoundary() {
        assertEquals(3.048, LengthUnit.FEET.toMetres(10), 1e-12); assertEquals(10, LengthUnit.FEET.fromMetres(3.048), 1e-12);
        assertEquals(7.5, LengthUnit.METRES.toMetres(7.5), 0.0);
        assertEquals(10, SpeedUnit.KILOMETRES_PER_HOUR.toMetresPerSecond(36), 1e-12);
        assertEquals(36, SpeedUnit.KILOMETRES_PER_HOUR.fromMetresPerSecond(10), 1e-12);
        assertEquals(26.8224, SpeedUnit.MILES_PER_HOUR.toMetresPerSecond(60), 1e-12);
        assertEquals("m", LengthUnit.METRES.symbol); assertEquals("ft", LengthUnit.FEET.symbol);
        assertEquals(List.of("m/s", "km/h", "mph"), java.util.Arrays.stream(SpeedUnit.values()).map(unit -> unit.symbol).toList());
    }
    @Test void exposureTimesReadAsFractionsSecondsMinutesAndHours() {
        assertEquals("1/125 s", CalculatorPresentation.exposureTime(1 / 125.0, english));
        assertEquals("1/8000 s", CalculatorPresentation.exposureTime(1 / 8000.0, english));
        assertEquals("1/2083 s", CalculatorPresentation.exposureTime(0.00048, english));
        assertEquals("1/4 s", CalculatorPresentation.exposureTime(0.25, english));
        assertEquals("0.5 s", CalculatorPresentation.exposureTime(0.5, english));
        assertEquals("8.19 s", CalculatorPresentation.exposureTime(8.192, english));
        assertEquals("30 s", CalculatorPresentation.exposureTime(30, english));
        assertEquals("2 min 11 s", CalculatorPresentation.exposureTime(131.072, english));
        assertEquals("1 min 0 s", CalculatorPresentation.exposureTime(59.97, english));
        assertEquals("1 h 10 min", CalculatorPresentation.exposureTime(4194.304, english), "69.9 minutes");
        assertEquals("1 d 13 h", CalculatorPresentation.exposureTime(134217.728, english));
        assertEquals(CalculatorPresentation.INFINITY, CalculatorPresentation.exposureTime(Double.POSITIVE_INFINITY, english), "no limit is never a number");
        assertEquals(CalculatorPresentation.NONE, CalculatorPresentation.exposureTime(Double.NaN, english));
        assertEquals("1/125", CalculatorPresentation.shutterMark(1 / 125.0)); assertEquals("1/8000", CalculatorPresentation.shutterMark(1 / 8000.0));
        assertEquals("0.3", CalculatorPresentation.shutterMark(0.3)); assertEquals("30", CalculatorPresentation.shutterMark(30));
        // Field text: the fraction when the time is one, otherwise decimal seconds that parse back to the same value.
        assertEquals("1/125", CalculatorPresentation.shutterField(1 / 125.0)); assertEquals("0.0123", CalculatorPresentation.shutterField(0.0123));
        assertEquals("2.5", CalculatorPresentation.shutterField(2.5));
    }
    @Test void numbersKeepPrecisionApartFromDisplayRounding() {
        assertEquals("0.025", CalculatorPresentation.number(0.025, 3)); assertEquals("4.17", CalculatorPresentation.number(4.1666666667, 3));
        assertEquals("12346", CalculatorPresentation.number(12345.678, 3), "whole digits are not rounded away");
        assertEquals("0", CalculatorPresentation.number(0, 3)); assertEquals("0", CalculatorPresentation.number(-0.0, 3));
        assertEquals(CalculatorPresentation.INFINITY, CalculatorPresentation.number(Double.POSITIVE_INFINITY, 3));
        assertEquals(CalculatorPresentation.NONE, CalculatorPresentation.number(Double.NaN, 3));
        assertEquals("1.5E12", CalculatorPresentation.number(1.5e12, 3), "huge values are not shown as ordinary measurements");
        assertEquals("12.97", CalculatorPresentation.decimals(12.9657842847, 2)); assertEquals("0.00", CalculatorPresentation.decimals(-0.001, 2));
        assertEquals("+1.3", CalculatorPresentation.signedStops(1.3333)); assertEquals("−0.7", CalculatorPresentation.signedStops(-0.6667));
        assertEquals("0.0", CalculatorPresentation.signedStops(0.01));
        assertEquals("f/5.6", CalculatorPresentation.aperture(5.6)); assertEquals("39.6°", CalculatorPresentation.angle(39.597752709));
        assertEquals("0.069%", CalculatorPresentation.percent(0.0694444)); assertEquals("4.63%", CalculatorPresentation.percent(4.6296));
        assertEquals("25 µm", CalculatorPresentation.sensorLength(0.025)); assertEquals("1.67 mm", CalculatorPresentation.sensorLength(1.6667));
        assertEquals("0 mm", CalculatorPresentation.sensorLength(0));
        assertEquals("10.4667", CalculatorPresentation.fieldNumber(10.466666666666667));
    }
    @Test void distancesChooseAReadableMagnitudeAndNeverHideInfinity() {
        assertEquals("3 m", CalculatorPresentation.distance(3, LengthUnit.METRES)); assertEquals("2.34 m", CalculatorPresentation.distance(2.3379052369, LengthUnit.METRES));
        assertEquals("85.3 cm", CalculatorPresentation.distance(0.853, LengthUnit.METRES)); assertEquals("8.5 mm", CalculatorPresentation.distance(0.0085, LengthUnit.METRES));
        assertEquals("1.25 km", CalculatorPresentation.distance(1250, LengthUnit.METRES));
        assertEquals("9.84 ft", CalculatorPresentation.distance(3, LengthUnit.FEET)); assertEquals("6 in", CalculatorPresentation.distance(0.1524, LengthUnit.FEET));
        assertEquals("2 mi", CalculatorPresentation.distance(3218.688, LengthUnit.FEET));
        // A large finite distance is a number; only an unbounded one is the infinity sign.
        assertEquals("152 km", CalculatorPresentation.distance(152000, LengthUnit.METRES));
        assertEquals("5E16 km", CalculatorPresentation.distance(5e19, LengthUnit.METRES));
        assertEquals(CalculatorPresentation.INFINITY, CalculatorPresentation.distance(Double.POSITIVE_INFINITY, LengthUnit.METRES));
        assertEquals(CalculatorPresentation.INFINITY, CalculatorPresentation.distance(Double.POSITIVE_INFINITY, LengthUnit.FEET));
    }
    @Test void equivalentExposuresKeepTheSettingsEv() {
        // EV 13 = f/8 at 1/128 s; one stop wider needs half the time.
        var pairs = CalculatorPresentation.equivalents(13, 8, 3);
        assertEquals(7, pairs.size());
        assertEquals(List.of(2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0), pairs.stream().map(CalculatorPresentation.ExposurePair::fNumber).toList());
        for (var pair : pairs) assertEquals(13, 2 * Math.log(pair.fNumber()) / Math.log(2) - Math.log(pair.seconds()) / Math.log(2), 1e-12);
        assertEquals(1 / 128.0, pairs.get(3).seconds(), 1e-15);
        // At the ends of the aperture scale the table still has seven rows.
        assertEquals(7, CalculatorPresentation.equivalents(13, 1, 3).size()); assertEquals(1.0, CalculatorPresentation.equivalents(13, 1, 3).getFirst().fNumber());
        assertEquals(64.0, CalculatorPresentation.equivalents(13, 64, 3).getLast().fNumber());
    }
    @Test void commercialNdNamesAreRoundedLabelsNotExactConversions() {
        var nd8 = CalculatorPresentation.commercialFilter(3).orElseThrow();
        assertEquals("ND8", nd8.name()); assertFalse(nd8.rounded(), "ND8 is exactly 3 stops");
        var nd1000 = CalculatorPresentation.commercialFilter(10).orElseThrow();
        assertEquals("ND1000", nd1000.name()); assertTrue(nd1000.rounded());
        assertEquals(10, nd1000.advertisedStops()); assertEquals(9.9658, nd1000.exactStops(), 1e-4);
        assertEquals("ND1000", CalculatorPresentation.commercialFilter(9.9658).orElseThrow().name());
        assertTrue(CalculatorPresentation.commercialFilter(13.5).isEmpty()); assertTrue(CalculatorPresentation.commercialFilter(0).isEmpty());
    }
    @Test void distanceAxisNeverTurnsAClippedLimitIntoInfinity() {
        var axis = DistanceAxis.fit(3, 4.185);
        assertEquals(6, axis.max(), 0.0); assertEquals(0.5, axis.fraction(3), 1e-12); assertEquals(3, axis.distance(0.5), 1e-12);
        assertEquals(DistanceAxis.Extent.WITHIN, axis.extent(4.185));
        assertEquals(DistanceAxis.Extent.BEYOND_VIEW, axis.extent(152.4), "finite but outside the diagram");
        assertEquals(DistanceAxis.Extent.BEYOND_VIEW, axis.extent(1e30));
        assertEquals(DistanceAxis.Extent.UNBOUNDED, axis.extent(Double.POSITIVE_INFINITY));
        assertTrue(axis.fraction(152.4) > 1, "an off-axis distance keeps its own position");
        // The focus distance stays inside the view with room beyond it; a nearby far limit is included.
        for (double focus : new double[] { 0.12, 0.5, 1, 2.7, 3, 9.99, 10, 47, 250, 999 }) for (double far : new double[] { focus * 1.2, focus * 3.9, focus * 50, Double.POSITIVE_INFINITY }) {
            var fitted = DistanceAxis.fit(focus, far);
            assertTrue(fitted.fraction(focus) <= 0.55 + 1e-9 && fitted.fraction(focus) > 0.14, focus + " on " + fitted.max());
            if (far <= 4 * focus) assertEquals(DistanceAxis.Extent.WITHIN, fitted.extent(far));
            double ticks = fitted.max() / fitted.tick();
            assertTrue(ticks >= 4 - 1e-9 && ticks <= 10 + 1e-9, "ticks " + ticks);
        }
    }
    @Test void detailViewScaleIsChosenFromTheBlurAndAlwaysStated() {
        // 4.17 px of blur in a 300 px wide view: at 24 screen px per image px it spans 100 px, within 42% of the view.
        var pixels = MotionBlurPreview.detail(0.025, 36, OptionalInt.of(6000), 300);
        assertTrue(pixels.imagePixels()); assertEquals(4.1666666667, pixels.blurUnits(), 1e-9);
        assertEquals(24, pixels.screenPerUnit(), 0.0); assertEquals(300 / 24.0, pixels.unitsAcross(), 1e-12);
        assertTrue(pixels.blurUnits() * pixels.screenPerUnit() <= 0.42 * 300 + 1e-9);
        // A long blur reduces the view instead of running off it.
        var streak = MotionBlurPreview.detail(1.6667, 36, OptionalInt.of(6000), 300);
        assertTrue(streak.screenPerUnit() < 1); assertTrue(streak.blurUnits() * streak.screenPerUnit() <= 0.42 * 300 + 1e-9);
        assertEquals(Math.rint(Math.log(streak.screenPerUnit()) / Math.log(2)), Math.log(streak.screenPerUnit()) / Math.log(2), 1e-12, "whole powers of two");
        // Without the image width the unit is a micrometre on the sensor, never a made-up pixel.
        var physical = MotionBlurPreview.detail(0.025, 36, OptionalInt.empty(), 300);
        assertFalse(physical.imagePixels()); assertEquals(25, physical.blurUnits(), 1e-12);
        assertEquals(5, physical.screenPerUnit(), 0.0); assertEquals(60, physical.unitsAcross(), 1e-12);
        var none = MotionBlurPreview.detail(0, 36, OptionalInt.of(6000), 300);
        assertEquals(0, none.blurUnits(), 0.0); assertEquals(24, none.screenPerUnit(), 0.0);
        for (double blur : new double[] { 1e-6, 0.001, 0.01, 0.2, 3, 36, 500 }) for (OptionalInt width : List.of(OptionalInt.empty(), OptionalInt.of(6000))) {
            var detail = MotionBlurPreview.detail(blur, 36, width, 320);
            assertTrue(detail.blurUnits() * detail.screenPerUnit() <= 0.42 * 320 + 1e-9, "blur " + blur);
            assertEquals(320, detail.unitsAcross() * detail.screenPerUnit(), 1e-9);
        }
    }
    @Test void previewSmearIsTheBoxAverageOfTheMovingSubject() {
        // A block from 10 to 20 moving 4 in total: full coverage in its core, linear ramps of length 4 at both ends.
        assertEquals(1, MotionBlurPreview.coverage(15, 10, 10, 4), 1e-12);
        assertEquals(0.5, MotionBlurPreview.coverage(10, 10, 10, 4), 1e-12); assertEquals(0.5, MotionBlurPreview.coverage(20, 10, 10, 4), 1e-12);
        assertEquals(0.25, MotionBlurPreview.coverage(9, 10, 10, 4), 1e-12); assertEquals(0, MotionBlurPreview.coverage(7.9, 10, 10, 4), 0.0);
        // A blur longer than the block never reaches full coverage: the block is thinned to width / blur.
        assertEquals(0.25, MotionBlurPreview.coverage(15, 10, 10, 40), 1e-12);
        // No motion: the sharp block itself.
        assertEquals(1, MotionBlurPreview.coverage(10, 10, 10, 0), 0.0); assertEquals(0, MotionBlurPreview.coverage(9.99, 10, 10, 0), 0.0);

        float[] mask = new float[40 * 2];
        for (int x = 10; x < 20; x++) mask[x] = 1;                                    // row 0: a 10 px block; row 1 stays empty
        assertArrayEquals(mask, MotionBlurPreview.smear(mask, 40, 2, 0), "zero blur changes nothing");
        float[] smeared = MotionBlurPreview.smear(mask, 40, 2, 6);
        double sharp = 0, blurred = 0;
        for (int x = 0; x < 40; x++) { sharp += mask[x]; blurred += smeared[x]; assertEquals(0, smeared[40 + x], 0.0); }
        assertEquals(sharp, blurred, 1e-4, "motion spreads the subject, it does not add or remove any");
        assertEquals(1, smeared[15], 1e-6); assertEquals(0.5, smeared[9], 0.1); assertEquals(0, smeared[5], 0.0); assertTrue(smeared[8] > 0 && smeared[8] < smeared[11]);
        float[] wider = MotionBlurPreview.smear(mask, 40, 2, 12);
        int touched6 = 0, touched12 = 0;
        for (int x = 0; x < 40; x++) { if (smeared[x] > 0) touched6++; if (wider[x] > 0) touched12++; }
        assertEquals(16, touched6); assertEquals(22, touched12, "the streak grows by exactly the blur length");
    }
    @Test void referenceFigureMaskIsDrawnToScale() {
        float[] mask = MotionBlurPreview.Figure.mask(60, 100, 30, 90, 80);
        int top = -1, bottom = -1; double area = 0;
        for (int y = 0; y < 100; y++) for (int x = 0; x < 60; x++) if (mask[y * 60 + x] > 0) { if (top < 0) top = y; bottom = y; area += mask[y * 60 + x]; }
        assertEquals(10, top, 1); assertEquals(89, bottom, 1); assertTrue(area > 800 && area < 2400, "area " + area);
        assertTrue(MotionBlurPreview.Figure.contains(0, 0.9) && MotionBlurPreview.Figure.contains(0, 0.6) && MotionBlurPreview.Figure.contains(0.07, 0.2));
        assertFalse(MotionBlurPreview.Figure.contains(0, 0.2), "gap between the legs"); assertFalse(MotionBlurPreview.Figure.contains(0.4, 0.5));
        // A figure taller than the frame is cut off at the frame, and a zero-size one draws nothing.
        float[] clipped = MotionBlurPreview.Figure.mask(20, 20, 10, 20, 400);
        assertEquals(400, clipped.length); assertTrue(clipped[19 * 20 + 10 + 3] >= 0);
        for (float value : MotionBlurPreview.Figure.mask(20, 20, 10, 20, 0)) assertEquals(0, value, 0.0);
    }
    @Test void cameraSetupStoresActualDimensionsAndADocumentedCircleOfConfusion() {
        var fullFrame = CameraSetup.DEFAULT;
        assertEquals(36, fullFrame.sensorWidthMm(), 0.0); assertEquals(24, fullFrame.sensorHeightMm(), 0.0);
        assertEquals(CameraSetup.CocPolicy.DIAGONAL_1500, fullFrame.cocPolicy());
        assertEquals(Math.hypot(36, 24) / 1500, fullFrame.cocMm(), 1e-15); assertEquals(0.0288, fullFrame.cocMm(), 5e-5);
        assertTrue(fullFrame.imageWidthPx().isEmpty(), "no pixel dimensions are assumed");
        // Formats sold under one name are not given one size: the two APS-C presets differ, and custom sizes are kept as entered.
        var formats = CameraSetup.FORMATS;
        var apsc = formats.stream().filter(format -> format.id().equals("apsc")).findFirst().orElseThrow();
        var canon = formats.stream().filter(format -> format.id().equals("apscCanon")).findFirst().orElseThrow();
        assertNotEquals(apsc.widthMm(), canon.widthMm());
        var custom = fullFrame.withSensor(23.6, 15.7);
        assertEquals(CameraSetup.CUSTOM, custom.formatId()); assertEquals(23.6, custom.sensorWidthMm(), 0.0); assertTrue(custom.format().isEmpty());
        assertEquals(Math.hypot(23.6, 15.7) / 1500, custom.cocMm(), 1e-15, "the policy follows the actual diagonal");
        assertEquals(Math.hypot(23.6, 15.7) / 1730, custom.withCoc(CameraSetup.CocPolicy.DIAGONAL_1730, 0.03).cocMm(), 1e-15);
        assertEquals(0.02, custom.withCoc(CameraSetup.CocPolicy.CUSTOM, 0.02).cocMm(), 0.0, "an explicit value overrides the policy");
        // Pixel dimensions belong to one sensor area: kept when that sensor is refined, cleared when another format is chosen.
        var withPixels = fullFrame.withImage(OptionalInt.of(6000), OptionalInt.of(4000));
        assertEquals(6000, withPixels.withSensor(35.9, 24).imageWidthPx().getAsInt());
        assertTrue(withPixels.withFormat(apsc).imageWidthPx().isEmpty());
        assertEquals(apsc, withPixels.withFormat(apsc).format().orElseThrow());
        for (double bad : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> fullFrame.withSensor(bad, 24));
            assertThrows(IllegalArgumentException.class, () -> fullFrame.withCoc(CameraSetup.CocPolicy.CUSTOM, bad));
        }
        assertThrows(IllegalArgumentException.class, () -> fullFrame.withImage(OptionalInt.of(0), OptionalInt.empty()));
    }
}
