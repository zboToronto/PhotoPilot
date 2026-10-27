package com.photopilot.integration;

import java.time.LocalDate;
import com.photopilot.common.Result;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 80 (zero-based): {weather and sunlight integration port}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface EnvironmentProvider {

    public Result<EnvironmentContext> fetch(GeoLocation place, LocalDate date);
}
