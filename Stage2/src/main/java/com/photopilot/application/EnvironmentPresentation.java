package com.photopilot.application;

import com.photopilot.i18n.LocalizationService;
import com.photopilot.integration.EnvironmentApiAdapter;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.model.EnvironmentContext.Hour;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Shared localized F09 text for GUI cards and CLI output. Provider facts, computed estimates and AI interpretation are
 * rendered in separate, labelled sections; every time carries its local zone abbreviation.
 */
public final class EnvironmentPresentation {
    private EnvironmentPresentation() { }
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH);
    private static final String[] COMPASS = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};

    /** What the session already says (confirmed F03 context, read only) or that the F09-only fields are needed. */
    public static List<String> context(PhotographySession session, LocalizationService l) {
        var lines = new ArrayList<String>();
        var requirement = session.requirements().filter(ShootRequirement::confirmed);
        if (requirement.isEmpty()) { lines.add(l.text("env.context.noRequirement")); return lines; }
        var r = requirement.get();
        lines.add(l.text("env.context.subject", r.subject()));
        if (!r.goals().isEmpty()) lines.add(l.text("env.context.goals", String.join("; ", r.goals())));
        if (!r.timeWindow().isBlank()) lines.add(l.text("env.context.timing", r.timeWindow()));
        r.duration().ifPresent(d -> lines.add(l.text("env.context.duration", d.toMinutes())));
        if (!r.fixedConstraints().isEmpty()) lines.add(l.text("env.context.constraints", r.fixedConstraints().toString()));
        lines.add(l.text("env.context.readOnly"));
        return lines;
    }

    public static String candidate(PlaceCandidate c, int number, LocalizationService l) {
        return l.text("env.place.option", number, c.displayName(), coordinate(c.latitude(), c.estimated()), coordinate(c.longitude(), c.estimated()),
            c.zone().map(ZoneId::getId).orElse(l.text("env.unknown"))) + " — " + provenance(c, l);
    }
    private static String coordinate(double value, boolean estimated) {
        return estimated ? String.format(Locale.ROOT, "%.2f", value) : EnvironmentForm.coordinate(value);
    }
    public static String provenance(PlaceCandidate c, LocalizationService l) {
        String line = l.text("env.resolve.source." + c.source()) + " · " + l.text("env.resolve.scope." + c.scope());
        return c.broaderArea() ? line + " · " + l.text("env.resolve.broader") : line;
    }

    public static List<String> summary(EnvironmentAssessment a, LocalizationService l) {
        var lines = shootingContext(a, l);
        var context = a.context();
        ZoneId zone = a.availability().map(av -> av.start().getZone()).orElse(ZoneOffset.UTC);
        lines.add(l.text("env.summary.retrieved", time(context.retrievedAt().atZone(zone), null)));
        lines.addAll(sources(context, l));
        lines.add(l.text(a.aiInterpreted() ? "env.summary.ai" : "env.summary.noAi"));
        lines.add(l.text("env.summary.counts", a.windows().size(), a.rejected().size()));
        return lines;
    }

    /** Subject, location with its provenance (and the estimate limitation), local zone and availability. */
    public static List<String> shootingContext(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        var request = a.request().orElseThrow();
        lines.add(l.text("env.summary.subject", a.subject()));
        lines.add(l.text(request.aiLocation() ? "env.resolve.aiPoint" : request.place().fromDevice() ? "env.resolve.devicePoint"
            : request.place().approximate() ? "env.summary.placeApproximate" : "env.summary.place", request.place().label(),
            request.place().latitude().map(v -> coordinate(v, request.aiLocation())).orElse(""),
            request.place().longitude().map(v -> coordinate(v, request.aiLocation())).orElse("")));
        lines.add(request.accepted().map(c -> provenance(c, l)).orElseGet(() -> l.text("env.resolve.source." + request.locationSource())));
        if (request.aiLocation()) lines.add(l.text("env.resolve.limitation"));
        a.availability().ifPresent(av -> {
            lines.add(l.text("env.summary.zone", av.start().getZone().getId(), av.start().getOffset().getId()));
            lines.add(l.text("env.summary.availability", request.date().toString(), time(av.start(), request.date()), time(av.end(), request.date()), av.duration().toMinutes()));
        });
        return lines;
    }
    public static List<String> sources(EnvironmentContext context, LocalizationService l) {
        var lines = new ArrayList<String>();
        var weather = context.sources().get(EnvironmentContext.HOURLY);
        if (weather == null) weather = context.sources().get(EnvironmentContext.DAILY);
        lines.add(weather == null ? l.text("env.source.weatherNone") : l.text("env.source.weather", EnvironmentApiAdapter.WEATHER_SOURCE, weather.getHost()));
        var sunSources = context.sunEvents().stream().map(EnvironmentContext.SunEvent::source).distinct().toList();
        lines.add(sunSources.isEmpty() ? l.text("env.source.sunNone") : l.text("env.source.sun", String.join(", ", sunSources)));
        lines.add(l.text("env.source.model"));
        return lines;
    }

    /** Provider-reported sun events and weather: facts with their applicable local times. */
    public static List<String> evidence(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        var context = a.context();
        var request = a.request().orElseThrow();
        ZoneId zone = a.availability().map(av -> av.start().getZone()).orElse(ZoneOffset.UTC);
        lines.add(l.text("env.evidence.sunTitle"));
        if (context.sunEvents().isEmpty()) lines.add("  " + l.text("env.evidence.sunNone"));
        for (var event : context.sunEvents()) {
            if (!event.date().equals(request.date()) && !request.crossesMidnight()) continue;
            lines.add("  " + l.text("env.evidence.sunEvent", l.text("env.sun." + event.kind()), time(event.time().atZone(zone), request.date()), event.source()));
        }
        lines.add(l.text("env.evidence.dailyTitle", request.date().toString()));
        if (context.weather().isEmpty()) lines.add("  " + l.text("env.evidence.dailyNone"));
        else lines.add("  " + daily(context.weather(), l));
        lines.add(l.text("env.evidence.hourlyTitle"));
        var hours = a.availability().map(av -> context.hours(av.start().toInstant().minus(Duration.ofHours(1)), av.end().toInstant())).orElse(context.hourly());
        if (hours.isEmpty()) lines.add("  " + l.text("env.evidence.hourlyNone"));
        hours.forEach(hour -> lines.add("  " + hour(hour, zone, request.date(), l)));
        return lines;
    }
    private static String daily(Map<String, Double> values, LocalizationService l) {
        var parts = new ArrayList<String>();
        if (values.containsKey("temperatureMinC") && values.containsKey("temperatureMaxC"))
            parts.add(l.text("env.weather.temperatureRange", one(values.get("temperatureMinC")), one(values.get("temperatureMaxC"))));
        Optional.ofNullable(values.get("precipitationProbabilityMaxPct")).ifPresent(v -> parts.add(l.text("env.weather.precipitationChanceMax", whole(v))));
        Optional.ofNullable(values.get("precipitationSumMm")).ifPresent(v -> parts.add(l.text("env.weather.precipitationSum", one(v))));
        Optional.ofNullable(values.get("windSpeedMaxKmh")).ifPresent(v -> parts.add(l.text("env.weather.windMax", one(v))));
        Optional.ofNullable(values.get("windGustsMaxKmh")).ifPresent(v -> parts.add(l.text("env.weather.gustsMax", one(v))));
        return String.join(", ", parts);
    }
    public static String hour(Hour hour, ZoneId zone, LocalDate base, LocalizationService l) {
        var parts = new ArrayList<String>();
        hour.value("weatherCode").ifPresent(code -> parts.add(l.text("env.wmo." + wmo(code.intValue()))));
        hour.value("temperatureC").ifPresent(v -> parts.add(l.text("env.weather.temperature", one(v))));
        hour.value("cloudCoverPct").ifPresent(v -> parts.add(l.text("env.weather.cloud", whole(v))));
        hour.value("precipitationProbabilityPct").ifPresent(v -> parts.add(l.text("env.weather.precipitationChance", whole(v))));
        hour.value("precipitationMm").ifPresent(v -> parts.add(l.text("env.weather.precipitation", one(v))));
        hour.value("windSpeedKmh").ifPresent(v -> parts.add(l.text("env.weather.wind", one(v),
            hour.value("windDirectionDeg").map(d -> COMPASS[(int) Math.round(d / 22.5) % 16]).orElse("?"))));
        hour.value("windGustsKmh").ifPresent(v -> parts.add(l.text("env.weather.gusts", one(v))));
        hour.value("visibilityM").ifPresent(v -> parts.add(l.text("env.weather.visibility", one(v / 1000))));
        return l.text("env.evidence.hour", time(hour.time().atZone(zone), base), String.join(", ", parts));
    }
    static String wmo(int code) {
        if (code == 0) return "clear";
        if (code == 1) return "mainlyClear";
        if (code == 2) return "partlyCloudy";
        if (code == 3) return "overcast";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if (code >= 71 && code <= 77) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code == 85 || code == 86) return "snowShowers";
        if (code >= 95) return "thunder";
        return "other";
    }

    /** Computed golden/blue hour phases overlapping the availability day(s); always labelled as estimates. */
    public static List<String> phases(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        var request = a.request().orElseThrow();
        lines.add(l.text("env.phase.notice"));
        var shown = a.lightPhases().stream().filter(p -> a.availability().map(av -> !p.end().isBefore(av.start().minusHours(3))
            && !p.start().isAfter(av.end().plusHours(3))).orElse(true)).toList();
        if (shown.isEmpty()) lines.add("  " + l.text("env.phase.none"));
        shown.forEach(p -> lines.add("  " + l.text("env.phase.line", l.text("env.phase." + p.kind()), time(p.start(), request.date()), time(p.end(), request.date()))));
        return lines;
    }

    /** One card per validated AI-proposed window, with the application-computed arrival suggestion. */
    public static List<List<String>> windows(EnvironmentAssessment a, LocalizationService l) {
        var cards = new ArrayList<List<String>>();
        var request = a.request().orElseThrow();
        for (int i = 0; i < a.windows().size(); i++) {
            var w = a.windows().get(i);
            var lines = new ArrayList<String>();
            lines.add(l.text("env.window.heading", i + 1, w.label()));
            lines.add(l.text("env.window.time", time(w.start(), request.date()), time(w.end(), request.date()), w.duration().toMinutes()));
            lines.add(request.leadTime().filter(d -> !d.isZero()).map(d -> l.text("env.window.arrival", time(w.arrival(), request.date()), d.toMinutes()))
                .orElse(l.text("env.window.arrivalNoLead", time(w.arrival(), request.date()))));
            lines.add(l.text("env.window.light", l.text("env.light." + w.light())));
            if (!w.conditions().isBlank()) lines.add(l.text("env.window.conditions", w.conditions()));
            if (!w.rationale().isBlank()) lines.add(l.text("env.window.rationale", w.rationale()));
            lines.add(l.text("env.window.evidence", String.join(", ", w.evidence().stream().map(id -> evidenceLabel(id, a, l)).toList())));
            w.notes().forEach(note -> lines.add(note.render(l)));
            lines.add(l.text("env.window.validated"));
            cards.add(lines);
        }
        return cards;
    }

    /** Readable name of a cited evidence id (sun event, light phase, forecast hour or daily summary). */
    public static String evidenceLabel(String id, EnvironmentAssessment a, LocalizationService l) {
        boolean nextDay = id.endsWith("+1");
        String base = nextDay ? id.substring(0, id.length() - 2) : id;
        String text;
        if (base.startsWith("h-")) {
            ZoneId zone = a.availability().map(av -> av.start().getZone()).orElse(ZoneOffset.UTC);
            try { text = l.text("env.evidence.hourRef", LocalDateTime.parse(base.substring(2)).atZone(zone).format(CLOCK)); }
            catch (java.time.format.DateTimeParseException unreadable) { return id; }
            return LocalDateTime.parse(base.substring(2)).toLocalDate().equals(a.request().map(EnvironmentRequest::date).orElse(null))
                ? text : l.text("env.evidence.nextDay", text);
        }
        if (base.equals("daily")) text = l.text("env.evidence.dailyRef");
        else if (EnvironmentContext.SUN_KINDS.contains(base)) text = l.text("env.sun." + base);
        else if (base.matches("(golden|blue)(Morning|Evening)")) text = l.text("env.phase." + base);
        else return id;
        return nextDay ? l.text("env.evidence.nextDay", text) : text;
    }

    public static List<String> interpretation(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (a.interpretation().isEmpty()) { lines.add(l.text("env.ai.none")); return lines; }
        var ai = a.interpretation().get();
        lines.add(l.text("env.ai.notice"));
        if (!ai.summary().isBlank()) lines.add(l.text("env.ai.summary", ai.summary()));
        if (!ai.considerations().isEmpty()) { lines.add(l.text("env.ai.considerations")); ai.considerations().forEach(c -> lines.add("  - " + c)); }
        if (!ai.alternatives().isEmpty()) { lines.add(l.text("env.ai.alternatives")); ai.alternatives().forEach(c -> lines.add("  - " + c)); }
        return lines;
    }

    public static List<String> rejected(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        if (a.rejected().isEmpty()) { lines.add(l.text("env.rejected.none")); return lines; }
        for (var r : a.rejected())
            lines.add("- " + l.text("env.rejected.line", r.label().isBlank() ? l.text("env.unknown") : r.label(), r.start(), r.end(),
                String.join(" ", r.reasons().stream().map(m -> m.render(l)).toList())));
        return lines;
    }

    /** Every notice, unavailable field and stale group; empty data is never shown as a value. */
    public static List<String> limitations(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        a.notices().forEach(n -> lines.add("- " + n.render(l)));
        if (!a.context().unavailableFields().isEmpty()) lines.add("- " + l.text("env.limit.unavailable", String.join(", ", a.context().unavailableFields())));
        if (lines.isEmpty()) lines.add(l.text("env.limit.none"));
        lines.add(l.text("env.limit.travel"));
        return lines;
    }

    /** Local clock time with zone abbreviation; prefixed with the date when it is not the shooting date. */
    public static String time(ZonedDateTime time, LocalDate base) {
        String clock = time.format(CLOCK);
        return base == null || time.toLocalDate().equals(base) ? clock : time.toLocalDate() + " " + clock;
    }

    // ---- Dashboard text shared by the GUI dashboard and the CLI report -------------------------------------------------
    // Values are rounded here, at the presentation boundary only; the analysis keeps full precision.

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter ZONE_ABBREVIATION = DateTimeFormatter.ofPattern("z", Locale.ENGLISH);

    /** "HH:mm" in the location zone, with the date in front when it is not {@code base}. */
    public static String clock(ZonedDateTime time, LocalDate base) {
        String clock = time.format(HOUR_MINUTE);
        return base == null || time.toLocalDate().equals(base) ? clock : time.toLocalDate() + " " + clock;
    }
    public static String clock(Instant time, ZoneId zone, LocalDate base) { return clock(time.atZone(zone), base); }
    /** "15:57–17:02" (dates added for times not on {@code base}). */
    public static String span(ZonedDateTime from, ZonedDateTime to, LocalDate base) { return clock(from, base) + "–" + clock(to, base); }
    public static String span(TimeInterval interval, ZoneId zone, LocalDate base) { return span(interval.start().atZone(zone), interval.end().atZone(zone), base); }
    /** Zone abbreviation at that instant ("EST"), for headings next to bare clock times. */
    public static String abbreviation(ZonedDateTime time) { return time.format(ZONE_ABBREVIATION); }
    public static String minutes(Duration duration, LocalizationService l) { return l.text("env.minutes", duration.toMinutes()); }

    /** Number of a metric value for display: temperature, precipitation, wind and visibility (in km) to one decimal, percentages whole. */
    public static String number(String metric, double value) {
        return switch (metric) {
            case "cloudCoverPct", "precipitationProbabilityPct", "precipitationProbabilityMaxPct", "windDirectionDeg" -> whole(value);
            case "visibilityM" -> one(value / 1000);
            default -> one(value);
        };
    }
    public static String unit(String metric, LocalizationService l) { return l.text("env.unit." + metric); }
    /** Value with its unit ("6.8 °C", "21%", "14.8 km/h", "24.1 km"). */
    public static String value(String metric, double value, LocalizationService l) {
        String unit = unit(metric, l);
        return unit.equals("%") ? number(metric, value) + unit : number(metric, value) + " " + unit;
    }
    /** "6.5–6.8 °C", or a single value when minimum and maximum agree after rounding. */
    public static String range(EnvironmentAnalysis.Metric metric, LocalizationService l) {
        String low = number(metric.key(), metric.min()), high = number(metric.key(), metric.max());
        if (low.equals(high)) return value(metric.key(), metric.min(), l);
        String unit = unit(metric.key(), l);
        return low + "–" + high + (unit.equals("%") ? unit : " " + unit);
    }
    /** Metric over the shooting window, or the localized "unavailable" (never zero). */
    public static String windowValue(EnvironmentAnalysis analysis, String metric, LocalizationService l) {
        return analysis.window().metric(metric).map(m -> range(m, l)).orElse(l.text("env.value.unavailable"));
    }
    /**
     * Value axis of a weather chart (GUI and CLI alike): percentages always 0-100, amounts, speeds and visibility from
     * zero to the largest value, temperature between its rounded extremes with one degree of margin (so values below
     * zero keep their place). Raw units (visibility in metres).
     */
    public static double[] axis(String metric, List<Double> values) {
        if (values.isEmpty()) return new double[] {0, 1};
        double low = Collections.min(values), high = Collections.max(values);
        return switch (metric) {
            case "cloudCoverPct", "precipitationProbabilityPct" -> new double[] {0, 100};
            case "temperatureC" -> new double[] {Math.floor(low) - 1, Math.ceil(high) + 1};
            default -> new double[] {0, high > 0 ? high : 1};
        };
    }
    public static String metric(String key, LocalizationService l) { return l.text("env.metric." + key); }
    public static String shortMetric(String key, LocalizationService l) { return l.text("env.short." + key); }
    /** 16-point compass sector of a direction in degrees ("WNW"); the wind comes from this direction. */
    public static String compass(double degrees) { return COMPASS[(int) Math.round(((degrees % 360) + 360) % 360 / 22.5) % 16]; }
    /** Wind directions of the window samples as sectors in time order, repeated sectors once ("W → NW"). */
    public static String directions(EnvironmentAnalysis analysis, LocalizationService l) {
        var sectors = new ArrayList<String>();
        for (var sample : analysis.window().samples())
            sample.value("windDirectionDeg").map(EnvironmentPresentation::compass).filter(s -> sectors.isEmpty() || !sectors.getLast().equals(s)).ifPresent(sectors::add);
        return sectors.isEmpty() ? l.text("env.value.unavailable") : String.join(" → ", sectors);
    }
    /** WMO weather code category ("partlyCloudy") for icons and the localized condition name. */
    public static String category(int code) { return wmo(code); }
    public static String condition(int code, LocalizationService l) { return l.text("env.wmo." + wmo(code)); }
    public static String condition(EnvironmentAnalysis analysis, LocalizationService l) {
        return analysis.window().primaryCondition().map(code -> condition(code, l)).orElse(l.text("env.value.unavailable"));
    }
    /** How the window statistics were selected (count, or the nearest hour standing in for the period). */
    public static String scope(EnvironmentAnalysis analysis, LocalizationService l) {
        var window = analysis.window();
        return switch (window.scope()) {
            case WINDOW -> l.text("env.scope.WINDOW", window.samples().size());
            case NEAREST -> l.text("env.scope.NEAREST", clock(window.samples().getFirst().local(), analysis.date()));
            case NONE -> l.text("env.scope.NONE");
        };
    }
    /** Full-day provider value of a daily key, labelled as such, or empty when the provider gave none. */
    public static Optional<String> daily(EnvironmentAnalysis analysis, String key, LocalizationService l) {
        return Optional.ofNullable(analysis.daily().get(key)).map(v -> value(key, v, l));
    }
    /** The provider's daily summary line for the date, or "unavailable". */
    public static String dailySummary(EnvironmentAnalysis analysis, LocalizationService l) {
        return analysis.daily().isEmpty() ? l.text("env.value.unavailable") : daily(analysis.daily(), l);
    }

    public static String stage(EnvironmentAnalysis.StageKey key, LocalizationService l) { return l.text("env.stage." + key.name()); }
    public static String status(EnvironmentAnalysis.StageStatus status, LocalizationService l) { return l.text("env.status." + status.name()); }
    /** Plain ASCII tag for terminals: status is in words as well, never colour alone. */
    public static String tag(EnvironmentAnalysis.StageStatus status) {
        return switch (status) {
            case SUCCESS -> "[OK]";
            case PARTIAL -> "[WARN]";
            case PENDING -> "[..]";
            case FAILED, TIMED_OUT, UNAVAILABLE -> "[FAIL]";
            case SKIPPED, NOT_PERFORMED -> "[--]";
        };
    }
    /** "Location resolution: Completed (Open-Meteo geographic database)". */
    public static String stageLine(EnvironmentAnalysis.Stage stage, LocalizationService l) {
        String text = status(stage.status(), l);
        return stage.detail().map(d -> l.text("env.stage.line", stage(stage.key(), l), text, d.render(l))).orElse(l.text("env.stage.lineShort", stage(stage.key(), l), text));
    }
    public static String overall(EnvironmentAnalysis analysis, LocalizationService l) { return l.text("env.overall." + analysis.overall().name()); }
    public static String aiStatus(EnvironmentAnalysis.AiOutcome ai, LocalizationService l) { return l.text("env.ai.status." + ai.status().name()); }
    /** Status line plus what remains usable when the interpretation is missing. */
    public static List<String> aiOutcome(EnvironmentAnalysis analysis, LocalizationService l) {
        var lines = new ArrayList<String>();
        var ai = analysis.ai();
        lines.add(aiStatus(ai, l));
        ai.reason().filter(r -> ai.status() != EnvironmentAnalysis.AiStatus.SUCCESS).ifPresent(r -> lines.add(l.text("env.ai.reason", r.render(l))));
        if (ai.status() != EnvironmentAnalysis.AiStatus.SUCCESS) {
            lines.add(l.text("env.ai.factsKept"));
            if (analysis.windows().isEmpty()) lines.add(l.text("env.ai.noValidated"));
        }
        return lines;
    }
    public static String light(EnvironmentAnalysis.LightKind kind, LocalizationService l) { return l.text("env.light." + kind.name()); }
    public static String phase(String kind, LocalizationService l) { return l.text("env.phase." + kind); }
    public static String sunEvent(String kind, LocalizationService l) { return l.text("env.sun." + kind); }
    /** Light at a solar sample: band name plus golden/blue hour when the elevation lies in their range. */
    public static String lightAt(double elevation, LocalizationService l) {
        String band = light(com.photopilot.service.SolarCalculator.light(elevation), l);
        if (elevation >= com.photopilot.service.SolarCalculator.GOLDEN_LOW && elevation <= com.photopilot.service.SolarCalculator.GOLDEN_HIGH)
            return band + " · " + l.text("env.light.goldenHour");
        if (elevation >= com.photopilot.service.SolarCalculator.BLUE_LOW && elevation < com.photopilot.service.SolarCalculator.GOLDEN_LOW)
            return band + " · " + l.text("env.light.blueHour");
        return band;
    }
    public static String degrees(double value) { return one(value); }
    public static String finding(EnvironmentAnalysis.Finding finding, LocalizationService l) { return finding.message().render(l); }
    /** Overlap of one light phase with the availability, in words ("15:57–16:00 · 3 min", "touches at 16:00", "none"). */
    public static String overlap(EnvironmentAnalysis.PhaseOverlap overlap, EnvironmentAnalysis analysis, LocalizationService l) {
        if (overlap.overlap().isEmpty()) return l.text("env.value.none");
        var interval = overlap.overlap().get();
        if (overlap.contact()) return l.text("env.windows.contact", clock(interval.start(), analysis.zone(), analysis.date()));
        return span(interval, analysis.zone(), analysis.date()) + " · " + minutes(interval.duration(), l) + (overlap.complete() ? " · " + l.text("env.windows.full") : "");
    }
    /** "Shooting window: 15:00–16:00 EST (60 min)". */
    public static String availabilityLine(EnvironmentAnalysis analysis, LocalizationService l) {
        var av = analysis.availability();
        return span(av.start(), av.end(), analysis.date()) + " " + abbreviation(av.start()) + " (" + minutes(av.duration(), l) + ")";
    }
    /** Location label as reviewed, with its provenance ("Open-Meteo geographic database · City-level location"). */
    public static String placeLine(EnvironmentAnalysis analysis) { return analysis.request().place().label(); }
    public static String provenance(EnvironmentAnalysis analysis, LocalizationService l) {
        var request = analysis.request();
        return request.accepted().map(c -> provenance(c, l)).orElseGet(() -> l.text("env.resolve.source." + request.locationSource()));
    }
    /** Coordinates as used for display: estimated points rounded to two decimals, others to five. */
    public static String coordinates(EnvironmentAnalysis analysis) {
        var request = analysis.request(); var place = request.place();
        return coordinate(place.latitude().orElseThrow(), request.aiLocation()) + ", " + coordinate(place.longitude().orElseThrow(), request.aiLocation());
    }
    /** Source of the location time zone: weather provider, entered by the user, place lookup or the accepted AI estimate. */
    public static String zoneSource(EnvironmentAssessment a, LocalizationService l) {
        if (a.context().zone().isPresent()) return l.text("env.zoneSource.provider");
        var request = a.request().orElseThrow();
        return l.text(request.zoneEntered() ? "env.zoneSource.entered" : request.aiLocation() ? "env.zoneSource.estimate" : "env.zoneSource.lookup");
    }
    /** Where the forecast came from and when ("Open-Meteo forecast · retrieved 14:58 EST"). */
    public static String freshness(EnvironmentAnalysis analysis, LocalizationService l) {
        var context = analysis.assessment().context();
        var stamp = Optional.ofNullable(context.sourceTimestamps().get(EnvironmentContext.HOURLY)).orElse(context.retrievedAt()).atZone(analysis.zone());
        var coverage = analysis.coverage();
        String hours = coverage.first().isPresent() ? l.text("env.weather.coverage", clock(coverage.first().get(), analysis.date()), clock(coverage.last().orElseThrow(), analysis.date()))
            : l.text("env.weather.none");
        return l.text("env.weather.source", EnvironmentApiAdapter.WEATHER_SOURCE, time(stamp, analysis.date()), hours);
    }
    /** The complete earlier text report, section by section (technical details, copy and the CLI "technical" view). */
    public static List<String> report(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        section(lines, l.text("env.card.summary"), summary(a, l));
        var windows = windows(a, l);
        if (windows.isEmpty()) section(lines, l.text("env.card.windows"), List.of(l.text("env.card.noWindows")));
        for (var card : windows) { lines.add(""); lines.addAll(card); }
        section(lines, l.text("env.card.ai"), interpretation(a, l));
        section(lines, l.text("env.card.evidence"), evidence(a, l));
        section(lines, l.text("env.card.phases"), phases(a, l));
        section(lines, l.text("env.card.rejected"), rejected(a, l));
        section(lines, l.text("env.card.limitations"), limitations(a, l));
        return lines;
    }
    private static void section(List<String> lines, String title, List<String> body) {
        if (!lines.isEmpty()) lines.add("");
        lines.add("== " + title + " ==");
        lines.addAll(body);
    }
    public static String availability(Availability availability) { return time(availability.start(), null) + " – " + time(availability.end(), null); }
    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String whole(double value) { return String.valueOf(Math.round(value)); }
}
