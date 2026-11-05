package com.photopilot.matching;

import com.photopilot.model.*;
import com.photopilot.model.GearCapabilityAnalysis.MountStatus;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.GearCapabilityService;
import com.photopilot.service.GearMatchingService;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.PlannedState;
import com.photopilot.strategy.*;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.CompatibilityValidator.Status;
import com.photopilot.validation.SpecificationValues;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** AUD-002: placeholder specifications are unknown everywhere, so matching placeholders never prove compatibility. */
class UnknownSpecificationTest {
    @TempDir Path root;
    final CompatibilityValidator validator = new CompatibilityValidator();
    static final List<String> PLACEHOLDERS = List.of("unknown", "n/a", "N/A", " none ", "-", "?", "未知", "不详", "？", "N / A", "Other");

    static Camera body(String mount, Set<String> batteries, Set<String> storage) { return camera("Fixture", "Body " + mount, mount, 1, 600.0, batteries, storage, 10.0, true); }
    static Lens optic(String mount) { return lens("Fixture", "Lens " + mount, mount, 24, 70, 2.8, 77.0, 500.0, true); }
    Status status(Equipment first, Equipment second) { return validator.check(first.snapshot(), second.snapshot()).orElseThrow().status(); }

    @Test void sharedPolicyRecognisesPlaceholdersButNotRealValues() {
        for (String value : PLACEHOLDERS) assertTrue(SpecificationValues.unknown(value), value);
        assertTrue(SpecificationValues.unknown(null)); assertTrue(SpecificationValues.unknown("  "));
        for (String value : List.of("Sony E", "E", "RF", "NP-FZ100", "SD", "CFexpress Type B", "Nikon Z")) assertFalse(SpecificationValues.unknown(value), value);
    }

    @Test void identicalOrDifferentPlaceholderMountsAreUnverifiedNeverCompatibleOrConflicting() {
        for (String placeholder : PLACEHOLDERS) {
            assertEquals(Status.UNVERIFIED, status(body(placeholder, Set.of("NP-FZ100"), Set.of("SD")), optic(placeholder)), "identical " + placeholder);
            assertEquals(Status.UNVERIFIED, status(body(placeholder, Set.of(), Set.of()), optic("Sony E")), "unknown body vs known lens " + placeholder);
            assertEquals(Status.UNVERIFIED, status(body("Sony E", Set.of(), Set.of()), optic(placeholder)), "known body vs unknown lens " + placeholder);
        }
        assertEquals(Status.UNVERIFIED, status(body("n/a", Set.of(), Set.of()), optic("未知")), "two different placeholders are not a mismatch either");
    }

    @Test void knownMountsKeepTheirCaseAndSpacingInsensitiveRule() {
        assertEquals(Status.COMPATIBLE, status(body("Sony E", Set.of(), Set.of()), optic("  sony e ")));
        assertEquals(Status.INCOMPATIBLE, status(body("Sony E", Set.of(), Set.of()), optic("Canon RF")));
    }

    @Test void batteryAndStorageListsIgnorePlaceholderEntries() {
        var placeholderLists = body("Sony E", Set.of("n/a"), Set.of("未知"));
        assertEquals(Status.UNVERIFIED, status(placeholderLists, battery("Spare", "n/a", 1, 80.0)));
        assertEquals(Status.UNVERIFIED, status(placeholderLists, card("Card", "未知", 1, 64, 90.0, 2.0)));
        var mixed = body("Sony E", Set.of("NP-FZ100", "unknown"), Set.of("SD", "-"));
        assertEquals(Status.COMPATIBLE, status(mixed, battery("FZ", " np-fz100 ", 1, 83.0)));
        assertEquals(Status.UNVERIFIED, status(mixed, battery("Mystery", "unknown", 1, 80.0)), "a listed placeholder never matches");
        assertEquals(Status.UNVERIFIED, status(mixed, card("Mystery", "-", 1, 64, 90.0, 2.0)));
        assertEquals(Status.INCOMPATIBLE, status(mixed, card("XQD", "XQD", 1, 64, 400.0, 10.0)));
    }

