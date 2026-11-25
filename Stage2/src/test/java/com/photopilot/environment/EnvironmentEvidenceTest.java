package com.photopilot.environment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.agent.AgentContext;
import com.photopilot.agent.CalculationTool;
import com.photopilot.agent.HistoryRetriever;
import com.photopilot.agent.PhotoPilotAgent;
import com.photopilot.agent.ToolRegistry;
import com.photopilot.application.CapabilityNeedForm;
import com.photopilot.application.PlanPresentation;
import com.photopilot.application.RequirementForm;
import com.photopilot.capability.CapabilityFixtures;
import com.photopilot.command.PlanEditor;
import com.photopilot.command.ShootPlan;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.EnglishMessages;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.integration.EnvironmentApiAdapter;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.repository.JsonInventoryRepository;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.*;
import com.photopilot.strategy.PortabilityRanking;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.EnvironmentEvidence;
import com.photopilot.validation.EnvironmentEvidence.Status;
import com.photopilot.validation.FeasibilityValidator;
import com.photopilot.validation.PlanValidator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2Review1 (high): F06 capability analysis and F10 planning use an F09 assessment only while it applies to the
 * current confirmed F03 requirement and only with evidence that is fresh when it is used. Through the real Facade ->
 * PlanningService -> EnvironmentService/RequirementService/PlanAssembly/PlanValidator chain against loopback Gemini and
 * provider fakes (no live calls); the expiry cases wire the same services by hand around a test clock.
 */
class EnvironmentEvidenceTest {
    static final List<String> GOALS = List.of("Sharp aircraft in flight", "Close-up passes");
    static final ZoneId TORONTO = FakeEnvironmentProviders.TORONTO;
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    final Gear gear = new Gear();
    final LocalDate day = day();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    PlanningService planning() { return app.facade().planning(); }

    /** Confirmed airshow requirement (two bodies, no lens changes) with the given date, location label and start time. */
    static ShootRequirement requirement(LocalDate date, String location, ZonedDateTime start, Duration duration, List<String> goals) {
        return new ShootRequirement("Airshow: two bodies, minimal lens changes, telephoto, portable", "Fast-moving aircraft at an airshow", goals,
            Optional.ofNullable(start), Optional.of(date), "afternoon to sunset", Optional.ofNullable(location).map(GeoLocation::manual),
            Optional.of(duration), Optional.of(2), Optional.of(false), Optional.empty(), "keep the kit portable", List.of(),
            Map.of("telephoto reach", 1, "portability", 2), Map.of(), List.of(), true);
    }
    UUID session(ShootRequirement req) {
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var id = ok(app.facade().sessions().create("Toronto airshow plan", day)).id();
        confirm(id, req);
        ok(planning().confirmProfile(id, profile().asDraft()));
        return id;
    }
    /** The real F03 confirmation path (validation, revision-checked atomic save). */
    void confirm(UUID id, ShootRequirement req) { ok(planning().confirm(id, req.asDraft())); }
    EnvironmentAssessment assess(UUID id, LocalTime from, LocalTime until) {
        var place = new GeoLocation(EnvironmentFixtures.PLACE, EnvironmentFixtures.LAT, EnvironmentFixtures.LON, false, false, Optional.empty());
        gemini.status(503, "");
        return ok(app.facade().assessEnvironment(id, new EnvironmentRequest(place, day, from, until, Optional.of(Duration.ofMinutes(30)),
            Optional.of(TORONTO), "", List.of())));
    }
    /** F06 analysis (the model answers with a capability-only proposal) and the environment it was sent, if any. */
    CapabilityProposal analyze(UUID id) { gemini.reply(CapabilityFixtures.PROPOSAL); return ok(planning().proposeNeeds(id)); }
    JsonObject sentEnvironment() { return PlanFixtures.evidence(gemini.requests.getLast()).getAsJsonObject("environment"); }
    /** The evidence F10 sent with its planning request; the model answers 503, so nothing is generated or saved. */
    JsonObject planEvidence(UUID id, EnvironmentAssessment assessment) {
        int before = gemini.requests.size();
        gemini.status(503, "");
        assertFalse((assessment == null ? planning().generatePreview(id) : planning().generatePreview(id, assessment)).isSuccess());
        return PlanFixtures.evidence(gemini.requests.get(before));
    }
    List<String> planNotes(UUID id) { return keys(ok(planning().planInputs(id)).notes()); }
    static List<String> keys(List<LocalizedMessage> messages) { return messages.stream().map(LocalizedMessage::key).toList(); }
    static String english(LocalizedMessage message) { return EnglishMessages.text(message.key(), message.arguments().toArray()); }
    static List<String> lights(JsonArray windows) { var all = new ArrayList<String>(); windows.forEach(w -> all.add(w.getAsJsonObject().get("light").getAsString())); return all; }
    static List<String> labels(JsonArray windows) { var all = new ArrayList<String>(); windows.forEach(w -> all.add(w.getAsJsonObject().get("label").getAsString())); return all; }
    /** Both consumers refuse the assessment for the same reason, and neither sends any of it to the model. */
    void bothReject(UUID id, String reason) {
        var proposal = analyze(id);
        assertFalse(proposal.environmentUsed(), "F06 used rejected evidence");
        assertEquals(List.of(reason), keys(proposal.environmentNotes()));
        assertNull(sentEnvironment(), "F06 sent rejected evidence to the model");
        var notes = planNotes(id);
        assertTrue(notes.contains(reason) && !notes.contains("plan.env.used"), notes::toString);
        assertFalse(planEvidence(id, null).has("environment"), "F10 sent rejected evidence to the model");
    }
    void bothUse(UUID id) {
        var proposal = analyze(id);
        assertTrue(proposal.environmentUsed()); assertTrue(proposal.environmentNotes().isEmpty(), proposal.environmentNotes()::toString);
        assertNotNull(sentEnvironment());
        var notes = planNotes(id);
        assertTrue(notes.contains("plan.env.used") && notes.stream().noneMatch(k -> k.startsWith("env.use.")), notes::toString);
        assertTrue(planEvidence(id, null).has("environment"));
    }

