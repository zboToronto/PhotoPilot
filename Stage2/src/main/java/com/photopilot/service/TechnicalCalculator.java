package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import java.util.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import static com.photopilot.service.CalculationValidation.value;

/**
 * Stage 1 UML element 90: deterministic F08 suite, shared by Facade and agent tool.
 * Validated entry point of every calculator: it checks the input, converts units and evaluates {@link PhotoFormulas}.
 * Results for an unchanged input keep their value names, because F10 recomputes stored plan calculations and compares
 * them; new outputs therefore appear only with a new operation (MOTION_BLUR) or a new optional parameter.
 */
public class TechnicalCalculator {
    /** Names of the calculated values (also the keys of the agent tool's JSON evidence). */
    public static final String SETTING_EV = "Camera setting EV", EV100 = "ISO-normalized EV100", ND_STRENGTH = "ND strength", ADJUSTED_SHUTTER = "Adjusted shutter",
        HYPERFOCAL_DISTANCE = "Hyperfocal distance", NEAR_LIMIT = "Near focus limit", FAR_LIMIT = "Far focus limit", TOTAL_DEPTH = "Total depth of field",
        HORIZONTAL_FOV = "Horizontal field of view", VERTICAL_FOV = "Vertical field of view", DIAGONAL_FOV = "Diagonal field of view",
        SCENE_WIDTH = "Scene width", SCENE_HEIGHT = "Scene height",
        SENSOR_BLUR = "Sensor blur", BLUR_SHARE = "Blur share of image width", IMAGE_BLUR = "Image blur", SUBJECT_TRAVEL = "Subject travel",
        LONGEST_EXPOSURE = "Longest exposure for blur limit";
    public TechnicalCalculator() { }
    public Result<CalculationResult> calculate(CalculationInput input) {
        var checked = CalculationValidation.validate(input);
        if (!checked.isSuccess()) return Result.failure(checked);
        try {
            return switch (input.operation()) {
                case EXPOSURE_ND -> exposure(input);
                case HYPERFOCAL, DEPTH_OF_FIELD -> focus(input);
                case FIELD_OF_VIEW -> view(input);
                case MOTION_BLUR -> motion(input);
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
            double ev = PhotoFormulas.settingsEv(value(input, "aperture"), t);
            values.put(SETTING_EV, ev); units.put(SETTING_EV, "EV");
            values.put(EV100, PhotoFormulas.ev100(ev, value(input, "iso")));
            units.put(EV100, "EV100");
            notes.add(LocalizedMessage.of("calc.assumption.exposure"));
        }
        String nd = List.of("ndStops", "ndFactor", "opticalDensity").stream().filter(input.parameters()::containsKey).findFirst().orElse(null);
        if (nd != null) {
            double strength = value(input, nd);
            double stops = switch (nd) { case "ndFactor" -> PhotoFormulas.stopsFromFactor(strength); case "opticalDensity" -> PhotoFormulas.stopsFromDensity(strength); default -> strength; };
            double adjusted = positiveFinite(PhotoFormulas.ndExposure(t, stops));
            values.put(ND_STRENGTH, stops); units.put(ND_STRENGTH, "stops");
            values.put(ADJUSTED_SHUTTER, adjusted); units.put(ADJUSTED_SHUTTER, "s");
            notes.add(LocalizedMessage.of("calc.assumption.nd"));
        }
        return result(input, values, units, false, notes);
    }
    private Result<CalculationResult> focus(CalculationInput input) {
        double f=value(input,"focalLength"), n=value(input,"aperture"), c=value(input,"coc");
        // Formulas work in mm; distances are reported in m. H = q + f, so q = H - f must itself be representable.
        double h=positiveFinite(PhotoFormulas.hyperfocal(f,n,c));
        positiveFinite(h-f);
        double hMetres=positiveFinite(h/1000);
        Map<String,Double> values=new LinkedHashMap<>();Map<String,String> units=new LinkedHashMap<>();
        values.put(HYPERFOCAL_DISTANCE,hMetres);units.put(HYPERFOCAL_DISTANCE,"m");
        if (input.operation()==CalculationKind.DEPTH_OF_FIELD) {
            double metres=value(input,"distance"), distance=positiveFinite(metres*1000);
            // The hyperfocal distance is reported in metres. Converting a distance to mm must not move it across that
            // boundary, so focusing exactly at the reported hyperfocal distance is exactly the unbounded case.
            if (metres>=hMetres) distance=Math.max(distance,h); else if (distance>=h) distance=Math.nextDown(h);
            var range=PhotoFormulas.focusRange(f,n,c,distance);
            double near=positiveFinite(range.near()/1000);
            double far=range.farUnbounded() ? Double.POSITIVE_INFINITY : positiveFinite(range.far()/1000);
            double depth=range.farUnbounded() ? far : positiveFinite(far-near);
            values.put(NEAR_LIMIT,near);values.put(FAR_LIMIT,far);values.put(TOTAL_DEPTH,depth);
            units.put(NEAR_LIMIT,"m");units.put(FAR_LIMIT,"m");units.put(TOTAL_DEPTH,"m");
        }
        return result(input,values,units,true,List.of(LocalizedMessage.of("calc.assumption.focusFormula"),
            LocalizedMessage.of("calc.assumption.focusLimits")));
    }
    private Result<CalculationResult> view(CalculationInput input) {
        double f=value(input,"focalLength"), w=value(input,"sensorWidth"), h=value(input,"sensorHeight");
        double diagonal=positiveFinite(Math.hypot(w,h));
        Map<String,Double> values=new LinkedHashMap<>();Map<String,String> units=new LinkedHashMap<>();
        values.put(HORIZONTAL_FOV,angle(w,f));values.put(VERTICAL_FOV,angle(h,f));values.put(DIAGONAL_FOV,angle(diagonal,f));
        for (String key:List.of(HORIZONTAL_FOV,VERTICAL_FOV,DIAGONAL_FOV)) units.put(key,"degrees");
        List<LocalizedMessage> notes=new ArrayList<>(List.of(LocalizedMessage.of("calc.assumption.fov")));
        // Optional scene coverage; without a distance the result is exactly the three angles, as before.
        if (input.parameters().containsKey("distance")) {
            double d=value(input,"distance");
            values.put(SCENE_WIDTH,positiveFinite(PhotoFormulas.coverage(d,w,f)));values.put(SCENE_HEIGHT,positiveFinite(PhotoFormulas.coverage(d,h,f)));
            units.put(SCENE_WIDTH,"m");units.put(SCENE_HEIGHT,"m");
            notes.add(LocalizedMessage.of("calc.assumption.coverage"));
        }
        return result(input,values,units,true,notes);
    }
    private static double angle(double dimension,double focal) {
        positiveFinite((dimension/focal)/2);
        double angle=positiveFinite(PhotoFormulas.angleOfView(dimension,focal));
        if (angle>=180) throw new LocalizedException("error.calc.fovPrecision");
        return angle;
    }
    /** Simplified still-photography model: stationary camera, constant lateral subject motion, distance much larger than the focal length. */
    private Result<CalculationResult> motion(CalculationInput input) {
        double t=value(input,"shutter"), f=value(input,"focalLength"), d=value(input,"distance"), v=value(input,"speed"), width=value(input,"sensorWidth");
        Map<String,Double> values=new LinkedHashMap<>();Map<String,String> units=new LinkedHashMap<>();
        double blur=nonnegativeFinite(PhotoFormulas.motionBlur(f,v,t,d));
        // A positive speed must give a positive blur; an underflow to zero would wrongly read as "no motion".
        if (v>0 && blur==0) throw new LocalizedException("error.calc.resultRange");
        values.put(SENSOR_BLUR,blur);units.put(SENSOR_BLUR,"mm");
        values.put(BLUR_SHARE,nonnegativeFinite(blur/width*100));units.put(BLUR_SHARE,"%");
        values.put(SUBJECT_TRAVEL,nonnegativeFinite(v*t));units.put(SUBJECT_TRAVEL,"m");
        boolean pixels=input.parameters().containsKey("imageWidth");
        if (pixels) { values.put(IMAGE_BLUR,nonnegativeFinite(PhotoFormulas.blurPixels(blur,value(input,"imageWidth"),width)));units.put(IMAGE_BLUR,"px"); }
        List<LocalizedMessage> notes=new ArrayList<>(List.of(LocalizedMessage.of("calc.assumption.motion")));
        if (!pixels) notes.add(LocalizedMessage.of("calc.assumption.motionNoPixels"));
        if (d<10*f/1000) notes.add(LocalizedMessage.of("calc.assumption.motionClose"));
        if (input.parameters().containsKey("blurLimit")) {
            // A pixel limit is converted with the same image width that relates pixels to the sensor.
            double limit=value(input,"blurLimit");
            if (input.units().get("blurLimit").equals("px")) limit=positiveFinite(limit*width/value(input,"imageWidth"));
            double longest=PhotoFormulas.longestExposure(limit,f,v,d);
            // Zero lateral speed: motion imposes no upper limit, reported as an explicit Infinity.
            values.put(LONGEST_EXPOSURE,v==0 ? Double.POSITIVE_INFINITY : positiveFinite(longest));units.put(LONGEST_EXPOSURE,"s");
            if (v==0) notes.add(LocalizedMessage.of("calc.assumption.motionStill"));
        }
        return result(input,values,units,true,notes);
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
    private static double positiveFinite(double v) {
        if (!Double.isFinite(v) || v <= 0) throw new LocalizedException("error.calc.resultRange");
        return v;
    }
    private static double nonnegativeFinite(double v) {
        if (!Double.isFinite(v) || v < 0) throw new LocalizedException("error.calc.resultRange");
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
    public Result<CalculationResult> motionBlur(CalculationInput input) { return operation(input, CalculationKind.MOTION_BLUR); }
}
