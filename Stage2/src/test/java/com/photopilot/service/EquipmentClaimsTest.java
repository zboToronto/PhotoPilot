package com.photopilot.service;

import com.google.gson.JsonParser;
import com.photopilot.command.ShootPlan;
import com.photopilot.model.*;
import com.photopilot.service.EquipmentClaims.Evidence;
import com.photopilot.service.EquipmentClaims.Names;
import com.photopilot.service.EquipmentClaims.Verdict;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 PP-03: the saved review G17/C64 said "The recorded camera was SONY ILCE-7M4 for 2 of 2 photos (100.0%),
 * differing from the planned Sony Alpha 7 IV." Its figures were right, so the figure check kept it, although the camera
 * catalog lists both names for one model. Equipment identity is now checked on its own, before a draft is shown or saved.
 */
class EquipmentClaimsTest {
    /** The audit's evidence: planned Alpha 7 IV + FE 55mm F1.8 ZA; two photos as SONY ILCE-7M4, one lens recorded as "Audit 50". */
    static final Evidence AUDIT = new Evidence(new Names(List.of("Sony Alpha 7 IV"), List.of("SONY ILCE-7M4")),
        new Names(List.of("Sony FE 55mm F1.8 ZA"), List.of("Audit 50")));
    static final String CAMERA_CLAIM = "The recorded camera was SONY ILCE-7M4 for 2 of 2 photos (100.0%), differing from the planned Sony Alpha 7 IV.";
    static final String LENS_CLAIM = "The recorded lens was Audit 50 for 1 of 1 photos with lens data (100.0%), differing from the planned Sony FE 55mm F1.8 ZA.";

    static Verdict verdict(String text, Evidence evidence) { return EquipmentClaims.check(text, evidence).verdict(); }
    static Evidence cameras(String planned, String recorded) {
        return new Evidence(new Names(List.of(planned), List.of(recorded)), new Names(List.of(), List.of()));
    }

    @Test void theAuditStatementsAreRejectedWithTheirReason() {
        var camera = EquipmentClaims.check(CAMERA_CLAIM, AUDIT);
        assertEquals(Verdict.CONTRADICTED, camera.verdict(), "one catalogued model is not different equipment");
        assertEquals("SONY ILCE-7M4", camera.recorded()); assertEquals("Sony Alpha 7 IV", camera.planned());
        assertTrue(camera.comparison().orElseThrow().alias());
        assertEquals(Verdict.UNSUPPORTED, verdict(LENS_CLAIM, AUDIT), "an undetermined lens identity cannot support a model difference");
        assertEquals(Verdict.CONTRADICTED, verdict("Photos were taken with the Sony α7 IV rather than the planned Sony Alpha 7 IV.", AUDIT));
        assertEquals(Verdict.CONTRADICTED, verdict("A different camera than planned was used for every photo.", AUDIT), "a generic claim relates all pairs");
        assertEquals(Verdict.CONTRADICTED, verdict("SONY ILCE-7M4 is a different camera from what the plan listed.", AUDIT));
    }

    @Test void chineseDifferenceAndMatchClaimsAreCheckedToo() {
        assertEquals(Verdict.CONTRADICTED, verdict("记录的相机为 SONY ILCE-7M4，与计划中的 Sony Alpha 7 IV 不同。", AUDIT));
        assertEquals(Verdict.CONTRADICTED, verdict("照片使用 SONY ILCE-7M4 拍摄，而非计划的 Sony Alpha 7 IV。", AUDIT));
        assertEquals(Verdict.SUPPORTED, verdict("记录的 SONY ILCE-7M4 即计划中的 Sony Alpha 7 IV。", AUDIT));
        assertEquals(Verdict.UNSUPPORTED, verdict("所有照片都由同一台机身拍摄。", AUDIT));
    }

    @Test void statementsWithoutAnIdentityClaimAreLeftAlone() {
        for (String text : List.of("The recorded camera was SONY ILCE-7M4 for 2 of 2 photos (100.0%).",
                "ISO differed from the planned ISO 400 on the SONY ILCE-7M4.",
                "Focal lengths of 50 mm and 85 mm differ from the planned lens range of 55 mm.",
                "One photo out of 2 has no recorded lens model.",
                "Make sure every body records lens metadata so lens use can be compared.",
                "SONY ILCE-7M4 is another name for the planned Sony Alpha 7 IV, the same camera model."))
            assertEquals(Verdict.NONE, verdict(text, AUDIT), text);
    }

