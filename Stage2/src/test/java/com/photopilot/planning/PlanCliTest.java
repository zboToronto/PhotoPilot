package com.photopilot.planning;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** CLI F10 workflow over the same Facade, PlanningService and storage as the GUI. */
class PlanCliTest {
    @TempDir Path root;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    static Object field(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }

    @Test void cliGeneratesApprovesListsRevisesComparesAndReopensPlans() throws Exception {
        var gear = new Gear();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = session(app, gear, true);
            assess(app, gemini, id);
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertSame(app.facade().planning(), ((PhotoPilotFacade) field(cli, "app")).planning(), "CLI uses the shared PlanningService");
            assertEquals(0, cli.execute("F10")); assertTrue(text(bytes).contains("plan generate <session-id>"));
            assertEquals(2, cli.execute("plan approve")); assertTrue(text(bytes).contains("No pending plan preview"));
            assertEquals(0, cli.execute("plan show " + id));
            assertTrue(text(bytes).contains("Confirmed requirements (F03): Fast-moving aircraft at an airshow"), text(bytes));
            assertTrue(text(bytes).contains("Environment evidence (F09)"));

            gemini.reply(depthOfFieldRequest()).replyWith(body -> plan(body, TELE, HEAVY_ZOOM, true));
            bytes.reset();
            assertEquals(0, cli.execute("plan generate " + id));
            String generated = text(bytes);
            assertTrue(generated.contains("Proposed plan (revision 1, not approved)"), generated);
            assertTrue(generated.contains("Validation passed"), generated);
            assertTrue(generated.contains("Mounted: Sony FE 200-600mm G on Sony A1"), generated);
            assertTrue(generated.contains("Tool call 1: calculation"), generated);
            assertTrue(generated.contains("Equipment (from the verified loadout):") && generated.contains("Hearing protection"), generated);
            assertTrue(generated.contains("Depth of field"), generated);
            assertTrue(generated.contains("plan approve"), generated);
            assertTrue(app.facade().planning().plans(id).value().orElseThrow().isEmpty(), "a preview is never saved");
            bytes.reset();
            assertEquals(0, cli.execute("plan approve"));
            assertTrue(text(bytes).contains("Plan revision 1 approved and saved to session Toronto airshow plan"), text(bytes));
            assertEquals("PLANNED", app.facade().sessions().open(id).value().orElseThrow().status());
            assertEquals(0, cli.execute("plan list " + id)); assertTrue(text(bytes).contains("Revision 1: Initial approved plan"));

            String request = "\"Make the setup lighter, but keep the two-camera requirement and avoid lens changes.\"";
            gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
            bytes.reset();
            assertEquals(0, cli.execute("plan revise " + id + " " + request));
            String revised = text(bytes);
            assertTrue(revised.contains("Revision 2 compared with saved revision 1") && revised.contains("Changed: equipment"), revised);
            assertTrue(revised.contains("Unchanged: settings, strategy, schedule"), revised);
            assertEquals(0, cli.execute("plan compare"));
            assertEquals(0, cli.execute("plan reject")); assertTrue(text(bytes).contains("Proposal rejected"));
            assertEquals(1, app.facade().planning().plans(id).value().orElseThrow().size(), "rejected revision is not saved");
            gemini.replyWith(body -> PlanFixtures.lighter(body, TELE, LIGHTER));
            assertEquals(0, cli.execute("plan revise " + id + " " + request));
            assertEquals(0, cli.execute("plan approve")); assertTrue(text(bytes).contains("Plan revision 2 approved and saved"));
            bytes.reset();
            assertEquals(0, cli.execute("plan open " + id + " 1"));
            assertTrue(text(bytes).contains("Approved plan (revision 1)") && text(bytes).contains(HEAVY_ZOOM));
            bytes.reset();
            assertEquals(0, cli.execute("plan open " + id));
            assertTrue(text(bytes).contains("Approved plan (revision 2)") && text(bytes).contains(LIGHTER));
            assertEquals(2, cli.execute("plan open " + id + " 9"));
            gemini.reply(conflict());
            assertEquals(2, cli.execute("plan revise " + id + " \"Use only one camera body.\""));
            assertTrue(text(bytes).contains("conflicts with a fixed constraint"));
            assertEquals(2, cli.execute("plan banana"));
            app.localization().useLanguage("zh-CN"); bytes.reset();
            assertEquals(0, cli.execute("plan show " + id));
            assertTrue(text(bytes).contains("已确认的需求（F03）") && text(bytes).contains("已批准的计划（修订 2）"), text(bytes));
        }
    }
}
