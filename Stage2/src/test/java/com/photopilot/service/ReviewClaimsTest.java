package com.photopilot.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.command.ShootPlan;
import com.photopilot.model.ExifStatistics;
import com.photopilot.model.ShootRequirement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2Review1 (medium): F11 figures must be bound to the fact they describe. Before, ReviewEvidence.check accepted a
 * statement when each of its numbers occurred anywhere in the evidence text or the earlier sessions' facts, so "focal
 * length 400 mm" passed on ISO 400, a planned value passed as a recorded one and another session's figure passed as this
 * session's. Hand-built statistics and plans, the real ReviewEvidence evidence and check; no model or file access.
 */
class ReviewClaimsTest {
    static final ShootRequirement REQUIREMENT = new ShootRequirement("Street portraits", "Street portraits", List.of("Shallow depth of field"), Optional.empty(),
        Optional.of(LocalDate.of(2026, 11, 14)), "", Optional.empty(), Optional.of(Duration.ofHours(2)), Optional.of(1), Optional.of(true), Optional.empty(), "",
        List.of(), Map.of(), Map.of(), List.of(), true);
    static final StatisticsCalculator CALCULATOR = new StatisticsCalculator();
    /** The review example: ISO 400, 85 mm, 1/1000 s, f/2.8 on all four photos. */
    static final ExifStatistics EXAMPLE = stats(Map.of(85.0, 4), Map.of(0.001, 4), Map.of(2.8, 4), Map.of(400, 4));
    /**
     * Session A, seven usable photos: focal 15/50x5/85 mm (mean and median 50, mode 50, max 85), 1/250 s x4 and 1/1000 s x3,
     * f/2.8 x5 and f/4 x2, ISO 640 x2 (2 of 7 = 28.571%) and ISO 800 x5 (median 800, mean 754.29).
     */
    static final ExifStatistics SESSION_A = stats(Map.of(15.0, 1, 50.0, 5, 85.0, 1), Map.of(0.004, 4, 0.001, 3), Map.of(2.8, 5, 4.0, 2), Map.of(640, 2, 800, 5));
    /** Earlier session B: 85 mm on 4 of 6 photos, ISO 1600 most often; its saved advice mentions 1/2000 s. */
    static final String HISTORY = """
        {"relatedHistory":[{"subject":"Street portraits","date":"2026-10-12","state":"REVIEWED","approvedRevision":2,"plannedEquipment":["1 x Sony A7 IV"],
        "shotPriorities":[],"savedReviews":[{"reviewedPlanRevision":2,"savedOn":"2026-10-13","facts":{"photosImported":6,"photosWithUsableExif":6,
        "mostUsedFocalLengthsMm":[{"value":"85.0","photos":4},{"value":"35.0","photos":2}],"mostUsedApertures":[{"value":"1.8","photos":6}],
        "mostUsedIso":[{"value":"1600","photos":4}]},"pastAdvice":{"lessons":["Try 1/2000 s for passing cyclists."],"improvements":[]}}]}]}""";

