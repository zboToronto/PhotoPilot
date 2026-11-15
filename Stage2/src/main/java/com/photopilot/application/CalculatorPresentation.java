package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.service.PhotoFormulas;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Display conventions of the calculator workspace: nominal stop scales, slider scales, units and the text of times,
 * distances and angles. It has no formulas of its own (those are {@link PhotoFormulas}) and no JavaFX, so the
 * conventions are tested without a window. Calculation precision is never reduced here: only the displayed text is rounded.
 * <p>
 * Nominal markings. A marking is stored as the marked number itself: f/5.6 is N = 5.6, 1/125 s is 0.008 s and ISO 125 is
 * 125. The exact series behind the markings (powers of the square root of two, 1/128 s, ...) is not substituted, so
 * neighbouring markings are about, not exactly, one third of a stop apart; the EV readouts show the exact figure.
 */
public final class CalculatorPresentation {
    private CalculatorPresentation() { }

    /** Shown wherever a value is unavailable. */
    public static final String NONE = "—";
    public static final String INFINITY = "∞";

    /** Third-stop aperture markings; every third one (from f/1) is a full stop. */
    public static final List<Double> APERTURES = List.of(0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1,
        8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0, 36.0, 40.0, 45.0, 51.0, 57.0, 64.0);
    public static final List<Double> FULL_STOP_APERTURES = List.of(1.0, 1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0, 32.0, 45.0, 64.0);
    /** Third-stop exposure-time markings in seconds, 1/32000 s to 64 min. */
    public static final List<Double> SHUTTERS = shutters();
    public static final List<Double> FULL_STOP_SHUTTERS = List.of(1 / 32000.0, 1 / 16000.0, 1 / 8000.0, 1 / 4000.0, 1 / 2000.0, 1 / 1000.0, 1 / 500.0, 1 / 250.0, 1 / 125.0,
        1 / 60.0, 1 / 30.0, 1 / 15.0, 1 / 8.0, 1 / 4.0, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0, 60.0, 120.0, 240.0, 480.0, 960.0, 1920.0, 3840.0);
    public static final List<Double> ISOS = List.of(50.0, 64.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 320.0, 400.0, 500.0, 640.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3200.0, 4000.0, 5000.0, 6400.0, 8000.0, 10000.0, 12800.0, 16000.0, 20000.0, 25600.0, 32000.0, 40000.0, 51200.0, 64000.0, 80000.0, 102400.0);
    public static final List<Double> FULL_STOP_ISOS = List.of(50.0, 100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0, 12800.0, 25600.0, 51200.0, 102400.0);
    public static final List<Double> FOCAL_LENGTHS = List.of(8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 24.0, 28.0, 35.0, 40.0, 50.0, 55.0, 70.0, 85.0, 100.0, 105.0, 135.0,
        150.0, 200.0, 250.0, 300.0, 400.0, 500.0, 600.0, 800.0);

    private static List<Double> shutters() {
        var values = new ArrayList<Double>();
        for (double reciprocal : new double[] { 32000, 25600, 20000, 16000, 12800, 10000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400,
            320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4 }) values.add(1 / reciprocal);
        for (double seconds : new double[] { 0.3, 0.4, 0.5, 0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 120, 160, 200,
            240, 320, 400, 480, 640, 800, 960, 1280, 1600, 1920, 2560, 3200, 3840 }) values.add(seconds);
        return List.copyOf(values);
    }

    // ---- Units -----------------------------------------------------------------------------------------------------

    /** Distance unit of inputs, results and diagram axes. {@link #symbol} is also the unit name the calculator accepts. */
    public enum LengthUnit {
        METRES("m", 1), FEET("ft", 0.3048);
        public final String symbol;
        public final double metres;
        LengthUnit(String symbol, double metres) { this.symbol = symbol; this.metres = metres; }
        public double fromMetres(double value) { return value / metres; }
        public double toMetres(double value) { return value * metres; }
    }
    /** Speed unit of the motion blur input; {@link #symbol} is the unit name the calculator accepts. */
    public enum SpeedUnit {
        METRES_PER_SECOND("m/s", 1), KILOMETRES_PER_HOUR("km/h", 1 / 3.6), MILES_PER_HOUR("mph", 0.44704);
        public final String symbol;
        public final double metresPerSecond;
        SpeedUnit(String symbol, double metresPerSecond) { this.symbol = symbol; this.metresPerSecond = metresPerSecond; }
        public double fromMetresPerSecond(double value) { return value / metresPerSecond; }
        public double toMetresPerSecond(double value) { return value * metresPerSecond; }
    }

