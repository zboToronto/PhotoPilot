package com.photopilot.model;

import java.util.List;
import java.util.Objects;

/**
 * Stage 1 UML element 76 (zero-based): {F09; guidance grounded in timestamped source data}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class EnvironmentAssessment {
    private EnvironmentContext context;
    private List<ShootingWindow> windows;
    private List<String> limitations;

    public EnvironmentAssessment(EnvironmentContext context, List<ShootingWindow> windows, List<String> limitations) {
        this.context = Objects.requireNonNull(context, "context");
        this.windows = List.copyOf(windows);
        this.limitations = List.copyOf(limitations);
    }
}
