package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import com.photopilot.service.TechnicalCalculator;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.math.RoundingMode;
import java.util.*;

/** Shared GUI/CLI field definitions, parsing and display; never evaluates expressions. */
public final class CalculationForm {
    private CalculationForm() { }
    public record Field(String key, String label, String unit, String hint) { }
    public enum Tool {
        EXPOSURE("exposure", EnglishMessages.text("calc.tool.EXPOSURE"), CalculationKind.EXPOSURE_ND, List.of(
            new Field("aperture", EnglishMessages.text("calc.field.aperture"), "f-number", EnglishMessages.text("calc.hint.aperture")), new Field("shutter", EnglishMessages.text("calc.field.shutter"), "s", EnglishMessages.text("calc.hint.shutter")), new Field("iso", EnglishMessages.text("calc.field.iso"), "ISO", EnglishMessages.text("calc.hint.iso")))),
        ND("nd", EnglishMessages.text("calc.tool.ND"), CalculationKind.EXPOSURE_ND, List.of(
            new Field("shutter", EnglishMessages.text("calc.field.shutter"), "s", EnglishMessages.text("calc.hint.shutter")), new Field("ndStops", EnglishMessages.text("calc.field.ndStops"), "stops", EnglishMessages.text("calc.hint.ndStops")))),
        DOF("dof", EnglishMessages.text("calc.tool.DOF"), CalculationKind.DEPTH_OF_FIELD, List.of(
            new Field("focalLength", EnglishMessages.text("calc.field.focalLength"),"mm",EnglishMessages.text("calc.hint.focalLength")),new Field("aperture", EnglishMessages.text("calc.field.aperture"),"f-number",EnglishMessages.text("calc.hint.aperture")),new Field("distance", EnglishMessages.text("calc.field.distance"),"m",EnglishMessages.text("calc.hint.distance")),new Field("coc", EnglishMessages.text("calc.field.coc"),"mm",EnglishMessages.text("calc.hint.coc")))),
        HYPERFOCAL("hyperfocal", EnglishMessages.text("calc.tool.HYPERFOCAL"), CalculationKind.HYPERFOCAL, List.of(
            new Field("focalLength", EnglishMessages.text("calc.field.focalLength"),"mm",EnglishMessages.text("calc.hint.focalLength")),new Field("aperture", EnglishMessages.text("calc.field.aperture"),"f-number",EnglishMessages.text("calc.hint.aperture")),new Field("coc", EnglishMessages.text("calc.field.coc"),"mm",EnglishMessages.text("calc.hint.coc")))),
        FOV("fov", EnglishMessages.text("calc.tool.FOV"), CalculationKind.FIELD_OF_VIEW, List.of(
            new Field("focalLength", EnglishMessages.text("calc.field.focalLength"),"mm",EnglishMessages.text("calc.hint.focalLength")),new Field("sensorWidth", EnglishMessages.text("calc.field.sensorWidth"),"mm",EnglishMessages.text("calc.hint.sensorWidth")),new Field("sensorHeight", EnglishMessages.text("calc.field.sensorHeight"),"mm",EnglishMessages.text("calc.hint.sensorHeight"))),
            List.of(new Field("distance", EnglishMessages.text("calc.field.distance"),"m",EnglishMessages.text("calc.hint.distance")))),
        MOTION("motion", EnglishMessages.text("calc.tool.MOTION"), CalculationKind.MOTION_BLUR, List.of(
            new Field("shutter", EnglishMessages.text("calc.field.shutter"), "s", EnglishMessages.text("calc.hint.shutter")),new Field("focalLength", EnglishMessages.text("calc.field.focalLength"),"mm",EnglishMessages.text("calc.hint.focalLength")),new Field("distance", EnglishMessages.text("calc.field.distance"),"m",EnglishMessages.text("calc.hint.distance")),new Field("speed", EnglishMessages.text("calc.field.speed"),"m/s",EnglishMessages.text("calc.hint.speed")),new Field("sensorWidth", EnglishMessages.text("calc.field.sensorWidth"),"mm",EnglishMessages.text("calc.hint.sensorWidth"))),
            List.of(new Field("imageWidth", EnglishMessages.text("calc.field.imageWidth"),"px",EnglishMessages.text("calc.hint.imageWidth")),new Field("blurLimit", EnglishMessages.text("calc.field.blurLimit"),"px",EnglishMessages.text("calc.hint.blurLimit"))));
        public final String command;
        public final String label;
        public final CalculationKind kind;
        public final List<Field> fields;
        /** Inputs that may be left out; each adds outputs when supplied. */
        public final List<Field> optional;
        Tool(String command, String label, CalculationKind kind, List<Field> fields) { this(command, label, kind, fields, List.of()); }
        Tool(String command, String label, CalculationKind kind, List<Field> fields, List<Field> optional) { this.command=command;this.label=label;this.kind=kind;this.fields=fields;this.optional=optional; }
        @Override public String toString() { return label; }
    }
    /**
     * The focus range when the lens is focused at a computed hyperfocal distance: the depth of field operation at
     * s = H with the same lens values, so the hyperfocal calculator has no near-limit rule of its own.
     */
    public static CalculationInput atHyperfocal(CalculationResult hyperfocal) {
        var parameters = new LinkedHashMap<>(hyperfocal.input().parameters()); var units = new LinkedHashMap<>(hyperfocal.input().units());
        parameters.put("distance", hyperfocal.values().get(TechnicalCalculator.HYPERFOCAL_DISTANCE)); units.put("distance", "m");
        return new CalculationInput(CalculationKind.DEPTH_OF_FIELD, parameters, units);
    }
    public static Result<CalculationInput> parse(Tool tool, Map<String, String> text) {
        try {
            Map<String, Double> values = new LinkedHashMap<>(); Map<String, String> units = new LinkedHashMap<>();
            var fields = new LinkedHashMap<String, Field>(); tool.fields.forEach(f -> fields.put(f.key(), f)); tool.optional.forEach(f -> fields.put(f.key(), f));
            if (tool == Tool.ND) {
                fields.put("ndFactor", new Field("ndFactor", EnglishMessages.text("calc.field.ndFactor"), "factor", ""));
                fields.put("opticalDensity", new Field("opticalDensity", EnglishMessages.text("calc.field.opticalDensity"), "OD", ""));
            }
            for (var entry : text.entrySet()) {
                Field field = fields.get(entry.getKey());
                if (field == null) throw new LocalizedException("error.calc.parameter", entry.getKey());
                values.put(field.key(), number(entry.getValue(), field.key().equals("shutter")));
                units.put(field.key(), field.unit());
            }
            return new CalculationInput(tool.kind, values, units).validate();
        } catch (IllegalArgumentException e) { return Result.failure(e); }
    }
    /** Only copies known inventory values. Zoom focal length and taking aperture require manual choice. */
    public static Map<String,String> fromGear(Tool tool, Equipment gear) {
        Map<String,String> values=new LinkedHashMap<>(knownValues(gear));
        values.keySet().removeIf(key -> tool.fields.stream().noneMatch(f -> f.key().equals(key)));
        return Map.copyOf(values);
    }
    /**
     * Everything an inventory item states that a calculator could use: a fixed focal length, sensor dimensions, an ND
     * strength. Nothing is inferred: no zoom focal length, taking aperture, circle of confusion or pixel dimensions.
     */
    public static Map<String,String> knownValues(Equipment gear) {
        Map<String,String> values=new LinkedHashMap<>();
        var specs=gear.specifications();
        if (gear instanceof Lens lens && lens.minFocalMm()==lens.maxFocalMm()) values.put("focalLength",Double.toString(lens.minFocalMm()));
        copy(specs,values,"sensorWidthMm","sensorWidth");copy(specs,values,"sensorHeightMm","sensorHeight");
        copy(specs,values,"ndStops","ndStops");
        return Map.copyOf(values);
    }
    private static void copy(Map<String,String> from,Map<String,String> to,String source,String target) {
        if (from.containsKey(source)) to.put(target,from.get(source));
    }
    /**
     * A decimal number, or (for exposure times only) a positive fraction such as 1/125. The text is taken literally:
     * 1/125 is 0.008 s and 5.6 is N = 5.6, not the exact power-of-two value behind the nominal marking.
     */
    public static double number(String text, boolean fraction) {
        if (text == null || text.isBlank()) throw new LocalizedException("error.calc.fieldsRequired");
        String[] parts = text.strip().split("/", -1);
        try {
            if (parts.length == 1) return Double.parseDouble(parts[0]);
            if (fraction && parts.length == 2) {
                double a=Double.parseDouble(parts[0].strip()), b=Double.parseDouble(parts[1].strip());
                if (!Double.isFinite(a) || !Double.isFinite(b) || a<=0 || b<=0) throw new NumberFormatException();
                double value=a/b;
                if (!Double.isFinite(value) || value<=0) throw new NumberFormatException();
                return value;
            }
        } catch (NumberFormatException e) { throw new LocalizedException("error.calc.number"); }
        throw new LocalizedException("error.calc.fraction");
    }
    public static String format(CalculationResult result) { return format(result, LocalizationService.english()); }
    public static String format(CalculationResult result, LocalizationService localization) {
        StringBuilder out = new StringBuilder(localization.text(result.estimated() ? "calc.estimate" : "calc.result"));
        result.values().keySet().stream().sorted().forEach(key -> out.append(resultLabel(key, localization)).append(": ")
            .append(result.values().get(key) == Double.POSITIVE_INFINITY ? localization.text("calc.infinity") : formatNumber(result.values().get(key)))
            .append(" ").append(unit(result.units().get(key), localization)).append('\n'));
        result.localizedAssumptions().forEach(note -> out.append(note.render(localization)).append('\n'));
        out.append(localization.text("calc.display"));
        return out.toString();
    }
    public static String unit(String unit, LocalizationService localization) {
        return switch (unit) {
            case "f-number", "stops", "factor", "degrees" -> localization.text("unit." + unit);
            default -> unit;
        };
    }
    /** The localized name of a calculated value. */
    public static String resultLabel(String key, LocalizationService localization) {
        String resource = switch (key) {
            case TechnicalCalculator.SETTING_EV -> "settingEV";
            case TechnicalCalculator.EV100 -> "ev100";
            case TechnicalCalculator.ND_STRENGTH -> "ndStrength";
            case TechnicalCalculator.ADJUSTED_SHUTTER -> "shutter";
            case TechnicalCalculator.HYPERFOCAL_DISTANCE -> "hyperfocal";
            case TechnicalCalculator.NEAR_LIMIT -> "near";
            case TechnicalCalculator.FAR_LIMIT -> "far";
            case TechnicalCalculator.TOTAL_DEPTH -> "depth";
            case TechnicalCalculator.HORIZONTAL_FOV -> "horizontalFov";
            case TechnicalCalculator.VERTICAL_FOV -> "verticalFov";
            case TechnicalCalculator.DIAGONAL_FOV -> "diagonalFov";
            case TechnicalCalculator.SCENE_WIDTH -> "sceneWidth";
            case TechnicalCalculator.SCENE_HEIGHT -> "sceneHeight";
            case TechnicalCalculator.SENSOR_BLUR -> "sensorBlur";
            case TechnicalCalculator.BLUR_SHARE -> "blurShare";
            case TechnicalCalculator.IMAGE_BLUR -> "imageBlur";
            case TechnicalCalculator.SUBJECT_TRAVEL -> "subjectTravel";
            case TechnicalCalculator.LONGEST_EXPOSURE -> "longestExposure";
            default -> null;
        };
        return resource == null ? key : localization.text("calc.output." + resource);
    }
    public static String formatNumber(double v) {
        if (v == Double.POSITIVE_INFINITY) return "Infinity";
        DecimalFormat f=new DecimalFormat(v!=0 && (Math.abs(v)<.000001 || Math.abs(v)>=1e9) ? "0.######E0" : "0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP); return f.format(v);
    }
}
