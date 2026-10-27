package com.photopilot.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 81 (zero-based): {Adapter; providers not yet selected}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EnvironmentApiAdapter implements EnvironmentProvider {
    private URI weatherEndpoint;
    private URI sunEndpoint;
    private HttpClient http;

    public EnvironmentApiAdapter(URI weatherEndpoint, URI sunEndpoint, HttpClient http) {
        this.weatherEndpoint = Objects.requireNonNull(weatherEndpoint, "weatherEndpoint");
        this.sunEndpoint = Objects.requireNonNull(sunEndpoint, "sunEndpoint");
        this.http = Objects.requireNonNull(http, "http");
    }

    public Result<EnvironmentContext> fetch(GeoLocation place, LocalDate date) {
        return Result.failure("NOT IMPLEMENTED: EnvironmentApiAdapter.fetch");
    }
}