    // A
    @Test void sameDateDifferentLocationIsNotUsedAsCurrentEvidence() {
        var id = session(requirement(day, EnvironmentFixtures.PLACE, null, Duration.ofHours(3), GOALS));
        PlanFixtures.assess(app, gemini, id);
        bothUse(id);
        confirm(id, requirement(day, "Toronto Island Park", null, Duration.ofHours(3), GOALS));
        bothReject(id, "env.use.location");
        assertTrue(planning().environmentFor(id).isPresent(), "the assessment itself is kept unchanged, never silently deleted");
        var text = english(ok(planning().planInputs(id)).notes().stream().filter(n -> n.key().equals("env.use.location")).findFirst().orElseThrow());
        assertTrue(text.contains(EnvironmentFixtures.PLACE) && text.contains("Toronto Island Park") && text.contains("assess the session again"), text);
    }

    // B
    @Test void sameLocationDifferentDateIsNotUsed() {
        var id = session(PlanFixtures.requirement(day));
        PlanFixtures.assess(app, gemini, id);
        confirm(id, PlanFixtures.requirement(day.plusDays(1)));
        bothReject(id, "env.use.date");
    }

    // C
    @Test void aChangedShootingTimeIsCheckedAgainstWhatTheAssessmentCovers() {
        var morning = ZonedDateTime.of(day, LocalTime.of(9, 0), TORONTO);
        var id = session(requirement(day, null, morning, Duration.ofHours(2), GOALS));
        assess(id, LocalTime.of(9, 0), LocalTime.of(11, 0));
        bothUse(id);
        confirm(id, requirement(day, null, morning.withHour(17), Duration.ofHours(2), GOALS));
        bothReject(id, "env.use.window");
        // Overlapping only partly: usable for the covered part, never presented as covering the whole shoot.
        confirm(id, requirement(day, null, morning.withHour(10), Duration.ofHours(2), GOALS));
        var proposal = analyze(id);
        assertTrue(proposal.environmentUsed()); assertEquals(List.of("env.use.partial"), keys(proposal.environmentNotes()));
        assertEquals("partly", sentEnvironment().get("availabilityCoversShootingTime").getAsString());
        var notes = planNotes(id);
        assertTrue(notes.contains("plan.env.used") && notes.contains("env.use.partial"), notes::toString);
    }

