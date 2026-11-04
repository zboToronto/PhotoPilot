package com.photopilot.discovery;

import com.photopilot.common.Result;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.model.ActivitySuggestion.Feasibility;
import com.photopilot.model.ActivitySuggestion.TravelEvidence;
import com.photopilot.model.DiscoveryContext.ZoneSource;
import com.photopilot.service.GearMatchingService;
import com.photopilot.state.CompletedState;
import com.photopilot.strategy.PortabilityRanking;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.FeasibilityValidator;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic F02 feasibility rules on hand-built contexts and evidence; no provider, model or file access. */
class DiscoveryFeasibilityTest {
    @TempDir Path root;
    static final ZoneId ZONE = ZoneId.of("America/Toronto");
    static final ZonedDateTime START = ZonedDateTime.of(2030, 6, 15, 13, 0, 0, 0, ZONE);
    static final Instant NOW = START.minusDays(1).toInstant();
    final FeasibilityValidator validator = new FeasibilityValidator(Clock.fixed(NOW, ZoneOffset.UTC));
    static final GeoLocation HOME = new GeoLocation("Downtown Toronto", 43.6532, -79.3832, false, false, Optional.empty());

    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    List<Loadout> loadouts(GearInventory gear) {
        var matching = new GearMatchingService(new CompatibilityValidator(), new PortabilityRanking(GearFixtures.agent(root, new ArrayList<>(), c -> Result.failureKey("ai.error.emptyPrompt"))));
        return ok(matching.compatibleCandidates(gear));
    }
    DiscoveryContext context(Optional<GeoLocation> place, double maxKm, GearInventory gear, Optional<EnvironmentContext> env, Set<String> stale, List<String> avoid) {
        return new DiscoveryContext(place, START, Duration.ofHours(4), maxKm, List.of("landscape"), gear, List.of(), env, avoid, "", "", Optional.of(Duration.ofHours(2)),
            ZoneSource.PROVIDER, true, gear.items().isEmpty() ? List.of() : loadouts(gear), List.of(), stale, List.of());
    }
    static ActivitySuggestion idea(boolean atLocation, ZonedDateTime start, int minutes, String light, boolean outdoor, boolean clearSky, boolean event, List<CapabilityNeed> needs) {
        return new ActivitySuggestion(UUID.randomUUID(), "Lakeshore sunset", "landscape", "Beach and pier", atLocation ? "Downtown Toronto" : "Sunnyside Beach", atLocation,
            Optional.of(start), Duration.ofMinutes(minutes), Optional.empty(), light, List.of(), "Calm water.", List.of(), "UNVERIFIED", Optional.of(NOW))
            .withProposal("S1", outdoor, clearSky, event, needs, List.of());
    }
    static EnvironmentContext forecast(double cloud, double code) {
        var hours = new ArrayList<EnvironmentContext.Hour>();
        for (int h = 0; h < 24; h++) hours.add(new EnvironmentContext.Hour(START.toLocalDate().atStartOfDay(ZONE).plusHours(h).toInstant(),
            Map.of("cloudCoverPct", cloud, "weatherCode", code, "precipitationProbabilityPct", 10.0)));
        return new EnvironmentContext(HOME, START.toLocalDate(), START.toLocalDate(), Optional.of(ZONE), Map.of(), hours, List.of(),
            Map.of(EnvironmentContext.HOURLY, java.net.URI.create("https://api.open-meteo.com/v1/forecast")), Map.of(EnvironmentContext.HOURLY, NOW), Set.of(), List.of(), NOW);
    }
    static Feasibility status(ActivitySuggestion idea, String criterion) { return idea.check(criterion).orElseThrow().status(); }
    static List<String> keys(ActivitySuggestion idea, String criterion) { return idea.check(criterion).orElseThrow().findings().stream().map(LocalizedMessage::key).toList(); }

