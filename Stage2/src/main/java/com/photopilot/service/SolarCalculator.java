package com.photopilot.service;

import java.time.*;
import java.util.*;
import com.photopilot.model.EnvironmentAssessment.LightPhase;

/**
 * Deterministic solar-position model for F09 light phases. Implements the NOAA Solar Calculator equations
 * (after Meeus, "Astronomical Algorithms"; https://gml.noaa.gov/grad/solcalc/calcdetails.html) for the geometric
 * solar elevation. Golden hour (-4 to +6 degrees) and blue hour (-6 to -4 degrees) follow the common photographic
 * convention. Results are estimates: terrain, buildings and atmospheric conditions are not modelled.
 */
public final class SolarCalculator {
    public static final double GOLDEN_LOW = -4, GOLDEN_HIGH = 6, BLUE_LOW = -6;
    /** Geometric elevation of the sun's centre at standard sunrise/sunset (refraction and semi-diameter). */
    public static final double SUNRISE_ELEVATION = -0.833;
    /** Standard twilight limits (civil, nautical, astronomical), as used by the sun data provider. */
    public static final double CIVIL = -6, NAUTICAL = -12, ASTRONOMICAL = -18;
    public static final String SOURCE = "NOAA solar position model (computed estimate)";
    private SolarCalculator() { }

    /** Geometric elevation and azimuth in degrees; azimuth is measured clockwise from true north (not magnetic). */
    public record Position(double elevation, double azimuth) { }

    /** Geometric solar elevation in degrees at {@code time} for latitude/longitude in degrees (east positive). */
    public static double elevation(double latitude, double longitude, Instant time) {
        return position(latitude, longitude, time).elevation();
    }

