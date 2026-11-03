package com.photopilot.capability;

import com.photopilot.application.*;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.*;
import com.photopilot.service.GearCapabilityService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GearCapabilityTest {
    @TempDir Path directory;
    ApplicationBootstrap bootstrap;
    GearCapabilityService service;
    @BeforeEach void setup() {
        bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()));
        service = bootstrap.facade().capabilities();
    }
    @AfterEach void close() { bootstrap.close(); }
    static Lens lens(double min, double max, double aperture, Double end) {
        return new Lens(UUID.randomUUID(), "Fixture", min + "-" + max, 1, Optional.empty(), Set.of(), Map.of(), Optional.empty(), "E",
            min, max, aperture, Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(end));
    }
    static Camera camera(String mount, Double width, Double height) {
        return new Camera(UUID.randomUUID(), "Fixture", "Body", 2, Optional.empty(), Set.of(), Map.of(), Optional.empty(), mount,
            Optional.ofNullable(width), Optional.ofNullable(height), Set.of(), Set.of(), Optional.empty(), Optional.empty(), Optional.empty(), Set.of());
    }
    GearCapabilityAnalysis analyze(Equipment... gear) { return service.analyzeItems(List.of(gear)).value().orElseThrow(); }
    @Test void mergesExampleRangesAndComputesRealOverlapUnion() {
        var report = analyze(lens(20,70,2.8,null), lens(70,180,2.8,null), lens(50,400,5.6,null));
        assertEquals(List.of(new Interval(20,400)), report.coverage());
        assertEquals(List.of(new Interval(50,180)), report.overlaps());
        assertTrue(report.gaps().isEmpty());
        assertEquals(3, report.lenses().size());
    }
    @Test void adjacentRangesShareOnlyTheirEndpoint() {
        var report = analyze(lens(20,70,4.,null), lens(70,180,4.,null));
        assertEquals(List.of(new Interval(20,180)), report.coverage());
        assertEquals(List.of(new Interval(70,70)), report.overlaps());
    }
    @Test void disconnectedRangesHaveOpenInternalGaps() {
        var report = analyze(lens(20,35,4.,null), lens(70,180,4.,null));
        assertEquals(List.of(new Interval(20,35),new Interval(70,180)),report.coverage());
        assertEquals(List.of(new Interval(35,70)),report.gaps());
        assertTrue(report.overlaps().isEmpty());
    }
    @Test void primesArePointsIncludingWhenNestedInZooms() {
        var a = lens(50,50,1.4,null); var b = lens(85,85,1.8,null);
        assertEquals(List.of(new Interval(50,50),new Interval(85,85)),analyze(a,b).coverage());
        assertEquals(List.of(new Interval(50,85)),analyze(a,b).gaps());
        assertEquals(List.of(new Interval(50,50)),analyze(a,lens(24,70,2.8,null)).overlaps());
        assertEquals(ApertureKind.PRIME,analyze(a).lenses().getFirst().apertureKind());
    }
    @Test void emptyAndCameraOnlyInventoriesHaveNoInventedNumbers() {
        assertTrue(service.analyze().value().orElseThrow().equipment().isEmpty());
        var report=analyze(camera("E",null,null));
        assertTrue(report.coverage().isEmpty()); assertTrue(report.largestAperture().isEmpty()); assertTrue(report.pairs().isEmpty());
        assertEquals(2L,report.quantities().get(EquipmentType.CAMERA));
    }
    @Test void comparesAperturesWithoutInventingIntermediateValues() {
        var variable=lens(28,200,2.8,5.6); var constant=lens(24,70,4.,4.); var legacy=lens(70,300,4.5,null);
        var report=analyze(variable,constant,legacy);
        assertEquals(2.8, report.largestAperture().orElseThrow());
        assertEquals(ApertureKind.VARIABLE_ENDPOINTS,analyze(variable).lenses().getFirst().apertureKind());
        assertEquals(ApertureKind.CONSTANT,analyze(constant).lenses().getFirst().apertureKind());
        assertEquals(ApertureKind.UNKNOWN_ZOOM,analyze(legacy).lenses().getFirst().apertureKind());
        assertEquals(5.6,analyze(variable).lenses().getFirst().lens().maxApertureAtMaxFocal().orElseThrow());
        assertTrue(CapabilityPresentation.format(report,CapabilityPresentation.Section.APERTURE,com.photopilot.i18n.LocalizationService.english()).contains("Intermediate maximum apertures are unknown"));
    }
    @Test void compatibilityNeverClaimsUnknownCoverageOrAdapters() {
        var lens=lens(50,50,1.8,null);
        assertEquals(MountStatus.MATCH_RECORDED,analyze(camera(" e ",36.,24.),lens).pairs().getFirst().mountStatus());
        assertEquals(MountStatus.DIFFERENT_RECORDED,analyze(camera("RF",36.,24.),lens).pairs().getFirst().mountStatus());
        assertEquals(MountStatus.UNKNOWN,analyze(camera("unknown",null,null),lens).pairs().getFirst().mountStatus());
        assertTrue(analyze(camera("RF",36.,24.),lens).pairs().getFirst().equivalentFocal().isEmpty());
    }
    @Test void equivalentFocalLengthUsesActualSensorDiagonalAndPreservesFNumber() {
        var lens=lens(20,70,2.8,null);
        var report=analyze(camera("E",24.,16.),lens);
        assertEquals(30, report.pairs().getFirst().equivalentFocal().orElseThrow().startMm(), 1e-10);
        assertEquals(105, report.pairs().getFirst().equivalentFocal().orElseThrow().endMm(), 1e-10);
        assertEquals(new Interval(20,70),report.coverage().getFirst());
        assertEquals(2.8,report.largestAperture().orElseThrow());
        assertTrue(analyze(camera("E",24.,null),lens).pairs().getFirst().equivalentFocal().isEmpty());
    }
    @Test void rejectsInvalidSpecsBeforeCalculating() {
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertFalse(service.analyzeItems(List.of(lens(value,70,4.,null))).isSuccess());
            assertFalse(service.analyzeItems(List.of(lens(20,70,value,null))).isSuccess());
            assertFalse(service.analyzeItems(List.of(lens(20,70,4.,value))).isSuccess());
        }
        assertFalse(service.analyzeItems(List.of(lens(70,20,4.,null))).isSuccess());
        assertFalse(service.analyzeItems(List.of(lens(50,50,1.8,2.8))).isSuccess());
        var duplicate=lens(50,50,1.8,null);
        assertFalse(service.analyzeItems(List.of(duplicate,duplicate)).isSuccess());
        assertFalse(EquipmentForm.parse(EquipmentType.LENS,UUID.randomUUID(),Map.of("brand","Test","model","Incomplete","mount","E"),null).isSuccess());
    }
    @Test void f05AddEditDeleteAndRestartAreReflectedWithoutCache() {
        var original=lens(20,70,2.8,null); var inventory=bootstrap.facade().inventory();
        assertTrue(inventory.add(original,false).isSuccess());
        var first=service.analyze().value().orElseThrow();
        assertEquals(new Interval(20,70),first.coverage().getFirst());
        var edited=EquipmentForm.parse(EquipmentType.LENS,original.id(),Map.of("maxFocalMm","200","maxApertureAtMaxFocal","5.6"),original).value().orElseThrow();
        assertTrue(inventory.update(edited,false).isSuccess());
        assertEquals(new Interval(20,200),service.analyze().value().orElseThrow().coverage().getFirst());
        try(var restarted=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.toString(),"PHOTOPILOT_CONFIG_DIR",directory.resolve("config").toString()))) {
            assertEquals(Optional.of(5.6),restarted.facade().capabilities().analyze().value().orElseThrow().lenses().getFirst().lens().maxApertureAtMaxFocal());
        }
        assertTrue(inventory.remove(original.id(),false).isSuccess());
        assertTrue(service.analyze().value().orElseThrow().equipment().isEmpty());
        assertEquals(new Interval(20,70),first.coverage().getFirst());
        assertThrows(UnsupportedOperationException.class,()->first.coverage().clear());
    }
    @Test void oldSchemaRecordsRemainReadableAndUnknownRatherThanConstant() throws Exception {
        assertTrue(bootstrap.facade().inventory().add(lens(24,70,2.8,null),false).isSuccess());
        String json=Files.readString(bootstrap.storageScope().inventoryFile());
        assertFalse(json.contains("maxApertureAtMaxFocal"));
        assertEquals(ApertureKind.UNKNOWN_ZOOM,service.analyze().value().orElseThrow().lenses().getFirst().apertureKind());
    }
    @Test void corruptPersistedInventoryIsReportedAndNeverOverwritten() throws Exception {
        var file=bootstrap.storageScope().inventoryFile(); Files.writeString(file,"{bad inventory}");
        var result=service.analyze(); assertFalse(result.isSuccess());
        assertTrue(result.hasError("error.inventory.read")); assertEquals("{bad inventory}",Files.readString(file));
    }
    @Test void sweepHandlesNestedRangesAndDuplicatePrimesWithoutDoubleCounting() {
        var report=analyze(lens(20,200,4.,null),lens(30,40,2.8,null),lens(60,70,2.8,null),lens(50,50,1.8,null),lens(50,50,1.8,null));
        assertEquals(List.of(new Interval(30,40),new Interval(50,50),new Interval(60,70)),report.overlaps());
        assertEquals(List.of(new Interval(20,200)),report.coverage());
    }
}
