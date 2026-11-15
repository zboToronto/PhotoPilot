package com.photopilot.service;

import com.photopilot.i18n.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.util.*;

/** Shared domain validation and explicit unit conversion. Missing units are rejected. */
public final class CalculationValidation {
    private CalculationValidation() { }
    public static Result<CalculationInput> validate(CalculationInput input) {
        try {
            if (input == null) throw new LocalizedException("error.calc.required");
            Set<String> required = switch (input.operation()) {
                case EXPOSURE_ND -> Set.of("shutter");
                case HYPERFOCAL -> Set.of("focalLength", "aperture", "coc");
                case DEPTH_OF_FIELD -> Set.of("focalLength", "aperture", "coc", "distance");
                case FIELD_OF_VIEW -> Set.of("focalLength", "sensorWidth", "sensorHeight");
                case MOTION_BLUR -> Set.of("shutter", "focalLength", "distance", "speed", "sensorWidth");
                case STORAGE -> Set.of("shotCount", "fileSize", "cardCapacity");
                case BATTERY_CAPACITY -> Set.of("shotCount", "ratedShots");
            };
            // Optional parameters add outputs; an input without them is validated and answered exactly as before.
            Set<String> optional = switch (input.operation()) {
                case EXPOSURE_ND -> Set.of("aperture", "iso", "ndStops", "ndFactor", "opticalDensity");
                case FIELD_OF_VIEW -> Set.of("distance");
                case MOTION_BLUR -> Set.of("imageWidth", "blurLimit");
                default -> Set.of();
            };
            for (var key : input.parameters().keySet()) {
                if (!required.contains(key) && !optional.contains(key)) throw new LocalizedException("error.calc.parameter", key);
                double value = input.parameters().get(key);
                if (!Double.isFinite(value)) throw new LocalizedException("error.validation.finite", key);
                if (key.equals("ndStops") || key.equals("opticalDensity") || key.equals("speed")) {
                    if (value < 0) throw new LocalizedException("error.validation.nonnegative", key);
                } else if (value <= 0) throw new LocalizedException("error.validation.positive", key);
                if (key.equals("ndFactor") && value < 1) throw new LocalizedException("error.calc.ndFactor");
                if ((key.equals("shotCount") || key.equals("ratedShots") || key.equals("imageWidth")) && (value != Math.rint(value) || value > 9_007_199_254_740_991d))
                    throw new LocalizedException("error.validation.whole", key);
                value(input, key);
            }
            if (!input.parameters().keySet().equals(input.units().keySet()))
                throw new LocalizedException("error.calc.units");
            if (input.operation() != CalculationKind.EXPOSURE_ND) {
                for (String key : required) required(input, key);
                boolean focused = input.operation() == CalculationKind.DEPTH_OF_FIELD || input.operation() == CalculationKind.MOTION_BLUR;
                if (focused && value(input, "distance") <= value(input, "focalLength") / 1000)
                    throw new LocalizedException("error.calc.distance");
                // A blur limit in pixels only has a physical size once the image width relates pixels to the sensor.
                if ("px".equals(input.units().get("blurLimit")) && !input.parameters().containsKey("imageWidth"))
                    throw new LocalizedException("error.calc.blurLimitPixels");
                return Result.success(input);
            }
            required(input, "shutter");
            boolean exposure = input.parameters().containsKey("aperture") || input.parameters().containsKey("iso");
            if (exposure) { required(input, "aperture"); required(input, "iso"); }
            long ndCount = List.of("ndStops", "ndFactor", "opticalDensity").stream().filter(input.parameters()::containsKey).count();
            if (ndCount > 1) throw new LocalizedException("error.calc.oneND");
            if (!exposure && ndCount == 0) throw new LocalizedException("error.calc.exposureOrND");
            return Result.success(input);
        } catch (IllegalArgumentException e) { return Result.failure(e); }
    }
    private static void required(CalculationInput input, String key) {
        if (!input.parameters().containsKey(key)) throw new LocalizedException("error.validation.required", key);
    }
    /**
     * Canonical units: seconds, millimetres for optical dimensions, metres for distance, metres per second for speed.
     * A blur limit keeps the unit family it was given in: millimetres on the sensor (mm, um) or image pixels (px).
     */
    public static double value(CalculationInput input, String key) {
        String unit = input.units().get(key);
        if (unit == null) throw new LocalizedException("error.validation.unitRequired", key);
        double v = input.parameters().get(key);
        double factor = switch (key) {
            case "shutter" -> switch (unit) { case "s" -> 1; case "ms" -> .001; default -> invalid(key); };
            case "focalLength", "coc", "sensorWidth", "sensorHeight" -> switch (unit) { case "mm" -> 1; case "cm" -> 10; case "m" -> 1000; default -> invalid(key); };
            case "distance" -> switch (unit) { case "m" -> 1; case "mm" -> .001; case "ft" -> .3048; default -> invalid(key); };
            case "speed" -> switch (unit) { case "m/s" -> 1; case "km/h" -> 1 / 3.6; case "mph" -> .44704; case "ft/s" -> .3048; default -> invalid(key); };
            case "imageWidth" -> unit.equals("px") ? 1 : invalid(key);
            case "blurLimit" -> switch (unit) { case "mm", "px" -> 1; case "um" -> .001; default -> invalid(key); };
            case "shotCount", "ratedShots" -> unit.equals("shots") ? 1 : invalid(key);
            case "fileSize" -> switch (unit) { case "MB" -> 1; case "MiB" -> 1.048576; default -> invalid(key); };
            case "cardCapacity" -> switch (unit) { case "GB" -> 1; case "GiB" -> 1.073741824; default -> invalid(key); };
            case "aperture" -> unit.equals("f-number") ? 1 : invalid(key);
            case "iso" -> unit.equals("ISO") ? 1 : invalid(key);
            case "ndStops" -> unit.equals("stops") ? 1 : invalid(key);
            case "ndFactor" -> unit.equals("factor") ? 1 : invalid(key);
            case "opticalDensity" -> unit.equals("OD") ? 1 : invalid(key);
            default -> invalid(key);
        };
        double result = v * factor;
        if (!Double.isFinite(result) || (v > 0 && result == 0))
            throw new LocalizedException("error.validation.range", key);
        return result;
    }
    private static double invalid(String key) { throw new LocalizedException("error.validation.unit", key); }
}
