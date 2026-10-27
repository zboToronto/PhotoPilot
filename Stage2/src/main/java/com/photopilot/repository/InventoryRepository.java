package com.photopilot.repository;


import com.photopilot.common.Result;
import com.photopilot.model.GearInventory;

/**
 * Stage 1 UML element 53 (zero-based): {persistence port}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface InventoryRepository {

    public Result<GearInventory> load();

    public Result<GearInventory> save(GearInventory inventory);

    /** One read/validate/write operation; storage implementations must serialize writers. */
    default <T> Result<T> transact(java.util.function.Function<GearInventory, Result<T>> change) {
        synchronized (this) {
            var loaded = load();
            if (!loaded.isSuccess()) return Result.failure(String.join("; ", loaded.errors()));
            var inventory = loaded.value().orElseThrow();
            var result = change.apply(inventory);
            if (!result.isSuccess()) return result;
            var saved = save(inventory);
            return saved.isSuccess() ? result : Result.failure(String.join("; ", saved.errors()));
        }
    }
}
