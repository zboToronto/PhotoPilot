package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.service.LocationService;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Shared GUI/CLI F09 input fields: prefill from the session, strict locale-neutral parsing, no guessed values. */
public final class EnvironmentForm {
    private EnvironmentForm() { }
    /** {@code zone}: optional regional IANA time zone the user confirms for coordinates no provider resolves (C16). */
    public static final List<String> FIELDS = List.of("location", "latitude", "longitude", "zone", "date", "from", "until", "lead", "activity", "goals");
    private static final Pattern CLOCK = Pattern.compile("\\d{2}:\\d{2}"), COORDINATE = Pattern.compile("-?\\d{1,3}(\\.\\d{1,8})?"),
        MINUTES = Pattern.compile("\\d{1,3}");
    private static final DateTimeFormatter HOURS = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /**
     * Values already in the session: the confirmed requirement's location/date/start time (and end from its duration),
     * otherwise the session's own location label and date. Coordinates appear only if they were stored with the place.
     */
    public static Map<String, String> defaults(PhotographySession session) {
        var values = new LinkedHashMap<String, String>();
        FIELDS.forEach(field -> values.put(field, ""));
        if (session == null) return values;
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        var place = requirement.flatMap(ShootRequirement::location).or(session::location);
        place.ifPresent(p -> {
            values.put("location", p.label());
            p.latitude().ifPresent(lat -> values.put("latitude", coordinate(lat)));
            p.longitude().ifPresent(lon -> values.put("longitude", coordinate(lon)));
        });
        values.put("date", requirement.flatMap(ShootRequirement::date).orElse(session.date()).toString());
        requirement.flatMap(ShootRequirement::time).ifPresent(time -> {
            values.put("from", time.toLocalTime().format(HOURS));
            requirement.flatMap(ShootRequirement::duration).filter(d -> d.compareTo(Duration.ofHours(24)) < 0)
                .ifPresent(d -> values.put("until", time.toLocalTime().plus(d).format(HOURS)));
        });
        return values;
    }

    /**
     * Fields filled from a chosen place-lookup match: its time zone becomes the fallback hint and the location stays
     * marked approximate with its original lookup source. Callers pass null once the user has edited the coordinates.
     */
    public static Result<EnvironmentRequest> parse(Map<String, String> input, LocationService location, PlaceCandidate chosen) {
        if (chosen == null) return parse(input, location, Optional.empty());
        String label = value(input, "location");
        if (!(label.equals(chosen.query()) || label.equals(chosen.displayName()) || label.equals(chosen.name()))
                || !matchesField(value(input, "latitude"), chosen.latitude()) || !matchesField(value(input, "longitude"), chosen.longitude()))
            return Result.failureKey("env.resolve.changed");
        return reviewed(input, location, chosen.location(), chosen.zone(), Optional.of(chosen));
    }

    private static boolean matchesField(String text, double coordinate) {
        return text.equals(coordinate(coordinate)) || text.equals(Double.toString(coordinate))
            || (COORDINATE.matcher(text).matches() && Double.parseDouble(text) == coordinate);
    }

    /** Presentation fields for a confirmed candidate. Calculations use the candidate's full precision. */
    public static void apply(Map<String, String> fields, PlaceCandidate candidate) {
        fields.put("location", candidate.displayName());
        fields.put("latitude", coordinate(candidate.latitude()));
        fields.put("longitude", coordinate(candidate.longitude()));
    }

