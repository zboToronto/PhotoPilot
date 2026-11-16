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

/**
 * F04 CLI, as in the GUI: undo and clear-regions change what the copy must show, so the copy reviewed before them is
 * deleted and cannot be approved; only a new preview can. An undo with nothing to remove keeps the copy.
 */
class ImportCliRegionChangeTest {
    private static final String COPY = "Sanitized copy written to: ";
    @TempDir Path directory;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private String output() { return bytes.toString(StandardCharsets.UTF_8); }
    private Path preview(PhotoPilotCLI cli) {
        bytes.reset();
        assertEquals(0, cli.execute("import preview"));
        Path copy = Path.of(output().lines().filter(line -> line.startsWith(COPY)).findFirst().orElseThrow().substring(COPY.length()));
        assertTrue(Files.exists(copy));
        return copy;
    }

    @Test void undoAndClearRegionsWithdrawTheReviewedCopy() throws Exception {
        try (var gemini = new FakeGemini(); var catalog = new ImportFixtures.FakeCatalog().standard()) {
            var environment = ImportFixtures.environment(directory.resolve("data"), gemini, catalog);
            Path screenshot = ImportFixtures.png(directory, "order.png", 900, 600);
            try (var bootstrap = new ApplicationBootstrap(environment)) {
                var cli = new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8));
                gemini.reply(ImportFixtures.CAMERA);
                assertEquals(0, cli.execute("import load \"" + screenshot + "\" --kind=screenshot"));
                assertEquals(0, cli.execute("import redact 10 10 60 20"));
                Path redacted = preview(cli);
                assertEquals(0, cli.execute("import undo"));
                bytes.reset();
                assertEquals(2, cli.execute("import approve --confirm"), "the copy still shows the undone redaction");
                assertTrue(gemini.requests.isEmpty(), "nothing is sent that no longer matches the regions");
                assertTrue(output().contains("Create and review the sanitized copy first"), output());
                assertFalse(Files.exists(redacted), "the stale copy is deleted");

                Path unchanged = preview(cli);
                assertEquals(0, cli.execute("import undo"));
                assertTrue(output().contains("Nothing to undo."), output());
                assertTrue(Files.exists(unchanged), "an undo that removed nothing keeps the reviewed copy");
                assertEquals(0, cli.execute("import clear-regions"));
                assertTrue(Files.exists(unchanged), "clearing when there are no regions keeps the reviewed copy");

                assertEquals(0, cli.execute("import crop 0 0 400 300"));
                Path cropped = preview(cli);
                assertEquals(0, cli.execute("import clear-regions"));
                assertEquals(2, cli.execute("import approve --confirm"), "the copy still shows the cleared crop");
                assertFalse(Files.exists(cropped));
                assertTrue(gemini.requests.isEmpty());

                Path fresh = preview(cli);
                assertEquals(0, cli.execute("import approve --confirm"), "a new preview can be approved");
                assertEquals(1, gemini.requests.size());
                assertEquals(900, ImportFixtures.sentImage(gemini.requests.getFirst()).getWidth(), "the sent copy matches the cleared regions");
                assertFalse(Files.exists(fresh));
                assertEquals(0, cli.execute("import cancel"));
            }
        }
    }
}
