package com.photopilot.service;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static com.photopilot.service.ReviewClaimsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 Review2 #05 (PP-03 follow-up): quantities written in words ("五张", "两台", "seven photos") were never read
 * as figures, so a wrong count or another session's count passed unchecked; and statements about "the earlier session"
 * could combine figures of different related sessions. Hand-built statistics (session A: 7 photos, one Sony A7 IV, one
 * lens; ISO 640 x2) and saved history (session B 2026-10-12: 6 photos, 85 mm x4; session C 2026-09-01: 10 photos, 35 mm
 * x7). Deterministic validation only; the model's reasoning is not involved.
 */
class ReviewNumeralClaimsTest {
    /** Earlier sessions B (latest) and C, with different statistics. */
    static final String TWO_SESSIONS = """
        {"relatedHistory":[{"subject":"Street portraits","date":"2026-10-12","state":"REVIEWED","approvedRevision":2,"plannedEquipment":[],"shotPriorities":[],
        "savedReviews":[{"reviewedPlanRevision":2,"savedOn":"2026-10-13","facts":{"photosImported":6,"photosWithUsableExif":6,
        "mostUsedFocalLengthsMm":[{"value":"85.0","photos":4},{"value":"35.0","photos":2}],"mostUsedApertures":[{"value":"1.8","photos":6}],
        "mostUsedIso":[{"value":"1600","photos":4}]},"pastAdvice":{"lessons":[],"improvements":[]}}]},
        {"subject":"Street portraits","date":"2026-09-01","state":"REVIEWED","approvedRevision":1,"plannedEquipment":[],"shotPriorities":[],
        "savedReviews":[{"reviewedPlanRevision":1,"savedOn":"2026-09-02","facts":{"photosImported":10,"photosWithUsableExif":10,
        "mostUsedFocalLengthsMm":[{"value":"35.0","photos":7},{"value":"24.0","photos":3}],"mostUsedApertures":[{"value":"2.8","photos":10}],
        "mostUsedIso":[{"value":"400","photos":6}]},"pastAdvice":{"lessons":[],"improvements":[]}}]}]}""";

    static void keptAll(ReviewEvidence.Prepared p, String history, String... statements) {
        for (String s : statements) assertTrue(kept(s, p, history), () -> "kept: " + s);
    }
    static void removedAll(ReviewEvidence.Prepared p, String history, String... statements) {
        for (String s : statements) assertFalse(kept(s, p, history), () -> "removed: " + s);
    }

    @Test void chineseNumeralsAreReadAsTheNumbersTheyWrite() {
        assertEquals(Optional.of(7L), ReviewClaims.chinese("七"));
        assertEquals(Optional.of(2L), ReviewClaims.chinese("两"));
        assertEquals(Optional.of(12L), ReviewClaims.chinese("十二"));
        assertEquals(Optional.of(25L), ReviewClaims.chinese("二十五"));
        assertEquals(Optional.of(105L), ReviewClaims.chinese("一百零五"));
        assertEquals(Optional.of(2000L), ReviewClaims.chinese("两千"));
        assertEquals(Optional.of(12000L), ReviewClaims.chinese("一万二千"));
        assertEquals(Optional.of(0L), ReviewClaims.chinese("零"));
        for (String unreadable : List.of("三四", "百", "二十三百", "零十"))
            assertEquals(Optional.empty(), ReviewClaims.chinese(unreadable), unreadable);
        assertEquals("本次导入了7张照片。", ReviewClaims.numerals("本次导入了七张照片。"));
        assertEquals("用了2台相机和12支镜头", ReviewClaims.numerals("用了两台相机和十二支镜头"));
        assertEquals("7 photos and 25 frames", ReviewClaims.numerals("seven photos and twenty-five frames"));
        // Idioms are not counts: ordinals, demonstratives, "both", articles.
        for (String idiom : List.of("上一次拍摄", "第二张照片", "这一张照片", "两场拍摄", "两次拍摄", "一个较大的光圈", "一种方法", "一直使用"))
            assertEquals(idiom, ReviewClaims.numerals(idiom), idiom);
        assertNull(ReviewClaims.numerals("本次拍了三四张照片。"), "a range in words cannot be checked exactly");
        assertNull(ReviewClaims.numerals("five-six photos"));
    }

