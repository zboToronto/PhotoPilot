package com.photopilot.validation;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.ActivitySuggestion;
import com.photopilot.model.DiscoveryContext;
import com.photopilot.model.EnvironmentAssessment;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentAssessment.RejectedWindow;
import com.photopilot.model.EnvironmentContext;
import com.photopilot.model.EnvironmentRequest;
import com.photopilot.model.ShootingWindow;

/**
 * Stage 1 UML element 65 (zero-based): {F02/F09; time, distance, gear and consent gates}
 * F09 deterministic time gates: local-time resolution in the verified zone (DST gaps rejected, overlaps resolved
 * conservatively and labelled) and {@link #checkTiming} for AI-proposed windows. A window survives only if it lies
 * inside the user's availability, is consistent with the sun data for its declared light, cites known evidence and
 * quotes no measurement absent from the provider data. Invalid windows are rejected with reasons; valid ones are kept.
 * F02 {@link #validate} remains NOT IMPLEMENTED.
 */
public class FeasibilityValidator {
    public static final Duration MIN_WINDOW = Duration.ofMinutes(5), SUN_TOLERANCE = Duration.ofMinutes(2);
    private final Clock clock;

    public FeasibilityValidator() {
        this(Clock.systemUTC());
    }

    public FeasibilityValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result<ActivitySuggestion> validate(ActivitySuggestion idea, DiscoveryContext context) {
        return Result.failureKey("error.notImplemented", "FeasibilityValidator.validate");
    }

    /** The user's on-site period in the location zone, plus labels for any ambiguous local time. */
    public record ResolvedAvailability(Availability availability, List<LocalizedMessage> notices) {
        public ResolvedAvailability { notices = List.copyOf(notices); }
    }

    /**
     * Resolves local wall-clock availability in {@code zone}. A time that does not exist (DST gap) is rejected. A time
     * that occurs twice (DST overlap) is resolved so the period can only shrink (later occurrence for the start,
     * earlier for the end) and labelled, so the user's constraint is never widened silently.
     */
    public Result<ResolvedAvailability> resolveAvailability(EnvironmentRequest request, ZoneId zone) {
        if (request == null) return Result.failureKey("env.error.time");
        if (zone == null) return Result.failureKey("env.error.zone");
        var valid = request.validate();
        if (!valid.isSuccess()) return Result.failure(valid);
        var notes = new ArrayList<LocalizedMessage>();
        var start = resolveLocal(LocalDateTime.of(request.date(), request.availableFrom()), zone, true, notes);
        if (!start.isSuccess()) return Result.failure(start);
        var end = resolveLocal(LocalDateTime.of(request.endDate(), request.availableUntil()), zone, false, notes);
        if (!end.isSuccess()) return Result.failure(end);
        if (!end.value().orElseThrow().isAfter(start.value().orElseThrow())) return Result.failureKey("env.error.emptyAvailability");
        return Result.success(new ResolvedAvailability(new Availability(start.value().orElseThrow(), end.value().orElseThrow()), notes));
    }

