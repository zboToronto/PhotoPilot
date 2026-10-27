package com.photopilot.model;

import java.util.*;
import com.photopilot.common.Result;

/** F05 aggregate. Changes are applied to freshly loaded transactions, never a UI cache. */
public class GearInventory {
    private final List<Equipment> items;
    private final String revision;
    public GearInventory(List<Equipment> items) { this(items, "missing"); }
    public GearInventory(List<Equipment> items, String revision) {
        this.items = new ArrayList<>(items);
        this.revision = Objects.requireNonNull(revision);
        Set<UUID> ids = new HashSet<>();
        for (Equipment item : items) {
            if (!item.validate().isSuccess()) throw new IllegalArgumentException(String.join("; ", item.validate().errors()));
            if (!ids.add(item.id())) throw new IllegalArgumentException("Duplicate equipment ID: " + item.id());
        }
    }
    public List<Equipment> items() { return List.copyOf(items); }
    public String revision() { return revision; }
    public Optional<Equipment> find(UUID id) { return items.stream().filter(item -> item.id().equals(id)).findFirst(); }
    public Result<Equipment> add(Equipment item) {
        if (item == null) return Result.failure("Equipment is required.");
        if (!item.validate().isSuccess()) return item.validate();
        if (find(item.id()).isPresent()) return Result.failure("Duplicate equipment ID: " + item.id());
        items.add(item);
        return Result.success(item);
    }
    public Result<Equipment> update(Equipment item) {
        if (item == null) return Result.failure("Equipment is required.");
        if (!item.validate().isSuccess()) return item.validate();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(item.id())) {
                if (items.get(i).type() != item.type()) return Result.failure("An existing item's equipment type cannot change.");
                items.set(i, item);
                return Result.success(item);
            }
        }
        return Result.failure("Equipment no longer exists. Refresh the inventory.");
    }
    public List<Equipment> search(String query) {
        String needle = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        return items.stream().filter(item -> (item.brand() + " " + item.model() + " " + item.type() + " " + item.id()
            + " " + item.specifications().values()).toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    public Result<Boolean> remove(UUID id) {
        if (id == null) return Result.failure("Equipment ID is required.");
        return items.removeIf(item -> item.id().equals(id)) ? Result.success(true) : Result.failure("Equipment no longer exists.");
    }
}