    // D
    @Test void aStartTimeInAnotherTimeZoneIsAMismatchNotAnAssumption() {
        var london = ZonedDateTime.of(day, LocalTime.of(15, 0), ZoneId.of("Europe/London"));
        var id = session(requirement(day, null, london, Duration.ofHours(3), GOALS));
        PlanFixtures.assess(app, gemini, id);
        bothReject(id, "env.use.zone");
        var text = english(ok(planning().planInputs(id)).notes().stream().filter(n -> n.key().equals("env.use.zone")).findFirst().orElseThrow());
        assertTrue(text.contains("America/Toronto") && text.contains("Europe/London"), text);
    }

    // F + basis: a place looked up in Environment Planning stands for the unchanged label-only requirement location.
    @Test void applicableFreshEvidenceKeepsBeingUsedIncludingALookedUpPlaceForAnUnchangedLocation() {
        var id = session(requirement(day, "Lakeshore airfield", null, Duration.ofHours(3), GOALS));
        // A different manual label alone cannot establish an alias for downstream planning.
        PlanFixtures.assess(app, gemini, id);
        bothReject(id, "env.use.location");
        var assessed = assessAirfieldAlias(id);
        bothUse(id);
        var environment = planEvidence(id, null).getAsJsonObject("environment");
        assertTrue(environment.has("hourly") && environment.has("daily") && environment.has("sun"), environment::toString);
        assertEquals(List.of("Afternoon flying display", "Golden-hour flybys"), labels(environment.getAsJsonArray("validatedF09Windows")));
        // The same facts without this process's record of how they were made cannot be matched to the label.
        var copy = assessed.withNotices(List.of());
        assertFalse(planEvidence(id, copy).has("environment"));
        assertEquals(Status.UNVERIFIED, EnvironmentEvidence.check(copy, requirement(day, "Lakeshore airfield", null, Duration.ofHours(3), GOALS), day,
            Optional.empty(), Set.of(), Instant.now()).status());
    }

    private EnvironmentAssessment assessAirfieldAlias(UUID id) {
        var match = new PlaceCandidate(EnvironmentFixtures.PLACE, "Ontario", "Canada", EnvironmentFixtures.LAT,
            EnvironmentFixtures.LON, Optional.of(TORONTO)).forQuery("Lakeshore airfield");
        var fields = EnvironmentFixtures.form(day);
        com.photopilot.application.EnvironmentForm.apply(fields, match);
        var request = ok(com.photopilot.application.EnvironmentForm.parse(fields, planning().location(), match));
        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        return ok(app.facade().assessEnvironment(id, request));
    }

    // E
    @Test void weatherThatExpiresAfterRetrievalIsRecognisedByF06AndF10() throws Exception {
        try (var h = new Harness(root.resolve("clock"), providers, day)) {
            var id = h.session(PlanFixtures.requirement(day));
            var assessed = h.assess(id);
            assertTrue(assessed.staleFields().isEmpty(), "fresh when assessed");
            h.proposal(id);
            assertTrue(h.needsEnvironment().has("weather"), "fresh daily weather reaches F06");
            assertFalse(keys(ok(h.planning.planInputs(id)).notes()).contains("plan.env.stale"));
            h.clock.advance(EnvironmentService.WEATHER_MAX_AGE.plusMinutes(30));
            assertTrue(assessed.staleFields().isEmpty(), "the assessment's own snapshot still says fresh");
            var proposal = h.proposal(id);
            var sent = h.needsEnvironment();
            assertFalse(sent.has("weather"), "expired weather is not sent to F06 as current");
            assertEquals(List.of(EnvironmentContext.DAILY, EnvironmentContext.HOURLY), strings(sent.getAsJsonArray("staleOmitted")));
            assertEquals(List.of("env.notice.stale"), keys(proposal.environmentNotes()));
            var notes = keys(ok(h.planning.planInputs(id)).notes());
            assertTrue(notes.contains("plan.env.used") && notes.contains("plan.env.stale"), notes::toString);
            var planned = h.planEnvironment(id);
            assertFalse(planned.has("hourly") || planned.has("daily"), "expired forecasts are not sent to F10");
            assertTrue(strings(planned.getAsJsonArray("staleOmitted")).contains(EnvironmentContext.HOURLY));
        }
    }