    @Test void correctChineseCountsAreKeptAndIncorrectOnesRemoved() {
        var p = prepare(SESSION_A);
        keptAll(p, "", "本次共导入了七张照片。", "本次使用了一台相机。", "本次两张照片使用了 ISO 640。", "85mm 焦距用了一张照片。", "本次 7 张照片带有可用的 EXIF 数据。");
        removedAll(p, "", "本次共导入了五张照片。", "本次使用了两台相机。", "本次使用了两支镜头。", "共有十二张照片。", "本次三张照片使用了 ISO 640。",
            "本次拍了三四张照片。");
    }

    @Test void arabicNumeralsWithChineseQuantifiersAreCheckedByWhatTheyCount() {
        var p = prepare(SESSION_A);
        keptAll(p, "", "本次使用了 1 台相机。", "本次使用了 1 支镜头。", "本次 2 张照片使用了 ISO 640。");
        removedAll(p, "", "本次使用了 2 台相机。", "本次使用了 3 支镜头。", "本次使用了 2 款镜头。", "本次 3 张照片使用了 ISO 640。");
    }

    @Test void aNumberOfSessionsIsNeverEvidence() {
        var p = prepare(SESSION_A);
        removedAll(p, HISTORY, "之前三次拍摄都以 85mm 为主。", "过去五场拍摄都以 85mm 为主。", "In three earlier sessions, 85 mm was the most used focal length.",
            "Across 3 sessions, 85 mm was the most used focal length.");
    }

    @Test void anotherSessionsCountDoesNotSupportAClaimAboutThisSessionInWordsEither() {
        var p = prepare(SESSION_A);
        keptAll(p, HISTORY, "上一次拍摄导入了六张照片。", "上次拍摄有四张照片使用了 85mm。");
        removedAll(p, HISTORY, "本次导入了六张照片。", "本次有四张照片使用了 85mm。", "本次导入了 6 张照片。");
    }

    @Test void figuresForTheEarlierSessionComeFromOneSessionAndLastTimeMeansTheLatest() {
        var p = prepare(SESSION_A);
        // B (2026-10-12) is the latest earlier session: "上次" / "last session" speak of it.
        keptAll(p, TWO_SESSIONS, "上次拍摄最常用的焦距为 85mm。", "上次拍摄导入了六张照片。", "In the last session, 85 mm was the most used focal length.");
        removedAll(p, TWO_SESSIONS, "上次拍摄最常用的焦距为 35mm。", "上次拍摄导入了十张照片。", "In the last session, 10 photos were imported.");
        keptAll(p, TWO_SESSIONS, "上次拍摄最常用的焦距为 85mm，最常用的光圈为 f/1.8。", "In the last session, ISO 1600 was used on four photos.");
        removedAll(p, TWO_SESSIONS, "上次有 7 张照片使用了 35mm。", "上次有七张照片使用了 35mm。");
        // "the earlier session" without "last": either earlier session, but one and the same for all its figures.
        keptAll(p, TWO_SESSIONS, "In the earlier session, 35 mm was the most used focal length.", "In the earlier session, 10 photos were imported.",
            "之前有七张照片使用了 35mm。", "之前拍摄最常用的焦距为 35mm，最常用的光圈为 f/2.8。",
            "In the earlier session, the most used focal length was 85 mm and the most used aperture was f/1.8.");
        removedAll(p, TWO_SESSIONS, "之前拍摄最常用的焦距为 35mm，最常用的光圈为 f/1.8。",
            "In the earlier session, the most used focal length was 35 mm and the most used aperture was f/1.8.");
        // Plural wording keeps the per-figure check of the related sessions.
        keptAll(p, TWO_SESSIONS, "In past sessions, 85 mm and 35 mm were the most used focal lengths.");
    }

    @Test void englishNumberWordsAndExistingEnglishValidationAgree() {
        var p = prepare(SESSION_A);
        keptAll(p, "", "Seven photos were imported.", "7 photos were imported.", "One camera body was used.", "ISO 640 was used on two photos.");
        removedAll(p, "", "Five photos were imported.", "5 photos were imported.", "Two camera bodies were used.", "ISO 640 was used on three photos.");
        // PP-03 regression: the established figure and identity rules still apply.
        assertTrue(kept("The most used focal length was 50 mm.", p));
        assertFalse(kept("The most used focal length was 85 mm.", p, HISTORY), "85 mm was most used in the earlier session");
    }

    @Test void theStatementIsShownAsWrittenNotAsNormalized() {
        var p = prepare(SESSION_A);
        var checked = ReviewEvidence.check(observation("本次共导入了七张照片。"), p.claims());
        assertEquals(List.of("本次共导入了七张照片。"), checked.technical(), "only validation reads the digits");
    }
}
