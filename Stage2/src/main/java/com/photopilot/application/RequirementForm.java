package com.photopilot.application;

import com.photopilot.common.Result;
import com.photopilot.i18n.*;
import com.photopilot.model.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared F03 GUI/CLI field text: one parser and formatter so both interfaces apply identical rules.
 * Blank text always means "not specified"; nothing is defaulted. Domain validation stays in ShootRequirement.
 */
public final class RequirementForm {
    private RequirementForm() { }
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern DURATION = Pattern.compile("(?i)^(?:(\\d+(?:\\.\\d+)?)\\s*h(?:ours?|rs?)?)?\\s*(?:(\\d+)\\s*m(?:in(?:utes?)?)?)?$");
    private static final Pattern WEIGHT = Pattern.compile("(?i)^(\\d+(?:\\.\\d+)?)\\s*(g|kg)?$");

    /** Manual-entry draft (AI unavailable or bypassed); the user's request text is kept as the source. */
    public static ShootRequirement blank(String originalText) {
        return new ShootRequirement(Objects.toString(originalText, "").strip(), "", List.of(), Optional.empty(), Optional.empty(), "",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), false);
    }

    public static Map<String, String> format(ShootRequirement r) {
        var values = new LinkedHashMap<String, String>();
        values.put("subject", r.subject());
        values.put("goals", String.join("; ", r.goals()));
        values.put("date", r.date().map(Object::toString).orElse(""));
        values.put("time", r.time().map(time -> time.format(CLOCK)).orElse(""));
        values.put("timeWindow", r.timeWindow());
        values.put("location", r.location().map(GeoLocation::label).orElse(""));
        values.put("duration", r.duration().map(RequirementForm::duration).orElse(""));
        values.put("cameraCount", r.cameraCount().map(String::valueOf).orElse(""));
        values.put("lensChanges", r.lensChangesAllowed().map(allowed -> allowed ? "allowed" : "avoid").orElse(""));
        values.put("maxWeight", r.maxWeightGrams().map(RequirementForm::grams).orElse(""));
        values.put("portability", r.portability());
        values.put("gearConstraints", String.join("; ", r.gearConstraints()));
        values.put("priorities", pairs(r.priorities()));
        values.put("constraints", pairs(r.fixedConstraints()));
        return values;
    }

    /**
     * Applies edited field text to a draft. Absent keys keep the draft's value. Unreadable text fails with
     * every field error at once and leaves the draft unchanged; edited fields lose their AI review flag.
     */
    public static Result<ShootRequirement> apply(ShootRequirement base, Map<String, String> edits, ZoneId zone) {
        if (base == null || edits == null) return Result.failureKey("requirement.error.noDraft");
        var unknown = edits.keySet().stream().filter(key -> !ShootRequirement.FIELDS.contains(key)).findFirst();
        if (unknown.isPresent()) return Result.failureKey("error.cli.option", unknown.get());
        var current = format(base);
        var values = new LinkedHashMap<>(current); edits.forEach((key, value) -> values.put(key, Objects.toString(value, "").strip()));
        var errors = new ArrayList<LocalizedMessage>();
        Optional<LocalDate> date = parse(values.get("date"), "requirement.error.dateFormat", errors, LocalDate::parse);
        Optional<LocalTime> clock = parse(values.get("time"), "requirement.error.timeFormat", errors, LocalTime::parse);
        Optional<ZonedDateTime> time = Optional.empty();
        if (clock.isPresent()) {
            // An unreadable date already has its own error; only a missing one needs this message.
            if (date.isEmpty()) { if (values.get("date").isBlank()) errors.add(LocalizedMessage.of("requirement.error.timeWithoutDate")); }
            else time = Optional.of(ZonedDateTime.of(date.get(), clock.get(), base.time().map(ZonedDateTime::getZone).orElse(zone)));
        }
        Optional<Duration> duration = parse(values.get("duration"), "requirement.error.durationFormat", errors, RequirementForm::duration);
        Optional<Integer> cameras = parse(values.get("cameraCount"), "requirement.error.cameraCount", errors, text -> {
            int count = Integer.parseInt(text); if (count < 1 || count > ShootRequirement.MAX_CAMERAS) throw new IllegalArgumentException(); return count;
        }, ShootRequirement.MAX_CAMERAS);
        Optional<Boolean> lens = parse(values.get("lensChanges"), "requirement.error.lensChanges", errors, RequirementForm::lensChanges);
        Optional<Double> weight = parse(values.get("maxWeight"), "requirement.error.weight", errors, RequirementForm::weight);
        Map<String, Integer> priorities = pairs(values.get("priorities"), "requirement.error.prioritiesFormat", errors, Integer::parseInt);
        Map<String, String> constraints = pairs(values.get("constraints"), "requirement.error.constraintsFormat", errors, text -> text);
        if (!errors.isEmpty()) return Result.failures(errors);
        String place = values.get("location");
        Optional<GeoLocation> location = place.isBlank() ? Optional.empty()
            : base.location().filter(existing -> existing.label().equals(place)).or(() -> Optional.of(GeoLocation.manual(place)));
        var changed = new HashSet<String>(); values.forEach((key, value) -> { if (!value.equals(current.get(key))) changed.add(key); });
        var review = new HashSet<>(base.reviewFields()); review.removeAll(changed);
        return Result.success(new ShootRequirement(base.originalText(), values.get("subject"), list(values.get("goals")), time, date,
            values.get("timeWindow"), location, duration, cameras, lens, weight, values.get("portability"), list(values.get("gearConstraints")),
            priorities, constraints, base.clarificationQuestions(), false).withReviewFields(review));
    }

    /** Readable summary shared by the CLI and the GUI saved-requirement card. */
    public static List<String> describe(ShootRequirement r, LocalizationService l) {
        var values = format(r); var lines = new ArrayList<String>();
        for (String field : ShootRequirement.FIELDS) {
            String value = values.get(field);
            if (field.equals("lensChanges") && !value.isEmpty()) value = l.text("requirement.lens." + value);
            if (field.equals("maxWeight") && !value.isEmpty()) value = value + " g";
            if (field.equals("time") && !value.isEmpty()) value = value + " (" + r.time().orElseThrow().getZone().getId() + ")";
            lines.add(l.text("requirement.line", l.text("requirement.field." + field), value.isEmpty() ? l.text("requirement.unspecified") : value));
        }
        return lines;
    }

    public static String duration(Duration value) {
        long hours = value.toHours(), minutes = value.toMinutesPart();
        return hours == 0 ? minutes + "m" : minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
    }
    static Duration duration(String text) {
        if (text.toUpperCase(Locale.ROOT).startsWith("P")) return positive(Duration.parse(text.toUpperCase(Locale.ROOT)));
        if (text.matches("\\d+")) return positive(Duration.ofMinutes(Long.parseLong(text)));
        var match = DURATION.matcher(text.strip());
        if (!match.matches() || match.group(1) == null && match.group(2) == null) throw new IllegalArgumentException();
        long minutes = Math.round((match.group(1) == null ? 0 : Double.parseDouble(match.group(1)) * 60) + (match.group(2) == null ? 0 : Long.parseLong(match.group(2))));
        return positive(Duration.ofMinutes(minutes));
    }
    private static Duration positive(Duration value) {
        if (value.isZero() || value.isNegative() || value.compareTo(ShootRequirement.MAX_DURATION) > 0) throw new IllegalArgumentException();
        return value;
    }
    private static Boolean lensChanges(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "allowed", "allow", "yes", "true" -> true;
            case "avoid", "no", "false", "none" -> false;
            default -> throw new IllegalArgumentException();
        };
    }
    private static Double weight(String text) {
        var match = WEIGHT.matcher(text.strip());
        if (!match.matches()) throw new IllegalArgumentException();
        double grams = Double.parseDouble(match.group(1)) * ("kg".equalsIgnoreCase(match.group(2)) ? 1000 : 1);
        if (!Double.isFinite(grams) || grams <= 0 || grams > ShootRequirement.MAX_WEIGHT_GRAMS) throw new IllegalArgumentException();
        return grams;
    }
    private static String grams(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value); }
    private static String pairs(Map<String, ?> values) {
        return String.join("; ", values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
    }
    private static <V> Map<String, V> pairs(String text, String error, List<LocalizedMessage> errors, java.util.function.Function<String, V> parser) {
        var result = new LinkedHashMap<String, V>();
        for (String part : list(text)) {
            int split = part.lastIndexOf('=');
            try {
                if (split <= 0 || split == part.length() - 1) throw new IllegalArgumentException();
                if (result.putIfAbsent(part.substring(0, split).strip(), parser.apply(part.substring(split + 1).strip())) != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { errors.add(LocalizedMessage.of(error)); return Map.of(); }
        }
        return result;
    }
    private static List<String> list(String text) {
        return Arrays.stream(text.split("[;\\n]")).map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
    }
    private static <T> Optional<T> parse(String text, String error, List<LocalizedMessage> errors,
            java.util.function.Function<String, T> parser, Object... arguments) {
        if (text.isBlank()) return Optional.empty();
        try { return Optional.of(parser.apply(text.strip())); }
        catch (IllegalArgumentException | DateTimeParseException | ArithmeticException invalid) {
            errors.add(LocalizedMessage.of(error, arguments)); return Optional.empty();
        }
    }
}
