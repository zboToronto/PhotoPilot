package com.photopilot.model;

import com.photopilot.i18n.*;
import java.util.List;
import java.util.Map;

/** Planning-relevant input definitions shared by desktop and command-line forms. */
public enum EquipmentType {
    CAMERA(EnglishMessages.text("gear.type.CAMERA"), List.of(text("mount", EnglishMessages.text("gear.field.mount"), true), number("sensorWidthMm", EnglishMessages.text("gear.field.sensorWidthMm"), false),
        number("sensorHeightMm", EnglishMessages.text("gear.field.sensorHeightMm"), false), text("batteryTypes", EnglishMessages.text("gear.field.batteryTypes"), false),
        text("storageFormats", EnglishMessages.text("gear.field.storageFormats"), false), number("resolutionMP", EnglishMessages.text("gear.field.resolutionMP"), false),
        number("burstFps", EnglishMessages.text("gear.field.burstFps"), false), bool("stabilization", EnglishMessages.text("gear.field.stabilization")), text("autofocusModes", EnglishMessages.text("gear.field.autofocusModes"), false))),
    LENS(EnglishMessages.text("gear.type.LENS"), List.of(text("mount", EnglishMessages.text("gear.field.mount"), true), number("minFocalMm", EnglishMessages.text("gear.field.minFocalMm"), true),
        number("maxFocalMm", EnglishMessages.text("gear.field.maxFocalMm"), true), number("maxAperture", EnglishMessages.text("gear.field.maxAperture"), true),
        number("filterDiameterMm", EnglishMessages.text("gear.field.filterDiameterMm"), false), bool("autofocus", EnglishMessages.text("gear.field.autofocus")), bool("stabilization", EnglishMessages.text("gear.field.stabilization")))),
    FILTER(EnglishMessages.text("gear.type.FILTER"), List.of(number("diameterMm", EnglishMessages.text("gear.field.diameterMm"), true), new Field("ndStops", EnglishMessages.text("gear.field.ndStops"), "nonnegative", false))),
    BATTERY(EnglishMessages.text("gear.type.BATTERY"), List.of(text("batteryType", EnglishMessages.text("gear.field.batteryType"), true), number("capacityMah", EnglishMessages.text("gear.field.capacityMah"), true), new Field("ratedShots", EnglishMessages.text("gear.field.ratedShots"), "integer", false))),
    STORAGEMEDIA(EnglishMessages.text("gear.type.STORAGEMEDIA"), List.of(text("format", EnglishMessages.text("gear.field.format"), true), number("capacityGB", EnglishMessages.text("gear.field.capacityGB"), true), number("writeSpeedMBps", EnglishMessages.text("gear.field.writeSpeedMBps"), false)));

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
            if (fields.stream().noneMatch(field -> field.key().equals(key))) throw new LocalizedException("error.gear.unknownSpec", key);
        }
        for (Field field : fields) {
            String value = values.getOrDefault(field.key(), "").strip();
            if (value.isEmpty()) {
                if (field.required()) throw new LocalizedException("error.validation.required", LocalizedMessage.of("gear.field." + field.key()));
                continue;
            }
            try {
                switch (field.kind()) {
                    case "positive" -> positive(Double.parseDouble(value), LocalizedMessage.of("gear.field." + field.key()));
                    case "integer" -> positive(Integer.parseInt(value), LocalizedMessage.of("gear.field." + field.key()));
                    case "nonnegative" -> {
                        double n = Double.parseDouble(value);
                        if (!Double.isFinite(n) || n < 0) throw new LocalizedException("error.validation.finiteNonnegative", LocalizedMessage.of("gear.field." + field.key()));
                    }
                    case "boolean" -> {
                        if (!value.equals("true") && !value.equals("false")) throw new LocalizedException("error.validation.boolean", LocalizedMessage.of("gear.field." + field.key()));
                    }
                    default -> { }
                }
            } catch (NumberFormatException error) {
                throw new LocalizedException(field.kind().equals("integer") ? "error.validation.integer" : "error.validation.number", LocalizedMessage.of("gear.field." + field.key()));
            }
        }
        if (this == LENS && Double.parseDouble(values.get("minFocalMm")) > Double.parseDouble(values.get("maxFocalMm"))) throw new LocalizedException("error.gear.focalRange");
    }
    public static void positive(double value, Object label) {
        if (!Double.isFinite(value) || value <= 0) throw new LocalizedException("error.validation.finitePositive", label);
    }
}
