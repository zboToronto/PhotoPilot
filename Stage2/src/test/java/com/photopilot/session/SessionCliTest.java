package com.photopilot.session;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.state.PlannedState;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SessionCliTest {
    @TempDir Path root;
    Map<String, String> environment() { return Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }
    @Test void sharedCommandsCoverCreateEditSearchRestoreTransitionsAndConfirmedDelete() {
        try (var app = new ApplicationBootstrap(environment())) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(0, cli.execute("session create title=\"Evening shoot\" date=2026-11-01 location=\"River park\" notes=\"Long exposure\""));
            var session = app.facade().sessions().search("").value().orElseThrow().getFirst(); String id = session.id().toString();
            assertEquals(0, cli.execute("session list status=DRAFT date=2026-11-01 query=River"));
            assertEquals(0, cli.execute("session edit " + id + " title=\"Edited shoot\" notes=\"Night portrait\" location="));
            assertEquals(0, cli.execute("session restore " + id));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Night portrait"));
            assertEquals(2, cli.execute("session transition " + id + " event=SHOOT_COMPLETED"));
            assertEquals(2, cli.execute("session transition " + id + " event=PLAN_APPROVED"));
            var fixture = new JsonSessionRepository(root.resolve("sessions")).insert(SessionFixtures.linked(new PlannedState(), List.of(), false)).value().orElseThrow();
            assertEquals(0, cli.execute("session transition " + fixture.id() + " event=SHOOT_COMPLETED"));
            assertEquals("COMPLETED", app.facade().sessions().open(fixture.id()).value().orElseThrow().status());
            assertEquals(2, cli.execute("session delete " + id));
            assertEquals(0, cli.execute("session delete " + id + " --confirm"));
            assertEquals(2, cli.execute("session open " + id));
        }
    }
    @Test void malformedCommandsAreLocalizedAndNeverCreateData() {
        try (var app = new ApplicationBootstrap(environment())) {
            app.localization().useLanguage("zh-CN"); var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            for (String command : List.of("session create", "session create title=T date=2026-02-30", "session open 1-1-1-1-1", "session edit",
                    "session list status=ACTIVE", "session create title=T title=U date=2026-11-01", "session create title=T date=2026-11-01 extra=value",
                    "session delete x --confirm=false", "session create title=\"unclosed")) assertEquals(2, cli.execute(command), command);
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("日期")); assertFalse(Files.exists(root.resolve("sessions/sessions.json")));
        }
    }
    @Test void separateJvmProcessesRestoreUtf8SessionAfterActualRestart() throws Exception {
        String created = process("session create title=\"夜景 shoot\" date=2026-11-01 location=\"湖边\" notes=\"保持原文\"\nexit\n");
        assertTrue(created.contains("Session saved."), created);
        var repository = new JsonSessionRepository(root.resolve("sessions")); var session = repository.search("").value().orElseThrow().getFirst();
        String restored = process("session restore " + session.id() + "\nexit\n");
        assertTrue(restored.contains("夜景 shoot"), restored); assertTrue(restored.contains("保持原文"), restored);
        String edited = process("session edit " + session.id() + " notes=\"Edited across processes\"\nexit\n");
        assertTrue(edited.contains("Session saved."), edited);
        assertEquals("Edited across processes", repository.find(session.id()).value().orElseThrow().notes());
    }
    private String process(String input) throws Exception {
        Path output = Files.createTempFile(root, "cli-", ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dfile.encoding=UTF-8", "-cp", System.getProperty("java.class.path"), "com.photopilot.ui.cli.PhotoPilotCLI", "--lang=en");
        builder.environment().putAll(environment()); builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            try (var writer = process.outputWriter(StandardCharsets.UTF_8)) { writer.write(input); }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "CLI process timed out"); assertEquals(0, process.exitValue());
            return Files.readString(output);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
