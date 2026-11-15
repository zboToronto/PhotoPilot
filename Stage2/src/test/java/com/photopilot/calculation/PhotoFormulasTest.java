package com.photopilot.calculation;

import com.photopilot.service.PhotoFormulas;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared formula core in its own units (mm, s). Expected values are worked out by hand from the stated formulas or
 * taken from the task's reference figures, never produced by the code under test.
 */
class PhotoFormulasTest {
    @Test void settingsEvAndIsoNormalizedEquivalentAreDifferentQuantities() {
        // f/8 at 1/125 s: log2(64 * 125) = log2(8000).
        double ev = PhotoFormulas.settingsEv(8, 1 / 125.0);
        assertEquals(12.9657842847, ev, 1e-9);
        assertEquals(12.9657842847, PhotoFormulas.ev100(ev, 100), 1e-9, "equal at ISO 100");
        assertEquals(11.9657842847, PhotoFormulas.ev100(ev, 200), 1e-9);
        // Sensitivity is not part of the settings EV: only the normalized value moves, one stop per doubling.
        for (double iso : new double[] { 50, 100, 400, 6400 }) {
            assertEquals(ev, PhotoFormulas.settingsEv(8, 1 / 125.0));
            assertEquals(ev - Math.log(iso / 100) / Math.log(2), PhotoFormulas.ev100(ev, iso), 1e-12);
        }
        assertEquals(13, PhotoFormulas.settingsEv(8, 1 / 128.0), 1e-12);
        assertEquals(0, PhotoFormulas.settingsEv(1, 1), 0.0, "EV 0 is f/1 at 1 s");
    }
    @Test void exposureSolversInvertTheDefinition() {
        // EV 15 at f/16 is 1/128 s (256 / 32768); at 1/128 s it is f/16; ISO 400 puts EV 15 at EV100 13.
        assertEquals(1 / 128.0, PhotoFormulas.shutterFor(15, 16), 1e-15);
        assertEquals(16, PhotoFormulas.apertureFor(15, 1 / 128.0), 1e-12);
        assertEquals(400, PhotoFormulas.isoFor(13, 15), 1e-9);
        // One stop more light through the aperture is paid back by half the time.
        assertEquals(PhotoFormulas.shutterFor(12, 8) / 2, PhotoFormulas.shutterFor(12, 8 / Math.sqrt(2)), 1e-15);
    }
    @Test void ndTimeIsBaseTimesTwoToTheStops() {
        assertEquals(8.192, PhotoFormulas.ndExposure(1 / 125.0, 10), 1e-12);
        // Zero stops is the original time itself, bit for bit, for awkward values too.
        for (double base : new double[] { 1 / 125.0, 0.008, 1 / 3.0, 0.1, 30, 1e-300, 7.3e12 }) assertEquals(base, PhotoFormulas.ndExposure(base, 0), 0.0);
        // Each additional stop exactly doubles the time.
        for (double base : new double[] { 1 / 125.0, 1 / 3.0, 2.5 }) for (int stops = 0; stops < 24; stops++)
            assertEquals(2 * PhotoFormulas.ndExposure(base, stops), PhotoFormulas.ndExposure(base, stops + 1), 0.0);
        assertEquals(Math.sqrt(2), PhotoFormulas.ndExposure(1, 0.5), 1e-15);
        // More stops never shorten the exposure.
        double previous = 0;
        for (double stops = 0; stops <= 20; stops += 0.25) { double t = PhotoFormulas.ndExposure(1 / 60.0, stops); assertTrue(t > previous); previous = t; }
        // Overflow only when the result itself is unrepresentable.
        assertTrue(PhotoFormulas.ndExposure(1e-300, 1100) > 1e30);
        assertEquals(Double.POSITIVE_INFINITY, PhotoFormulas.ndExposure(1, 2000));
        assertEquals(Double.POSITIVE_INFINITY, PhotoFormulas.ndExposure(1, 1e12));
    }
    @Test void stopsFilterFactorAndOpticalDensityAreDistinctScales() {
        assertEquals(3, PhotoFormulas.stopsFromFactor(8), 1e-12);
        assertEquals(1024, PhotoFormulas.factorFromStops(10), 0.0);
        // Optical density is log10 of the factor: OD 0.9 is a factor of 7.94, not exactly ND8.
        assertEquals(0.9 / 0.30102999566398120, PhotoFormulas.stopsFromDensity(0.9), 1e-12);
        assertEquals(3.0102999566398120, PhotoFormulas.densityFromStops(10), 1e-12);
        // A filter sold as ND1000 "10 stops": factor 1000 is 9.966 stops, OD 3.0.
        assertEquals(9.965784284662087, PhotoFormulas.stopsFromFactor(1000), 1e-12);
        assertEquals(9.965784284662087, PhotoFormulas.stopsFromDensity(3.0), 1e-12);
        assertNotEquals(10, PhotoFormulas.stopsFromFactor(1000), 0.01);
    }
    @Test void hyperfocalAndDepthOfFieldShareOneThinLensModel() {
        // f = 50 mm, N = 8, c = 0.03 mm: q = 2500 / 0.24 = 10416.67 mm, H = q + f.
        double h = PhotoFormulas.hyperfocal(50, 8, 0.03);
        assertEquals(10466.6666667, h, 1e-6);
        var range = PhotoFormulas.focusRange(50, 8, 0.03, 3000);
        assertEquals(h, range.hyperfocal(), 0.0, "one definition of H");
        assertEquals(2337.9052369, range.near(), 1e-6);
        assertEquals(4185.2678571, range.far(), 1e-6);
        assertEquals(4185.2678571 - 2337.9052369, range.total(), 1e-6);
        assertFalse(range.farUnbounded());
        // The focus distance lies inside its own range.
        assertTrue(range.near() < 3000 && 3000 < range.far());
    }
    @Test void farLimitIsUnboundedExactlyFromTheHyperfocalDistance() {
        double h = PhotoFormulas.hyperfocal(50, 8, 0.03);
        var at = PhotoFormulas.focusRange(50, 8, 0.03, h);
        assertTrue(at.farUnbounded()); assertEquals(Double.POSITIVE_INFINITY, at.total());
        assertEquals(h / 2, at.near(), 1e-9, "near = H / 2 when focused at H");
        var below = PhotoFormulas.focusRange(50, 8, 0.03, h * (1 - 1e-9));
        assertFalse(below.farUnbounded(), "just below H the far limit is very large but finite");
        assertTrue(below.far() > 1e6 * h && Double.isFinite(below.far()));
        var above = PhotoFormulas.focusRange(50, 8, 0.03, h * (1 + 1e-9));
        assertTrue(above.farUnbounded());
        assertTrue(above.near() > at.near(), "the near limit keeps moving away beyond H");
        assertTrue(PhotoFormulas.focusRange(50, 8, 0.03, 1e9).farUnbounded());
        // Across lenses and apertures: exactly at H is always unbounded, with the near limit at H / 2.
        for (double f : new double[] { 8, 14, 24, 35, 50, 85, 135, 200, 400, 800 }) for (double n : new double[] { 1, 1.4, 2.8, 5.6, 8, 11, 16, 22, 64 })
            for (double c : new double[] { 0.005, 0.015, 0.0288, 0.03, 0.05 }) {
                double hyperfocal = PhotoFormulas.hyperfocal(f, n, c);
                var focused = PhotoFormulas.focusRange(f, n, c, hyperfocal);
                assertTrue(focused.farUnbounded(), f + " mm f/" + n);
                assertEquals(hyperfocal / 2, focused.near(), 1e-9 * hyperfocal);
            }
    }
    @Test void depthOfFieldRespondsToEachParameterInTheExpectedDirection() {
        var base = PhotoFormulas.focusRange(50, 8, 0.03, 3000);
        // Stopping down, a larger circle of confusion and a shorter lens all deepen the range.
        assertTrue(PhotoFormulas.focusRange(50, 11, 0.03, 3000).total() > base.total());
        assertTrue(PhotoFormulas.focusRange(50, 8, 0.05, 3000).total() > base.total());
        assertTrue(PhotoFormulas.focusRange(35, 8, 0.03, 3000).total() > base.total());
        assertTrue(PhotoFormulas.focusRange(50, 8, 0.03, 4000).total() > base.total());
        assertTrue(PhotoFormulas.hyperfocal(50, 11, 0.03) < PhotoFormulas.hyperfocal(50, 8, 0.03));
        assertTrue(PhotoFormulas.hyperfocal(85, 8, 0.03) > PhotoFormulas.hyperfocal(50, 8, 0.03));
        // Full frame, diagonal / 1500: sqrt(36^2 + 24^2) / 1500 = 0.028844 mm.
        assertEquals(0.0288444, PhotoFormulas.cocFromDiagonal(36, 24, 1500), 1e-7);
    }
    @Test void angleOfViewAndSceneCoverageUseTheActualFocalLength() {
        assertEquals(39.597752709, PhotoFormulas.angleOfView(36, 50), 1e-9);
        assertEquals(26.991466562, PhotoFormulas.angleOfView(24, 50), 1e-9);
        assertEquals(90, PhotoFormulas.angleOfView(36, 18), 1e-12, "half the sensor width equals the focal length");
        // A longer lens narrows the view; a larger sensor widens it.
        double previous = 180;
        for (double focal : new double[] { 8, 14, 24, 35, 50, 85, 135, 200, 400, 800 }) { double a = PhotoFormulas.angleOfView(36, focal); assertTrue(a < previous); previous = a; }
        assertTrue(PhotoFormulas.angleOfView(23.5, 50) < PhotoFormulas.angleOfView(36, 50));
        // Coverage at 20 m: 20 * 36 / 50 = 14.4 m, the same as 2 d tan(angle / 2).
        assertEquals(14.4, PhotoFormulas.coverage(20, 36, 50), 1e-12);
        assertEquals(2 * 20 * Math.tan(Math.toRadians(PhotoFormulas.angleOfView(36, 50)) / 2), PhotoFormulas.coverage(20, 36, 50), 1e-12);
        assertEquals(2 * PhotoFormulas.coverage(20, 24, 50), PhotoFormulas.coverage(40, 24, 50), 1e-12, "coverage scales with distance");
        // 35 mm equivalent is additional information: 23.5 x 15.6 mm has a diagonal crop factor of about 1.534.
        assertEquals(1, PhotoFormulas.cropFactor(36, 24), 1e-12);
        assertEquals(1.534, PhotoFormulas.cropFactor(23.5, 15.6), 5e-4);
        assertEquals(50, PhotoFormulas.fullFrameEquivalentFocal(50, 36, 24), 1e-12);
        assertEquals(76.7, PhotoFormulas.fullFrameEquivalentFocal(50, 23.5, 15.6), 0.05);
    }
    @Test void motionBlurFollowsTheStatedApproximation() {
        // 50 mm, 10 m/s across the frame at 20 m, 1/1000 s: 50 * 10 * 0.001 / 20 = 0.025 mm.
        double blur = PhotoFormulas.motionBlur(50, 10, 1 / 1000.0, 20);
        assertEquals(0.025, blur, 1e-15);
        assertEquals(4.1666666667, PhotoFormulas.blurPixels(blur, 6000, 36), 1e-9);
        assertEquals(2 * blur, PhotoFormulas.motionBlur(50, 10, 2 / 1000.0, 20), 1e-15, "twice the exposure, twice the blur");
        assertEquals(2 * blur, PhotoFormulas.motionBlur(100, 10, 1 / 1000.0, 20), 1e-15);
        assertEquals(blur / 2, PhotoFormulas.motionBlur(50, 10, 1 / 1000.0, 40), 1e-15);
        assertEquals(0, PhotoFormulas.motionBlur(50, 0, 1 / 1000.0, 20), 0.0, "a subject that does not move does not blur");
        assertEquals(0, PhotoFormulas.blurPixels(0, 6000, 36), 0.0);
        // Longest exposure for 2 px on 6000 px / 36 mm (0.012 mm): 0.012 * 20 / (50 * 10) = 0.00048 s.
        double longest = PhotoFormulas.longestExposure(0.012, 50, 10, 20);
        assertEquals(0.00048, longest, 1e-15);
        assertEquals(0.012, PhotoFormulas.motionBlur(50, 10, longest, 20), 1e-15, "that exposure gives exactly the limit");
        assertEquals(Double.POSITIVE_INFINITY, PhotoFormulas.longestExposure(0.012, 50, 0, 20), "no motion, no motion-imposed limit");
    }
}
