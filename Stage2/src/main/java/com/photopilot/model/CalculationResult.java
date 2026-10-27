package com.photopilot.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stage 1 UML element 92 (zero-based): {F08; reproducible inputs retained}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CalculationResult {
    private Map<String, Double> values;
    private Map<String, String> units;
    private boolean estimated;
    private List<String> assumptions;
    private CalculationInput input;

    public CalculationResult(Map<String, Double> values, Map<String, String> units, boolean estimated, List<String> assumptions, CalculationInput input) {
        this.values = Map.copyOf(values);
        this.units = Map.copyOf(units);
        this.estimated = estimated;
        this.assumptions = List.copyOf(assumptions);
        this.input = Objects.requireNonNull(input, "input");
    }
}
