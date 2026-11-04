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
    public static final List<String> FIELDS = List.of("location", "latitude", "longitude", "date", "from", "until", "lead", "activity", "goals");
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
     * marked approximate (a gazetteer point). Callers pass null once the user has edited the coordinates.
     */
    public static Result<EnvironmentRequest> parse(Map<String, String> input, LocationService location, PlaceCandidate chosen) {
        var parsed = parse(input, location, chosen == null ? Optional.<ZoneId>empty() : chosen.zone());
        if (chosen == null || !parsed.isSuccess()) return parsed;
        var r = parsed.value().orElseThrow();
        var place = new GeoLocation(r.place().label(), r.place().latitude().orElseThrow(), r.place().longitude().orElseThrow(), true, false, Optional.empty());
        return new EnvironmentRequest(place, r.date(), r.availableFrom(), r.availableUntil(), r.leadTime(), r.zoneHint(), r.activity(), r.goals()).validate();
    }

    /** Reviewed fields -> validated request; every problem is reported at once and nothing is contacted. */
    public static Result<EnvironmentRequest> parse(Map<String, String> input, LocationService location, Optional<ZoneId> zoneHint) {
        var found = new ArrayList<LocalizedMessage>();
        String label = value(input, "location"), latText = value(input, "latitude"), lonText = value(input, "longitude");
        GeoLocation place = null;
        if (label.isEmpty() && latText.isEmpty() && lonText.isEmpty()) found.add(LocalizedMessage.of("env.location.error.missing"));
        else if (latText.isEmpty() || lonText.isEmpty()) found.add(LocalizedMessage.of("env.location.error.coordinates"));
        else if (!COORDINATE.matcher(latText).matches() || !COORDINATE.matcher(lonText).matches()) found.add(LocalizedMessage.of("env.location.error.number"));
        else {
            var manual = location.manual(label, Double.parseDouble(latText), Double.parseDouble(lonText));
            if (manual.isSuccess()) place = manual.value().orElseThrow(); else found.addAll(manual.messages());
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
        if (!found.isEmpty()) return Result.failures(found);
        return new EnvironmentRequest(place, date, from, until, lead, zoneHint, value(input, "activity"), goals).validate();
    }

    /** The request back as editable fields (CLI "env again" and redisplay). */
    public static Map<String, String> format(EnvironmentRequest request) {
        var values = new LinkedHashMap<String, String>();
        values.put("location", request.place().label());
        values.put("latitude", request.place().latitude().map(EnvironmentForm::coordinate).orElse(""));
        values.put("longitude", request.place().longitude().map(EnvironmentForm::coordinate).orElse(""));
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
