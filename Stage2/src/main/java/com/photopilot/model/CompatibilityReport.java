package com.photopilot.model;

import java.util.List;

/**
 * Stage 1 UML element 59 (zero-based): {unknown is not compatible; partial needs explicit}
 * UC14 result. Texts are English historical evidence; presentation re-runs the validator for localized findings.
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

    /** Physically verified: no rule conflict and no unverifiable relationship. Unknown never counts as compatible. */
    public boolean isUsable() {
        return compatible && unverified.isEmpty() && violations.isEmpty();
    }

    /** Usable and every capability need (and loadout essential) is met. */
    public boolean fullySuitable() { return isUsable() && unmetNeeds.isEmpty(); }

    public CompatibilityReport withUnmetNeeds(List<String> needs) { return new CompatibilityReport(compatible, unverified, violations, needs); }
    public boolean compatible() { return compatible; }
    public List<String> unverified() { return unverified; }
    public List<String> violations() { return violations; }
    public List<String> unmetNeeds() { return unmetNeeds; }
}
