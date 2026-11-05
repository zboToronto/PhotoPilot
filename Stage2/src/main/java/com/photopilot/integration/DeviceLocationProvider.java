package com.photopilot.integration;


import com.photopilot.common.Result;
import com.photopilot.model.GeoLocation;

/**
 * Stage 1 UML element 87 (zero-based): {optional platform integration; implementation deferred}
 * No implementation exists: LocationService receives none, so F09 reports device location as unsupported and
 * keeps manual entry and place search.
 */
public interface DeviceLocationProvider {

    public Result<GeoLocation> current();
}
