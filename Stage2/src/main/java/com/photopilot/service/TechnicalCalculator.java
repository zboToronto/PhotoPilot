package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.i18n.*;
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
        if (!checked.isSuccess()) return Result.failure(checked);
        try {
            return switch (input.operation()) {
                case EXPOSURE_ND -> exposure(input);
                case HYPERFOCAL, DEPTH_OF_FIELD -> focus(input);
                case FIELD_OF_VIEW -> view(input);
                case STORAGE -> storageEstimate(input);
                case BATTERY_CAPACITY -> batteryEstimate(input);
            };
        } catch (IllegalArgumentException e) { return Result.failure(e); }
    }
    private Result<CalculationResult> exposure(CalculationInput input) {
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, String> units = new LinkedHashMap<>();
        List<LocalizedMessage> notes = new ArrayList<>();
        double t = value(input, "shutter");
        if (input.parameters().containsKey("aperture")) {
            double ev = 2 * log2(value(input, "aperture")) - log2(t);
            values.put("Camera setting EV", ev); units.put("Camera setting EV", "EV");
            values.put("ISO-normalized EV100", ev - (log2(value(input, "iso")) - log2(100)));
            units.put("ISO-normalized EV100", "EV100");
            notes.add(LocalizedMessage.of("calc.assumption.exposure"));
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
            notes.add(LocalizedMessage.of("calc.assumption.nd"));
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
        return result(input,values,units,true,List.of(LocalizedMessage.of("calc.assumption.focusFormula"),
            LocalizedMessage.of("calc.assumption.focusLimits")));
    }
    private Result<CalculationResult> view(CalculationInput input) {
        double f=value(input,"focalLength"), w=value(input,"sensorWidth"), h=value(input,"sensorHeight");
        double diagonal=positiveFinite(Math.hypot(w,h));
        Map<String,Double> values=Map.of("Horizontal field of view",angle(w,f),"Vertical field of view",angle(h,f),"Diagonal field of view",angle(diagonal,f));
        return result(input,values,Map.of("Horizontal field of view","degrees","Vertical field of view","degrees","Diagonal field of view","degrees"),true,
            List.of(LocalizedMessage.of("calc.assumption.fov")));
    }
    private static double angle(double dimension,double focal) {
        double ratio=positiveFinite((dimension/focal)/2);
        double angle=positiveFinite(Math.toDegrees(2*Math.atan(ratio)));
        if (angle>=180) throw new LocalizedException("error.calc.fovPrecision");
        return angle;
    }
    private Result<CalculationResult> storageEstimate(CalculationInput input) {
        double size=positiveFinite(value(input,"shotCount")*value(input,"fileSize")/1000);
        double cards=wholeCeiling(BigDecimal.valueOf(value(input,"shotCount")).multiply(BigDecimal.valueOf(value(input,"fileSize"))),
            BigDecimal.valueOf(value(input,"cardCapacity")).multiply(BigDecimal.valueOf(1000)));
        return result(input,Map.of("Required storage",size,"Cards required",cards),Map.of("Required storage","GB","Cards required","cards"),true,
            List.of(LocalizedMessage.of("calc.assumption.storage")));
    }
    private Result<CalculationResult> batteryEstimate(CalculationInput input) {
        double batteries=wholeCeiling(BigDecimal.valueOf(value(input,"shotCount")),BigDecimal.valueOf(value(input,"ratedShots")));
        return result(input,Map.of("Batteries required",batteries),Map.of("Batteries required","batteries"),true,
            List.of(LocalizedMessage.of("calc.assumption.battery")));
    }
    private static double wholeCeiling(BigDecimal numerator, BigDecimal denominator) {
        // Decimal ceiling prevents e.g. 0.07 GB / 0.01 GB becoming 8 through binary roundoff.
        double count=numerator.divide(denominator,0,RoundingMode.CEILING).doubleValue();
        if (!Double.isFinite(count) || count>9_007_199_254_740_991d) throw new LocalizedException("error.calc.capacityPrecision");
        return positiveFinite(count);
    }
    private static double log2(double v) { return Math.log(v) / Math.log(2); }
    private static double positiveFinite(double v) {
        if (!Double.isFinite(v) || v <= 0) throw new LocalizedException("error.calc.resultRange");
        return v;
    }
    private static Result<CalculationResult> result(CalculationInput input, Map<String, Double> values, Map<String, String> units, boolean estimated, List<LocalizedMessage> notes) {
        return Result.success(CalculationResult.localized(values, units, estimated, notes, input));
    }
    private Result<CalculationResult> operation(CalculationInput input, CalculationKind kind) {
        if (input == null || input.operation() != kind) return Result.failureKey("error.calc.operation", kind);
        return calculate(input);
    }
    public Result<CalculationResult> depthOfField(CalculationInput input) { return operation(input, CalculationKind.DEPTH_OF_FIELD); }
    public Result<CalculationResult> hyperfocal(CalculationInput input) { return operation(input, CalculationKind.HYPERFOCAL); }
    public Result<CalculationResult> fieldOfView(CalculationInput input) { return operation(input, CalculationKind.FIELD_OF_VIEW); }
    public Result<CalculationResult> exposureAndND(CalculationInput input) { return operation(input, CalculationKind.EXPOSURE_ND); }
    public Result<CalculationResult> storage(CalculationInput input) { return operation(input, CalculationKind.STORAGE); }
    public Result<CalculationResult> batteryCapacity(CalculationInput input) { return operation(input, CalculationKind.BATTERY_CAPACITY); }
}
