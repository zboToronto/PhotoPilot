package com.photopilot.inventory;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.review.ExifFixtures;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The shutter CLI command runs the same service as the Camera Shutter Count page, with the bundled ExifTool. */
class ShutterCliTest {
    @TempDir Path temp;

    @Test void modelsAndChecksThroughTheRealDispatcher() throws Exception {
        try (var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", temp.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", temp.resolve("config").toString()))) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
            assertEquals(0, cli.execute("help"));
            assertEquals(0, cli.execute("shutter help"));
            assertEquals(0, cli.execute("shutter models sony alpha 7r"));
            assertEquals(0, cli.execute("shutter models nikon z6"));
            assertEquals(2, cli.execute("shutter models canon"));
            assertEquals(2, cli.execute("shutter models sony qwertyuiop"));
            assertEquals(2, cli.execute("shutter check"));
            assertEquals(2, cli.execute("shutter check \"" + temp.resolve("missing.ARW") + "\""));
            Path jpeg = ExifFixtures.jpeg(temp.resolve("照片 dir"), "a7rv.jpg", ExifFixtures.airshow(400, 2500, 800, "2026:01:02 03:04:05").withCamera("SONY", "ILCE-7RM5"));
            assertEquals(2, cli.execute("shutter check \"" + jpeg + "\" model=\"A7R V\""), "no counter in an edited JPEG");
            assertEquals(2, cli.execute("shutter check \"" + jpeg + "\" model=D850"));
            assertEquals(2, cli.execute("shutter check \"" + jpeg + "\" model=NoSuchCamera"));
            assertEquals(2, cli.execute("shutter frobnicate"));
            String output = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("shutter help: shutter count checker"), output);
            assertTrue(output.contains("shutter check <photo>"), output);
            assertTrue(output.contains("Alpha 7R series · Alpha (E-mount)"), output);
            assertTrue(output.contains("- α7R V (ILCE-7RM5) — Verified with a real photo"), output);
            assertTrue(output.contains("- α7 IV (ILCE-7M4)") == false, "the search narrows the list");
            assertTrue(output.contains("- Z 6II (Z 6_2) — Documented by ExifTool"), output);
            assertTrue(output.contains("Unknown brand: canon"), output);
            assertTrue(output.contains("0 model(s)."), output);
            assertTrue(output.contains("Usage: shutter check"), output);
            assertTrue(output.contains("missing.ARW was not found."), output);
            assertTrue(output.contains("Status: No maker notes"), output);
            assertTrue(output.contains("Camera: Sony ILCE-7RM5"), output);
            assertTrue(output.contains("The photo was taken with the selected model, α7R V (ILCE-7RM5)."), output);
            assertTrue(output.contains("This JPEG has no manufacturer maker notes"), output);
            assertTrue(output.contains("Brand mismatch: the photo was taken with a Sony ILCE-7RM5"), output);
            assertTrue(output.contains("Unknown camera model: NoSuchCamera"), output);
            assertFalse(output.contains("Shutter count: 0"), "never a substituted zero");
            assertFalse(output.contains(temp.toString()), "messages name the file, never the folder");

            app.localization().useLanguage("zh-CN");
            bytes.reset();
            assertEquals(2, cli.execute("shutter check \"" + temp.resolve("missing.ARW") + "\""));
            assertEquals(2, cli.execute("shutter check \"" + jpeg + "\""));
            String chinese = bytes.toString(StandardCharsets.UTF_8);
            assertTrue(chinese.contains("找不到文件 missing.ARW。"), chinese);
            assertTrue(chinese.contains("状态：缺少厂商元数据"), chinese);
            assertTrue(chinese.contains("该 JPEG 不含相机厂商的 MakerNotes 元数据"), chinese);
        }
    }
}
