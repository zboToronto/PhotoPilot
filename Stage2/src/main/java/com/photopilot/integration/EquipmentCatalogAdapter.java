package com.photopilot.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.model.Equipment;

/**
 * Stage 1 UML element 98 (zero-based): {Adapter; sources, conflicts and unknowns retained}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EquipmentCatalogAdapter implements EquipmentSpecificationProvider {
    private URI endpoint;
    private HttpClient http;

    public EquipmentCatalogAdapter(URI endpoint, HttpClient http) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.http = Objects.requireNonNull(http, "http");
    }

    public Result<List<Equipment>> lookup(String model) {
        return Result.failureKey("error.notImplemented", "EquipmentCatalogAdapter.lookup");
    }
}
