package com.photopilot.planning;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.photopilot.application.NextSteps;
import com.photopilot.application.OperationKind;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.progress.*;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.CompletedState;
import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneStatus;
import com.photopilot.workflow.SessionWorkflow;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow milestone 04 through the real Facade -> PlanningService -> JSON repository chain (loopback Gemini and
 * provider fakes): only the assessment the user reviewed, made for the shooting date of an editable session, is added,
 * against the reviewed session version; the record keeps identifying metadata only, and a later change of the shoot
 * never rewrites it, it only asks for review.
 */
class EnvironmentRecordTest {
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    final Gear gear = new Gear();

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = boot(); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    ApplicationBootstrap boot() { return new ApplicationBootstrap(providers.environment(gemini.environment(root))); }

    /** PlanFixtures' confirmed airshow requirement without a date of its own: the shooting date is the session date. */
    static ShootRequirement undated() {
        return new ShootRequirement("Airshow: two bodies, minimal lens changes, telephoto, portable", "Fast-moving aircraft at an airshow",
            List.of("Sharp aircraft in flight", "Close-up passes"), Optional.empty(), Optional.empty(), "afternoon to sunset", Optional.empty(),
            Optional.of(Duration.ofHours(3)), Optional.of(2), Optional.of(false), Optional.empty(), "keep the kit portable", List.of(),
            Map.of("telephoto reach", 1, "portability", 2), Map.of(), List.of(), true);
    }
    /** Draft session on day() with the confirmed requirement and profile and the fixture inventory (no real user data). */
    UUID airshow() {
        for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
        var id = ok(app.facade().sessions().create("Toronto airshow plan", day())).id();
        ok(app.facade().planning().confirm(id, undated().asDraft()));
        ok(app.facade().planning().confirmProfile(id, profile().asDraft()));
        return id;
    }
    /** F07 with Gemini unavailable (the fake answers 503 when nothing is queued): deterministic ranking, explicit selection. */
    void select(UUID id) {
        var best = ok(app.facade().planning().matchGear(id, "portability")).recommendations().getFirst();
        ok(app.facade().planning().selectLoadout(id, best.loadout()));
    }
    /** As PlanFixtures.assess, for another reviewed date. */
    EnvironmentAssessment assessOn(UUID id, LocalDate day) {
        var place = new GeoLocation(EnvironmentFixtures.PLACE, EnvironmentFixtures.LAT, EnvironmentFixtures.LON, false, false, Optional.empty());
        var request = new EnvironmentRequest(place, day, EnvironmentFixtures.from(day), EnvironmentFixtures.until(day), Optional.of(Duration.ofMinutes(30)),
            Optional.of(FakeEnvironmentProviders.TORONTO), "", List.of());
        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        return ok(app.facade().assessEnvironment(id, request));
    }
    PhotographySession open(UUID id) { return ok(app.facade().sessions().open(id)); }
    SessionWorkflow workflow(UUID id) { return ok(app.facade().workflow().of(id)); }
    Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    /** The session's record exactly as written to sessions.json. */
    JsonObject stored(UUID id) throws IOException {
        for (var element : JsonParser.parseString(Files.readString(store())).getAsJsonObject().getAsJsonArray("sessions"))
            if (element.getAsJsonObject().get("id").getAsString().equals(id.toString())) return element.getAsJsonObject();
        throw new AssertionError("no stored session " + id);
    }
    private record Run<T>(Result<T> result, List<ExecutionProgressEvent> events) {
        ExecutionProgressEvent last() { return events.getLast(); }
        List<String> stages() { return last().stages().stream().map(OperationStage::key).toList(); }
    }
    private <T> Run<T> tracked(Supplier<Result<T>> work) {
        var events = new ArrayList<ExecutionProgressEvent>();
        var context = OperationKind.ENV_RECORD.start(events::add);
        context.acknowledge();
        var result = context.run(work);
        context.finish(result, NextSteps.after(OperationKind.ENV_RECORD, result));
        return new Run<>(result, events);
    }