    @Test void timeTravelAndOverallStatusFollowTheWorstCheck() {
        var empty = new GearInventory(List.of());
        var c = context(Optional.of(HOME), 40, empty, Optional.of(forecast(20, 1)), Set.of(), List.of());
        var inside = ok(validator.validate(idea(true, START.plusMinutes(30), 60, "any", true, false, false, List.of()), c));
        assertEquals(Feasibility.FEASIBLE, status(inside, "time"));
        assertEquals(Feasibility.UNVERIFIED, inside.feasibility(), "an empty inventory leaves equipment unverified");
        var late = ok(validator.validate(idea(true, START.plusHours(3).plusMinutes(30), 60, "any", false, false, false, List.of()), c));
        assertEquals(Feasibility.INFEASIBLE, late.feasibility());
        assertTrue(keys(late, "time").contains("discover.check.time.outside"));
        var long_ = ok(validator.validate(idea(true, START, 150, "any", false, false, false, List.of()), c));
        assertTrue(keys(long_, "time").contains("discover.check.time.tooLong"), "the longest-activity limit is a hard constraint");
        var past = ok(new FeasibilityValidator(Clock.fixed(START.plusHours(1).toInstant(), ZoneOffset.UTC)).validate(idea(true, START.plusMinutes(30), 60, "any", false, false, false, List.of()), c));
        assertTrue(keys(past, "time").contains("discover.check.time.past"));

        var destination = idea(false, START.plusMinutes(30), 60, "any", false, false, false, List.of());
        assertTrue(keys(ok(validator.validate(destination, c)), "travel").contains("discover.check.travel.notChecked"));
        var near = ok(validator.validate(destination.withTravel(new TravelEvidence("Sunnyside", List.of("Sunnyside, Ontario"), List.of(6.4), Optional.empty())), c));
        assertEquals(Feasibility.FEASIBLE, status(near, "travel"));
        assertEquals(Optional.of(6.4), near.estimatedTravelKm());
        assertTrue(keys(near, "travel").contains("discover.check.travel.straightLine"));
        var far = ok(validator.validate(destination.withTravel(new TravelEvidence("X", List.of("a", "b"), List.of(55.0, 70.0), Optional.empty())), c));
        assertEquals(Feasibility.INFEASIBLE, status(far, "travel"));
        var mixed = ok(validator.validate(destination.withTravel(new TravelEvidence("X", List.of("a", "b"), List.of(10.0, 300.0), Optional.empty())), c));
        assertEquals(Feasibility.UNVERIFIED, status(mixed, "travel"), "an ambiguous name is never resolved to the convenient match");
        assertTrue(mixed.estimatedTravelKm().isEmpty());
        var stay = context(Optional.of(HOME), 0, empty, Optional.empty(), Set.of(), List.of());
        assertEquals(Feasibility.INFEASIBLE, status(ok(validator.validate(destination, stay)), "travel"));
        assertEquals(Feasibility.FEASIBLE, status(ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of()), stay)), "travel"));
        var nameOnly = context(Optional.of(GeoLocation.manual("Toronto area")), 40, empty, Optional.empty(), Set.of(), List.of());
        assertTrue(keys(ok(validator.validate(destination, nameOnly)), "travel").contains("discover.check.travel.noCoordinates"));
        assertEquals(3.0, FeasibilityValidator.straightLineKm(43.6532, -79.3832, 43.6275, -79.3961), 0.3);
    }

    @Test void equipmentNeedsAreComparedWithEveryVerifiedOwnedLoadout() {
        var gear = new GearFixtures.Airshow().inventory();
        var c = context(Optional.of(HOME), 40, gear, Optional.of(forecast(20, 1)), Set.of(), List.of());
        var reach = ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of(GearFixtures.need("focalLength", 150.0, 600.0, 1, true))), c));
        assertEquals(Feasibility.FEASIBLE, status(reach, "equipment"));
        assertTrue(keys(reach, "equipment").contains("discover.check.gear.met"));
        var tooLong = ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of(GearFixtures.need("focalLength", null, 800.0, 1, true))), c));
        assertEquals(Feasibility.INFEASIBLE, status(tooLong, "equipment"));
        assertTrue(keys(tooLong, "equipment").contains("discover.check.gear.unmet"));
        var noBurst = new GearInventory(List.of(GearFixtures.camera("Sony", "A7 IV", "Sony E", 1, 658.0, Set.of("NP-FZ100"), Set.of("SD"), null, true),
            GearFixtures.lens("Sony", "FE 24-105mm G", "Sony E", 24, 105, 4, 77.0, 663.0, true)));
        var unknown = ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of(GearFixtures.need("burstRate", 10.0, null, 1, true))),
            context(Optional.of(HOME), 40, noBurst, Optional.empty(), Set.of(), List.of())));
        assertEquals(Feasibility.UNVERIFIED, status(unknown, "equipment"), "a missing specification is not treated as met");
        assertTrue(keys(unknown, "equipment").contains("discover.check.gear.unknown"));
        var unreadable = new DiscoveryContext(Optional.of(HOME), START, Duration.ofHours(4), 40, List.of(), new GearInventory(List.of()), List.of(), Optional.empty(),
            List.of(), "", "", Optional.empty(), ZoneSource.PROVIDER, false, List.of(), List.of(), Set.of(), List.of());
        assertTrue(keys(ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of()), unreadable)), "equipment").contains("discover.check.gear.unreadable"));
    }

    @Test void environmentEvidenceMustBePresentFreshAndFavourableEnoughButIsNeverAssumed() {
        var empty = new GearInventory(List.of());
        var night = idea(true, START.plusMinutes(30), 60, "any", true, true, false, List.of());
        var overcast = ok(validator.validate(night, context(Optional.of(HOME), 40, empty, Optional.of(forecast(95, 3)), Set.of(), List.of())));
        assertEquals(Feasibility.INFEASIBLE, status(overcast, "environment"), "clear sky needed but overcast throughout");
        assertTrue(keys(overcast, "environment").contains("discover.check.env.overcast"));
        var clear = ok(validator.validate(night, context(Optional.of(HOME), 40, empty, Optional.of(forecast(20, 95)), Set.of(), List.of())));
        assertEquals(Feasibility.FEASIBLE, status(clear, "environment"));
        assertTrue(keys(clear, "environment").contains("discover.check.env.warn.thunder"), "adverse forecasts are warnings for ranking");
        var stale = ok(validator.validate(night, context(Optional.of(HOME), 40, empty, Optional.of(forecast(20, 1)), Set.of(EnvironmentContext.HOURLY), List.of())));
        assertEquals(Feasibility.UNVERIFIED, status(stale, "environment"), "stale forecasts are not relied on");
        var missing = ok(validator.validate(night, context(Optional.of(HOME), 40, empty, Optional.empty(), Set.of(), List.of())));
        assertEquals(Feasibility.UNVERIFIED, status(missing, "environment"));
        var golden = ok(validator.validate(idea(true, START, 60, "goldenHour", true, false, false, List.of()),
            context(Optional.of(HOME), 40, empty, Optional.of(forecast(20, 1)), Set.of(), List.of())));
        assertTrue(keys(golden, "environment").contains("discover.check.env.lightUnverified"), "no sun data: light cannot be confirmed");
        var indoor = ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of()), context(Optional.of(HOME), 40, empty, Optional.empty(), Set.of(), List.of())));
        assertEquals(Feasibility.FEASIBLE, status(indoor, "environment"));
        var event = ok(validator.validate(idea(true, START, 60, "any", false, false, true, List.of()), context(Optional.of(HOME), 40, empty, Optional.empty(), Set.of(), List.of())));
        assertEquals(Feasibility.UNVERIFIED, status(event, "event"), "event schedules are never assumed");
        var avoided = ok(validator.validate(idea(true, START, 60, "any", false, false, false, List.of()), context(Optional.of(HOME), 40, empty, Optional.empty(), Set.of(), List.of("sunset"))));
        assertEquals(Feasibility.INFEASIBLE, status(avoided, "avoid"));
    }

    @Test void aiTextQuotingDistancesTravelTimesOrUnknownMeasurementsIsDetected() {
        assertEquals(List.of("15 km"), FeasibilityValidator.unverifiedFigures("The pier is 15 km from you.", Optional.empty()));
        assertFalse(FeasibilityValidator.unverifiedFigures("Only a 20-minute drive.", Optional.empty()).isEmpty());
        assertFalse(FeasibilityValidator.unverifiedFigures("约 2 小时车程", Optional.empty()).isEmpty());
        assertTrue(FeasibilityValidator.unverifiedFigures("Use the 100-400mm lens at f/5.6.", Optional.empty()).isEmpty(), "focal lengths are equipment values");
        assertTrue(FeasibilityValidator.unverifiedFigures("Cloud cover 20% suits it.", Optional.of(forecast(20, 1))).isEmpty());
        assertEquals(List.of("60%"), FeasibilityValidator.unverifiedFigures("Cloud cover 60% suits it.", Optional.of(forecast(20, 1))));
        assertTrue(FeasibilityValidator.unverifiedFigures("Wind is light.", Optional.empty()).isEmpty());
    }

    @Test void conversionToF03IsAnUnconfirmedDraftAndHistoricalSessionsKeepTheirIdeas() {
        var idea = idea(false, START, 90, "goldenHour", true, false, false, List.of());
        var draft = idea.toRequirement();
        assertFalse(draft.confirmed());
        assertTrue(draft.problems().isEmpty(), draft.problems()::toString);
        assertEquals("Sunnyside Beach", draft.location().orElseThrow().label());
        assertTrue(draft.location().orElseThrow().latitude().isEmpty());
        assertEquals(Optional.of(Duration.ofMinutes(90)), draft.duration());
        assertEquals(Optional.of(START.toLocalDate()), draft.date());
        assertTrue(draft.cameraCount().isEmpty() && draft.gearConstraints().isEmpty(), "nothing the idea did not state is filled in");
        var session = new PhotographySession(UUID.randomUUID(), "Done", START.toLocalDate(), Optional.empty(), "", NOW, NOW, new CompletedState(),
            Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        assertTrue(session.attachIdea(idea.forStorage()).hasError("discover.error.historical"));
        var draftSession = new PhotographySession(UUID.randomUUID(), "Draft", START.toLocalDate(), Optional.empty(), "", NOW, NOW, new com.photopilot.state.DraftState(),
            Optional.empty(), List.of(), List.of(), Optional.empty(), Optional.empty(), List.of());
        var saved = ok(draftSession.attachIdea(idea.forStorage()));
        assertTrue(saved.attachIdea(idea.forStorage()).hasError("discover.error.alreadySaved"));
        var stored = ok(saved.validate()).savedIdeas().getFirst();
        assertEquals(idea.id(), stored.id());
        assertEquals(Feasibility.UNVERIFIED, stored.feasibility(), "an unchecked idea is stored as unverified");
        assertTrue(stored.checks().isEmpty() && stored.aiId().isEmpty(), "transient AI and check details are not persisted");
    }
}
