package com.photopilot.integration;

import java.time.LocalDate;
import com.photopilot.common.Result;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 80 (zero-based): {weather and sunlight integration port}
 * Factual retrieval only: implementations return provider data with sources and timestamps, mark everything
 * missing as unavailable and never substitute values.
 */
public interface EnvironmentProvider {

    public Result<EnvironmentContext> fetch(GeoLocation place, LocalDate date);

    /** Shooting date plus, for availability past midnight, the following day ({@code to} at most one day later). */
    public default Result<EnvironmentContext> fetch(GeoLocation place, LocalDate from, LocalDate to) {
        return from.equals(to) ? fetch(place, from) : Result.failureKey("env.error.range");
    }
}
