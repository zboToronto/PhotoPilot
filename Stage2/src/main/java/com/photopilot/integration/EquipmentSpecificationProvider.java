package com.photopilot.integration;

import java.util.List;
import com.photopilot.common.Result;
import com.photopilot.model.Equipment;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.SpecificationEvidence;

/**
 * Stage 1 UML element 43 (zero-based): {integration port; sourced or unverified fields}
 * {@link #search} (F04) returns external evidence records with their sources: an empty list means no catalogued
 * product matched; a failure means no source could be consulted. Warnings name sources that were unavailable.
 */
public interface EquipmentSpecificationProvider {

    public Result<List<Equipment>> lookup(String model);

    default Result<List<SpecificationEvidence>> search(EquipmentType type, String brand, String model, String modelCode) {
        return Result.failureKey("error.notImplemented", "EquipmentSpecificationProvider.search");
    }
}
