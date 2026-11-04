package com.photopilot.discovery;

import com.photopilot.application.DiscoveryPresentation;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.ShootRequirement;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F02 through the CLI on the same Facade/DiscoveryService as the GUI: same ideas and text, select/save/dismiss and F03 handoff. */
class DiscoveryCliTest {
    @TempDir Path root;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    @Test void cliRunsTheWholeDiscoveryWorkflowWithTheSharedPresentation() throws Exception {
        var day = DiscoveryFixtures.day();
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            for (var item : new GearFixtures.Airshow().items()) ok(app.facade().inventory().add(item, true));
            var bytes = new ByteArrayOutputStream();
            var localization = app.localization();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), localization);
            assertEquals(0, cli.execute("F02")); assertTrue(text(bytes).contains("discover run location="));
            assertEquals(0, cli.execute("help")); assertTrue(text(bytes).contains("F02 / discover"));
            assertEquals(0, cli.execute("discover gear")); assertTrue(text(bytes).contains("11 item(s) recorded"));
            assertEquals(2, cli.execute("discover select 1 new"), "no results yet");
            assertEquals(2, cli.execute("discover device"), "device location needs explicit consent");
            assertEquals(2, cli.execute("discover device consent")); assertTrue(text(bytes).contains("not supported on this computer"));
            var form = DiscoveryFixtures.form(day);
            String args = form.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(e -> e.getKey() + "=\"" + e.getValue() + "\"").collect(Collectors.joining(" "));
            bytes.reset();
            assertEquals(2, cli.execute("discover run " + args.replace("distance=\"40\"", "distance=\"-1\"")));
            assertTrue(text(bytes).contains("maximum travel distance must be"), text(bytes));
            assertEquals(2, cli.execute("discover again"), "no successful request yet");
            assertTrue(gemini.requests.isEmpty());

            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            bytes.reset();
            assertEquals(0, cli.execute("discover run " + args));
            String output = text(bytes);
            var latest = app.facade().discovery().latest().orElseThrow();
            // Same result objects and the same shared text as the GUI cards.
            for (var line : DiscoveryPresentation.context(latest.context(), localization)) assertTrue(output.contains(line), line);
            for (var idea : latest.ideas()) for (var line : DiscoveryPresentation.idea(idea, localization)) assertTrue(output.contains(line), line);
            assertTrue(output.contains("Idea 1 - Feasible: Aircraft departures at the island airport"), output);
            assertTrue(output.contains("Idea 3 - Unverified: Hidden laneway murals"));
            assertTrue(output.contains("== Not suitable for your constraints =="));
            assertTrue(output.contains("AI notes (Gemini; advice, not facts)") && output.contains("Straight-line distance"));

            assertEquals(0, cli.execute("discover show 2"));
            assertEquals(0, cli.execute("discover dismiss 3"));
            bytes.reset();
            assertEquals(2, cli.execute("discover show 3")); assertTrue(text(bytes).contains("was dismissed"));
            assertEquals(2, cli.execute("discover save 4 new"), "infeasible ideas cannot be saved");
            assertEquals(2, cli.execute("discover select 9 new"));
            bytes.reset();
            assertEquals(0, cli.execute("discover save 1 new"));
            assertTrue(text(bytes).contains("Created draft session") && text(bytes).contains("was saved in"), text(bytes));
            var session = ok(app.facade().sessions().search("")).getFirst();
            assertEquals(1, session.savedIdeas().size());
            bytes.reset();
            assertEquals(0, cli.execute("discover saved " + session.id()));
            assertTrue(text(bytes).contains("Aircraft departures at the island airport (Feasible when suggested"), text(bytes));

            // Select hands an unconfirmed draft to the F03 commands; only "requirement confirm" saves it.
            bytes.reset();
            assertEquals(0, cli.execute("discover select 2 " + session.id()));
            assertTrue(text(bytes).contains("unconfirmed F03 draft") && text(bytes).contains("Subject / genre: Skyline reflections along the waterfront"), text(bytes));
            assertTrue(ok(app.facade().sessions().open(session.id())).requirements().isEmpty(), "nothing confirmed yet");
            assertEquals(2, cli.execute("discover select 1 " + session.id()), "one open F03 draft at a time");
            assertEquals(0, cli.execute("requirement draft"));
            assertEquals(0, cli.execute("requirement confirm"));
            var confirmed = ok(app.facade().planning().requirement(session.id()));
            assertTrue(confirmed.confirmed());
            assertEquals("Skyline reflections along the waterfront", confirmed.subject());

            // Adjusted context, same session state; Gemini outage keeps the inputs and invents nothing.
            gemini.status(503, "");
            bytes.reset();
            assertEquals(2, cli.execute("discover again distance=10"));
            assertTrue(text(bytes).contains("No ideas were generated and none were invented"), text(bytes));
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            assertEquals(0, cli.execute("discover again distance=10"));
            assertEquals(10, app.facade().discovery().latest().orElseThrow().context().maxDistanceKm());
            assertEquals(2, cli.execute("discover run colour=blue"));
            app.localization().useLanguage("zh-CN");
            bytes.reset();
            gemini.reply(DiscoveryFixtures.acceptanceReply(day));
            assertEquals(0, cli.execute("discover again"));
            assertTrue(text(bytes).contains("可行的建议") && text(bytes).contains("直线距离"), text(bytes));
            app.localization().useLanguage("en");
            assertFalse(ok(app.facade().planning().requirement(session.id())).subject().isBlank());
            assertTrue(ok(app.facade().sessions().open(session.id())).requirements().filter(ShootRequirement::confirmed).isPresent());
        }
    }
}