    /** Solar elevation and azimuth at {@code time} (same NOAA equations as {@link #elevation}). */
    public static Position position(double latitude, double longitude, Instant time) {
        double julianDay = time.getEpochSecond() / 86400.0 + time.getNano() / 86_400e9 + 2440587.5;
        double t = (julianDay - 2451545.0) / 36525.0;
        double meanLongitude = normalize(280.46646 + t * (36000.76983 + t * 0.0003032));
        double meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t);
        double eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
        double m = Math.toRadians(meanAnomaly);
        double center = Math.sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + Math.sin(2 * m) * (0.019993 - 0.000101 * t)
            + Math.sin(3 * m) * 0.000289;
        double omega = Math.toRadians(125.04 - 1934.136 * t);
        double apparentLongitude = Math.toRadians(meanLongitude + center - 0.00569 - 0.00478 * Math.sin(omega));
        double meanObliquity = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60;
        double obliquity = Math.toRadians(meanObliquity + 0.00256 * Math.cos(omega));
        double declination = Math.asin(Math.sin(obliquity) * Math.sin(apparentLongitude));
        double y = Math.pow(Math.tan(obliquity / 2), 2);
        double l0 = Math.toRadians(meanLongitude);
        double equationOfTime = 4 * Math.toDegrees(y * Math.sin(2 * l0) - 2 * eccentricity * Math.sin(m)
            + 4 * eccentricity * y * Math.sin(m) * Math.cos(2 * l0) - 0.5 * y * y * Math.sin(4 * l0) - 1.25 * eccentricity * eccentricity * Math.sin(2 * m));
        double utcMinutes = (time.getEpochSecond() % 86400 + 86400) % 86400 / 60.0;
        double trueSolarTime = ((utcMinutes + equationOfTime + 4 * longitude) % 1440 + 1440) % 1440;
        double hourAngle = Math.toRadians(trueSolarTime / 4 < 0 ? trueSolarTime / 4 + 180 : trueSolarTime / 4 - 180);
        double lat = Math.toRadians(latitude);
        double cosZenith = Math.sin(lat) * Math.sin(declination) + Math.cos(lat) * Math.cos(declination) * Math.cos(hourAngle);
        double elevation = 90 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosZenith))));
        // Hour angle is negative before solar noon, so the morning sun lies east (azimuth below 180 degrees).
        double azimuth = normalize(Math.toDegrees(Math.atan2(Math.sin(hourAngle),
            Math.cos(hourAngle) * Math.sin(lat) - Math.tan(declination) * Math.cos(lat))) + 180);
        return new Position(elevation, azimuth);
    }

    /** Natural-light category of a geometric elevation (standard thresholds; refraction only through -0.833°). */
    public static com.photopilot.model.EnvironmentAnalysis.LightKind light(double elevation) {
        if (elevation >= SUNRISE_ELEVATION) return com.photopilot.model.EnvironmentAnalysis.LightKind.DAY;
        if (elevation >= CIVIL) return com.photopilot.model.EnvironmentAnalysis.LightKind.CIVIL;
        if (elevation >= NAUTICAL) return com.photopilot.model.EnvironmentAnalysis.LightKind.NAUTICAL;
        if (elevation >= ASTRONOMICAL) return com.photopilot.model.EnvironmentAnalysis.LightKind.ASTRONOMICAL;
        return com.photopilot.model.EnvironmentAnalysis.LightKind.NIGHT;
    }

    /**
     * Solar positions every {@code step} from the start of local date {@code from} to the start of the day after
     * {@code to} (both ends included), following the instant time-line so DST days have 23 or 25 hours of samples.
     * For charts only: event times keep their one-minute calculation.
     */
    public static List<com.photopilot.model.EnvironmentAnalysis.SolarSample> samples(double latitude, double longitude, ZoneId zone,
            LocalDate from, LocalDate to, Duration step) {
        if (step == null || step.isNegative() || step.isZero()) throw new IllegalArgumentException("step must be positive");
        var found = new ArrayList<com.photopilot.model.EnvironmentAnalysis.SolarSample>();
        ZonedDateTime cursor = from.atStartOfDay(zone), end = to.plusDays(1).atStartOfDay(zone);
        for (; !cursor.isAfter(end); cursor = cursor.plus(step)) {
            var p = position(latitude, longitude, cursor.toInstant());
            found.add(new com.photopilot.model.EnvironmentAnalysis.SolarSample(cursor, p.elevation(), p.azimuth()));
        }
        return List.copyOf(found);
    }

    /**
     * Night, astronomical/nautical/civil twilight and daylight on each local date in [from, to] at one-minute
     * resolution: each minute takes the category of its elevation. Covers polar day and night (one band all day).
     */
    public static List<com.photopilot.model.EnvironmentAnalysis.LightBand> bands(double latitude, double longitude, ZoneId zone, LocalDate from, LocalDate to) {
        var found = new ArrayList<com.photopilot.model.EnvironmentAnalysis.LightBand>();
        ZonedDateTime cursor = from.atStartOfDay(zone), end = to.plusDays(1).atStartOfDay(zone), started = cursor;
        var open = light(elevation(latitude, longitude, cursor.toInstant()));
        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(1);
            var kind = light(elevation(latitude, longitude, next.toInstant()));
            if (kind != open || !next.isBefore(end)) {
                found.add(new com.photopilot.model.EnvironmentAnalysis.LightBand(open, started, next));
                open = kind; started = next;
            }
            cursor = next;
        }
        return List.copyOf(found);
    }

    /** First minute in [from, to) where the elevation crosses {@code threshold} in the given direction. */
    public static Optional<Instant> crossing(double latitude, double longitude, Instant from, Instant to, double threshold, boolean rising) {
        double previous = elevation(latitude, longitude, from);
        for (Instant t = from.plusSeconds(60); t.isBefore(to); t = t.plusSeconds(60)) {
            double current = elevation(latitude, longitude, t);
            if (rising ? previous < threshold && current >= threshold : previous >= threshold && current < threshold) return Optional.of(t);
            previous = current;
        }
        return Optional.empty();
    }

    /**
     * Golden- and blue-hour phases on each local date in [from, to], at one-minute resolution. Morning/evening is
     * decided by whether the sun is rising; a phase is attributed to the local date on which it starts.
     */
    public static List<LightPhase> phases(double latitude, double longitude, ZoneId zone, LocalDate from, LocalDate to) {
        var found = new ArrayList<LightPhase>();
        ZonedDateTime cursor = from.atStartOfDay(zone), end = to.plusDays(1).atStartOfDay(zone);
        String open = null; ZonedDateTime started = null;
        double previous = elevation(latitude, longitude, cursor.toInstant());
        // plusMinutes follows the instant time-line, so DST transitions neither skip nor repeat minutes.
        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMinutes(1);
            double current = elevation(latitude, longitude, next.toInstant());
            String kind = classify(previous, current);
            if (!Objects.equals(kind, open)) {
                if (open != null) found.add(new LightPhase(open, started.toLocalDate(), started, cursor, SOURCE));
                open = kind; started = cursor;
            }
            previous = current; cursor = next;
        }
        if (open != null) found.add(new LightPhase(open, started.toLocalDate(), started, cursor, SOURCE));
        return List.copyOf(found);
    }
    private static String classify(double elevation, double next) {
        String time = next >= elevation ? "Morning" : "Evening";
        if (elevation >= GOLDEN_LOW && elevation <= GOLDEN_HIGH) return "golden" + time;
        if (elevation >= BLUE_LOW && elevation < GOLDEN_LOW) return "blue" + time;
        return null;
    }
    private static double normalize(double degrees) { return (degrees % 360 + 360) % 360; }
}
