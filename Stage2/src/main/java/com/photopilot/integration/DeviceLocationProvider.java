package com.photopilot.integration;


import com.photopilot.common.Result;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 87 (zero-based): {optional platform integration; implementation deferred}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public interface DeviceLocationProvider {

    public Result<GeoLocation> current();
}
