package com.photopilot.integration;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.Equipment;

/**
 * Stage 1 UML element 43 (zero-based): {integration port; sourced or unverified fields}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface EquipmentSpecificationProvider {

    public Result<List<Equipment>> lookup(String model);
}
