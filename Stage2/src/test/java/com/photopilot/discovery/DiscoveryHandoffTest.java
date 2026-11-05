package com.photopilot.discovery;

import com.photopilot.application.DiscoveryForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.matching.GearFixtures;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** AUD-010: F02 -> F01 handoff into a new session either succeeds completely or leaves no session behind. */
class DiscoveryHandoffTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;

    @BeforeEach void start() throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        app = new ApplicationBootstrap(providers.environment(gemini.environment(root)));
        for (var item : new GearFixtures.Airshow().items()) ok(app.facade().inventory().add(item, true));
    }
    @AfterEach void stop() { app.close(); gemini.close(); providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    int sessions() { return ok(app.facade().sessions().search("")).size(); }
    DiscoveryResult discover() throws Exception {
        var day = DiscoveryFixtures.day();
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var request = ok(DiscoveryForm.parse(DiscoveryFixtures.form(day), app.facade().discovery().location(), Optional.empty()));
        return ok(app.facade().discover(request));
    }

    @Test void selectAndSaveIntoANewSessionCreateExactlyOneCompleteSession() throws Exception {
        var r = discover(); var airport = r.feasible().getFirst();
        var made = ok(app.facade().discovery().selectInNewSession(airport));
        assertEquals(1, sessions());
        assertEquals(airport.subject(), made.session().title());
        assertEquals("DRAFT", made.session().status());
        assertFalse(made.draft().confirmed(), "the F03 draft still needs explicit confirmation");
        assertTrue(made.session().requirements().isEmpty(), "selection writes no requirement");

        var stored = ok(app.facade().discovery().saveInNewSession(r.feasible().get(1)));
        assertEquals(2, sessions());
        var reloaded = ok(app.facade().sessions().open(stored.id()));
        assertEquals(List.of(r.feasible().get(1).id()), reloaded.savedIdeas().stream().map(ActivitySuggestion::id).toList(), "session and idea written together");
    }

    @Test void refusedIdeasNeverLeaveAnEmptySession() throws Exception {
        var r = discover(); var murals = r.unverified().getFirst();
        ok(app.facade().discovery().dismiss(murals.id()));
        assertTrue(app.facade().discovery().selectInNewSession(murals).hasError("discover.error.unknownIdea"));
        assertTrue(app.facade().discovery().saveInNewSession(murals).hasError("discover.error.unknownIdea"));
        assertTrue(app.facade().discovery().saveInNewSession(r.infeasible().getFirst()).hasError("discover.error.infeasible"));
        assertTrue(app.facade().discovery().selectInNewSession(r.infeasible().getFirst()).hasError("discover.error.infeasible"));
        assertTrue(app.facade().discovery().selectInNewSession(null).hasError("discover.error.idea"));
        assertEquals(0, sessions(), "no orphan Draft sessions");
        assertFalse(Files.exists(app.storageScope().sessionsDirectory().resolve("sessions.json")), "nothing was written");
    }

    @Test void storageFailureDuringTheCombinedInsertLeavesTheStoreUnchanged() throws Exception {
        var r = discover();
        ok(app.facade().sessions().create("Existing", DiscoveryFixtures.day()));
        Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
        var attributes = Files.getFileAttributeView(store, DosFileAttributeView.class);
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && attributes != null);
        byte[] before = Files.readAllBytes(store);
        attributes.setReadOnly(true);
        try {
            assertFalse(app.facade().discovery().saveInNewSession(r.feasible().getFirst()).isSuccess());
            assertFalse(app.facade().discovery().selectInNewSession(r.feasible().getFirst()).isSuccess());
        } finally { attributes.setReadOnly(false); }
        assertArrayEquals(before, Files.readAllBytes(store));
        assertEquals(1, sessions());
    }

    @Test void cliNewSessionHandoffUsesTheSameAllOrNothingOperations() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        var day = DiscoveryFixtures.day();
        gemini.reply(DiscoveryFixtures.acceptanceReply(day));
        var form = DiscoveryFixtures.form(day);
        var command = new StringBuilder("discover run");
        form.forEach((key, value) -> { if (!value.isEmpty()) command.append(' ').append(key).append("=\"").append(value).append('"'); });
        assertEquals(0, cli.execute(command.toString()), bytes.toString(StandardCharsets.UTF_8));
        assertEquals(0, cli.execute("discover save 1 new"), bytes.toString(StandardCharsets.UTF_8));
        assertEquals(1, sessions());
        assertEquals(1, ok(app.facade().sessions().search("")).getFirst().savedIdeas().size());
        int infeasible = app.facade().discovery().latest().orElseThrow().ideas().size();
        assertEquals(2, cli.execute("discover save " + infeasible + " new"), "the last-ranked idea is not suitable");
        assertEquals(1, sessions(), "a refused CLI save creates no session");
    }
}
