package com.photopilot.application;

import com.photopilot.common.Result;
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
        EXPOSURE("exposure", "Exposure value", CalculationKind.EXPOSURE_ND, List.of(
            new Field("aperture", "Aperture", "f-number", "e.g. 8"), new Field("shutter", "Shutter", "s", "e.g. 1/125"), new Field("iso", "Sensitivity", "ISO", "e.g. 100"))),
        ND("nd", "ND exposure", CalculationKind.EXPOSURE_ND, List.of(
            new Field("shutter", "Original shutter", "s", "e.g. 1/125"), new Field("ndStops", "ND strength", "stops", "e.g. 10"))),
        DOF("dof", "Depth of field", CalculationKind.DEPTH_OF_FIELD, List.of(
            new Field("focalLength","Focal length","mm","e.g. 50"),new Field("aperture","Aperture","f-number","e.g. 8"),new Field("distance","Subject distance","m","e.g. 3"),new Field("coc","Circle of confusion","mm","e.g. 0.03; choose your assumption"))),
        HYPERFOCAL("hyperfocal", "Hyperfocal distance", CalculationKind.HYPERFOCAL, List.of(
            new Field("focalLength","Focal length","mm","e.g. 50"),new Field("aperture","Aperture","f-number","e.g. 8"),new Field("coc","Circle of confusion","mm","e.g. 0.03; choose your assumption"))),
        FOV("fov", "Field of view", CalculationKind.FIELD_OF_VIEW, List.of(
            new Field("focalLength","Focal length","mm","e.g. 50"),new Field("sensorWidth","Active sensor width","mm","e.g. 36"),new Field("sensorHeight","Active sensor height","mm","e.g. 24"))),
        STORAGE("storage", "Storage estimate", CalculationKind.STORAGE, List.of(
            new Field("shotCount","Planned shots","shots","e.g. 1000"),new Field("fileSize","Average file size","MB","e.g. 25"),new Field("cardCapacity","Usable card capacity","GB","e.g. 64"))),
        BATTERY("battery", "Battery / shooting capacity", CalculationKind.BATTERY_CAPACITY, List.of(
            new Field("shotCount","Planned shots","shots","e.g. 1000"),new Field("ratedShots","Rated shots per battery","shots","e.g. 600")));
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
                fields.put("ndFactor", new Field("ndFactor", "ND factor", "factor", ""));
                fields.put("opticalDensity", new Field("opticalDensity", "Optical density", "OD", ""));
            }
            for (var entry : text.entrySet()) {
                Field field = fields.get(entry.getKey());
                if (field == null) throw new IllegalArgumentException("Unsupported parameter: " + entry.getKey());
                values.put(field.key(), number(entry.getValue(), field.key().equals("shutter")));
                units.put(field.key(), field.unit());
            }
            return new CalculationInput(tool.kind, values, units).validate();
        } catch (IllegalArgumentException e) { return Result.failure(e.getMessage()); }
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
        if (text == null || text.isBlank()) throw new IllegalArgumentException("All displayed inputs are required.");
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
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a finite number, or a positive shutter fraction such as 1/125."); }
        throw new IllegalArgumentException("Only shutter speeds accept fractions such as 1/125.");
    }
    public static String format(CalculationResult result) {
        StringBuilder out=new StringBuilder(result.estimated() ? "Theoretical approximation / estimate\n" : "Calculated result\n");
        result.values().keySet().stream().sorted().forEach(key -> out.append(key).append(": ").append(formatNumber(result.values().get(key))).append(" ").append(result.units().get(key)).append('\n'));
        result.assumptions().forEach(note -> out.append(note).append('\n'));
        out.append("Display: up to 6 decimals, half-up rounding; scientific notation for very small/large values. Internal values retain double precision.");
        return out.toString();
    }
    public static String formatNumber(double v) {
        if (v == Double.POSITIVE_INFINITY) return "Infinity";
        DecimalFormat f=new DecimalFormat(v!=0 && (Math.abs(v)<.000001 || Math.abs(v)>=1e9) ? "0.######E0" : "0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP); return f.format(v);
    }
}
