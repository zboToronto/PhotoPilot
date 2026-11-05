package com.photopilot.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import com.photopilot.common.Result;

/**
 * Stage 1 UML element 105 (zero-based): {F11; denominators exclude missing measurements}
 * Deterministic summary of the records of one import. Histograms count usable photographs per recorded value; the
 * denominator of every field is its own count ({@link #count(String)}), so a photograph without a lens model still
 * counts for focal length or ISO. Missing counts include values that were present but invalid, which are also counted
 * separately. Descriptive statistics are derived exactly from the histograms. No quality or success score exists.
 * Fields after {@code limitations} are additive F11 schema fields (empty for statistics saved before them).
 */
public class ExifStatistics {
    /** Numeric statistic fields in display order. */
    public static final List<String> NUMERIC = List.of("focalLength", "shutter", "aperture", "iso", "exposureCompensation");

    private int importedCount;
    private int usableCount;
    private Map<String, Integer> missingCounts;
    private Map<Double, Integer> focalLengthCounts;
    private Map<Double, Integer> shutterCounts;
    private Map<Double, Integer> apertureCounts;
    private Map<Integer, Integer> isoCounts;
    private Map<String, Integer> cameraCounts;
    private Map<String, Integer> lensCounts;
    private List<String> limitations;
    private Map<String, Integer> invalidCounts;
    private Map<Double, Integer> compensationCounts;
    private int failedFileCount;
    private int timedCount;
    private int zonedCount;
    /** Earliest/latest camera-clock capture time (ISO local date-time) of the usable photographs with a timestamp. */
    private Optional<String> firstCapture;
    private Optional<String> lastCapture;
    /** The single recorded UTC offset shared by every timestamped photograph, when all of them recorded the same one. */
    private Optional<String> captureOffset;
    private Optional<Instant> analyzedAt;
    /** SHA-256 over the sorted content hashes of the analysed files; identifies a repeated import of the same files. */
    private String sourceFingerprint;

    public ExifStatistics(int importedCount, int usableCount, Map<String, Integer> missingCounts, Map<Double, Integer> focalLengthCounts, Map<Double, Integer> shutterCounts, Map<Double, Integer> apertureCounts, Map<Integer, Integer> isoCounts, Map<String, Integer> cameraCounts, Map<String, Integer> lensCounts, List<String> limitations) {
        this(importedCount, usableCount, missingCounts, focalLengthCounts, shutterCounts, apertureCounts, isoCounts, cameraCounts, lensCounts, limitations,
            Map.of(), Map.of(), 0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "");
    }

