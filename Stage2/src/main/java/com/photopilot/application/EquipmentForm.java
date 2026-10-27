package com.photopilot.application;

import com.photopilot.i18n.*;
import com.photopilot.common.Result;
import com.photopilot.model.*;
import java.util.*;

/** Shared input mapping only; Equipment and InventoryService own validation and persistence. */
public final class EquipmentForm {
    private EquipmentForm() { }
    public static Map<String, String> values(Equipment item) {
        var values = new LinkedHashMap<>(item.specifications());
        values.put("brand", item.brand()); values.put("model", item.model());
        values.put("quantity", String.valueOf(item.quantity()));
        values.put("weightGrams", item.weightGrams().map(String::valueOf).orElse(""));
        return values;
    }
    public static Result<Equipment> parse(EquipmentType type, UUID id, Map<String, String> changes, Equipment original) {
        try {
            var values = original == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(values(original));
            var allowed = new HashSet<>(List.of("brand", "model", "quantity", "weightGrams"));
            type.fields().forEach(field -> allowed.add(field.key()));
            for (var entry : changes.entrySet()) {
                if (!allowed.contains(entry.getKey())) return Result.failureKey("error.gear.unknownField", entry.getKey());
                values.put(entry.getKey(), entry.getValue().strip());
            }
            String brand = values.remove("brand"), model = values.remove("model");
            if (brand == null || model == null) return Result.failureKey("error.gear.brandModel");
            int quantity = Integer.parseInt(values.getOrDefault("quantity", "1")); values.remove("quantity");
            String weight = values.remove("weightGrams");
            Double grams = weight == null || weight.isBlank() ? null : Double.valueOf(weight);
            values.values().removeIf(String::isBlank);
            Set<String> verified = new HashSet<>();
            Map<String, String> sources = new HashMap<>();
            String verifiedAt = null;
            if (original != null) {
                var before = EquipmentData.from(original);
                verified.addAll(before.verifiedFields()); sources.putAll(before.sources()); verifiedAt = before.verifiedAt();
                var old = values(original);
                for (String key : changes.keySet()) {
                    if (!Objects.equals(old.getOrDefault(key, ""), changes.get(key).strip())) { verified.remove(key); sources.remove(key); }
                }
                if (!original.brand().equals(brand) || !original.model().equals(model)) { verified.clear(); sources.clear(); }
                if (verified.isEmpty()) verifiedAt = null;
            }
            return new EquipmentData(type.name(), id.toString(), brand, model, quantity, grams, verified, sources, verifiedAt, values).toEquipment();
        } catch (IllegalArgumentException error) { return Result.failureKey("error.gear.number"); }
    }
}
