package com.photopilot.planning;

import com.photopilot.application.PlanPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.model.*;
import com.photopilot.service.PlanPreview;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Optional live smoke test (never part of routine runs): set PHOTOPILOT_LIVE_GEMINI=1 to run F09 + F10 against the
 * real providers and Gemini with the locally configured key, on isolated fixture data in a temporary directory.
 * The transcript (no key, no identifiers) is written to target/f10-live-smoke.txt.
 */
@EnabledIfEnvironmentVariable(named = "PHOTOPILOT_LIVE_GEMINI", matches = "1")
class LiveGeminiPlanSmokeTest {
    @TempDir Path root;
    final StringBuilder log = new StringBuilder();
    final LocalizationService english = LocalizationService.english();
    void print(String line) { log.append(line).append(System.lineSeparator()); System.out.println(line); }
    void show(String title, com.photopilot.common.Result<PlanPreview> result) {
        print("=== " + title);
        if (!result.isSuccess()) { result.errors().forEach(e -> print("ERROR: " + e)); return; }
        var preview = result.value().orElseThrow();
        if (preview.revision()) PlanPresentation.comparison(preview, english).forEach(this::print);
        print(PlanPresentation.heading(preview.plan(), english));
        for (String section : PlanPresentation.SECTIONS) { print("[" + section + "]"); PlanPresentation.section(preview.plan(), section, english).forEach(l -> print("  " + l)); }
        PlanPresentation.validation(preview, english).forEach(this::print);
        PlanPresentation.evidence(preview, english).forEach(this::print);
    }

    @Test void liveAirshowPlanIsGeneratedValidatedApprovedAndRevised() throws Exception {
        var environment = Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(), "GEMINI_TIMEOUT_SECONDS", "120");
        try (var app = new ApplicationBootstrap(environment)) {
            var id = PlanFixtures.session(app, new PlanFixtures.Gear(), true);
            var day = PlanFixtures.day();
            var place = new GeoLocation(EnvironmentFixtures.PLACE, EnvironmentFixtures.LAT, EnvironmentFixtures.LON, false, false, Optional.empty());
            var request = new EnvironmentRequest(place, day, EnvironmentFixtures.from(day), EnvironmentFixtures.until(day), Optional.of(Duration.ofMinutes(30)),
                Optional.of(FakeEnvironmentProviders.TORONTO), "", List.of());
            long started = System.nanoTime();
            var assessed = app.facade().assessEnvironment(id, request);
            print("F09 live assessment: " + (assessed.isSuccess() ? "ok, " + assessed.value().orElseThrow().windows().size() + " validated window(s)" : assessed.errors()));
            var generated = app.facade().planning().generatePreview(id);
            show("F10 generate (" + (System.nanoTime() - started) / 1_000_000_000 + " s incl. F09)", generated);
            assertTrue(generated.isSuccess(), () -> generated.errors().toString());
            var preview = generated.value().orElseThrow();
            if (preview.approvable()) {
                var saved = app.facade().planning().approve(id, preview.plan());
                print("Approve: " + (saved.isSuccess() ? "saved revision " + saved.value().orElseThrow().revision() + ", state "
                    + app.facade().sessions().open(id).value().orElseThrow().status() : saved.errors()));
                var lighter = app.facade().planning().revisePreview(id, "Make the setup lighter, but keep the two-camera requirement and avoid lens changes.");
                show("F10 revise: lighter", lighter);
                if (lighter.isSuccess() && lighter.value().orElseThrow().approvable()) {
                    var revised = app.facade().planning().approve(id, lighter.value().orElseThrow().plan());
                    print("Approve revision: " + (revised.isSuccess() ? "saved revision " + revised.value().orElseThrow().revision() : revised.errors()));
                }
                var closeUps = app.facade().planning().revisePreview(id, "Prioritize close-up aircraft shots; keep the same equipment and schedule.");
                show("F10 revise: prioritize close-ups", closeUps);
                if (closeUps.isSuccess() && closeUps.value().orElseThrow().approvable()) {
                    var revised = app.facade().planning().approve(id, closeUps.value().orElseThrow().plan());
                    print("Approve revision: " + (revised.isSuccess() ? "saved revision " + revised.value().orElseThrow().revision() : revised.errors()));
                }
                show("F10 revise: conflicting request", app.facade().planning().revisePreview(id, "Use only one camera body and change lenses whenever needed."));
                print("Saved revisions: " + app.facade().planning().plans(id).value().orElseThrow().stream().map(p -> p.revision()).toList());
            }
        } finally {
            Files.writeString(Path.of("target", "f10-live-smoke.txt"), log.toString(), StandardCharsets.UTF_8);
        }
    }
}