    /**
     * C16: a zone the user typed must be a regional IANA id ("America/Toronto"): offsets, "UTC"/"GMT" and Etc/ ids say
     * nothing about daylight saving at the location and are refused, like the providers' unverified answers.
     */
    public static Result<ZoneId> zone(String text) {
        String id = text == null ? "" : text.strip();
        try {
            if (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/") && ZoneId.getAvailableZoneIds().contains(id)) return Result.success(ZoneId.of(id));
        } catch (DateTimeException invalid) { /* reported below */ }
        return Result.failureKey("env.error.zoneId", id);
    }

    /** Reviewed fields -> validated request; every problem is reported at once and nothing is contacted. */
    public static Result<EnvironmentRequest> parse(Map<String, String> input, LocationService location, Optional<ZoneId> zoneHint) {
        return reviewed(input, location, null, zoneHint, Optional.empty());
    }

    /** A device position carries its original permission stamp through assessment construction. */
    public static Result<EnvironmentRequest> device(Map<String,String> input, LocationService location, GeoLocation position) {
        if (!position.fromDevice() || !position.validate().isSuccess()) return Result.failureKey("env.location.error.deviceFailed");
        return reviewed(input, location, position, Optional.empty(), Optional.empty());
    }

    public static Result<GeoLocation> manual(Map<String,String> input, LocationService location) {
        String label = value(input, "location"), lat = value(input, "latitude"), lon = value(input, "longitude");
        if (label.isEmpty() && lat.isEmpty() && lon.isEmpty()) return Result.failureKey("env.location.error.missing");
        if (lat.isEmpty() || lon.isEmpty()) return Result.failureKey("env.location.error.coordinates");
        if (!COORDINATE.matcher(lat).matches() || !COORDINATE.matcher(lon).matches()) return Result.failureKey("env.location.error.number");
        return location.manual(label, Double.parseDouble(lat), Double.parseDouble(lon));
    }

    private static Result<EnvironmentRequest> reviewed(Map<String,String> input, LocationService location, GeoLocation place,
            Optional<ZoneId> zoneHint, Optional<PlaceCandidate> accepted) {
        var found = new ArrayList<LocalizedMessage>();
        if (place == null) {
            var manual = manual(input, location);
            place = manual.value().orElse(null);
            found.addAll(manual.messages());
        }
        LocalDate date = null;
        try { date = LocalDate.parse(value(input, "date")); } catch (DateTimeParseException invalid) { found.add(LocalizedMessage.of("env.error.date")); }
        LocalTime from = clock(value(input, "from")), until = clock(value(input, "until"));
        if (from == null || until == null) found.add(LocalizedMessage.of("env.error.time"));
        Optional<Duration> lead = Optional.empty();
        String leadText = value(input, "lead");
        if (!leadText.isEmpty()) {
            if (MINUTES.matcher(leadText).matches()) lead = Optional.of(Duration.ofMinutes(Integer.parseInt(leadText)));
            else found.add(LocalizedMessage.of("env.error.lead", EnvironmentRequest.MAX_LEAD.toMinutes()));
        }
        var goals = Arrays.stream(value(input, "goals").split(";")).map(String::strip).filter(goal -> !goal.isEmpty()).toList();
        // An entered zone replaces a lookup hint: the user confirmed it (it still yields to a provider-verified zone).
        Optional<ZoneId> entered = Optional.empty();
        if (!value(input, "zone").isEmpty()) {
            var zone = zone(value(input, "zone"));
            if (zone.isSuccess()) entered = zone.value(); else found.addAll(zone.messages());
        }
        if (!found.isEmpty()) return Result.failures(found);
        return new EnvironmentRequest(place, date, from, until, lead, entered.or(() -> zoneHint), value(input, "activity"), goals, entered.isPresent(), accepted).validate();
    }

    /**
     * Pre-flight check shown next to the date and time inputs before anything is requested: where the reviewed date
     * and local times disagree with the session's confirmed requirement or its scheduled date (the same rule the
     * assessment applies, {@link com.photopilot.validation.FeasibilityValidator#requirementConflicts}). Unreadable
     * values are left to the form validation. Nothing is changed.
     */
    public static List<LocalizedMessage> conflicts(PhotographySession session, Map<String, String> input) {
        var found = new ArrayList<LocalizedMessage>();
        if (session == null) return found;
        LocalDate date;
        try { date = LocalDate.parse(value(input, "date")); } catch (DateTimeParseException invalid) { return found; }
        LocalTime from = clock(value(input, "from")), until = clock(value(input, "until"));
        session.requirements().filter(ShootRequirement::confirmed).ifPresent(requirement -> found.addAll(
            com.photopilot.validation.FeasibilityValidator.requirementConflicts(requirement, date, from, until,
                com.photopilot.validation.FeasibilityValidator.wallClockLength(from, until).orElse(null))));
        if (!session.date().equals(date)) found.add(LocalizedMessage.of("env.notice.sessionDate", session.date().toString(), date.toString()));
        return found;
    }

    /** The request back as editable fields (CLI "env again" and redisplay). */
    public static Map<String, String> format(EnvironmentRequest request) {
        var values = new LinkedHashMap<String, String>();
        values.put("location", request.place().label());
        values.put("latitude", request.place().latitude().map(EnvironmentForm::coordinate).orElse(""));
        values.put("longitude", request.place().longitude().map(EnvironmentForm::coordinate).orElse(""));
        values.put("zone", request.zoneEntered() ? request.zoneHint().map(ZoneId::getId).orElse("") : "");
        values.put("date", request.date().toString());
        values.put("from", request.availableFrom().format(HOURS));
        values.put("until", request.availableUntil().format(HOURS));
        values.put("lead", request.leadTime().map(d -> String.valueOf(d.toMinutes())).orElse(""));
        values.put("activity", request.activity());
        values.put("goals", String.join("; ", request.goals()));
        return values;
    }

    public static String coordinate(double value) { return String.format(Locale.ROOT, "%.5f", value); }
    private static LocalTime clock(String text) {
        if (!CLOCK.matcher(text).matches()) return null;
        try { return LocalTime.parse(text, HOURS); } catch (DateTimeParseException invalid) { return null; }
    }
    private static String value(Map<String, String> input, String key) { return Objects.toString(input.get(key), "").strip(); }
}
