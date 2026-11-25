package com.photopilot.ui.cli;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.PhotographySession;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.PlannedState;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.workflow.Milestone;
import com.photopilot.workflow.MilestoneStatus;
import com.photopilot.workflow.SessionWorkflow;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F01 workflow progress in the CLI: the shared SessionWorkflowService numbers after the existing session lines, one line
 * per milestone for 'session progress', the 'env record' command, the milestone-based [NEXT] line on the progress stream
 * (standard output unchanged), Chinese texts and help. Loopback Gemini and provider fakes only.
 */
class SessionWorkflowCliTest {
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;
    /** Ahead of today, so the optional environment step is still recommended (F09 refuses past dates). */
    static final LocalDate AHEAD = LocalDate.now().plusDays(10);

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }

    private static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    private static List<String> lines(ByteArrayOutputStream bytes) { return text(bytes).lines().toList(); }
    private PhotoPilotCLI cli(ByteArrayOutputStream out) {
        return new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
    }
    private SessionWorkflow workflow(UUID id) { return ok(app.facade().workflow().of(id)); }
    private Path store() { return app.storageScope().sessionsDirectory().resolve("sessions.json"); }
    /** Draft session with confirmed F03 requirements and F06 profile (manual confirmations, no Gemini call). */
    private UUID planning() {
        var id = ok(app.facade().sessions().create("Airshow", AHEAD)).id();
        ok(app.facade().planning().confirm(id, PlanFixtures.requirement(AHEAD).asDraft()));
        ok(app.facade().planning().confirmProfile(id, GearFixtures.airshowProfile().asDraft()));
        return id;
    }
    private PhotographySession planned() {
        return ok(new JsonSessionRepository(app.storageScope().sessionsDirectory()).insert(SessionFixtures.linked(new PlannedState(), List.of(), false)));
    }
    /** The two workflow lines carry exactly the numbers, phase and next milestone of app.facade().workflow().of(id). */
    private void sameAsFacade(UUID id, String progress, String next) {
        var w = workflow(id); var l = app.localization();
        String phase = w.attention() ? l.text("workflow.phaseAttention", l.text(w.phase().labelKey())) : l.text(w.phase().labelKey());
        assertEquals(String.format(Locale.ROOT, "Workflow progress: %d%% (%d/%d milestones) · %s", w.percent(), w.completed(), w.applicable(), phase), progress);
        // The milestone comes from the facade; the exact command is asserted literally by each caller.
        assertTrue(next.startsWith("Next step: " + l.text(w.next().orElseThrow().milestone().titleKey()) + " (") && next.endsWith(")")
            && next.contains(id.toString()), next);
    }

    @Test void createOpenEditAndTransitionPrintTheExistingLinesThenTheSharedWorkflowProgress() {
        var out = new ByteArrayOutputStream(); var cli = cli(out);
        assertEquals(0, cli.execute("session create title=\"Workflow shoot\" date=" + AHEAD));
        var id = ok(app.facade().sessions().search("")).getFirst().id();
        var created = lines(out);
        assertEquals(6, created.size(), created::toString);
        assertEquals("Session saved.", created.get(0));
        assertTrue(created.get(1).startsWith(id + " | Workflow shoot | " + AHEAD + " | "), created.get(1));
        assertTrue(created.get(3).startsWith("Linked records — requirements: 0"), created.get(3));
        assertEquals("Workflow progress: 0% (0/7 milestones) · Getting started", created.get(4));
        assertEquals("Next step: Shooting Requirements (requirement parse " + id + " <request text>)", created.get(5));
        sameAsFacade(id, created.get(4), created.get(5));
        assertEquals(Milestone.REQUIREMENTS, workflow(id).next().orElseThrow().milestone());

        // Unchanged session: 'session open' prints the same record lines and the same progress.
        out.reset();
        assertEquals(0, cli.execute("session open " + id));
        assertEquals(created.subList(1, 6), lines(out));

        ok(app.facade().planning().confirm(id, PlanFixtures.requirement(AHEAD).asDraft()));
        ok(app.facade().planning().confirmProfile(id, GearFixtures.airshowProfile().asDraft()));
        out.reset();
        assertEquals(0, cli.execute("session open " + id));
        var opened = lines(out);
        assertEquals(5, opened.size(), opened::toString);
        assertTrue(opened.get(2).startsWith("Linked records — requirements: 1"), opened.get(2));
        assertEquals("Workflow progress: 29% (2/7 milestones) · Planning in progress", opened.get(3));
        assertEquals("Next step: Gear Selection (match run " + id + ")", opened.get(4));
        sameAsFacade(id, opened.get(3), opened.get(4));

        out.reset();
        assertEquals(0, cli.execute("session edit " + id + " notes=\"Bring ear plugs\""));
        var edited = lines(out);
        assertEquals("Session saved.", edited.getFirst());
        assertTrue(edited.get(2).contains("Bring ear plugs"), edited.get(2));
        assertEquals(opened.subList(3, 5), edited.subList(edited.size() - 2, edited.size()), "notes are not a milestone");

        // A planned shoot whose date has passed: the shoot needs recording; then the review is in progress.
        var shoot = planned();
        out.reset();
        assertEquals(0, cli.execute("session open " + shoot.id()));
        var before = lines(out);
        // The overdue shoot asks for action, so the phase carries the same "needs attention" as the GUI views.
        assertEquals("Workflow progress: 67% (4/6 milestones) · Ready to shoot · needs attention", before.get(before.size() - 2));
        assertEquals("Next step: Shooting Completion (session transition " + shoot.id() + " event=SHOOT_COMPLETED, after the shoot)", before.getLast());
        sameAsFacade(shoot.id(), before.get(before.size() - 2), before.getLast());
        out.reset();
        assertEquals(0, cli.execute("session transition " + shoot.id() + " event=SHOOT_COMPLETED"));
        var after = lines(out);
        assertEquals("Session saved.", after.getFirst());
        assertEquals("Workflow progress: 83% (5/6 milestones) · Awaiting review", after.get(after.size() - 2));
        // The fixture's statistics are already saved: the review continues with its draft, not with a new import.
        assertEquals("Next step: Post-Shoot Review (review generate " + shoot.id() + ")", after.getLast());
        sameAsFacade(shoot.id(), after.get(after.size() - 2), after.getLast());
    }

    @Test void anApprovedPlanWhoseShootMovedNamesTheRevisionCommand() {
        var id = PlanFixtures.session(app, new PlanFixtures.Gear(), true);
        PlanFixtures.assess(app, gemini, id);
        gemini.reply(PlanFixtures.depthOfFieldRequest()).replyWith(body -> PlanFixtures.plan(body, PlanFixtures.TELE, PlanFixtures.HEAVY_ZOOM, true));
        var preview = ok(app.facade().planning().generatePreview(id));
        assertTrue(preview.approvable(), () -> preview.validation().toString());
        ok(app.facade().planning().approve(id, preview.plan()));
        var out = new ByteArrayOutputStream(); var cli = cli(out);
        assertEquals(0, cli.execute("session open " + id));
        assertEquals(Milestone.SHOOT, workflow(id).next().orElseThrow().milestone(), "approved: the shoot is next");
        int requests = gemini.requests.size();

        // The requirement is re-confirmed for the next day (its own date is the shooting date): the constraints are the
        // same, so the approved plan is revised rather than generated again.
        ok(app.facade().planning().confirm(id, PlanFixtures.requirement(PlanFixtures.day().plusDays(1)).asDraft()));
        out.reset();
        assertEquals(0, cli.execute("session open " + id));
        var edited = lines(out);
        assertEquals(MilestoneStatus.NEEDS_REVIEW, workflow(id).status(Milestone.PLAN));
        assertEquals("workflow.detail.planShootChanged", workflow(id).state(Milestone.PLAN).detail().key());
        assertEquals("Next step: Photography Plan (plan revise " + id + " \"<change request>\")", edited.getLast());
        sameAsFacade(id, edited.get(edited.size() - 2), edited.getLast());
        assertEquals(1, ok(app.facade().planning().plans(id)).size(), "the approved revision is kept");
        assertEquals(requests, gemini.requests.size(), "showing the next step calls no model");
    }

    @Test void sessionProgressListsEveryMilestoneAndUnknownSessionsFail() {
        var id = planning();
        var out = new ByteArrayOutputStream(); var cli = cli(out);
        assertEquals(0, cli.execute("session progress " + id));
        var printed = lines(out);
        assertEquals(9, printed.size(), printed::toString);
        sameAsFacade(id, printed.getFirst(), printed.getLast());
        var expected = List.of("01 Shooting Requirements: Done.", "02 Gear Requirements: Done.", "03 Gear Selection: Not started.",
            "04 Environment Assessment: Not started.", "05 Photography Plan: Not started.", "06 Shooting Completion: Waiting for an earlier step.",
            "07 Post-Shoot Review: Waiting for an earlier step.");
        var w = workflow(id);
        for (var milestone : Milestone.values()) {
            String line = printed.get(milestone.number());
            assertEquals("  " + expected.get(milestone.ordinal()) + " " + w.state(milestone).detail().render(app.localization()), line);
            assertTrue(line.contains(": " + app.localization().text(w.status(milestone).labelKey()) + "."), "same status as the facade: " + line);
        }
        assertEquals("  04 Environment Assessment: Not started. Optional; not assessed yet.", printed.get(4));
        assertEquals("  06 Shooting Completion: Waiting for an earlier step. Needs an approved photography plan.", printed.get(6));

        // Past planned date: the shoot itself asks for action, and the optional environment step is left out.
        var shoot = planned();
        out.reset();
        assertEquals(0, cli.execute("session progress " + shoot.id()));
        var overdue = lines(out);
        assertTrue(overdue.get(4).startsWith("  04 Environment Assessment: Not needed."), overdue.get(4));
        assertTrue(overdue.get(6).startsWith("  06 Shooting Completion: Action needed."), overdue.get(6));
        assertEquals(MilestoneStatus.ACTION_REQUIRED, workflow(shoot.id()).status(Milestone.SHOOT));

        var unknown = UUID.randomUUID();
        out.reset();
        assertEquals(2, cli.execute("session progress " + unknown));
        assertEquals(List.of("Error: Session not found: " + unknown), lines(out));
        out.reset();
        assertEquals(2, cli.execute("session progress"));
        assertTrue(text(out).contains("Invalid session command"), text(out));
        assertFalse(text(out).contains("Workflow progress"));
    }

    @Test void envRecordWithoutAnAssessmentIsRefusedAndWritesNothing() throws Exception {
        var id = planning();
        byte[] before = Files.readAllBytes(store());
        var out = new ByteArrayOutputStream();
        assertEquals(2, cli(out).execute("env record " + id));
        assertEquals(List.of("Error: Assess the environment for this session first."), lines(out));
        assertArrayEquals(before, Files.readAllBytes(store()));
        assertEquals(MilestoneStatus.NOT_STARTED, workflow(id).status(Milestone.ENVIRONMENT));
        out.reset();
        assertEquals(2, cli(out).execute("env record " + UUID.randomUUID()));
        assertTrue(text(out).startsWith("Error: Session not found: "), text(out));
    }

    @Test void envRecordAddsTheReviewedAssessmentAndTheProgressStreamNamesTheSessionsNextMilestone() {
        var day = EnvironmentFixtures.day();
        var id = ok(app.facade().sessions().create("Toronto airshow", day)).id();
        ok(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()));
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream(); var cli = cli(out);
        cli.progress(new PrintStream(err, true, StandardCharsets.UTF_8));
        String args = " location=\"" + EnvironmentFixtures.PLACE + "\" latitude=43.62750 longitude=-79.39610 from=" + EnvironmentFixtures.clock(EnvironmentFixtures.from(day))
            + " until=" + EnvironmentFixtures.clock(EnvironmentFixtures.until(day)) + " lead=30";
        gemini.reply(EnvironmentFixtures.airshowInterpretation(day));
        assertEquals(0, cli.execute("env assess " + id + args), text(out));
        out.reset(); err.reset();
        assertEquals(0, cli.execute("env record " + id), text(out));
        assertEquals(List.of("The environment assessment was added to the session."), lines(out));
        var progress = lines(err);
        assertEquals(List.of("[F09] Checking that it applies to the session", "[F09] Saving to the session", "[F09] Saved"), progress.subList(0, 3), text(err));
        assertTrue(progress.get(3).matches("\\[F09] Adding the environment assessment: Done \\(\\d+\\.\\d s\\)"), progress.get(3));
        // The session's own next milestone (02: no gear requirements yet), not the operation-level "continue with the plan".
        assertEquals(List.of("[NEXT] Define and confirm the gear requirements."), progress.subList(4, progress.size()));
        assertEquals(MilestoneStatus.COMPLETED, workflow(id).status(Milestone.ENVIRONMENT));
        out.reset();
        assertEquals(0, cli.execute("session progress " + id));
        assertEquals("  04 Environment Assessment: Done. Recorded for " + EnvironmentFixtures.PLACE + " on " + day + ".", lines(out).get(4));
    }

    @Test void progressStreamCarriesTheMilestoneNextStepWhileStandardOutputIsUnchanged() {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream(); var quiet = new ByteArrayOutputStream();
        var cli = cli(out); var silent = cli(quiet);
        cli.progress(new PrintStream(err, true, StandardCharsets.UTF_8));
        var id = ok(app.facade().sessions().create("Night", AHEAD)).id();
        String set = "requirement set subject=\"Milky Way\" cameraCount=1 lensChanges=allowed duration=PT2H";

        assertEquals(0, cli.execute("requirement manual " + id));
        assertEquals(0, cli.execute(set));
        out.reset(); err.reset();
        assertEquals(0, cli.execute("requirement confirm"));
        String confirmed = text(out);
        var progress = lines(err);
        assertTrue(progress.contains("[F03] Saved"), text(err));
        assertEquals("[NEXT] Define and confirm the gear requirements.", progress.getLast(), "02 is the session's next milestone");
        assertFalse(text(err).contains("Continue to Gear Requirements."), "not the operation-level step");
        assertEquals(app.localization().text("progress.cli.next", app.localization().text(workflow(id).nextStep().orElseThrow().message().key())), progress.getLast());

        // The same confirmation without a progress stream prints exactly the same standard output.
        assertEquals(0, silent.execute("requirement manual " + id));
        assertEquals(0, silent.execute(set));
        quiet.reset();
        assertEquals(0, silent.execute("requirement confirm"));
        assertEquals(text(quiet), confirmed, "standard output is byte-for-byte what a CLI without progress prints");
        assertFalse(confirmed.contains("[F03]") || confirmed.contains("[NEXT]") || confirmed.contains("Workflow progress"));

        out.reset(); quiet.reset(); err.reset();
        assertEquals(0, cli.execute("session progress " + id));
        assertEquals(0, silent.execute("session progress " + id));
        assertEquals(text(quiet), text(out));
        assertEquals("", text(err), "reading progress is no operation");
    }

    @Test void chineseOutputUsesTheChineseWorkflowTexts() {
        assertTrue(app.localization().selectLanguage("zh-CN"));
        var id = ok(app.facade().sessions().create("夜景", AHEAD)).id();
        var out = new ByteArrayOutputStream(); var cli = cli(out);
        assertEquals(0, cli.execute("session progress " + id));
        assertEquals(List.of("工作流进度：0%（0/7 个里程碑）· 刚开始",
            "  01 拍摄需求：未开始。尚未描述。",
            "  02 器材需求：等待前序步骤。请先确认拍摄需求。",
            "  03 器材选择：等待前序步骤。请先确认器材需求。",
            "  04 环境评估：未开始。可选；尚未评估。",
            "  05 拍摄计划：等待前序步骤。请先确认拍摄需求。",
            "  06 拍摄完成：等待前序步骤。需要已批准的拍摄计划。",
            "  07 拍后复盘：等待前序步骤。记录拍摄完成后可用。",
            "下一步：拍摄需求（requirement parse " + id + " <需求文本>）"), lines(out));
        out.reset();
        assertEquals(0, cli.execute("session open " + id));
        assertEquals(List.of("工作流进度：0%（0/7 个里程碑）· 刚开始", "下一步：拍摄需求（requirement parse " + id + " <需求文本>）"),
            lines(out).subList(3, 5));
        out.reset();
        assertEquals(2, cli.execute("env record " + id));
        assertEquals(List.of("错误：请先为此拍摄活动评估环境。"), lines(out));
        out.reset();
        assertEquals(0, cli.execute("session help"));
        assertTrue(text(out).contains("session progress <id>（工作流里程碑与下一步）"), text(out));
        assertFalse(text(out).contains("Workflow"));
    }

    @Test void helpNamesTheNewCommands() {
        var out = new ByteArrayOutputStream(); var cli = cli(out);
        assertEquals(0, cli.execute("help"));
        assertTrue(text(out).contains("session progress <id>"), text(out));
        out.reset();
        assertEquals(0, cli.execute("session help"));
        assertTrue(text(out).contains("session progress <id> (workflow milestones and the next step)"), text(out));
        out.reset();
        assertEquals(0, cli.execute("env help"));
        assertTrue(text(out).contains("env record <session-id>"), text(out));
        out.reset();
        assertEquals(0, cli.execute("F09"));
        assertTrue(text(out).contains("env record <session-id>"), "F09 shows the env help");
    }
}
