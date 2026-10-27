package com.photopilot.model;

import java.util.Map;
import java.util.Objects;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 91 (zero-based): {validated schema per operation; no free-form expressions}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CalculationInput {
    private CalculationKind operation;
    private Map<String, Double> parameters;
    private Map<String, String> units;

    public CalculationInput(CalculationKind operation, Map<String, Double> parameters, Map<String, String> units) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.parameters = Map.copyOf(parameters);
        this.units = Map.copyOf(units);
    }

    public Result<CalculationInput> validate() {
        return Result.failure("NOT IMPLEMENTED: CalculationInput.validate");
    }
}
