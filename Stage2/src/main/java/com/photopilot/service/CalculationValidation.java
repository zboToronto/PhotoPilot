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
            if (input.operation() != CalculationKind.EXPOSURE_ND)
                throw new IllegalArgumentException("This calculation is not available yet.");
            Set<String> allowed = Set.of("aperture", "shutter", "iso", "ndStops", "ndFactor", "opticalDensity");
            for (var key : input.parameters().keySet()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException("Unsupported parameter: " + key);
                double value = input.parameters().get(key);
                if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " must be finite.");
                if (key.equals("ndStops") || key.equals("opticalDensity")) {
                    if (value < 0) throw new IllegalArgumentException(key + " must be nonnegative.");
                } else if (value <= 0) throw new IllegalArgumentException(key + " must be positive.");
                if (key.equals("ndFactor") && value < 1) throw new IllegalArgumentException("ndFactor must be at least 1.");
                value(input, key);
            }
            if (!input.parameters().keySet().equals(input.units().keySet()))
                throw new IllegalArgumentException("Every parameter must have exactly one supported unit.");
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
