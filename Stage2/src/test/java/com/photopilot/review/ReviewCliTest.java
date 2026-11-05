package com.photopilot.review;

import com.photopilot.application.PhotoPilotFacade;
import com.photopilot.application.ReviewPresentation;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.planning.PlanFixtures;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.planning.PlanFixtures.ok;
import static org.junit.jupiter.api.Assertions.*;

/** CLI F11 workflow over the same Facade, ReviewService and storage as the GUI. */
class ReviewCliTest {
    @TempDir Path root;
    @TempDir Path photos;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    static Object field(Object owner, String name) throws Exception { var f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); }

    @Test void cliAnalysesComparesReviewsSavesAndReopensWithTheSharedService() throws Exception {
        try (var gemini = new FakeGemini(); var providers = new FakeEnvironmentProviders();
             var app = new ApplicationBootstrap(providers.environment(gemini.environment(root)))) {
            var id = ReviewFixtures.completed(app, gemini, new PlanFixtures.Gear());
            var files = ReviewFixtures.photos(photos, ReviewFixtures.plan(app, id));
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertSame(app.facade().reviews(), ((PhotoPilotFacade) field(cli, "app")).reviews(), "CLI uses the shared ReviewService");
            assertEquals(0, cli.execute("F11")); assertTrue(text(bytes).contains("review analyze <session-id>"));
            assertEquals(0, cli.execute("status")); assertTrue(text(bytes).contains("F11 AI Post-Shoot Analysis and Review - IMPLEMENTED"), text(bytes));
            assertEquals(2, cli.execute("review save")); assertTrue(text(bytes).contains("No pending draft review"));
            assertEquals(2, cli.execute("review generate " + id)); assertTrue(text(bytes).contains("No saved EXIF statistics"), text(bytes));
            assertEquals(2, cli.execute("review analyze " + id));

            bytes.reset();
            var quoted = new StringBuilder("review analyze " + id);
            for (var file : files) quoted.append(" \"").append(file).append('"');
            quoted.append(" \"").append(photos.resolve("missing.jpg")).append('"');
            assertEquals(0, cli.execute(quoted.toString()));
            String analysed = text(bytes);
            assertTrue(analysed.contains("air-01.jpg [JPEG]: read, 1 record(s)"), analysed);
            assertTrue(analysed.contains("air-07-exported.jpg [JPEG]: no EXIF metadata"), analysed);
            assertTrue(analysed.contains("missing.jpg [-]: not found"), analysed);
            assertTrue(analysed.contains("Statistics calculated and saved to the session."), analysed);
            assertTrue(analysed.contains("Sony A1: 4 (66.7%)"), analysed);
            assertTrue(analysed.contains("Fastest 1/3200 s, slowest 1/800 s"), analysed);
            assertFalse(analysed.contains(photos.toString()), "folders are never printed");
            assertEquals("COMPLETED", ok(app.facade().sessions().open(id)).status());

            // GUI and CLI consistency: identical files give identical deterministic statistics text.
            var saved = ok(app.facade().reviews().inputs(id)).statistics().orElseThrow();
            for (String section : ReviewPresentation.STATISTICS)
                for (String line : ReviewPresentation.statistics(saved, section, app.localization())) assertTrue(analysed.contains(line), line);

            bytes.reset();
            assertEquals(0, cli.execute("review compare " + id));
            assertTrue(text(bytes).contains("Shutter speed: planned 1/1600-1/3200 s; 4 of 6 photos with shutter data were within it (66.7%), 0 faster and 2 slower"), text(bytes));
            assertEquals(0, cli.execute("review stats " + id)); assertTrue(text(bytes).contains("Evidence limits"));

            gemini.replyWith(body -> ReviewFixtures.review(body, false));
            bytes.reset();
            assertEquals(0, cli.execute("review generate " + id));
            String generated = text(bytes);
            assertTrue(generated.contains("Draft review (not saved)"), generated);
            assertTrue(generated.contains("Median ISO was ISO 720."), generated);
            assertTrue(generated.contains("review save"), generated);
            assertTrue(ok(app.facade().reviews().reviews(id)).isEmpty(), "a draft is never saved");
            assertEquals(0, cli.execute("review draft"));
            bytes.reset();
            assertEquals(0, cli.execute("review save"));
            assertTrue(text(bytes).contains("session status: Reviewed"), text(bytes));
            assertEquals("REVIEWED", ok(app.facade().sessions().open(id)).status());
            assertEquals(0, cli.execute("review list " + id)); assertTrue(text(bytes).contains("1. Saved review"));
            bytes.reset();
            assertEquals(0, cli.execute("review open " + id));
            assertTrue(text(bytes).contains("Median ISO was ISO 720.") && text(bytes).contains("Statistical references"), text(bytes));
            assertEquals(2, cli.execute("review open " + id + " 5"));
            assertEquals(2, cli.execute("review analyze " + id + " \"" + files.getFirst() + "\""));
            assertTrue(text(bytes).contains("already been reviewed"), text(bytes));
            assertEquals(2, cli.execute("review banana"));
            app.localization().useLanguage("zh-CN"); bytes.reset();
            assertEquals(0, cli.execute("review show " + id));
            assertTrue(text(bytes).contains("已保存的复盘：1") && text(bytes).contains("快门速度"), text(bytes));
        }
    }
}
