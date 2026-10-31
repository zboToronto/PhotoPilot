package com.photopilot.ui.gui;

import com.photopilot.i18n.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageNoticeGuiTest {
    @Test void legacyNoticeRemainsVisibleAcrossNavigationAndLanguageChanges() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        InventoryGuiTest.fx(() -> {
            var localization = LocalizationService.english(); var workspace = new PhotoPilotWorkspace(localization);
            var stage = new Stage(); var scene = new Scene(workspace, 880, 600);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene);
            try {
                stage.show();
                var notice = (Label) workspace.lookup("#storage-notice"); assertFalse(notice.isManaged());
                workspace.showStorageNotice(LocalizedMessage.of("storage.legacyFound", "C:/PhotoPilot/Stage2/data", "C:/Users/Example/.photopilot/data"));
                workspace.verifyNavigation(); assertTrue(notice.isVisible()); assertTrue(notice.isManaged());
                assertTrue(notice.getText().contains("PHOTOPILOT_DATA_DIR"));
                localization.useLanguage("zh-CN"); workspace.applyCss(); workspace.layout();
                assertTrue(notice.getText().contains("旧数据目录"));
                assertTrue(notice.getBoundsInParent().getHeight() > 20);
                var image = workspace.snapshot(null, null);
                var buffered = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                javax.imageio.ImageIO.write(buffered, "png", Path.of("target/phase2-storage-notice.png").toFile());
                return true;
            } finally { stage.close(); }
        });
    }
}