    // ---- Slider scales ---------------------------------------------------------------------------------------------

    /** Maps a slider position (0 to {@link #positions()}) to a value and back. */
    public interface Scale {
        double positions();
        double value(double position);
        double position(double value);
        /** True when the slider rests only on whole positions (nominal markings or fixed steps). */
        boolean snaps();

        /** Nominal markings: whole positions are the markings themselves; values between two markings sit between them. */
        static Scale nominal(List<Double> markings) {
            return new Scale() {
                public double positions() { return markings.size() - 1; }
                public boolean snaps() { return true; }
                public double value(double position) {
                    double clamped = Math.max(0, Math.min(positions(), position));
                    int index = (int) Math.min(markings.size() - 2, Math.floor(clamped));
                    double low = markings.get(index), high = markings.get(index + 1), part = clamped - index;
                    // Whole positions return the marking exactly; nothing is recomputed from the series behind it.
                    return part == 0 ? low : part == 1 ? high : low * Math.pow(high / low, part);
                }
                public double position(double value) {
                    if (!(value > markings.getFirst())) return 0;
                    if (value >= markings.getLast()) return positions();
                    int index = 0;
                    while (markings.get(index + 1) < value) index++;
                    double low = markings.get(index), high = markings.get(index + 1);
                    return index + Math.log(value / low) / Math.log(high / low);
                }
            };
        }
        /** Logarithmic scale between two positive bounds; slider values are rounded to three significant digits. */
        static Scale logarithmic(double min, double max) {
            return new Scale() {
                public double positions() { return 1000; }
                public boolean snaps() { return false; }
                public double value(double position) {
                    double clamped = Math.max(0, Math.min(1000, position));
                    return clamped == 0 ? min : clamped == 1000 ? max : Math.max(min, Math.min(max, significant(min * Math.pow(max / min, clamped / 1000), 3)));
                }
                public double position(double value) { return Math.max(0, Math.min(1000, 1000 * Math.log(Math.max(value, min) / min) / Math.log(max / min))); }
            };
        }
        /** Zero at the first position, then logarithmic from a smallest value to max: for a quantity that may be nothing at all. */
        static Scale fromZero(double smallest, double max) {
            Scale positive = logarithmic(smallest, max);
            return new Scale() {
                public double positions() { return 1000; }
                public boolean snaps() { return false; }
                public double value(double position) { return position < 5 ? 0 : positive.value((position - 5) / 995 * 1000); }
                public double position(double value) { return value <= 0 ? 0 : 5 + positive.position(value) / 1000 * 995; }
            };
        }
        /** Linear scale in equal steps from min to max. */
        static Scale linear(double min, double max, double step) {
            return new Scale() {
                public double positions() { return Math.round((max - min) / step); }
                public boolean snaps() { return true; }
                public double value(double position) {
                    double clamped = Math.max(0, Math.min(positions(), position));
                    // Rounded to the step's own decimals, so 0.1 steps never show as 0.30000000000000004.
                    return clamped == Math.rint(clamped) ? significant(min + clamped * step, 12) : min + clamped * step;
                }
                public double position(double value) { return Math.max(0, Math.min(positions(), (value - min) / step)); }
            };
        }
    }

    /** Index of the marking nearest to a value, compared as a ratio (stops), not as a difference. */
    public static int nearest(List<Double> markings, double value) {
        int best = 0;
        for (int i = 1; i < markings.size(); i++)
            if (Math.abs(Math.log(markings.get(i) / value)) < Math.abs(Math.log(markings.get(best) / value))) best = i;
        return best;
    }

