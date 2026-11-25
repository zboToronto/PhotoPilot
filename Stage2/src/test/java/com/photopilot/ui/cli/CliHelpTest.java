package com.photopilot.ui.cli;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 PP-02: the short help said "other F01-F11: availability", while F01 lists sessions, F06 prints the needs
 * commands and the inventory capability summary, and every other alias prints its feature's commands. The aliases are
 * now expanded through CliCommandCatalog and the help prints the same catalog, so the two cannot drift apart again.
 */
class CliHelpTest {
    @TempDir Path root;

    Map<String, String> environment() {
        return Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
    }
    record Run(int code, String output) { }
    static Run run(ApplicationBootstrap app, String command) {
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        int code = cli.execute(command);
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test void shortHelpDescribesWhatEveryAliasRunsAndNoLongerClaimsAvailability() {
        try (var app = new ApplicationBootstrap(environment())) {
            var help = run(app, "help");
            assertEquals(0, help.code());
            // The stale line (F09's "availability" of a shooting time is a different, correct use of the word).
            assertFalse(help.output().contains("F01-F11: availability"), help.output());
            assertFalse(help.output().contains("F05: inventory | other"), help.output());
            assertTrue(help.output().contains("Feature aliases run real commands of their feature:"), help.output());
            for (var alias : CliCommandCatalog.ALIASES) {
                String line = alias.feature() + " runs " + String.join(" + ", alias.commands()) + " (" + app.localization().text("catalog." + alias.feature()) + ")";
                assertTrue(help.output().contains(line), "missing: " + line + "\n" + help.output());
            }
            assertTrue(help.output().contains("F01 runs session list (Photography Session and History Management)"));
            assertTrue(help.output().contains("F06 runs needs help + capability"));
            // One line per command group, invocation options and exit codes.
            for (String part : List.of("session create title=", "F02 / discover", "requirement parse", "F04 / import", "F06 / needs", "capability [summary",
                    "F07 / match", "F09 / env", "F10 / plan", "F11 / review", "shutter help", "lang [en|zh-CN]", "ai-test:", "gear help: inventory commands",
                    "calc help: calculators", "--profile <UUID>", "Exit status: 0 when every command succeeded, 2 when"))
                assertTrue(help.output().contains(part), "missing " + part);
            assertEquals(11, CliCommandCatalog.ALIASES.size());
            assertEquals(List.of("F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11"), CliCommandCatalog.ALIASES.stream().map(a -> a.feature()).toList());
        }
    }

    @Test void chineseHelpListsTheSameAliases() {
        try (var app = new ApplicationBootstrap(environment())) {
            app.localization().useLanguage("zh-CN");
            var help = run(app, "help");
            assertEquals(0, help.code());
            assertFalse(help.output().contains("F01-F11：可用性"), help.output());
            assertTrue(help.output().contains("功能别名会运行该功能的真实命令："), help.output());
            assertTrue(help.output().contains("F01 运行 session list（" + app.localization().text("catalog.F01") + "）"), help.output());
            assertTrue(help.output().contains("F06 运行 needs help + capability"), help.output());
            assertTrue(help.output().contains("退出状态"), help.output());
        }
    }

    @Test void everyAliasRunsExactlyTheCommandsTheHelpNames() {
        try (var app = new ApplicationBootstrap(environment())) {
            assertEquals(0, run(app, "session create title=\"Alias check\" date=2026-12-12").code());
            for (var alias : CliCommandCatalog.ALIASES) for (String spelling : List.of(alias.feature(), alias.feature().toLowerCase(Locale.ROOT), " " + alias.feature() + " ")) {
                var direct = run(app, spelling);
                var expected = new StringBuilder(); int code = 0;
                for (String command : alias.commands()) { var r = run(app, command); expected.append(r.output()); code = Math.max(code, r.code()); }
                assertEquals(code, direct.code(), spelling);
                assertEquals(expected.toString(), direct.output(), spelling + " runs " + alias.commands());
                assertFalse(direct.output().contains("Unknown command") || direct.output().contains("not available yet"), spelling + ": " + direct.output());
            }
            assertTrue(run(app, "F01").output().contains("Alias check"), "F01 lists the real sessions");
        }
    }

    @Test void everyDocumentedCommandGroupIsRoutedAndUnknownOrMalformedInputFailsWithExitCode2() {
        try (var app = new ApplicationBootstrap(environment())) {
            for (String command : List.of("session help", "discover help", "requirement help", "import help", "gear help", "needs help", "capability help",
                    "match help", "calc help", "env help", "plan help", "review help", "shutter help", "status", "lang")) {
                var r = run(app, command);
                assertEquals(0, r.code(), command + ": " + r.output());
                assertFalse(r.output().contains("Unknown command"), command);
            }
            for (String unknown : List.of("F12", "F00", "F01 extra", "sessions", "helpme", "exit-now")) {
                var r = run(app, unknown);
                assertEquals(2, r.code(), unknown); assertEquals("Unknown command. Type help.", r.output().strip(), unknown);
            }
            // Missing or malformed arguments: a localized refusal and exit code 2, never an internal error.
            for (String malformed : List.of("session open", "session open not-a-uuid", "session list status", "session create title=", "calc nd shutter=1/125",
                    "review open", "plan show", "lang xx", "gear add", "session transition " + UUID.randomUUID() + " event=NOPE")) {
                var r = run(app, malformed);
                assertEquals(2, r.code(), malformed + ": " + r.output());
                assertFalse(r.output().contains("unexpected internal error"), malformed + ": " + r.output());
                assertFalse(r.output().isBlank(), malformed);
            }
        }
    }

    // ---- a real CLI process: interactive input, batch arguments, EOF and exit codes ---------------------------------

    private final Map<Process, Path> outputs = new HashMap<>();
    Process child(Path cwd, String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-Duser.home=" + root.resolve("home"), "-cp", System.getProperty("java.class.path"), "com.photopilot.ui.cli.PhotoPilotCLI"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.environment().remove("PHOTOPILOT_DATA_DIR"); builder.environment().remove("PHOTOPILOT_CONFIG_DIR");
        builder.environment().put("PHOTOPILOT_PROGRESS", "0");
        Path output = Files.createTempFile(cwd, "out-", ".log");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start(); outputs.put(process, output); return process;
    }
    String output(Process process, Path cwd) throws Exception { return Files.readString(outputs.get(process), StandardCharsets.UTF_8); }

    @Test void aRealProcessHandlesRedirectedInputBatchArgumentsEofAndExitCodes() throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        // Redirected input ending without "exit": every line runs, the unknown one fails, and EOF ends the process with 2.
        Process piped = child(work, "--lang=en");
        try (var in = piped.getOutputStream()) { in.write("help\nF01\nnot-a-command\nstatus\n".getBytes(StandardCharsets.UTF_8)); }
        assertTrue(piped.waitFor(30, TimeUnit.SECONDS)); String text = output(piped, work);
        assertEquals(2, piped.exitValue(), text);
        assertTrue(text.contains("F01 runs session list"), text);
        assertTrue(text.contains("0 session(s)") || text.contains("Sessions: 0"), text);
        assertTrue(text.contains("Unknown command. Type help."), text);
        assertTrue(text.contains("F11 AI Post-Shoot Analysis and Review - IMPLEMENTED"), "input after a failure still runs: " + text);

        // Batch arguments that all succeed exit 0; "exit" closes explicitly.
        Process batch = child(work, "--lang=en", "F05", "status", "exit");
        assertTrue(batch.waitFor(30, TimeUnit.SECONDS)); String ok = output(batch, work);
        assertEquals(0, batch.exitValue(), ok);
        assertTrue(ok.contains("Inventory: 0 item(s)"), ok); assertTrue(ok.contains("PhotoPilot closed."), ok);

        // Normal end of input after successful commands exits 0 without "exit".
        Process eof = child(work, "--lang=zh-CN");
        try (var in = eof.getOutputStream()) { in.write("status\n".getBytes(StandardCharsets.UTF_8)); }
        assertTrue(eof.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, eof.exitValue(), output(eof, work));

        // An invalid invocation option is reported with exit code 2.
        Process invalid = child(work, "--lang=xx", "status");
        assertTrue(invalid.waitFor(30, TimeUnit.SECONDS));
        assertEquals(2, invalid.exitValue());
        assertTrue(output(invalid, work).contains("Unsupported or missing language"));
    }
}