    @Test void supportedClaimsAreKept() {
        // Name-level wording is true; an alias explanation is true; a catalogued difference is a real difference.
        assertEquals(Verdict.SUPPORTED, verdict("The recorded name SONY ILCE-7M4 differs from the planned name Sony Alpha 7 IV.", AUDIT));
        assertEquals(Verdict.SUPPORTED, verdict("SONY ILCE-7M4 differs only in name from the planned Sony Alpha 7 IV; it is the same camera model.", AUDIT));
        assertEquals(Verdict.SUPPORTED, verdict("SONY ILCE-7M4 matches the planned Sony Alpha 7 IV.", AUDIT));
        var different = cameras("Sony A1", "SONY ILCE-7M4");
        assertEquals(Verdict.SUPPORTED, verdict("The recorded SONY ILCE-7M4 differs from the planned Sony A1.", different));
        assertEquals(Verdict.SUPPORTED, verdict("SONY ILCE-7M4 was used instead of the planned Sony A1.", different));
        assertEquals(Verdict.SUPPORTED, verdict("It is unclear whether the recorded lens Audit 50 differs from the planned Sony FE 55mm F1.8 ZA.", AUDIT),
            "honest uncertainty about an undetermined identity");
    }

    @Test void matchClaimsNeedTheSameModel() {
        assertEquals(Verdict.CONTRADICTED, verdict("SONY ILCE-7M4 matches the planned Sony A1.", cameras("Sony A1", "SONY ILCE-7M4")));
        assertEquals(Verdict.UNSUPPORTED, verdict("SONY ILCE-7M4 matches the planned Sony A7 Mark IV.", cameras("Sony A7 Mark IV", "SONY ILCE-7M4")),
            "a spelling the catalog does not list proves no match");
        assertEquals(Verdict.UNSUPPORTED, verdict("SONY ILCE-7M4 differs from the planned Sony A7 Mark IV.", cameras("Sony A7 Mark IV", "SONY ILCE-7M4")),
            "nor a difference");
    }

    @Test void claimsAboutTheIndividualBodyAreNeverSupportedByModelNames() {
        assertEquals(Verdict.UNSUPPORTED, verdict("All photos were taken with the same camera body.", AUDIT));
        assertTrue(EquipmentClaims.check("Both photos come from the same physical camera.", AUDIT).body());
        assertEquals(Verdict.UNSUPPORTED, verdict("The serial numbers show your own SONY ILCE-7M4 was used.", AUDIT));
        assertEquals(Verdict.NONE, verdict("EXIF cannot show whether the same camera body was used for both photos.", AUDIT), "a stated limit");
    }

    @Test void missingEquipmentDataChecksNothing() {
        var noRecorded = new Evidence(new Names(List.of("Sony Alpha 7 IV"), List.of()), new Names(List.of(), List.of()));
        assertEquals(Verdict.NONE, verdict(CAMERA_CLAIM, noRecorded), "no recorded camera: the figure check decides");
        assertEquals(Verdict.NONE, verdict(CAMERA_CLAIM, Evidence.NONE));
        assertEquals(Verdict.NONE, verdict("", AUDIT)); assertEquals(Verdict.NONE, verdict(null, AUDIT));
    }

    // ---- through the real F11 evidence and claim check -------------------------------------------------------------

