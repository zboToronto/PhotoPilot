package com.photopilot.environment;

import com.photopilot.service.SolarCalculator;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The documented solar model against reference values; phases are ordered and attributed to the right local date. */
class SolarCalculatorTest {
    static final double LAT = 43.6532, LON = -79.3832;
    static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    @Test void sunriseAndSunsetMatchProviderReferenceValuesWithinTwoMinutes() {
        // Reference: sunrise-sunset.org reply for these coordinates and 2026-11-06, recorded 2026-11-03.
        LocalDate day = LocalDate.of(2026, 11, 6);
        Instant from = day.atStartOfDay(TORONTO).toInstant(), to = day.plusDays(1).atStartOfDay(TORONTO).toInstant();
        var sunrise = SolarCalculator.crossing(LAT, LON, from, to, SolarCalculator.SUNRISE_ELEVATION, true).orElseThrow();
        var sunset = SolarCalculator.crossing(LAT, LON, from, to, SolarCalculator.SUNRISE_ELEVATION, false).orElseThrow();
        assertTrue(Math.abs(Duration.between(Instant.parse("2026-11-06T12:00:08Z"), sunrise).toSeconds()) <= 120, sunrise::toString);
        assertTrue(Math.abs(Duration.between(Instant.parse("2026-11-06T22:02:11Z"), sunset).toSeconds()) <= 120, sunset::toString);
        assertTrue(SolarCalculator.elevation(LAT, LON, Instant.parse("2026-11-06T17:01:10Z")) > 28, "solar noon elevation in early November");
        assertTrue(SolarCalculator.elevation(LAT, LON, Instant.parse("2026-11-07T05:00:00Z")) < -50, "local midnight");
    }

    @Test void goldenAndBlueHourPhasesAreOrderedAroundSunriseAndSunset() {
        LocalDate day = LocalDate.of(2026, 11, 7);
        var phases = SolarCalculator.phases(LAT, LON, TORONTO, day, day);
        assertEquals(java.util.List.of("blueMorning", "goldenMorning", "goldenEvening", "blueEvening"), phases.stream().map(p -> p.kind()).toList());
        var golden = phases.get(2);
        assertTrue(golden.start().toLocalTime().isAfter(LocalTime.of(15, 30)) && golden.end().toLocalTime().isBefore(LocalTime.of(17, 30)), golden::toString);
        assertEquals(golden.end(), phases.get(3).start(), "contiguous at -4 degrees");
        assertTrue(phases.stream().allMatch(p -> p.end().isAfter(p.start()) && p.date().equals(day)));
        // Polar night: no golden or blue hour is invented when the sun never reaches those elevations.
        assertTrue(SolarCalculator.phases(89.9, 0, ZoneOffset.UTC, LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 21)).isEmpty());
    }
}
