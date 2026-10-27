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
}
