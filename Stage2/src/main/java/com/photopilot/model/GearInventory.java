package com.photopilot.model;

import java.util.List;
import java.util.UUID;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 46 (zero-based): {F05; aggregate of independently identified gear}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GearInventory {
    private List<Equipment> items;

    public GearInventory(List<Equipment> items) {
        this.items = List.copyOf(items);
    }

    public Result<Equipment> add(Equipment item) {
        return Result.failure("NOT IMPLEMENTED: GearInventory.add");
    }

    public Result<Equipment> update(Equipment item) {
        return Result.failure("NOT IMPLEMENTED: GearInventory.update");
    }

    public List<Equipment> search(String query) {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: GearInventory.search");
    }

    public Result<Boolean> remove(UUID id) {
        return Result.failure("NOT IMPLEMENTED: GearInventory.remove");
    }
}
