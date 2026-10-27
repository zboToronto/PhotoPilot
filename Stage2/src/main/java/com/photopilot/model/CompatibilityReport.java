package com.photopilot.model;

import java.util.List;

/**
 * Stage 1 UML element 59 (zero-based): {unknown is not compatible; partial needs explicit}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class CompatibilityReport {
    private boolean compatible;
    private List<String> unverified;
    private List<String> violations;
    private List<String> unmetNeeds;

    public CompatibilityReport(boolean compatible, List<String> unverified, List<String> violations, List<String> unmetNeeds) {
        this.compatible = compatible;
        this.unverified = List.copyOf(unverified);
        this.violations = List.copyOf(violations);
        this.unmetNeeds = List.copyOf(unmetNeeds);
    }

    public boolean isUsable() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: CompatibilityReport.isUsable");
    }
}
