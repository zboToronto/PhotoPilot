package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import java.nio.file.Path;
import java.util.Map;
import javafx.application.Platform;
import javafx.scene.image.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The generated logo set loads from the classpath and every PhotoPilot window carries it. */
class AppIconsGuiTest {
    @TempDir Path root;
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }

    @Test void logoResourcesDecodeAtTheirSizeWithTransparency() throws Exception {
        var images = InventoryGuiTest.fx(AppIcons::logoImages);
        int[] sizes = { 16, 32, 48, 64, 128, 256, 512 };
        assertEquals(sizes.length, images.size());
        for (int i = 0; i < sizes.length; i++) {
            Image image = images.get(i); int size = sizes[i];
            assertFalse(image.isError(), "photopilot-" + size + ".png failed to decode");
            assertEquals(size, image.getWidth()); assertEquals(size, image.getHeight());
            PixelReader pixels = image.getPixelReader();
            assertEquals(0, pixels.getArgb(0, 0) >>> 24, "corner should stay transparent at " + size);
            assertEquals(255, pixels.getArgb(size / 2, size / 2) >>> 24, 2, "centre should stay opaque at " + size);
        }
    }

    @Test void chooserAndDialogWindowsUseTheLogo() throws Exception {
        var expected = InventoryGuiTest.fx(AppIcons::logoImages);
        InventoryGuiTest.fx(() -> {
            var owner = new Stage(); owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.Pane())); AppIcons.windowIcon(owner);
            var chooser = new UserSelectionWindow(LocalizationService.english());
            var error = AppDialogs.error(owner, LocalizationService.english(), "profile.errorTitle");
            var startup = PhotoPilotGUI.startupError(new IllegalArgumentException("broken"));
            var recovery = new ProfileRecoveryDialog(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(),
                "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()), new IllegalArgumentException("broken"));
            try {
                assertEquals(expected, owner.getIcons()); assertEquals(expected, chooser.getIcons());
                for (var dialog : java.util.List.of(error, startup, recovery))
                    assertEquals(expected, ((Stage) dialog.getDialogPane().getScene().getWindow()).getIcons());
                return true;
            } finally { chooser.dispose(); }
        });
    }
}
