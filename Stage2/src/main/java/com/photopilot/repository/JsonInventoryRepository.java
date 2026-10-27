package com.photopilot.repository;

import java.nio.file.Path;
import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.model.GearInventory;

/**
 * Stage 1 UML element 54 (zero-based): {data implementation; atomic write}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class JsonInventoryRepository implements InventoryRepository {
    private Path file;

    public JsonInventoryRepository(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Result<GearInventory> load() {
        return Result.failure("NOT IMPLEMENTED: JsonInventoryRepository.load");
    }

    public Result<GearInventory> save(GearInventory inventory) {
        return Result.failure("NOT IMPLEMENTED: JsonInventoryRepository.save");
    }
}
