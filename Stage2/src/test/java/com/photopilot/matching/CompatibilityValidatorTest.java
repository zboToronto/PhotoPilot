package com.photopilot.matching;

import com.photopilot.model.*;
import com.photopilot.service.GearMatchingService;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.CompatibilityValidator.Status;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** UC14 deterministic rules on F05 snapshots: compatible, incompatible and unverified relationships. */
class CompatibilityValidatorTest {
    final CompatibilityValidator validator = new CompatibilityValidator();
    final Airshow gear = new Airshow();

    static Loadout loadout(Map<UUID, UUID> mounted, Equipment... items) {
        var snapshots = Arrays.stream(items).map(e -> GearMatchingService.withQuantity(e.snapshot(), e.quantity())).toList();
        return new Loadout(GearMatchingService.candidateId(snapshots), snapshots, mounted, new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of());
    }
    Status status(Equipment first, Equipment second) { return validator.check(first.snapshot(), second.snapshot()).orElseThrow().status(); }

    @Test void matchingMountsAreCompatibleAndAFullLoadoutIsUsable() {
        assertEquals(Status.COMPATIBLE, status(gear.a1, gear.tele));
        assertEquals(Status.COMPATIBLE, status(gear.tele, gear.a1), "pair order does not matter");
        var report = validator.validate(loadout(Map.of(gear.a1.id(), gear.tele.id()), gear.a1, gear.tele, gear.fz, gear.sd));
        assertTrue(report.isUsable(), report.violations() + " " + report.unverified());
        assertTrue(report.unmetNeeds().isEmpty(), "battery and storage are included");
    }

