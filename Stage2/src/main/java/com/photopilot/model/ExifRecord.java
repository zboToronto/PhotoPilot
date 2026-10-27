package com.photopilot.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 104 (zero-based): {objective metadata only; per-file errors in Result}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ExifRecord {
    private String sourceName;
    private Optional<Double> focalLengthMm;
    private Optional<Double> shutterSeconds;
    private Optional<Double> aperture;
    private Optional<Integer> iso;
    private Optional<String> cameraModel;
    private Optional<String> lensModel;
    private Optional<Instant> capturedAt;
    private Set<String> missingFields;

    public ExifRecord(String sourceName, Optional<Double> focalLengthMm, Optional<Double> shutterSeconds, Optional<Double> aperture, Optional<Integer> iso, Optional<String> cameraModel, Optional<String> lensModel, Optional<Instant> capturedAt, Set<String> missingFields) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.focalLengthMm = Objects.requireNonNull(focalLengthMm, "focalLengthMm");
        this.shutterSeconds = Objects.requireNonNull(shutterSeconds, "shutterSeconds");
        this.aperture = Objects.requireNonNull(aperture, "aperture");
        this.iso = Objects.requireNonNull(iso, "iso");
        this.cameraModel = Objects.requireNonNull(cameraModel, "cameraModel");
        this.lensModel = Objects.requireNonNull(lensModel, "lensModel");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.missingFields = Set.copyOf(missingFields);
    }

    public Result<ExifRecord> validate() {
        return Result.failureKey("error.notImplemented", "ExifRecord.validate");
    }
}
