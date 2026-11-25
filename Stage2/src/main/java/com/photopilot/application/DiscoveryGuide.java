package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.DiscoveryRequest;
import java.time.Duration;
import java.util.*;

/**
 * Guided Discovery structure shared by the GUI stages and the CLI wizard: which {@link DiscoveryForm} fields belong to
 * which stage, which are required or advanced, and which input a validation message is about. It only groups the
 * messages that {@link DiscoveryForm#parse} and the discovery service already produce; it adds no rule of its own.
 */
public final class DiscoveryGuide {
    private DiscoveryGuide() { }

    public enum Stage {
        LOCATION_TIME, PREFERENCES, REVIEW;
        public int number() { return ordinal() + 1; }
        public String titleKey() { return "discover.stage." + name(); }
    }

    /** The input a validation message is shown next to; GENERAL when it concerns the whole request. */
    public enum Slot {
        LOCATION(Stage.LOCATION_TIME, "location"), COORDINATES(Stage.LOCATION_TIME, "latitude", "longitude"),
        DATE(Stage.LOCATION_TIME, "date"), TIME(Stage.LOCATION_TIME, "from", "until"), DURATION(Stage.LOCATION_TIME, "duration"),
        DISTANCE(Stage.LOCATION_TIME, "distance"), LISTS(Stage.PREFERENCES, "interests", "avoid"),
        NOTES(Stage.PREFERENCES, "portability", "notes"), HISTORY(Stage.PREFERENCES, "history"), GENERAL(Stage.REVIEW);
        private final Stage stage;
        private final List<String> fields;
        Slot(Stage stage, String... fields) { this.stage = stage; this.fields = List.of(fields); }
        public Stage stage() { return stage; }
        public List<String> fields() { return fields; }
        /** Inputs kept in the collapsed advanced section of stage 1. */
        public boolean advanced() { return this == COORDINATES || this == DURATION; }
    }

    public static final List<String> LOCATION_TIME = List.of("location", "latitude", "longitude", "date", "from", "until", "duration", "distance");
    public static final List<String> PREFERENCES = List.of("interests", "avoid", "portability", "notes", "history");
    /** Fields the request cannot be made without (the ones DiscoveryForm reports when empty). */
    public static final Set<String> REQUIRED = Set.of("location", "date", "from", "until", "distance");

    private static final Map<String, Slot> SLOTS = Map.ofEntries(
        Map.entry("discover.error.location", Slot.LOCATION), Map.entry("env.location.error.label", Slot.LOCATION),
        Map.entry("env.location.error.labelLength", Slot.LOCATION), Map.entry("env.location.error.invalid", Slot.LOCATION),
        Map.entry("discover.error.coordinatePair", Slot.COORDINATES), Map.entry("env.location.error.number", Slot.COORDINATES),
        Map.entry("env.location.error.latitude", Slot.COORDINATES), Map.entry("env.location.error.longitude", Slot.COORDINATES),
        Map.entry("env.location.error.coordinates", Slot.COORDINATES),
        Map.entry("env.error.date", Slot.DATE), Map.entry("env.error.pastDate", Slot.DATE), Map.entry("discover.error.farDate", Slot.DATE),
        Map.entry("env.error.time", Slot.TIME), Map.entry("env.error.emptyAvailability", Slot.TIME), Map.entry("discover.error.shortWindow", Slot.TIME),
        Map.entry("env.error.availabilityOver", Slot.TIME),
        Map.entry("discover.error.duration", Slot.DURATION), Map.entry("discover.error.durationWindow", Slot.DURATION),
        Map.entry("discover.error.distance", Slot.DISTANCE), Map.entry("discover.error.list", Slot.LISTS),
        Map.entry("discover.error.notes", Slot.NOTES), Map.entry("discover.error.history", Slot.HISTORY));

    public static Stage stage(String field) { return LOCATION_TIME.contains(field) ? Stage.LOCATION_TIME : PREFERENCES.contains(field) ? Stage.PREFERENCES : Stage.REVIEW; }
    public static boolean required(String field) { return REQUIRED.contains(field); }
    public static Slot slot(LocalizedMessage message) { return SLOTS.getOrDefault(message.key(), Slot.GENERAL); }

