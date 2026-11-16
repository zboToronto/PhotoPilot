package com.photopilot.ui.cli;

import com.photopilot.application.ProfileManager;
import com.photopilot.gearimport.ImportFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * As in the GUI, selecting the user that is already active keeps this CLI's in-memory work (drafts and an open F04
 * import); only a switch to another user clears it.
 */
class ProfileReselectCliTest {
    @TempDir Path root;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Test void selectingTheActiveProfileKeepsDraftsAndTheOpenImport() throws Exception {
        var environment = Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
        try (var manager = new ProfileManager(environment)) {
            var cli = new PhotoPilotCLI(manager, new Scanner(""), new PrintStream(output, true, StandardCharsets.UTF_8));
            UUID active = manager.activeProfile().value().orElseThrow().id();
            var context = manager.activeContext();
            assertEquals(0, cli.execute("import load \"" + ImportFixtures.png(root, "lens.png", 400, 300) + "\""));
            assertEquals(0, cli.execute("import redact 10 10 60 20"));

            assertEquals(0, cli.execute("profile select " + active));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("User selected."));
            assertSame(context, manager.activeContext());
            output.reset();
            assertEquals(0, cli.execute("import regions"), () -> output.toString(StandardCharsets.UTF_8));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("1 blacked-out area(s)"), "the open import survives re-selecting the same user");

            UUID other = manager.createProfile("Other").value().orElseThrow().id();
            assertEquals(0, cli.execute("profile select " + other));
            output.reset();
            assertEquals(2, cli.execute("import regions"), "a real switch still closes the previous user's import");
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Load an image first"));
        }
    }
}
