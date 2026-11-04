package com.photopilot.discovery;

import com.google.gson.JsonArray;
import com.photopilot.application.DiscoveryForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.model.DiscoveryContext.ZoneSource;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 2 F02 acceptance through the real Facade -> DiscoveryService -> EnvironmentService/GeocodingAdapter, F05
 * inventory, F07 loadout generation, PhotoPilotAgent -> GeminiAdapter chain against loopback provider and Gemini
 * fakes, with real F01/F05 JSON persistence and isolated fixture gear (never a real user's inventory).
 */
class F02WorkflowTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    Map<String, String> environment;

    @BeforeEach void start() throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        environment = providers.environment(gemini.environment(root));
        app = new ApplicationBootstrap(environment);
    }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    void seedGear() { for (var item : new GearFixtures.Airshow().items()) ok(app.facade().inventory().add(item, true)); }
    DiscoveryRequest request(Map<String, String> values) { return ok(DiscoveryForm.parse(values, app.facade().discovery().location(), Optional.empty())); }
    static Map<String, ActivitySuggestion> bySubject(DiscoveryResult r) {
        var map = new LinkedHashMap<String, ActivitySuggestion>(); r.ideas().forEach(i -> map.put(i.subject(), i)); return map;
    }
    static Feasibility status(ActivitySuggestion idea, String criterion) { return idea.check(criterion).orElseThrow().status(); }
    static boolean finding(ActivitySuggestion idea, String criterion, String key) {
        return idea.check(criterion).orElseThrow().findings().stream().anyMatch(f -> f.key().equals(key));
    }

    @Test void torontoInspirationWithoutASessionIsGroundedValidatedRankedAndNeverSaved() throws Exception {
        var day = DiscoveryFixtures.day();
        seedGear();
        byte[] gear = Files.readAllBytes(app.storageScope().inventoryFile());
        assertFalse(Files.exists(store()), "no photography session exists");
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));

        var r = ok(app.facade().discover(request(DiscoveryFixtures.form(day))));
        var c = r.context();
        assertEquals(ZoneSource.PROVIDER, c.zoneSource());
        assertEquals(FakeEnvironmentProviders.TORONTO, c.start().getZone());
        assertEquals(Duration.ofHours(4), c.availableTime());
        assertTrue(c.environment().isPresent() && !c.lightPhases().isEmpty());
        assertEquals(11, c.gear().items().size());
        assertFalse(c.usableLoadouts().isEmpty(), "F07 deterministic generation supplies verified loadouts");
        assertTrue(c.notices().stream().anyMatch(n -> n.key().equals("discover.notice.noHistory")), "missing history is optional");

        // Ranked: feasible (interest ties broken by the validated AI priority), then unverified, then not suitable.
        assertEquals(List.of("Aircraft departures at the island airport", "Skyline reflections along the waterfront"),
            r.feasible().stream().map(ActivitySuggestion::subject).toList());
        assertEquals(List.of("Hidden laneway murals"), r.unverified().stream().map(ActivitySuggestion::subject).toList());
        assertEquals(Set.of("Distant bird portraits", "Small-town main street", "Night skyline from a rooftop"),
            Set.copyOf(r.infeasible().stream().map(ActivitySuggestion::subject).toList()));
        assertEquals(r.ideas().subList(0, 3), List.of(r.feasible().get(0), r.feasible().get(1), r.unverified().getFirst()));
        assertTrue(r.limitingFactors().isEmpty() && r.rejected().isEmpty());
        var ideas = bySubject(r);

        var airport = ideas.get("Aircraft departures at the island airport");
        assertEquals(Feasibility.FEASIBLE, status(airport, "travel"));
        double km = airport.estimatedTravelKm().orElseThrow();
        assertTrue(km > 2 && km < 5, "straight-line distance computed from the gazetteer match: " + km);
        assertTrue(finding(airport, "travel", "discover.check.travel.straightLine"), "labelled as straight-line, not a route");
        assertTrue(finding(airport, "time", "discover.check.time.travelExcluded"), "travel time is never invented");
        assertEquals(Feasibility.FEASIBLE, status(airport, "equipment"));
        assertTrue(airport.gear().contains("Sony FE 100-400mm GM") && airport.gear().contains("Sony A1"), airport.gear().toString());
        assertTrue(finding(airport, "environment", "discover.check.env.lightOk"));
        assertTrue(finding(airport, "environment", "discover.check.env.forecast"));
        assertTrue(airport.notes().stream().anyMatch(n -> n.key().equals("discover.note.placeUnverified")), "AI places stay unverified");
        assertTrue(airport.rankReasons().stream().anyMatch(n -> n.key().equals("discover.rank.interests")));
        var skyline = ideas.get("Skyline reflections along the waterfront");
        assertTrue(skyline.atUserLocation());
        assertEquals(DiscoveryFixtures.PLACE, skyline.place());
        assertTrue(finding(skyline, "equipment", "discover.check.gear.noNeeds"));

        var murals = ideas.get("Hidden laneway murals");
        assertEquals(Feasibility.UNVERIFIED, status(murals, "travel"));
        assertTrue(finding(murals, "travel", "discover.check.travel.notFound"));
        assertEquals("", murals.rationale(), "an AI distance/travel-time claim is removed, not shown");
        assertTrue(murals.notes().stream().anyMatch(n -> n.key().equals("discover.note.statementsDropped")));

        var birds = ideas.get("Distant bird portraits");
        assertEquals(Feasibility.INFEASIBLE, status(birds, "equipment"), "no owned lens reaches 800 mm");
        assertEquals(Feasibility.INFEASIBLE, status(birds, "avoid"), "portraits were to be avoided");
        var ohio = ideas.get("Small-town main street");
        assertEquals(Feasibility.INFEASIBLE, status(ohio, "travel"));
        assertTrue(finding(ohio, "travel", "discover.check.travel.beyond"));
        assertEquals(Feasibility.INFEASIBLE, status(ideas.get("Night skyline from a rooftop"), "time"));

        // Gemini receives labels, constraints and provider evidence, never coordinates, item identifiers or the key.
        String body = gemini.requests.getLast();
        assertTrue(body.contains("responseJsonSchema") && body.contains("G1") && body.contains(DiscoveryFixtures.PLACE)
            && body.contains("maxTravelKmStraightLine") && body.contains("sunset"), body);
        assertFalse(body.contains(FakeGemini.KEY) || body.contains(DiscoveryFixtures.LAT) || body.contains(DiscoveryFixtures.LON));
        for (var item : c.gear().items()) assertFalse(body.contains(item.id().toString()));
        // Providers receive coordinates or place names, never interests or AI text.
        assertTrue(providers.requests.stream().anyMatch(u -> u.getPath().equals("/v1/search") && u.toString().contains("Billy")));
        assertTrue(providers.requests.stream().noneMatch(u -> u.toString().contains("aviation") || u.toString().contains("landscape")));
        // Nothing persisted; the real inventory file is untouched.
        assertFalse(Files.exists(store()));
        assertArrayEquals(gear, Files.readAllBytes(app.storageScope().inventoryFile()));
        // Stage 1 suggest(context) returns exactly the usable ideas of the same validation pipeline.
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var usable = ok(app.facade().suggest(c));
        assertEquals(List.of("Aircraft departures at the island airport", "Skyline reflections along the waterfront", "Hidden laneway murals"),
            usable.stream().map(ActivitySuggestion::subject).toList());
    }

    @Test void selectedIdeaBecomesAnUnconfirmedF03DraftAndSaveDismissAreSafe() throws Exception {
        var day = DiscoveryFixtures.day();
        seedGear();
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var r = ok(app.facade().discover(request(DiscoveryFixtures.form(day))));
        var airport = r.feasible().getFirst();
        var session = ok(app.facade().sessions().create(airport.subject(), day));
        byte[] before = Files.readAllBytes(store());

        var draft = ok(app.facade().discovery().select(session.id(), airport));
        assertFalse(draft.confirmed(), "selection never confirms requirements");
        assertEquals(airport.subject(), draft.subject());
        assertEquals("Billy Bishop Toronto City Airport, Ontario, Canada", draft.location().orElseThrow().label());
        assertTrue(draft.location().orElseThrow().latitude().isEmpty(), "no coordinates are carried into F03");
        assertEquals(airport.start(), draft.time());
        assertEquals(Optional.of(Duration.ofMinutes(45)), draft.duration());
        assertEquals("golden hour", draft.timeWindow());
        assertTrue(draft.reviewFields().contains("location") && !draft.clarificationQuestions().isEmpty());
        assertTrue(draft.originalText().contains("Find Something to Shoot"));
        assertArrayEquals(before, Files.readAllBytes(store()), "select never writes");
        assertTrue(app.facade().planning().requirement(session.id()).hasError("requirement.error.none"));
        // Only explicit F03 confirmation creates confirmed requirements.
        var confirmed = ok(app.facade().planning().confirm(session.id(), draft));
        assertTrue(confirmed.confirmed());

        var saved = ok(app.facade().discovery().save(session.id(), airport));
        assertEquals(Feasibility.FEASIBLE, saved.feasibility());
        assertTrue(saved.limitations().stream().anyMatch(t -> t.contains("cannot verify")), "caveats are stored with the idea");
        assertTrue(app.facade().discovery().save(session.id(), airport).hasError("discover.error.alreadySaved"), "duplicate save is refused, nothing changes");
        assertTrue(app.facade().discovery().save(session.id(), r.infeasible().getFirst()).hasError("discover.error.infeasible"));
        var forged = new ActivitySuggestion(airport.id(), "Forged", "", "", "", true, airport.start(), Duration.ofHours(1), Optional.empty(), "any",
            List.of(), "", List.of(), "FEASIBLE", Optional.empty());
        assertTrue(app.facade().discovery().save(session.id(), forged).hasError("discover.error.unknownIdea"), "only issued ideas can be saved");
        assertEquals(airport.subject(), ok(app.facade().discovery().select(session.id(), forged)).subject(),
            "an id saved in this session resolves to the stored idea, never to the caller's copy");
        assertEquals(confirmed.subject(), ok(app.facade().planning().requirement(session.id())).subject(), "saving an idea keeps the confirmed requirement");

        // Restart: the saved idea is restored from the F01 record; another session cannot claim it.
        app.close(); app = new ApplicationBootstrap(environment);
        var stored = ok(app.facade().discovery().saved(session.id()));
        assertEquals(1, stored.size());
        assertEquals(airport.id(), stored.getFirst().id());
        assertEquals(airport.start(), stored.getFirst().start());
        assertEquals(Feasibility.FEASIBLE, stored.getFirst().feasibility());
        var other = ok(app.facade().sessions().create("Another day", day));
        assertTrue(app.facade().discovery().select(other.id(), stored.getFirst()).hasError("discover.error.otherSession"));
        assertFalse(ok(app.facade().discovery().select(session.id(), stored.getFirst())).confirmed(), "a saved idea re-enters F03 unconfirmed");

        // Dismiss affects only the current results.
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var again = ok(app.facade().discover(request(DiscoveryFixtures.form(day))));
        var murals = again.unverified().getFirst();
        byte[] stable = Files.readAllBytes(store());
        assertEquals(murals, ok(app.facade().discovery().dismiss(murals.id())));
        assertTrue(app.facade().discovery().latest().orElseThrow().find(murals.id()).isEmpty());
        assertTrue(app.facade().discovery().dismiss(murals.id()).hasError("discover.error.unknownIdea"));
        assertTrue(app.facade().discovery().select(session.id(), murals).hasError("discover.error.unknownIdea"));
        assertThrows(LocalizedException.class, () -> app.facade().discovery().dismiss(murals));
        assertArrayEquals(stable, Files.readAllBytes(store()), "dismissal never touches saved records");
        assertFalse(app.facade().discovery().save(UUID.randomUUID(), again.feasible().getFirst()).isSuccess(), "unknown session");
    }

    @Test void persistenceFailureIsReportedAndTheStoredSessionIsUnchanged() throws Exception {
        var day = DiscoveryFixtures.day();
        seedGear();
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var r = ok(app.facade().discover(request(DiscoveryFixtures.form(day))));
        var session = ok(app.facade().sessions().create("Waterfront", day));
        var attributes = Files.getFileAttributeView(store(), DosFileAttributeView.class);
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && attributes != null);
        byte[] before = Files.readAllBytes(store());
        attributes.setReadOnly(true);
        try {
            var failed = app.facade().discovery().save(session.id(), r.feasible().getFirst());
            assertFalse(failed.isSuccess(), "a failed write is never reported as saved");
            assertTrue(failed.hasError("error.session.storage"), failed.errors()::toString);
        } finally { attributes.setReadOnly(false); }
        assertArrayEquals(before, Files.readAllBytes(store()));
        assertTrue(ok(app.facade().discovery().saved(session.id())).isEmpty());
        ok(app.facade().discovery().save(session.id(), r.feasible().getFirst()));
    }

    @Test void geminiFailuresAndInvalidCandidatesNeverProduceFabricatedIdeas() throws Exception {
        var day = DiscoveryFixtures.day();
        seedGear();
        var request = request(DiscoveryFixtures.form(day));
        gemini.status(503, "");
        var unavailable = app.facade().discover(request);
        assertFalse(unavailable.isSuccess());
        assertTrue(unavailable.messages().stream().anyMatch(m -> m.key().startsWith("ai.error.")), unavailable.errors()::toString);
        assertTrue(app.facade().discovery().latest().isEmpty(), "no ideas exist after a Gemini failure");
        gemini.reply("Here are some ideas: go outside!");
        assertTrue(app.facade().discover(request).hasError("ai.error.malformedOutput"));
        gemini.reply("{\"suggestions\":\"many\",\"ranking\":[],\"missingContext\":[],\"adjustments\":[],\"summary\":\"\"}");
        assertTrue(app.facade().discover(request).hasError("ai.error.malformedOutput"));

        var start = DiscoveryFixtures.from(day).plusMinutes(30);
        var ideas = new JsonArray();
        ideas.add(DiscoveryFixtures.idea("A1", "Harbour ferries", "documentary", "atLocation", null, null, start, 60, "daylight", true, false,
            "Use G1 with a short zoom.", DiscoveryFixtures.needs(DiscoveryFixtures.need("autofocus", 1.0, null)), "G1"));
        ideas.add(DiscoveryFixtures.idea("A1", "Repeated id", "street", "atLocation", null, null, start, 60, "daylight", true, false, "x", DiscoveryFixtures.needs()));
        ideas.add(DiscoveryFixtures.idea("A2", "Borrowed lens", "wildlife", "atLocation", null, null, start, 60, "daylight", true, false, "x", DiscoveryFixtures.needs(), "G99"));
        ideas.add(DiscoveryFixtures.idea("A3", "Zoom wish", "landscape", "atLocation", null, null, start, 60, "daylight", true, false, "x",
            DiscoveryFixtures.needs(DiscoveryFixtures.need("zoomRatio", 10.0, null))));
        var badTime = DiscoveryFixtures.idea("A4", "Vague time", "landscape", "atLocation", null, null, start, 60, "daylight", true, false, "x", DiscoveryFixtures.needs());
        badTime.addProperty("start", "this afternoon");
        ideas.add(badTime);
        ideas.add(DiscoveryFixtures.idea("A5", "Harbour Ferries!", "documentary", "atLocation", null, null, start, 30, "daylight", true, false, "Same idea.", DiscoveryFixtures.needs()));
        gemini.reply(DiscoveryFixtures.reply(ideas, "A1", "S99"));
        var r = ok(app.facade().discover(request));
        assertEquals(List.of("Harbour ferries"), r.ideas().stream().map(ActivitySuggestion::subject).toList());
        var ferries = r.ideas().getFirst();
        assertEquals("Use Sony A1 with a short zoom.", ferries.rationale(), "inventory labels are shown as the owned item names");
        assertEquals(List.of("autofocus"), ferries.gearNeeds().stream().map(CapabilityNeed::capability).toList(), "a required-only need keeps its meaning without bounds");
        assertEquals(Feasibility.FEASIBLE, status(ferries, "equipment"));
        var reasons = r.rejected().stream().flatMap(x -> x.reasons().stream()).map(m -> m.key()).toList();
        assertTrue(reasons.containsAll(List.of("discover.reject.duplicateId", "discover.reject.gearLabel", "discover.reject.gearNeed",
            "env.reject.format", "discover.reject.duplicate")), reasons.toString());
        assertTrue(r.notices().stream().anyMatch(n -> n.key().equals("discover.notice.rankingIgnored")), "unknown AI ranking ids are ignored");
        assertTrue(r.ideas().getFirst().rankReasons().stream().anyMatch(n -> n.key().equals("discover.rank.noAi")));

        // Nothing feasible: limiting factors and adjustments are explained; constraints are never relaxed.
        var late = new JsonArray();
        late.add(DiscoveryFixtures.idea("L1", "Star trails", "astrophotography", "atLocation", null, null, DiscoveryFixtures.until(day).plusHours(2), 120, "night", true, true, "Dark sky.", DiscoveryFixtures.needs()));
        late.add(DiscoveryFixtures.idea("L2", "Long exposure marathon", "landscape", "atLocation", null, null, DiscoveryFixtures.from(day), 300, "mixed", true, false, "x", DiscoveryFixtures.needs()));
        gemini.reply(DiscoveryFixtures.reply(late, "L1", "L2"));
        var none = ok(app.facade().discover(request));
        assertTrue(none.usable().isEmpty());
        var limits = none.limitingFactors().stream().map(m -> m.key()).toList();
        assertTrue(limits.contains("discover.limit.time") && limits.contains("discover.adjust.time"), limits.toString());
        assertEquals(Duration.ofHours(4), none.context().availableTime(), "the user's availability is unchanged");
        // Only infeasible ideas: none can continue to F03 or be saved.
        var session = ok(app.facade().sessions().create("Night", day));
        assertTrue(app.facade().discovery().select(session.id(), none.ideas().getFirst()).hasError("discover.error.infeasible"));
    }

    @Test void missingOptionalContextAndPermissionsAreHandledHonestly() throws Exception {
        var day = DiscoveryFixtures.day();
        var location = app.facade().discovery().location();
        assertTrue(location.current(false).hasError("env.location.error.consent"), "no device request without consent");
        assertTrue(location.current(true).hasError("env.location.error.deviceUnsupported"));
        location.recordDenial();
        assertTrue(location.current(true).hasError("env.location.error.denied"), "a denial is respected");

        // Manual place name only, empty inventory, no sessions: still usable, nothing contacted, nothing assumed.
        var values = DiscoveryFixtures.form(day); values.put("latitude", ""); values.put("longitude", "");
        var start = DiscoveryFixtures.from(day).plusMinutes(30);
        var ideas = new JsonArray();
        ideas.add(DiscoveryFixtures.idea("M1", "Street corners at golden hour", "street", "atLocation", null, null, start, 60, "daylight", true, false, "x", DiscoveryFixtures.needs()));
        ideas.add(DiscoveryFixtures.idea("M2", "Lakeside cliffs", "landscape", "destination", "Scarborough Bluffs", "Ontario, Canada", start, 60, "daylight", true, false, "x", DiscoveryFixtures.needs()));
        gemini.reply(DiscoveryFixtures.reply(ideas, "M1", "M2"));
        var r = ok(app.facade().discover(request(values)));
        assertTrue(gemini.requests.getLast().replace("\\\"", "\"").contains("\"inventoryEmpty\":true"), "Gemini is told the inventory is empty");
        assertTrue(providers.requests.isEmpty(), "a place name alone contacts no provider");
        assertEquals(ZoneSource.COMPUTER, r.context().zoneSource());
        assertTrue(r.notices().stream().noneMatch(n -> n.key().equals("discover.notice.approximate")), "no distance is measured without coordinates");
        assertTrue(r.feasible().isEmpty() && r.usable().size() == 2, "missing evidence is unverified, never feasible");
        var corner = r.ideas().stream().filter(i -> i.atUserLocation()).findFirst().orElseThrow();
        assertTrue(finding(corner, "environment", "discover.check.env.noCoordinates"));
        assertTrue(finding(corner, "equipment", "discover.check.gear.empty"));
        var bluffs = r.ideas().stream().filter(i -> !i.atUserLocation()).findFirst().orElseThrow();
        assertTrue(finding(bluffs, "travel", "discover.check.travel.noCoordinates"));
        assertTrue(r.limitingFactors().stream().anyMatch(m -> m.key().equals("discover.adjust.coordinates")));

        // Forecast provider down: sun data only, weather never assumed favourable.
        providers.forecast = q -> new FakeEnvironmentProviders.Reply(500, "");
        gemini.reply(DiscoveryFixtures.reply(ideas, "M1", "M2"));
        var partial = ok(app.facade().discover(request(DiscoveryFixtures.form(day))));
        assertTrue(partial.context().environment().orElseThrow().hourly().isEmpty());
        assertTrue(partial.notices().stream().anyMatch(n -> n.key().equals("env.notice.weatherUnavailable")));
        assertTrue(partial.ideas().stream().allMatch(i -> status(i, "environment") == Feasibility.UNVERIFIED));
        assertTrue(finding(partial.ideas().stream().filter(i -> i.atUserLocation()).findFirst().orElseThrow(), "environment", "discover.check.env.noForecast"));
        // Both providers down: no environmental evidence at all, stated as such.
        providers.sun = q -> new FakeEnvironmentProviders.Reply(500, "");
        gemini.reply(DiscoveryFixtures.reply(ideas, "M1", "M2"));
        var elsewhere = DiscoveryFixtures.form(day); elsewhere.put("latitude", "43.66900"); // not served from the F09 evidence cache
        var down = ok(app.facade().discover(request(elsewhere)));
        assertTrue(down.context().environment().isEmpty());
        assertTrue(down.notices().stream().anyMatch(n -> n.key().equals("discover.notice.environmentUnavailable")));
        assertTrue(down.ideas().stream().allMatch(i -> status(i, "environment") == Feasibility.UNVERIFIED));
        providers.forecast = FakeEnvironmentProviders::defaultForecast; providers.sun = FakeEnvironmentProviders::defaultSun;

        // History is used only as a hint and only when allowed; titles and notes are never sent.
        var past = ok(app.facade().sessions().create("Private title", day));
        ok(app.facade().planning().confirm(past.id(), new ShootRequirement("", "Harbourfront night skyline", List.of(), Optional.empty(), Optional.empty(), "",
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), false)));
        gemini.reply(DiscoveryFixtures.reply(ideas, "M1"));
        var withHistory = ok(app.facade().discover(request(DiscoveryFixtures.form(day))));
        assertEquals(1, withHistory.context().history().size());
        assertTrue(gemini.requests.getLast().contains("Harbourfront night skyline") && !gemini.requests.getLast().contains("Private title"));
        var noHistory = DiscoveryFixtures.form(day); noHistory.put("history", "no");
        gemini.reply(DiscoveryFixtures.reply(ideas, "M1"));
        assertTrue(ok(app.facade().discover(request(noHistory))).context().history().isEmpty());
        assertFalse(gemini.requests.getLast().contains("Harbourfront night skyline"));
    }

    @Test void invalidInputsAreAllReportedBeforeAnyRequest() {
        var day = DiscoveryFixtures.day();
        var location = app.facade().discovery().location();
        var values = DiscoveryFixtures.form(day);
        values.put("location", ""); values.put("latitude", "43.6"); values.put("longitude", "");
        values.put("distance", "-1"); values.put("until", values.get("from")); values.put("history", "maybe");
        var invalid = DiscoveryForm.parse(values, location, Optional.empty());
        assertFalse(invalid.isSuccess());
        for (String key : List.of("discover.error.location", "discover.error.distance", "discover.error.history")) assertTrue(invalid.hasError(key), key);
        var coordinates = DiscoveryFixtures.form(day); coordinates.put("longitude", "");
        assertTrue(DiscoveryForm.parse(coordinates, location, Optional.empty()).hasError("discover.error.coordinatePair"));
        var empty = DiscoveryFixtures.form(day); empty.put("until", empty.get("from"));
        assertTrue(DiscoveryForm.parse(empty, location, Optional.empty()).hasError("env.error.emptyAvailability"));
        var tooLong = DiscoveryFixtures.form(day); tooLong.put("duration", "300");
        assertTrue(DiscoveryForm.parse(tooLong, location, Optional.empty()).hasError("discover.error.durationWindow"));
        var far = DiscoveryFixtures.form(day); far.put("distance", "5000");
        assertTrue(DiscoveryForm.parse(far, location, Optional.empty()).hasError("discover.error.distance"));
        var past = DiscoveryFixtures.form(day); past.put("date", LocalDate.now().minusDays(3).toString());
        assertTrue(app.facade().discover(request(past)).hasError("env.error.pastDate"));
        assertTrue(gemini.requests.isEmpty(), "no Gemini request for invalid inputs");
        assertTrue(app.facade().discover(null).hasError("discover.error.context"));
    }

    @Test void ideasSavedBeforeF02FieldsLoadAsUnverifiedWithAStableId() throws Exception {
        Path file = root.resolve("legacy/sessions.json"); Files.createDirectories(file.getParent());
        try (var input = getClass().getResourceAsStream("/persistence/legacy-v1-sessions.json")) { Files.copy(Objects.requireNonNull(input), file); }
        byte[] before = Files.readAllBytes(file);
        var first = ok(new com.photopilot.repository.JsonSessionRepository(file.getParent()).search("")).getFirst().savedIdeas().getFirst();
        var second = ok(new com.photopilot.repository.JsonSessionRepository(file.getParent()).search("")).getFirst().savedIdeas().getFirst();
        assertEquals(Feasibility.UNVERIFIED, first.feasibility(), "never-checked legacy ideas are not presented as feasible");
        assertEquals(first.id(), second.id());
        assertEquals("Portrait", first.subject());
        assertArrayEquals(before, Files.readAllBytes(file), "loading does not rewrite the file");
    }
}
