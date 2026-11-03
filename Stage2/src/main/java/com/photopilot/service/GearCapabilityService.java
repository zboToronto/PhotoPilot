package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.util.*;

/** Deterministic, read-only inventory analysis, deliberately separate from Stage 1 requirement derivation. */
public final class GearCapabilityService {
    private final InventoryService inventory;
    public GearCapabilityService(InventoryService inventory) { this.inventory = Objects.requireNonNull(inventory); }

    public Result<GearCapabilityAnalysis> analyze() {
        var loaded = inventory.list("");
        if (!loaded.isSuccess()) return Result.failure(loaded);
        return analyzeItems(loaded.value().orElseThrow().items());
    }

    public Result<GearCapabilityAnalysis> analyzeItems(List<Equipment> items) {
        if (items == null || items.stream().anyMatch(Objects::isNull)) return Result.failureKey("capability.invalidItems");
        var ids = new HashSet<UUID>();
        for (var item : items) {
            var valid = item.validate();
            if (!valid.isSuccess()) return Result.failure(valid);
            if (!ids.add(item.id())) return Result.failureKey("capability.invalidItems");
        }
        var ordered = items.stream().sorted(Comparator.comparing((Equipment e) -> e.type().name())
            .thenComparing(Equipment::brand).thenComparing(Equipment::model).thenComparing(Equipment::id)).toList();
        var counts = new EnumMap<EquipmentType, Long>(EquipmentType.class);
        ordered.forEach(e -> counts.merge(e.type(), (long)e.quantity(), Long::sum));
        var lenses = ordered.stream().filter(Lens.class::isInstance).map(Lens.class::cast).map(lens ->
            new LensCapability(lens, new Interval(lens.minFocalMm(), lens.maxFocalMm()),
                lens.minFocalMm() == lens.maxFocalMm() ? ApertureKind.PRIME :
                lens.maxApertureAtMaxFocal().isEmpty() ? ApertureKind.UNKNOWN_ZOOM :
                lens.maxApertureAtMaxFocal().get() == lens.maxAperture() ? ApertureKind.CONSTANT : ApertureKind.VARIABLE_ENDPOINTS)).toList();
        var coverage = merge(lenses.stream().map(LensCapability::focal).toList());
        var gaps = new ArrayList<Interval>();
        for (int i = 1; i < coverage.size(); i++) gaps.add(new Interval(coverage.get(i - 1).endMm(), coverage.get(i).startMm()));
        var aperture = lenses.stream().mapToDouble(l -> Math.min(l.lens().maxAperture(),
            l.lens().maxApertureAtMaxFocal().orElse(l.lens().maxAperture()))).min();
        var pairs = new ArrayList<CameraLensPair>();
        for (var item : ordered) if (item instanceof Camera camera) for (var lens : lenses) {
            var status = mountStatus(camera.mount(), lens.lens().mount());
            Optional<Interval> equivalent = Optional.empty();
            if (status == MountStatus.MATCH_RECORDED && camera.sensorWidthMm().isPresent() && camera.sensorHeightMm().isPresent()) {
                double crop = Math.hypot(36, 24) / Math.hypot(camera.sensorWidthMm().get(), camera.sensorHeightMm().get());
                double start = lens.focal().startMm() * crop, end = lens.focal().endMm() * crop;
                if (Double.isFinite(start) && Double.isFinite(end) && start > 0) equivalent = Optional.of(new Interval(start, end));
            }
            pairs.add(new CameraLensPair(camera, lens.lens(), status, equivalent));
        }
        return Result.success(new GearCapabilityAnalysis(ordered, counts, lenses, coverage,
            overlaps(lenses.stream().map(LensCapability::focal).toList()), gaps,
            aperture.isPresent() ? Optional.of(aperture.getAsDouble()) : Optional.empty(), pairs));
    }

    private static List<Interval> merge(List<Interval> ranges) {
        var result = new ArrayList<Interval>();
        for (var range : ranges.stream().sorted(Comparator.comparingDouble(Interval::startMm).thenComparingDouble(Interval::endMm)).toList()) {
            if (result.isEmpty() || range.startMm() > result.getLast().endMm()) result.add(range);
            else { var previous = result.removeLast(); result.add(new Interval(previous.startMm(), Math.max(previous.endMm(), range.endMm()))); }
        }
        return List.copyOf(result);
    }

    /** Sweep closed endpoints separately from open spans, so primes and shared endpoints remain exact. */
    private static List<Interval> overlaps(List<Interval> ranges) {
        var events = new TreeMap<Double, int[]>();
        for (var r : ranges) { events.computeIfAbsent(r.startMm(), x -> new int[2])[0]++; events.computeIfAbsent(r.endMm(), x -> new int[2])[1]++; }
        var overlaps = new ArrayList<Interval>();
        int active = 0; Double previous = null;
        for (var event : events.entrySet()) {
            double point = event.getKey();
            if (previous != null && active >= 2) overlaps.add(new Interval(previous, point));
            int atPoint = active + event.getValue()[0];
            if (atPoint >= 2) overlaps.add(new Interval(point, point));
            active = atPoint - event.getValue()[1]; previous = point;
        }
        return merge(overlaps);
    }

    private static MountStatus mountStatus(String camera, String lens) {
        String c = camera.strip().toLowerCase(Locale.ROOT), l = lens.strip().toLowerCase(Locale.ROOT);
        var unknown = Set.of("", "unknown", "unspecified", "n/a", "na", "?", "未知", "不详", "none", "-", "other");
        if (unknown.contains(c) || unknown.contains(l)) return MountStatus.UNKNOWN;
        // Compare recorded labels only. No alias database, adapter, image-circle or electronic inference.
        return c.equals(l) ? MountStatus.MATCH_RECORDED : MountStatus.DIFFERENT_RECORDED;
    }
}
