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
        return l.text("env.place.option", number, c.displayName(), EnvironmentForm.coordinate(c.latitude()), EnvironmentForm.coordinate(c.longitude()),
            c.zone().map(ZoneId::getId).orElse(l.text("env.unknown")));
    }

    public static List<String> summary(EnvironmentAssessment a, LocalizationService l) {
        var lines = new ArrayList<String>();
        var context = a.context();
        var request = a.request().orElseThrow();
        lines.add(l.text("env.summary.subject", a.subject()));
        lines.add(l.text(request.place().approximate() ? "env.summary.placeApproximate" : "env.summary.place", request.place().label(),
            request.place().latitude().map(EnvironmentForm::coordinate).orElse(""), request.place().longitude().map(EnvironmentForm::coordinate).orElse("")));
        a.availability().ifPresent(av -> {
            lines.add(l.text("env.summary.zone", av.start().getZone().getId(), av.start().getOffset().getId()));
            lines.add(l.text("env.summary.availability", request.date().toString(), time(av.start(), request.date()), time(av.end(), request.date()), av.duration().toMinutes()));
        });
        ZoneId zone = a.availability().map(av -> av.start().getZone()).orElse(ZoneOffset.UTC);
        lines.add(l.text("env.summary.retrieved", time(context.retrievedAt().atZone(zone), null)));
        lines.addAll(sources(context, l));
        lines.add(l.text(a.aiInterpreted() ? "env.summary.ai" : "env.summary.noAi"));
        lines.add(l.text("env.summary.counts", a.windows().size(), a.rejected().size()));
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
    public static String availability(Availability availability) { return time(availability.start(), null) + " – " + time(availability.end(), null); }
    private static String one(double value) { return String.format(Locale.ROOT, "%.1f", value); }
    private static String whole(double value) { return String.valueOf(Math.round(value)); }
}
