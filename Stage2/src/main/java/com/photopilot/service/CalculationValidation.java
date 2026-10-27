package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.util.*;

/** Shared domain validation and explicit unit conversion. Missing units are rejected. */
public final class CalculationValidation {
    private CalculationValidation() { }
    public static Result<CalculationInput> validate(CalculationInput input) {
        try {
            if (input == null) throw new IllegalArgumentException("Calculation input is required.");
            Set<String> allowed = switch (input.operation()) {
                case EXPOSURE_ND -> Set.of("aperture", "shutter", "iso", "ndStops", "ndFactor", "opticalDensity");
                case HYPERFOCAL -> Set.of("focalLength", "aperture", "coc");
                case DEPTH_OF_FIELD -> Set.of("focalLength", "aperture", "coc", "distance");
                case FIELD_OF_VIEW -> Set.of("focalLength", "sensorWidth", "sensorHeight");
                case STORAGE -> Set.of("shotCount", "fileSize", "cardCapacity");
                case BATTERY_CAPACITY -> Set.of("shotCount", "ratedShots");
            };
            for (var key : input.parameters().keySet()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported parameter: " + key);
                double value = input.parameters().get(key);
                if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite.");
                if (key.equals("ndStops") || key.equals("opticalDensity")) {
                    if (value < 0) throw new IllegalArgumentException(key + " must be nonnegative.");
                } else if (value <= 0) throw new IllegalArgumentException(key + " must be positive.");
                if (key.equals("ndFactor") && value < 1) throw new IllegalArgumentException("ndFactor must be at least 1.");
                if ((key.equals("shotCount") || key.equals("ratedShots")) && (value != Math.rint(value) || value > 9_007_199_254_740_991d))
                    throw new IllegalArgumentException(key + " must be a positive whole number within the exact integer range.");
                value(input, key);
            }
            if (!input.parameters().keySet().equals(input.units().keySet()))
                throw new IllegalArgumentException("Every parameter must have exactly one supported unit.");
            if (input.operation() != CalculationKind.EXPOSURE_ND) {
                for (String key : allowed) required(input, key);
                if (input.operation() == CalculationKind.DEPTH_OF_FIELD && value(input, "distance") <= value(input, "focalLength") / 1000)
                    throw new IllegalArgumentException("Subject distance must exceed focal length (distance measured from the lens principal plane).");
                return Result.success(input);
            }
            required(input, "shutter");
            boolean exposure = input.parameters().containsKey("aperture") || input.parameters().containsKey("iso");
            if (exposure) { required(input, "aperture"); required(input, "iso"); }
            long ndCount = List.of("ndStops", "ndFactor", "opticalDensity").stream().filter(input.parameters()::containsKey).count();
            if (ndCount > 1) throw new IllegalArgumentException("Use only one ND strength: ndStops, ndFactor or opticalDensity.");
            if (!exposure && ndCount == 0) throw new IllegalArgumentException("Supply aperture and iso, or one ND strength.");
            return Result.success(input);
        } catch (IllegalArgumentException e) { return Result.failure(e.getMessage()); }
    }
    private static void required(CalculationInput input, String key) {
        if (!input.parameters().containsKey(key)) throw new IllegalArgumentException(key + " is required.");
    }
    /** Canonical units: seconds, millimetres for optical dimensions, metres for distance. */
    public static double value(CalculationInput input, String key) {
        String unit = input.units().get(key);
        if (unit == null) throw new IllegalArgumentException("Unit is required for " + key);
        double v = input.parameters().get(key);
        double factor = switch (key) {
            case "shutter" -> switch (unit) { case "s" -> 1; case "ms" -> .001; default -> invalid(key); };
            case "focalLength", "coc", "sensorWidth", "sensorHeight" -> switch (unit) { case "mm" -> 1; case "cm" -> 10; case "m" -> 1000; default -> invalid(key); };
            case "distance" -> switch (unit) { case "m" -> 1; case "mm" -> .001; case "ft" -> .3048; default -> invalid(key); };
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
            throw new IllegalArgumentException(key + " is outside the supported numerical range.");
        return result;
    }
    private static double invalid(String key) { throw new IllegalArgumentException("Unsupported unit for " + key); }
}
