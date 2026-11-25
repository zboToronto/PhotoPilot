package com.photopilot.gearimport;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe2 C19/C42 (TASK 11): the C19 sequence in one CLI process. A damaged JPEG fails with a content error and does
 * not affect the next load: a valid JPEG in a folder with spaces and non-ASCII characters loads, crops, rejects an area
 * outside the image, is never sent without explicit approval, and cancelling removes the sanitized copy. Repeated loads,
 * missing and unsupported files behave the same each time; the original file is never changed.
 */
class ImportLoadSequenceCliTest {
    @TempDir Path root;
    static String text(ByteArrayOutputStream bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    static String sha256(Path file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
    /** A synthetic 760 x 480 label photo (no real product or person). */
    static Path label(Path file) throws Exception {
        var image = new BufferedImage(760, 480, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); g.setColor(new Color(235, 235, 228)); g.fillRect(0, 0, 760, 480);
        g.setColor(Color.DARK_GRAY); g.drawString("SYNTHETIC LABEL 0000", 60, 200); g.dispose();
        Files.createDirectories(file.getParent()); assertTrue(ImageIO.write(image, "jpg", file.toFile())); return file;
    }

    @Test void aDamagedJpegDoesNotAffectTheNextLoadAndNothingIsSentWithoutApproval() throws Exception {
        Path folder = root.resolve("F04 fixtures 图像 ü");
        Path valid = label(folder.resolve("sony label 标签.jpg"));
        Path damaged = Files.write(folder.resolve("damaged.jpg"), "this is not a jpeg file at all".getBytes(StandardCharsets.US_ASCII));
        Path notes = Files.writeString(folder.resolve("notes.jpg"), "plain text");
        String before = sha256(valid);
        try (var gemini = new FakeGemini(); var app = new ApplicationBootstrap(gemini.environment(root.resolve("profile")))) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(2, cli.execute("import load \"" + damaged + "\""));
            assertTrue(text(bytes).contains("damaged.jpg is not a supported image"), text(bytes));
            assertFalse(text(bytes).contains("could not be read"), "a damaged file is a content error, not an I/O failure");
            bytes.reset();
            assertEquals(0, cli.execute("import load \"" + valid + "\" --kind=label"));
            assertTrue(text(bytes).contains("Loaded sony label 标签.jpg · 760 × 480 px · JPEG"), text(bytes));
            assertEquals(0, cli.execute("import crop 0 0 600 400"));
            bytes.reset();
            assertEquals(2, cli.execute("import redact 999 0 5 5"));
            assertTrue(text(bytes).contains("outside the image"), text(bytes));
            bytes.reset();
            assertEquals(0, cli.execute("import preview"));
            var written = text(bytes).lines().filter(l -> l.startsWith("Sanitized copy written to: ")).map(l -> Path.of(l.substring("Sanitized copy written to: ".length()).strip())).findFirst();
            assertTrue(written.isPresent() && Files.isRegularFile(written.get()), text(bytes));
            assertTrue(text(bytes).contains("600 × 400 px PNG"), text(bytes));
            assertEquals(2, cli.execute("import approve"), "approval needs --confirm");
            assertTrue(gemini.requests.isEmpty(), "nothing is sent without explicit approval");
            assertEquals(0, cli.execute("import cancel"));
            assertFalse(Files.exists(written.get()), "cancel removes the sanitized copy");
            // Repeated loads in the same process, then missing and unsupported files, then the valid file again.
            for (int i = 0; i < 3; i++) { assertEquals(0, cli.execute("import load \"" + valid + "\"")); assertEquals(0, cli.execute("import cancel")); }
            bytes.reset();
            assertEquals(2, cli.execute("import load \"" + folder.resolve("missing.jpg") + "\""));
            assertTrue(text(bytes).contains("missing.jpg does not exist"), text(bytes));
            assertEquals(2, cli.execute("import load \"" + notes + "\""));
            assertTrue(text(bytes).contains("notes.jpg is not a supported image"), text(bytes));
            assertEquals(0, cli.execute("import load \"" + valid + "\""));
            assertEquals(0, cli.execute("import cancel"));
            assertTrue(gemini.requests.isEmpty());
        }
        assertEquals(before, sha256(valid), "the original file is never changed");
    }
}
