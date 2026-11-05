package com.photopilot.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * F11 units for recorded values, shared by comparisons, AI evidence, GUI and CLI so every surface shows the same
 * number: shutter as 1/N s (or seconds), focal length in mm, aperture as f-number, ISO, compensation in EV.
 * Formatting only; no value is changed or converted (no crop-factor equivalents).
 */
public final class ExifValues {
    private ExifValues() { }

    public static String format(String field, double value) {
        return switch (field) {
            case "shutter" -> shutter(value);
            case "focalLength" -> number(value, 1) + " mm";
            case "aperture" -> "f/" + number(value, 2);
            case "iso" -> "ISO " + Math.round(value);
            case "exposureCompensation" -> (value > 0 ? "+" : "") + number(value, 2) + " EV";
            default -> number(value, 3);
        };
    }
    /** Fractions of a second as 1/N when N is (practically) whole, e.g. 0.0004 -> 1/2500 s; 0.3 s stays decimal. */
    public static String shutter(double seconds) {
        if (seconds < 1) {
            double reciprocal = 1 / seconds;
            if (Math.abs(reciprocal - Math.rint(reciprocal)) <= 1e-3 * reciprocal) return "1/" + (long) Math.rint(reciprocal) + " s";
        }
        return number(seconds, 4) + " s";
    }
    public static String number(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    /** Share of a denominator with one decimal, e.g. 62.5%; 0 of 0 is never formatted (callers report "no data"). */
    public static String percent(int count, int total) {
        if (total <= 0) throw new IllegalArgumentException("No denominator");
        return BigDecimal.valueOf(count * 100.0 / total).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
