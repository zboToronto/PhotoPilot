package com.photopilot.repository;

import com.photopilot.application.ProfileManager;
import com.photopilot.common.Result;
import com.photopilot.gearimport.ImportFixtures;
import com.photopilot.gearimport.LargeImageFixtures;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Avatar import limits (25 MB, 80 MP) and bounded decoding: header-only boundary checks, an 80 MP PNG and a 20 MP JPEG
 * decoded through their center square only, PNG transparency, specific failure reasons, and preview = saved avatar.
 */
class AvatarImportTest {
    @TempDir Path dir;
    private static final long MB = 1024L * 1024;

    private static BufferedImage decode(byte[] png) throws Exception { return ImageIO.read(new ByteArrayInputStream(png)); }
    private static byte[] ok(Result<byte[]> result) { assertTrue(result.isSuccess(), result.errors().toString()); return result.value().orElseThrow(); }

    @Test void limitsAre25MegabytesAnd80MegapixelsAtTheirExactBoundaries() throws Exception {
        assertEquals(25 * MB, AvatarRepository.MAX_INPUT_BYTES);
        assertEquals(80_000_000L, AvatarRepository.MAX_INPUT_PIXELS);
        Path small = ImportFixtures.png(dir, "small.png", 300, 200);
        Path exact = LargeImageFixtures.padded(small, dir.resolve("exact 25 MB.png"), 25 * MB);
        assertEquals(25 * MB, Files.size(exact));
        var accepted = decode(ok(ProfileManager.prepareAvatar(exact)));
        assertEquals(256, accepted.getWidth()); assertEquals(256, accepted.getHeight());
        Path over = dir.resolve("over 25 MB.png");
        try (var file = new RandomAccessFile(over.toFile(), "rw")) { file.write(Files.readAllBytes(small)); file.setLength(25 * MB + 1); }
        assertTrue(ProfileManager.prepareAvatar(over).hasError("avatar.error.tooLarge"));

        // Header-only files: no pixel data, so a "damaged" result means the dimension limits were passed.
        assertTrue(ProfileManager.prepareAvatar(LargeImageFixtures.headerOnlyPng(dir.resolve("80mp.png"), 10_000, 8000)).hasError("avatar.error.corrupt"));
        assertTrue(ProfileManager.prepareAvatar(LargeImageFixtures.headerOnlyPng(dir.resolve("80mp+.png"), 10_000, 8001)).hasError("avatar.error.dimensions"));
        assertTrue(ProfileManager.prepareAvatar(LargeImageFixtures.headerOnlyPng(dir.resolve("long.png"), 70_000, 100)).hasError("avatar.error.dimensions"),
            "more than 65,535 px on one side");
        assertTrue(ProfileManager.prepareAvatar(LargeImageFixtures.headerOnlyPng(dir.resolve("huge.png"), Integer.MAX_VALUE, Integer.MAX_VALUE))
            .hasError("avatar.error.dimensions"), "no int overflow");
    }

    @Test void an80MegapixelPngIsCroppedToItsCenterSquareWithoutDecodingTheRest() throws Exception {
        // Red margins left and right, blue center square: only the square may reach the avatar.
        Path file = LargeImageFixtures.png(dir.resolve("80mp.png"), 10_000, 8000, true, (x, y) -> x < 1000 || x >= 9000 ? 0xFF0000 : 0x0000FF);
        var avatar = decode(ok(ProfileManager.prepareAvatar(file)));
        assertEquals(256, avatar.getWidth()); assertEquals(256, avatar.getHeight());
        for (int y = 0; y < 256; y += 5) for (int x = 0; x < 256; x += 5) {
            int rgb = avatar.getRGB(x, y);
            assertTrue(((rgb >> 16) & 0xFF) < 40 && (rgb & 0xFF) > 200, "only the blue center square at " + x + "," + y);
        }
    }

    @Test void a20MegapixelJpegAboveTheOldLimitIsAccepted() throws Exception {
        var image = new BufferedImage(5472, 3648, BufferedImage.TYPE_3BYTE_BGR);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.RED); graphics.fillRect(0, 0, 5472, 3648); graphics.setColor(Color.GREEN); graphics.fillRect(912, 0, 3648, 3648); }
        finally { graphics.dispose(); }
        Path file = dir.resolve("20mp.jpg");
        ImageIO.write(image, "jpeg", file.toFile());
        var avatar = decode(ok(ProfileManager.prepareAvatar(file)));
        int center = avatar.getRGB(128, 128);
        assertTrue(((center >> 8) & 0xFF) > 200 && ((center >> 16) & 0xFF) < 60, "the green center square");
        assertEquals(0xFF, (avatar.getRGB(2, 128) >>> 24), "a JPEG avatar is opaque");
    }

    @Test void pngTransparencyIsKept() throws Exception {
        var image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(Color.ORANGE); graphics.fillOval(250, 150, 100, 100); } finally { graphics.dispose(); }
        Path file = dir.resolve("transparent.png");
        ImageIO.write(image, "png", file.toFile());
        var avatar = decode(ok(ProfileManager.prepareAvatar(file)));
        assertEquals(0, avatar.getRGB(3, 3) >>> 24, "transparent corner stays transparent");
        assertEquals(0xFF, avatar.getRGB(128, 128) >>> 24, "opaque center stays opaque");
    }

    @Test void eachFailureNamesItsReasonAndThePreviewIsExactlyWhatIsSaved() throws Exception {
        assertTrue(ProfileManager.prepareAvatar(Files.writeString(dir.resolve("text.png"), "not an image")).hasError("avatar.error.format"));
        Path gif = dir.resolve("anim.png"); ImageIO.write(ImportFixtures.image(64, 64), "gif", gif.toFile());
        assertTrue(ProfileManager.prepareAvatar(gif).hasError("avatar.error.format"));
        byte[] valid = Files.readAllBytes(ImportFixtures.png(dir, "valid.png", 200, 200));
        assertTrue(ProfileManager.prepareAvatar(Files.write(dir.resolve("truncated.png"), java.util.Arrays.copyOf(valid, 60))).hasError("avatar.error.corrupt"));
        assertTrue(ProfileManager.prepareAvatar(Files.write(dir.resolve("empty.png"), new byte[0])).hasError("avatar.error.corrupt"));
        assertTrue(ProfileManager.prepareAvatar(LargeImageFixtures.losslessProcessJpeg(dir.resolve("lossless.jpg"))).hasError("avatar.error.decoder"));
        assertTrue(ProfileManager.prepareAvatar(dir.resolve("missing.png")).hasError("avatar.failed"));

        Path source = ImportFixtures.jpeg(dir, "portrait.jpg", 4000, 3000);
        byte[] preview = ok(ProfileManager.prepareAvatar(source));
        try (var manager = new ProfileManager(Map.of("PHOTOPILOT_DATA_DIR", dir.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", dir.resolve("config").toString()))) {
            var id = manager.activeProfile().value().orElseThrow().id();
            assertTrue(manager.saveAvatar(id, preview).isSuccess());
            byte[] saved = manager.avatar(id).value().orElseThrow().orElseThrow();
            var shown = decode(preview); var stored = decode(saved);
            assertEquals(256, stored.getWidth()); assertEquals(256, stored.getHeight());
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) assertEquals(shown.getRGB(x, y), stored.getRGB(x, y), "pixel " + x + "," + y);
        }
    }
}
