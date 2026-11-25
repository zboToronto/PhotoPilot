package com.photopilot.model;

import java.util.*;

/**
 * Immutable observations of one fresh F05 inventory snapshot; no recommendations, scores or saved profile. Everything
 * the GUI dashboard and the CLI report show is derived here (GearCapabilityService), so both interfaces read the same
 * counts, focal intervals, aperture findings, mount comparisons and data-quality findings. Unknown values stay empty;
 * nothing is filled in from model names.
 */
public record GearCapabilityAnalysis(List<Equipment> equipment, Map<EquipmentType, Long> quantities,
        List<LensCapability> lenses, List<Interval> coverage, List<Interval> overlaps,
        List<Interval> gaps, Optional<Double> largestAperture, List<CameraLensPair> pairs,
        List<CameraCapability> cameras, List<Lens> widestLenses, List<Finding> findings,
        List<ItemQuality> quality, List<MountNaming> mountNaming) {
    public GearCapabilityAnalysis {
        equipment = List.copyOf(equipment); quantities = Map.copyOf(quantities);
        lenses = List.copyOf(lenses); coverage = List.copyOf(coverage);
        overlaps = List.copyOf(overlaps); gaps = List.copyOf(gaps); pairs = List.copyOf(pairs);
        cameras = List.copyOf(cameras); widestLenses = List.copyOf(widestLenses); findings = List.copyOf(findings);
        quality = List.copyOf(quality); mountNaming = List.copyOf(mountNaming);
        Objects.requireNonNull(largestAperture);
    }

    /** Inventory records of {@code type}; one record may stand for several physical units. */
    public long records(EquipmentType type) { return equipment.stream().filter(e -> e.type() == type).count(); }
    /** Physical units of {@code type} (sum of record quantities). */
    public long units(EquipmentType type) { return quantities.getOrDefault(type, 0L); }
    /** Shortest and longest recorded focal length; may contain gaps (see {@link #gaps()}). */
    public Optional<Interval> focalSpan() {
        return coverage.isEmpty() ? Optional.empty() : Optional.of(new Interval(coverage.getFirst().startMm(), coverage.getLast().endMm()));
    }
    /** Covered components that are real intervals (zoom ranges, possibly joined by primes inside them). */
    public List<Interval> continuous() { return coverage.stream().filter(range -> !range.point()).toList(); }
    /** Covered components that are single focal lengths (primes outside every other range). */
    public List<Interval> isolated() { return coverage.stream().filter(Interval::point).toList(); }
    public List<CameraLensPair> pairsFor(UUID camera) { return pairs.stream().filter(p -> p.camera().id().equals(camera)).toList(); }
    public Optional<CameraLensPair> pair(UUID camera, UUID lens) {
        return pairs.stream().filter(p -> p.camera().id().equals(camera) && p.lens().id().equals(lens)).findFirst();
    }
    public Optional<CameraCapability> camera(UUID id) { return cameras.stream().filter(c -> c.camera().id().equals(id)).findFirst(); }
    public Optional<LensCapability> lens(UUID id) { return lenses.stream().filter(l -> l.lens().id().equals(id)).findFirst(); }
    /** Items with a missing, invalid or inconsistent key specification (optional fields alone do not count). */
    public List<ItemQuality> needsAttention() { return quality.stream().filter(ItemQuality::needsAttention).toList(); }
    /** Open gaps between consecutive components of a merged, ordered coverage list (endpoints excluded). */
    public static List<Interval> gapsBetween(List<Interval> merged) {
        var gaps = new ArrayList<Interval>();
        for (int i = 1; i < merged.size(); i++) gaps.add(new Interval(merged.get(i - 1).endMm(), merged.get(i).startMm()));
        return List.copyOf(gaps);
    }

    /** Closed measured coverage; gaps use the same bounds but exclude both endpoints. */
    public record Interval(double startMm, double endMm) {
        public Interval {
            if (!Double.isFinite(startMm) || !Double.isFinite(endMm) || startMm <= 0 || endMm < startMm)
                throw new IllegalArgumentException("Invalid focal interval");
        }
        /** A single focal length (a prime, or the shared endpoint of two ranges). */
        public boolean point() { return startMm == endMm; }
    }
    public enum ApertureKind { PRIME, CONSTANT, VARIABLE_ENDPOINTS, UNKNOWN_ZOOM }
    /** {@code mount} is the lens's recorded mount label as the report reads it. */
    public record LensCapability(Lens lens, Interval focal, ApertureKind apertureKind, MountReading mount) {
        public LensCapability { Objects.requireNonNull(lens); Objects.requireNonNull(focal); Objects.requireNonNull(apertureKind); Objects.requireNonNull(mount); }
        public boolean prime() { return focal.point(); }
        /** Smallest recorded f-number of the lens (either endpoint); intermediate values are never inferred. */
        public double widestAperture() { return Math.min(lens.maxAperture(), lens.maxApertureAtMaxFocal().orElse(lens.maxAperture())); }
    }

    /** Recorded-label status kept for earlier callers: identical labels, different labels, or unknown. */
    public enum MountStatus { MATCH_RECORDED, DIFFERENT_RECORDED, UNKNOWN }
    /** How two recorded mount labels relate under the report's conservative normalization. */
    public enum Relation {
        /** Identical after case and whitespace normalization. */
        SAME_LABEL,
        /** Different text that names the same mount: a naming variant ("E" / "E-mount") or the same known system ("E" on a Sony body / "Sony E"). */
        EQUIVALENT_LABELS,
        /** One label names a known system; the other is that system's bare designation without context establishing it. */
        POSSIBLE_ALIAS,
        /** Labels differ and are not both recognised; nothing is concluded. */
        DIFFERENT_LABELS,
        /** Both labels name known, different mount systems. */
        DIFFERENT_SYSTEMS,
        /** At least one mount is not recorded (blank or a placeholder such as "n/a"). */
        UNKNOWN;

        public PairStatus status() {
            return switch (this) {
                case SAME_LABEL, EQUIVALENT_LABELS -> PairStatus.ESTABLISHED;
                case DIFFERENT_SYSTEMS -> PairStatus.CONFLICT;
                default -> PairStatus.NOT_ESTABLISHED;
            };
        }
    }
    /** ESTABLISHED covers the mount labels only; image circle, electronics and autofocus operation are never verified. */
    public enum PairStatus { ESTABLISHED, NOT_ESTABLISHED, CONFLICT }
    /**
     * One recorded mount label as read by the report: the original text (stored records are never rewritten), whether
     * anything is recorded, the comparison key (case, spacing, hyphens and a trailing "mount" removed) and the known
     * system it establishes, if any.
     */
    public record MountReading(String recorded, boolean known, String key, Optional<MountSystem> system) {
        public MountReading { Objects.requireNonNull(recorded); Objects.requireNonNull(key); Objects.requireNonNull(system); }
    }
    public record MountComparison(Relation relation, MountReading camera, MountReading lens) {
        public MountComparison { Objects.requireNonNull(relation); Objects.requireNonNull(camera); Objects.requireNonNull(lens); }
        public boolean established() { return relation.status() == PairStatus.ESTABLISHED; }
        public PairStatus status() { return relation.status(); }
    }
    public enum EquivalentStatus { CALCULATED, MOUNT_NOT_ESTABLISHED, SENSOR_UNKNOWN }
    /** Equivalent focal interval is a geometric, diagonal-FOV estimate, conditional on sensor coverage. */
    public record CameraLensPair(Camera camera, Lens lens, MountComparison mount, Optional<Interval> equivalentFocal) {
        public CameraLensPair { Objects.requireNonNull(mount); Objects.requireNonNull(equivalentFocal); }
        public MountStatus mountStatus() {
            return switch (mount.relation()) {
                case SAME_LABEL -> MountStatus.MATCH_RECORDED;
                case UNKNOWN -> MountStatus.UNKNOWN;
                default -> MountStatus.DIFFERENT_RECORDED;
            };
        }
        public EquivalentStatus equivalentStatus() {
            return equivalentFocal.isPresent() ? EquivalentStatus.CALCULATED
                : mount.established() ? EquivalentStatus.SENSOR_UNKNOWN : EquivalentStatus.MOUNT_NOT_ESTABLISHED;
        }
    }

    /** Format named from the recorded sensor size only (crop-factor bands); OTHER when no band fits. */
    public enum SensorFormat { MEDIUM_FORMAT, FULL_FRAME, APS_C, FOUR_THIRDS, ONE_INCH, OTHER }
    /**
     * A body's recorded sensor geometry and what its mount-established lenses reach: physical focal coverage and the
     * conditional 35 mm-equivalent coverage (both closed-interval unions, as for the whole inventory).
     */
    public record CameraCapability(Camera camera, MountReading mount, Optional<Double> cropFactor, Optional<SensorFormat> format,
            List<Interval> mountedCoverage, List<Interval> equivalentCoverage) {
        public CameraCapability {
            Objects.requireNonNull(camera); Objects.requireNonNull(mount); Objects.requireNonNull(cropFactor); Objects.requireNonNull(format);
            mountedCoverage = List.copyOf(mountedCoverage); equivalentCoverage = List.copyOf(equivalentCoverage);
        }
    }

    public enum Level { INFO, WARN }
    public enum FindingKind {
        NO_EQUIPMENT, NO_LENSES, NO_CAMERAS, CONTINUOUS_COVERAGE, ISOLATED_FOCAL, COVERAGE_GAP, WIDEST_APERTURE, FAST_LENSES,
        ALL_MOUNTS_MATCH, MOUNT_CONFLICTS, MOUNT_NOT_ESTABLISHED, LENS_WITHOUT_BODY, BODY_WITHOUT_LENS, MOUNT_NAMING,
        INCOMPLETE_CAMERAS, INCOMPLETE_LENSES, NO_FILTERS
    }
    /**
     * One structured conclusion, rendered by both interfaces: the intervals, items, count or value it is about. Findings
     * are observations of recorded data, never ratings or recommendations.
     */
    public record Finding(FindingKind kind, Level level, List<Interval> intervals, List<Equipment> items, long count, Optional<Double> value) {
        public Finding {
            Objects.requireNonNull(kind); Objects.requireNonNull(level); Objects.requireNonNull(value);
            intervals = List.copyOf(intervals); items = List.copyOf(items);
        }
    }

    /** MISSING: a key field is not recorded; INVALID: outside the plausible range; INCONSISTENT: contradicts itself or other records; OPTIONAL: not needed by the report. */
    public enum IssueCategory { MISSING, INVALID, INCONSISTENT, OPTIONAL }
    /** {@code code} names the field or rule (localized as {@code capability.issue.<code>}); arguments are display text. */
    public record Issue(IssueCategory category, String code, List<String> arguments) {
        public Issue { Objects.requireNonNull(category); Objects.requireNonNull(code); arguments = List.copyOf(arguments); }
    }
    public record ItemQuality(Equipment item, List<Issue> issues) {
        public ItemQuality { Objects.requireNonNull(item); issues = List.copyOf(issues); }
        public boolean needsAttention() { return issues.stream().anyMatch(issue -> issue.category() != IssueCategory.OPTIONAL); }
        public List<Issue> of(IssueCategory category) { return issues.stream().filter(issue -> issue.category() == category).toList(); }
    }
    /** One mount recorded under several labels: label (as recorded) to the items using it, in report order. */
    public record MountNaming(String mount, Map<String, List<Equipment>> labels) {
        public MountNaming {
            Objects.requireNonNull(mount);
            var copy = new LinkedHashMap<String, List<Equipment>>();
            labels.forEach((label, items) -> copy.put(label, List.copyOf(items)));
            labels = Collections.unmodifiableMap(copy);
        }
    }
}
