package com.photopilot.model;

import java.util.List;
import java.util.Map;

/** Planning-relevant input definitions shared by desktop and command-line forms. */
public enum EquipmentType {
    CAMERA("Camera", List.of(text("mount", "Mount", true), number("sensorWidthMm", "Sensor width (mm)", false),
        number("sensorHeightMm", "Sensor height (mm)", false), text("batteryTypes", "Battery types (comma separated)", false),
        text("storageFormats", "Storage formats (comma separated)", false), number("resolutionMP", "Resolution (MP)", false),
        number("burstFps", "Burst rate (fps)", false), bool("stabilization", "Stabilization"), text("autofocusModes", "Autofocus modes (comma separated)", false))),
    LENS("Lens", List.of(text("mount", "Mount", true), number("minFocalMm", "Minimum focal length (mm)", true),
        number("maxFocalMm", "Maximum focal length (mm)", true), number("maxAperture", "Maximum aperture (f-number)", true),
        number("filterDiameterMm", "Filter diameter (mm)", false), bool("autofocus", "Autofocus"), bool("stabilization", "Stabilization"))),
    FILTER("Filter", List.of(number("diameterMm", "Diameter (mm)", true), new Field("ndStops", "ND stops", "nonnegative", false))),
    BATTERY("Battery", List.of(text("batteryType", "Battery type", true), number("capacityMah", "Capacity (mAh)", true), new Field("ratedShots", "Rated shots", "integer", false))),
    STORAGEMEDIA("Storage media", List.of(text("format", "Format", true), number("capacityGB", "Capacity (GB)", true), number("writeSpeedMBps", "Write speed (MB/s)", false)));

    public record Field(String key, String label, String kind, boolean required) { }
    private final String label;
    private final List<Field> fields;
    EquipmentType(String label, List<Field> fields) { this.label = label; this.fields = fields; }
    public List<Field> fields() { return fields; }
    @Override public String toString() { return label; }
    private static Field text(String key, String label, boolean required) { return new Field(key, label, "text", required); }
    private static Field number(String key, String label, boolean required) { return new Field(key, label, "positive", required); }
    private static Field bool(String key, String label) { return new Field(key, label, "boolean", false); }

    public void validate(Map<String, String> values) {
        for (String key : values.keySet()) {
            if (fields.stream().noneMatch(field -> field.key().equals(key))) throw new IllegalArgumentException("Unknown specification: " + key);
        }
        for (Field field : fields) {
            String value = values.getOrDefault(field.key(), "").strip();
            if (value.isEmpty()) {
                if (field.required()) throw new IllegalArgumentException(field.label() + " is required.");
                continue;
            }
            try {
                switch (field.kind()) {
                    case "positive" -> positive(Double.parseDouble(value), field.label());
                    case "integer" -> positive(Integer.parseInt(value), field.label());
                    case "nonnegative" -> {
                        double n = Double.parseDouble(value);
                        if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException(field.label() + " must be finite and nonnegative.");
                    }
                    case "boolean" -> {
                        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(field.label() + " must be true, false, or blank (unknown).");
                    }
                    default -> { }
                }
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(field.label() + " must be a valid " + (field.kind().equals("integer") ? "integer." : "number."));
            }
        }
        if (this == LENS && Double.parseDouble(values.get("minFocalMm")) > Double.parseDouble(values.get("maxFocalMm"))) throw new IllegalArgumentException("Minimum focal length cannot exceed maximum focal length.");
    }
    public static void positive(double value, String label) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(label + " must be finite and greater than zero.");
    }
}
