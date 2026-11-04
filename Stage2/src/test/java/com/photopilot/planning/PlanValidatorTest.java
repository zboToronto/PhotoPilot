package com.photopilot.planning;

import com.photopilot.command.*;
import com.photopilot.model.*;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.state.*;
import com.photopilot.validation.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic F10 gates (PlanValidator, UC14, Command revisions, State) on fixture plans; no model involved. */
class PlanValidatorTest {
    final PlanFixtures.Gear gear = new PlanFixtures.Gear();
    final CompatibilityValidator compatibility = new CompatibilityValidator();
    final TechnicalCalculator calculator = new TechnicalCalculator();
    final PlanValidator validator = new PlanValidator(compatibility, calculator);
    final ShootRequirement req = PlanFixtures.requirement(PlanFixtures.day());
    final EquipmentRequirementProfile profile = PlanFixtures.profile();
    final PlanValidator.Context context = new PlanValidator.Context(Optional.of(profile), Optional.of(new GearInventory(gear.items())),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    Loadout loadout(Lens onA1, Lens onA7) {
        var a = gear.airshow;
        var items = new ArrayList<GearSnapshot>(List.of(a.a1.snapshot(), a.a7.snapshot(), onA1.snapshot(), onA7.snapshot(), a.fz.snapshot(), a.sd.snapshot()));
        return new Loadout(UUID.randomUUID(), items, Map.of(a.a1.id(), onA1.id(), a.a7.id(), onA7.id()),
            new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
    }
    CalculationResult fov(double focal) {
        return calculator.calculate(new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", focal, "sensorWidth", 35.9, "sensorHeight", 24.0),
            Map.of("focalLength", "mm", "sensorWidth", "mm", "sensorHeight", "mm"))).value().orElseThrow();
    }
    ShootPlan plan(Loadout loadout, Map<String, String> settings, List<CalculationResult> calculations) {
        return new ShootPlan(UUID.randomUUID(), 1, false, List.of(), Optional.of(loadout), settings, List.of(), List.of("Hearing protection"),
            List.of("Charge all batteries."), List.of("primary: Fast jets"), PlanValidator.constraintsOf(req, Optional.of(profile)), calculations,
            "Summary", Map.of("subject", req.subject()), Map.of("approach", "Track aircraft."), List.of(), List.of(), List.of(), "");
    }
    ShootPlan valid() { return plan(loadout(gear.airshow.tele, gear.airshow.zoom), Map.of("shutterSpeed", "1/1600-1/3200 s", "iso", "100-3200"), List.of(fov(600))); }
    static Set<String> keys(PlanValidator.Report report) {
        var keys = new HashSet<String>(); report.violations().forEach(m -> keys.add(m.key())); report.missing().forEach(m -> keys.add(m.key())); return keys;
    }

    @Test void compatibleOwnedLoadoutWithValidNumbersPasses() {
        var report = validator.assess(valid(), req, context);
        assertTrue(report.valid(), report.violations()::toString);
        assertTrue(validator.validate(valid(), req, context).isSuccess());
        assertFalse(validator.validate(valid(), req.asDraft(), context).isSuccess(), "unconfirmed requirements are never planned against");
    }

    @Test void incompatibleUnverifiedOrUnownedEquipmentIsRejected() {
        var canon = validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.canon), Map.of(), List.of()), req, context);
        assertTrue(keys(canon).contains("compat.mountMismatch"), canon.violations()::toString);
        var vintage = validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.vintage), Map.of(), List.of()), req, context);
        assertTrue(keys(vintage).contains("compat.mountUnknown"), "unverified is never treated as compatible");
        var stranger = com.photopilot.matching.GearFixtures.camera("Nikon", "Z9", "Nikon Z", 1, 1340.0, Set.of(), Set.of(), 20.0, true);
        var notOwned = validator.assess(plan(new Loadout(UUID.randomUUID(), List.of(stranger.snapshot(), gear.airshow.tele.snapshot()), Map.of(),
            new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of()), Map.of(), List.of()), req, context);
        assertTrue(keys(notOwned).contains("compat.itemMissing"), "only owned inventory may be planned");
        assertTrue(keys(validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.zoom), Map.of(), List.of()).withExplanation("", "", List.of(), List.of(), List.of("x")),
            req, context)).contains("plan.missing.provisional"));
    }

    @Test void confirmedCameraCountLensChangeAndCapabilityLimitsAreEnforced() {
        var a = gear.airshow;
        var oneBody = new Loadout(UUID.randomUUID(), List.of(a.a1.snapshot(), a.tele.snapshot(), a.zoom.snapshot()), Map.of(a.a1.id(), a.tele.id()),
            new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
        var report = validator.assess(plan(oneBody, Map.of(), List.of()), req, context);
        assertTrue(keys(report).containsAll(Set.of("plan.violation.cameraCount", "plan.violation.lensChanges")), report.violations()::toString);
        var shortReach = validator.assess(plan(loadout(a.standard, gear.lighter), Map.of(), List.of()), req, context);
        assertTrue(keys(shortReach).contains("plan.violation.need"), "mandatory 100-600 mm need not met");
        var changed = new ShootRequirement(req.originalText(), req.subject(), req.goals(), req.time(), req.date(), req.timeWindow(), req.location(), req.duration(),
            Optional.of(2), Optional.of(false), Optional.of(2000.0), req.portability(), req.gearConstraints(), req.priorities(), req.fixedConstraints(), List.of(), true);
        var heavy = validator.assess(valid(), changed, context);
        assertTrue(keys(heavy).containsAll(Set.of("plan.violation.weight", "plan.violation.constraintsChanged")), heavy.violations()::toString);
    }

    @Test void impossibleSettingsAndAlteredCalculationsAreRejected() {
        var tooWide = validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.zoom), Map.of(ShootPlan.settingKey("aperture", Optional.of(gear.airshow.a1.id())), "f/4"), List.of()), req, context);
        assertTrue(keys(tooWide).contains("plan.violation.aperture"), "the 200-600 mm on the A1 opens only to f/5.6");
        var settings = Map.of("iso", "100-100000000", "shutterSpeed", "1/0 s", "focalLength", "800 mm", "exposureCompensation", "+12 EV", "warpDrive", "on");
        var impossible = validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.zoom), settings, List.of()), req, context);
        assertEquals(3, impossible.violations().stream().filter(m -> m.key().equals("plan.violation.settingRange")).count(), impossible.violations()::toString);
        assertTrue(keys(impossible).containsAll(Set.of("plan.violation.focal", "plan.violation.settingName")), impossible.violations()::toString);
        var descriptive = validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.zoom), Map.of("iso", "Auto"), List.of()), req, context);
        assertTrue(descriptive.valid()); assertEquals("plan.unverified.setting", descriptive.unverified().getFirst().key(), "descriptive values are flagged, not guessed");
        var real = fov(600);
        var forged = new CalculationResult(Map.of("Horizontal field of view", 1.0), real.units(), real.estimated(), real.assumptions(), real.input());
        var mismatch = validator.assess(plan(loadout(gear.airshow.tele, gear.airshow.zoom), Map.of(), List.of(forged)), req, context);
        assertTrue(keys(mismatch).contains("plan.violation.calculationMismatch"), "AI-style numbers never replace F08 results");
    }

    @Test void commandRevisionPreservesUnaffectedDecisionsAndNeverTouchesTheApprovedBase() {
        var base = valid().approvedCopy().value().orElseThrow();
        var editor = new PlanEditor(validator);
        var start = ZonedDateTime.of(PlanFixtures.day(), LocalTime.of(14, 0), ZoneId.of("America/Toronto"));
        var later = List.of(new ShootingWindow(start.plusHours(1), start.plusHours(2), start.plusMinutes(45), "Later display", true));
        var preview = editor.preview(base, new ReviseScheduleCommand(later));
        assertTrue(preview.isSuccess(), preview.errors()::toString);
        var draft = preview.value().orElseThrow();
        assertEquals(base.id(), draft.id()); assertEquals(2, draft.revision()); assertFalse(draft.approved());
        assertEquals(List.of("schedule"), draft.differingSections(base));
        assertEquals(base.settings(), draft.settings()); assertEquals(base.loadout(), draft.loadout()); assertEquals(base.fixedConstraints(), draft.fixedConstraints());
        assertTrue(base.windows().isEmpty(), "the saved base is unchanged by the preview");
        assertTrue(new ReviseScheduleCommand(later).applyTo(base).hasError("plan.error.immutable"), "commands never change an approved plan");
        var strategy = editor.preview(base, new ReviseStrategyCommand(Map.of(), Map.of("approach", "Prioritize close-ups."), List.of("primary: Close-up passes"),
            List.of(), List.of(), List.of(), Set.of("strategy", "shotPriorities")));
        assertTrue(strategy.isSuccess(), strategy.errors()::toString);
        assertEquals(base.settings(), strategy.value().orElseThrow().settings(), "settings were not named and stay the same");
    }

    @Test void revisionGateRejectsFixedConstraintChangesUndeclaredChangesIncompatibleGearAndWrongRevisions() {
        var base = valid().approvedCopy().value().orElseThrow();
        var incompatible = new PlanEditor(validator).preview(base, new ReplaceLoadoutCommand(loadout(gear.airshow.tele, gear.airshow.canon)));
        assertFalse(incompatible.isSuccess());
        assertTrue(incompatible.hasError("compat.mountMismatch"), "an equipment-changing revision re-runs UC14");
        var relaxed = new HashMap<>(base.fixedConstraints()); relaxed.put("cameraCount", "1");
        var sneaky = new ShootPlan(base.id(), 2, false, List.of(), base.loadout(), base.settings(), base.windows(), List.of("Something else"), base.preparation(),
            base.shotPriorities(), relaxed, base.calculations(), base.summary(), base.sessionInfo(), base.strategy(), base.equipmentNotes(), base.tradeOffs(), base.limitations(), "");
        var gate = validator.validateChange(base, sneaky);
        assertTrue(gate.hasError("plan.violation.fixedChanged") && gate.hasError("plan.violation.undeclared"), gate.errors()::toString);
        var skipped = new ShootPlan(base.id(), 3, false, List.of(), base.loadout(), base.settings(), base.windows(), base.checklist(), base.preparation(),
            base.shotPriorities(), base.fixedConstraints(), base.calculations());
        assertTrue(validator.validateChange(base, skipped).hasError("plan.violation.revisionOrder"));
        var foreign = new ShootPlan(UUID.randomUUID(), 2, false, List.of(), base.loadout(), base.settings(), base.windows(), base.checklist(), base.preparation(),
            base.shotPriorities(), base.fixedConstraints(), base.calculations());
        assertTrue(validator.validateChange(base, foreign).hasError("plan.violation.foreign"));
        assertTrue(new PlanEditor(validator).preview(base, new ReviseScheduleCommand(base.windows())).hasError("plan.error.noChange"));
    }

    @Test void stateAllowsOnlyTheFirstValidApprovalToPlanASession() {
        var now = Instant.parse("2026-11-01T10:00:00Z");
        var draft = new PhotographySession(UUID.randomUUID(), "Airshow", PlanFixtures.day(), Optional.empty(), "", now, now, new DraftState(),
            Optional.of(req), List.of(), List.of(), Optional.of(profile), Optional.empty(), List.of());
        assertTrue(draft.apply(SessionEvent.PLAN_APPROVED).hasError("error.session.planRequired"), "a proposal alone never plans a session");
        assertTrue(draft.appendApprovedPlan(valid()).hasError("plan.error.notApproved"));
        var appended = PlanFixtures.ok(draft.appendApprovedPlan(valid().approvedCopy().value().orElseThrow()));
        var planned = PlanFixtures.ok(appended.apply(SessionEvent.PLAN_APPROVED));
        assertEquals("PLANNED", planned.status());
        assertTrue(planned.apply(SessionEvent.PLAN_APPROVED).hasError("error.session.transition"), "Planned has no second PLAN_APPROVED transition");
        assertTrue(planned.apply(SessionEvent.REVIEW_SAVED).hasError("error.session.transition"));
        var stale = valid().approvedCopy().value().orElseThrow();
        var once = PlanFixtures.ok(planned.appendApprovedPlan(stale));
        assertTrue(once.appendApprovedPlan(stale).hasError("plan.error.stale"), "the same revision is never appended twice");
        assertThrows(IllegalStateException.class, () -> stale.withExplanation("", "", List.of(), List.of(), List.of()), "approved plans are immutable");
    }
}