    @Test void theReviewedAssessmentIsRecordedWithItsBasisAndCompletesMilestone04() throws Exception {
        var id = airshow();
        select(id);
        var before = workflow(id);
        assertEquals(MilestoneStatus.NOT_STARTED, before.status(Milestone.ENVIRONMENT));
        assertEquals(Milestone.ENVIRONMENT, before.next().orElseThrow().milestone(), "recommended once the gear is selected");
        var assessment = assess(app, gemini, id);
        assertEquals(before.milestones(), workflow(id).milestones(), "an assessment alone is not recorded progress");

        var check = ok(app.facade().planning().recordEnvironment(open(id), assessment)).environmentCheck().orElseThrow();
        var day = day();
        assertEquals(assessment.assessedAt(), check.assessedAt());
        assertEquals(day, check.date());
        assertEquals(EnvironmentFixtures.PLACE, check.place().label());
        assertEquals(Optional.of(EnvironmentFixtures.LAT), check.place().latitude());
        assertEquals(Optional.of(EnvironmentFixtures.LON), check.place().longitude());
        assertEquals(ZonedDateTime.of(day, EnvironmentFixtures.from(day), FakeEnvironmentProviders.TORONTO), check.availableFrom());
        assertEquals(ZonedDateTime.of(day, EnvironmentFixtures.until(day), FakeEnvironmentProviders.TORONTO), check.availableUntil());
        assertEquals(assessment.availability().orElseThrow().start(), check.availableFrom(), "the reviewed availability");
        assertEquals(2, check.windows(), "the two windows that passed validation (the after-dark one was rejected)");
        assertEquals(Optional.empty(), check.basisLocation(), "the confirmed requirement named no location");
        assertEquals(Optional.of("afternoon to sunset"), check.basisTimeWindow(), "the confirmed timing it was made under");
        assertTrue(check.madeUnderRequirement());

        // Persisted shape: identifying metadata only; no provider facts, weather values or AI text.
        var json = stored(id).getAsJsonObject("environmentCheck");
        assertEquals(Set.of("assessedAt", "date", "place", "availableFrom", "availableUntil", "windows", "basisLocation", "basisTimeWindow"), json.keySet());
        assertEquals(day.toString(), json.get("date").getAsString());
        assertEquals(check.availableFrom().toString(), json.get("availableFrom").getAsString());
        assertEquals(2, json.get("windows").getAsInt());
        assertTrue(json.get("basisLocation").isJsonNull());
        assertEquals("afternoon to sunset", json.get("basisTimeWindow").getAsString());
        assertEquals(EnvironmentFixtures.PLACE, json.getAsJsonObject("place").get("label").getAsString());
        String file = Files.readString(store());
        assertFalse(file.contains("Golden-hour flybys") || file.contains("Afternoon daylight") || file.contains("cloud cover"),"no assessment content is stored");

        var after = workflow(id);
        assertEquals(MilestoneStatus.COMPLETED, after.status(Milestone.ENVIRONMENT));
        assertEquals(LocalizedMessage.of("workflow.detail.environment", EnvironmentFixtures.PLACE, day.toString()), after.state(Milestone.ENVIRONMENT).detail());
        assertEquals(Milestone.PLAN, after.next().orElseThrow().milestone());
        assertEquals(Optional.of("F10"), after.nextStep().orElseThrow().target());
        assertEquals(List.of(4, 7, 57), List.of(after.completed(), after.applicable(), after.percent()), "01-04 of 7");
        // Progress comes from the stored record: a restart forgets the in-process assessment, not the milestone.
        app.close(); app = boot();
        assertTrue(app.facade().planning().environmentFor(id).isEmpty());
        assertEquals(after.milestones(), workflow(id).milestones());
    }

    @Test void recordingIsRefusedWithoutTheReviewedAssessmentOrForAnotherVersionDateOrHistory() throws Exception {
        var id = airshow();
        var other = ok(app.facade().sessions().create("Other shoot", day())).id();
        var planning = app.facade().planning();
        assertTrue(planning.recordEnvironment(open(id), null).hasError("env.record.error.none"), "nothing assessed yet");
        var first = assess(app, gemini, id);
        assertTrue(planning.recordEnvironment(open(other), first).hasError("env.record.error.none"), "assessed for another session");
        assertTrue(planning.recordEnvironment(open(id), null).hasError("env.record.error.none"), "the reviewed assessment must be named");
        // A later run for the session replaced the one the user reviewed.
        var second = assess(app, gemini, id);
        assertNotSame(first, second);
        assertTrue(planning.recordEnvironment(open(id), first).hasError("env.record.error.replaced"));
        // The reviewed session version is no longer the stored one: the repository's revision check refuses the save.
        var reviewed = open(id);
        ok(app.facade().sessions().update(reviewed, "Changed elsewhere", reviewed.date(), reviewed.location(), reviewed.notes()));
        byte[] changed = Files.readAllBytes(store());
        assertTrue(planning.recordEnvironment(reviewed, second).hasError("error.session.changed"));
        assertArrayEquals(changed, Files.readAllBytes(store()), "the other change is kept and nothing is written");
        // Made for another day than the shooting date (the session date, as the requirement names none).
        var later = assessOn(id, day().plusDays(1));
        assertEquals(List.of(LocalizedMessage.of("env.record.error.date", day().plusDays(1).toString(), day().toString())),
            planning.recordEnvironment(open(id), later).messages());
        // Completed sessions keep their history, whatever assessment is offered.
        var completed = ok(new JsonSessionRepository(app.storageScope().sessionsDirectory())
            .insert(SessionFixtures.linked(new CompletedState(), List.of(), false)));
        assertTrue(planning.recordEnvironment(open(completed.id()), later).hasError("env.error.historical"));

        for (var session : ok(app.facade().sessions().search(""))) assertTrue(session.environmentCheck().isEmpty(), session.title());
        assertFalse(Files.readString(store()).contains("environmentCheck"), "sessions without a record carry no such field");
        assertEquals(MilestoneStatus.NOT_STARTED, workflow(id).status(Milestone.ENVIRONMENT));
        assertEquals("Changed elsewhere", open(id).title());
    }

