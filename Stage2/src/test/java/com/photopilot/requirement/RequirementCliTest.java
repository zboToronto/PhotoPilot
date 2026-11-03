package com.photopilot.requirement;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.requirement.FakeGemini.*;
import static org.junit.jupiter.api.Assertions.*;

/** CLI F03 workflow over the same services and storage as the GUI; Gemini is a loopback fake. */
class RequirementCliTest {
    @TempDir Path root;

    @Test void cliParsesReviewsEditsClarifiesAndConfirmsOnlyOnRequest() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            var id = app.facade().sessions().create("Airshow", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();
            assertEquals(0, cli.execute("F03")); assertTrue(text(bytes).contains("requirement parse <session-id>"));
            assertEquals(2, cli.execute("requirement confirm")); assertTrue(text(bytes).contains("No draft is open"));
            gemini.reply(AIRSHOW);
            assertEquals(0, cli.execute("requirement parse " + id + " " + AIRSHOW_REQUEST));
            String parsed = text(bytes);
            assertTrue(parsed.contains("Camera bodies: 2"), parsed); assertTrue(parsed.contains("Lens changes: Avoid lens changes"), parsed);
            assertTrue(parsed.contains("Location: Not specified"), parsed); assertTrue(parsed.contains("Which date is the airshow?"), parsed);
            assertTrue(parsed.contains("unconfirmed, not saved"), parsed);
            assertTrue(app.facade().sessions().open(id).value().orElseThrow().requirements().isEmpty(), "parse never saves");
            assertEquals(2, cli.execute("requirement parse " + id + " another"), "one open draft at a time");
            assertEquals(2, cli.execute("requirement set cameraCount=0")); assertTrue(text(bytes).contains("whole number from 1 to 10"));
            assertEquals(0, cli.execute("requirement set location=\"Lakeshore airfield\" date=2026-11-14 time=18:45"));
            gemini.reply(AIRSHOW.replace("\"location\":null", "\"location\":\"Lakeshore airfield\"").replace("\"date\":null", "\"date\":\"2026-11-14\"")
                .replace("\"startTime\":null", "\"startTime\":\"18:45\"").replace("\"Which date is the airshow?\",\"Where is the airshow taking place?\"", ""));
            assertEquals(0, cli.execute("requirement clarify It's on November 14 at the lakeshore airfield"));
            assertTrue(gemini.requests.get(1).contains("It's on November 14"), "free text keeps apostrophes verbatim");
            assertEquals(0, cli.execute("requirement confirm"));
            var saved = app.facade().planning().requirement(id).value().orElseThrow();
            assertEquals("Lakeshore airfield", saved.location().orElseThrow().label()); assertEquals(Optional.of(2), saved.cameraCount());
            assertEquals(0, cli.execute("requirement show " + id)); assertTrue(text(bytes).contains("Confirmed requirements for session " + id));
            assertEquals(2, cli.execute("requirement draft"), "confirmation closes the draft");
        }
    }

    @Test void cliCancelAndAiFailureKeepStoredDataAndOfferManualEntry() throws Exception {
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            var id = app.facade().sessions().create("Night", LocalDate.of(2026, 11, 20)).value().orElseThrow().id();
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json"); byte[] before = Files.readAllBytes(store);
            gemini.reply(AIRSHOW);
            assertEquals(0, cli.execute("requirement parse " + id + " \"" + AIRSHOW_REQUEST + "\""));
            assertEquals(0, cli.execute("requirement cancel")); assertArrayEquals(before, Files.readAllBytes(store));
            gemini.status(503, "");
            assertEquals(2, cli.execute("requirement parse " + id + " Milky Way over the lake, one camera"));
            String failed = text(bytes); assertTrue(failed.contains("AI parsing failed"), failed); assertTrue(failed.contains("requirement manual " + id), failed);
            assertEquals(0, cli.execute("requirement manual " + id));
            assertTrue(text(bytes).contains("Original request: Milky Way over the lake, one camera"), "manual entry starts from the kept request");
            assertEquals(2, cli.execute("requirement confirm")); assertTrue(text(bytes).contains("Subject or genre is required"));
            assertEquals(0, cli.execute("requirement set subject=\"Milky Way\" cameraCount=1 lensChanges=allowed duration=PT2H"));
            assertEquals(0, cli.execute("requirement confirm"));
            assertEquals("Milky Way", app.facade().planning().requirement(id).value().orElseThrow().subject());
            assertEquals(2, cli.execute("requirement parse not-a-uuid text"));
            assertEquals(2, cli.execute("requirement unknown"));
            assertEquals(0, cli.execute("status")); assertTrue(text(bytes).contains("F03 Natural-Language Photography Requirement Parsing - IMPLEMENTED"));
        }
    }
    private static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
}