    public static Result<ZonedDateTime> resolveLocal(LocalDateTime local, ZoneId zone, boolean laterOnOverlap, List<LocalizedMessage> notes) {
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty())
            return Result.failureKey("env.error.timeGap", local.toLocalTime().toString(), local.toLocalDate().toString(), zone.getId());
        ZonedDateTime resolved = ZonedDateTime.of(local, zone);
        if (offsets.size() > 1) {
            resolved = laterOnOverlap ? resolved.withLaterOffsetAtOverlap() : resolved.withEarlierOffsetAtOverlap();
            notes.add(LocalizedMessage.of("env.notice.timeAmbiguous", local.toLocalTime().toString(), local.toLocalDate().toString(),
                resolved.getOffset().getId(), zone.getId()));
        }
        return Result.success(resolved);
    }

    /**
     * A model-proposed local time. With an offset, it must be a valid offset for that local time in the zone;
     * without one, nonexistent and ambiguous local times are rejected rather than guessed.
     */
    public static Result<ZonedDateTime> parseProposed(String text, ZoneId zone) {
        String value = Objects.toString(text, "").strip();
        if (value.isEmpty() || value.length() > 40) return Result.failureKey("env.reject.format", value);
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            LocalDateTime local = parsed.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
            if (!zone.getRules().isValidOffset(local, parsed.getOffset())) return Result.failureKey("env.reject.offset", value, zone.getId());
            return Result.success(ZonedDateTime.ofStrict(local, parsed.getOffset(), zone));
        } catch (DateTimeParseException withoutOffset) {
            try {
                LocalDateTime local = LocalDateTime.parse(value).truncatedTo(ChronoUnit.MINUTES);
                int offsets = zone.getRules().getValidOffsets(local).size();
                if (offsets == 0) return Result.failureKey("env.reject.gap", value, zone.getId());
                if (offsets > 1) return Result.failureKey("env.reject.ambiguous", value, zone.getId());
                return Result.success(ZonedDateTime.of(local, zone));
            } catch (DateTimeParseException unreadable) {
                return Result.failureKey("env.reject.format", value);
            }
        } catch (DateTimeException invalid) {
            return Result.failureKey("env.reject.offset", value, zone.getId());
        }
    }

    /** Validates every proposed window; never repairs one. Rejections carry all their reasons. */
    public Result<EnvironmentAssessment> checkTiming(EnvironmentAssessment assessment) {
        if (assessment == null) return Result.failureKey("env.error.noAssessment");
        var accepted = new ArrayList<ShootingWindow>();
        var rejected = new ArrayList<>(assessment.rejected());
        Set<String> known = assessment.evidenceIds();
        Instant now = clock.instant();
        for (ShootingWindow window : assessment.windows()) {
            var reasons = reasons(window, assessment, known, accepted, now);
            if (reasons.isEmpty()) accepted.add(window);
            else rejected.add(new RejectedWindow(window.label(), window.start().toString(), window.end().toString(), reasons));
        }
        accepted.sort(Comparator.comparing(ShootingWindow::start));
        var notices = new ArrayList<LocalizedMessage>();
        if (accepted.isEmpty() && !rejected.isEmpty()) notices.add(LocalizedMessage.of("env.notice.noValidWindow"));
        else if (accepted.isEmpty() && assessment.aiInterpreted()) notices.add(LocalizedMessage.of("env.notice.noWindowProposed"));
        return Result.success(assessment.validated(accepted, rejected, notices));
    }

    private List<LocalizedMessage> reasons(ShootingWindow window, EnvironmentAssessment assessment, Set<String> known,
            List<ShootingWindow> accepted, Instant now) {
        var structural = window.validate();
        if (!structural.isSuccess()) return structural.messages();
        var found = new ArrayList<LocalizedMessage>();
        var context = assessment.context();
        if (assessment.availability().isEmpty()) return List.of(LocalizedMessage.of("env.reject.noAvailability"));
        Availability available = assessment.availability().get();
        if (context.zone().isPresent() && !window.start().getZone().equals(context.zone().get()))
            found.add(LocalizedMessage.of("env.reject.zone"));
        if (!available.contains(window.start(), window.end()))
            found.add(LocalizedMessage.of("env.reject.availability", available.start().toLocalDateTime().toString(), available.end().toLocalDateTime().toString()));
        if (window.arrival().isBefore(available.start())) found.add(LocalizedMessage.of("env.reject.arrival"));
        if (window.duration().compareTo(MIN_WINDOW) < 0) found.add(LocalizedMessage.of("env.reject.short", MIN_WINDOW.toMinutes()));
        if (!window.end().toInstant().isAfter(now)) found.add(LocalizedMessage.of("env.reject.past"));
        lightProblem(window, assessment).ifPresent(found::add);
        if (window.evidence().isEmpty()) found.add(LocalizedMessage.of("env.reject.noEvidence"));
        var unknown = window.evidence().stream().filter(id -> !known.contains(id)).toList();
        if (!unknown.isEmpty()) found.add(LocalizedMessage.of("env.reject.unknownEvidence", String.join(", ", unknown)));
        var unsupported = unsupportedMeasurements(window.label() + " " + window.conditions() + " " + window.rationale(), context);
        if (!unsupported.isEmpty()) found.add(LocalizedMessage.of("env.reject.measurement", String.join(", ", unsupported)));
        if (accepted.stream().anyMatch(a -> a.start().isEqual(window.start()) && a.end().isEqual(window.end())))
            found.add(LocalizedMessage.of("env.reject.duplicate"));
        return found;
    }

    /** Declared light must agree with provider sun events or the computed phases; unverifiable claims are rejected. */
    private static Optional<LocalizedMessage> lightProblem(ShootingWindow window, EnvironmentAssessment assessment) {
        var context = assessment.context();
        LocalDate day = window.start().toLocalDate();
        String light = window.light();
        var unverifiable = Optional.of(LocalizedMessage.of("env.reject.lightUnverified", light));
        var conflict = Optional.of(LocalizedMessage.of("env.reject.light", light));
        switch (light) {
            case "mixed" -> { return Optional.empty(); }
            case "daylight" -> {
                var rise = context.sunEvent(day, "sunrise"); var set = context.sunEvent(day, "sunset");
                if (rise.isEmpty() || set.isEmpty()) return unverifiable;
                return within(window, rise.get(), set.get()) ? Optional.empty() : conflict;
            }
            case "goldenHour", "blueHour" -> {
                var phases = assessment.lightPhases().stream().filter(p -> light.equals("goldenHour") ? p.golden() : p.blue()).toList();
                if (phases.isEmpty()) return unverifiable;
                return phases.stream().anyMatch(p -> overlaps(window, p.start().toInstant(), p.end().toInstant())) ? Optional.empty() : conflict;
            }
            case "twilight" -> {
                var intervals = new ArrayList<Instant[]>();
                for (LocalDate d : List.of(day.minusDays(1), day, day.plusDays(1))) {
                    pair(context, d, "civilDawn", d, "sunrise").ifPresent(intervals::add);
                    pair(context, d, "sunset", d, "civilDusk").ifPresent(intervals::add);
                }
                if (intervals.isEmpty()) return unverifiable;
                return intervals.stream().anyMatch(i -> overlaps(window, i[0], i[1])) ? Optional.empty() : conflict;
            }
            case "night" -> {
                var intervals = new ArrayList<Instant[]>();
                for (LocalDate d : List.of(day.minusDays(1), day)) pair(context, d, "civilDusk", d.plusDays(1), "civilDawn").ifPresent(intervals::add);
                // Without the next morning's dawn, darkness is still verified up to the end of the evidence range.
                context.sunEvent(day, "civilDusk").ifPresent(dusk -> {
                    if (context.sunEvent(day.plusDays(1), "civilDawn").isEmpty())
                        intervals.add(new Instant[]{dusk, day.plusDays(1).atStartOfDay(window.start().getZone()).toInstant()});
                });
                if (intervals.isEmpty()) return unverifiable;
                return intervals.stream().anyMatch(i -> within(window, i[0], i[1])) ? Optional.empty() : conflict;
            }
            default -> { return Optional.of(LocalizedMessage.of("env.reject.lightCategory", light)); }
        }
    }
    private static Optional<Instant[]> pair(EnvironmentContext context, LocalDate first, String from, LocalDate second, String to) {
        var a = context.sunEvent(first, from); var b = context.sunEvent(second, to);
        return a.isPresent() && b.isPresent() && b.get().isAfter(a.get()) ? Optional.of(new Instant[]{a.get(), b.get()}) : Optional.empty();
    }
    private static boolean within(ShootingWindow window, Instant from, Instant to) {
        return !window.start().toInstant().isBefore(from.minus(SUN_TOLERANCE)) && !window.end().toInstant().isAfter(to.plus(SUN_TOLERANCE));
    }
    private static boolean overlaps(ShootingWindow window, Instant from, Instant to) {
        Instant start = window.start().toInstant().isAfter(from) ? window.start().toInstant() : from;
        Instant end = window.end().toInstant().isBefore(to) ? window.end().toInstant() : to;
        return Duration.between(start, end).compareTo(Duration.ofMinutes(1)) >= 0;
    }

    /** Number + unit, longest units first; Chinese unit words are included for zh-CN replies. */
    private static final Pattern MEASUREMENT = Pattern.compile(
        "(?<![\\w.])(-?\\d+(?:\\.\\d+)?)\\s*(°C|℃|摄氏度|°F|℉|华氏度|%|％|km/h|kph|公里/小时|千米/小时|公里每小时|mph|m/s|米/秒|mm|毫米|km|公里|千米|m(?![a-zA-Z/])|米|°)");

    /**
     * Measurements in AI text that do not match any provider value of the same quantity (within display rounding).
     * Units the providers do not report (°F, mph, m/s) are always unsupported. Solar-elevation thresholds of the
     * documented light-phase model are accepted as degrees.
     */
    public static List<String> unsupportedMeasurements(String text, EnvironmentContext context) {
        if (text == null || text.isBlank()) return List.of();
        var allowed = new HashMap<String, List<Double>>();
        for (var hour : context.hourly()) hour.values().forEach((key, value) -> add(allowed, key, value));
        context.weather().forEach((key, value) -> add(allowed, key, value));
        allowed.computeIfAbsent("degrees", k -> new ArrayList<>()).addAll(List.of(-6.0, -4.0, 6.0));
        var unsupported = new ArrayList<String>();
        var matcher = MEASUREMENT.matcher(text);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String quantity = switch (matcher.group(2)) {
                case "°C", "℃", "摄氏度" -> "temperature";
                case "%", "％" -> "percent";
                case "km/h", "kph", "公里/小时", "千米/小时", "公里每小时" -> "speed";
                case "mm", "毫米" -> "precipitation";
                case "km", "公里", "千米" -> "kilometres";
                case "m", "米" -> "metres";
                case "°" -> "degrees";
                default -> "unsupportedUnit";
            };
            boolean supported = allowed.getOrDefault(quantity, List.of()).stream()
                .anyMatch(v -> Math.abs(v - value) <= Math.max(quantity.equals("kilometres") ? 0.05 : 0.5, Math.abs(v) * 0.01));
            if (!supported) unsupported.add(matcher.group().strip());
        }
        return unsupported;
    }
    private static void add(Map<String, List<Double>> allowed, String key, double value) {
        String quantity = key.startsWith("temperature") ? "temperature" : key.startsWith("cloudCover") || key.startsWith("precipitationProbability") ? "percent"
            : key.startsWith("wind") && key.endsWith("Kmh") ? "speed" : key.startsWith("precipitation") ? "precipitation"
            : key.equals("windDirectionDeg") ? "degrees" : key.equals("visibilityM") ? "metres" : "";
        if (quantity.isEmpty()) return;
        allowed.computeIfAbsent(quantity, k -> new ArrayList<>()).add(value);
        if (quantity.equals("metres")) allowed.computeIfAbsent("kilometres", k -> new ArrayList<>()).add(value / 1000);
    }
}
