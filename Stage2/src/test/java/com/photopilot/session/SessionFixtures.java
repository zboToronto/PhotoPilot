package com.photopilot.session;

import com.photopilot.command.ShootPlan;
import com.photopilot.model.*;
import com.photopilot.state.*;
import java.time.*;
import java.util.*;

/** Controlled linked records, never demonstration/user data. F10/F11 generation is not invoked. */
public final class SessionFixtures {
    public static PhotographySession linked(SessionState state, List<GearSnapshot> gear, boolean review) {
        var now = Instant.parse("2026-10-31T10:00:00Z");
        var stats = new ExifStatistics(1, 1, Map.of(), Map.of(50.0, 1), Map.of(), Map.of(), Map.of(100, 1), Map.of(), Map.of(), List.of("Fixture evidence"));
        var loadout = new Loadout(UUID.randomUUID(), gear, Map.of(), new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
        var calculation = new CalculationResult(Map.of("far", Double.POSITIVE_INFINITY), Map.of("far", "m"), true, List.of("Fixture"),
            new CalculationInput(CalculationKind.DEPTH_OF_FIELD, Map.of(), Map.of()));
        var plan = new ShootPlan(UUID.randomUUID(), 1, true, List.of(), Optional.of(loadout), Map.of("iso", "100"),
            List.of(new ShootingWindow(ZonedDateTime.parse("2026-11-01T10:00:00-05:00[America/Toronto]"),
                ZonedDateTime.parse("2026-11-01T11:00:00-05:00[America/Toronto]"), ZonedDateTime.parse("2026-11-01T09:00:00-05:00[America/Toronto]"), "Manual", true)),
            List.of("Charge"), List.of(), List.of("Light"), Map.of(), List.of(calculation));
        var reviews = review ? List.of(new PostShootReview(UUID.randomUUID(), now, stats, plan.id(), 1, List.of(), List.of("Lesson"), List.of("Fixture"))) : List.<PostShootReview>of();
        return new PhotographySession(UUID.randomUUID(), "Linked shoot", LocalDate.of(2026, 11, 1), Optional.of(GeoLocation.manual("Park")), "Goals", now, now, state,
            Optional.of(new ShootRequirement("Brief", "Portrait", List.of("Light"), Optional.empty(), Optional.empty(), Duration.ofHours(1), 1, true,
                Optional.empty(), List.of(), Map.of(), Map.of(), List.of(), true)),
            List.of(plan), reviews, Optional.of(new EquipmentRequirementProfile(List.of(), true)), Optional.of(stats),
            List.of(new ActivitySuggestion("Portrait", "Park", Duration.ofHours(1), Optional.empty(), "Light", List.of())));
    }
}
