package com.photopilot.ui.cli;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizedException;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.service.SessionService;
import com.photopilot.service.TechnicalCalculator;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** AUD-006 outer CLI error boundary and AUD-011 manual lifecycle events, with controlled exception injection. */
class CliErrorBoundaryTest {
    @TempDir Path root;
    Map<String, String> environment() { return Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }

    /** Real services; sessions() throws whatever the test injects, once. */
    static final class Failing extends PhotoPilotFacade {
        Supplier<Throwable> next;
        Failing(PhotoPilotFacade base) { super(base.sessions(), base.inventory(), base.discovery(), base.planning(), base.reviews(), new TechnicalCalculator()); }
        @Override public SessionService sessions() {
            var failure = next; next = null;
            if (failure != null) {
                Throwable thrown = failure.get();
                if (thrown instanceof RuntimeException runtime) throw runtime;
                throw (Error) thrown;
            }
            return super.sessions();
        }
    }

    @Test void unexpectedRuntimeFailuresAreReportedAndTheInteractiveLoopContinues() {
        try (var app = new ApplicationBootstrap(environment())) {
            var facade = new Failing(app.facade());
            var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(facade, new ImagePrivacyBoundary(), new Scanner("session list\nsession list\nsession list\nstatus\nexit\n"),
                new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
            cli.diagnostics(new PrintStream(err, true, StandardCharsets.UTF_8));
            var failures = new ArrayDeque<Supplier<Throwable>>(List.of(() -> new IllegalStateException("injected state"),
                () -> new UncheckedIOException(new IOException("injected io"))));
            facade.next = failures.poll();
            // The first command fails; the loop must carry on. Inject the second failure between commands.
            var wrapped = new PhotoPilotCLI(facade, new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
            wrapped.diagnostics(new PrintStream(err, true, StandardCharsets.UTF_8));
            assertEquals(2, wrapped.execute("session list"));
            facade.next = failures.poll();
            assertEquals(2, wrapped.execute("session list"));
            assertEquals(0, wrapped.execute("session list"), "the next command works normally");
            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("unexpected internal error (IllegalStateException)"), text);
            assertTrue(text.contains("unexpected internal error (UncheckedIOException)"), text);
            assertFalse(text.contains("injected"), "exception messages are not echoed to the console");
            assertTrue(err.toString(StandardCharsets.UTF_8).contains("injected state"), "the stack trace goes to the diagnostics stream");

            facade.next = () -> new IllegalStateException("loop");
            int start = out.toString(StandardCharsets.UTF_8).length();
            cli.run(new String[0]);
            String loop = out.toString(StandardCharsets.UTF_8).substring(start);
            int failed = loop.indexOf("unexpected internal error (IllegalStateException)");
            assertTrue(failed >= 0, loop);
            String after = loop.substring(failed);
            assertTrue(after.contains("Sessions") || after.contains("session"), "later session commands ran: " + after);
            assertTrue(after.contains("F11"), "status after the failure still ran: " + after);
            assertTrue(after.contains("PhotoPilot closed."), "the session ended only on exit");
            assertEquals(2, cli.exitCode(), "the failure is still reflected in the exit status");
        }
    }

    @Test void localizedValidationErrorsKeepTheirMessageAndFatalErrorsAreNotSwallowed() {
        try (var app = new ApplicationBootstrap(environment())) {
            var facade = new Failing(app.facade()); var out = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(facade, new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
            cli.diagnostics(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            facade.next = () -> new LocalizedException("error.session.id");
            assertEquals(2, cli.execute("session list"));
            String text = out.toString(StandardCharsets.UTF_8);
            assertFalse(text.contains("internal error"), "an expected validation error is not reported as internal: " + text);
            facade.next = () -> new AssertionError("fatal");
            assertThrows(AssertionError.class, () -> cli.execute("session list"), "Errors are never caught");
        }
    }

    @Test void onlyShootCompletedCanBeRecordedManuallyAndWorkflowEventsCannotBypassF10OrF11() throws Exception {
        try (var app = new ApplicationBootstrap(environment())) {
            var repository = new JsonSessionRepository(app.storageScope().sessionsDirectory());
            // A Draft that already holds an approved plan: the State guard alone would let PLAN_APPROVED through.
            var draft = repository.insert(SessionFixtures.linked(new DraftState(), List.of(), false)).value().orElseThrow();
            Path store = app.storageScope().sessionsDirectory().resolve("sessions.json");
            byte[] before = Files.readAllBytes(store);
            var sessions = app.facade().sessions();
            assertEquals(List.of(SessionEvent.SHOOT_COMPLETED), SessionService.MANUAL_EVENTS);
            assertTrue(sessions.recordEvent(draft, SessionEvent.PLAN_APPROVED).hasError("error.session.workflowEvent"));
            assertTrue(sessions.recordEvent(draft, SessionEvent.REVIEW_SAVED).hasError("error.session.workflowEvent"));
            assertTrue(sessions.recordEvent(draft, SessionEvent.SHOOT_COMPLETED).hasError("error.session.transition"), "State guards still apply");
            assertTrue(sessions.recordEvent(draft, null).hasError("error.session.state"));
            assertArrayEquals(before, Files.readAllBytes(store), "refused events change nothing");

            var out = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(2, cli.execute("session transition " + draft.id() + " event=PLAN_APPROVED"));
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("cannot be recorded manually"), out.toString(StandardCharsets.UTF_8));
            assertEquals("DRAFT", sessions.open(draft.id()).value().orElseThrow().status());

            var planned = repository.insert(SessionFixtures.linked(new PlannedState(), List.of(), false)).value().orElseThrow();
            assertEquals(0, cli.execute("session transition " + planned.id() + " event=SHOOT_COMPLETED"));
            assertEquals("COMPLETED", sessions.open(planned.id()).value().orElseThrow().status());
            var completed = sessions.open(planned.id()).value().orElseThrow();
            assertEquals(2, cli.execute("session transition " + completed.id() + " event=REVIEW_SAVED"), "a review is saved through F11 only");
            assertEquals("COMPLETED", sessions.open(planned.id()).value().orElseThrow().status());
        }
    }
}
