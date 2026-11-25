package com.photopilot.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.command.ShootPlan;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.model.EnvironmentAssessment.Availability;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.service.PlanPreview;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.validation.FeasibilityValidator;
import com.photopilot.validation.PlanValidator;
import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 D04 / C49: the confirmed F03 start time and duration are a hard limit for every F10 schedule window; a wider
 * F09 availability never widens them. Validator cases use fixed dates and explicit zones (no clock); service cases run the
 * real Facade with loopback Gemini and environment providers and read the JSON store back in a fresh application instance.
 */
class PlanShootingTimeTest {
    static final ZoneId TORONTO = FakeEnvironmentProviders.TORONTO;

    // ------------------------------------------------------------------ validator (input-space partitions, boundaries)

    @Nested class Validator {
        final PlanFixtures.Gear gear = new PlanFixtures.Gear();
        final PlanValidator validator = new PlanValidator(new CompatibilityValidator(), new TechnicalCalculator());
        final LocalDate day = LocalDate.of(2026, 11, 27);

        ShootRequirement requirement(Optional<ZonedDateTime> start, Optional<Duration> duration) {
            var base = PlanFixtures.requirement(start.map(ZonedDateTime::toLocalDate).orElse(day));
            return new ShootRequirement("Fixed start", base.subject(), base.goals(), start, base.date(), "", Optional.empty(), duration,
                base.cameraCount(), base.lensChangesAllowed(), base.maxWeightGrams(), base.portability(), base.gearConstraints(), base.priorities(),
                base.fixedConstraints(), List.of(), true);
        }
        ShootRequirement at(int hour, int minute, int minutes) {
            return requirement(Optional.of(ZonedDateTime.of(day, LocalTime.of(hour, minute), TORONTO)), Optional.of(Duration.ofMinutes(minutes)));
        }
        Loadout loadout() {
            var a = gear.airshow;
            return new Loadout(UUID.randomUUID(), List.of(a.a1.snapshot(), a.a7.snapshot(), a.tele.snapshot(), a.zoom.snapshot(), a.fz.snapshot(), a.sd.snapshot()),
                Map.of(a.a1.id(), a.tele.id(), a.a7.id(), a.zoom.id()), new CompatibilityReport(true, List.of(), List.of(), List.of()), List.of(), List.of());
        }
        ShootPlan plan(ShootRequirement req, List<ShootingWindow> windows) {
            return new ShootPlan(UUID.randomUUID(), 1, false, List.of(), Optional.of(loadout()), Map.of(), windows, List.of("Hearing protection"),
                List.of("Charge all batteries."), List.of("primary: Fast jets"), PlanValidator.constraintsOf(req, Optional.of(PlanFixtures.profile())), List.of(),
                "Summary", Map.of("subject", req.subject(), "date", day.toString()), Map.of("approach", "Track aircraft."), List.of(), List.of(), List.of(), "");
        }
        PlanValidator.Context context(Availability available, Optional<ShootPlan> base) {
            return new PlanValidator.Context(Optional.of(PlanFixtures.profile()), Optional.of(new GearInventory(gear.items())), Optional.empty(),
                Optional.of(available), Optional.of(available.start().getZone()), Optional.of(day), base);
        }
        ZonedDateTime t(int hour, int minute) { return ZonedDateTime.of(day, LocalTime.of(hour, minute), TORONTO); }
        Availability available(ZonedDateTime from, ZonedDateTime until) { return new Availability(from, until); }
        ShootingWindow window(ZonedDateTime start, ZonedDateTime end) { return window(start, end, start); }
        ShootingWindow window(ZonedDateTime start, ZonedDateTime end, ZonedDateTime arrival) {
            return new ShootingWindow(start, end, arrival, "Display " + start.toLocalTime(), true, "Display " + start.toLocalTime(), "mixed", "", List.of(), List.of());
        }
        Set<String> blocking(ShootRequirement req, Availability available, ShootingWindow... windows) {
            var report = validator.assess(plan(req, List.of(windows)), req, context(available, Optional.empty()));
            var keys = new TreeSet<String>(); report.blocking().forEach(m -> keys.add(m.key())); return keys;
        }
        void accepted(ShootRequirement req, Availability available, ShootingWindow... windows) {
            var report = validator.assess(plan(req, List.of(windows)), req, context(available, Optional.empty()));
            assertTrue(report.valid(), () -> report.blocking().toString());
        }

