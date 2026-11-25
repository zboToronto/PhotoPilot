package com.photopilot.service;

import com.photopilot.common.Result;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;

/**
 * Deterministic, read-only inventory analysis, deliberately separate from Stage 1 requirement derivation. It reads the
 * F05 inventory once per call and never writes, calls a model or reaches the network; the GUI dashboard and the CLI
 * report both render the single {@link GearCapabilityAnalysis} it returns.
 */
public final class GearCapabilityService {
    /** Threshold of the "fast lenses" observation: a conventional, stated f-number, not a rating. */
    public static final double FAST_APERTURE = 2.8;
    /** Plausibility limits of the data-quality check (validation already rejects non-positive and non-finite values). */
    static final double MAX_SENSOR_SIDE_MM = 100, MIN_SENSOR_SIDE_MM = 1, MAX_RESOLUTION_MP = 1000, MAX_BURST_FPS = 1000,
        MAX_FOCAL_MM = 5000, MIN_F_NUMBER = 0.5, MAX_FILTER_MM = 200;
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
        // Mount labels are read once per item (originals kept); every pair compares these readings.
        var readings = new HashMap<UUID, MountReading>();
        for (var item : ordered) {
            if (item instanceof Camera camera) readings.put(camera.id(), MountNormalization.read(camera.mount(), camera.brand()));
            if (item instanceof Lens lens) readings.put(lens.id(), MountNormalization.read(lens.mount(), lens.brand()));
        }
        var lenses = ordered.stream().filter(Lens.class::isInstance).map(Lens.class::cast).map(lens ->
            new LensCapability(lens, new Interval(lens.minFocalMm(), lens.maxFocalMm()),
                lens.minFocalMm() == lens.maxFocalMm() ? ApertureKind.PRIME :
                lens.maxApertureAtMaxFocal().isEmpty() ? ApertureKind.UNKNOWN_ZOOM :
                lens.maxApertureAtMaxFocal().get() == lens.maxAperture() ? ApertureKind.CONSTANT : ApertureKind.VARIABLE_ENDPOINTS,
                readings.get(lens.id()))).toList();
        var bodies = ordered.stream().filter(Camera.class::isInstance).map(Camera.class::cast).toList();
        var coverage = merge(lenses.stream().map(LensCapability::focal).toList());
        var gaps = GearCapabilityAnalysis.gapsBetween(coverage);
        var aperture = lenses.stream().mapToDouble(LensCapability::widestAperture).min();
        var widest = aperture.isPresent() ? lenses.stream().filter(l -> l.widestAperture() == aperture.getAsDouble()).map(LensCapability::lens).toList() : List.<Lens>of();

