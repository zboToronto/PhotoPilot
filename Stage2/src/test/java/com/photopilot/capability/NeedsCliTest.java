package com.photopilot.capability;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.EquipmentRequirementProfile;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** CLI F06 workflow over the same services and storage as the GUI; restart, replacement, cancel and failures. */
class NeedsCliTest {
    @TempDir Path root;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    static PhotoPilotCLI cli(ApplicationBootstrap app, ByteArrayOutputStream bytes) {
        return new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
    }

    @Test void cliAnalyzesEditsClarifiesConfirmsAndRestoresTheProfile() throws Exception {
        UUID id;
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            id = confirmedSession(app, gemini);
            var bytes = new ByteArrayOutputStream(); var cli = cli(app, bytes);
            assertEquals(0, cli.execute("F06")); assertTrue(text(bytes).contains("needs analyze <session-id>") && text(bytes).contains("No equipment yet"));
            assertEquals(2, cli.execute("needs draft")); assertTrue(text(bytes).contains("No capability profile draft is open"));
            bytes.reset();
            assertEquals(0, cli.execute("needs show " + id));
            assertTrue(text(bytes).contains("Confirmed shooting requirements (F03)") && text(bytes).contains("Fast-moving aircraft"), text(bytes));
            assertTrue(text(bytes).contains("no confirmed capability profile"));

            gemini.status(503, "");
            bytes.reset();
            assertEquals(2, cli.execute("needs analyze " + id));
            assertTrue(text(bytes).contains("AI capability analysis failed") && text(bytes).contains("needs manual " + id), text(bytes));

            gemini.reply(PROPOSAL);
            bytes.reset();
            assertEquals(0, cli.execute("needs analyze " + id));
            String shown = text(bytes);
            assertTrue(shown.contains("unconfirmed and not saved") && shown.contains("proposed by Gemini"), shown);
            assertTrue(shown.contains("Focal length: 300-500 mm | priority 1 | mandatory"), shown);
            assertTrue(shown.contains("Priorities and trade-offs:") && shown.contains("Telephoto reach competes"), shown);
            assertTrue(shown.contains("not checked automatically") && shown.contains("Weather sealing"), shown);
            assertTrue(shown.contains("How far from the flight line"), shown);
            assertTrue(shown.contains("The needs are valid"), shown);
            assertEquals(2, cli.execute("needs analyze " + id), "one open draft at a time");

            assertEquals(2, cli.execute("needs set focalLength minimum=600 maximum=100"));
            assertEquals(2, cli.execute("needs set warpDrive minimum=1"));
            assertEquals(0, cli.execute("needs set totalWeight maximum=5000 priority=2 mandatory=yes note=\"Keep it light\""));
            assertEquals(0, cli.execute("needs remove storageCapacity"));
            assertTrue(app.facade().planning().profile(id).hasError("needs.error.none"), "editing never saves");

            gemini.reply(reply("[" + need("focalLength", 400, 600, 1, true, "Distant passes.") + ","
                + need("totalWeight", null, 5000, 2, true, "Keep it light") + "]"));
            bytes.reset();
            assertEquals(0, cli.execute("needs clarify About 400 metres from the flight line"));
            assertTrue(text(bytes).contains("Focal length: 400-600 mm"), text(bytes));
            assertTrue(text(bytes).contains("Camera bodies set to exactly 2"), "constraint preservation is shown");
            assertTrue(gemini.requests.getLast().contains("Keep it light"), "the edited draft is resubmitted");

            bytes.reset();
            assertEquals(0, cli.execute("needs confirm"));
            assertTrue(text(bytes).contains("Capability profile confirmed and saved to session Sunset aircraft"), text(bytes));
            var saved = app.facade().planning().profile(id).value().orElseThrow();
            assertTrue(saved.reviewed()); assertEquals(EquipmentRequirementProfile.AI_ASSISTED, saved.origin());
            assertEquals(Optional.of(5000.0), saved.need("totalWeight").orElseThrow().maximum());
        }
        // A separate bootstrap (restart) shows the saved profile; re-analysis needs an explicit replace; cancel writes nothing.
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var bytes = new ByteArrayOutputStream(); var cli = cli(app, bytes);
            assertEquals(0, cli.execute("needs show " + id));
            assertTrue(text(bytes).contains("Confirmed capability profile:") && text(bytes).contains("Focal length: 400-600 mm"), text(bytes));
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = Files.readAllBytes(store);
            gemini.reply(PROPOSAL);
            assertEquals(0, cli.execute("needs analyze " + id));
            assertTrue(text(bytes).contains("needs confirm replace"));
            assertEquals(2, cli.execute("needs confirm"), "replacing needs the explicit word");
            assertEquals(0, cli.execute("needs cancel"));
            assertArrayEquals(before, Files.readAllBytes(store), "cancel and the refused confirmation wrote nothing");
            gemini.reply(PROPOSAL);
            assertEquals(0, cli.execute("needs analyze " + id));
            assertEquals(0, cli.execute("needs confirm replace"));
            assertEquals(10, app.facade().planning().profile(id).value().orElseThrow().needs().size());
            // Editing the saved profile is itself the explicit replacement (F07 alias shares the same draft and path).
            assertEquals(0, cli.execute("match profile " + id));
            assertEquals(0, cli.execute("match need remove batteryCount"));
            assertEquals(0, cli.execute("needs confirm"));
            assertEquals(9, app.facade().planning().profile(id).value().orElseThrow().needs().size());
            bytes.reset();
            app.localization().useLanguage("zh-CN");
            assertEquals(0, cli.execute("needs show " + id));
            assertTrue(text(bytes).contains("已确认的拍摄需求（F03）") && text(bytes).contains("来源"), text(bytes));
        }
    }

    @Test void guiAndCliShareTheServiceResult() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var id = confirmedSession(app, gemini);
            gemini.reply(PROPOSAL);
            var direct = app.facade().proposeNeeds(id).value().orElseThrow();
            var bytes = new ByteArrayOutputStream(); var cli = cli(app, bytes);
            gemini.reply(PROPOSAL);
            assertEquals(0, cli.execute("needs analyze " + id));
            for (String line : com.photopilot.application.CapabilityNeedForm.describe(direct.profile(), app.localization()))
                assertTrue(text(bytes).contains(line), line);
        }
    }
}
