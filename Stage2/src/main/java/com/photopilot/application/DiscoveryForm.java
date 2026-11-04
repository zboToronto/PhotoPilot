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

/**
 * Shared GUI/CLI F02 input fields: strict locale-neutral parsing, every problem reported at once, nothing guessed.
 * A place name alone is a valid manual or regional location; coordinates are optional and enable distance and
 * weather checks. Interests and avoided subjects are separated by ";" or ",".
 */
public final class DiscoveryForm {
    private DiscoveryForm() { }
    public static final List<String> FIELDS = List.of("location", "latitude", "longitude", "date", "from", "until", "duration", "distance",
        "interests", "avoid", "portability", "notes", "history");
    private static final Pattern CLOCK = Pattern.compile("\\d{2}:\\d{2}"), COORDINATE = Pattern.compile("-?\\d{1,3}(\\.\\d{1,8})?"),
        MINUTES = Pattern.compile("\\d{1,4}"), DISTANCE = Pattern.compile("\\d{1,4}(\\.\\d{1,3})?");
    private static final DateTimeFormatter HOURS = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /** Starting values: today's date and history enabled; the user enters everything else. */
    public static Map<String, String> defaults(LocalDate today) {
        var values = new LinkedHashMap<String, String>();
        FIELDS.forEach(field -> values.put(field, ""));
        values.put("date", today.toString());
        values.put("history", "yes");
        return values;
    }

    /** Fields filled from a chosen place-lookup match: approximate point, its time zone as the fallback hint. */
    public static Result<DiscoveryRequest> parse(Map<String, String> input, LocationService location, PlaceCandidate chosen) {
        var parsed = parse(input, location, chosen == null ? Optional.<ZoneId>empty() : chosen.zone());
        if (chosen == null || !parsed.isSuccess()) return parsed;
        var r = parsed.value().orElseThrow();
        if (!r.hasCoordinates()) return parsed;
        var place = new GeoLocation(r.place().label(), r.place().latitude().orElseThrow(), r.place().longitude().orElseThrow(), true, false, Optional.empty());
        return new DiscoveryRequest(place, r.date(), r.availableFrom(), r.availableUntil(), r.maxActivity(), r.maxDistanceKm(), r.interests(),
            r.avoid(), r.portability(), r.notes(), r.useHistory(), r.zoneHint()).validate();
    }

    public static Result<DiscoveryRequest> parse(Map<String, String> input, LocationService location, Optional<ZoneId> zoneHint) {
        var found = new ArrayList<LocalizedMessage>();
        String label = value(input, "location"), latText = value(input, "latitude"), lonText = value(input, "longitude");
        GeoLocation place = null;
        if (label.isEmpty()) found.add(LocalizedMessage.of("discover.error.location"));
        else if (latText.isEmpty() && lonText.isEmpty()) {
            if (label.length() > LocationService.MAX_LABEL) found.add(LocalizedMessage.of("env.location.error.labelLength", LocationService.MAX_LABEL));
            else place = GeoLocation.manual(label);
        } else if (latText.isEmpty() || lonText.isEmpty()) found.add(LocalizedMessage.of("discover.error.coordinatePair"));
        else if (!COORDINATE.matcher(latText).matches() || !COORDINATE.matcher(lonText).matches()) found.add(LocalizedMessage.of("env.location.error.number"));
        else {
            var manual = location.manual(label, Double.parseDouble(latText), Double.parseDouble(lonText));
            if (manual.isSuccess()) place = manual.value().orElseThrow(); else found.addAll(manual.messages());
        }
        LocalDate date = null;
        try { date = LocalDate.parse(value(input, "date")); } catch (DateTimeParseException invalid) { found.add(LocalizedMessage.of("env.error.date")); }
        LocalTime from = clock(value(input, "from")), until = clock(value(input, "until"));
        if (from == null || until == null) found.add(LocalizedMessage.of("env.error.time"));
        Optional<Duration> duration = Optional.empty();
        String durationText = value(input, "duration");
        if (!durationText.isEmpty()) {
            if (MINUTES.matcher(durationText).matches()) duration = Optional.of(Duration.ofMinutes(Integer.parseInt(durationText)));
            else found.add(LocalizedMessage.of("discover.error.duration", DiscoveryRequest.MIN_ACTIVITY.toMinutes()));
        }
        double distance = -1;
        String distanceText = value(input, "distance");
        if (DISTANCE.matcher(distanceText).matches()) distance = Double.parseDouble(distanceText);
        else found.add(LocalizedMessage.of("discover.error.distance", (int) DiscoveryRequest.MAX_DISTANCE_KM));
        String history = value(input, "history").toLowerCase(Locale.ROOT);
        if (!Set.of("", "yes", "no").contains(history)) found.add(LocalizedMessage.of("discover.error.history"));
        if (!found.isEmpty()) return Result.failures(found);
        return new DiscoveryRequest(place, date, from, until, duration, distance, list(value(input, "interests")), list(value(input, "avoid")),
            value(input, "portability"), value(input, "notes"), !history.equals("no"), zoneHint).validate();
    }

    /** The request back as editable fields (CLI "discover again" and redisplay). */
    public static Map<String, String> format(DiscoveryRequest request) {
        var values = new LinkedHashMap<String, String>();
        values.put("location", request.place().label());
        values.put("latitude", request.place().latitude().map(EnvironmentForm::coordinate).orElse(""));
        values.put("longitude", request.place().longitude().map(EnvironmentForm::coordinate).orElse(""));
        values.put("date", request.date().toString());
        values.put("from", request.availableFrom().format(HOURS));
        values.put("until", request.availableUntil().format(HOURS));
        values.put("duration", request.maxActivity().map(d -> String.valueOf(d.toMinutes())).orElse(""));
        values.put("distance", com.photopilot.validation.CompatibilityValidator.num(request.maxDistanceKm()));
        values.put("interests", String.join("; ", request.interests()));
        values.put("avoid", String.join("; ", request.avoid()));
        values.put("portability", request.portability());
        values.put("notes", request.notes());
        values.put("history", request.useHistory() ? "yes" : "no");
        return values;
    }

    private static List<String> list(String text) { return Arrays.stream(text.split("[;,；，]")).map(String::strip).filter(s -> !s.isEmpty()).toList(); }
    private static LocalTime clock(String text) {
        if (!CLOCK.matcher(text).matches()) return null;
        try { return LocalTime.parse(text, HOURS); } catch (DateTimeParseException invalid) { return null; }
    }
    private static String value(Map<String, String> input, String key) { return Objects.toString(input.get(key), "").strip(); }
}