    // ---- Numbers, times, distances ---------------------------------------------------------------------------------

    /** The value rounded to a number of significant digits. */
    public static double significant(double value, int digits) {
        if (value == 0 || !Double.isFinite(value)) return value;
        return new BigDecimal(value).round(new MathContext(digits, RoundingMode.HALF_UP)).doubleValue();
    }
    /**
     * Display text with a number of significant digits; whole-number digits are never rounded away, trailing zeros are
     * dropped, and very small or large values use the calculator's scientific notation. Always a decimal point.
     */
    public static String number(double value, int digits) {
        if (Double.isNaN(value)) return NONE;
        if (Double.isInfinite(value)) return value > 0 ? INFINITY : "-" + INFINITY;
        if (value == 0) return "0";
        double magnitude = Math.abs(value);
        if (magnitude >= 1e9 || magnitude < 1e-6) return CalculationForm.formatNumber(value);
        BigDecimal exact = new BigDecimal(value);
        BigDecimal rounded = magnitude >= Math.pow(10, digits) ? exact.setScale(0, RoundingMode.HALF_UP) : exact.round(new MathContext(digits, RoundingMode.HALF_UP));
        String text = rounded.stripTrailingZeros().toPlainString();
        return text.equals("-0") ? "0" : text;
    }
    /** A fixed number of decimals, for values compared in a column (EV, stops). */
    public static String decimals(double value, int decimals) {
        if (!Double.isFinite(value)) return number(value, 3);
        String text = new BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString();
        return text.startsWith("-") && new BigDecimal(text).signum() == 0 ? text.substring(1) : text;
    }
    /** A difference in stops with an explicit sign, e.g. +1.3 or -0.7. */
    public static String signedStops(double stops) {
        String text = decimals(stops, 1);
        return new BigDecimal(text).signum() > 0 ? "+" + text : text.replace('-', '−');
    }
    /**
     * An exposure time: a fraction below 0.3 s (1/125 s, 1/2083 s), decimal seconds up to a minute (8.19 s), then
     * minutes, hours and days. Infinity means "no limit" and is shown as the infinity sign.
     */
    public static String exposureTime(double seconds, LocalizationService localization) {
        if (Double.isNaN(seconds)) return NONE;
        if (Double.isInfinite(seconds)) return INFINITY;
        if (seconds < 0.3) {
            double reciprocal = 1 / seconds;
            return "1/" + (reciprocal >= 100 ? number(Math.rint(reciprocal), 12) : number(reciprocal, 3)) + " s";
        }
        if (seconds < 59.95) return number(seconds, 3) + " s";
        long whole = Math.round(seconds);
        if (seconds >= 1e15) return number(seconds / 86400, 3) + " d";
        if (whole < 3600) return localization.text("calc.time.minutes", whole / 60, whole % 60);
        long minutes = Math.round(seconds / 60);
        if (minutes < 1440) return localization.text("calc.time.hours", minutes / 60, minutes % 60);
        long hours = Math.round(seconds / 3600);
        return localization.text("calc.time.days", Long.toString(hours / 24), hours % 24);
    }
    /** The marking text of an exposure time without its unit, for slider and scale labels (1/125, 0.5, 30). */
    public static String shutterMark(double seconds) {
        if (seconds < 0.3) return "1/" + number(1 / seconds, seconds < 0.01 ? 12 : 3);
        return seconds < 60 ? number(seconds, 3) : number(seconds, 12);
    }
    /** An aperture as f/N. */
    public static String aperture(double fNumber) { return "f/" + number(fNumber, 3); }
    /**
     * A distance in the chosen unit family with a readable magnitude: mm, cm, m, km or in, ft, mi. An unbounded distance
     * is the infinity sign.
     */
    public static String distance(double metres, LengthUnit unit) {
        if (Double.isNaN(metres)) return NONE;
        if (Double.isInfinite(metres)) return INFINITY;
        if (unit == LengthUnit.FEET) {
            double feet = metres / LengthUnit.FEET.metres;
            if (feet < 1) return number(feet * 12, 3) + " in";
            return feet < 5280 ? number(feet, 3) + " ft" : number(feet / 5280, 3) + " mi";
        }
        if (metres < 0.01) return number(metres * 1000, 3) + " mm";
        if (metres < 1) return number(metres * 100, 3) + " cm";
        return metres < 1000 ? number(metres, 3) + " m" : number(metres / 1000, 3) + " km";
    }
    /** A length on the sensor: micrometres below 0.1 mm, otherwise millimetres. */
    public static String sensorLength(double millimetres) {
        if (!Double.isFinite(millimetres)) return number(millimetres, 3);
        return millimetres != 0 && millimetres < 0.1 ? number(millimetres * 1000, 3) + " µm" : number(millimetres, 3) + " mm";
    }
    public static String angle(double degrees) { return number(degrees, 3) + "°"; }
    public static String percent(double percent) { return number(percent, percent < 1 ? 2 : 3) + "%"; }
    /** Plain text of a value for an input field: six significant digits, no unit. */
    public static String fieldNumber(double value) { return number(value, 6); }
    /** Field text of an exposure time: the familiar fraction when the time is one (1/125), otherwise decimal seconds. */
    public static String shutterField(double seconds) {
        double reciprocal = 1 / seconds;
        if (seconds < 0.3 && Math.abs(reciprocal - Math.rint(reciprocal)) <= 1e-9 * reciprocal) return "1/" + number(Math.rint(reciprocal), 12);
        return number(seconds, 6);
    }

