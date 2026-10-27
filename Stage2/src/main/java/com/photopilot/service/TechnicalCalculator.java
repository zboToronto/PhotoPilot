package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
                case HYPERFOCAL, DEPTH_OF_FIELD -> focus(input);
                case FIELD_OF_VIEW -> view(input);
                case STORAGE -> storageEstimate(input);
                case BATTERY_CAPACITY -> batteryEstimate(input);
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
    private Result<CalculationResult> focus(CalculationInput input) {
        double f=value(input,"focalLength"), n=value(input,"aperture"), c=value(input,"coc");
        // Work in mm, then convert distances to m. h0=H-f preserves the thin-lens infinity boundary.
        double h0=positiveFinite((f/n)*(f/c));
        double h=positiveFinite(h0+f);
        Map<String,Double> values=new LinkedHashMap<>();Map<String,String> units=new LinkedHashMap<>();
        values.put("Hyperfocal distance",positiveFinite(h/1000));units.put("Hyperfocal distance","m");
        if (input.operation()==CalculationKind.DEPTH_OF_FIELD) {
            double distance=positiveFinite(value(input,"distance")*1000);
            double ratio=positiveFinite((distance-f)/h0);
            double near=positiveFinite(distance/(1+ratio)/1000);
            double far=distance>=h ? Double.POSITIVE_INFINITY : positiveFinite(distance/(1-ratio)/1000);
            double depth=far==Double.POSITIVE_INFINITY ? far : positiveFinite(far-near);
            values.put("Near focus limit",near);values.put("Far focus limit",far);values.put("Total depth of field",depth);
            units.put("Near focus limit","m");units.put("Far focus limit","m");units.put("Total depth of field","m");
        }
        return result(input,values,units,true,List.of("Thin-lens theoretical approximation: H=f^2/(N*c)+f; h0=H-f; near=s*h0/(h0+s-f); far=s*h0/(h0-s+f). Far limit is Infinity at s >= H.",
            "Distances are measured from the lens principal plane. CoC is user-specified, not an intrinsic sensor constant. Ignores diffraction, pupil magnification and focus breathing; macro accuracy is limited."));
    }
    private Result<CalculationResult> view(CalculationInput input) {
        double f=value(input,"focalLength"), w=value(input,"sensorWidth"), h=value(input,"sensorHeight");
        double diagonal=positiveFinite(Math.hypot(w,h));
        Map<String,Double> values=Map.of("Horizontal field of view",angle(w,f),"Vertical field of view",angle(h,f),"Diagonal field of view",angle(diagonal,f));
        return result(input,values,Map.of("Horizontal field of view","degrees","Vertical field of view","degrees","Diagonal field of view","degrees"),true,
            List.of("Rectilinear lens at infinity focus: angle=2*atan(sensor dimension/(2*f)). Actual active sensor dimensions required; ignores cropping, distortion and focus breathing."));
    }
    private static double angle(double dimension,double focal) {
        double ratio=positiveFinite((dimension/focal)/2);
        double angle=positiveFinite(Math.toDegrees(2*Math.atan(ratio)));
        if (angle>=180) throw new IllegalArgumentException("Field of view exceeds numerical precision.");
        return angle;
    }
    private Result<CalculationResult> storageEstimate(CalculationInput input) {
        double size=positiveFinite(value(input,"shotCount")*value(input,"fileSize")/1000);
        double cards=wholeCeiling(BigDecimal.valueOf(value(input,"shotCount")).multiply(BigDecimal.valueOf(value(input,"fileSize"))),
            BigDecimal.valueOf(value(input,"cardCapacity")).multiply(BigDecimal.valueOf(1000)));
        return result(input,Map.of("Required storage",size,"Cards required",cards),Map.of("Required storage","GB","Cards required","cards"),true,
            List.of("Storage = shot count x average file size. Decimal MB=10^6 bytes; GB=10^9 bytes. Cards are rounded up; no filesystem overhead, redundancy or safety reserve included. Variable compression/video requires a measured average."));
    }
    private Result<CalculationResult> batteryEstimate(CalculationInput input) {
        double batteries=wholeCeiling(BigDecimal.valueOf(value(input,"shotCount")),BigDecimal.valueOf(value(input,"ratedShots")));
        return result(input,Map.of("Batteries required",batteries),Map.of("Batteries required","batteries"),true,
            List.of("Shooting capacity estimate: ceil(planned shots / rated shots per full battery). Rating must match expected usage. Temperature, battery age, screen, autofocus and video affect capacity; no reserve included. mAh alone cannot determine shot count."));
    }
    private static double wholeCeiling(BigDecimal numerator, BigDecimal denominator) {
        // Decimal ceiling prevents e.g. 0.07 GB / 0.01 GB becoming 8 through binary roundoff.
        double count=numerator.divide(denominator,0,RoundingMode.CEILING).doubleValue();
        if (!Double.isFinite(count) || count>9_007_199_254_740_991d) throw new IllegalArgumentException("Capacity count exceeds exact integer precision.");
        return positiveFinite(count);
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
