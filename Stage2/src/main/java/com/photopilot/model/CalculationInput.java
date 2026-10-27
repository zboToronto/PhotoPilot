package com.photopilot.model;

import java.util.Map;
import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.service.CalculationValidation;

/** Stage 1 UML element 91: operation-specific numeric schema; no expressions. */
public class CalculationInput {
    private final CalculationKind operation;
    private final Map<String, Double> parameters;
    private final Map<String, String> units;

    public CalculationInput(CalculationKind operation, Map<String, Double> parameters, Map<String, String> units) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.parameters = Map.copyOf(parameters);
        this.units = Map.copyOf(units);
    }
    public CalculationKind operation() { return operation; }
    public Map<String, Double> parameters() { return parameters; }
    public Map<String, String> units() { return units; }
    public Result<CalculationInput> validate() { return CalculationValidation.validate(this); }
}
