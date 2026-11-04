package com.photopilot.environment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.agent.*;
import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.EnvironmentApiAdapter;
import com.photopilot.integration.LLMClient;
import com.photopilot.model.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.EnvironmentService;
import com.photopilot.service.LocationService;
import com.photopilot.validation.FeasibilityValidator;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.environment.FakeEnvironmentProviders.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EnvironmentService + FeasibilityValidator with the real adapter over the loopback provider fake, a stubbed
 * Gemini client and an adjustable fixed clock: deterministic time-zone, DST, midnight, freshness and AI-validation cases.
 */
class EnvironmentAssessmentTest {
    static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    static final GeoLocation AIRPORT = new GeoLocation("Billy Bishop Toronto City Airport", 43.6275, -79.3961, false, false, Optional.empty());
    @TempDir Path root;
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    final Deque<Result<ModelReply>> replies = new ArrayDeque<>();
    final List<AgentContext> prompts = new CopyOnWriteArrayList<>();
    FakeEnvironmentProviders providers;
    HttpClient http;
    EnvironmentService service;
    FeasibilityValidator validator;

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration by) { now = now.plus(by); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @BeforeEach void start() throws Exception {
        providers = new FakeEnvironmentProviders(); http = HttpClient.newHttpClient();
        var adapter = new EnvironmentApiAdapter(providers.weatherEndpoint(), providers.sunEndpoint(), http, Duration.ofSeconds(5), clock);
        LLMClient model = context -> { prompts.add(context); return replies.isEmpty() ? Result.failureKey("ai.error.serviceUnavailable", "503") : replies.poll(); };
        var agent = new PhotoPilotAgent(model, new ToolRegistry(Map.of()), new HistoryRetriever(new JsonSessionRepository(root)), 8);
        validator = new FeasibilityValidator(clock);
        service = new EnvironmentService(adapter, new LocationService(Optional.empty(), Optional.empty(), clock), agent, validator, clock);
    }
    @AfterEach void stop() { providers.close(); http.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static boolean has(List<LocalizedMessage> messages, String key) { return messages.stream().anyMatch(m -> m.key().equals(key)); }

    EnvironmentRequest request(LocalDate date, String from, String until, Integer lead, String activity) {
        return new EnvironmentRequest(AIRPORT, date, LocalTime.parse(from), LocalTime.parse(until),
            lead == null ? Optional.empty() : Optional.of(Duration.ofMinutes(lead)), Optional.empty(), activity, List.of("aircraft in flight"));
    }
    static ZonedDateTime local(LocalDate day, String time) { return ZonedDateTime.of(day, LocalTime.parse(time), TORONTO); }
    static ZonedDateTime sunset(LocalDate day) { return event(AIRPORT.latitude().get(), AIRPORT.longitude().get(), day, -0.833, false).atZone(TORONTO); }
    static String at(ZonedDateTime time) { return time.toOffsetDateTime().withSecond(0).withNano(0).toString(); }
    static JsonObject window(String label, ZonedDateTime start, ZonedDateTime end, String light, String conditions, String... evidence) {
        var w = new JsonObject(); w.addProperty("label", label); w.addProperty("start", at(start)); w.addProperty("end", at(end));
        w.addProperty("light", light); w.addProperty("conditions", conditions); w.addProperty("rationale", "Suits the stated goals.");
        var ids = new JsonArray(); for (String id : evidence) ids.add(id); w.add("evidence", ids); return w;
    }
    void reply(List<JsonObject> windows, List<String> considerations, String summary) {
        var root = new JsonObject(); var list = new JsonArray(); windows.forEach(list::add); root.add("windows", list);
        var notes = new JsonArray(); considerations.forEach(notes::add); root.add("considerations", notes);
        root.add("alternatives", new JsonArray()); root.addProperty("summary", summary);
        replies.add(Result.success(new ModelReply(Optional.of(root.toString()), List.of(), List.of())));
    }

    @Test void verifiedEvidenceIsInterpretedAndOnlyConsistentWindowsSurvive() {
        var set = sunset(DAY);
        reply(List.of(
            window("Afternoon flying display", local(DAY, "13:15"), local(DAY, "15:00"), "daylight",
                "Partly cloudy: cloud cover 40% and wind 12.5 km/h from the west.", "h-2026-11-07T14:00", "sunset"),
            window("Golden-hour flybys", set.minusMinutes(50), set.minusMinutes(5), "goldenHour", "Low warm light before sunset.", "goldenEvening", "sunset"),
            window("Evening static display", local(DAY, "19:30"), local(DAY, "20:30"), "night", "Dark sky.", "civilDusk"),
            window("Late flypast", set.plusMinutes(40), set.plusMinutes(70), "daylight", "Gusts of 80 km/h expected.", "sunset")),
            List.of("Wind 12.5 km/h from the west favours landings over the lake.", "Expect about 35% cloud cover."), "Two windows fit the airshow.");
        var a = ok(service.assess(null, request(DAY, "13:00", "19:00", 30, "Airshow aviation photography")));

        assertEquals(TORONTO, a.availability().orElseThrow().start().getZone());
        assertEquals(ZoneOffset.ofHours(-5), a.availability().orElseThrow().start().getOffset(), "EST after the 2026-11-01 DST change");
        assertEquals(2, a.windows().size(), () -> a.rejected().toString());
        var first = a.windows().getFirst();
        assertEquals("Afternoon flying display", first.label());
        assertEquals(local(DAY, "13:00"), first.arrival(), "30-minute lead clipped to the start of availability");
        assertTrue(has(first.notes(), "env.window.leadShortened"));
        var golden = a.windows().get(1);
        assertEquals(golden.start().minusMinutes(30), golden.arrival(), "arrival = start - the user's own lead time");
        assertTrue(golden.estimated());
        var rejected = a.rejected();
        assertEquals(2, rejected.size());
        assertTrue(has(rejected.get(0).reasons(), "env.reject.availability"), rejected.get(0).toString());
        assertTrue(has(rejected.get(1).reasons(), "env.reject.light") && has(rejected.get(1).reasons(), "env.reject.measurement"), rejected.get(1).toString());
        // AI interpretation is kept apart; statements quoting values absent from the data are removed.
        var ai = a.interpretation().orElseThrow();
        assertEquals(List.of("Wind 12.5 km/h from the west favours landings over the lake."), ai.considerations());
        assertTrue(has(a.notices(), "env.notice.statementsDropped"));
        // Gemini received verified evidence, the fixed availability and the schema - never coordinates.
        var prompt = prompts.getFirst();
        assertTrue(prompt.evidenceJson().contains("\"fixedUserConstraint\":true") && prompt.evidenceJson().contains("goldenEvening"));
        assertTrue(prompt.evidenceJson().contains("\"timeZone\":\"America/Toronto\"") && prompt.evidenceJson().contains("2026-11-07T13:00-05:00"));
        assertFalse(prompt.evidenceJson().contains("43.62") || prompt.evidenceJson().contains("-79.39"), "coordinates stay with the factual providers");
        assertTrue(prompt.responseSchema().contains("\"windows\""));
        assertTrue(a.lightPhases().stream().anyMatch(p -> p.kind().equals("goldenEvening")));
        assertTrue(a.evidenceIds().containsAll(Set.of("sunrise", "sunset", "goldenEvening", "daily", "h-2026-11-07T14:00")));
    }

    @Test void invalidGeminiTimingIsRejectedWithReasonsAndValidAlternativesAreKept() {
        var good = window("Midday", local(DAY, "13:00"), local(DAY, "14:00"), "daylight", "Cloud cover 40%.", "h-2026-11-07T13:00");
        var reversed = window("Reversed", local(DAY, "15:00"), local(DAY, "14:00"), "daylight", "", "sunset");
        var wrongOffset = window("Summer offset", local(DAY, "13:00"), local(DAY, "14:00"), "daylight", "", "sunset");
        wrongOffset.addProperty("start", "2026-11-07T13:00-04:00");
        var unreadable = window("Unreadable", local(DAY, "13:00"), local(DAY, "14:00"), "daylight", "", "sunset");
        unreadable.addProperty("end", "around 3pm");
        var invented = window("Invented evidence", local(DAY, "14:00"), local(DAY, "14:30"), "mixed", "", "radar-feed");
        var tiny = window("Tiny", local(DAY, "14:00"), local(DAY, "14:02"), "mixed", "", "sunset");
        reply(List.of(good, reversed, wrongOffset, unreadable), List.of(), "");
        var a = ok(service.assess(null, request(DAY, "12:00", "16:00", null, "Aviation")));
        assertEquals(List.of("Midday"), a.windows().stream().map(ShootingWindow::label).toList());
        assertEquals(local(DAY, "13:00"), a.windows().getFirst().arrival(), "no lead time entered: arrive by the start");
        var reasons = new HashMap<String, List<LocalizedMessage>>();
        a.rejected().forEach(r -> reasons.put(r.label(), r.reasons()));
        assertTrue(has(reasons.get("Reversed"), "env.reject.order"));
        assertTrue(has(reasons.get("Summer offset"), "env.reject.offset"));
        assertTrue(has(reasons.get("Unreadable"), "env.reject.format"));
        assertEquals(3, a.rejected().size());
        reply(List.of(good, good, tiny, invented, good), List.of(), "");
        var b = ok(service.assess(null, request(DAY, "12:00", "16:00", null, "Aviation")));
        assertEquals(1, b.windows().size());
        assertTrue(b.rejected().stream().anyMatch(r -> has(r.reasons(), "env.reject.duplicate")));
        assertTrue(b.rejected().stream().anyMatch(r -> has(r.reasons(), "env.reject.short")));
        assertTrue(b.rejected().stream().anyMatch(r -> has(r.reasons(), "env.reject.tooMany")));
        assertTrue(b.rejected().stream().anyMatch(r -> r.label().equals("Invented evidence") && has(r.reasons(), "env.reject.unknownEvidence")));
        reply(List.of(reversed), List.of(), "");
        assertTrue(has(ok(service.assess(null, request(DAY, "12:00", "16:00", null, "Aviation"))).notices(), "env.notice.noValidWindow"));
    }

    @Test void localTimesUseTheLocationZoneAndDaylightSavingAmbiguityIsLabelledOrRejected() {
        var summer = ok(validator.resolveAvailability(request(LocalDate.of(2027, 7, 7), "13:00", "19:00", null, "x"), TORONTO));
        assertEquals(ZoneOffset.ofHours(-4), summer.availability().start().getOffset());
        var tokyo = ok(validator.resolveAvailability(request(DAY, "13:00", "19:00", null, "x"), ZoneId.of("Asia/Tokyo")));
        assertEquals(Instant.parse("2026-11-07T04:00:00Z"), tokyo.availability().start().toInstant(), "never the computer's own zone");
        // 2026-11-01 01:30 occurs twice in Toronto: the start takes the later and the end the earlier occurrence.
        var fallBack = LocalDate.of(2026, 11, 1);
        var ambiguousStart = ok(validator.resolveAvailability(request(fallBack, "01:30", "03:00", null, "x"), TORONTO));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), ambiguousStart.availability().start().toInstant());
        assertTrue(has(ambiguousStart.notices(), "env.notice.timeAmbiguous"));
        var ambiguousEnd = ok(validator.resolveAvailability(request(fallBack, "00:30", "01:30", null, "x"), TORONTO));
        assertEquals(Duration.ofHours(1), ambiguousEnd.availability().duration(), "the period is never widened by the repeated hour");
        assertTrue(validator.resolveAvailability(request(LocalDate.of(2027, 3, 14), "02:30", "05:00", null, "x"), TORONTO).hasError("env.error.timeGap"));
        // Model-proposed times: an offset must be valid for that local time; a bare ambiguous/nonexistent time is rejected.
        assertTrue(FeasibilityValidator.parseProposed("2026-11-01T01:30", TORONTO).hasError("env.reject.ambiguous"));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), ok(FeasibilityValidator.parseProposed("2026-11-01T01:30-05:00", TORONTO)).toInstant());
        assertTrue(FeasibilityValidator.parseProposed("2027-03-14T02:30", TORONTO).hasError("env.reject.gap"));
        assertTrue(FeasibilityValidator.parseProposed("2026-11-07T15:00-04:00", TORONTO).hasError("env.reject.offset"));
        assertTrue(FeasibilityValidator.parseProposed("15:00", TORONTO).hasError("env.reject.format"));
    }

    @Test void crossMidnightAvailabilityUsesTheFollowingDayForEvidenceAndValidation() {
        reply(List.of(
            window("Night sky", local(DAY, "23:00"), local(DAY.plusDays(1), "01:00"), "night", "Cloud cover 40%.", "civilDusk", "civilDawn+1", "h-2026-11-08T00:00"),
            window("Not daylight", local(DAY, "23:30"), local(DAY.plusDays(1), "00:30"), "daylight", "", "sunset")), List.of(), "");
        var a = ok(service.assess(null, request(DAY, "21:00", "02:00", null, "Night photography")));
        assertEquals(local(DAY.plusDays(1), "02:00"), a.availability().orElseThrow().end());
        assertEquals("2026-11-08", providers.requests.stream().filter(u -> u.getPath().equals("/v1/forecast")).map(u -> query(u).get("end_date")).findFirst().orElseThrow());
        assertEquals(2, providers.count("/json"), "sun data for both local dates");
        assertEquals(List.of("Night sky"), a.windows().stream().map(ShootingWindow::label).toList());
        assertEquals(DAY.plusDays(1), a.windows().getFirst().end().toLocalDate());
        assertTrue(has(a.rejected().getFirst().reasons(), "env.reject.light"));
    }

    @Test void geminiFailureOrMalformedOutputStillReturnsTheFactualEvidence() {
        replies.add(Result.failureKey("ai.error.timeout", "30"));
        var a = ok(service.assess(null, request(DAY, "13:00", "19:00", null, "Aviation")));
        assertFalse(a.aiInterpreted()); assertTrue(a.windows().isEmpty());
        assertTrue(a.notices().stream().anyMatch(n -> n.key().equals("env.notice.aiUnavailable")
            && n.arguments().getFirst() instanceof LocalizedMessage reason && reason.key().equals("ai.error.timeout")));
        assertFalse(a.context().hourly().isEmpty()); assertTrue(a.context().sunset().isPresent());
        assertFalse(a.lightPhases().isEmpty());
        replies.add(Result.success(new ModelReply(Optional.of("{\"windows\": \"soon\""), List.of(), List.of())));
        var malformed = ok(service.assess(null, request(DAY, "13:00", "19:00", null, "Aviation")));
        assertFalse(malformed.aiInterpreted());
        assertTrue(malformed.notices().stream().anyMatch(n -> n.key().equals("env.notice.aiUnavailable")));
        assertEquals(1, providers.count("/v1/forecast"), "the second assessment reused fresh cached evidence");
    }

    @Test void missingSunlightIsLabelledAndInsufficientEvidenceProducesNoRecommendation() {
        providers.sun = q -> new Reply(503, "");
        providers.forecast = q -> {
            var body = com.google.gson.JsonParser.parseString(defaultForecast(q).body()).getAsJsonObject();
            body.getAsJsonObject("daily").remove("sunrise"); body.getAsJsonObject("daily").remove("sunset");
            return new Reply(200, body.toString());
        };
        reply(List.of(window("Bright light", local(DAY, "13:00"), local(DAY, "14:00"), "daylight", "", "h-2026-11-07T13:00"),
            window("Any light", local(DAY, "14:00"), local(DAY, "15:00"), "mixed", "Cloud cover 40%.", "h-2026-11-07T14:00")), List.of(), "");
        var a = ok(service.assess(null, request(DAY, "13:00", "19:00", null, "Aviation")));
        assertTrue(a.context().sunrise().isEmpty());
        assertTrue(a.context().unavailableFields().contains(EnvironmentContext.SUN));
        assertTrue(has(a.notices(), "env.notice.sunUnavailable"));
        assertTrue(has(a.rejected().getFirst().reasons(), "env.reject.lightUnverified"), "no daylight claim without sun data");
        assertEquals(List.of("Any light"), a.windows().stream().map(ShootingWindow::label).toList());

        providers.forecast = q -> {
            var body = com.google.gson.JsonParser.parseString(defaultForecast(q).body()).getAsJsonObject();
            body.remove("hourly"); body.getAsJsonObject("daily").remove("sunrise"); body.getAsJsonObject("daily").remove("sunset");
            return new Reply(200, body.toString());
        };
        int before = prompts.size();
        var thin = ok(service.assess(null, request(DAY.plusDays(1), "13:00", "19:00", null, "Aviation")));
        assertTrue(has(thin.notices(), "env.notice.insufficient"));
        assertTrue(thin.windows().isEmpty() && !thin.aiInterpreted());
        assertEquals(before, prompts.size(), "Gemini is not asked to guess without evidence");
        providers.forecast = q -> new Reply(503, "");
        assertFalse(service.assess(null, request(DAY.plusDays(2), "13:00", "19:00", null, "Aviation")).isSuccess(), "no data: explicit failure, no recommendation");
    }

    @Test void staleWeatherIsDetectedPerTypeAndRefetchedInsteadOfReused() {
        var context = ok(service.retrieve(AIRPORT, DAY));
        assertTrue(service.staleFields(context).isEmpty());
        clock.advance(Duration.ofHours(2));
        assertSame(context, ok(service.retrieve(AIRPORT, DAY)), "fresh evidence is reused");
        assertEquals(1, providers.count("/v1/forecast"));
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        assertEquals(Set.of(EnvironmentContext.DAILY, EnvironmentContext.HOURLY), service.staleFields(context), "3 h forecast limit; sun data not stale");
        assertTrue(context.staleFields(clock.instant(), EnvironmentService.SUN_MAX_AGE).isEmpty());
        var refreshed = ok(service.retrieve(AIRPORT, DAY));
        assertNotSame(context, refreshed); assertEquals(2, providers.count("/v1/forecast"));
        assertEquals(clock.instant(), refreshed.retrievedAt());
        // A timestamp from the future is invalid and therefore reported as well.
        var future = new EnvironmentContext(Map.of(), Optional.empty(), Optional.empty(), Map.of(), Map.of("hourlyForecast", clock.instant().plus(Duration.ofHours(1))), Set.of(), clock.instant());
        assertEquals(Set.of("hourlyForecast"), future.staleFields(clock.instant(), Duration.ofHours(3)));
    }

    @Test void confirmedRequirementIsReadOnlyAndContradictionsAndPastTimesAreReported() {
        var requirement = new ShootRequirement("airshow", "Aircraft at an airshow", List.of("Aircraft in flight"),
            Optional.of(ZonedDateTime.of(DAY.plusDays(1), LocalTime.of(20, 0), ZoneId.systemDefault())), Optional.of(DAY.plusDays(1)), "around sunset",
            Optional.empty(), Optional.of(Duration.ofHours(8)), Optional.of(2), Optional.of(false), Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), true);
        replies.add(Result.failureKey("ai.error.serviceUnavailable", "503"));
        var a = ok(service.assess(requirement, request(DAY, "13:00", "19:00", null, "ignored activity")));
        assertEquals("Aircraft at an airshow", a.subject(), "the confirmed F03 subject is used, the F09-only field is ignored");
        assertTrue(has(a.notices(), "env.notice.dateDiffers") && has(a.notices(), "env.notice.timeOutside") && has(a.notices(), "env.notice.durationLonger"));
        assertEquals(Optional.of(DAY.plusDays(1)), requirement.date(), "never modified");
        assertTrue(prompts.getLast().evidenceJson().contains("around sunset"));
        assertTrue(service.assess(null, request(DAY, "13:00", "19:00", null, " ")).hasError("env.error.subject"));
        // 2026-11-04 12:00 UTC is 07:00 in Toronto.
        assertTrue(service.assess(null, request(LocalDate.of(2026, 11, 3), "13:00", "19:00", null, "x")).hasError("env.error.pastDate"));
        assertTrue(service.assess(null, request(LocalDate.of(2026, 11, 4), "05:00", "06:30", null, "x")).hasError("env.error.availabilityOver"));
        replies.add(Result.failureKey("ai.error.serviceUnavailable", "503"));
        assertTrue(has(ok(service.assess(null, request(LocalDate.of(2026, 11, 4), "06:00", "10:00", null, "x"))).notices(), "env.notice.partlyPast"));
        assertTrue(service.assess(requirement).hasError("env.error.needsInput"), "SD03 entry names the inputs a label-only requirement lacks");
    }
}