    public ExifStatistics(int importedCount, int usableCount, Map<String, Integer> missingCounts, Map<Double, Integer> focalLengthCounts,
            Map<Double, Integer> shutterCounts, Map<Double, Integer> apertureCounts, Map<Integer, Integer> isoCounts, Map<String, Integer> cameraCounts,
            Map<String, Integer> lensCounts, List<String> limitations, Map<String, Integer> invalidCounts, Map<Double, Integer> compensationCounts,
            int failedFileCount, int timedCount, int zonedCount, Optional<String> firstCapture, Optional<String> lastCapture,
            Optional<String> captureOffset, Optional<Instant> analyzedAt, String sourceFingerprint) {
        this.importedCount = importedCount;
        this.usableCount = usableCount;
        this.missingCounts = Map.copyOf(missingCounts);
        this.focalLengthCounts = Map.copyOf(focalLengthCounts);
        this.shutterCounts = Map.copyOf(shutterCounts);
        this.apertureCounts = Map.copyOf(apertureCounts);
        this.isoCounts = Map.copyOf(isoCounts);
        this.cameraCounts = Map.copyOf(cameraCounts);
        this.lensCounts = Map.copyOf(lensCounts);
        this.limitations = List.copyOf(limitations);
        this.invalidCounts = Map.copyOf(invalidCounts);
        this.compensationCounts = Map.copyOf(compensationCounts);
        this.failedFileCount = failedFileCount;
        this.timedCount = timedCount;
        this.zonedCount = zonedCount;
        this.firstCapture = Objects.requireNonNull(firstCapture, "firstCapture");
        this.lastCapture = Objects.requireNonNull(lastCapture, "lastCapture");
        this.captureOffset = Objects.requireNonNull(captureOffset, "captureOffset");
        this.analyzedAt = Objects.requireNonNull(analyzedAt, "analyzedAt");
        this.sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
        if (importedCount < 0 || usableCount < 0 || usableCount > importedCount)
            throw new IllegalArgumentException("Invalid EXIF sample counts.");
        checkCounts(this.missingCounts, importedCount);
        for (var histogram : List.of(this.focalLengthCounts, this.shutterCounts, this.apertureCounts,
                this.isoCounts, this.cameraCounts, this.lensCounts)) {
            checkCounts(histogram, usableCount);
            if (histogram.values().stream().mapToLong(Integer::longValue).sum() > usableCount)
                throw new IllegalArgumentException("EXIF histogram exceeds usable sample count.");
            for (Object key : histogram.keySet()) {
                if (key instanceof Number n && (!Double.isFinite(n.doubleValue()) || n.doubleValue() <= 0))
                    throw new IllegalArgumentException("Invalid EXIF measurement.");
            }
        }
        checkCounts(this.invalidCounts, importedCount);
        checkCounts(this.compensationCounts, usableCount);
        if (this.compensationCounts.values().stream().mapToLong(Integer::longValue).sum() > usableCount
                || this.compensationCounts.keySet().stream().anyMatch(k -> !Double.isFinite(k) || Math.abs(k) > ExifRecord.MAX_COMPENSATION_EV))
            throw new IllegalArgumentException("Invalid exposure compensation evidence.");
        if (failedFileCount < 0 || timedCount < 0 || timedCount > usableCount || zonedCount < 0 || zonedCount > timedCount)
            throw new IllegalArgumentException("Invalid EXIF capture counts.");
        firstCapture.ifPresent(LocalDateTime::parse); lastCapture.ifPresent(LocalDateTime::parse); captureOffset.ifPresent(ZoneOffset::of);
        if (firstCapture.isPresent() != lastCapture.isPresent() || firstCapture.isPresent() && timedCount == 0
                || firstCapture.isPresent() && LocalDateTime.parse(firstCapture.get()).isAfter(LocalDateTime.parse(lastCapture.get())))
            throw new IllegalArgumentException("Invalid EXIF capture span.");
    }

    private static void checkCounts(Map<?, Integer> counts, int maximum) {
        if (counts.values().stream().anyMatch(count -> count < 0 || count > maximum))
            throw new IllegalArgumentException("Invalid EXIF histogram count.");
    }

    /**
     * Strict F11 consistency before persistence: at least one usable photograph, and for every field the photographs
     * with a value plus those missing it equal the imported count, invalid values being a subset of the missing ones.
     */
    public Result<ExifStatistics> validate() {
        if (usableCount < 1) return Result.failureKey("review.error.noUsable");
        for (String field : ExifRecord.FIELDS) {
            int missing = missing(field), invalid = invalidCounts.getOrDefault(field, 0);
            if (count(field) + missing != importedCount || invalid > missing) return Result.failureKey("review.error.inconsistent", field);
        }
        if (cameraCounts.keySet().stream().anyMatch(String::isBlank) || lensCounts.keySet().stream().anyMatch(String::isBlank))
            return Result.failureKey("review.error.inconsistent", "equipment");
        return Result.success(this);
    }

    /** Copy carrying the import context, which the deterministic calculator itself does not know. */
    public ExifStatistics withSource(int failedFiles, Instant analyzed, String fingerprint) {
        return new ExifStatistics(importedCount, usableCount, missingCounts, focalLengthCounts, shutterCounts, apertureCounts, isoCounts,
            cameraCounts, lensCounts, limitations, invalidCounts, compensationCounts, failedFiles, timedCount, zonedCount, firstCapture,
            lastCapture, captureOffset, Optional.of(analyzed), fingerprint);
    }

