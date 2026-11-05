package com.photopilot.review;

import com.photopilot.application.ReviewPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Optional live smoke test (never part of routine runs): set PHOTOPILOT_LIVE_GEMINI=1. The approved airshow plan is
 * prepared offline with the loopback fakes; then a second bootstrap on the same temporary data uses the real Gemini
 * endpoint with the locally configured key to interpret synthetic fixture statistics. Only aggregate evidence is
 * sent. The transcript (no key, no paths) is written to target/f11-live-smoke.txt.
 */
@EnabledIfEnvironmentVariable(named = "PHOTOPILOT_LIVE_GEMINI", matches = "1")
class LiveGeminiReviewSmokeTest {
    @TempDir Path root;
    @TempDir Path photos;
    final StringBuilder log = new StringBuilder();
    final LocalizationService english = LocalizationService.english();
    void print(String line) { log.append(line).append(System.lineSeparator()); System.out.println(line); }

    @Test void liveReviewInterpretsVerifiedStatisticsAndIsSavedOnlyOnConfirmation() throws Exception {
        UUID id;
        List<Path> files;
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var offline = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            id = ReviewFixtures.completed(offline, gemini, new PlanFixtures.Gear());
            files = ReviewFixtures.photos(photos, ReviewFixtures.plan(offline, id));
        }
        var environment = Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(), "GEMINI_TIMEOUT_SECONDS", "120");
        try (var app = new ApplicationBootstrap(environment)) {
            var analysis = app.facade().reviews().analysis(id, files, false);
            assertTrue(analysis.isSuccess(), () -> analysis.errors().toString());
            ReviewPresentation.analysis(analysis.value().orElseThrow(), english).forEach(this::print);
            long started = System.nanoTime();
            var draft = app.facade().reviews().draft(id);
            print("=== F11 live review (" + (System.nanoTime() - started) / 1_000_000 + " ms)");
            if (!draft.isSuccess()) { draft.messages().forEach(m -> print("ERROR: " + ReviewPresentation.render(m, english))); fail(draft.errors().toString()); }
            var review = draft.value().orElseThrow();
            print(ReviewPresentation.heading(review.review(), english));
            for (String section : ReviewPresentation.SECTIONS) {
                print("[" + section + "]");
                ReviewPresentation.section(review.review(), section, english).forEach(l -> print("  " + l));
            }
            ReviewPresentation.lines(review.notes(), english).forEach(this::print);
            print("State before save: " + app.facade().sessions().open(id).value().orElseThrow().status());
            var saved = app.facade().reviews().save(id, review.review());
            print("Save: " + (saved.isSuccess() ? "saved; state " + app.facade().sessions().open(id).value().orElseThrow().status() : saved.errors()));
            assertTrue(saved.isSuccess());
        } finally {
            Files.writeString(Path.of("target", "f11-live-smoke.txt"), log.toString(), StandardCharsets.UTF_8);
        }
    }
}
