package com.photopilot.model;

import java.util.*;

/** Immutable observations of one fresh F05 inventory snapshot; no recommendations or saved profile. */
public record GearCapabilityAnalysis(List<Equipment> equipment, Map<EquipmentType, Long> quantities,
        List<LensCapability> lenses, List<Interval> coverage, List<Interval> overlaps,
        List<Interval> gaps, Optional<Double> largestAperture, List<CameraLensPair> pairs) {
    public GearCapabilityAnalysis {
        equipment = List.copyOf(equipment); quantities = Map.copyOf(quantities);
        lenses = List.copyOf(lenses); coverage = List.copyOf(coverage);
        overlaps = List.copyOf(overlaps); gaps = List.copyOf(gaps); pairs = List.copyOf(pairs);
        Objects.requireNonNull(largestAperture);
    }
    /** Closed measured coverage; gaps use the same bounds but exclude both endpoints. */
    public record Interval(double startMm, double endMm) {
        public Interval {
            if (!Double.isFinite(startMm) || !Double.isFinite(endMm) || startMm <= 0 || endMm < startMm)
                throw new IllegalArgumentException("Invalid focal interval");
        }
    }
    public enum ApertureKind { PRIME, CONSTANT, VARIABLE_ENDPOINTS, UNKNOWN_ZOOM }
    public record LensCapability(Lens lens, Interval focal, ApertureKind apertureKind) { }
    public enum MountStatus { MATCH_RECORDED, DIFFERENT_RECORDED, UNKNOWN }
    /** Equivalent focal interval is a geometric, diagonal-FOV estimate, conditional on sensor coverage. */
    public record CameraLensPair(Camera camera, Lens lens, MountStatus mountStatus,
            Optional<Interval> equivalentFocal) { }
}
