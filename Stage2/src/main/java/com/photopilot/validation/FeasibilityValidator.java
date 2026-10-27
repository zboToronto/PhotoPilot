package com.photopilot.validation;


import com.photopilot.common.Result;
import com.photopilot.model.ActivitySuggestion;
import com.photopilot.model.DiscoveryContext;
import com.photopilot.model.EnvironmentAssessment;

/**
 * Stage 1 UML element 65 (zero-based): {F02/F09; time, distance, gear and consent gates}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class FeasibilityValidator {

    public FeasibilityValidator() {
    }

    public Result<ActivitySuggestion> validate(ActivitySuggestion idea, DiscoveryContext context) {
        return Result.failure("NOT IMPLEMENTED: FeasibilityValidator.validate");
    }

    public Result<EnvironmentAssessment> checkTiming(EnvironmentAssessment assessment) {
        return Result.failure("NOT IMPLEMENTED: FeasibilityValidator.checkTiming");
    }
}
