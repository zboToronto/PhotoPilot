package com.photopilot.model;

import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 40 (zero-based): {F06; e.g. aperture, AF, burst, stabilization}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CapabilityNeed {
    private String capability;
    private Optional<Double> minimum;
    private Optional<Double> maximum;
    private String unit;
    private int priority;
    private boolean hardConstraint;
    private String rationale;

    public CapabilityNeed(String capability, Optional<Double> minimum, Optional<Double> maximum, String unit, int priority, boolean hardConstraint, String rationale) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.maximum = Objects.requireNonNull(maximum, "maximum");
        this.unit = Objects.requireNonNull(unit, "unit");
        this.priority = priority;
        this.hardConstraint = hardConstraint;
        this.rationale = Objects.requireNonNull(rationale, "rationale");
    }

    public Result<CapabilityNeed> validate() {
        return Result.failure("NOT IMPLEMENTED: CapabilityNeed.validate");
    }
}
