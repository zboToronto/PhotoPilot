package com.photopilot.model;

import com.photopilot.common.Result;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Explicit transport representation: no reflective deserialization of domain objects. */
public record EquipmentData(String type, String id, String brand, String model, int quantity,
        Double weightGrams, Set<String> verifiedFields, Map<String, String> sources,
        String verifiedAt, Map<String, String> specs) {
    public EquipmentData {
        Objects.requireNonNull(type); Objects.requireNonNull(id);
        Objects.requireNonNull(brand); Objects.requireNonNull(model);
        verifiedFields = Set.copyOf(verifiedFields);
        sources = Map.copyOf(sources);
        specs = Map.copyOf(specs);
    }
    public static EquipmentData from(Equipment item) {
        return new EquipmentData(item.type().name(), item.id().toString(), item.brand(), item.model(), item.quantity(),
            item.weightGrams().orElse(null), item.verifiedFields(), item.sources().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toString())),
            item.verifiedAt().map(Instant::toString).orElse(null), item.specifications());
    }
    public Result<Equipment> toEquipment() {
        try {
            EquipmentType kind = EquipmentType.valueOf(type);
            kind.validate(specs);
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException("ID must be a full UUID.");
            Optional<Double> weight = Optional.ofNullable(weightGrams);
            Map<String, URI> links = sources.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> URI.create(e.getValue())));
            Optional<Instant> date = Optional.ofNullable(verifiedAt).map(Instant::parse);
            String b = brand.strip(), m = model.strip();
            Equipment item = switch (kind) {
                case CAMERA -> new Camera(uuid,b,m,quantity,weight,verifiedFields,links,date,text("mount"),number("sensorWidthMm"),number("sensorHeightMm"),set("batteryTypes"),set("storageFormats"),number("resolutionMP"),number("burstFps"),bool("stabilization"),set("autofocusModes"));
                case LENS -> new Lens(uuid,b,m,quantity,weight,verifiedFields,links,date,text("mount"),required("minFocalMm"),required("maxFocalMm"),required("maxAperture"),number("filterDiameterMm"),bool("autofocus"),bool("stabilization"));
                case FILTER -> new Filter(uuid,b,m,quantity,weight,verifiedFields,links,date,required("diameterMm"),number("ndStops"));
                case BATTERY -> new Battery(uuid,b,m,quantity,weight,verifiedFields,links,date,text("batteryType"),required("capacityMah"),text("ratedShots").isEmpty() ? Optional.empty() : Optional.of(Integer.parseInt(text("ratedShots"))));
                case STORAGEMEDIA -> new StorageMedia(uuid,b,m,quantity,weight,verifiedFields,links,date,text("format"),required("capacityGB"),number("writeSpeedMBps"));
            };
            return item.validate();
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            return Result.failure("Invalid equipment: " + error.getMessage());
        }
    }
    private String text(String key) { return specs.getOrDefault(key, "").strip(); }
    private double required(String key) { return Double.parseDouble(text(key)); }
    private Optional<Double> number(String key) { return text(key).isEmpty() ? Optional.empty() : Optional.of(required(key)); }
    private Optional<Boolean> bool(String key) { return text(key).isEmpty() ? Optional.empty() : Optional.of(Boolean.parseBoolean(text(key))); }
    private Set<String> set(String key) { return Arrays.stream(text(key).split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toSet()); }
}