    /** The messages grouped by the input they concern, in slot order (stage 1 inputs first). */
    public static Map<Slot, List<LocalizedMessage>> group(List<LocalizedMessage> messages) {
        var grouped = new EnumMap<Slot, List<LocalizedMessage>>(Slot.class);
        for (var message : messages) grouped.computeIfAbsent(slot(message), s -> new ArrayList<>()).add(message);
        return grouped;
    }

    /**
     * The problems of a {@link DiscoveryForm#parse} result that a stage must resolve before the user may leave it
     * forwards: those about the inputs of {@code stage} or an earlier stage. Later stages are checked when reached.
     */
    public static List<LocalizedMessage> problems(Result<?> parsed, Stage stage) {
        if (parsed.isSuccess()) return List.of();
        return parsed.messages().stream().filter(m -> slot(m).stage().ordinal() <= stage.ordinal()).toList();
    }

    /** The first stage with a problem in {@code messages}, or empty when there is none. */
    public static Optional<Stage> firstStage(List<LocalizedMessage> messages) {
        return messages.stream().map(m -> slot(m).stage()).min(Comparator.naturalOrder());
    }

    /**
     * Length of a typed availability window and whether it continues past midnight, by the request's own rule; empty
     * when a time does not parse or the request reports the window as empty (equal start and end).
     */
    public record Window(Duration length, boolean overnight) { }
    public static Optional<Window> window(String from, String until) {
        var start = DiscoveryForm.clock(from == null ? "" : from.strip());
        var end = DiscoveryForm.clock(until == null ? "" : until.strip());
        if (start.isEmpty() || end.isEmpty()) return Optional.empty();
        var probe = probe(start.get(), end.get(), 0);
        if (reports(probe, "env.error.emptyAvailability")) return Optional.empty();
        return Optional.of(new Window(probe.window(), probe.crossesMidnight()));
    }

    /**
     * A typed maximum travel distance in km when the request would accept it (the form's number format and the
     * request's range), or empty; for display only, validation stays {@link DiscoveryForm#parse}.
     */
    public static Optional<Double> distance(String text) {
        String value = text == null ? "" : text.strip();
        var parsed = DiscoveryForm.parse(Map.of("distance", value), null, Optional.empty());
        if (problems(parsed, Stage.LOCATION_TIME).stream().anyMatch(m -> slot(m) == Slot.DISTANCE)) return Optional.empty();
        double km = Double.parseDouble(value);
        return reports(probe(null, null, km), "discover.error.distance") ? Optional.empty() : Optional.of(km);
    }

    /**
     * Which list inputs ({@code interests}, {@code avoid}) a {@link Slot#LISTS} problem is about: each list is checked
     * alone by the request's own rule, so a view can mark and focus only the list to correct. Empty when neither list
     * alone breaks the rule.
     */
    public static List<String> listInputs(String interests, String avoid) {
        var found = new ArrayList<String>();
        var interestEntries = DiscoveryForm.entries(interests == null ? "" : interests);
        var avoidEntries = DiscoveryForm.entries(avoid == null ? "" : avoid);
        if (reports(probe(interestEntries, List.of()), "discover.error.list")) found.add("interests");
        if (reports(probe(List.of(), avoidEntries), "discover.error.list")) found.add("avoid");
        return found;
    }

    private static DiscoveryRequest probe(java.time.LocalTime from, java.time.LocalTime until, double km) {
        return new DiscoveryRequest(null, null, from, until, Optional.empty(), km, List.of(), List.of(), "", "", true, Optional.empty());
    }
    private static DiscoveryRequest probe(List<String> interests, List<String> avoid) {
        return new DiscoveryRequest(null, null, null, null, Optional.empty(), 0, interests, avoid, "", "", true, Optional.empty());
    }
    private static boolean reports(DiscoveryRequest probe, String key) {
        var checked = probe.validate();
        return !checked.isSuccess() && checked.messages().stream().anyMatch(m -> m.key().equals(key));
    }
}
