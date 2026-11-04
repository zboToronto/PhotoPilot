package com.photopilot.gearimport;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** F04 through the real CLI, bootstrap and JSON storage; Gemini and the catalogues are offline loopback fakes. */
class ImportCliTest {
    @TempDir Path directory;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private PhotoPilotCLI cli(ApplicationBootstrap bootstrap, ImagePrivacyBoundary privacy) {
        bytes.reset();
        return new PhotoPilotCLI(bootstrap.facade(), privacy, new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }
    private String output() { return bytes.toString(StandardCharsets.UTF_8); }

    @Test void screenshotIsRedactedApprovedRecognizedVerifiedConfirmedAndRestored() throws Exception {
        try (var gemini = new FakeGemini(); var catalog = new ImportFixtures.FakeCatalog().standard()) {
            var environment = ImportFixtures.environment(directory.resolve("data"), gemini, catalog);
            Path screenshot = ImportFixtures.png(directory, "order screenshot.png", 900, 600);
            byte[] original = Files.readAllBytes(screenshot);
            var privacy = new ImagePrivacyBoundary();
            try (var bootstrap = new ApplicationBootstrap(environment)) {
                var cli = cli(bootstrap, privacy);
                assertEquals(0, cli.execute("F04"));
                assertTrue(output().contains("import approve --confirm"));
                assertEquals(2, cli.execute("import approve --confirm"), "nothing to approve before loading");
                assertEquals(0, cli.execute("import load \"" + screenshot + "\" --kind=screenshot"));
                assertTrue(output().contains("900 × 600"));
                assertTrue(output().contains("personal information"));
                assertEquals(0, cli.execute("import redact 10 10 60 20"));
                assertEquals(2, cli.execute("import approve --confirm"), "the sanitized copy must be created and reviewed first");
                assertEquals(0, cli.execute("import preview"));
                String copy = output().lines().filter(line -> line.startsWith("Sanitized copy written to: ")).findFirst().orElseThrow()
                    .substring("Sanitized copy written to: ".length());
                assertTrue(Files.exists(Path.of(copy)));
                assertEquals(2, cli.execute("import approve"), "approval needs --confirm");
                assertTrue(gemini.requests.isEmpty(), "no upload without explicit approval");

                gemini.reply(ImportFixtures.CAMERA);
                assertEquals(0, cli.execute("import approve --confirm"));
                assertEquals(1, gemini.requests.size());
                var sent = ImportFixtures.sentImage(gemini.requests.getFirst());
                assertNotNull(sent, "the request carries the sanitized image");
                assertEquals(0x000000, sent.getRGB(40, 20) & 0xFFFFFF, "the redacted area was blacked out in the transmitted pixels");
                assertFalse(Files.exists(Path.of(copy)), "temporary copy released after recognition");
                assertArrayEquals(original, Files.readAllBytes(screenshot), "the original screenshot is unchanged");
                String review = output();
                assertTrue(review.contains("Identified in external sources: Wikipedia: Sony α7 IV; Lensfun: Sony Alpha 7 IV"), review);
                assertTrue(review.contains("Mount: Sony E · Externally verified (Lensfun)"), review);
                assertTrue(review.contains("Burst rate (fps): 10 · Externally verified (Wikipedia)"), review);
                assertTrue(review.contains("Stabilization: Unknown · Unknown or conflicting"), review);
                assertTrue(review.contains("oldid=123456"), review);

                assertEquals(0, cli.execute("import set quantity=2"));
                assertTrue(output().contains("Quantity: 2 · Entered by you"));
                assertEquals(0, cli.execute("import confirm"));
                assertTrue(output().contains("Imported Sony α7 IV (quantity 2) into Gear Inventory"), output());
                assertEquals(2, cli.execute("import show"), "the completed import is closed");
            }
            try (var reopened = new ApplicationBootstrap(environment)) {
                var cli = cli(reopened, privacy);
                assertEquals(0, cli.execute("gear list"));
                assertTrue(output().contains("Sony α7 IV | qty 2"), output());
                assertTrue(output().contains("mount"), output());
                // The same F05 service backs the GUI inventory; duplicate review applies to a second CLI import too.
                gemini.reply(ImportFixtures.CAMERA);
                assertEquals(0, cli.execute("import load \"" + screenshot + "\""));
                assertEquals(0, cli.execute("import preview"));
                assertEquals(0, cli.execute("import approve --confirm"));
                assertTrue(output().contains("Possible duplicates in your inventory"));
                assertEquals(2, cli.execute("import confirm"));
                assertTrue(output().contains("Possible duplicate gear"));
                String id = reopened.facade().inventory().list("").value().orElseThrow().items().getFirst().id().toString();
                assertEquals(0, cli.execute("import confirm --increase=" + id));
                assertEquals(3, reopened.facade().inventory().list("").value().orElseThrow().items().getFirst().quantity());
            }
        }
    }

    @Test void failuresAndCancellationLeaveInventoryUntouched() throws Exception {
        try (var gemini = new FakeGemini(); var catalog = new ImportFixtures.FakeCatalog().standard()) {
            var environment = ImportFixtures.environment(directory.resolve("data"), gemini, catalog);
            Path photo = ImportFixtures.jpeg(directory, "lens.jpg", 640, 480);
            try (var bootstrap = new ApplicationBootstrap(environment)) {
                var cli = cli(bootstrap, new ImagePrivacyBoundary());
                assertEquals(2, cli.execute("import load \"" + directory.resolve("missing.png") + "\""));
                assertTrue(output().contains("does not exist"));
                Path text = Files.writeString(directory.resolve("fake.png"), "text");
                assertEquals(2, cli.execute("import load \"" + text + "\""));
                assertTrue(output().contains("not a supported image"));
                assertEquals(0, cli.execute("import load \"" + photo + "\" --kind=photo"));
                assertEquals(0, cli.execute("import preview"));
                gemini.status(503, "");
                assertEquals(2, cli.execute("import approve --confirm"));
                assertTrue(output().contains("Recognition failed"), output());
                assertEquals(0, cli.execute("import preview"), "the review is kept for a retry");
                gemini.reply(ImportFixtures.AMBIGUOUS_LENS);
                assertEquals(0, cli.execute("import approve --confirm"));
                assertTrue(output().contains("Several models are plausible"));
                assertEquals(2, cli.execute("import confirm --accept-unverified"));
                assertEquals(2, cli.execute("import select 5"));
                assertEquals(0, cli.execute("import select 2"));
                assertTrue(output().contains("Filter diameter (mm): 82 · Externally verified (Wikipedia)"), output());
                assertEquals(0, cli.execute("import cancel"));
                assertTrue(output().contains("Import cancelled"));
                assertTrue(Files.exists(photo));
                assertTrue(bootstrap.facade().inventory().list("").value().orElseThrow().items().isEmpty());
            }
        }
    }
}
