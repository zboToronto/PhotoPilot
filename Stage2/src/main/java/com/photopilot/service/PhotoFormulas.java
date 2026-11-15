package com.photopilot.service;

/**
 * The one place the F08 photographic formulas live. Every entry point computes through it: {@link TechnicalCalculator}
 * (Facade, CLI, agent tool, planning) and the calculator workspace's presentation helpers.
 * Pure functions: no validation messages, no rounding, no formatting and no UI. Callers validate their inputs first;
 * out-of-domain arguments give NaN or infinity, never an exception.
 * Units: every length is in millimetres and every time in seconds unless a parameter name says otherwise.
 */
public final class PhotoFormulas {
    private PhotoFormulas() { }

    /** Diagonal of the 36 x 24 mm frame that "35 mm equivalent" focal lengths refer to. */
    public static final double FULL_FRAME_DIAGONAL = Math.hypot(36, 24);

    public static double log2(double value) { return Math.log(value) / Math.log(2); }

    // ---- Exposure ------------------------------------------------------------------------------------------------

    /** Settings EV = log2(N^2 / t): aperture and exposure time only, no sensitivity term. */
    public static double settingsEv(double fNumber, double seconds) { return 2 * log2(fNumber) - log2(seconds); }
    /** ISO-normalized equivalent: EV100 = EV - log2(S / 100). Equal to the settings EV at ISO 100. */
    public static double ev100(double settingsEv, double iso) { return settingsEv - (log2(iso) - log2(100)); }
    /** Exposure time that gives a settings EV at an aperture: t = N^2 / 2^EV. */
    public static double shutterFor(double settingsEv, double fNumber) { return fNumber * fNumber / Math.pow(2, settingsEv); }
    /** Aperture that gives a settings EV at an exposure time: N = sqrt(t * 2^EV). */
    public static double apertureFor(double settingsEv, double seconds) { return Math.sqrt(seconds * Math.pow(2, settingsEv)); }
    /** Sensitivity at which a settings EV corresponds to an EV100: S = 100 * 2^(EV - EV100). */
    public static double isoFor(double ev100, double settingsEv) { return 100 * Math.pow(2, settingsEv - ev100); }

    // ---- Neutral density -----------------------------------------------------------------------------------------

    /** Stops, filter factor and optical density are three scales of one attenuation: factor = 2^stops = 10^density. */
    public static double stopsFromFactor(double factor) { return log2(factor); }
    public static double stopsFromDensity(double density) { return density / Math.log10(2); }
    public static double factorFromStops(double stops) { return Math.pow(2, stops); }
    public static double densityFromStops(double stops) { return stops * Math.log10(2); }
    /**
     * adjusted = base * 2^stops. Whole stops are an exact binary scaling, so zero stops returns the base time unchanged
     * and each further stop exactly doubles it; a large stop count overflows only when the result itself does.
     */
    public static double ndExposure(double seconds, double stops) {
        double whole = Math.floor(stops);
        if (whole > 4096) return seconds > 0 ? Double.POSITIVE_INFINITY : seconds;
        return Math.scalb(seconds * Math.pow(2, stops - whole), (int) whole);
    }

    // ---- Depth of field and hyperfocal distance (ideal thin lens) --------------------------------------------------

    /** Sensor diagonal divided by a constant: the usual way to pick a circle of confusion for a format. */
    public static double cocFromDiagonal(double sensorWidth, double sensorHeight, double divisor) { return Math.hypot(sensorWidth, sensorHeight) / divisor; }
    /** H = q + f with q = f^2 / (N c). */
    public static double hyperfocal(double focal, double fNumber, double coc) { return (focal / fNumber) * (focal / coc) + focal; }

    /**
     * Limits of acceptable sharpness for one focus distance. When {@code farUnbounded}, {@code far} and {@code total}
     * are +Infinity by definition; otherwise they are finite distances, however large.
     */
    public record FocusRange(double hyperfocal, double near, double far, double total, boolean farUnbounded) { }
    /**
     * q = f^2 / (N c), H = q + f, near = q s / (q + s - f); far = q s / (q - s + f) for s &lt; H and unbounded for
     * s &gt;= H. {@code focus} (s) is measured from the lens (principal plane) and must exceed the focal length.
     * The same definition of H serves the hyperfocal calculator, so near = H / 2 when s = H.
     * The far limit's denominator q - s + f is evaluated as H - s, which is exact for distances close to H, so a
     * distance just below H gives a very large finite limit rather than a rounding artefact.
     */
    public static FocusRange focusRange(double focal, double fNumber, double coc, double focus) {
        double q = (focal / fNumber) * (focal / coc), hyperfocal = q + focal;
        double near = q * focus / (q + (focus - focal));
        boolean unbounded = focus >= hyperfocal;
        double far = unbounded ? Double.POSITIVE_INFINITY : q * focus / (hyperfocal - focus);
        return new FocusRange(hyperfocal, near, far, unbounded ? Double.POSITIVE_INFINITY : far - near, unbounded);
    }

    // ---- Field of view (ideal rectilinear / pinhole projection) ----------------------------------------------------

    /** angle = 2 atan(a / (2 f)), in degrees, for a sensor dimension a and the actual focal length f. */
    public static double angleOfView(double dimension, double focal) { return Math.toDegrees(2 * Math.atan((dimension / focal) / 2)); }
    /** Extent covered at a distance, in the distance's unit: 2 d tan(angle / 2) = d a / f. */
    public static double coverage(double distance, double dimension, double focal) { return distance * (dimension / focal); }
    /** Diagonal crop factor relative to the 36 x 24 mm frame. */
    public static double cropFactor(double sensorWidth, double sensorHeight) { return FULL_FRAME_DIAGONAL / Math.hypot(sensorWidth, sensorHeight); }
    /** Focal length with the same diagonal angle of view on a 36 x 24 mm frame. Additional information only. */
    public static double fullFrameEquivalentFocal(double focal, double sensorWidth, double sensorHeight) { return focal * cropFactor(sensorWidth, sensorHeight); }

    // ---- Motion blur (stationary camera, lateral subject motion, distance much larger than the focal length) -------

    /** Blur on the sensor in mm: f (mm) * v (m/s) * t (s) / d (m). */
    public static double motionBlur(double focal, double metresPerSecond, double seconds, double distanceMetres) { return focal * metresPerSecond * seconds / distanceMetres; }
    /** Horizontal blur in image pixels: blur (mm) * image width (px) / sensor width (mm). */
    public static double blurPixels(double blur, double imageWidthPixels, double sensorWidth) { return blur * imageWidthPixels / sensorWidth; }
    /** Longest exposure that keeps the blur within a limit (mm on the sensor); +Infinity when nothing moves. */
    public static double longestExposure(double blurLimit, double focal, double metresPerSecond, double distanceMetres) {
        return metresPerSecond == 0 ? Double.POSITIVE_INFINITY : blurLimit * distanceMetres / (focal * metresPerSecond);
    }
}
