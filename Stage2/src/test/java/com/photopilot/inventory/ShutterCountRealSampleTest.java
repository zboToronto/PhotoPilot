package com.photopilot.inventory;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.ShutterCountResult.Status;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A real, unedited camera photograph through the full application (composition root, Facade, service, bundled ExifTool,
 * and the CLI), compared with the value an independent shutter count tool reported for the same file. The photograph and
 * the reference stay outside the repository: their location and values are supplied when the test is run:
 * PHOTOPILOT_SHUTTER_SAMPLE (photo path), PHOTOPILOT_SHUTTER_REFERENCE (the reference count) and
 * PHOTOPILOT_SHUTTER_REFERENCE_MODEL (the EXIF model the reference tool detected). Skipped when they are not set.
 */
class ShutterCountRealSampleTest {
    @TempDir Path temp;

    private static byte[] sha256(Path file) throws Exception { return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)); }

    @Test void realPhotoMatchesTheIndependentReference() throws Exception {
        String sample = System.getenv("PHOTOPILOT_SHUTTER_SAMPLE"), reference = System.getenv("PHOTOPILOT_SHUTTER_REFERENCE"),
            referenceModel = System.getenv("PHOTOPILOT_SHUTTER_REFERENCE_MODEL");
        assumeTrue(sample != null && reference != null && referenceModel != null, "real sample not configured");
        Path photo = Path.of(sample);
        assumeTrue(Files.isRegularFile(photo), "real sample not present");
        long expected = Long.parseLong(reference.strip());
        byte[] before = sha256(photo);
        var modified = Files.getLastModifiedTime(photo);

        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", temp.resolve("data").toString(),
                "PHOTOPILOT_CONFIG_DIR", temp.resolve("config").toString(), "LOCALAPPDATA", temp.resolve("local").toString()))) {
            var outcome = app.facade().shutterCount().check(photo);
            assertTrue(outcome.isSuccess(), outcome.errors().toString());
            var result = outcome.value().orElseThrow();
            assertEquals(Status.SUCCESS, result.status(), result.reason().map(Object::toString).orElse(""));
            assertEquals(referenceModel, result.model().orElseThrow(), "camera model identified from the photo");
            var counter = result.counter().orElseThrow();
            var model = result.catalogModel().orElseThrow();
            assertEquals("ShutterCount", counter.tag());
            assertEquals(model.support().field().orElseThrow(), model.brand().label() + " " + counter.table() + " ShutterCount",
                "the counter comes from the table ExifTool documents for this model");
            System.out.printf("Real sample %s: model %s (%s), field %s, original value %s, kind %s; reference %d -> %s%n",
                result.fileName(), result.model().orElseThrow(), model.label(), counter.source(), counter.original(), counter.kind(),
                expected, counter.value() == expected ? "MATCH" : "DIFFERENT");
            result.otherCounters().forEach(other -> System.out.printf("  other counter %s = %d (%s)%n", other.source(), other.value(), other.relation()));
            assertEquals(expected, counter.value(), "same counter as the independent tool");

            // The same check through the CLI, comparing with the selected model.
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(0, cli.execute("shutter check \"" + photo + "\" model=" + referenceModel));
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Shutter count: " + NumberFormat.getIntegerInstance(Locale.ENGLISH).format(expected)), output);
            assertTrue(output.contains("The photo was taken with the selected model"), output);
        }
        assertArrayEquals(before, sha256(photo), "the photograph is never modified");
        assertEquals(modified, Files.getLastModifiedTime(photo));
    }
}
