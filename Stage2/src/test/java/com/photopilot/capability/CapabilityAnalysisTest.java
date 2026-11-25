package com.photopilot.capability;

import com.photopilot.application.CapabilityPresentation;
import com.photopilot.application.FocalScale;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import com.photopilot.service.GearCapabilityService;
import com.photopilot.service.PhotoFormulas;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityDemoInventory.*;
import static org.junit.jupiter.api.Assertions.*;

/** Structured inventory capability analysis on the demonstration-like fixture and edge cases; shared formatting. */
class CapabilityAnalysisTest {
    @TempDir Path directory;
    ApplicationBootstrap bootstrap;
    GearCapabilityService service;
    final LocalizationService english = LocalizationService.english();
    @BeforeEach void setup() {
        bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()));
        service = bootstrap.facade().capabilities();
    }
    @AfterEach void close() { bootstrap.close(); }
    GearCapabilityAnalysis analyze(List<? extends Equipment> items) { return service.analyzeItems(List.copyOf(items)).value().orElseThrow(); }
    static CameraLensPair pair(GearCapabilityAnalysis report, UUID camera, UUID lens) { return report.pair(camera, lens).orElseThrow(); }
    static List<FindingKind> kinds(GearCapabilityAnalysis report) { return report.findings().stream().map(Finding::kind).toList(); }

    @Test void demoInventorySeparatesTheIsolatedPrimeFromContinuousCoverage() {
        var report = analyze(items());
        assertEquals(3, report.records(EquipmentType.CAMERA)); assertEquals(3, report.units(EquipmentType.CAMERA));
        assertEquals(7, report.records(EquipmentType.LENS)); assertEquals(0, report.units(EquipmentType.FILTER));
        assertEquals(List.of(new Interval(16, 16), new Interval(20, 400)), report.coverage());
        assertEquals(List.of(new Interval(20, 400)), report.continuous());
        assertEquals(List.of(new Interval(16, 16)), report.isolated());
        assertEquals(List.of(new Interval(16, 20)), report.gaps());
        assertEquals(Optional.of(new Interval(16, 400)), report.focalSpan());
        // The 16-400 span is an extent with a gap, never reported as continuous coverage.
        assertTrue(report.findings().stream().noneMatch(f -> f.kind() == FindingKind.CONTINUOUS_COVERAGE && f.intervals().getFirst().startMm() == 16));
        assertEquals(List.of(FindingKind.CONTINUOUS_COVERAGE, FindingKind.ISOLATED_FOCAL, FindingKind.COVERAGE_GAP, FindingKind.WIDEST_APERTURE,
            FindingKind.FAST_LENSES, FindingKind.MOUNT_NOT_ESTABLISHED, FindingKind.MOUNT_NAMING, FindingKind.INCOMPLETE_CAMERAS, FindingKind.NO_FILTERS), kinds(report));
        var isolated = report.findings().get(1);
        assertEquals(List.of(V16), isolated.items().stream().map(Equipment::id).toList());
        assertEquals("Continuous coverage: 20–400 mm", CapabilityPresentation.finding(report.findings().getFirst(), report, english));
        assertEquals("Isolated prime focal length: 16 mm (Viltrox AF 16mm F1.8 FE)", CapabilityPresentation.finding(isolated, report, english));
        assertEquals("Coverage gap between 16 mm and 20 mm", CapabilityPresentation.finding(report.findings().get(2), report, english));
        assertEquals("Widest recorded aperture: f/1.4 (Sony FE 35mm F1.4 GM)", CapabilityPresentation.finding(report.findings().get(3), report, english));
        assertEquals(5, report.findings().get(4).count(), "f/2.8 or brighter: 24-70, 35, 55, 70-180 and 16");
        assertEquals(3, report.findings().get(5).count(), "Tamron x2 and Viltrox on the body recorded as Sony E");
        assertEquals("No filters recorded.", CapabilityPresentation.finding(report.findings().getLast(), report, english));
        assertEquals(Optional.of(1.4), report.largestAperture());
        assertEquals(List.of(L35), report.widestLenses().stream().map(Equipment::id).toList());
    }

    @Test void mountAliasesAreNormalizedConservativelyAndStoredLabelsStayUnchanged() {
        var report = analyze(items());
        for (var lens : List.of(L2070, L2470, L35, L55, T50400, T70180, V16)) {
            assertEquals(Relation.SAME_LABEL, pair(report, A7IV, lens).mount().relation());
            assertEquals(Relation.SAME_LABEL, pair(report, A7RV, lens).mount().relation());
        }
        // Sony lenses say "E" with Sony brand context; the body says "Sony E": both identify Sony E.
        var sony = pair(report, A7V, L2070);
        assertEquals(Relation.EQUIVALENT_LABELS, sony.mount().relation());
        assertEquals(Optional.of(MountSystem.SONY_E), sony.mount().camera().system());
        assertEquals(Optional.of(MountSystem.SONY_E), sony.mount().lens().system());
        assertEquals(MountStatus.DIFFERENT_RECORDED, sony.mountStatus(), "the recorded labels themselves still differ");
        // A third-party lens's bare "E" is not established as Sony E by the report.
        assertEquals(Relation.POSSIBLE_ALIAS, pair(report, A7V, T50400).mount().relation());
        assertEquals(PairStatus.NOT_ESTABLISHED, pair(report, A7V, V16).mount().status());
        assertEquals("Sony E", ((Camera) report.equipment().stream().filter(e -> e.id().equals(A7V)).findFirst().orElseThrow()).mount());
        assertEquals(1, report.mountNaming().size());
        var naming = report.mountNaming().getFirst();
        assertEquals("Sony E", naming.mount());
        assertEquals(Set.of("E", "Sony E"), naming.labels().keySet());
        assertEquals(List.of(A7V), naming.labels().get("Sony E").stream().map(Equipment::id).toList());
    }

    @Test void equivalentFocalLengthsUseRecordedSensorsOnlyForEstablishedMounts() {
        var report = analyze(items());
        var a7iv = report.camera(A7IV).orElseThrow();
        assertEquals(Optional.of(SensorFormat.FULL_FRAME), a7iv.format());
        assertEquals(PhotoFormulas.cropFactor(35.9, 23.9), a7iv.cropFactor().orElseThrow(), 0);
        var equivalent = pair(report, A7IV, L2070).equivalentFocal().orElseThrow();
        assertEquals(PhotoFormulas.fullFrameEquivalentFocal(20, 35.9, 23.9), equivalent.startMm(), 0, "full precision internally");
        assertEquals("20.1–70.2 mm", CapabilityPresentation.equivalent(equivalent));
        assertEquals("1.0×", CapabilityPresentation.crop(a7iv.cropFactor(), english));
        // Missing sensor dimensions never produce an equivalent; neither does an unestablished mount.
        assertEquals(EquivalentStatus.SENSOR_UNKNOWN, pair(report, A7V, L2070).equivalentStatus());
        assertEquals(EquivalentStatus.MOUNT_NOT_ESTABLISHED, pair(report, A7V, T50400).equivalentStatus());
        assertTrue(report.camera(A7V).orElseThrow().format().isEmpty());
        assertEquals(List.of(new Interval(20, 70)), report.camera(A7V).orElseThrow().mountedCoverage(), "only the mount-matched Sony lenses");
        assertTrue(report.camera(A7V).orElseThrow().equivalentCoverage().isEmpty());
        assertEquals(report.coverage(), a7iv.mountedCoverage());
        var apsc = analyze(List.of(camera(UUID.randomUUID(), "Fujifilm", "X-T5", 1, "Fujifilm X", 23.5, 15.6, 40., 15., true, Set.of()),
            lens(UUID.randomUUID(), "Fujifilm", "XF 33mm", "X", 33, 33, 1.4, null, true, false, 58.)));
        assertEquals(Optional.of(SensorFormat.APS_C), apsc.cameras().getFirst().format());
        assertEquals(Relation.EQUIVALENT_LABELS, apsc.pairs().getFirst().mount().relation());
        assertEquals("50.6 mm", CapabilityPresentation.equivalent(apsc.pairs().getFirst().equivalentFocal().orElseThrow()));
    }

    @Test void dataQualityGroupsFindingsByItemAndSeparatesCategories() {
        var report = analyze(items());
        var a7v = report.quality().stream().filter(q -> q.item().id().equals(A7V)).findFirst().orElseThrow();
        assertEquals(List.of("sensorSize", "resolution", "burstRate", "bodyStabilization"), a7v.of(IssueCategory.MISSING).stream().map(Issue::code).toList());
        assertEquals(List.of("mountNaming"), a7v.of(IssueCategory.INCONSISTENT).stream().map(Issue::code).toList());
        assertEquals(List.of("Sony E", "E"), a7v.of(IssueCategory.INCONSISTENT).getFirst().arguments());
        assertTrue(a7v.of(IssueCategory.OPTIONAL).stream().map(Issue::code).toList().containsAll(List.of("autofocusModes", "weight")));
        assertEquals(List.of(A7V), report.needsAttention().stream().map(q -> q.item().id()).toList());
        // The lens without a filter thread is only missing optional information.
        var viltrox = report.quality().stream().filter(q -> q.item().id().equals(V16)).findFirst().orElseThrow();
        assertFalse(viltrox.needsAttention());
        assertTrue(viltrox.of(IssueCategory.OPTIONAL).stream().anyMatch(i -> i.code().equals("filterThread")));
        // No filters is an observation, not a data error.
        assertTrue(report.needsAttention().stream().noneMatch(q -> q.item() instanceof Filter));
        assertEquals("sensor size", CapabilityPresentation.issue(a7v.of(IssueCategory.MISSING).getFirst(), english));
    }

    @Test void invalidAndContradictoryValuesAreReportedWithoutInventingGeometry() {
        var huge = camera(UUID.randomUUID(), "Fixture", "Typo", 1, "E", 359., 239., 3300., 5000., true, Set.of());
        var reversed = lens(UUID.randomUUID(), "Fixture", "Odd zoom", "E", 24, 105, 5.6, 4., true, true, 77.);
        var report = analyze(List.of(huge, reversed));
        var issues = report.quality().stream().filter(q -> q.item() == huge).findFirst().orElseThrow();
        assertEquals(List.of("sensorRange", "resolutionRange", "burstRange"), issues.of(IssueCategory.INVALID).stream().map(Issue::code).toList());
        assertTrue(report.cameras().getFirst().cropFactor().isEmpty(), "implausible sensor sizes give no crop factor");
        assertEquals(EquivalentStatus.SENSOR_UNKNOWN, report.pairs().getFirst().equivalentStatus());
        var lensIssues = report.quality().stream().filter(q -> q.item() == reversed).findFirst().orElseThrow();
        assertEquals(List.of("apertureOrder"), lensIssues.of(IssueCategory.INCONSISTENT).stream().map(Issue::code).toList());
        assertEquals(4.0, report.lenses().getFirst().widestAperture(), "the brighter endpoint is the widest recorded aperture");
    }

    @Test void quantitiesDuplicatesAndAdjacentRangesAreCountedCorrectly() {
        var twin = camera(UUID.randomUUID(), "Fixture", "Body", 2, "E", 36., 24., 24., 8., false, Set.of());
        var same = camera(UUID.randomUUID(), "Fixture", "Body", 1, "E", 36., 24., 24., 8., false, Set.of());
        var a = lens(UUID.randomUUID(), "Fixture", "Zoom", "E", 24, 70, 4, 4., true, true, 77.);
        var b = lens(UUID.randomUUID(), "Fixture", "Tele", "E", 70, 200, 4, 4., true, true, 77.);
        var report = analyze(List.of(twin, same, a, b));
        assertEquals(2, report.records(EquipmentType.CAMERA)); assertEquals(3, report.units(EquipmentType.CAMERA));
        assertEquals(List.of(new Interval(24, 200)), report.coverage());
        assertEquals(List.of(new Interval(70, 70)), report.overlaps(), "adjacent ranges share only their endpoint");
        assertTrue(report.gaps().isEmpty());
        assertTrue(kinds(report).contains(FindingKind.ALL_MOUNTS_MATCH));
        var names = CapabilityPresentation.names(report.equipment());
        assertEquals(Set.of("Fixture Body", "Fixture Body #2"), Set.of(names.get(twin.id()), names.get(same.id())), "duplicate models stay distinguishable");
    }

    @Test void edgeInventoriesProduceObservationsInsteadOfInventedNumbers() {
        var empty = analyze(List.of());
        assertEquals(List.of(FindingKind.NO_EQUIPMENT), kinds(empty));
        assertTrue(empty.focalSpan().isEmpty()); assertTrue(FocalScale.of(empty.coverage()).isEmpty());
        var bodyOnly = analyze(List.of(camera(UUID.randomUUID(), "Fixture", "Body", 1, "E", null, null, null, null, null, Set.of())));
        assertEquals(List.of(FindingKind.NO_LENSES, FindingKind.INCOMPLETE_CAMERAS), kinds(bodyOnly));
        assertTrue(bodyOnly.pairs().isEmpty());
        var filterOnly = analyze(List.of(new Filter(UUID.randomUUID(), "Fixture", "ND", 1, Optional.empty(), Set.of(), Map.of(), Optional.empty(), 77, Optional.of(3.))));
        assertEquals(List.of(FindingKind.NO_LENSES, FindingKind.NO_CAMERAS), kinds(filterOnly));
        var prime = analyze(List.of(lens(UUID.randomUUID(), "Fixture", "50", "E", 50, 50, 1.8, null, true, false, 52.)));
        assertEquals(List.of(new Interval(50, 50)), prime.isolated()); assertTrue(prime.continuous().isEmpty());
        assertTrue(kinds(prime).contains(FindingKind.NO_CAMERAS));
        var primes = analyze(List.of(lens(UUID.randomUUID(), "Fixture", "35", "E", 35, 35, 1.8, null, true, false, 52.),
            lens(UUID.randomUUID(), "Fixture", "85", "E", 85, 85, 1.8, null, true, false, 67.), lens(UUID.randomUUID(), "Fixture", "135", "E", 135, 135, 2., null, true, false, 77.)));
        assertEquals(3, primes.findings().stream().filter(f -> f.kind() == FindingKind.ISOLATED_FOCAL).count());
        assertEquals(2, primes.findings().stream().filter(f -> f.kind() == FindingKind.COVERAGE_GAP).count());
        var unknownMount = analyze(List.of(camera(UUID.randomUUID(), "Fixture", "Body", 1, "n/a", 36., 24., 24., 8., false, Set.of("AF-S")),
            lens(UUID.randomUUID(), "Fixture", "Zoom", "E", 24, 70, 4, 4., true, true, 77.)));
        assertEquals(Relation.UNKNOWN, unknownMount.pairs().getFirst().mount().relation());
        assertTrue(unknownMount.pairs().getFirst().equivalentFocal().isEmpty(), "an unknown mount never becomes a positive match");
        assertTrue(kinds(unknownMount).containsAll(List.of(FindingKind.MOUNT_NOT_ESTABLISHED, FindingKind.LENS_WITHOUT_BODY, FindingKind.BODY_WITHOUT_LENS)));
        var conflict = analyze(List.of(camera(UUID.randomUUID(), "Canon", "R6", 1, "RF", 36., 24., 20., 12., true, Set.of()),
            lens(UUID.randomUUID(), "Sony", "FE 24-70", "E", 24, 70, 2.8, 2.8, true, false, 82.)));
        assertEquals(Relation.DIFFERENT_SYSTEMS, conflict.pairs().getFirst().mount().relation());
        assertEquals(PairStatus.CONFLICT, conflict.pairs().getFirst().mount().status());
        assertTrue(kinds(conflict).contains(FindingKind.MOUNT_CONFLICTS));
    }

    @Test void sharedFormattingUsesPhotographicUnitsAndNeverRawValues() {
        var report = analyze(items());
        var byId = new HashMap<UUID, LensCapability>(); report.lenses().forEach(l -> byId.put(l.lens().id(), l));
        assertEquals("f/4.5–6.3", CapabilityPresentation.aperture(byId.get(T50400), english));
        assertEquals("f/2.8", CapabilityPresentation.aperture(byId.get(L2470), english));
        assertEquals("f/4", CapabilityPresentation.aperture(byId.get(L2070), english));
        assertEquals("f/1.4", CapabilityPresentation.aperture(byId.get(L35), english));
        assertEquals(ApertureKind.VARIABLE_ENDPOINTS, byId.get(T50400).apertureKind());
        assertEquals(ApertureKind.CONSTANT, byId.get(L2070).apertureKind());
        assertEquals("20–70 mm", CapabilityPresentation.focal(byId.get(L2070).focal()));
        assertEquals("16 mm", CapabilityPresentation.focal(byId.get(V16).focal()));
        var a7iv = report.camera(A7IV).orElseThrow().camera();
        assertEquals("33 MP", CapabilityPresentation.megapixels(a7iv.resolutionMP(), english));
        assertEquals("10 fps", CapabilityPresentation.fps(a7iv.burstFps(), english));
        assertEquals("35.9 × 23.9 mm", CapabilityPresentation.sensor(a7iv, english));
        assertEquals("Yes", CapabilityPresentation.yesNo(a7iv.stabilization(), english));
        var a7v = report.camera(A7V).orElseThrow().camera();
        assertEquals("Not recorded", CapabilityPresentation.megapixels(a7v.resolutionMP(), english));
        assertEquals("Unknown", CapabilityPresentation.yesNo(a7v.stabilization(), english));
        assertEquals("Not recorded", CapabilityPresentation.sensor(a7v, english));
        assertEquals("f/4.5 (long end not recorded)", CapabilityPresentation.aperture(analyze(List.of(lens(UUID.randomUUID(), "Fixture", "Old", "E", 70, 300, 4.5, null, true, true, 67.))).lenses().getFirst(), english));
        String everything = CapabilityPresentation.all(report, english);
        for (String raw : List.of("null", "true", "false", "Optional[", "Optional.empty", "35.900000", "2.800000")) assertFalse(everything.contains(raw), raw);
        var chinese = LocalizationService.english(); chinese.useLanguage("zh-CN");
        assertEquals("是", CapabilityPresentation.yesNo(a7iv.stabilization(), chinese));
        assertEquals("连续覆盖：20–400 mm", CapabilityPresentation.finding(report.findings().getFirst(), report, chinese));
    }

    @Test void focalScaleIsLogarithmicAndShared() {
        var scale = FocalScale.of(List.of(new Interval(16, 16), new Interval(20, 400))).orElseThrow();
        assertEquals(0, scale.fraction(scale.low()), 1e-12); assertEquals(1, scale.fraction(scale.high()), 1e-12);
        assertEquals(scale.fraction(48) - scale.fraction(24), scale.fraction(400) - scale.fraction(200), 1e-12, "equal ratios, equal distances");
        var ticks = scale.ticks(8);
        assertEquals(List.of(20., 50., 100., 200., 400.), ticks, "round focal lengths are preferred");
        assertTrue(scale.ticks(20).containsAll(List.of(16., 24., 35., 70., 135., 300.)), scale.ticks(20).toString());
        for (int i = 1; i < ticks.size(); i++) assertTrue(scale.fraction(ticks.get(i)) - scale.fraction(ticks.get(i - 1)) >= 1.0 / 8 - 1e-12);
        assertThrows(IllegalArgumentException.class, () -> scale.fraction(0));
        assertThrows(IllegalArgumentException.class, () -> scale.fraction(Double.NaN));
        var single = FocalScale.of(List.of(new Interval(50, 50))).orElseThrow();
        assertTrue(single.high() / single.low() >= 2 - 1e-9);
        assertEquals(0.5, single.fraction(50), 1e-9, "a lone prime sits in the middle");
    }
}
