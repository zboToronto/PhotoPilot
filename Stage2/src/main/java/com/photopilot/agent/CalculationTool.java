package com.photopilot.agent;

import java.util.Objects;
import com.photopilot.common.Result;
import com.photopilot.service.TechnicalCalculator;

/**
 * Stage 1 UML element 94 (zero-based): {AgentTool; deterministic F08}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CalculationTool implements AgentTool {
    private TechnicalCalculator calculator;

    public CalculationTool(TechnicalCalculator calculator) {
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    public String name() {
        return "calculation";
    }

    public String inputSchema() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: CalculationTool.inputSchema");
    }

    public Result<String> execute(String argumentsJson) {
        return Result.failure("NOT IMPLEMENTED: CalculationTool.execute");
    }
}