    static ExifStatistics auditStatistics() {
        var missing = new HashMap<String, Integer>(Map.of("lens", 1, "exposureCompensation", 2, "captureTime", 2));
        return new ExifStatistics(2, 2, missing, Map.of(50.0, 1, 85.0, 1), Map.of(1.0 / 125, 1, 1.0 / 250, 1), Map.of(4.0, 1, 8.0, 1),
            Map.of(200, 1, 400, 1), Map.of("SONY ILCE-7M4", 2), Map.of("Audit 50", 1), List.of());
    }
    static ShootPlan auditPlan() {
        var now = Instant.parse("2026-11-20T12:00:00Z");
        var camera = new GearSnapshot(UUID.randomUUID(), "CAMERA", "Sony", "Alpha 7 IV", 1, now, Map.of(), Set.of(), Map.of());
        var lens = new GearSnapshot(UUID.randomUUID(), "LENS", "Sony", "FE 55mm F1.8 ZA", 1, now, Map.of("minFocalMm", "55", "maxFocalMm", "55"), Set.of(), Map.of());
        var loadout = new Loadout(UUID.randomUUID(), List.of(camera, lens), Map.of(camera.sourceGearId(), lens.sourceGearId()),
            new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
        return new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.of(loadout), Map.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }
    static ReviewInterpretation equipment(String... statements) {
        return new ReviewInterpretation("", List.of(), List.of(statements), List.of(), List.of(), List.of(), List.of());
    }

    @Test void correctFiguresNoLongerCarryAWrongEquipmentClaimIntoTheDraft() {
        var prepared = ReviewClaimsTest.prepare(auditStatistics(), auditPlan());
        // Before the fix: the figure check alone (no identity evidence) kept both statements.
        var figuresOnly = new ReviewClaims.Evidence(prepared.claims().facts(), prepared.claims().verbatim(), prepared.claims().names());
        var before = ReviewEvidence.check(equipment(CAMERA_CLAIM, LENS_CLAIM), figuresOnly);
        assertEquals(List.of(CAMERA_CLAIM, LENS_CLAIM), before.equipment(), "the figures are all supported");
        assertEquals(0, before.removed());
        // Now both are removed and counted as identity removals; a correct statement in the same reply is kept.
        String kept = "The recorded camera was SONY ILCE-7M4 for 2 of 2 photos (100.0%).";
        var after = ReviewEvidence.check(equipment(CAMERA_CLAIM, LENS_CLAIM, kept), prepared.claims());
        assertEquals(List.of(kept), after.equipment());
        assertEquals(2, after.removed()); assertEquals(2, after.identityRemoved());
        // History evidence keeps the identity names (the check runs on the evidence the agent extended).
        assertEquals(List.of(kept), ReviewEvidence.check(equipment(CAMERA_CLAIM, kept), prepared.claims().withHistory("{}")).equipment());
    }

    @Test void theModelIsToldTheDeterministicIdentityAndTheComparisonSaysTheNamesMatch() {
        var prepared = ReviewClaimsTest.prepare(auditStatistics(), auditPlan());
        var identity = JsonParser.parseString(prepared.json()).getAsJsonObject().getAsJsonObject("equipmentIdentity");
        var pairs = identity.getAsJsonArray("pairs");
        assertEquals(2, pairs.size());
        var camera = pairs.get(0).getAsJsonObject();
        assertEquals("SONY ILCE-7M4", camera.get("recorded").getAsString()); assertEquals("Sony Alpha 7 IV", camera.get("planned").getAsString());
        assertEquals("same model", camera.get("relation").getAsString()); assertEquals("Sony α7 IV (ILCE-7M4)", camera.get("catalogModel").getAsString());
        assertEquals("not determined", pairs.get(1).getAsJsonObject().get("relation").getAsString(), "Audit 50 vs FE 55mm F1.8 ZA");
        assertTrue(identity.get("note").getAsString().contains("never an individual camera body"));
        assertTrue(ReviewEvidence.TASK.contains("equipmentIdentity"), "the instruction names the evidence it must respect");
        var comparison = String.join("\n", ReviewClaimsTest.CALCULATOR.compare(auditStatistics(), auditPlan()));
        assertTrue(comparison.contains("Planned camera Sony Alpha 7 IV: 2 of 2 photos with camera data (100.0%), recorded as SONY ILCE-7M4."), comparison);
        assertTrue(comparison.contains("Whether recorded lens Audit 50 is the same model as the planned Sony FE 55mm F1.8 ZA cannot be determined"), comparison);
        assertFalse(comparison.contains("Recorded camera SONY ILCE-7M4"), comparison);
    }

    @Test void savedReviewsAreAuditedWithoutBeingChanged() {
        var stats = auditStatistics(); var plan = auditPlan();
        var review = new PostShootReview(UUID.randomUUID(), Instant.parse("2026-11-23T19:30:00Z"), stats, plan.id(), 1, List.of(),
            List.of("Lesson"), List.of("Limit"));
        var legacy = withEquipment(review, List.of(CAMERA_CLAIM, "One photo out of 2 has no recorded lens model."));
        var session = new PhotographySession(UUID.randomUUID(), "Portrait", java.time.LocalDate.of(2026, 12, 12), Optional.empty(), "",
            Instant.parse("2026-11-20T12:00:00Z"), Instant.parse("2026-11-23T19:30:00Z"), new com.photopilot.state.ReviewedState(), Optional.empty(),
            List.of(plan), List.of(legacy), Optional.empty(), Optional.of(stats), List.of());
        var conflicts = EquipmentClaims.audit(legacy, session);
        assertEquals(1, conflicts.size());
        assertEquals("equipment", conflicts.getFirst().section()); assertEquals(CAMERA_CLAIM, conflicts.getFirst().statement());
        assertEquals(List.of(CAMERA_CLAIM, "One photo out of 2 has no recorded lens model."), legacy.equipmentObservations(), "nothing is rewritten");
        // A review compared with another plan revision is judged against that revision only (none here: nothing to relate).
        var otherRevision = new PhotographySession(session.id(), session.title(), session.date(), Optional.empty(), "", session.createdAt(), session.updatedAt(),
            new com.photopilot.state.ReviewedState(), Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.of(stats), List.of());
        assertTrue(EquipmentClaims.audit(legacy, otherRevision).isEmpty());
    }
    static PostShootReview withEquipment(PostShootReview r, List<String> equipment) {
        return new PostShootReview(r.id(), r.createdAt(), r.statistics(), r.comparedPlanId(), r.comparedRevision(), r.planDifferences(), r.lessons(),
            r.limitations(), Optional.empty(), "", List.of(), equipment, List.of(), List.of(), r.evidence(), PostShootReview.SAVED);
    }
}
