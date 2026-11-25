package com.photopilot.application;

import com.photopilot.model.GearCapabilityAnalysis.Interval;
import java.util.*;

/**
 * Logarithmic focal-length axis shared by the GUI coverage chart and the CLI chart, so both place every tick, bar and
 * prime marker with the same mapping: {@code fraction(mm) = ln(mm / low) / ln(high / low)}. Equal ratios get equal
 * distances (24 to 48 mm spans as far as 200 to 400 mm). Only positive, finite focal lengths are plotted.
 */
public final class FocalScale {
    /** Reference focal lengths offered as ticks (mm), roundest first: a later tier only fills room the earlier ones leave. */
    private static final double[][] TIERS = {
        {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000},
        {14, 24, 35, 70, 135, 300, 400, 600, 800},
        {4, 6, 8, 12, 16, 18, 28, 85, 105, 1200, 1600, 3000, 4000, 8000}};
    private final double low, high;

    private FocalScale(double low, double high) { this.low = low; this.high = high; }

    /** The axis around {@code intervals} with a small margin; empty when there is nothing positive to plot. */
    public static Optional<FocalScale> of(Collection<Interval> intervals) {
        double min = Double.POSITIVE_INFINITY, max = 0;
        for (var range : intervals) { min = Math.min(min, range.startMm()); max = Math.max(max, range.endMm()); }
        if (!(min > 0) || !Double.isFinite(min) || !(max > 0) || !Double.isFinite(max)) return Optional.empty();
        double lo = min / 1.12, hi = max * 1.12;
        // A single prime (or a very short range) still gets a readable axis of at least a factor of two.
        if (hi / lo < 2) { double centre = Math.sqrt(min * max); lo = centre / Math.sqrt(2); hi = centre * Math.sqrt(2); }
        return Optional.of(new FocalScale(lo, hi));
    }

    public double low() { return low; }
    public double high() { return high; }

    /** Position of {@code mm} on the axis, 0 at {@link #low()} and 1 at {@link #high()}. */
    public double fraction(double mm) {
        if (!(mm > 0) || !Double.isFinite(mm)) throw new IllegalArgumentException("Focal length must be positive and finite");
        return Math.log(mm / low) / Math.log(high / low);
    }

    /**
     * Reference ticks inside the axis, in ascending order, at least {@code 1 / maxTicks} of the axis apart. Round values
     * (20, 50, 100 ...) are placed first; finer references are added only where they keep that spacing.
     */
    public List<Double> ticks(int maxTicks) {
        double spacing = 1.0 / Math.max(2, maxTicks);
        var kept = new TreeSet<Double>();
        for (double[] tier : TIERS)
            for (double tick : tier)
                if (tick >= low && tick <= high && kept.stream().allMatch(other -> Math.abs(fraction(tick) - fraction(other)) >= spacing)) kept.add(tick);
        return List.copyOf(kept);
    }
}
