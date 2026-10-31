package com.photopilot.model;

import java.util.List;
import java.util.Map;

/**
 * Stage 1 UML element 105 (zero-based): {F11; denominators exclude missing measurements}
 * SCAFFOLDED: business behavior is NOT IMPLEMENTED.
 */
public class ExifStatistics {
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

    public ExifStatistics(int importedCount, int usableCount, Map<String, Integer> missingCounts, Map<Double, Integer> focalLengthCounts, Map<Double, Integer> shutterCounts, Map<Double, Integer> apertureCounts, Map<Integer, Integer> isoCounts, Map<String, Integer> cameraCounts, Map<String, Integer> lensCounts, List<String> limitations) {
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
    }

    private static void checkCounts(Map<?, Integer> counts, int maximum) {
        if (counts.values().stream().anyMatch(count -> count < 0 || count > maximum))
            throw new IllegalArgumentException("Invalid EXIF histogram count.");
    }
}