    @Test void recordingInsideAnOperationPublishesItsStagesAndEndsSaved() throws Exception {
        var id = airshow();
        var assessment = assess(app, gemini, id);
        var reviewed = open(id);
        var saved = tracked(() -> app.facade().planning().recordEnvironment(reviewed, assessment));
        assertTrue(saved.result().isSuccess(), () -> saved.result().errors().toString());
        assertEquals(OperationStatus.PENDING, saved.events().getFirst().status());
        assertEquals(List.of("env.recordCheck", "env.record"), saved.events().getFirst().pending(), "the whole plan is shown before work starts");
        assertEquals(List.of("env.recordCheck", "env.record"), saved.stages());
        assertTrue(saved.events().stream().anyMatch(e -> e.status() == OperationStatus.VALIDATING));
        assertEquals(List.of(PersistenceStatus.NOT_APPLICABLE, PersistenceStatus.SAVING, PersistenceStatus.SAVED),
            saved.events().stream().map(ExecutionProgressEvent::persistence).distinct().toList(), "saving is announced before the confirmed save");
        assertEquals(OperationStatus.COMPLETED, saved.last().status());
        assertEquals(PersistenceStatus.SAVED, saved.last().persistence());
        assertEquals(Optional.of("F10"), saved.last().next().orElseThrow().target());
        assertTrue(saved.events().stream().noneMatch(ExecutionProgressEvent::cancellable), "a record is never offered for cancelling");
        assertTrue(open(id).environmentCheck().isPresent(), "SAVED matches what the repository holds");

        // The same, now outdated, version again: the write is attempted and reported as a conflict, never as saved.
        byte[] before = Files.readAllBytes(store());
        var stale = tracked(() -> app.facade().planning().recordEnvironment(reviewed, assessment));
        assertTrue(stale.result().hasError("error.session.changed"));
        assertEquals(OperationStatus.FAILED, stale.last().status());
        assertEquals(ErrorKind.CONFLICT, stale.last().error());
        assertEquals(PersistenceStatus.CONFLICT, stale.last().persistence());
        assertArrayEquals(before, Files.readAllBytes(store()));
        // A refusal before any write claims no persistence at all.
        var dated = assessOn(id, day().plusDays(1));
        var refused = tracked(() -> app.facade().planning().recordEnvironment(open(id), dated));
        assertEquals(ErrorKind.REJECTED, refused.last().error());
        assertEquals(PersistenceStatus.NOT_APPLICABLE, refused.last().persistence());
        assertEquals(List.of("env.recordCheck"), refused.stages());
    }

    @Test void changingTheShootingDateAfterwardsAsksForReviewAndKeepsTheRecord() throws Exception {
        var id = airshow();
        select(id);
        var recorded = ok(app.facade().planning().recordEnvironment(open(id), assess(app, gemini, id)));
        var record = stored(id).getAsJsonObject("environmentCheck");
        ok(app.facade().sessions().update(recorded, recorded.title(), day().plusDays(1), recorded.location(), recorded.notes()));

        var moved = workflow(id);
        assertEquals(MilestoneStatus.NEEDS_REVIEW, moved.status(Milestone.ENVIRONMENT));
        assertEquals("workflow.detail.environmentStale", moved.state(Milestone.ENVIRONMENT).detail().key());
        assertTrue(moved.attention());
        assertEquals(Milestone.ENVIRONMENT, moved.next().orElseThrow().milestone(), "re-checking 04 is the next step");
        assertEquals("workflow.recheck.ENVIRONMENT", moved.nextStep().orElseThrow().message().key());
        assertEquals(List.of(3, 7, 43), List.of(moved.completed(), moved.applicable(), moved.percent()), "04 no longer counts");
        // Historical-record preservation: the stored record is byte-for-byte the one added for the old date.
        assertEquals(record, stored(id).getAsJsonObject("environmentCheck"));
        assertEquals(day(), open(id).environmentCheck().orElseThrow().date());
        // Back on the recorded date the unchanged record applies again.
        var reopened = open(id);
        ok(app.facade().sessions().update(reopened, reopened.title(), day(), reopened.location(), reopened.notes()));
        assertEquals(MilestoneStatus.COMPLETED, workflow(id).status(Milestone.ENVIRONMENT));
        assertEquals(record, stored(id).getAsJsonObject("environmentCheck"));
    }
}
