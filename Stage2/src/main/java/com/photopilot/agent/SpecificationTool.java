package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.integration.EquipmentSpecificationProvider;

/**
 * Stage 1 UML element 97 (zero-based): {AgentTool; factual equipment lookup}
 * Retained for Stage 1 traceability and deliberately not registered: F04 queries EquipmentCatalogAdapter directly for
 * the identity the user reviewed, while a model-callable lookup would send model-chosen names to external catalogues.
 * It publishes no schema, so ToolRegistry refuses to register it.
 */
public class SpecificationTool implements AgentTool {
    private EquipmentSpecificationProvider provider;

    public SpecificationTool(EquipmentSpecificationProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public String name() {
        return "specification";
    }

    public String inputSchema() {
        throw new UnsupportedOperationException("SpecificationTool is not offered to the model; see the class documentation");
    }

    public Result<String> execute(String argumentsJson) {
        return Result.failureKey("error.notImplemented", "SpecificationTool.execute");
    }
}