        @Test void theC49WindowBeforeTheConfirmedStartIsRejectedEvenInsideTheWiderAvailability() {
            var req = at(14, 0, 60);
            var keys = blocking(req, available(t(13, 0), t(17, 0)), window(t(13, 10), t(13, 45), t(13, 5)));
            assertEquals(Set.of("plan.violation.requirementTime"), keys);
            var message = validator.assess(plan(req, List.of(window(t(13, 10), t(13, 45)))), req, context(available(t(13, 0), t(17, 0)), Optional.empty()))
                .violations().getFirst();
            assertEquals(List.of("Display 13:10", "2026-11-27 14:00–15:00 America/Toronto"), message.arguments().stream().map(Object::toString).toList());
            accepted(req, available(t(13, 0), t(17, 0)), window(t(14, 5), t(14, 55), t(13, 50)));
        }

        @Test void wideNarrowPartialIdenticalAndDisjointAvailabilityAreIntersectedWithTheConfirmedTime() {
            // F09 narrower than F03 (14:00-17:00 confirmed, 15:00-16:00 available): the availability limits.
            var long3h = at(14, 0, 180);
            assertEquals(Set.of("env.reject.arrival", "plan.violation.availability"), blocking(long3h, available(t(15, 0), t(16, 0)), window(t(14, 30), t(15, 30))));
            accepted(long3h, available(t(15, 0), t(16, 0)), window(t(15, 0), t(16, 0)));
            // Partial overlap (14:00-15:00 confirmed, 14:30-17:00 available): only 14:30-15:00 is usable.
            var hour = at(14, 0, 60);
            assertEquals(Set.of("env.reject.arrival", "plan.violation.availability"), blocking(hour, available(t(14, 30), t(17, 0)), window(t(14, 15), t(14, 45))));
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(hour, available(t(14, 30), t(17, 0)), window(t(14, 45), t(15, 15))));
            accepted(hour, available(t(14, 30), t(17, 0)), window(t(14, 30), t(15, 0)));
            // Identical intervals.
            accepted(hour, available(t(14, 0), t(15, 0)), window(t(14, 0), t(15, 0)));
            // Completely disjoint: nothing can be scheduled.
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(hour, available(t(16, 0), t(17, 0)), window(t(16, 0), t(16, 30))));
            assertEquals(Set.of("env.reject.arrival", "plan.violation.availability"), blocking(hour, available(t(16, 0), t(17, 0)), window(t(14, 0), t(14, 30))));
            // The interval offered to the model is the intersection, in the availability's zone.
            var time = PlanValidator.ShootingTime.of(hour).orElseThrow();
            assertEquals(Optional.of(available(t(14, 0), t(15, 0))), time.within(available(t(13, 0), t(17, 0))));
            assertEquals(Optional.of(available(t(14, 30), t(15, 0))), time.within(available(t(14, 30), t(17, 0))));
            assertEquals(Optional.empty(), time.within(available(t(16, 0), t(17, 0))));
            assertEquals(Optional.empty(), time.within(available(t(15, 0), t(16, 0))), "touching at 15:00 leaves no usable time");
        }

        @Test void startAndEndBoundariesAreInclusiveAndOneMinuteOutsideIsRejected() {
            var req = at(14, 0, 60); var wide = available(t(13, 0), t(17, 0));
            accepted(req, wide, window(t(14, 0), t(14, 30)));
            accepted(req, wide, window(t(14, 30), t(15, 0)));
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(req, wide, window(t(13, 59), t(14, 30))));
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(req, wide, window(t(14, 30), t(15, 1))));
        }

        @Test void aWindowLongerThanTheConfirmedDurationAndATotalOverItAreBothRejected() {
            var req = at(14, 0, 60); var wide = available(t(13, 0), t(17, 0));
            assertEquals(Set.of("plan.violation.duration", "plan.violation.requirementTime"), blocking(req, wide, window(t(14, 0), t(15, 30))));
            // Without a start time the duration still limits the total, never the position.
            var untimed = requirement(Optional.empty(), Optional.of(Duration.ofMinutes(60)));
            assertEquals(Set.of("plan.violation.duration"), blocking(untimed, wide, window(t(13, 0), t(13, 40)), window(t(15, 0), t(15, 30))));
            accepted(untimed, wide, window(t(13, 0), t(13, 30)), window(t(16, 0), t(16, 30)));
        }

        @Test void aStartWithoutDurationLimitsOnlyTheStartAndMissingTimingAddsNoLimit() {
            var open = requirement(Optional.of(t(14, 0)), Optional.empty()); var wide = available(t(13, 0), t(17, 0));
            assertEquals(Set.of("plan.violation.beforeStart"), blocking(open, wide, window(t(13, 30), t(14, 30))));
            accepted(open, wide, window(t(14, 0), t(16, 30)));
            assertTrue(PlanValidator.ShootingTime.of(requirement(Optional.empty(), Optional.empty())).isEmpty());
            accepted(requirement(Optional.empty(), Optional.empty()), wide, window(t(13, 0), t(17, 0)));
            assertTrue(PlanValidator.ShootingTime.of(null).isEmpty());
        }

        @Test void arrivalMayPrecedeTheConfirmedStartOnlyInsideTheAvailability() {
            var req = at(14, 0, 60);
            accepted(req, available(t(13, 0), t(17, 0)), window(t(14, 0), t(14, 45), t(13, 15)));
            assertEquals(Set.of("env.reject.arrival"), blocking(req, available(t(13, 30), t(17, 0)), window(t(14, 0), t(14, 45), t(13, 15))));
            // The requirement-only timing (no F09 evidence) starts the availability at the confirmed start itself.
            assertEquals(Set.of("env.reject.arrival"), blocking(req, available(t(14, 0), t(15, 0)), window(t(14, 0), t(14, 45), t(13, 45))));
        }

        @Test void crossMidnightIntervalsAreComparedAsInstants() {
            var late = requirement(Optional.of(t(23, 30)), Optional.of(Duration.ofMinutes(90)));
            var night = available(t(22, 0), t(22, 0).plusHours(4));
            var next = day.plusDays(1);
            accepted(late, night, window(t(23, 45), ZonedDateTime.of(next, LocalTime.of(0, 45), TORONTO)));
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(late, night, window(ZonedDateTime.of(next, LocalTime.of(0, 30), TORONTO),
                ZonedDateTime.of(next, LocalTime.of(1, 15), TORONTO))));
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(late, night, window(t(23, 0), t(23, 40))));
            var message = validator.assess(plan(late, List.of(window(t(23, 0), t(23, 40)))), late, context(night, Optional.empty())).violations().getFirst();
            assertEquals("2026-11-27 23:30–2026-11-28 01:00 America/Toronto", message.arguments().get(1).toString(), "a midnight crossing names the end date");
        }

        @Test void zonesWithTheSameInstantAgreeAndDaylightSavingTransitionsAreExact() {
            // America/New_York and America/Toronto share offsets: 14:00 New York is 14:00 Toronto.
            var newYork = requirement(Optional.of(ZonedDateTime.of(day, LocalTime.of(14, 0), ZoneId.of("America/New_York"))), Optional.of(Duration.ofMinutes(60)));
            var wide = available(t(13, 0), t(17, 0));
            assertEquals(Set.of("plan.violation.requirementTime"), blocking(newYork, wide, window(t(13, 30), t(14, 15))));
            accepted(newYork, wide, window(t(14, 0), t(15, 0)));
            // Fall back (2026-11-01 02:00 EDT -> 01:00 EST): 00:30 EDT + 120 min ends at 01:30 EST.
            var fall = LocalDate.of(2026, 11, 1);
            var start = ZonedDateTime.ofStrict(LocalDateTime.of(fall, LocalTime.of(0, 30)), ZoneOffset.ofHours(-4), TORONTO);
            var dst = new ShootRequirement("DST", "Night sky", List.of("Stars"), Optional.of(start), Optional.of(fall), "", Optional.empty(),
                Optional.of(Duration.ofMinutes(120)), Optional.of(2), Optional.of(false), Optional.empty(), "", List.of(), Map.of(), Map.of(), List.of(), true);
            var est = ZoneOffset.ofHours(-5);
            var secondOneTen = ZonedDateTime.ofStrict(LocalDateTime.of(fall, LocalTime.of(1, 10)), est, TORONTO);
            var secondOneTwentyFive = ZonedDateTime.ofStrict(LocalDateTime.of(fall, LocalTime.of(1, 25)), est, TORONTO);
            var secondOneForty = ZonedDateTime.ofStrict(LocalDateTime.of(fall, LocalTime.of(1, 40)), est, TORONTO);
            var time = PlanValidator.ShootingTime.of(dst).orElseThrow();
            assertTrue(time.contains(new ShootingWindow(secondOneTen, secondOneTwentyFive, secondOneTen, "repeated hour", true)), "inside, after the fall-back");
            assertFalse(time.contains(new ShootingWindow(secondOneTen, secondOneForty, secondOneTen, "repeated hour", true)), "a wall-clock comparison would accept it");
            // Spring forward (2027-03-14 02:00 EST -> 03:00 EDT): 01:30 EST + 60 min ends at 03:30 EDT.
            var spring = LocalDate.of(2027, 3, 14);
            var early = ZonedDateTime.of(spring, LocalTime.of(1, 30), TORONTO);
            var springTime = new PlanValidator.ShootingTime(early, Optional.of(early.plusMinutes(60)));
            assertEquals(LocalTime.of(3, 30), springTime.end().orElseThrow().toLocalTime());
            assertTrue(springTime.contains(new ShootingWindow(ZonedDateTime.of(spring, LocalTime.of(3, 0), TORONTO), ZonedDateTime.of(spring, LocalTime.of(3, 30), TORONTO),
                ZonedDateTime.of(spring, LocalTime.of(3, 0), TORONTO), "after the gap", true)));
            assertFalse(springTime.contains(new ShootingWindow(ZonedDateTime.of(spring, LocalTime.of(3, 15), TORONTO), ZonedDateTime.of(spring, LocalTime.of(3, 45), TORONTO),
                ZonedDateTime.of(spring, LocalTime.of(3, 15), TORONTO), "after the gap", true)));
        }

        @Test void ambiguousAndNonexistentModelTimesAreRejectedNotGuessed() {
            assertTrue(FeasibilityValidator.parseProposed("2026-11-01T01:30", TORONTO).hasError("env.reject.ambiguous"));
            assertTrue(FeasibilityValidator.parseProposed("2027-03-14T02:30", TORONTO).hasError("env.reject.gap"));
            assertTrue(FeasibilityValidator.parseProposed("2026-11-01T01:30-05:00", TORONTO).isSuccess(), "an explicit valid offset resolves the repeated hour");
            assertTrue(FeasibilityValidator.parseProposed("2026-11-27T25:00", TORONTO).hasError("env.reject.format"));
        }

        @Test void aWindowCarriedFromTheApprovedBaseMustStillMeetTheCurrentConfirmedTime() {
            var req = at(14, 0, 60); var wide = available(t(13, 0), t(17, 0));
            var early = window(t(13, 10), t(13, 45));
            var legacy = plan(req, List.of(early)).approvedCopy().value().orElseThrow(); // e.g. approved before this fix
            var revision = new ShootPlan(legacy.id(), 2, false, List.of(), legacy.loadout(), legacy.settings(), legacy.windows(), List.of("Water"),
                legacy.preparation(), legacy.shotPriorities(), legacy.fixedConstraints(), legacy.calculations(), legacy.summary(), legacy.sessionInfo(),
                legacy.strategy(), legacy.equipmentNotes(), legacy.tradeOffs(), legacy.limitations(), "");
            var report = validator.assess(revision, req, context(wide, Optional.of(legacy)));
            assertEquals(List.of("plan.violation.requirementTime"), report.blocking().stream().map(LocalizedMessage::key).toList());
            assertEquals(List.of(early), legacy.windows(), "the historical approved revision is not changed");
        }
    }

    // ------------------------------------------------------------------ service, persistence, fresh instance

    @Nested class Service {
        @TempDir Path root;
        FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
        final PlanFixtures.Gear gear = new PlanFixtures.Gear();
        final LocalDate day = PlanFixtures.day();

        @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
        @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
        ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
        Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }

        ShootRequirement timed(LocalTime start, int minutes) {
            var base = PlanFixtures.requirement(day);
            return new ShootRequirement("Fixed 14:00 local start, sixty minutes", base.subject(), base.goals(), Optional.of(ZonedDateTime.of(day, start, TORONTO)),
                Optional.of(day), "", Optional.empty(), Optional.of(Duration.ofMinutes(minutes)), base.cameraCount(), base.lensChangesAllowed(),
                base.maxWeightGrams(), base.portability(), base.gearConstraints(), base.priorities(), base.fixedConstraints(), List.of(), true);
        }
        UUID session(ShootRequirement requirement) {
            for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
            var id = ok(app.facade().sessions().create("QA timing boundary", day)).id();
            ok(app.facade().planning().confirm(id, requirement.asDraft()));
            ok(app.facade().planning().confirmProfile(id, PlanFixtures.profile().asDraft()));
            return id;
        }
        EnvironmentAssessment assess(UUID id, LocalTime from, LocalTime until) {
            var place = new GeoLocation("Toronto", 43.65, -79.38, false, false, Optional.empty());
            var request = new EnvironmentRequest(place, day, from, until, Optional.empty(), Optional.of(TORONTO), "", List.of());
            gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
            return ok(app.facade().assessEnvironment(id, request));
        }
        JsonObject window(LocalTime start, int minutes, LocalTime arrival) {
            var from = ZonedDateTime.of(day, start, TORONTO);
            return PlanFixtures.window("Display " + start, from, from.plusMinutes(minutes), ZonedDateTime.of(day, arrival, TORONTO), "daylight",
                "Use daylight for the display.", "sunset");
        }
        String reply(String body, JsonObject... windows) {
            var root = JsonParser.parseString(PlanFixtures.plan(body, TELE, HEAVY_ZOOM, false)).getAsJsonObject();
            var schedule = new JsonArray(); for (var w : windows) schedule.add(w);
            root.getAsJsonObject("result").add("schedule", schedule);
            return root.toString();
        }
        PlanPreview generate(UUID id, JsonObject... windows) {
            gemini.replyWith(body -> reply(body, windows));
            return ok(app.facade().planning().generatePreview(id));
        }
        static List<String> keys(List<LocalizedMessage> messages) { return messages.stream().map(LocalizedMessage::key).toList(); }

        @Test void c49WindowBeforeTheConfirmedStartIsNeitherValidatedNorApprovedNorSaved() throws Exception {
            var id = session(timed(LocalTime.of(14, 0), 60));
            assess(id, LocalTime.of(13, 0), LocalTime.of(17, 0));
            var preview = generate(id, window(LocalTime.of(13, 10), 35, LocalTime.of(13, 5)));
            assertFalse(preview.approvable(), "the C49 window is never presented as validated");
            assertTrue(keys(preview.validation().violations()).contains("plan.violation.requirementTime"), preview.validation()::toString);
            var timing = PlanFixtures.evidence(gemini.requests.getLast()).getAsJsonObject("timing");
            assertEquals(day + "T13:00", timing.get("availableFrom").getAsString(), "the F09 availability is still reported as such");
            assertEquals(day + "T14:00", timing.get("windowsFrom").getAsString(), "the model is told the confirmed interval");
            assertEquals(day + "T15:00", timing.get("windowsUntil").getAsString());
            byte[] before = Files.readAllBytes(store());
            var approval = app.facade().planning().approve(id, preview.plan());
            assertFalse(approval.isSuccess());
            assertTrue(approval.hasError("plan.violation.requirementTime"), approval.errors()::toString);
            assertArrayEquals(before, Files.readAllBytes(store()), "a rejected approval writes nothing");
            assertEquals("DRAFT", ok(app.facade().sessions().open(id)).status());
            assertNotEquals(MilestoneStatus.COMPLETED, ok(app.facade().workflow().of(id)).status(Milestone.PLAN));
            app.close(); app = boot();
            var reread = ok(app.facade().sessions().open(id));
            assertEquals("DRAFT", reread.status()); assertTrue(reread.plans().isEmpty(), "fresh readback: no plan was persisted");
            assertEquals(LocalTime.of(14, 0), reread.requirements().orElseThrow().time().orElseThrow().toLocalTime(), "F03 is unchanged");
        }

        @Test void aWindowInsideTheIntersectionIsApprovedPersistedAndRestored() throws Exception {
            var id = session(timed(LocalTime.of(14, 0), 60));
            assess(id, LocalTime.of(13, 0), LocalTime.of(17, 0));
            var preview = generate(id, window(LocalTime.of(14, 5), 50, LocalTime.of(13, 50)));
            assertTrue(preview.approvable(), preview.validation()::toString);
            var saved = ok(app.facade().planning().approve(id, preview.plan()));
            assertEquals(1, saved.revision());
            assertEquals("PLANNED", ok(app.facade().sessions().open(id)).status());
            assertEquals(MilestoneStatus.COMPLETED, ok(app.facade().workflow().of(id)).status(Milestone.PLAN));
            app.close(); app = boot();
            var restored = ok(app.facade().planning().plans(id));
            assertEquals(1, restored.size());
            var w = restored.getFirst().windows().getFirst();
            assertEquals(ZonedDateTime.of(day, LocalTime.of(14, 5), TORONTO).toInstant(), w.start().toInstant());
            assertEquals(ZonedDateTime.of(day, LocalTime.of(14, 55), TORONTO).toInstant(), w.end().toInstant());
            assertEquals(ZonedDateTime.of(day, LocalTime.of(13, 50), TORONTO).toInstant(), w.arrival().toInstant(), "lead time inside the availability is kept");
        }

        @Test void anInvalidScheduleRevisionLeavesTheApprovedRevisionAndStoreUnchanged() throws Exception {
            var id = session(timed(LocalTime.of(14, 0), 60));
            assess(id, LocalTime.of(13, 0), LocalTime.of(17, 0));
            ok(app.facade().planning().approve(id, generate(id, window(LocalTime.of(14, 5), 50, LocalTime.of(14, 0))).plan()));
            byte[] before = Files.readAllBytes(store());
            gemini.replyWith(body -> {
                var result = new JsonObject(); result.addProperty("status", "change"); result.addProperty("summary", "Start earlier.");
                result.add("questions", new JsonArray()); result.addProperty("conflict", "");
                var changes = new JsonArray(); changes.add("schedule"); result.add("changes", changes);
                var schedule = new JsonArray(); schedule.add(window(LocalTime.of(13, 10), 35, LocalTime.of(13, 5))); result.add("schedule", schedule);
                var envelope = new JsonObject(); envelope.addProperty("step", "final"); envelope.add("result", result); return envelope.toString();
            });
            var revision = ok(app.facade().planning().revisePreview(id, "Start the display at 13:10."));
            assertFalse(revision.approvable());
            assertTrue(keys(revision.validation().violations()).contains("plan.violation.requirementTime"), revision.validation()::toString);
            assertTrue(app.facade().planning().approve(id, revision.plan()).hasError("plan.violation.requirementTime"));
            assertArrayEquals(before, Files.readAllBytes(store()));
            app.close(); app = boot();
            var history = ok(app.facade().planning().plans(id));
            assertEquals(List.of(1), history.stream().map(ShootPlan::revision).toList(), "the approved revision is preserved");
            assertEquals(LocalTime.of(14, 5), history.getFirst().windows().getFirst().start().toLocalTime());
            assertEquals("PLANNED", ok(app.facade().sessions().open(id)).status());
        }

        @Test void approvalRevalidatesAgainstARequirementReconfirmedAfterThePreview() throws Exception {
            var id = session(timed(LocalTime.of(14, 0), 60));
            assess(id, LocalTime.of(13, 0), LocalTime.of(17, 0));
            var preview = generate(id, window(LocalTime.of(14, 5), 50, LocalTime.of(13, 50)));
            assertTrue(preview.approvable(), preview.validation()::toString);
            ok(app.facade().planning().confirm(id, timed(LocalTime.of(14, 30), 60).asDraft()));
            byte[] before = Files.readAllBytes(store());
            var approval = app.facade().planning().approve(id, preview.plan());
            assertTrue(approval.hasError("plan.violation.requirementTime"), approval.errors()::toString);
            assertArrayEquals(before, Files.readAllBytes(store()));
            assertTrue(ok(app.facade().sessions().open(id)).plans().isEmpty());
        }

        @Test void cliShowsTheViolationRefusesApprovalAndWritesNothingInBothLanguages() throws Exception {
            var id = session(timed(LocalTime.of(14, 0), 60));
            assess(id, LocalTime.of(13, 0), LocalTime.of(17, 0));
            var bytes = new java.io.ByteArrayOutputStream();
            var cli = new com.photopilot.ui.cli.PhotoPilotCLI(app.facade(), new com.photopilot.ui.privacy.ImagePrivacyBoundary(), new Scanner(""),
                new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8), app.localization());
            gemini.replyWith(body -> reply(body, window(LocalTime.of(13, 10), 35, LocalTime.of(13, 5))));
            assertEquals(0, cli.execute("plan generate " + id), "a preview is shown so it can be corrected or rejected");
            String generated = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(generated.contains("Validation failed") && !generated.contains("Validation passed"), generated);
            assertTrue(generated.contains("is outside the confirmed shooting time (" + day + " 14:00–15:00 America/Toronto)"), generated);
            byte[] before = Files.readAllBytes(store());
            assertEquals(2, cli.execute("plan approve"));
            assertTrue(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("did not pass validation"));
            app.localization().useLanguage("zh-CN"); bytes.reset();
            assertEquals(0, cli.execute("plan preview"));
            String chinese = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(chinese.contains("超出已确认的拍摄时间（" + day + " 14:00–15:00 America/Toronto）"), chinese);
            assertEquals(2, cli.execute("plan approve"));
            assertArrayEquals(before, Files.readAllBytes(store()));
            assertEquals("DRAFT", ok(app.facade().sessions().open(id)).status());
            app.localization().useLanguage("en");
        }

        @Test void partialAndDisjointF09AvailabilityNeverWidenTheConfirmedTime() throws Exception {
            var id = session(timed(LocalTime.of(14, 0), 60));
            assess(id, LocalTime.of(14, 30), LocalTime.of(17, 0)); // covers only 14:30-15:00 of the confirmed hour
            var late = generate(id, window(LocalTime.of(14, 45), 30, LocalTime.of(14, 40)));
            assertTrue(keys(late.validation().violations()).contains("plan.violation.requirementTime"), late.validation()::toString);
            assertTrue(generate(id, window(LocalTime.of(14, 30), 30, LocalTime.of(14, 30))).approvable());
            assess(id, LocalTime.of(16, 0), LocalTime.of(17, 0)); // does not apply to 14:00-15:00 at all
            var inputs = ok(app.facade().planning().planInputs(id));
            assertTrue(keys(inputs.notes()).contains("env.use.window"), inputs.notes()::toString);
            var disjoint = generate(id, window(LocalTime.of(16, 0), 30, LocalTime.of(16, 0)));
            assertFalse(disjoint.approvable());
            assertTrue(keys(disjoint.validation().violations()).contains("plan.violation.requirementTime"), disjoint.validation()::toString);
            assertTrue(ok(app.facade().sessions().open(id)).plans().isEmpty());
        }
    }
}
