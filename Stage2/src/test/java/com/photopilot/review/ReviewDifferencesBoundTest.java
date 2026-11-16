package com.photopilot.review;

import com.photopilot.command.ShootPlan;
import com.photopilot.model.*;
import com.photopilot.service.StatisticsCalculator;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F11: the deterministic plan comparison grows with the plan (one line per camera-bound setting, planned and recorded
 * camera/lens) and the import, so it has its own bound; a valid two-body plan can always be reviewed, while the
 * model-written sections keep their tighter limit.
 */
class ReviewDifferencesBoundTest {
    static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    @Test void aValidTwoBodyComparisonFitsAReviewWhileModelSectionsStayBounded() {
        var a1 = new GearSnapshot(UUID.randomUUID(), "CAMERA", "Sony", "A1", 1, NOW, Map.of(), Set.of(), Map.of());
        var a7 = new GearSnapshot(UUID.randomUUID(), "CAMERA", "Sony", "A7 IV", 1, NOW, Map.of(), Set.of(), Map.of());
        var tele = new GearSnapshot(UUID.randomUUID(), "LENS", "Sony", "FE 200-600mm G", 1, NOW, Map.of("minFocalMm", "200", "maxFocalMm", "600"), Set.of(), Map.of());
        var zoom = new GearSnapshot(UUID.randomUUID(), "LENS", "Sony", "FE 100-400mm GM", 1, NOW, Map.of("minFocalMm", "100", "maxFocalMm", "400"), Set.of(), Map.of());
        var loadout = new Loadout(UUID.randomUUID(), List.of(a1, a7, tele, zoom), Map.of(a1.sourceGearId(), tele.sourceGearId(), a7.sourceGearId(), zoom.sourceGearId()),
            new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
        var settings = new LinkedHashMap<String, String>();
        for (var body : List.of(a1, a7)) {
            var camera = Optional.of(body.sourceGearId());
            settings.put(ShootPlan.settingKey("shutterSpeed", camera), "1/1600-1/3200 s"); settings.put(ShootPlan.settingKey("aperture", camera), "f/6.3-f/8");
            settings.put(ShootPlan.settingKey("iso", camera), "100-3200"); settings.put(ShootPlan.settingKey("focalLength", camera), "100-600 mm");
            settings.put(ShootPlan.settingKey("exposureCompensation", camera), "-1/3 EV");
        }
        var plan = new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.of(loadout), settings, List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
        var stats = new ExifStatistics(14, 14, Map.of(), Map.of(400.0, 7, 600.0, 7), Map.of(0.0005, 14), Map.of(8.0, 14), Map.of(1600, 14),
            Map.of("Sony ILCE-1", 6, "Sony ILCE-7M4", 6, "Apple iPhone 15", 1, "Google Pixel 8", 1), Map.of("FE 200-600mm F5.6-6.3 G OSS", 7, "FE 100-400mm F4.5-5.6 GM OSS", 7), List.of());
        var differences = new StatisticsCalculator().compare(stats, plan);
        assertTrue(differences.size() > PostShootReview.MAX_ITEMS * 2, () -> differences.size() + " " + differences);
        var limitations = new ArrayList<String>();
        for (int i = 1; i <= 28; i++) limitations.add("Evidence limit " + i);
        var review = new PostShootReview(UUID.randomUUID(), NOW, stats, plan.id(), 1, differences, List.of("Track earlier"), limitations, Optional.empty(),
            "Summary", List.of("Shutter speeds held"), List.of(), List.of(), List.of("Arrive earlier"), List.of(), PostShootReview.DRAFT);
        assertTrue(review.validate().isSuccess(), () -> review.validate().errors().toString());
        var lessons = new ArrayList<String>();
        for (int i = 1; i <= PostShootReview.MAX_ITEMS * 2 + 1; i++) lessons.add("Lesson " + i);
        var tooMany = new PostShootReview(UUID.randomUUID(), NOW, stats, plan.id(), 1, differences, lessons, limitations, Optional.empty(),
            "Summary", List.of("Shutter speeds held"), List.of(), List.of(), List.of(), List.of(), PostShootReview.DRAFT);
        assertTrue(tooMany.validate().hasError("review.error.invalidReview"), "model-written sections keep MAX_ITEMS * 2");
    }
}
