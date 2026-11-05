package com.photopilot.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 104 (zero-based): {objective metadata only; per-file errors in Result}
 * One photograph's recorded technical metadata as read by the metadata adapter. Absent values stay empty; a value that
 * was present but unreadable or physically impossible is listed in {@link #invalidFields()} and never used. Nothing is
 * inferred: no lens from a file name, no full-frame equivalent, no exposure derived from other tags. Transient
 * extraction candidate only; sessions persist {@link ExifStatistics}, never records.
 */
public class ExifRecord {
    /** Statistic fields in display order; also the keys of ExifStatistics missing/invalid counts. */
    public static final List<String> FIELDS = List.of("camera", "lens", "focalLength", "shutter", "aperture", "iso", "exposureCompensation", "captureTime");
    /** Fields of which at least one must be valid for the record to count as usable metadata. */
    public static final List<String> MEASURED = List.of("camera", "lens", "focalLength", "shutter", "aperture", "iso");
    /** Deterministic plausibility bounds; values outside them are rejected, not clamped. */
    public static final double MAX_FOCAL_MM = 5000, MAX_SHUTTER_SECONDS = 86_400, MIN_APERTURE = 0.5, MAX_APERTURE = 256, MAX_COMPENSATION_EV = 15;
    public static final int MAX_ISO = 10_000_000, MAX_NAME = 120;

    private String sourceName;
    private Optional<Double> focalLengthMm;
    private Optional<Double> shutterSeconds;
    private Optional<Double> aperture;
    private Optional<Integer> iso;
    private Optional<String> cameraModel;
    private Optional<String> lensModel;
    private Optional<Instant> capturedAt;
    private Set<String> missingFields;
    private Optional<Double> exposureCompensation;
    /** Camera-clock capture time as recorded; {@link #capturedAt} is present only when the offset was recorded too. */
    private Optional<LocalDateTime> localCaptureTime;
    private Optional<ZoneOffset> captureOffset;
    private Set<String> invalidFields;

    public ExifRecord(String sourceName, Optional<Double> focalLengthMm, Optional<Double> shutterSeconds, Optional<Double> aperture, Optional<Integer> iso, Optional<String> cameraModel, Optional<String> lensModel, Optional<Instant> capturedAt, Set<String> missingFields) {
        this(sourceName, focalLengthMm, shutterSeconds, aperture, iso, cameraModel, lensModel, Optional.empty(),
            Optional.empty(), Optional.empty(), Set.of());
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.missingFields = Set.copyOf(missingFields);
    }

    /** F11 full record: raw values from the adapter plus the fields it found present but unreadable. */
    public ExifRecord(String sourceName, Optional<Double> focalLengthMm, Optional<Double> shutterSeconds, Optional<Double> aperture,
            Optional<Integer> iso, Optional<String> cameraModel, Optional<String> lensModel, Optional<Double> exposureCompensation,
            Optional<LocalDateTime> localCaptureTime, Optional<ZoneOffset> captureOffset, Set<String> invalidFields) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.focalLengthMm = Objects.requireNonNull(focalLengthMm, "focalLengthMm");
        this.shutterSeconds = Objects.requireNonNull(shutterSeconds, "shutterSeconds");
        this.aperture = Objects.requireNonNull(aperture, "aperture");
        this.iso = Objects.requireNonNull(iso, "iso");
        this.cameraModel = Objects.requireNonNull(cameraModel, "cameraModel");
        this.lensModel = Objects.requireNonNull(lensModel, "lensModel");
        this.exposureCompensation = Objects.requireNonNull(exposureCompensation, "exposureCompensation");
        this.localCaptureTime = Objects.requireNonNull(localCaptureTime, "localCaptureTime");
        this.captureOffset = Objects.requireNonNull(captureOffset, "captureOffset");
        this.capturedAt = localCaptureTime.flatMap(time -> captureOffset.map(offset -> time.toInstant(offset)));
        this.invalidFields = Set.copyOf(invalidFields);
        this.missingFields = missing(this);
    }

    /**
     * Range-checked, normalized copy (focal length and f-number to 0.01, exposure compensation to 0.01 EV, other
     * measurements to 10 significant digits so equal rationals from different sources agree) plus "usable" check:
     * at least one camera, lens or exposure field must remain. Failure means the photograph has no usable metadata.
     */
    public Result<ExifRecord> validate() {
        var normalized = normalized();
        if (MEASURED.stream().allMatch(normalized.missingFields::contains)) return Result.failureKey("review.record.noMetadata", sourceName);
        return Result.success(normalized);
    }

    /** The normalized copy whether or not it is usable; invalid values are moved to {@link #invalidFields()}. */
    public ExifRecord normalized() {
        var invalid = new LinkedHashSet<>(invalidFields);
        var focal = measure(focalLengthMm, v -> v > 0 && v <= MAX_FOCAL_MM, "focalLength", invalid).map(v -> round(v, 2));
        var shutter = measure(shutterSeconds, v -> v > 0 && v <= MAX_SHUTTER_SECONDS, "shutter", invalid).map(ExifRecord::significant);
        var fNumber = measure(aperture, v -> v >= MIN_APERTURE && v <= MAX_APERTURE, "aperture", invalid).map(v -> round(v, 2));
        var compensation = measure(exposureCompensation, v -> Math.abs(v) <= MAX_COMPENSATION_EV, "exposureCompensation", invalid).map(v -> round(v, 2) + 0.0);
        var sensitivity = iso.filter(v -> {
            boolean ok = v >= 1 && v <= MAX_ISO;
            if (!ok) invalid.add("iso");
            return ok;
        });
        var camera = name(cameraModel, "camera", invalid);
        var lens = name(lensModel, "lens", invalid);
        return new ExifRecord(sourceName, focal, shutter, fNumber, sensitivity, camera, lens, compensation, localCaptureTime, captureOffset, invalid);
    }

    /**
     * Equipment identity from separate make and model tags: the model alone when it already starts with the make or
     * the make's first word (e.g. "Canon" + "Canon EOS R5", "NIKON CORPORATION" + "NIKON Z 9"), otherwise
     * "make model". A make without a model is no identity.
     */
    public static Optional<String> equipmentName(String make, String model) {
        String m = clean(model), brand = clean(make);
        if (m.isEmpty()) return Optional.empty();
        String lower = m.toLowerCase(java.util.Locale.ROOT), first = brand.split(" ")[0].toLowerCase(java.util.Locale.ROOT);
        if (brand.isEmpty() || lower.startsWith(brand.toLowerCase(java.util.Locale.ROOT)) || lower.startsWith(first + " ") || lower.equals(first)) return Optional.of(m);
        return Optional.of(brand + " " + m);
    }
    /** Trimmed text without control characters and with single spaces. */
    public static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
    }

    private static Optional<Double> measure(Optional<Double> value, java.util.function.DoublePredicate valid, String field, Set<String> invalid) {
        if (value.isEmpty()) return value;
        double v = value.get();
        if (Double.isFinite(v) && valid.test(v)) return value;
        invalid.add(field);
        return Optional.empty();
    }
    /** A name needs a letter or digit (placeholders such as "----" are recorded by some bodies for unknown lenses). */
    private static Optional<String> name(Optional<String> value, String field, Set<String> invalid) {
        if (value.isEmpty()) return value;
        String text = clean(value.get());
        if (text.isEmpty() || text.codePoints().noneMatch(Character::isLetterOrDigit)) return Optional.empty();
        if (text.length() > MAX_NAME) { invalid.add(field); return Optional.empty(); }
        return Optional.of(text);
    }
    private static double round(double value, int decimals) { return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue(); }
    private static double significant(double value) { return new BigDecimal(value).round(new MathContext(10, RoundingMode.HALF_UP)).doubleValue(); }
    private static Set<String> missing(ExifRecord r) {
        var missing = new LinkedHashSet<String>();
        if (r.cameraModel.isEmpty()) missing.add("camera");
        if (r.lensModel.isEmpty()) missing.add("lens");
        if (r.focalLengthMm.isEmpty()) missing.add("focalLength");
        if (r.shutterSeconds.isEmpty()) missing.add("shutter");
        if (r.aperture.isEmpty()) missing.add("aperture");
        if (r.iso.isEmpty()) missing.add("iso");
        if (r.exposureCompensation.isEmpty()) missing.add("exposureCompensation");
        if (r.localCaptureTime.isEmpty()) missing.add("captureTime");
        return Set.copyOf(missing);
    }

    public String sourceName() { return sourceName; }
    public Optional<Double> focalLengthMm() { return focalLengthMm; }
    public Optional<Double> shutterSeconds() { return shutterSeconds; }
    public Optional<Double> aperture() { return aperture; }
    public Optional<Integer> iso() { return iso; }
    public Optional<String> cameraModel() { return cameraModel; }
    public Optional<String> lensModel() { return lensModel; }
    public Optional<Instant> capturedAt() { return capturedAt; }
    public Optional<Double> exposureCompensation() { return exposureCompensation; }
    public Optional<LocalDateTime> localCaptureTime() { return localCaptureTime; }
    public Optional<ZoneOffset> captureOffset() { return captureOffset; }
    public Set<String> missingFields() { return missingFields; }
    public Set<String> invalidFields() { return invalidFields; }
}
