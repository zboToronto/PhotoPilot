package com.photopilot.discovery;

import com.photopilot.application.DiscoveryForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F02: an untrusted destination made only of separators is one unverifiable idea, never the loss of every idea. */
class DiscoveryDestinationTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;

    @BeforeEach void start() throws Exception { gemini = new FakeGemini(); providers = new FakeEnvironmentProviders(); app = new ApplicationBootstrap(providers.environment(gemini.environment(root))); }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }

    @Test void aDestinationOfOnlyCommasDoesNotDiscardTheOtherIdeas() {
        var day = DiscoveryFixtures.day();
        var normal = DiscoveryFixtures.acceptanceReply(day);
        // S6 needs no gear, so it reaches the travel lookup (ideas naming unknown gear labels are rejected before it).
        assertTrue(normal.contains("\"Nowhere Placeville\""));
        gemini.reply(normal.replace("\"Nowhere Placeville\"", "\",\""));
        var request = ok(DiscoveryForm.parse(DiscoveryFixtures.form(day), app.facade().discovery().location(), Optional.empty()));
        var result = ok(app.facade().discover(request));
        var subjects = result.ideas().stream().map(ActivitySuggestion::subject).toList();
        assertTrue(subjects.contains("Hidden laneway murals"), subjects::toString);
        assertTrue(subjects.contains("Small-town main street") && subjects.contains("Night skyline from a rooftop"), "the other ideas survive: " + subjects);
    }
}
