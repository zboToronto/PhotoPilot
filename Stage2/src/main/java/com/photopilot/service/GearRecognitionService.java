package com.photopilot.service;

import java.util.Objects;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.integration.EquipmentSpecificationProvider;
import com.photopilot.model.Equipment;
import com.photopilot.model.GearImportDraft;
import com.photopilot.ui.privacy.SanitizedImage;

/**
 * Stage 1 UML element 38 (zero-based): {F04; no save before confirmation}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class GearRecognitionService {
    private PhotoPilotAgent agent;
    private EquipmentSpecificationProvider specifications;

    public GearRecognitionService(PhotoPilotAgent agent, EquipmentSpecificationProvider specifications) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.specifications = Objects.requireNonNull(specifications, "specifications");
    }

    public Result<GearImportDraft> recognize(SanitizedImage image) {
        return Result.failureKey("error.notImplemented", "GearRecognitionService.recognize");
    }

    public Result<GearImportDraft> verify(GearImportDraft draft) {
        return Result.failureKey("error.notImplemented", "GearRecognitionService.verify");
    }

    public Result<Equipment> confirm(GearImportDraft edited) {
        return Result.failureKey("error.notImplemented", "GearRecognitionService.confirm");
    }
}
