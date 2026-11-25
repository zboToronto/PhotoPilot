package com.photopilot.model;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * A closed period on the instant time-line [start, end]. F09 uses it for deterministic overlap arithmetic between the
 * user's availability, computed light phases and validated windows: durations are always measured between instants,
 * so time zones, daylight saving transitions and midnight crossings need no special cases. A zero-length result of
 * {@link #intersect} is boundary contact (the periods touch), not an overlap.
 */
public record TimeInterval(Instant start, Instant end) {
    public TimeInterval {
        Objects.requireNonNull(start, "start"); Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) throw new IllegalArgumentException("Interval ends before it starts.");
    }

    public Duration duration() { return Duration.between(start, end); }
    public boolean contains(Instant time) { return !time.isBefore(start) && !time.isAfter(end); }

    /**
     * {@code max(starts)..min(ends)} when the two periods meet; empty when they are apart. A result with zero
     * duration means that the periods only touch at one instant.
     */
    public Optional<TimeInterval> intersect(TimeInterval other) {
        Instant from = start.isAfter(other.start) ? start : other.start;
        Instant to = end.isBefore(other.end) ? end : other.end;
        return to.isBefore(from) ? Optional.empty() : Optional.of(new TimeInterval(from, to));
    }

    /** The periods merged where they overlap or touch, in chronological order. */
    public static List<TimeInterval> union(Collection<TimeInterval> intervals) {
        var sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(TimeInterval::start).thenComparing(TimeInterval::end));
        var merged = new ArrayList<TimeInterval>();
        for (var next : sorted) {
            if (!merged.isEmpty() && !next.start.isAfter(merged.getLast().end)) {
                var last = merged.removeLast();
                merged.add(new TimeInterval(last.start, next.end.isAfter(last.end) ? next.end : last.end));
            } else merged.add(next);
        }
        return List.copyOf(merged);
    }

    /** Total length of the union: time covered by several periods is counted once. */
    public static Duration total(Collection<TimeInterval> intervals) {
        return union(intervals).stream().map(TimeInterval::duration).reduce(Duration.ZERO, Duration::plus);
    }
}
