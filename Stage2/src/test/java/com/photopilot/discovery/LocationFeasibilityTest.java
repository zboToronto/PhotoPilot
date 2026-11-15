package com.photopilot.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.photopilot.application.DiscoveryForm;
import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.model.ActivitySuggestion.TravelEvidence;
import com.photopilot.model.DiscoveryContext.ZoneSource;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.DiscoveryService;
import com.photopilot.service.GearMatchingService;
import com.photopilot.strategy.PortabilityRanking;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.FeasibilityValidator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2Review1 (medium): Gemini's "atLocation" classification must not establish that an F02 idea needs no travel.
 * Only looked-up evidence decides: a destination is the user's own place when every gazetteer match carries the exact
 * (not approximate) place the user entered, by name and point; nearby or same-point places are separate destinations.
 * Hand-built contexts for the validator rules; the real Facade with loopback Gemini and providers for the workflow.
 */
class LocationFeasibilityTest {
    static final ZoneId ZONE = ZoneId.of("America/Toronto");
    static final ZonedDateTime START = ZonedDateTime.of(2030, 10, 12, 13, 0, 0, 0, ZONE);
    static final Instant NOW = START.minusDays(1).toInstant();
    /** Gazetteer entries: the user's park, a farm about 0.74 km away, and a lookout with exactly the park's point. */
    static final PlaceCandidate PARK = new PlaceCandidate("Riverdale Park East", "Ontario", "Canada", 43.67150, -79.35480, Optional.of(ZONE));
    static final PlaceCandidate FARM = new PlaceCandidate("Riverdale Farm", "Ontario", "Canada", 43.66720, -79.36180, Optional.of(ZONE));
    static final PlaceCandidate LOOKOUT = new PlaceCandidate("Broadview Lookout", "Ontario", "Canada", 43.67150, -79.35480, Optional.of(ZONE));
    /** The user's place as entered with manual coordinates (exact) and as chosen from the place lookup (approximate). */
    static final GeoLocation EXACT = new GeoLocation(PARK.displayName(), PARK.latitude(), PARK.longitude(), false, false, Optional.empty());
    static final GeoLocation LOOKED_UP = PARK.location();
    @TempDir Path root;
    final FeasibilityValidator validator = new FeasibilityValidator(Clock.fixed(NOW, ZoneOffset.UTC));
    final GearInventory gear = new GearFixtures.Airshow().inventory();
    List<Loadout> loadouts;

    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    DiscoveryContext context(GeoLocation home, double maxKm, List<String> avoid) {
        if (loadouts == null) {
            var matching = new GearMatchingService(new CompatibilityValidator(), new PortabilityRanking(GearFixtures.agent(root, new ArrayList<>(), c -> Result.failureKey("ai.error.emptyPrompt"))));
            loadouts = ok(matching.compatibleCandidates(gear));
        }
        return new DiscoveryContext(Optional.of(home), START, Duration.ofHours(4), maxKm, List.of("landscape"), gear, List.of(), Optional.empty(), avoid, "", "",
            Optional.of(Duration.ofHours(2)), ZoneSource.PROVIDER, true, loadouts, List.of(), Set.of(), List.of());
    }
    DiscoveryContext context(GeoLocation home, double maxKm) { return context(home, maxKm, List.of()); }
    /** An indoor-light idea with no gear needs, so time, travel and avoid decide; Gemini's claim is {@code claimedHere}. */
    static ActivitySuggestion idea(String place, boolean claimedHere, ZonedDateTime start) {
        return new ActivitySuggestion(UUID.randomUUID(), "Autumn colour", "landscape", "Park paths", place, claimedHere, Optional.of(start), Duration.ofMinutes(60),
            Optional.empty(), "any", List.of(), "Maples turn red.", List.of(), "UNVERIFIED", Optional.of(NOW)).withProposal("S1", false, false, false, List.of(), List.of());
    }
    /** Evidence exactly as DiscoveryService records it: the gazetteer matches and their straight-line distances from home. */
    static ActivitySuggestion lookedUp(ActivitySuggestion idea, GeoLocation home, GeoLocation... matches) {
        var km = Arrays.stream(matches).map(m -> FeasibilityValidator.straightLineKm(home.latitude().orElseThrow(), home.longitude().orElseThrow(),
            m.latitude().orElseThrow(), m.longitude().orElseThrow())).toList();
        return idea.withTravel(new TravelEvidence(idea.place(), Arrays.stream(matches).map(GeoLocation::label).toList(), km, Optional.empty(), List.of(matches)));
    }
    ActivitySuggestion check(ActivitySuggestion idea, DiscoveryContext context) { return ok(validator.validate(idea, context)); }
    static Feasibility status(ActivitySuggestion idea, String criterion) { return idea.check(criterion).orElseThrow().status(); }
    static List<String> keys(ActivitySuggestion idea, String criterion) { return idea.check(criterion).orElseThrow().findings().stream().map(LocalizedMessage::key).toList(); }
    static GeoLocation at(String name, double lat, double lon) { return new PlaceCandidate(name, "Ontario", "Canada", lat, lon, Optional.of(ZONE)).location(); }

