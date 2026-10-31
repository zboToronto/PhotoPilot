package com.photopilot.repository;

import com.photopilot.i18n.*;

import com.photopilot.common.Result;
import com.photopilot.model.GearInventory;

/**
 * Stage 1 UML element 53 (zero-based): {persistence port}
 * Implementations return detached aggregates and preserve data on failed transactions.
 */
public interface InventoryRepository {

    public Result<GearInventory> load();

    /** Compare the input revision and return the saved aggregate with its new revision. */
    public Result<GearInventory> save(GearInventory inventory);

    /** One read/validate/write operation; storage implementations must serialize writers. */
    default <T> Result<T> transact(java.util.function.Function<GearInventory, Result<T>> change) {
        synchronized (this) {
            var loaded = load();
            if (!loaded.isSuccess()) return Result.failure(loaded);
            var inventory = loaded.value().orElseThrow();
            var result = change.apply(inventory);
            if (!result.isSuccess()) return result;
            var saved = save(inventory);
            return saved.isSuccess() ? result : Result.failure(saved);
        }
    }
}