    @Test void differentMountsAreIncompatibleAndNoAdapterOrBrandRuleIsAssumed() {
        assertEquals(Status.INCOMPATIBLE, status(gear.a1, gear.canon));
        var report = validator.validate(loadout(Map.of(gear.a1.id(), gear.canon.id()), gear.a1, gear.canon, gear.fz, gear.sd));
        assertFalse(report.isUsable());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("Canon EF") && v.contains("No adapter")), report.violations().toString());
        // Same manufacturer is not evidence of compatibility either.
        var sonyA = camera("Sony", "A99", "Sony A", 1, 850.0, Set.of("NP-FM500H"), Set.of("SD"), 12.0, true);
        assertEquals(Status.INCOMPATIBLE, status(sonyA, gear.tele));
    }

    @Test void missingMountSpecificationIsUnverifiedNeverCompatible() {
        assertEquals(Status.UNVERIFIED, status(gear.a1, gear.vintage));
        var blank = new GearSnapshot(UUID.randomUUID(), "LENS", "Old", "Lens", 1, Instant.now(), Map.of("mount", " ", "minFocalMm", "50", "maxFocalMm", "50", "maxAperture", "2"), Set.of(), Map.of());
        assertEquals(Status.UNVERIFIED, validator.check(gear.a1.snapshot(), blank).orElseThrow().status());
        var report = validator.validate(loadout(Map.of(), gear.a1, gear.vintage, gear.fz, gear.sd));
        assertFalse(report.isUsable(), "unverified is not usable");
        assertFalse(report.compatible());
        assertTrue(report.violations().isEmpty()); assertFalse(report.unverified().isEmpty());
    }

    @Test void filterThreadMustMatchExactlyAndUnknownThreadIsUnverified() {
        assertEquals(Status.COMPATIBLE, status(gear.zoom, gear.cpl));
        assertEquals(Status.INCOMPATIBLE, status(gear.tele, gear.cpl), "77 mm filter on a 95 mm thread: no step ring is assumed");
        assertEquals(Status.UNVERIFIED, status(gear.vintage, gear.cpl));
        var wrong = validator.validate(loadout(Map.of(gear.a1.id(), gear.tele.id()), gear.a1, gear.tele, gear.cpl, gear.fz, gear.sd));
        assertTrue(wrong.violations().stream().anyMatch(v -> v.contains("77 mm") && v.contains("95 mm")), wrong.violations().toString());
        assertTrue(validator.validate(loadout(Map.of(gear.a1.id(), gear.zoom.id()), gear.a1, gear.zoom, gear.cpl, gear.fz, gear.sd)).isUsable());
    }

    @Test void batteryTypeMustBeListedByTheCameraAndNamesProveNothing() {
        assertEquals(Status.COMPATIBLE, status(gear.a1, gear.fz));
        assertEquals(Status.INCOMPATIBLE, status(gear.a1, gear.canonBattery));
        var unknownTypes = camera("Sony", "A9", "Sony E", 1, 670.0, Set.of(), Set.of("SD"), 20.0, true);
        assertEquals(Status.UNVERIFIED, status(unknownTypes, gear.fz), "no recorded battery list means unverified, even for the same brand");
        var report = validator.validate(loadout(Map.of(gear.a1.id(), gear.zoom.id()), gear.a1, gear.zoom, gear.canonBattery, gear.sd));
        assertFalse(report.isUsable());
        assertTrue(report.violations().stream().anyMatch(v -> v.contains("LP-E6NH")));
    }

    @Test void unsupportedStorageFormatIsIncompatibleAndMissingEssentialsMakeAPartialNotAConflict() {
        var xqd = card("XQD 64GB", "XQD", 1, 64, 400.0, 10.0);
        assertEquals(Status.INCOMPATIBLE, status(gear.a7, xqd));
        assertFalse(validator.validate(loadout(Map.of(gear.a7.id(), gear.zoom.id()), gear.a7, gear.zoom, gear.fz, xqd)).isUsable());
        var noCard = validator.validate(loadout(Map.of(gear.a7.id(), gear.zoom.id()), gear.a7, gear.zoom, gear.fz));
        assertTrue(noCard.isUsable(), "nothing physically conflicts");
        assertFalse(noCard.fullySuitable());
        assertTrue(noCard.unmetNeeds().getFirst().contains("No compatible storage"));
    }

    @Test void availabilityRejectsRemovedChangedAndOverAllocatedItems() {
        var chosen = loadout(Map.of(gear.a1.id(), gear.zoom.id()), gear.a1, gear.zoom, gear.fz, gear.sd);
        assertTrue(validator.availability(chosen, gear.inventory()).isEmpty());
        var without = new GearInventory(gear.items().stream().filter(e -> e != gear.zoom).toList());
        assertEquals("compat.itemMissing", validator.availability(chosen, without).getFirst().key());
        var heavier = new Lens(gear.zoom.id(), "Sony", "FE 100-400mm GM", 1, Optional.of(1500.0), Set.of(), Map.of(), Optional.empty(), "Sony E", 100, 400, 4.5,
            Optional.of(77.0), Optional.of(true), Optional.of(true));
        var edited = new GearInventory(gear.items().stream().map(e -> e == gear.zoom ? heavier : e).toList());
        assertEquals("compat.itemChanged", validator.availability(chosen, edited).getFirst().key());
        var twoBodies = new Loadout(UUID.randomUUID(), List.of(GearMatchingService.withQuantity(gear.a1.snapshot(), 2)), Map.of(),
            new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of());
        assertEquals("compat.quantity", validator.availability(twoBodies, gear.inventory()).getFirst().key(), "only one A1 is owned");
    }

    @Test void capabilityNeedsUseOnlyRecordedValuesAndReportUnknowns() {
        var chosen = loadout(Map.of(gear.a1.id(), gear.tele.id(), gear.a7.id(), gear.zoom.id()), gear.a1, gear.a7, gear.tele, gear.zoom, gear.fz, gear.sd);
        var results = CompatibilityValidator.evaluateNeeds(chosen, airshowProfile());
        assertTrue(results.stream().allMatch(r -> r.status() == GearMatchReport.NeedStatus.MET), results.toString());
        assertEquals(Optional.of(737 + 658 + 2115 + 1395 + 4 * 83 + 2 * 2.0), chosen.totalWeightGrams());
        var withVintage = loadout(Map.of(), gear.a1, gear.vintage);
        assertTrue(withVintage.totalWeightGrams().isEmpty(), "an unrecorded weight makes the total unknown, never zero");
        var weight = CompatibilityValidator.evaluateNeeds(withVintage, new EquipmentRequirementProfile(List.of(need("totalWeight", null, 3000.0, 1, true)), true));
        assertEquals(GearMatchReport.NeedStatus.UNKNOWN, weight.getFirst().status());
        var gap = loadout(Map.of(), gear.a1, gear.tele, gear.standard);
        var focal = CompatibilityValidator.evaluateNeeds(gap, new EquipmentRequirementProfile(List.of(need("focalLength", 100.0, 600.0, 1, true)), true));
        assertEquals(GearMatchReport.NeedStatus.UNMET, focal.getFirst().status(), "24-105 and 200-600 leave a gap");
    }
}
