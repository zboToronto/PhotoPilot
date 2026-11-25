package com.photopilot.ui.cli;

import com.photopilot.application.NextSteps;
import com.photopilot.application.OperationKind;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.requirement.FakeGemini.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI execution feedback: the shared operation events are printed to the progress stream (standard error in the real
 * CLI) while standard output and exit codes stay as they were; the [NEXT] line is the same deterministic step the GUI
 * shows; retries and persistence outcomes are visible.
 */
class CliProgressTest {
    @TempDir Path root;
    private static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }

    @Test void progressGoesToItsOwnStreamAndStandardOutputIsUnchanged() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
            var quiet = new ByteArrayOutputStream();
            var silent = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(quiet, true, StandardCharsets.UTF_8), app.localization());
            cli.progress(new PrintStream(err, true, StandardCharsets.UTF_8));
            var id = app.facade().sessions().create("Airshow", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();

            gemini.reply(AIRSHOW);
            assertEquals(0, cli.execute("requirement parse " + id + " " + AIRSHOW_REQUEST));
            gemini.reply(AIRSHOW);
            assertEquals(0, silent.execute("requirement parse " + id + " " + AIRSHOW_REQUEST));
            assertEquals(text(quiet), text(out), "standard output is byte-for-byte what a CLI without progress prints");
            assertFalse(text(out).contains("[F03]") || text(out).contains("[NEXT]"));

            String progress = text(err);
            var lines = progress.lines().toList();
            assertEquals(List.of("[F03] Loading session", "[F03] Waiting for Gemini", "[F03] Checking response format", "[F03] Preparing draft"),
                lines.subList(0, 4), progress);
            assertTrue(lines.get(4).matches("\\[F03] Interpreting request: Done \\(\\d+\\.\\d s\\)"), lines.get(4));
            String next = app.localization().text("progress.cli.next", app.localization().text(
                NextSteps.after(OperationKind.REQUIREMENT_PARSE, Result.success(new Object())).orElseThrow().message().key()));
            assertEquals(next, lines.get(5), "the same next step the GUI offers");
            assertEquals("[NEXT] Review the draft, then confirm it.", lines.get(5));

            assertEquals(0, cli.execute("requirement cancel"));
            assertEquals(0, cli.execute("requirement manual " + id));
            assertEquals(0, cli.execute("requirement set subject=\"Milky Way\" cameraCount=1 lensChanges=allowed duration=PT2H"));
            err.reset();
            assertEquals(0, cli.execute("requirement confirm"));
            assertTrue(text(err).contains("[F03] Saved"), text(err));
            // After a saved milestone the CLI names the session's own next milestone, as the GUI sidebar does.
            assertTrue(text(err).contains("[NEXT] Define and confirm the gear requirements."), text(err));
            assertEquals(Optional.of(com.photopilot.workflow.Milestone.EQUIPMENT_NEEDS),
                app.facade().workflow().of(id).value().orElseThrow().next().map(com.photopilot.workflow.MilestoneState::milestone));
        }
    }

    @Test void failuresAndRetriesAreReportedWithoutChangingTheExitCode() throws Exception {
        try (var gemini = new FakeGemini()) {
            var environment = new HashMap<>(gemini.environment(root)); environment.put("GEMINI_RETRIES", "1");
            try (var app = new ApplicationBootstrap(environment)) {
                var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
                var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
                cli.progress(new PrintStream(err, true, StandardCharsets.UTF_8));
                var id = app.facade().sessions().create("Night", LocalDate.of(2026, 11, 20)).value().orElseThrow().id();
                gemini.status(503, "").status(503, "");
                assertEquals(2, cli.execute("requirement parse " + id + " Milky Way over the lake"), "the exit code is unchanged");
                String progress = text(err);
                assertTrue(progress.contains("[F03] HTTP 503. Retry 1 of 1 in 1 s"), progress);
                assertTrue(progress.lines().anyMatch(line -> line.matches("\\[F03] Interpreting request: Failed \\(\\d+\\.\\d s\\)")), progress);
                assertTrue(progress.contains("[NEXT] Retry, or enter the requirement manually."), progress);
                assertTrue(text(out).contains("AI parsing failed"), "the existing failure text stays on standard output");
                app.localization().useLanguage("zh-CN"); err.reset();
                gemini.status(503, "").status(503, "");
                assertEquals(2, cli.execute("requirement parse " + id + " 夜景"));
                assertTrue(text(err).contains("[F03] 等待 Gemini 响应"), text(err));
                assertTrue(text(err).contains("[下一步] 请重试，或手动填写需求。"), text(err));
            }
        }
    }
}