    // G
    @Test void partlyStaleEvidenceKeepsSunAndComputedLightButNotWindowsThatRelyOnWeather() throws Exception {
        try (var h = new Harness(root.resolve("partial"), providers, day)) {
            var id = h.session(PlanFixtures.requirement(day));
            var assessed = h.assess(id);
            assertEquals(List.of("Afternoon flying display", "Golden-hour flybys"), assessed.windows().stream().map(ShootingWindow::label).toList());
            h.clock.advance(EnvironmentService.WEATHER_MAX_AGE.plusMinutes(30));
            var planned = h.planEnvironment(id);
            assertTrue(planned.has("sun"), "sun events are within their own (astronomical) freshness limit");
            assertEquals(List.of("Golden-hour flybys"), labels(planned.getAsJsonArray("validatedF09Windows")),
                "the afternoon window cites a forecast hour and is withheld; the golden-hour window cites only sun and light evidence");
            h.proposal(id);
            var sent = h.needsEnvironment();
            assertEquals(List.of("goldenHour"), lights(sent.getAsJsonArray("shootingWindows")));
            assertTrue(sent.has("lightPhases"), "computed light phases do not age");
            assertFalse(sent.has("weather"));
        }
    }

    // H
    @Test void assessmentsWithoutEnoughRecordedContextAreNeverAssumedToApply() {
        var id = session(PlanFixtures.requirement(day));
        var a = PlanFixtures.assess(app, gemini, id);
        var req = PlanFixtures.requirement(day); var now = Instant.now();
        var legacy = new EnvironmentAssessment(a.context(), a.windows(), List.of());
        assertEquals(Status.UNVERIFIED, EnvironmentEvidence.check(legacy, req, day, Optional.empty(), Set.of(), now).status(), "no request or availability");
        var c = a.context();
        var noZone = new EnvironmentContext(c.place().orElseThrow(), c.date().orElseThrow(), c.endDate().orElseThrow(), Optional.empty(), c.weather(),
            c.hourly(), c.sunEvents(), c.sources(), c.sourceTimestamps(), c.unavailableFields(), c.notices(), c.retrievedAt());
        var zoneless = new EnvironmentAssessment(noZone, a.request().orElseThrow(), a.subject(), a.availability(), a.lightPhases(), a.windows(), a.rejected(),
            a.interpretation(), a.notices(), Set.of(), a.assessedAt());
        assertEquals(Status.UNVERIFIED, EnvironmentEvidence.check(zoneless, req, day, Optional.empty(), Set.of(), now).status(), "no verified time zone");
        var available = a.availability().orElseThrow();
        var utc = new EnvironmentAssessment(c, a.request().orElseThrow(), a.subject(), Optional.of(new Availability(available.start().withZoneSameInstant(ZoneOffset.UTC),
            available.end().withZoneSameInstant(ZoneOffset.UTC))), a.lightPhases(), a.windows(), a.rejected(), a.interpretation(), a.notices(), Set.of(), a.assessedAt());
        assertEquals(Status.ZONE, EnvironmentEvidence.check(utc, req, day, Optional.empty(), Set.of(), now).status(), "availability in another zone");
        // Provider data without a retrieval time cannot be shown to be fresh.
        var stamps = new HashMap<>(c.sourceTimestamps()); stamps.remove(EnvironmentContext.HOURLY);
        var unstamped = new EnvironmentAssessment(new EnvironmentContext(c.place().orElseThrow(), c.date().orElseThrow(), c.endDate().orElseThrow(), c.zone(),
            c.weather(), c.hourly(), c.sunEvents(), c.sources(), stamps, c.unavailableFields(), c.notices(), c.retrievedAt()), a.request().orElseThrow(),
            a.subject(), a.availability(), a.lightPhases(), a.windows(), a.rejected(), a.interpretation(), a.notices(), Set.of(), a.assessedAt());
        var environment = planEvidence(id, unstamped).getAsJsonObject("environment");
        assertFalse(environment.has("hourly"), environment::toString);
        assertTrue(strings(environment.getAsJsonArray("staleOmitted")).contains(EnvironmentContext.HOURLY));
        for (var broken : List.of(legacy, zoneless, utc)) assertFalse(planEvidence(id, broken).has("environment"));
    }

