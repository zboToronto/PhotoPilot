package com.photopilot.capability;

import com.photopilot.application.CapabilityNeedForm;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.matching.GearFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.capability.CapabilityFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Optional live smoke test (never part of routine runs): set PHOTOPILOT_LIVE_GEMINI=1 to run the Stage 2 F06 acceptance
 * request through the real F03 parse and F06 analysis with the locally configured key, on isolated fixture data in a
 * temporary directory, then confirm the profile and let F07 consume it. The transcript (no key, no identifiers) is
 * written to target/f06-live-smoke.txt.
 */
@EnabledIfEnvironmentVariable(named = "PHOTOPILOT_LIVE_GEMINI", matches = "1")
class LiveGeminiCapabilitySmokeTest {
    @TempDir Path root;
    final StringBuilder log = new StringBuilder();
    final LocalizationService english = LocalizationService.english();
    void print(String line) { log.append(line).append(System.lineSeparator()); System.out.println(line); }

    @Test void liveAcceptanceRequestYieldsAValidCapabilityOnlyProfileThatF07Consumes() throws Exception {
        var environment = Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString(), "GEMINI_TIMEOUT_SECONDS", "120");
        try (var app = new ApplicationBootstrap(environment)) {
            var gear = new GearFixtures.Airshow();
            for (var item : gear.items()) ok(app.facade().inventory().add(item, true));
            var id = ok(app.facade().sessions().create("Sunset aircraft (live)", LocalDate.now().plusDays(7))).id();
            print("=== F03 parse: " + REQUEST);
            var draft = ok(app.facade().parse(id, REQUEST));
            var requirement = ok(app.facade().planning().confirm(id, draft));
            com.photopilot.application.RequirementForm.describe(requirement, english).forEach(line -> print("  " + line));

            print("=== F06 analysis (Gemini)");
            var proposal = ok(app.facade().proposeNeeds(id));
            CapabilityNeedForm.profile(proposal.profile(), english).forEach(this::print);
            CapabilityNeedForm.review(proposal, english).forEach(this::print);
            CapabilityNeedForm.questions(proposal).forEach(line -> print("  Q " + line));
            var profile = proposal.profile();
            assertFalse(profile.reviewed());
            assertTrue(profile.validate(requirement).isSuccess(), () -> profile.validate(requirement).errors().toString());
            assertTrue(profile.need("focalLength").isPresent(), "aircraft need telephoto coverage");
            var bodies = profile.need("cameraCount").orElseThrow();
            assertEquals(Optional.of(2.0), bodies.minimum()); assertEquals(Optional.of(2.0), bodies.maximum());
            assertTrue(profile.need("lensCount").flatMap(n -> n.maximum()).filter(max -> max <= 2).isPresent(), "lens-change restriction preserved");
            var text = new ArrayList<String>(profile.tradeOffs()); profile.needs().forEach(n -> text.add(n.rationale()));
            for (String line : text) assertFalse(line.matches("(?i).*\\b(sony|canon|nikon|fujifilm|sigma|tamron)\\b.*"), "capabilities only: " + line);

            print("=== Confirm and F07");
            var saved = ok(app.facade().planning().confirmProfile(id, profile));
            print("Saved " + saved.needs().size() + " needs, origin " + saved.origin());
            var report = ok(app.facade().planning().matchGear(id, "portability"));
            print("F07 recommendations: " + report.recommendations().size() + ", AI ranked: " + report.aiRanked());
            assertFalse(report.recommendations().isEmpty());
        } finally {
            Files.writeString(Path.of("target", "f06-live-smoke.txt"), log, StandardCharsets.UTF_8);
        }
    }
}
