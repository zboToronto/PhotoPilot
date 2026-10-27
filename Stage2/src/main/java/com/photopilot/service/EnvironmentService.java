package com.photopilot.service;

import java.time.LocalDate;
import java.util.Objects;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.common.Result;
import com.photopilot.integration.EnvironmentProvider;
import com.photopilot.model.EnvironmentAssessment;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.GeoLocation;
import com.photopilot.model.ShootRequirement;
import com.photopilot.validation.FeasibilityValidator;

/**
 * Stage 1 UML element 79 (zero-based): {F09; factual retrieval independent of AI interpretation}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EnvironmentService {
    private EnvironmentProvider provider;
    private LocationService location;
    private PhotoPilotAgent agent;
    private FeasibilityValidator validator;

    public EnvironmentService(EnvironmentProvider provider, LocationService location, PhotoPilotAgent agent, FeasibilityValidator validator) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.location = Objects.requireNonNull(location, "location");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public Result<EnvironmentContext> retrieve(GeoLocation place, LocalDate date) {
        return Result.failureKey("error.notImplemented", "EnvironmentService.retrieve");
    }

    public Result<EnvironmentAssessment> assess(ShootRequirement req) {
        return Result.failureKey("error.notImplemented", "EnvironmentService.assess");
    }
}