    // ---- Exposure relationships ------------------------------------------------------------------------------------

    /** One aperture and exposure-time pair. */
    public record ExposurePair(double fNumber, double seconds) { }
    /**
     * Full-stop apertures around the current one, each with the exposure time that keeps the same settings EV: the
     * reciprocity table. The times are exact results, not rounded to a marking.
     */
    public static List<ExposurePair> equivalents(double settingsEv, double fNumber, int eachSide) {
        int centre = nearest(FULL_STOP_APERTURES, fNumber);
        int from = Math.max(0, Math.min(centre - eachSide, FULL_STOP_APERTURES.size() - 1 - 2 * eachSide)), to = Math.min(FULL_STOP_APERTURES.size() - 1, from + 2 * eachSide);
        var pairs = new ArrayList<ExposurePair>();
        for (int i = from; i <= to; i++) pairs.add(new ExposurePair(FULL_STOP_APERTURES.get(i), PhotoFormulas.shutterFor(settingsEv, FULL_STOP_APERTURES.get(i))));
        return pairs;
    }

    /** Stops of light between two values of a quantity that is proportional to exposure (time, ISO): log2(to / from). */
    public static double stopsBetween(double from, double to) { return PhotoFormulas.log2(to / from); }
    /** Stops of light gained by going from one f-number to another: -2 log2(to / from), positive when opening up. */
    public static double apertureStops(double from, double to) { return -2 * PhotoFormulas.log2(to / from); }
    /** A time or sensitivity changed by a number of stops of exposure. */
    public static double shifted(double value, double stops) { return value * Math.pow(2, stops); }
    /** An f-number changed by a number of stops of exposure (more exposure is a smaller f-number). */
    public static double shiftedAperture(double fNumber, double stops) { return fNumber * Math.pow(2, -stops / 2); }
    /** The one exposure parameter that keeps EV100 at a held value, given the other two. */
    public static double heldShutter(double ev100, double fNumber, double iso) { return PhotoFormulas.shutterFor(ev100 + PhotoFormulas.log2(iso / 100), fNumber); }
    public static double heldAperture(double ev100, double seconds, double iso) { return PhotoFormulas.apertureFor(ev100 + PhotoFormulas.log2(iso / 100), seconds); }
    public static double heldIso(double ev100, double fNumber, double seconds) { return PhotoFormulas.isoFor(ev100, PhotoFormulas.settingsEv(fNumber, seconds)); }
    /**
     * Display level (0 to 1, sRGB) of a mid-grey patch shown a number of stops brighter or darker and clipped at black
     * and white. A demonstration of what a stop difference looks like; it does not predict a photograph or histogram.
     */
    public static double demonstrationGrey(double stops) {
        double linear = Math.max(0, Math.min(1, 0.18 * Math.pow(2, stops)));
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    }