    // A
    @Test void theExactPlaceTheUserEnteredSatisfiesZeroTravelAndEveryOtherCheckStillApplies() {
        var here = check(lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), EXACT, PARK.location()), context(EXACT, 0));
        assertEquals(Feasibility.FEASIBLE, status(here, "travel"));
        assertEquals(List.of("discover.check.travel.samePlace"), keys(here, "travel"));
        assertEquals(Feasibility.FEASIBLE, here.feasibility(), here.checks()::toString);
        assertFalse(keys(here, "time").contains("discover.check.time.travelExcluded"), "no travel time to subtract");
        var avoided = check(lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), EXACT, PARK.location()), context(EXACT, 0, List.of("autumn")));
        assertEquals(Feasibility.FEASIBLE, status(avoided, "travel"));
        assertEquals(Feasibility.INFEASIBLE, avoided.feasibility(), "the avoided subject still decides");
    }

    // B
    @Test void aDistinctLookedUpPlaceViolatesZeroTravel() {
        var farm = check(lookedUp(idea(FARM.displayName(), false, START.plusMinutes(30)), EXACT, FARM.location()), context(EXACT, 0));
        assertEquals(Feasibility.INFEASIBLE, status(farm, "travel"));
        assertEquals(List.of("discover.check.travel.noTravel"), keys(farm, "travel"));
        assertEquals(Feasibility.INFEASIBLE, farm.feasibility());
    }

    // C
    @Test void aNearbyWalkingPlaceLabelledAtLocationDoesNotBypassZeroTravel() {
        var walk = lookedUp(idea(FARM.displayName(), true, START.plusMinutes(30)), EXACT, FARM.location());
        assertTrue(walk.travel().orElseThrow().straightLineKm().getFirst() < 1, "within walking distance");
        var checked = check(walk, context(EXACT, 0));
        assertEquals(Feasibility.INFEASIBLE, status(checked, "travel"), "walking distance is not zero travel");
        assertNotEquals(Feasibility.FEASIBLE, checked.feasibility());
    }

    // D
    @Test void anAtLocationClaimWithoutEvidenceIsUnverifiedNeverFeasible() {
        var claim = check(idea(EXACT.label(), true, START.plusMinutes(30)), context(EXACT, 0));
        assertEquals(Feasibility.UNVERIFIED, status(claim, "travel"));
        assertEquals(List.of("discover.check.travel.atLocationNoTravel"), keys(claim, "travel"));
        assertEquals(Feasibility.UNVERIFIED, claim.feasibility(), "every other check passed; the claim alone is not enough");
        assertTrue(keys(claim, "time").contains("discover.check.time.travelExcluded"), "travel time is not assumed to be zero");
        var nameOnly = check(idea("Riverdale", true, START.plusMinutes(30)), context(GeoLocation.manual("Riverdale"), 0));
        assertEquals(Feasibility.UNVERIFIED, status(nameOnly, "travel"));
        var failedLookup = idea(EXACT.label(), true, START.plusMinutes(30)).withTravel(TravelEvidence.unavailable("Riverdale", LocalizedMessage.of("discover.check.travel.notFound", "Riverdale")));
        assertEquals(Feasibility.UNVERIFIED, status(check(failedLookup, context(EXACT, 0)), "travel"), "a failed lookup leaves the claim unverified, not confirmed");
    }

    // E
    @Test void lookedUpEvidenceOverridesAContradictingAtLocationClaim() {
        var claimed = lookedUp(idea(FARM.displayName(), true, START.plusMinutes(30)), EXACT, FARM.location());
        assertEquals(Feasibility.INFEASIBLE, status(check(claimed, context(EXACT, 0)), "travel"));
        var beyond = check(claimed, context(EXACT, 0.3));
        assertEquals(Feasibility.INFEASIBLE, status(beyond, "travel"));
        assertEquals(List.of("discover.check.travel.beyond"), keys(beyond, "travel"));
        var samePoint = check(lookedUp(idea(LOOKOUT.displayName(), true, START.plusMinutes(30)), EXACT, LOOKOUT.location()), context(EXACT, 0));
        assertEquals(Feasibility.INFEASIBLE, status(samePoint, "travel"), "the same point under another name is not the user's place");
    }

    // F
    @Test void aNonzeroAllowanceKeepsTheStraightLineDistanceCheck() {
        var farm = check(lookedUp(idea(FARM.displayName(), false, START.plusMinutes(30)), EXACT, FARM.location()), context(EXACT, 2));
        assertEquals(Feasibility.FEASIBLE, status(farm, "travel"));
        assertEquals(List.of("discover.check.travel.within", "discover.check.travel.straightLine"), keys(farm, "travel"), "labelled straight-line only");
        assertTrue(keys(farm, "time").contains("discover.check.time.travelExcluded"), "travel time is never claimed as verified");
        assertEquals(0.7, farm.estimatedTravelKm().orElseThrow(), 1e-9);
        assertEquals(Feasibility.FEASIBLE, farm.feasibility());
    }

    // G
    @Test void aDistanceBeyondTheLimitFails() {
        var farm = check(lookedUp(idea(FARM.displayName(), false, START.plusMinutes(30)), EXACT, FARM.location()), context(EXACT, 0.5));
        assertEquals(Feasibility.INFEASIBLE, status(farm, "travel"));
        assertEquals(List.of("discover.check.travel.beyond"), keys(farm, "travel"));
    }

    // H
    @Test void missingCoordinatesNeverBecomeAZeroDistance() {
        var nameOnly = context(GeoLocation.manual(PARK.displayName()), 5);
        var destination = check(idea(FARM.displayName(), false, START.plusMinutes(30)), nameOnly);
        assertEquals(Feasibility.UNVERIFIED, status(destination, "travel"));
        assertEquals(List.of("discover.check.travel.noCoordinates"), keys(destination, "travel"));
        assertTrue(destination.estimatedTravelKm().isEmpty());
        var claim = check(idea(PARK.displayName(), true, START.plusMinutes(30)), nameOnly);
        assertEquals(List.of("discover.check.travel.atLocation"), keys(claim, "travel"));
        assertEquals(Feasibility.UNVERIFIED, status(claim, "travel"));
        var notFound = idea(FARM.displayName(), false, START.plusMinutes(30)).withTravel(TravelEvidence.unavailable("Riverdale Farm", LocalizedMessage.of("discover.check.travel.notFound", "Riverdale Farm")));
        assertEquals(Feasibility.UNVERIFIED, status(check(notFound, context(EXACT, 5)), "travel"));
        assertEquals(Feasibility.INFEASIBLE, status(check(notFound, context(EXACT, 0)), "travel"), "a named destination is still somewhere to go");
    }

    // I
    @Test void invalidCoordinatesAreRejectedOrIgnoredNeverFeasible() {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root.resolve("i")))) {
            var location = app.facade().discovery().location();
            var values = DiscoveryFixtures.form(DiscoveryFixtures.day()); values.put("latitude", "95.00000");
            assertTrue(DiscoveryForm.parse(values, location, Optional.empty()).hasError("env.location.error.latitude"));
            values.put("latitude", "43.67150"); values.put("longitude", "-181");
            assertTrue(DiscoveryForm.parse(values, location, Optional.empty()).hasError("env.location.error.longitude"));
        } catch (Exception failure) { throw new AssertionError(failure); }
        var invalid = new GeoLocation("X", 95, 0, false, false, Optional.empty());
        assertFalse(invalid.validate().isSuccess());
        var broken = new GeoLocation(EXACT.label(), 95, PARK.longitude(), true, false, Optional.empty());
        var idea = idea(PARK.displayName(), false, START.plusMinutes(30)).withTravel(new TravelEvidence(PARK.displayName(), List.of(PARK.displayName()),
            List.of(5000.0), Optional.empty(), List.of(broken)));
        assertEquals(Feasibility.INFEASIBLE, status(check(idea, context(EXACT, 0)), "travel"), "an invalid point never proves identity");
    }

    // J
    @Test void withTravelAllowedNoZeroTravelRuleIsInvented() {
        var allowed = context(EXACT, 40);
        assertEquals(Feasibility.FEASIBLE, status(check(lookedUp(idea(FARM.displayName(), false, START.plusMinutes(30)), EXACT, FARM.location()), allowed), "travel"));
        var claim = check(idea(EXACT.label(), true, START.plusMinutes(30)), allowed);
        assertEquals(Feasibility.UNVERIFIED, status(claim, "travel"), "unverified, not rejected");
        assertEquals(List.of("discover.check.travel.atLocation"), keys(claim, "travel"));
        assertEquals(Feasibility.FEASIBLE, status(check(lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), EXACT, PARK.location()), allowed), "travel"));
    }

    // K
    @Test void theSamePlaceDoesNotBypassTheTimeConstraint() {
        var late = check(lookedUp(idea(PARK.displayName(), false, START.plusHours(3).plusMinutes(30)), EXACT, PARK.location()), context(EXACT, 0));
        assertEquals(Feasibility.FEASIBLE, status(late, "travel"));
        assertEquals(Feasibility.INFEASIBLE, status(late, "time"));
        assertEquals(Feasibility.INFEASIBLE, late.feasibility());
    }

    // P
    @Test void precisionApproximationAndBoundariesFollowTheExistingRules() {
        var stay = context(EXACT, 0);
        var withinPrecision = at(PARK.name(), 43.67152, -79.35480); // same 4-decimal point (about 2 m)
        assertEquals(Feasibility.FEASIBLE, status(check(lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), EXACT, withinPrecision), stay), "travel"));
        var elevenMetres = at(PARK.name(), 43.67160, -79.35480);
        double km = FeasibilityValidator.straightLineKm(EXACT.latitude().orElseThrow(), EXACT.longitude().orElseThrow(), 43.67160, -79.35480);
        assertTrue(km > 0 && km < 0.02, "very small nonzero distance: " + km);
        var close = lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), EXACT, elevenMetres);
        assertEquals(Feasibility.INFEASIBLE, status(check(close, stay), "travel"), "a tiny distance is not identity");
        assertEquals(Feasibility.FEASIBLE, status(check(close, context(EXACT, 0.1)), "travel"), "but it is within a nonzero limit");
        var approximate = check(lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), LOOKED_UP, PARK.location()), context(LOOKED_UP, 0));
        assertEquals(Feasibility.UNVERIFIED, status(approximate, "travel"), "a looked-up point is not the exact shooting spot");
        assertEquals(List.of("discover.check.travel.samePlaceUnverified"), keys(approximate, "travel"));
        var twoParks = check(lookedUp(idea(PARK.displayName(), false, START.plusMinutes(30)), EXACT, PARK.location(), at(PARK.name(), 45.0, -75.0)), stay);
        assertEquals(Feasibility.UNVERIFIED, status(twoParks, "travel"), "the name also matches another place");
        double farm = FeasibilityValidator.straightLineKm(EXACT.latitude().orElseThrow(), EXACT.longitude().orElseThrow(), FARM.latitude(), FARM.longitude());
        var farmIdea = lookedUp(idea(FARM.displayName(), false, START.plusMinutes(30)), EXACT, FARM.location());
        assertEquals(Feasibility.FEASIBLE, status(check(farmIdea, context(EXACT, farm)), "travel"), "a distance equal to the limit is within it");
        assertEquals(Feasibility.INFEASIBLE, status(check(farmIdea, context(EXACT, 0.7)), "travel"), "0.74 km is shown as 0.7 km but compared exactly");
        assertEquals(Feasibility.FEASIBLE, status(check(lookedUp(idea(LOOKOUT.displayName(), false, START.plusMinutes(30)), EXACT, LOOKOUT.location()), context(EXACT, 1)), "travel"),
            "zero distance to another named place passes a nonzero limit");
    }

    /** The real F02 workflow: Facade -> DiscoveryService -> GeocodingAdapter / FeasibilityValidator, loopback providers. */
    @Nested class Workflow {
        FakeGemini gemini;
        FakeEnvironmentProviders providers;
        ApplicationBootstrap app;
        final LocalDate day = DiscoveryFixtures.day();

        @BeforeEach void start() throws Exception {
            gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
            providers.geocode = gazetteer(Map.of("riverdale park east", PARK, "riverdale farm", FARM, "broken place",
                new PlaceCandidate("Broken Place", "Ontario", "Canada", 95, -79.35, Optional.of(ZONE))));
            app = new ApplicationBootstrap(providers.environment(gemini.environment(root.resolve("app"))));
            for (var item : new GearFixtures.Airshow().items()) ok(app.facade().inventory().add(item, true));
        }
        @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }

        Function<Map<String, String>, FakeEnvironmentProviders.Reply> gazetteer(Map<String, PlaceCandidate> known) {
            return q -> {
                var root = new JsonObject(); var match = known.get(q.get("name").toLowerCase(Locale.ROOT));
                if (match != null) {
                    var item = new JsonObject(); item.addProperty("name", match.name()); item.addProperty("latitude", match.latitude());
                    item.addProperty("longitude", match.longitude()); item.addProperty("timezone", ZONE.getId());
                    item.addProperty("admin1", match.region()); item.addProperty("country", match.country());
                    var results = new JsonArray(); results.add(item); root.add("results", results);
                }
                return new FakeEnvironmentProviders.Reply(200, root.toString());
            };
        }
        Map<String, String> form(String distance) {
            var values = DiscoveryFixtures.form(day);
            values.put("location", PARK.displayName()); values.put("latitude", "43.67150"); values.put("longitude", "-79.35480"); values.put("distance", distance);
            return values;
        }
        DiscoveryResult discover(String distance) {
            var start = DiscoveryFixtures.from(day).plusMinutes(30);
            var ideas = new JsonArray();
            ideas.add(DiscoveryFixtures.idea("Z1", "Leaf textures where you are", "landscape", "atLocation", null, null, start, 60, "any", false, false, "Close-ups.", DiscoveryFixtures.needs()));
            ideas.add(DiscoveryFixtures.idea("Z2", "Barn animals", "documentary", "atLocation", "Riverdale Farm", "Ontario, Canada", start, 60, "any", false, false, "Animals.", DiscoveryFixtures.needs()));
            ideas.add(DiscoveryFixtures.idea("Z3", "Park panorama", "landscape", "destination", "Riverdale Park East", "Ontario, Canada", start, 60, "any", false, false, "Skyline view.", DiscoveryFixtures.needs()));
            ideas.add(DiscoveryFixtures.idea("Z4", "Farm fences", "landscape", "destination", "Riverdale Farm", "Ontario, Canada", start, 60, "any", false, false, "Lines.", DiscoveryFixtures.needs()));
            ideas.add(DiscoveryFixtures.idea("Z5", "Broken map pin", "landscape", "destination", "Broken Place", "Ontario, Canada", start, 60, "any", false, false, "x", DiscoveryFixtures.needs()));
            gemini.reply(DiscoveryFixtures.reply(ideas, "Z1", "Z2", "Z3", "Z4", "Z5"));
            return ok(app.facade().discover(ok(DiscoveryForm.parse(form(distance), app.facade().discovery().location(), Optional.empty()))));
        }
        ActivitySuggestion idea(DiscoveryResult r, String id) { return r.ideas().stream().filter(i -> i.aiId().equals(id)).findFirst().orElseThrow(); }
        Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }

        // L (also I for gazetteer points outside the valid range)
        @Test void discoveryDecidesPlacesFromLookupsNotFromGeminisClassification() {
            var r = discover("0");
            var claim = idea(r, "Z1");
            assertTrue(claim.atUserLocation());
            assertEquals(List.of("discover.check.travel.atLocationNoTravel"), keys(claim, "travel"));
            assertEquals(Feasibility.UNVERIFIED, claim.feasibility());
            var named = idea(r, "Z2");
            assertFalse(named.atUserLocation(), "a place Gemini names is checked as a destination");
            assertEquals("Riverdale Farm, Ontario, Canada", named.place());
            assertTrue(named.notes().stream().anyMatch(n -> n.key().equals("discover.note.placeTypeIgnored")));
            assertEquals(Feasibility.INFEASIBLE, status(named, "travel"));
            var park = idea(r, "Z3");
            assertEquals(List.of("discover.check.travel.samePlace"), keys(park, "travel"));
            assertEquals(Feasibility.FEASIBLE, park.feasibility(), park.checks()::toString);
            assertEquals(Feasibility.INFEASIBLE, status(idea(r, "Z4"), "travel"));
            assertEquals(Feasibility.INFEASIBLE, status(idea(r, "Z5"), "travel"));
            assertEquals(List.of(park), r.feasible(), "only the looked-up user place is feasible");
            assertTrue(providers.requests.stream().anyMatch(u -> u.getPath().equals("/v1/search") && u.toString().contains("Riverdale")), "places are looked up with a 0 km limit too");
            assertTrue(providers.count("/v1/search") <= DiscoveryService.MAX_LOOKUPS);
            var allowed = discover("40");
            assertEquals(List.of("discover.check.travel.atLocation"), keys(idea(allowed, "Z1"), "travel"));
            assertEquals(Feasibility.FEASIBLE, status(idea(allowed, "Z4"), "travel"), "within 40 km in a straight line");
            assertEquals(Feasibility.UNVERIFIED, status(idea(allowed, "Z5"), "travel"), "an out-of-range gazetteer point is dropped, never measured");
            assertTrue(keys(idea(allowed, "Z5"), "travel").contains("discover.check.travel.notFound"));
        }

        // M
        @Test void selectAndSaveTreatEachStatusAsBefore() throws Exception {
            var r = discover("0");
            var session = ok(app.facade().sessions().create("Riverdale", day));
            var savedPark = ok(app.facade().discovery().save(session.id(), idea(r, "Z3")));
            assertEquals(Feasibility.FEASIBLE, savedPark.feasibility());
            var savedClaim = ok(app.facade().discovery().save(ok(app.facade().sessions().open(session.id())), idea(r, "Z1")));
            assertEquals(Feasibility.UNVERIFIED, savedClaim.feasibility());
            assertTrue(savedClaim.limitations().stream().anyMatch(t -> t.contains("cannot verify that it needs no travel")), "the caveat is stored with the idea");
            byte[] before = Files.readAllBytes(store());
            assertTrue(app.facade().discovery().save(session.id(), idea(r, "Z4")).hasError("discover.error.infeasible"));
            assertTrue(app.facade().discovery().select(session.id(), idea(r, "Z2")).hasError("discover.error.infeasible"));
            int sessions = ok(app.facade().sessions().search("")).size();
            assertTrue(app.facade().discovery().selectInNewSession(idea(r, "Z4")).hasError("discover.error.infeasible"));
            assertTrue(app.facade().discovery().saveInNewSession(idea(r, "Z2")).hasError("discover.error.infeasible"));
            assertEquals(sessions, ok(app.facade().sessions().search("")).size(), "no orphan session");
            assertArrayEquals(before, Files.readAllBytes(store()), "refused actions write nothing");
            app.close(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root.resolve("app"))));
            var restored = ok(app.facade().discovery().saved(session.id()));
            assertEquals(List.of(Feasibility.FEASIBLE, Feasibility.UNVERIFIED), restored.stream().map(ActivitySuggestion::feasibility).toList());
        }

        // N
        @Test void selectionStillHandsAnUnconfirmedDraftToF03() {
            var r = discover("0");
            var session = ok(app.facade().sessions().create("Riverdale", day));
            var park = ok(app.facade().discovery().select(session.id(), idea(r, "Z3")));
            assertFalse(park.confirmed());
            assertEquals(PARK.displayName(), park.location().orElseThrow().label());
            assertTrue(park.location().orElseThrow().latitude().isEmpty(), "no coordinates are carried into F03");
            var claim = ok(app.facade().discovery().select(session.id(), idea(r, "Z1")));
            assertEquals(PARK.displayName(), claim.location().orElseThrow().label(), "the user's own place label");
            assertFalse(claim.clarificationQuestions().isEmpty(), "the open travel question is asked in F03");
            assertTrue(ok(app.facade().planning().confirm(session.id(), park)).confirmed(), "only explicit F03 confirmation confirms");
        }

        // O
        @Test void guiAndCliShowTheSameValidatedResultFromTheSharedPresentation() {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            var start = DiscoveryFixtures.from(day).plusMinutes(30);
            var ideas = new JsonArray();
            ideas.add(DiscoveryFixtures.idea("Z1", "Leaf textures where you are", "landscape", "atLocation", null, null, start, 60, "any", false, false, "Close-ups.", DiscoveryFixtures.needs()));
            gemini.reply(DiscoveryFixtures.reply(ideas, "Z1"));
            var command = new StringBuilder("discover run");
            form("0").forEach((key, value) -> { if (!value.isEmpty()) command.append(' ').append(key).append("=\"").append(value).append('"'); });
            assertEquals(0, cli.execute(command.toString()), () -> bytes.toString(StandardCharsets.UTF_8));
            String output = bytes.toString(StandardCharsets.UTF_8);
            var latest = app.facade().discovery().latest().orElseThrow();
            var english = LocalizationService.english();
            for (var line : DiscoveryPresentation.idea(latest.ideas().getFirst(), app.localization())) assertTrue(output.contains(line), line);
            assertTrue(output.contains("Idea 1 - Unverified: Leaf textures where you are"), output);
            var card = DiscoveryPresentation.idea(latest.ideas().getFirst(), english);
            assertTrue(card.contains("Where: your place (" + PARK.displayName() + "), according to AI; not verified"), card::toString);
            assertTrue(card.stream().anyMatch(s -> s.contains("cannot verify that it needs no travel at all")), card::toString);
            assertTrue(card.stream().noneMatch(s -> s.contains("no travel needed")), "never presented as confirmed");
        }
    }
}
