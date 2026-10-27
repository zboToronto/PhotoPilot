package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.integration.EquipmentSpecificationProvider;

/**
 * Stage 1 UML element 97 (zero-based): {AgentTool; factual equipment lookup}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
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
        throw new UnsupportedOperationException("NOT IMPLEMENTED: SpecificationTool.inputSchema");
    }

    public Result<String> execute(String argumentsJson) {
        return Result.failureKey("error.notImplemented", "SpecificationTool.execute");
    }
}
