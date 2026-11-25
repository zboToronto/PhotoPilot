package com.photopilot.matching;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.Equipment;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.matching.GearFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 D01 / C18: `match details N` and `match select N` with zero, one and several compatible candidates. An
 * empty result has no number range ("1 to 0"); it says so and how to recover. Exit 2 and no write on every rejection;
 * the compatibility rules themselves are unchanged.
 */
class MatchCandidateRangeCliTest {
    @TempDir Path root;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }

    record Run(ApplicationBootstrap app, PhotoPilotCLI cli, ByteArrayOutputStream bytes, UUID id, int candidates) { }
    Run matched(FakeGemini gemini, Path profile, List<? extends Equipment> items) throws Exception {
        var app = new ApplicationBootstrap(gemini.environment(profile));
        for (var item : items) assertTrue(app.facade().inventory().add(item, true).isSuccess());
        var id = app.facade().sessions().create("Range check", LocalDate.of(2026, 11, 14)).value().orElseThrow().id();
        assertTrue(app.facade().planning().confirm(id, airshowRequirement().asDraft()).isSuccess());
        assertTrue(app.facade().planning().confirmProfile(id, airshowProfile().asDraft()).isSuccess());
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        assertEquals(0, cli.execute("match run " + id + " portability"));
        int candidates = app.facade().planning().matchGear(id, "portability").value().orElseThrow().recommendations().size();
        return new Run(app, cli, bytes, id, candidates);
    }
    static byte[] store(ApplicationBootstrap app) throws Exception { return Files.readAllBytes(app.storageScope().sessionsDirectory().resolve("sessions.json")); }

    @Test void zeroCandidatesExplainHowToRecoverInBothLanguagesWithoutANumberRange() throws Exception {
        var gear = new Airshow();
        try (var gemini = new FakeGemini()) {
            // C18: only lenses whose mount labels do not match the bodies (Canon EF, unknown): no UC14-compatible combination.
            var run = matched(gemini, root.resolve("empty"), List.of(gear.a1, gear.a7, gear.canon, gear.vintage, gear.fz, gear.sd));
            try (var app = run.app()) {
                assertEquals(0, run.candidates(), "precondition: no compatible combination");
                assertTrue(text(run.bytes()).contains("Nothing to select"), text(run.bytes()));
                byte[] before = store(app);
                for (String command : List.of("match details 1", "match select 1", "match details 0", "match select -1", "match details 7", "match select x")) {
                    run.bytes().reset();
                    assertEquals(2, run.cli().execute(command), command);
                    String out = text(run.bytes());
                    assertTrue(out.contains("No compatible gear combinations are available. Adjust your requirements or inventory and run matching again."), command + ": " + out);
                    assertFalse(out.contains("1 to 0"), command + ": " + out);
                }
                assertEquals(2, run.cli().execute("match confirm"), "nothing was selected");
                app.localization().useLanguage("zh-CN"); run.bytes().reset();
                assertEquals(2, run.cli().execute("match select 1"));
                String zh = text(run.bytes());
                assertTrue(zh.contains("没有可用的兼容器材组合。请调整拍摄需求或器材库存后重新运行匹配。"), zh);
                assertFalse(zh.contains("1 到 0"), zh);
                app.localization().useLanguage("en");
                assertArrayEquals(before, store(app), "rejections never write");
                assertTrue(app.facade().sessions().open(run.id()).value().orElseThrow().selectedLoadout().isEmpty());
            }
        }
    }

    @Test void oneOrSeveralCandidatesKeepTheExistingRangeAndSelection() throws Exception {
        var gear = new Airshow();
        try (var gemini = new FakeGemini()) {
            for (var inventory : List.of(List.of(gear.a1, gear.a7, gear.tele, gear.zoom, gear.fz, gear.sd), gear.items())) {
                var run = matched(gemini, root.resolve("p" + inventory.size()), inventory);
                try (var app = run.app()) {
                    int n = run.candidates();
                    assertTrue(n >= 1, "precondition: at least one candidate");
                    byte[] before = store(app);
                    for (String command : List.of("match details 0", "match details -1", "match select " + (n + 1), "match details x")) {
                        run.bytes().reset();
                        assertEquals(2, run.cli().execute(command), command);
                        assertTrue(text(run.bytes()).contains("Choose a candidate number from 1 to " + n + "."), command + ": " + text(run.bytes()));
                    }
                    app.localization().useLanguage("zh-CN"); run.bytes().reset();
                    assertEquals(2, run.cli().execute("match details " + (n + 1)));
                    assertTrue(text(run.bytes()).contains("请选择 1 到 " + n + " 之间的候选编号。"), text(run.bytes()));
                    app.localization().useLanguage("en");
                    assertArrayEquals(before, store(app), "invalid numbers never write");
                    assertEquals(0, run.cli().execute("match details " + n));
                    assertEquals(0, run.cli().execute("match select 1"));
                    assertArrayEquals(before, store(app), "selection is saved only on confirm");
                    assertEquals(0, run.cli().execute("match confirm"));
                    assertTrue(app.facade().sessions().open(run.id()).value().orElseThrow().selectedLoadout().isPresent());
                }
            }
        }
    }
}