    // I + M: the real F03 form/confirmation path, then F06 and F10 through the shared service, the CLI and the GUI presentations.
    @Test void requirementConfirmationThroughTheFormChangesWhatF06AndF10AcceptInGuiAndCli() throws Exception {
        var id = session(requirement(day, "Lakeshore airfield", null, Duration.ofHours(3), GOALS));
        assessAirfieldAlias(id);
        bothUse(id);
        var current = ok(app.facade().sessions().open(id)).requirements().orElseThrow();
        var edited = ok(RequirementForm.apply(current.asDraft(), Map.of("location", "Toronto Island Park"), TORONTO));
        ok(planning().confirm(ok(app.facade().sessions().open(id)), edited));
        bothReject(id, "env.use.location");
        var english = LocalizationService.english();
        String reason = english(ok(planning().planInputs(id)).notes().stream().filter(n -> n.key().equals("env.use.location")).findFirst().orElseThrow());
        assertTrue(PlanPresentation.inputs(ok(planning().planInputs(id)), english).contains("- " + reason), "GUI plan inputs (PlanPane)");
        var proposal = analyze(id);
        assertTrue(CapabilityNeedForm.review(proposal, english).contains(reason), "GUI review (CapabilityProfilePane)");
        assertFalse(CapabilityNeedForm.review(proposal, english).contains(english.text("needs.review.noEnvironment")), "an existing assessment is not reported as missing");
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        assertEquals(0, cli.execute("plan show " + id));
        gemini.reply(CapabilityFixtures.PROPOSAL);
        assertEquals(0, cli.execute("needs analyze " + id));
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, output.split(java.util.regex.Pattern.quote(reason), -1).length - 1, "CLI shows the same reason for F10 and F06: " + output);
    }

    // J
    @Test void aRevisionAfterADateChangeDoesNotReuseTheEarlierScheduleOrEvidence() throws Exception {
        var id = session(PlanFixtures.requirement(day));
        PlanFixtures.assess(app, gemini, id);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
        var first = ok(planning().generatePreview(id));
        assertTrue(first.approvable(), () -> first.validation().toString());
        var approved = ok(planning().approve(id, first.plan()));
        assertEquals(2, approved.windows().size());
        // Same fixed constraints, so a regeneration is refused and the change goes through a revision of the saved plan.
        confirm(id, PlanFixtures.requirement(day.plusDays(1)));
        assertTrue(planning().generatePreview(id).hasError("plan.error.exists"));
        gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
        var revision = ok(planning().revisePreview(id, "Make the setup lighter."));
        assertFalse(PlanFixtures.evidence(gemini.requests.getLast()).has("environment"), "the earlier date's evidence is not offered to the revision");
        assertTrue(keys(revision.evidence()).contains("env.use.date"), revision.evidence()::toString);
        assertEquals(signatures(approved.windows()), signatures(revision.plan().windows()), "the revision carries the earlier schedule unchanged");
        assertFalse(revision.approvable(), "carried windows from another date are not exempt from validation");
        assertTrue(keys(revision.validation().violations()).contains("plan.violation.noTiming"), revision.validation()::toString);
        assertFalse(planning().approve(id, revision.plan()).isSuccess());
        assertEquals(List.of(1), ok(planning().plans(id)).stream().map(ShootPlan::revision).toList(), "the approved plan is unchanged");
    }

    // K
    @Test void withoutAnyAssessmentTheExistingFallbacksStayUnchanged() {
        var id = session(PlanFixtures.requirement(day));
        var proposal = analyze(id);
        assertFalse(proposal.environmentUsed()); assertTrue(proposal.environmentNotes().isEmpty());
        assertTrue(CapabilityNeedForm.review(proposal, LocalizationService.english()).contains(LocalizationService.english().text("needs.review.noEnvironment")));
        var notes = planNotes(id);
        assertTrue(notes.contains("plan.env.noTiming") && notes.stream().noneMatch(k -> k.startsWith("env.use.")), notes::toString);
        gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, false));
        var untimed = ok(planning().generatePreview(id));
        assertTrue(untimed.approvable(), () -> untimed.validation().toString());
    }

    // L
    @Test void anUnrelatedRequirementChangeKeepsApplicableEvidence() {
        var id = session(requirement(day, "Lakeshore airfield", null, Duration.ofHours(3), GOALS));
        assessAirfieldAlias(id);
        confirm(id, requirement(day, "Lakeshore airfield", null, Duration.ofHours(3), List.of("Sharp aircraft in flight", "Formation passes")));
        bothUse(id);
    }

    static List<String> signatures(List<ShootingWindow> windows) { return windows.stream().map(w -> w.start() + "|" + w.end() + "|" + w.conditions()).toList(); }
    static List<String> strings(JsonArray array) { var all = new ArrayList<String>(); array.forEach(e -> all.add(e.getAsString())); return all; }

    /** A clock the test moves forward; nothing else in the application shares it. */
    static final class MovableClock extends Clock {
        private volatile Instant now;
        MovableClock(Instant start) { now = start; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    /**
     * The production F03/F06/F09/F10 services wired as in ApplicationBootstrap, but around {@link MovableClock}: the provider
     * adapter (loopback fakes) stamps retrieval times with it and the shared evidence check reads it. The model is a
     * recorded stand-in: F09 gets the airshow interpretation, F06 the capability proposal, F10 an error (evidence captured).
     */
    static final class Harness implements AutoCloseable {
        final MovableClock clock = new MovableClock(Instant.now());
        final List<AgentContext> seen = new ArrayList<>();
        final PlanningService planning;
        final SessionService sessions;
        final LocalDate day;
        final HttpClient http = HttpClient.newHttpClient();

        Harness(Path directory, FakeEnvironmentProviders providers, LocalDate day) {
            this.day = day;
            var store = new JsonSessionRepository(directory.resolve("sessions"));
            var calculator = new TechnicalCalculator();
            var tools = new ToolRegistry(Map.of());
            tools.register(new CalculationTool(calculator));
            var agent = new PhotoPilotAgent(context -> {
                seen.add(context);
                String evidence = context.evidenceJson();
                if (evidence.contains("\"fixedUserConstraint\"")) return GearFixtures.json(EnvironmentFixtures.airshowInterpretation(day));
                if (evidence.contains("\"supportedCapabilities\"")) return GearFixtures.json(CapabilityFixtures.PROPOSAL);
                return Result.failureKey("ai.error.network");
            }, tools, new HistoryRetriever(store), 8);
            var feasibility = new FeasibilityValidator(clock);
            var environment = new EnvironmentService(new EnvironmentApiAdapter(providers.weatherEndpoint(), providers.sunEndpoint(), http, Duration.ofSeconds(15), clock),
                new LocationService(Optional.empty(), Optional.empty(), clock), agent, feasibility, clock);
            var compatibility = new CompatibilityValidator();
            var validator = new PlanValidator(compatibility, calculator, feasibility);
            planning = new PlanningService(new RequirementService(agent, clock), new GearMatchingService(compatibility, new PortabilityRanking(agent)), environment,
                agent, new PlanEditor(validator), validator, store, new JsonInventoryRepository(directory.resolve("inventory.json")), calculator);
            sessions = new SessionService(store);
        }
        UUID session(ShootRequirement req) {
            var id = ok(sessions.create("Toronto airshow plan", day)).id();
            ok(planning.confirm(id, req.asDraft()));
            ok(planning.confirmProfile(id, profile().asDraft()));
            return id;
        }
        EnvironmentAssessment assess(UUID id) {
            var place = new GeoLocation(EnvironmentFixtures.PLACE, EnvironmentFixtures.LAT, EnvironmentFixtures.LON, false, false, Optional.empty());
            return ok(planning.assessEnvironment(id, new EnvironmentRequest(place, day, EnvironmentFixtures.from(day), EnvironmentFixtures.until(day),
                Optional.of(Duration.ofMinutes(30)), Optional.of(TORONTO), "", List.of())));
        }
        CapabilityProposal proposal(UUID id) { return ok(planning.proposeNeeds(id)); }
        /** Environment section of the last F06 request, or an empty object when none was sent. */
        JsonObject needsEnvironment() {
            var evidence = JsonParser.parseString(last("\"supportedCapabilities\"")).getAsJsonObject();
            return evidence.has("environment") ? evidence.getAsJsonObject("environment") : new JsonObject();
        }
        /** Environment section F10 sent with a planning request (the stand-in model then fails, so nothing is generated). */
        JsonObject planEnvironment(UUID id) {
            assertFalse(planning.generatePreview(id).isSuccess());
            var evidence = JsonParser.parseString(last("\"candidates\"")).getAsJsonObject();
            assertTrue(evidence.has("environment"), evidence::toString);
            return evidence.getAsJsonObject("environment");
        }
        private String last(String marker) {
            for (int i = seen.size() - 1; i >= 0; i--) if (seen.get(i).evidenceJson().contains(marker)) return seen.get(i).evidenceJson();
            throw new AssertionError("no request containing " + marker);
        }
        @Override public void close() { http.close(); }
    }
}