    static ExifStatistics stats(Map<Double, Integer> focal, Map<Double, Integer> shutter, Map<Double, Integer> aperture, Map<Integer, Integer> iso) {
        int n = List.of(sum(focal), sum(shutter), sum(aperture), sum(iso)).stream().mapToInt(Integer::intValue).max().orElseThrow();
        var missing = new HashMap<String, Integer>();
        missing.put("focalLength", n - sum(focal)); missing.put("shutter", n - sum(shutter)); missing.put("aperture", n - sum(aperture)); missing.put("iso", n - sum(iso));
        missing.put("exposureCompensation", n); missing.put("captureTime", n);
        return new ExifStatistics(n, n, missing, focal, shutter, aperture, iso, Map.of("Sony A7 IV", n), Map.of("Sony FE 24-105mm G", n), List.of());
    }
    static int sum(Map<?, Integer> counts) { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    static ShootPlan plan(Map<String, String> settings) {
        return new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.empty(), settings, List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }
    static ReviewEvidence.Prepared prepare(ExifStatistics stats, ShootPlan plan) {
        return ReviewEvidence.prepare(stats, plan, REQUIREMENT, CALCULATOR.differences(stats, plan), CALCULATOR.limitations(stats));
    }
    static ReviewEvidence.Prepared prepare(ExifStatistics stats) { return prepare(stats, plan(Map.of())); }
    static ReviewInterpretation observation(String text) { return new ReviewInterpretation("", List.of(text), List.of(), List.of(), List.of(), List.of(), List.of()); }
    static ReviewInterpretation advice(String text) { return new ReviewInterpretation("", List.of(), List.of(), List.of(), List.of(), List.of(text), List.of()); }
    static boolean kept(String observation, ReviewEvidence.Prepared prepared, String history) {
        return !ReviewEvidence.check(observation(observation), prepared.claims().withHistory(history)).technical().isEmpty();
    }
    static boolean kept(String observation, ReviewEvidence.Prepared prepared) { return kept(observation, prepared, ""); }
    static boolean advised(String text, ReviewEvidence.Prepared prepared) {
        return !ReviewEvidence.check(advice(text), prepared.claims()).improvements().isEmpty();
    }
    static JsonObject json(ReviewEvidence.Prepared prepared) { return JsonParser.parseString(prepared.json()).getAsJsonObject(); }
    /** Id of statistics.{field} (the field's summary record). */
    static String record(ReviewEvidence.Prepared prepared, String field) { return json(prepared).getAsJsonObject("statistics").getAsJsonObject(field).get("id").getAsString(); }
    /** Id of the mostFrequentValues item of a field showing {@code shown}, e.g. "ISO 400". */
    static String value(ReviewEvidence.Prepared prepared, String field, String shown) {
        for (JsonElement item : json(prepared).getAsJsonObject("statistics").getAsJsonObject(field).getAsJsonArray("mostFrequentValues"))
            if (item.getAsJsonObject().get("value").getAsString().equals(shown)) return item.getAsJsonObject().get("id").getAsString();
        throw new AssertionError("no value " + shown);
    }
    static String setting(ReviewEvidence.Prepared prepared, String name) {
        for (JsonElement item : json(prepared).getAsJsonObject("approvedPlan").getAsJsonArray("settings"))
            if (item.getAsJsonObject().get("setting").getAsString().equals(name)) return item.getAsJsonObject().get("id").getAsString();
        throw new AssertionError("no setting " + name);
    }

    // A
    @Test void aFigureOfTheRightMetricIsSupportedWithOrWithoutItsCitation() {
        var p = prepare(EXAMPLE);
        assertTrue(kept("ISO 400 was recorded.", p));
        assertTrue(kept("ISO 400 was recorded [" + value(p, "iso", "ISO 400") + "].", p));
        var shown = ReviewEvidence.check(observation("ISO 400 was recorded [" + value(p, "iso", "ISO 400") + "]."), p.claims()).technical();
        assertEquals(List.of("ISO 400 was recorded."), shown, "citation markers are checked, then removed from the text");
        assertTrue(json(p).getAsJsonObject("statistics").getAsJsonObject("iso").has("id"), "evidence records carry application ids");
    }

    // B
    @Test void theSameNumberUnderAnotherMetricIsNotSupported() {
        var p = prepare(EXAMPLE);
        assertFalse(kept("The focal length was 400 mm.", p), "ISO 400 is no focal length");
        assertFalse(kept("The focal length was 400 mm [" + value(p, "iso", "ISO 400") + "].", p));
        assertFalse(kept("The photographer frequently used a focal length of 400 mm.", p));
        assertTrue(kept("The photographer frequently used a focal length of 85 mm.", p));
        assertFalse(kept("The aperture was f/85.", p), "85 mm is no f-number");
    }

    // C
    @Test void theSameNumberInAnotherUnitIsNotSupported() {
        var p = prepare(SESSION_A);
        assertFalse(kept("The shutter duration was 50 seconds.", p));
        assertTrue(kept("The most used focal length was 50 mm.", p));
    }

    // D
    @Test void anotherSessionsFigureDoesNotSupportAClaimAboutThisSession() {
        var p = prepare(SESSION_A);
        assertFalse(kept("The most used focal length was 85 mm.", p, HISTORY), "85 mm was most used in the earlier session");
        assertFalse(kept("The average focal length for this session was 85 mm.", p, HISTORY));
        assertTrue(kept("The average focal length for this session was 50 mm.", p, HISTORY));
        assertFalse(kept("ISO 1600 was used most often.", p, HISTORY));
    }

    // E
    @Test void aComparisonThatNamesBothSourcesIsSupported() {
        var p = prepare(SESSION_A);
        assertTrue(kept("In the earlier session the most used focal length was 85 mm, compared with 50 mm in this session.", p, HISTORY));
        assertTrue(kept("The earlier session recorded ISO 1600 most often.", p, HISTORY));
        assertTrue(kept("The previous session used 85 mm for 4 of 6 photos.", p, HISTORY));
        assertFalse(kept("In the earlier session the most used focal length was 50 mm.", p, HISTORY), "this session's figure under the earlier session");
        assertFalse(kept("In the earlier session the most used focal length was 85 mm, compared with 50 mm in this session.", p),
            "without related history no earlier figure is accepted");
        assertFalse(kept("Earlier advice suggested 1/2000 s.", p, HISTORY), "earlier advice is never a fact");
    }

    // F
    @Test void aPlannedValueNeverSupportsARecordedOne() {
        var p = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(2.8, 7), Map.of(800, 7)), plan(Map.of("iso", "400")));
        String planned = setting(p, "iso");
        assertFalse(kept("The photographer actually used ISO 400.", p));
        assertFalse(kept("The photographer used ISO 400 [" + planned + "].", p), "a plan record cited for a recorded value");
        assertFalse(kept("The photographer used ISO 400 as planned.", p));
        assertTrue(kept("The plan called for ISO 400 [" + planned + "], but the photographer used ISO 800.", p));
        assertFalse(kept("The plan called for ISO 800.", p), "a recorded value is not a planned one");
        String line = p.claims().verbatim().stream().filter(l -> l.startsWith("ISO: planned 400")).findFirst().orElseThrow();
        assertTrue(kept(line, p), "the deterministic comparison line itself");
    }

    // G
    @Test void oneRecordedValueIsNotAnAverageAMedianOrTheMostUsedValue() {
        var p = prepare(SESSION_A);
        assertFalse(kept("The average focal length across the session was 85 mm.", p));
        assertTrue(kept("The average focal length across the session was 50 mm.", p));
        assertTrue(kept("The longest focal length was 85 mm.", p));
        assertFalse(kept("The median focal length was 85 mm.", p));
        assertFalse(kept("The most used focal length was 85 mm.", p));
        assertTrue(kept("1 photo was taken at 85 mm.", p));
    }

    // H
    @Test void anEquivalentShutterNotationIsSupported() {
        var p = prepare(SESSION_A);
        assertTrue(kept("Shutter duration was 0.004 seconds.", p));
        assertTrue(kept("The most used shutter speed was 1/250 s.", p));
        assertFalse(kept("Shutter duration was 0.04 seconds.", p));
    }

    // I
    @Test void anIsoValueIsNeverConvertedIntoAShutterSpeed() {
        var p = prepare(stats(Map.of(50.0, 4), Map.of(0.001, 4), Map.of(2.8, 4), Map.of(250, 4)));
        assertFalse(kept("Shutter speed was 1/250 s.", p));
        assertTrue(kept("ISO 250 was recorded.", p));
    }

    // J
    @Test void aShareRoundedFromItsOwnCountAndTotalIsSupported() {
        var p = prepare(SESSION_A);
        assertTrue(kept("ISO 640 was used in 28.6% of the photos with ISO data.", p));
        assertTrue(kept("ISO 640 was used in 29% of the photos.", p));
        assertTrue(kept("ISO 640 was used in 28.57% of the photos.", p));
        assertTrue(kept("2 of 7 photos used ISO 640.", p));
    }

    // K
    @Test void aCloseButUnrelatedValueIsNotSupported() {
        var p = prepare(SESSION_A);
        assertFalse(kept("ISO 640 was used in 30% of the photos.", p));
        assertFalse(kept("ISO 640 was used in 28% of the photos.", p));
        assertFalse(kept("Median ISO was ISO 790.", p));
        assertFalse(kept("ISO 800 was used in 2 photos.", p), "2 photos is the count of ISO 640");
        assertFalse(kept("ISO 640 was used in 5 photos.", p));
        assertFalse(kept("ISO 640 was used in 71.4% of the photos.", p), "the share of ISO 800");
    }

    // L
    @Test void aCitationOfARecordTheApplicationNeverIssuedFails() {
        var p = prepare(SESSION_A);
        assertTrue(kept("Median ISO was ISO 800 [" + record(p, "iso") + "].", p));
        assertFalse(kept("Median ISO was ISO 800 [S99].", p));
        assertFalse(kept("Median ISO was ISO 800 [Q7].", p));
        assertFalse(kept("In the earlier session ISO 1600 was used most often [H1].", p, HISTORY), "earlier sessions have no citable ids");
        assertFalse(kept("The camera was the main body [S99].", p), "an unknown id fails even without a figure");
    }

    // M
    @Test void aCitationOfARecordOfAnotherMetricFails() {
        var p = prepare(SESSION_A);
        assertFalse(kept("Median ISO was ISO 800 [" + record(p, "focalLength") + "].", p));
        assertFalse(kept("Median ISO was ISO 800 [" + value(p, "iso", "ISO 640") + "].", p), "a record of the same metric but another value");
        assertTrue(kept("Median ISO was ISO 800 [" + record(p, "iso") + "].", p));
    }

    // N
    @Test void missingMetadataIsNeverFilledByAnotherStatistic() {
        var p = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(), Map.of(800, 7)));
        assertFalse(kept("The median aperture was f/2.8.", p));
        assertFalse(kept("The aperture was f/0.", p));
        assertTrue(kept("Aperture is missing for 7 of 7 photos.", p));
        assertTrue(kept("No photo recorded an aperture value.", p));
    }

    // O
    @Test void qualitativeAndProposedAdviceNeedsNoEvidenceButFactualAdviceDoes() {
        var p = prepare(SESSION_A);
        assertTrue(advised("Consider a faster shutter speed when photographing moving subjects.", p));
        assertTrue(advised("Next time try 1/2000 s for passing cyclists.", p));
        assertFalse(advised("You used 1/2000 s in 80% of the photographs.", p), "a statistic stays a statistic in advice");
        assertTrue(advised("You used 1/250 s most often; next time try 1/2000 s for passing cyclists.", p));
    }

    // P
    @Test void anUnsupportedFigureInTheNarrativeRemovesTheNarrative() {
        var p = prepare(SESSION_A);
        var reply = new ReviewInterpretation("Median ISO was ISO 800 [" + record(p, "iso") + "]. 93% of frames were taken at 1/2000 s.",
            List.of("The most used focal length was 50 mm."), List.of(), List.of(), List.of(), List.of(), List.of());
        var checked = ReviewEvidence.check(reply, p.claims());
        assertEquals("", checked.summary(), "a valid citation does not cover the uncited sentence after it");
        assertEquals(List.of("The most used focal length was 50 mm."), checked.technical());
        assertEquals(1, checked.removed());
    }

    // Q
    @Test void correctCurrentAndEarlierFactsAndAdviceAreAllKept() {
        var p = prepare(SESSION_A);
        var reply = new ReviewInterpretation("Of the 7 photos imported [" + json(p).getAsJsonObject("statistics").get("id").getAsString() + "], 7 had usable metadata.",
            List.of("The most used focal length was 50 mm [" + value(p, "focalLength", "50 mm") + "].",
                "In the earlier session the most used focal length was 85 mm, compared with 50 mm in this session."),
            List.of("The Sony FE 24-105mm G recorded 7 photos."), List.of("Median ISO was ISO 800."),
            List.of("Consider a faster shutter speed for moving subjects."), List.of("Next time try ISO 400 in bright light."),
            List.of("Only metadata was available."));
        var checked = ReviewEvidence.check(reply, p.claims().withHistory(HISTORY));
        assertEquals(0, checked.removed(), checked::toString);
        assertEquals("Of the 7 photos imported, 7 had usable metadata.", checked.summary());
        assertEquals(2, checked.technical().size()); assertEquals(1, checked.equipment().size()); assertEquals(1, checked.exposure().size());
        assertTrue(checked.technical().stream().noneMatch(t -> t.contains("[")), "markers are not shown");
    }

    // R
    @Test void oneUnsupportedClaimAmongValidOnesIsRemovedAndCounted() {
        var p = prepare(SESSION_A);
        var reply = new ReviewInterpretation("", List.of("The most used focal length was 50 mm.", "The average aperture was f/8.", "Median ISO was ISO 800.",
            "The longest focal length was 85 mm."), List.of(), List.of(), List.of(), List.of(), List.of());
        var checked = ReviewEvidence.check(reply, p.claims());
        assertEquals(List.of("The most used focal length was 50 mm.", "Median ISO was ISO 800.", "The longest focal length was 85 mm."), checked.technical());
        assertEquals(1, checked.removed(), "the draft says that a statement was removed");
    }

    /**
     * Statements written by an adversarial review of this checker (four lenses: parsing, source wording, statistics and
     * subjects, masking/citations/advice), each run against the fixture it was written for: false ones must be removed,
     * true ones kept. Before the second pass of the fix, 31 of the 32 false ones were accepted and all 8 true ones removed.
     */
    @Test void adversarialStatementsAreJudgedByWhatTheyClaim() {
        var a = prepare(SESSION_A); var example = prepare(EXAMPLE);
        var planned = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(2.8, 7), Map.of(800, 7)), plan(Map.of("iso", "400")));
        var falseObservations = Map.<String, ReviewEvidence.Prepared>ofEntries(
            Map.entry("Most used focal length: 85 mm.", a), Map.entry("Avg. ISO was 800.", a), Map.entry("1/1000 sec. was the most frequent shutter speed.", a),
            Map.entry("Focal lengths ranged from 50–85 mm.", a), Map.entry("Focal lengths ranged between 50 and 85 mm.", a),
            Map.entry("85 mm was used in 4 per cent of the photos.", example),
            Map.entry("Apertures: f/2.8 x2, f/4 x5.", a), Map.entry("Focal lengths of 50 mm and 85 mm were used on 1 and 5 photos respectively.", a),
            Map.entry("Exposure was left at 0 stops of compensation.", a), Map.entry("The photographer used ISO 800 as planned.", planned),
            Map.entry("The photographer used the planned ISO 400 throughout the shoot.", planned), Map.entry("All 7 photos were taken at 50 mm.", a),
            Map.entry("ISO 640 was the less common setting, used in 71.4% of the photos.", a), Map.entry("Median focal length: 85 mm.", a),
            Map.entry("Most of the photos were taken at f/4.", a), Map.entry("ISO is missing for 7 of 7 photos.", a),
            Map.entry("The photographer used 4 different lenses.", example), Map.entry("All 7 photos were shot at the planned ISO 400.", planned),
            Map.entry("ISO 640 was recorded on 7 photos [" + record(a, "iso") + "].", a), Map.entry("The photographer also shot at f/3.14 [" + record(a, "aperture") + "].", a),
            Map.entry("The Sony A7 IV recorded 7 frames at 50 mm.", a));
        falseObservations.forEach((statement, p) -> assertFalse(kept(statement, p), statement));
        for (String statement : List.of("In the earlier session, the most used focal length was 50 mm.", "f/2.8 was used most often, as in the earlier session.",
                "In this session the most used focal length was 50 mm, compared with 15 mm in the earlier session.", "The earlier session took place on 2026-10-13.",
                "The earlier session most often used 35 mm.", "The earlier session had only 4 photos with usable metadata.",
                "As in the earlier session there was no focal-length target — Focal length: the plan sets no value; recorded 15 mm - 85 mm. The most used focal length was 85 mm."))
            assertFalse(kept(statement, a, HISTORY), statement);
        for (var entry : Map.of("Consider keeping the planned ISO 1600 for indoor scenes.", planned, "You could keep the planned ISO 800 for similar overcast light.", planned,
                "You used ISO 800 for most frames and ISO 1600 for the darker scenes; keep a similar range for evening street work.", a,
                "Since 4 of the frames were at 1/1000 s, consider a slower shutter for static portraits next time.", a).entrySet())
            assertFalse(advised(entry.getKey(), entry.getValue()), entry.getKey());
        for (String statement : List.of("ISO 800 was used in 5 out of 7 photos.", "ISO 640 was used in 28.6 percent of the photos.",
                "ISO 800 was used on a total of 5 photos.", "ISO 800 was used on 5 photos and ISO 640 on 2.", "Focal lengths ranged from 15 to 85 mm.",
                "ISO 800 was recorded on 5 of 7 photos [" + value(a, "iso", "ISO 800") + "]."))
            assertTrue(kept(statement, a), statement);
        assertTrue(kept("In the earlier session, the most used focal length was 85 mm.", a, HISTORY));
        assertTrue(kept("ISO 400 was planned, but ISO 800 was used.", planned));
        assertTrue(advised("Consider using 1/2000 s for passing cyclists next time.", a));
    }

    /** Second adversarial round against the reworked checker: sentence and clause structure, Chinese text, echoed and carried sources. */
    @Test void secondRoundAdversarialStatementsAreJudgedByWhatTheyClaim() {
        var a = prepare(SESSION_A);
        var planned = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(2.8, 7), Map.of(800, 7)), plan(Map.of("iso", "400")));
        for (String statement : List.of("The photographer used ISO 800. ISO 640 was used in 5 photos.", "ISO 640: 5 photos, ISO 800: 2 photos.",
                "Focal lengths ran from 50 mm up to 85 mm.", "Focal lengths ranged from 50 mm‑85 mm.", "All photos were shot with an exposure compensation of 0.",
                "本次最常用的焦距是85mm。", "ISO 640 用于 5张照片。", "ISO 640 用于 5 张照片，ISO 800 用于 2 张照片。",
                "Most common settings: f/2.8, 1/1000 s and ISO 800.", "The highest shutter speed recorded was 1/250 s.", "None of the 7 photos used f/4.",
                "The photographer used 3 different apertures.", "Shutter speed was recorded for only 3 photos.", "f/4 was used on 2 of 3 photos.",
                "The second most common ISO was ISO 800.", "ISO peaked at ISO 640.", "f/4 [" + record(a, "aperture") + ", " + value(a, "aperture", "f/4") + "] was the most used aperture.",
                "Lens model is missing for 7 of 7 photos; its statistics use only the photos that recorded it.", "The Sony A7 IV was the only camera, and it recorded 5 photos."))
            assertFalse(kept(statement, a), statement);
        for (String statement : List.of("As in the earlier session, the most used focal length was 85 mm.", "In the previous session, ISO 800 was used on 5 photos.",
                "The earlier session used 85 mm for most frames, and its most used aperture was f/2.8.",
                "In the earlier session, the most used focal length was 85 mm. That session also used f/2.8 most often.",
                "The most used focal length was 85 mm (unchanged from the previous session).", "In the earlier session, ISO: the plan sets no value; recorded ISO 640 - ISO 800."))
            assertFalse(kept(statement, a, HISTORY), statement);
        for (String statement : List.of("ISO 800 was used throughout, as the plan specified.", "As planned, the camera stayed at ISO 400 for all 7 photos.",
                "The plan set ISO 400, and 7 photos were taken at that setting."))
            assertFalse(kept(statement, planned), statement);
        assertFalse(advised("Next time, keep ISO 800 as planned.", planned));
        for (String advice : List.of("Consider a faster shutter speed next time, since 6 of 7 photos were taken at 1/250 s.",
                "These patterns should be read with caution, since ISO 1600 was used on most frames.",
                "Working wide open at f/1.8 made depth of field the main constraint for street portraits."))
            assertFalse(advised(advice, a), advice);
        for (String statement : List.of("The session had a median ISO of 800.", "Median ISO: 800.", "The session used 3 different focal lengths.",
                "Focal lengths of 50 mm and 85 mm were used on 5 and 1 photos respectively."))
            assertTrue(kept(statement, a), statement);
        assertTrue(kept("The photographer used ISO 800 instead of the planned ISO 400.", planned));
        assertTrue(kept("Unlike the earlier session, the most used focal length was 50 mm.", a, HISTORY));
        assertTrue(kept("In the earlier session, 85 mm was used on 4 of 6 photos.", a, HISTORY));
    }

    /** Third adversarial round: unread digits, ranges absorbing counts, listed statistics, joint counts, plan comparison places, Chinese wording. */
    @Test void thirdRoundAdversarialStatementsAreJudgedByWhatTheyClaim() {
        var a = prepare(SESSION_A);
        var planned = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(2.8, 7), Map.of(800, 7)), plan(Map.of("iso", "400")));
        for (String statement : List.of("Aperture usage: f/2.8 – 4 of 7 photos.", "50 mm was only the 2nd most used focal length.",
                "The 3 frames at 1/1000 s were shot in a 10fps burst.", "最常用的焦距是 50 毫米，85 毫米只用于 5 张照片。", "Median ISO was 800 (n=5).",
                "The narrowest aperture was used on just 4 exposures.", "5 photos were taken at 85 mm with f/2.8.", "5 photos were taken at 50 mm and the rest at 85 mm.",
                "The photographer worked mainly at 85 mm.", "The mean and median ISO were both 800.", "The most common aperture and shutter speed were f/4 and 1/1000 s.",
                "ISO 640 was the most-used ISO setting.", "ISO 640 (S17) was the most frequent ISO setting.", "The Sony A7 IV recorded only 5 photos, all at 50 mm.",
                "As with the lens model, exposure compensation is missing for 7 of 7 photos; its statistics use only the photos that recorded it.",
                "Exposure compensation stayed at an EV of 0 for all 7 photos.", "The approved plan covered 1 shooting window.",
                "f/2.8 是最常用的光圈，85mm 是最常用的焦距。", "50mm 和 85mm 焦距各拍摄了 5 张照片。", "大部分照片使用的光圈为 f/4。", "本次拍摄的最高快门速度为 1/250 秒。"))
            assertFalse(kept(statement, a), statement);
        assertFalse(kept("Every frame was shot at an f-stop of 4.", prepare(EXAMPLE)));
        for (String statement : List.of("The earlier session used ISO 1600 on 2/6 photos.", "f/2.8 was the most used aperture both this time and last time.",
                "Previously, the most used aperture was also f/2.8.", "The earlier session, unlike this session, relied mostly on f/2.8.",
                "In the earlier session, ISO 1600 was used most often; in your latest shoot, the most used focal length was 85 mm.",
                "In the earlier session, only 1 photo was taken with the Sony A7 IV.", "上一次拍摄中最常用的焦距为 50mm。", "上次拍摄中，ISO 1600 仅用于 2 帧。"))
            assertFalse(kept(statement, a, HISTORY), statement);
        for (String statement : List.of("The plan for this session called for ISO 800.", "ISO 800, the planned setting, was used on all 7 photos.",
                "The plan called for ISO 400, and the camera stayed at ISO 400 throughout.", "The shoot plan specified ISO 800.",
                "The planned ISO 400 was met on 7 photos.", "All 7 photos fell below the planned ISO 400.", "所有照片都使用了 ISO 800，与计划一致。"))
            assertFalse(kept(statement, planned), statement);
        for (String advice : List.of("Next time, consider a faster shutter than the 1/60 s you relied on indoors.",
                "Conclusions about subject motion could only be drawn for the 1/60 s frames.", "Next time, consider a faster shutter speed for the 3 slower exposures.",
                "下次建议适当收小光圈，这组照片大多用的是 f/1.8。"))
            assertFalse(advised(advice, a), advice);
        assertTrue(ReviewEvidence.check(advice("Next time, consider returning to 35 mm, which you used most in the earlier session."), a.claims().withHistory(HISTORY)).improvements().isEmpty());
        assertFalse(advised("The approved plan recommended ISO 1600 for the venue; keep that in mind next time.", planned));
        for (String statement : List.of("ISO ranged from 640 to 800.", "Recorded focal lengths: 15/50/85 mm.", "Median ISO was 800 (n=7).",
                "The most common aperture and shutter speed were f/2.8 and 1/250 s respectively.", "The Sony A7 IV recorded all 7 photos, mostly at 50 mm.",
                "7 张照片中有 2 张使用了 ISO 640。"))
            assertTrue(kept(statement, a), statement);
        assertTrue(kept("The plan for this session called for ISO 400, but ISO 800 was used.", planned));
        for (String statement : List.of("Compared with the earlier session, which used ISO 1600 most often, this session's most used ISO was ISO 800.",
                "The earlier session has no shutter data to compare against. Shutter speed: the plan sets no value; recorded 1/1000 s - 1/250 s.",
                "与上次相比，本次最常用的焦距为 50mm。"))
            assertTrue(kept(statement, a, HISTORY), statement);
    }

    /** Fourth adversarial round: parentheses, appositives, listed labels, respectively, aperture stops, approximations, leading settings, and ordinary true wording. */
    @Test void fourthRoundAdversarialStatementsAreJudgedByWhatTheyClaim() {
        var a = prepare(SESSION_A);
        var planned = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(2.8, 7), Map.of(800, 7)), plan(Map.of("iso", "400")));
        for (String statement : List.of("The most frequently used focal length (85 mm) suits tight street portraits.", "Median focal length and ISO: 85 mm and ISO 640.",
                "The camera's compensation dial stayed at EV 0 for the whole shoot.", "f/4, the aperture you used most, suits environmental street portraits.",
                "Aperture use: f/2.8 – 4 times, f/4 – 3 times.", "Shutter speed: lowest 1/1000 s, highest 1/250 s.", "ISO 640, the most common setting, was used on 2 photos.",
                "All photos were shot at the widest aperture of f/2.8.", "Across all photos, the aperture stayed at f/2.8.", "f/2.8 was the only aperture used.",
                "85 mm and f/2.8 were the most common focal length and aperture, respectively.",
                "The highest ISO and the slowest shutter speed were ISO 800 and 1/1000 s, respectively.", "Most frequent values (by photo count): ISO 640, f/4 and 1/1000 s.",
                "The smallest aperture used was f/2.8.", "Exposure compensation was recorded on 7 of 7 photos.", "Lens model: missing for 7 of 7 photos.",
                "The 50 mm and 85 mm focal lengths were each used on 1 photo.", "The approved plan included 1 camera body and 1 lens.",
                "Of the photos taken at f/2.8, 71.4% used 50 mm.", "Of the 7 photos shot at 85 mm, 5 used f/2.8.", "5 photos were taken at 50 mm and the other photo at 85 mm.",
                "More than 5 of the 7 photos used ISO 800.", "At 50 mm, the Sony FE 24-105mm G recorded 7 photos.", "Lens model and capture time are missing for 7 of 7 photos.",
                "Unlike the capture time, exposure compensation is missing for 7 of 7 photos; its statistics use only the photos that recorded it."))
            assertFalse(kept(statement, a), statement);
        for (String statement : List.of("Apertures varied from f/2.8 to 4.", "Every frame was shot at ƒ/4."))
            assertFalse(kept(statement, prepare(EXAMPLE)), statement);
        for (String statement : List.of("In the earlier session, the most used aperture was f/2.", "The earlier session used 85 mm most often, and this session did the same.",
                "Both sessions had 7 photos with usable metadata.", "The most used focal length was 85 mm, which matched the earlier session.",
                "85 mm was the most used focal length just as it was in the earlier session.", "上次拍摄比较依赖单一焦段，最常用的焦距为 50mm。",
                "Since the earlier session, the most used focal length has shifted to 85 mm.", "In the earlier session, only 1 of the photos was taken with the Sony A7 IV.",
                "In both sessions, ISO: the plan sets no value; recorded ISO 640 - ISO 800."))
            assertFalse(kept(statement, a, HISTORY), statement);
        for (String statement : List.of("The photographer used the planned setting (ISO 400) throughout.", "ISO 400 was planned and used.",
                "This session's plan set ISO 800 for every photo.", "The approved plan for this session called for ISO 400. The median ISO was 400.",
                "7 photos exceeded the planned ISO 400, and 7 photos also used a slower shutter speed than planned."))
            assertFalse(kept(statement, planned), statement);
        for (String advice : List.of("Next time, consider stopping down from your usual aperture (f/1.8) to gain more depth of field.",
                "Consider staying with 1/2000 s, the shutter speed you used for most frames.", "建议下次继续用 f/1.8，这是你最常用的光圈。",
                "建议下次提高快门速度，你在室内一般用 1/60 秒。", "You seem to prefer f/1.8 for portraits; next time consider stopping down to f/2.8 for more depth of field.",
                "Next time, consider a faster shutter than your indoor 1/60 s."))
            assertFalse(advised(advice, a), advice);
        assertFalse(advised("Next time, return to ISO 1600, the planned setting, if the light allows.", planned));
        for (String statement : List.of("The session used focal lengths of 15, 50 and 85 mm.", "Most frames were shot at an ISO value of 800.",
                "The minimum and maximum ISO were ISO 640 and ISO 800 respectively.", "本次 ISO 最低为 640，最高为 800。",
                "Shutter speeds were split between 1/250 s (4 photos) and 1/1000 s (3 photos)."))
            assertTrue(kept(statement, a), statement);
        for (String statement : List.of("The photographer, who had favoured 85 mm in the earlier session, used 50 mm most often.",
                "In the earlier session, 85 mm and 35 mm were used on 4 and 2 photos respectively.",
                "The earlier session relied on f/1.8 for all 6 photos, while this session mostly used f/2.8."))
            assertTrue(kept(statement, a, HISTORY), statement);
        for (String statement : List.of("The camera stayed at ISO 800 throughout, contrary to the plan.", "ISO 800, higher than the planned ISO 400, was used on all 7 photos.",
                "0 of 7 photos were within the planned ISO 400; all 7 were higher, at ISO 800."))
            assertTrue(kept(statement, planned), statement);
        for (String advice : List.of("For static portraits, consider a few f/8 frames next time to compare depth of field.", "建议下次采用 1/2000 秒的快门速度拍摄骑车人。",
                "Next time try 1/2000 s instead of the 1/250 s you used most often."))
            assertTrue(advised(advice, a), advice);
        assertTrue(advised("Next time, set the camera to the planned ISO 400 before the shoot instead of leaving it at ISO 800.", planned));
    }

    /** Fifth round: realistic English and Chinese reviewer wording, and ordinary true statements the stricter rules must still keep. */
    @Test void fifthRoundRealisticStatementsAreJudgedByWhatTheyClaim() {
        var a = prepare(SESSION_A);
        var planned = prepare(stats(Map.of(50.0, 7), Map.of(0.004, 7), Map.of(2.8, 7), Map.of(800, 7)), plan(Map.of("iso", "400")));
        for (String statement : List.of("The smallest f-number used was f/4.", "The focal range ran from 15 mm at the wide end to 50 mm at the long end.",
                "Of the 7 photos with recorded capture times, 5 were shot at 50 mm.", "f/4 和 1/1000 秒分别是最常用的光圈和快门速度。", "使用 f/4 光圈的照片共 7 张。",
                "1/1000 秒，即本次最常用的快门速度，适合抓拍街头动态。", "本次使用频率最高的光圈为 f/4。", "在 f/4 光圈下，50mm 焦距拍了 5 张照片。", "本次拍摄以 85mm 焦段为主。"))
            assertFalse(kept(statement, a), statement);
        for (String statement : List.of("All 4 photos had complete EXIF metadata.", "多于 4 张照片使用了 ISO 400。"))
            assertFalse(kept(statement, prepare(EXAMPLE)), statement);
        assertFalse(advised("Next time, rather than shooting everything at 1/500 s, try 1/2000 s for passing cyclists.", prepare(EXAMPLE)));
        for (String statement : List.of("Following the plan, the camera stayed at ISO 800 throughout.", "Every photo met the planned ISO 400.", "全部 7 张照片均采用计划设定的 ISO 400。"))
            assertFalse(kept(statement, planned), statement);
        for (String advice : List.of("Next time, consider ISO 800, which is what the plan called for.", "已批准的拍摄方案将 ISO 设为 800，下次可继续沿用这一设定。"))
            assertFalse(advised(advice, planned), advice);
        for (String statement : List.of("In the earlier session, you worked mainly at 35 mm.", "In each of your sessions, f/2.8 was the most used aperture.", "上次拍摄的焦距均为 85mm。"))
            assertFalse(kept(statement, a, HISTORY), statement);
        for (String advice : List.of("Next time, try stopping down from f/1.8, where you shot most of this session.", "建议下次继续使用 f/1.8，本次大部分照片都用了这个光圈。"))
            assertFalse(advised(advice, a), advice);
        for (String advice : List.of("Next time, vary your ISO more, as you only shot at ISO 1600 in the earlier session.", "上一场拍摄主要使用 ISO 800，建议下次在类似光线下继续保持。"))
            assertTrue(ReviewEvidence.check(advice(advice), a.claims().withHistory(HISTORY)).improvements().isEmpty(), advice);
        for (String statement : List.of("No exposure compensation data was recorded (0 of 7 photos).", "All 7 photos were shot at either f/2.8 or f/4.", "50mm 焦距占全部照片的 71.4%。"))
            assertTrue(kept(statement, a), statement);
        for (String statement : List.of("All 7 photos used ISO 800, which is above the planned ISO 400.", "None of the 7 photos were within the planned ISO 400; all 7 were higher.",
                "本次 7 张照片都使用了 ISO 800，高于计划的 ISO 400。"))
            assertTrue(kept(statement, planned), statement);
        for (String statement : List.of("The most used focal length shifted from 85 mm in the earlier session to 50 mm in this session.",
                "In the earlier session, 85 mm was used on 4 of 6 photos; in this session, 50 mm was used on 5 of 7 photos.", "与上次最常用的 85mm 不同，本次最常用的焦距是 50mm。"))
            assertTrue(kept(statement, a, HISTORY), statement);
        assertTrue(advised("For dim street scenes, consider adding a fast prime such as an f/1.8 lens next time.", a));
        assertTrue(advised("Next time, consider updating the plan to ISO 800 for this venue.", planned));
    }

    /** Malformed text never throws (an impossible date once aborted the whole review), and a summary of the longest kept length is checked quickly. */
    @Test void malformedOrLongTextNeverBreaksTheCheck() {
        var a = prepare(SESSION_A);
        for (String text : List.of("", " ", "(", ")", "((()", "(n=", "[S1", ":", ",,,,", "1/0 s", "f/", "ISO", "0/0 photos", "2026-13-45 was the date.", "2026-02-30",
                "99999999999999999999999/5 photos", "1/99999999999999999999999 s", "9999999999999999999999 mm", "UTC+99:99", "99:99", "))) 50 mm (((", "-0 EV",
                "(".repeat(300) + "50 mm" + ")".repeat(300), "ISO 800 ".repeat(200))) {
            assertDoesNotThrow(() -> kept(text, a, HISTORY), text);
            assertDoesNotThrow(() -> advised(text, a), text);
        }
        assertFalse(kept("2026-13-45 was the date.", a));
        String block = "The most used focal length was 50 mm (5 of 7 photos), and the median ISO was 800. Shutter speeds were split between 1/250 s on 4 photos and"
            + " 1/1000 s on 3 photos, while f/2.8 was used on 5 photos and f/4 on 2. The Sony A7 IV recorded all 7 photos; exposure compensation is missing for 7 of 7 photos.";
        String summary = String.join(" ", block, block, block, block);
        var evidence = a.claims().withHistory(HISTORY);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertTrue(ReviewClaims.supported(summary, evidence, false)));
        String dense = "f/2.8 on 5, ".repeat(60) + "ISO 800.";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertTrue(ReviewClaims.supported(dense, evidence, false)));
    }

    // V
    @Test void numberFormatsUnitsAndLanguagesAreParsedWithoutCrossingMetrics() {
        var p = prepare(stats(Map.of(50.0, 7), Map.of(0.01, 7), Map.of(2.8, 7), Map.of(1600, 2, 640, 5)));
        assertTrue(kept("ISO 1,600 was recorded on 2 photos.", p), "thousands separator");
        assertTrue(kept("Aperture F2.8 was used most often.", p), "f-number without a slash");
        assertFalse(kept("The aperture was F85.", p), "an f-number written like a model name is still checked");
        assertTrue(kept("The most used shutter speed was 1/100 s.", p), "a shutter speed is no score");
        assertFalse(kept("Exposure was ISO -800.", p), "negative and impossible values match nothing");
        assertFalse(kept("The value was 640.", p), "a number without a unit or metric supports nothing but counts");
        assertTrue(kept("ISO 1600 was used in 28,6 % of the photos.", p), "decimal comma");
        assertTrue(kept("中位 ISO 为 640。", p));
        assertTrue(kept("最常用的焦距是 50 毫米。", p));
        assertFalse(kept("焦距 400 毫米。", p));
        assertTrue(kept("The Sony FE 24-105mm G recorded 7 photos.", p), "digits of an equipment name are not focal-length claims");
        assertTrue(kept("Every photo used the Sony A7 IV.", p));
    }
}