    @Test void f07NeverRecommendsAPlaceholderPairing() {
        var a = body("n/a", Set.of("n/a"), Set.of("n/a")); var l = optic("n/a");
        var inventory = new GearInventory(List.of(a, l, battery("Spare", "n/a", 2, 80.0), card("Card", "n/a", 1, 64, 90.0, 2.0)));
        var agent = agent(root, new ArrayList<>(), context -> com.photopilot.common.Result.failureKey("ai.error.network"));
        var service = new GearMatchingService(validator, new PortabilityRanking(agent), List.of(new CoverageRanking(agent)));
        var profile = new EquipmentRequirementProfile(List.of(need("focalLength", 24.0, 70.0, 1, true)), true);
        var report = service.analyze(profile, inventory, "portability", "");
        assertTrue(report.isSuccess(), () -> report.errors().toString());
        var found = report.value().orElseThrow();
        assertTrue(found.recommendations().isEmpty(), "nothing unverified is offered: " + found.recommendations());
        assertTrue(found.rejectedCombinations() > 0);
        assertTrue(found.rejections().stream().anyMatch(m -> m.key().equals("compat.mountUnknown")), found.rejections().toString());
        var loadout = new Loadout(UUID.randomUUID(), List.of(a.snapshot(), l.snapshot()), Map.of(a.id(), l.id()),
            new CompatibilityReport(false, List.of(), List.of(), List.of()), List.of(), List.of());
        assertFalse(validator.assess(loadout).usable());
        assertTrue(validator.assess(loadout).unverified().stream().anyMatch(m -> m.key().equals("compat.mountUnknown")));
    }

    @Test void f06InventoryReportUsesTheSamePolicy() {
        try (var app = new com.photopilot.config.ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()))) {
            GearCapabilityService service = app.facade().capabilities();
            for (String placeholder : PLACEHOLDERS) {
                var pairs = service.analyzeItems(List.of(body(placeholder, Set.of(), Set.of()), optic(placeholder))).value().orElseThrow().pairs();
                assertEquals(MountStatus.UNKNOWN, pairs.getFirst().mountStatus(), placeholder);
            }
            var known = service.analyzeItems(List.of(body("Sony E", Set.of(), Set.of()), optic(" sony e"))).value().orElseThrow().pairs();
            assertEquals(MountStatus.MATCH_RECORDED, known.getFirst().mountStatus());
        }
    }

    @Test void storedPlansWithPlaceholderGearStayReadableAndUnchangedButFailNewValidation() throws Exception {
        var a = body("n/a", Set.of(), Set.of()); var l = optic("n/a");
        var session = SessionFixtures.linked(new PlannedState(), List.of(a.snapshot(), l.snapshot()), false);
        var repository = new JsonSessionRepository(root.resolve("sessions"));
        var saved = repository.insert(session).value().orElseThrow();
        byte[] before = Files.readAllBytes(root.resolve("sessions/sessions.json"));
        var loaded = repository.find(saved.id()).value().orElseThrow();
        assertArrayEquals(before, Files.readAllBytes(root.resolve("sessions/sessions.json")), "reading never rewrites");
        var historical = loaded.plans().getFirst().loadout().orElseThrow();
        assertEquals(saved.plans().getFirst().id(), loaded.plans().getFirst().id());
        assertEquals(saved.plans().getFirst().loadout().orElseThrow().gear().stream().map(g -> List.of(g.sourceGearId(), g.specs())).toList(),
            historical.gear().stream().map(g -> List.of(g.sourceGearId(), g.specs())).toList(), "historical plan kept as approved");
        assertEquals("n/a", historical.gear().getFirst().specs().get("mount"), "no value substituted");
        assertTrue(historical.compatibility().isUsable(), "the stored report is history, not recomputed");
        assertFalse(validator.assess(historical).usable(), "new operations revalidate and see the unknown mount");
    }
}
