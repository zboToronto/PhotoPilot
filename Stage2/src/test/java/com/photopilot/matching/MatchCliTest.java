package com.photopilot.matching;

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
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** CLI F07 workflow over the same services and storage as the GUI; results agree with the shared service. */
class MatchCliTest {
    @TempDir Path root;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }

    @Test void cliEntersProfileMatchesSelectsConfirmsAndShowsTheSavedLoadout() throws Exception {
        var gear = new Airshow();
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            for (var item : gear.items()) assertTrue(app.facade().inventory().add(item, true).isSuccess());
            var id = app.facade().sessions().create("Lakeshore airshow", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(0, cli.execute("F07")); assertTrue(text(bytes).contains("match run <session-id>"));
            assertEquals(2, cli.execute("match profile " + id)); assertTrue(text(bytes).contains("Confirm the shooting requirements (F03)"));
            assertTrue(app.facade().planning().confirm(id, airshowRequirement().asDraft()).isSuccess());
            assertEquals(0, cli.execute("match profile " + id)); assertTrue(text(bytes).contains("unconfirmed and not saved"));
            assertTrue(text(bytes).contains("not AI-generated"));
            assertEquals(2, cli.execute("match need warpDrive minimum=1")); assertTrue(text(bytes).contains("Unsupported capability"));
            assertEquals(2, cli.execute("match need focalLength minimum=600 maximum=100")); assertTrue(text(bytes).contains("must not exceed"));
            assertEquals(0, cli.execute("match need focalLength minimum=100 maximum=600 priority=1 mandatory=yes note=\"strong telephoto\""));
            assertEquals(0, cli.execute("match need cameraCount minimum=2 maximum=2 priority=2"));
            assertEquals(0, cli.execute("match need burstRate minimum=10 priority=2"));
            assertEquals(0, cli.execute("match need lensCount maximum=2 priority=3"));
            assertEquals(0, cli.execute("match need totalWeight maximum=6000 priority=4 mandatory=no"));
            assertEquals(0, cli.execute("match need autofocus priority=2"));
            assertEquals(2, cli.execute("match run " + id), "no confirmed profile yet");
            assertEquals(0, cli.execute("match profile confirm")); assertTrue(text(bytes).contains("Capability profile confirmed and saved"));
            assertTrue(app.facade().planning().profile(id).value().orElseThrow().reviewed());

            gemini.status(503, "");
            bytes.reset();
            assertEquals(0, cli.execute("match run " + id + " portability"));
            String run = text(bytes);
            assertTrue(run.contains("AI ranking unavailable; showing the deterministic ranking"), run);
            assertTrue(run.contains("#1 Full match"), run);
            assertTrue(run.contains("Compatibility: verified by deterministic rules (UC14)."), run);
            assertTrue(run.contains("Excluded (incompatible)") && run.contains("Canon EF"), run);
            assertTrue(run.contains("Excluded (unverified)"), run);
            assertFalse(run.contains("AI comparison"), "no fabricated AI explanation on fallback");
            // GUI and CLI share the service: the same first candidate comes back from a direct call.
            gemini.status(503, "");
            var direct = app.facade().planning().matchGear(id, "portability").value().orElseThrow();
            assertTrue(run.contains(direct.recommendations().getFirst().loadout().gear().getFirst().model()));

            assertEquals(2, cli.execute("match select 99"));
            assertEquals(0, cli.execute("match select 1")); assertTrue(text(bytes).contains("Save this loadout to session Lakeshore airshow?"));
            assertEquals(0, cli.execute("match cancel"));
            assertTrue(app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().isEmpty(), "cancel never saves");
            assertEquals(2, cli.execute("match confirm"), "nothing pending after cancel");
            assertEquals(0, cli.execute("match select 1"));
            assertEquals(0, cli.execute("match confirm")); assertTrue(text(bytes).contains("Loadout selected and saved to session Lakeshore airshow."));
            var saved = app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().orElseThrow();
            assertEquals(direct.recommendations().getFirst().loadout().id(), saved.id());

            bytes.reset();
            assertEquals(0, cli.execute("match show " + id));
            String shown = text(bytes);
            assertTrue(shown.contains("Selected loadout (full match):"), shown);
            assertTrue(shown.contains("Rechecked now: still owned, unchanged and compatible."), shown);
            assertTrue(shown.contains("Confirmed capability profile:"), shown);

            gemini.status(503, "");
            assertEquals(0, cli.execute("match run " + id));
            assertEquals(0, cli.execute("match select 2")); assertTrue(text(bytes).contains("match confirm replace"));
            assertEquals(2, cli.execute("match confirm"), "replacement requires the explicit replace word");
            assertEquals(saved.id(), app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().orElseThrow().id());
            assertEquals(0, cli.execute("match confirm replace"));
            assertNotEquals(saved.id(), app.facade().sessions().open(id).value().orElseThrow().selectedLoadout().orElseThrow().id());
        }
    }

    @Test void chineseCliUsesTheSameFindings() throws Exception {
        var gear = new Airshow();
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root))) {
            for (var item : gear.items()) app.facade().inventory().add(item, true);
            var id = app.facade().sessions().create("Airshow", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();
            app.facade().planning().confirm(id, airshowRequirement().asDraft());
            app.facade().planning().confirmProfile(id, airshowProfile().asDraft());
            app.localization().useLanguage("zh-CN");
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            gemini.status(503, "");
            assertEquals(0, cli.execute("match run " + id + " coverage"));
            String run = text(bytes);
            assertTrue(run.contains("完全匹配") && run.contains("已排除（不兼容）") && run.contains("确定性排序"), run);
        }
    }
}