        var pairs = new ArrayList<CameraLensPair>();
        var cameras = new ArrayList<CameraCapability>();
        for (var camera : bodies) {
            var sensor = sensor(camera);
            var mounted = new ArrayList<Interval>(); var equivalents = new ArrayList<Interval>();
            for (var lens : lenses) {
                var mount = MountNormalization.compare(readings.get(camera.id()), readings.get(lens.lens().id()));
                Optional<Interval> equivalent = Optional.empty();
                if (mount.established() && sensor.isPresent()) {
                    double w = sensor.get()[0], h = sensor.get()[1];
                    double start = PhotoFormulas.fullFrameEquivalentFocal(lens.focal().startMm(), w, h);
                    double end = PhotoFormulas.fullFrameEquivalentFocal(lens.focal().endMm(), w, h);
                    if (Double.isFinite(start) && Double.isFinite(end) && start > 0) equivalent = Optional.of(new Interval(start, end));
                }
                if (mount.established()) mounted.add(lens.focal());
                equivalent.ifPresent(equivalents::add);
                pairs.add(new CameraLensPair(camera, lens.lens(), mount, equivalent));
            }
            Optional<Double> crop = sensor.map(s -> PhotoFormulas.cropFactor(s[0], s[1]));
            cameras.add(new CameraCapability(camera, readings.get(camera.id()), crop, crop.map(GearCapabilityService::format), merge(mounted), merge(equivalents)));
        }
        var naming = naming(ordered, readings);
        var quality = quality(ordered, readings, naming);
        var overlaps = overlaps(lenses.stream().map(LensCapability::focal).toList());
        var analysis = new GearCapabilityAnalysis(ordered, counts, lenses, coverage, overlaps, gaps,
            aperture.isPresent() ? Optional.of(aperture.getAsDouble()) : Optional.empty(), pairs, cameras, widest, List.of(), quality, naming);
        return Result.success(new GearCapabilityAnalysis(ordered, counts, lenses, coverage, overlaps, gaps, analysis.largestAperture(),
            pairs, cameras, widest, findings(analysis), quality, naming));
    }

    /** Recorded sensor size usable for geometry: both sides present and within the plausible range. */
    private static Optional<double[]> sensor(Camera camera) {
        if (camera.sensorWidthMm().isEmpty() || camera.sensorHeightMm().isEmpty()) return Optional.empty();
        double w = camera.sensorWidthMm().get(), h = camera.sensorHeightMm().get();
        return plausibleSensor(w) && plausibleSensor(h) ? Optional.of(new double[]{w, h}) : Optional.empty();
    }
    private static boolean plausibleSensor(double side) { return side >= MIN_SENSOR_SIDE_MM && side <= MAX_SENSOR_SIDE_MM; }

    /** Named only inside these crop-factor bands; anything between them stays OTHER. */
    static SensorFormat format(double crop) {
        if (crop >= 0.6 && crop < 0.9) return SensorFormat.MEDIUM_FORMAT;
        if (crop >= 0.95 && crop <= 1.05) return SensorFormat.FULL_FRAME;
        if (crop >= 1.45 && crop <= 1.65) return SensorFormat.APS_C;
        if (crop >= 1.9 && crop <= 2.1) return SensorFormat.FOUR_THIRDS;
        if (crop >= 2.6 && crop <= 2.9) return SensorFormat.ONE_INCH;
        return SensorFormat.OTHER;
    }

    private static List<Finding> findings(GearCapabilityAnalysis a) {
        var found = new ArrayList<Finding>();
        if (a.equipment().isEmpty()) { found.add(finding(FindingKind.NO_EQUIPMENT, Level.INFO)); return found; }
        if (a.lenses().isEmpty()) found.add(finding(FindingKind.NO_LENSES, Level.INFO));
        if (a.cameras().isEmpty()) found.add(finding(FindingKind.NO_CAMERAS, Level.INFO));
        for (var range : a.continuous()) found.add(new Finding(FindingKind.CONTINUOUS_COVERAGE, Level.INFO, List.of(range), List.of(), 0, Optional.empty()));
        for (var point : a.isolated()) found.add(new Finding(FindingKind.ISOLATED_FOCAL, Level.INFO, List.of(point),
            a.lenses().stream().filter(l -> l.focal().equals(point)).map(LensCapability::lens).map(Equipment.class::cast).toList(), 0, Optional.empty()));
        for (var gap : a.gaps()) found.add(new Finding(FindingKind.COVERAGE_GAP, Level.WARN, List.of(gap), List.of(), 0, Optional.empty()));
        a.largestAperture().ifPresent(value -> found.add(new Finding(FindingKind.WIDEST_APERTURE, Level.INFO, List.of(),
            List.copyOf(a.widestLenses()), 0, Optional.of(value))));
        if (!a.lenses().isEmpty()) found.add(new Finding(FindingKind.FAST_LENSES, Level.INFO, List.of(), List.of(),
            a.lenses().stream().filter(l -> l.widestAperture() <= FAST_APERTURE).count(), Optional.of(FAST_APERTURE)));
        if (!a.pairs().isEmpty()) {
            long conflicts = a.pairs().stream().filter(p -> p.mount().status() == PairStatus.CONFLICT).count();
            long open = a.pairs().stream().filter(p -> p.mount().status() == PairStatus.NOT_ESTABLISHED).count();
            if (conflicts == 0 && open == 0) found.add(new Finding(FindingKind.ALL_MOUNTS_MATCH, Level.INFO, List.of(), List.of(), a.pairs().size(), Optional.empty()));
            if (conflicts > 0) found.add(new Finding(FindingKind.MOUNT_CONFLICTS, Level.WARN, List.of(), List.of(), conflicts, Optional.empty()));
            if (open > 0) found.add(new Finding(FindingKind.MOUNT_NOT_ESTABLISHED, Level.WARN, List.of(), List.of(), open, Optional.empty()));
            var lonelyLenses = a.lenses().stream().map(LensCapability::lens)
                .filter(lens -> a.pairs().stream().noneMatch(p -> p.lens().id().equals(lens.id()) && p.mount().established())).map(Equipment.class::cast).toList();
            if (!lonelyLenses.isEmpty()) found.add(new Finding(FindingKind.LENS_WITHOUT_BODY, Level.WARN, List.of(), lonelyLenses, lonelyLenses.size(), Optional.empty()));
            var lonelyBodies = a.cameras().stream().map(CameraCapability::camera)
                .filter(camera -> a.pairsFor(camera.id()).stream().noneMatch(p -> p.mount().established())).map(Equipment.class::cast).toList();
            if (!lonelyBodies.isEmpty()) found.add(new Finding(FindingKind.BODY_WITHOUT_LENS, Level.WARN, List.of(), lonelyBodies, lonelyBodies.size(), Optional.empty()));
        }
        if (!a.mountNaming().isEmpty()) found.add(new Finding(FindingKind.MOUNT_NAMING, Level.WARN, List.of(), List.of(), a.mountNaming().size(), Optional.empty()));
        var incomplete = a.needsAttention();
        Predicate<ItemQuality> camera = q -> q.item() instanceof Camera, lens = q -> q.item() instanceof Lens;
        var cameras = incomplete.stream().filter(camera).map(ItemQuality::item).toList();
        var lenses = incomplete.stream().filter(lens).map(ItemQuality::item).toList();
        if (!cameras.isEmpty()) found.add(new Finding(FindingKind.INCOMPLETE_CAMERAS, Level.WARN, List.of(), cameras, cameras.size(), Optional.empty()));
        if (!lenses.isEmpty()) found.add(new Finding(FindingKind.INCOMPLETE_LENSES, Level.WARN, List.of(), lenses, lenses.size(), Optional.empty()));
        if (!a.lenses().isEmpty() && a.records(EquipmentType.FILTER) == 0) found.add(finding(FindingKind.NO_FILTERS, Level.INFO));
        return found;
    }
    private static Finding finding(FindingKind kind, Level level) { return new Finding(kind, level, List.of(), List.of(), 0, Optional.empty()); }

    /**
     * Mounts recorded under more than one label. Items are grouped by the system their label establishes; a bare
     * designation without context joins the system it designates only when that system is established elsewhere in
     * the inventory; other labels group by their comparison key. Groups with one label are consistent.
     */
    private static List<MountNaming> naming(List<Equipment> ordered, Map<UUID, MountReading> readings) {
        var established = new LinkedHashSet<MountSystem>();
        readings.values().forEach(reading -> reading.system().ifPresent(established::add));
        var groups = new LinkedHashMap<String, LinkedHashMap<String, List<Equipment>>>();
        var names = new HashMap<String, String>();
        for (var item : ordered) {
            var reading = readings.get(item.id());
            if (reading == null || !reading.known()) continue;
            var system = reading.system().or(() -> established.stream().filter(s -> s.designations().contains(reading.key())).findFirst());
            String group = system.map(s -> "system:" + s.name()).orElse("key:" + reading.key());
            names.putIfAbsent(group, system.map(MountSystem::displayName).orElse(reading.recorded().strip()));
            var labels = groups.computeIfAbsent(group, g -> new LinkedHashMap<>());
            String strict = MountNormalization.strict(reading.recorded());
            String shown = labels.keySet().stream().filter(label -> MountNormalization.strict(label).equals(strict)).findFirst().orElse(reading.recorded().strip());
            labels.computeIfAbsent(shown, label -> new ArrayList<>()).add(item);
        }
        var result = new ArrayList<MountNaming>();
        groups.forEach((group, labels) -> { if (labels.size() > 1) result.add(new MountNaming(names.get(group), labels)); });
        return result;
    }

    private static List<ItemQuality> quality(List<Equipment> ordered, Map<UUID, MountReading> readings, List<MountNaming> naming) {
        var minority = new HashMap<UUID, List<String>>();
        for (var group : naming) {
            String common = group.labels().entrySet().stream().max(Comparator.comparingInt((Map.Entry<String, List<Equipment>> e) -> e.getValue().size()))
                .map(Map.Entry::getKey).orElseThrow();
            int top = group.labels().get(common).size();
            // The first of the most-used labels is the reference; every other label is flagged on its items.
            String reference = group.labels().entrySet().stream().filter(e -> e.getValue().size() == top).findFirst().orElseThrow().getKey();
            group.labels().forEach((label, items) -> {
                if (!label.equals(reference)) for (var item : items) minority.put(item.id(), List.of(label, reference));
            });
        }
        var result = new ArrayList<ItemQuality>();
        for (var item : ordered) {
            var issues = new ArrayList<Issue>();
            switch (item) {
                case Camera camera -> {
                    if (!readings.get(camera.id()).known()) issues.add(issue(IssueCategory.MISSING, "mount"));
                    if (camera.sensorWidthMm().isEmpty() && camera.sensorHeightMm().isEmpty()) issues.add(issue(IssueCategory.MISSING, "sensorSize"));
                    else if (camera.sensorWidthMm().isEmpty()) issues.add(issue(IssueCategory.MISSING, "sensorWidth"));
                    else if (camera.sensorHeightMm().isEmpty()) issues.add(issue(IssueCategory.MISSING, "sensorHeight"));
                    else if (!plausibleSensor(camera.sensorWidthMm().get()) || !plausibleSensor(camera.sensorHeightMm().get()))
                        issues.add(issue(IssueCategory.INVALID, "sensorRange", number(camera.sensorWidthMm().get()) + " × " + number(camera.sensorHeightMm().get()) + " mm"));
                    if (camera.resolutionMP().isEmpty()) issues.add(issue(IssueCategory.MISSING, "resolution"));
                    else if (camera.resolutionMP().get() > MAX_RESOLUTION_MP) issues.add(issue(IssueCategory.INVALID, "resolutionRange", number(camera.resolutionMP().get()) + " MP"));
                    if (camera.burstFps().isEmpty()) issues.add(issue(IssueCategory.MISSING, "burstRate"));
                    else if (camera.burstFps().get() > MAX_BURST_FPS) issues.add(issue(IssueCategory.INVALID, "burstRange", number(camera.burstFps().get()) + " fps"));
                    if (camera.stabilization().isEmpty()) issues.add(issue(IssueCategory.MISSING, "bodyStabilization"));
                    if (minority.containsKey(camera.id())) issues.add(new Issue(IssueCategory.INCONSISTENT, "mountNaming", minority.get(camera.id())));
                    if (camera.autofocusModes().isEmpty()) issues.add(issue(IssueCategory.OPTIONAL, "autofocusModes"));
                    if (camera.weightGrams().isEmpty()) issues.add(issue(IssueCategory.OPTIONAL, "weight"));
                }
                case Lens lens -> {
                    boolean zoom = lens.minFocalMm() != lens.maxFocalMm();
                    if (!readings.get(lens.id()).known()) issues.add(issue(IssueCategory.MISSING, "mount"));
                    if (zoom && lens.maxApertureAtMaxFocal().isEmpty()) issues.add(issue(IssueCategory.MISSING, "apertureLongEnd"));
                    if (zoom && lens.maxApertureAtMaxFocal().filter(end -> end < lens.maxAperture()).isPresent())
                        issues.add(issue(IssueCategory.INCONSISTENT, "apertureOrder", "f/" + number(lens.maxAperture()), "f/" + number(lens.maxApertureAtMaxFocal().get())));
                    if (lens.maxFocalMm() > MAX_FOCAL_MM) issues.add(issue(IssueCategory.INVALID, "focalRange", number(lens.maxFocalMm()) + " mm"));
                    if (lens.maxAperture() < MIN_F_NUMBER) issues.add(issue(IssueCategory.INVALID, "apertureRange", "f/" + number(lens.maxAperture())));
                    if (lens.autofocus().isEmpty()) issues.add(issue(IssueCategory.MISSING, "autofocus"));
                    if (lens.stabilization().isEmpty()) issues.add(issue(IssueCategory.MISSING, "lensStabilization"));
                    if (lens.filterDiameterMm().filter(d -> d > MAX_FILTER_MM).isPresent()) issues.add(issue(IssueCategory.INVALID, "filterRange", number(lens.filterDiameterMm().get()) + " mm"));
                    if (minority.containsKey(lens.id())) issues.add(new Issue(IssueCategory.INCONSISTENT, "mountNaming", minority.get(lens.id())));
                    if (lens.filterDiameterMm().isEmpty()) issues.add(issue(IssueCategory.OPTIONAL, "filterThread"));
                    if (lens.weightGrams().isEmpty()) issues.add(issue(IssueCategory.OPTIONAL, "weight"));
                }
                case Filter filter -> {
                    if (filter.diameterMm() > MAX_FILTER_MM) issues.add(issue(IssueCategory.INVALID, "filterRange", number(filter.diameterMm()) + " mm"));
                    if (filter.weightGrams().isEmpty()) issues.add(issue(IssueCategory.OPTIONAL, "weight"));
                }
                default -> { }
            }
            result.add(new ItemQuality(item, issues));
        }
        return result;
    }
    private static Issue issue(IssueCategory category, String code, String... arguments) { return new Issue(category, code, List.of(arguments)); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

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
}