    /** Same evidence (everything except when it was calculated); used to detect repeats and stale review drafts. */
    public boolean sameEvidence(ExifStatistics other) {
        return other != null && importedCount == other.importedCount && usableCount == other.usableCount
            && missingCounts.equals(other.missingCounts) && focalLengthCounts.equals(other.focalLengthCounts)
            && shutterCounts.equals(other.shutterCounts) && apertureCounts.equals(other.apertureCounts) && isoCounts.equals(other.isoCounts)
            && cameraCounts.equals(other.cameraCounts) && lensCounts.equals(other.lensCounts) && invalidCounts.equals(other.invalidCounts)
            && compensationCounts.equals(other.compensationCounts) && failedFileCount == other.failedFileCount && timedCount == other.timedCount
            && zonedCount == other.zonedCount && firstCapture.equals(other.firstCapture) && lastCapture.equals(other.lastCapture)
            && captureOffset.equals(other.captureOffset) && sourceFingerprint.equals(other.sourceFingerprint);
    }

    /** Photographs with a usable value for the field: the denominator of its statistics. */
    public int count(String field) {
        return switch (field) {
            case "camera" -> sum(cameraCounts);
            case "lens" -> sum(lensCounts);
            case "captureTime" -> timedCount;
            default -> sum(histogram(field));
        };
    }
    public int missing(String field) { return missingCounts.getOrDefault(field, 0); }
    public int invalid(String field) { return invalidCounts.getOrDefault(field, 0); }

    /** Recorded values of a numeric field with their photograph counts, in ascending value order. */
    public TreeMap<Double, Integer> histogram(String field) {
        var values = new TreeMap<Double, Integer>();
        switch (field) {
            case "focalLength" -> values.putAll(focalLengthCounts);
            case "shutter" -> values.putAll(shutterCounts);
            case "aperture" -> values.putAll(apertureCounts);
            case "iso" -> isoCounts.forEach((k, v) -> values.put(k.doubleValue(), v));
            case "exposureCompensation" -> values.putAll(compensationCounts);
            default -> throw new IllegalArgumentException("Not a numeric EXIF field: " + field);
        }
        return values;
    }

    /** Exact descriptive statistics of one numeric field (empty when no photograph recorded it). */
    public record Summary(int count, double min, double max, double mean, double median) { }
    public Optional<Summary> summary(String field) {
        var values = histogram(field);
        int n = sum(values);
        if (n == 0) return Optional.empty();
        double total = 0;
        for (var entry : values.entrySet()) total += entry.getKey() * entry.getValue();
        return Optional.of(new Summary(n, values.firstKey(), values.lastKey(), total / n, median(values, n)));
    }
    private static double median(TreeMap<Double, Integer> values, int n) {
        var ordered = new ArrayList<Double>();
        long lower = (n - 1) / 2, upper = n / 2, seen = 0;
        for (var entry : values.entrySet()) {
            long next = seen + entry.getValue();
            if (lower >= seen && lower < next) ordered.add(entry.getKey());
            if (upper >= seen && upper < next) ordered.add(entry.getKey());
            seen = next;
        }
        return (ordered.getFirst() + ordered.getLast()) / 2;
    }
    private static int sum(Map<?, Integer> counts) { return (int) counts.values().stream().mapToLong(Integer::longValue).sum(); }

    public int importedCount() { return importedCount; }
    public int usableCount() { return usableCount; }
    public Map<String, Integer> missingCounts() { return missingCounts; }
    public Map<Double, Integer> focalLengthCounts() { return focalLengthCounts; }
    public Map<Double, Integer> shutterCounts() { return shutterCounts; }
    public Map<Double, Integer> apertureCounts() { return apertureCounts; }
    public Map<Integer, Integer> isoCounts() { return isoCounts; }
    public Map<String, Integer> cameraCounts() { return cameraCounts; }
    public Map<String, Integer> lensCounts() { return lensCounts; }
    public List<String> limitations() { return limitations; }
    public Map<String, Integer> invalidCounts() { return invalidCounts; }
    public Map<Double, Integer> compensationCounts() { return compensationCounts; }
    public int failedFileCount() { return failedFileCount; }
    public int timedCount() { return timedCount; }
    public int zonedCount() { return zonedCount; }
    public Optional<String> firstCapture() { return firstCapture; }
    public Optional<String> lastCapture() { return lastCapture; }
    public Optional<String> captureOffset() { return captureOffset; }
    public Optional<Instant> analyzedAt() { return analyzedAt; }
    public String sourceFingerprint() { return sourceFingerprint; }
}
