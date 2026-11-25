package com.photopilot.repository;

import com.photopilot.common.Result;
import com.photopilot.model.GearInventory;
import java.util.Objects;
import java.util.function.Function;

/**
 * Decorator: the same inventory store, announcing each successful save or transaction to {@link StoreChanges} after it
 * returned. The delegate's own transaction (its locking and atomic write) is used, never the interface default.
 */
final class ObservedInventoryRepository implements InventoryRepository {
    private final InventoryRepository delegate;
    private final StoreChanges changes;

    ObservedInventoryRepository(InventoryRepository delegate, StoreChanges changes) {
        this.delegate = Objects.requireNonNull(delegate, "delegate"); this.changes = Objects.requireNonNull(changes, "changes");
    }

    @Override public Result<GearInventory> load() { return delegate.load(); }
    @Override public Result<GearInventory> save(GearInventory inventory) { return announced(delegate.save(inventory)); }
    @Override public <T> Result<T> transact(Function<GearInventory, Result<T>> change) { return announced(delegate.transact(change)); }

    private <T> Result<T> announced(Result<T> result) {
        if (result.isSuccess()) changes.announce(StoreChanges.Change.inventory());
        return result;
    }
}
