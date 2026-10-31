package com.photopilot.model;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 UML element 86 (zero-based): {F02; conversion produces unconfirmed draft}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ActivitySuggestion {
    private String subject;
    private String setting;
    private Duration estimatedDuration;
    private Optional<Double> estimatedTravelKm;
    private String rationale;
    private List<String> limitations;

    public ActivitySuggestion(String subject, String setting, Duration estimatedDuration, Optional<Double> estimatedTravelKm, String rationale, List<String> limitations) {
        this.subject = Objects.requireNonNull(subject, "subject");
        this.setting = Objects.requireNonNull(setting, "setting");
        this.estimatedDuration = Objects.requireNonNull(estimatedDuration, "estimatedDuration");
        this.estimatedTravelKm = Objects.requireNonNull(estimatedTravelKm, "estimatedTravelKm");
        this.rationale = Objects.requireNonNull(rationale, "rationale");
        this.limitations = List.copyOf(limitations);
        if (estimatedDuration.isNegative()) throw new IllegalArgumentException("Negative activity duration.");
        estimatedTravelKm.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid travel estimate.");
        });
    }

    public ShootRequirement toRequirement() {
        throw new UnsupportedOperationException("NOT IMPLEMENTED: ActivitySuggestion.toRequirement");
    }
}
