package com.photopilot.service;

import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;
import com.photopilot.integration.DeviceLocationProvider;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 82 (zero-based): {F01/F02/F09; no call without consent}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class LocationService {
    private Optional<DeviceLocationProvider> provider;

    public LocationService(Optional<DeviceLocationProvider> provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public Result<GeoLocation> manual(String label, double lat, double lon) {
        return Result.failure("NOT IMPLEMENTED: LocationService.manual");
    }

    public Result<GeoLocation> current(boolean explicitConsent) {
        return Result.failure("NOT IMPLEMENTED: LocationService.current");
    }
}