    // ---- Neutral density filter names ------------------------------------------------------------------------------

    /**
     * A filter as it is sold: its name states a filter factor, and its advertised strength is a whole number of stops.
     * The two agree exactly only up to ND256; from ND1000 on the name is rounded (ND1000 is sold as 10 stops, but a
     * factor of 1000 is 9.97 stops and 10 stops is a factor of 1024).
     */
    public record NdFilter(String name, double factor, int advertisedStops) {
        public double exactStops() { return PhotoFormulas.stopsFromFactor(factor); }
        public boolean rounded() { return Math.abs(exactStops() - advertisedStops) > 1e-9; }
    }
    public static final List<NdFilter> ND_FILTERS = List.of(new NdFilter("ND2", 2, 1), new NdFilter("ND4", 4, 2), new NdFilter("ND8", 8, 3), new NdFilter("ND16", 16, 4),
        new NdFilter("ND32", 32, 5), new NdFilter("ND64", 64, 6), new NdFilter("ND128", 128, 7), new NdFilter("ND256", 256, 8), new NdFilter("ND500", 500, 9),
        new NdFilter("ND1000", 1000, 10), new NdFilter("ND2000", 2000, 11), new NdFilter("ND4000", 4000, 12), new NdFilter("ND32000", 32000, 15),
        new NdFilter("ND64000", 64000, 16), new NdFilter("ND1000000", 1000000, 20));
    /** The commercial filter advertised with this strength, when the strength is within 0.05 stops of a whole number it is sold as. */
    public static Optional<NdFilter> commercialFilter(double stops) {
        return ND_FILTERS.stream().filter(filter -> Math.abs(stops - filter.advertisedStops()) < 0.05 || Math.abs(stops - filter.exactStops()) < 0.005).findFirst();
    }

    // ---- Distance diagram axis -------------------------------------------------------------------------------------

    /**
     * The linear distance axis of the focus diagrams, from the camera (0) to {@link #max} in the display unit. The axis
     * only decides what is drawn where; it never changes a computed distance, and a far limit that does not fit is
     * reported as {@link Extent#BEYOND_VIEW} with its finite value, never as infinity.
     */
    public record DistanceAxis(double max) {
        public enum Extent { WITHIN, BEYOND_VIEW, UNBOUNDED }
        private static final double[] STEPS = { 1, 1.5, 2, 3, 4, 5, 6, 8, 10 };

        /**
         * An axis that shows the focus distance at no more than about 55% of its length, extended to include a finite
         * far limit of up to four times the focus distance. Values are in the display unit.
         */
        public static DistanceAxis fit(double focus, double far) {
            double target = focus / 0.55;
            if (Double.isFinite(far) && far <= 4 * focus) target = Math.max(target, far * 1.12);
            return new DistanceAxis(niceCeiling(Math.max(target, 1e-3)));
        }
        static double niceCeiling(double value) {
            double decade = Math.pow(10, Math.floor(Math.log10(value)));
            for (double step : STEPS) if (step * decade >= value * (1 - 1e-12)) return step * decade;
            return 10 * decade;
        }
        /** Position along the axis, 0 (camera) to 1 (right edge); beyond the edge it is larger than 1. */
        public double fraction(double distance) { return distance / max; }
        public double distance(double fraction) { return fraction * max; }
        public Extent extent(double far) {
            return far == Double.POSITIVE_INFINITY ? Extent.UNBOUNDED : far > max ? Extent.BEYOND_VIEW : Extent.WITHIN;
        }
        /** Tick spacing giving between four and ten labelled divisions. */
        public double tick() {
            double raw = max / 8, decade = Math.pow(10, Math.floor(Math.log10(raw)));
            for (double step : new double[] { 1, 2, 2.5, 5, 10 }) if (step * decade >= raw * (1 - 1e-12)) return step * decade;
            return 10 * decade;
        }
    }
}
