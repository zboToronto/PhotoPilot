package com.photopilot.model;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 36 (zero-based): {F03; confirmed before capability analysis}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ShootRequirement {
    private String originalText;
    private String subject;
    private List<String> goals;
    private Optional<ZonedDateTime> time;
    private Optional<GeoLocation> location;
    private Duration duration;
    private int cameraCount;
    private boolean lensChangesAllowed;
    private Optional<Double> maxWeightGrams;
    private List<String> gearConstraints;
    private Map<String, Integer> priorities;
    private Map<String, String> fixedConstraints;
    private List<String> clarificationQuestions;
    private boolean confirmed;

    public ShootRequirement(String originalText, String subject, List<String> goals, Optional<ZonedDateTime> time, Optional<GeoLocation> location, Duration duration, int cameraCount, boolean lensChangesAllowed, Optional<Double> maxWeightGrams, List<String> gearConstraints, Map<String, Integer> priorities, Map<String, String> fixedConstraints, List<String> clarificationQuestions, boolean confirmed) {
        this.originalText = Objects.requireNonNull(originalText, "originalText");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.goals = List.copyOf(goals);
        this.time = Objects.requireNonNull(time, "time");
        this.location = Objects.requireNonNull(location, "location");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.cameraCount = cameraCount;
        this.lensChangesAllowed = lensChangesAllowed;
        this.maxWeightGrams = Objects.requireNonNull(maxWeightGrams, "maxWeightGrams");
        this.gearConstraints = List.copyOf(gearConstraints);
        this.priorities = Map.copyOf(priorities);
        this.fixedConstraints = Map.copyOf(fixedConstraints);
        this.clarificationQuestions = List.copyOf(clarificationQuestions);
        this.confirmed = confirmed;
    }

    public Result<ShootRequirement> validate() {
        return Result.failure("NOT IMPLEMENTED: ShootRequirement.validate");
    }

    public Result<ShootRequirement> confirm() {
        return Result.failure("NOT IMPLEMENTED: ShootRequirement.confirm");
    }
}
