package com.photopilot.capability;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Generating or refreshing the capability report, in either interface, writes nothing and calls no model: inventory,
 * mount labels, sessions (requirements, profiles, selections, plans) and derived milestones stay byte-for-byte the same.
 */
class CapabilityReadOnlyTest {
    @TempDir Path directory;

    static Map<String, String> files(Path root) throws IOException {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return result;
    }

    @Test void analysisAndEveryCliSectionLeaveStoredDataAndAiUntouched() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(directory))) {
            for (var item : CapabilityDemoInventory.items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            var session = app.facade().sessions().create("Sunset aircraft", LocalDate.of(2026, 11, 30)).value().orElseThrow();
            var before = files(directory);
            var progress = app.facade().workflow().of(session.id()).value().orElseThrow();
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            for (int i = 0; i < 3; i++) assertTrue(app.facade().capabilities().analyze().isSuccess());
            for (String command : List.of("capability", "capability overview", "capability cameras", "capability lenses", "capability coverage", "capability coverage 2",
                    "capability aperture", "capability matrix", "capability pair 1 2", "capability quality", "capability all", "F06"))
                assertEquals(0, cli.execute(command), command);
            assertEquals(before, files(directory), "no stored file changed, appeared or disappeared");
            var after = app.facade().workflow().of(session.id()).value().orElseThrow();
            assertEquals(progress.phase(), after.phase());
            assertEquals(progress.milestones().toString(), after.milestones().toString(), "milestones unchanged");
            assertTrue(gemini.requests.isEmpty(), "the report never calls the AI provider");
            var stored = app.facade().inventory().list("").value().orElseThrow().find(CapabilityDemoInventory.A7V).orElseThrow();
            assertEquals("Sony E", ((com.photopilot.model.Camera) stored).mount(), "mount labels are never rewritten");
        }
    }
}
