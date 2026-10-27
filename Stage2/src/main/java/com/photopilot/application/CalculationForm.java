package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
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
            new Field("focalLength", EnglishMessages.text("calc.field.focalLength"),"mm",EnglishMessages.text("calc.hint.focalLength")),new Field("sensorWidth", EnglishMessages.text("calc.field.sensorWidth"),"mm",EnglishMessages.text("calc.hint.sensorWidth")),new Field("sensorHeight", EnglishMessages.text("calc.field.sensorHeight"),"mm",EnglishMessages.text("calc.hint.sensorHeight")))),
        STORAGE("storage", EnglishMessages.text("calc.tool.STORAGE"), CalculationKind.STORAGE, List.of(
            new Field("shotCount", EnglishMessages.text("calc.field.shotCount"),"shots",EnglishMessages.text("calc.hint.shotCount")),new Field("fileSize", EnglishMessages.text("calc.field.fileSize"),"MB",EnglishMessages.text("calc.hint.fileSize")),new Field("cardCapacity", EnglishMessages.text("calc.field.cardCapacity"),"GB",EnglishMessages.text("calc.hint.cardCapacity")))),
        BATTERY("battery", EnglishMessages.text("calc.tool.BATTERY"), CalculationKind.BATTERY_CAPACITY, List.of(
            new Field("shotCount", EnglishMessages.text("calc.field.shotCount"),"shots",EnglishMessages.text("calc.hint.shotCount")),new Field("ratedShots", EnglishMessages.text("calc.field.ratedShots"),"shots",EnglishMessages.text("calc.hint.ratedShots"))));
        public final String command;
        public final String label;
        public final CalculationKind kind;
        public final List<Field> fields;
        Tool(String command, String label, CalculationKind kind, List<Field> fields) { this.command=command;this.label=label;this.kind=kind;this.fields=fields; }
        @Override public String toString() { return label; }
    }
    public static Result<CalculationInput> parse(Tool tool, Map<String, String> text) {
        try {
            Map<String, Double> values = new LinkedHashMap<>(); Map<String, String> units = new LinkedHashMap<>();
            var fields = new LinkedHashMap<String, Field>(); tool.fields.forEach(f -> fields.put(f.key(), f));
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
        Map<String,String> values=new LinkedHashMap<>();
        var specs=gear.specifications();
        if (gear instanceof Lens lens && lens.minFocalMm()==lens.maxFocalMm()) values.put("focalLength",Double.toString(lens.minFocalMm()));
        copy(specs,values,"sensorWidthMm","sensorWidth");copy(specs,values,"sensorHeightMm","sensorHeight");
        copy(specs,values,"ndStops","ndStops");copy(specs,values,"capacityGB","cardCapacity");copy(specs,values,"ratedShots","ratedShots");
        values.keySet().removeIf(key -> tool.fields.stream().noneMatch(f -> f.key().equals(key)));
        return Map.copyOf(values);
    }
    private static void copy(Map<String,String> from,Map<String,String> to,String source,String target) {
        if (from.containsKey(source)) to.put(target,from.get(source));
    }
    private static double number(String text, boolean fraction) {
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
            case "f-number", "stops", "shots", "factor", "degrees", "cards", "batteries" -> localization.text("unit." + unit);
            default -> unit;
        };
    }
    private static String resultLabel(String key, LocalizationService localization) {
        String resource = switch (key) {
            case "Camera setting EV" -> "settingEV";
            case "ISO-normalized EV100" -> "ev100";
            case "ND strength" -> "ndStrength";
            case "Adjusted shutter" -> "shutter";
            case "Hyperfocal distance" -> "hyperfocal";
            case "Near focus limit" -> "near";
            case "Far focus limit" -> "far";
            case "Total depth of field" -> "depth";
            case "Horizontal field of view" -> "horizontalFov";
            case "Vertical field of view" -> "verticalFov";
            case "Diagonal field of view" -> "diagonalFov";
            case "Required storage" -> "storage";
            case "Cards required" -> "cards";
            case "Batteries required" -> "batteries";
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
