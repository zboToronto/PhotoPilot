package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.util.*;
import static com.photopilot.service.CalculationValidation.value;

/** Stage 1 UML element 90: deterministic F08 suite, shared by Facade and agent tool. */
public class TechnicalCalculator {
    public TechnicalCalculator() { }
    public Result<CalculationResult> calculate(CalculationInput input) {
        var checked = CalculationValidation.validate(input);
        if (!checked.isSuccess()) return Result.failure(String.join("; ", checked.errors()));
        try {
            return switch (input.operation()) {
                case EXPOSURE_ND -> exposure(input);
                default -> Result.failure("This calculation is not available yet.");
            };
        } catch (IllegalArgumentException e) { return Result.failure(e.getMessage()); }
    }
    private Result<CalculationResult> exposure(CalculationInput input) {
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        double t = value(input, "shutter");
        if (input.parameters().containsKey("aperture")) {
            double ev = 2 * log2(value(input, "aperture")) - log2(t);
            values.put("Camera setting EV", ev); units.put("Camera setting EV", "EV");
            values.put("ISO-normalized EV100", ev - (log2(value(input, "iso")) - log2(100)));
            units.put("ISO-normalized EV100", "EV100");
            notes.add("EV = log2(N^2/t). EV100 = EV - log2(ISO/100); equal at ISO 100. This is not exposure compensation or a light measurement.");
        }
        String nd = List.of("ndStops", "ndFactor", "opticalDensity").stream().filter(input.parameters()::containsKey).findFirst().orElse(null);
        if (nd != null) {
            double strength = value(input, nd);
            double stops = switch (nd) { case "ndFactor" -> log2(strength); case "opticalDensity" -> strength / Math.log10(2); default -> strength; };
            // Log-domain multiplication avoids overflowing 2^stops when the final time is representable.
            double adjusted = Math.pow(2, log2(t) + stops);
            positiveFinite(adjusted);
            values.put("ND strength", stops); units.put("ND strength", "stops");
            values.put("Adjusted shutter", adjusted); units.put("Adjusted shutter", "s");
            notes.add("Adjusted shutter = original shutter x 2^stops. Aperture and ISO remain fixed; assumes ideal filter transmission. ND8 factor = 3 stops; OD 3.0 is about 9.966 stops.");
        }
        return result(input, values, units, false, notes);
    }
    private static double log2(double v) { return Math.log(v) / Math.log(2); }
    private static double positiveFinite(double v) {
        if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException("Result is outside the supported numerical range (overflow or underflow).");
        return v;
    }
    private static Result<CalculationResult> result(CalculationInput input, Map<String, Double> values, Map<String, String> units, boolean estimated, List<String> notes) {
        return Result.success(new CalculationResult(values, units, estimated, notes, input));
    }
    private Result<CalculationResult> operation(CalculationInput input, CalculationKind kind) {
        if (input == null || input.operation() != kind) return Result.failure("Expected operation " + kind);
        return calculate(input);
    }
    public Result<CalculationResult> depthOfField(CalculationInput input) { return operation(input, CalculationKind.DEPTH_OF_FIELD); }
    public Result<CalculationResult> hyperfocal(CalculationInput input) { return operation(input, CalculationKind.HYPERFOCAL); }
    public Result<CalculationResult> fieldOfView(CalculationInput input) { return operation(input, CalculationKind.FIELD_OF_VIEW); }
    public Result<CalculationResult> exposureAndND(CalculationInput input) { return operation(input, CalculationKind.EXPOSURE_ND); }
    public Result<CalculationResult> storage(CalculationInput input) { return operation(input, CalculationKind.STORAGE); }
    public Result<CalculationResult> batteryCapacity(CalculationInput input) { return operation(input, CalculationKind.BATTERY_CAPACITY); }
}
