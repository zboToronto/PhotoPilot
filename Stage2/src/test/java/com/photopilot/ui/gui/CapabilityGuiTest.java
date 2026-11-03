package com.photopilot.ui.gui;

import com.photopilot.application.EquipmentForm;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityGuiTest {
    @TempDir Path directory;
    @Test void actualWorkspaceControlsRefreshLocalizeResizeAndClearFailures() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        var executor = Executors.newSingleThreadExecutor();
        Stage[] stage = new Stage[1]; CapabilityPane[] pane = new CapabilityPane[1]; PhotoPilotWorkspace[] shell = new PhotoPilotWorkspace[1];
        try(var app = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString(),"PHOTOPILOT_CONFIG_DIR",directory.resolve("config").toString()))) {
            InventoryGuiTest.fx(() -> {
                shell[0] = new PhotoPilotWorkspace(app.localization());
                pane[0] = new CapabilityPane(app.facade(),executor,app.localization()); shell[0].connectCapabilities(pane[0]);
                stage[0] = new Stage(); var scene = new Scene(shell[0],1120,760);
                scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage[0].setScene(scene); stage[0].show();
                ((ToggleButton)shell[0].lookup("#nav-F06")).fire(); return true;
            });
            await(pane[0]);
            InventoryGuiTest.fx(() -> { assertTrue(text(pane[0],"overview").contains("No equipment yet")); return true; });
            Equipment lens = EquipmentForm.parse(EquipmentType.LENS,UUID.randomUUID(),Map.of("brand","Fixture","model","20-70","mount","E","minFocalMm","20","maxFocalMm","70","maxAperture","2.8","maxApertureAtMaxFocal","4"),null).value().orElseThrow();
            assertTrue(app.facade().inventory().add(lens,false).isSuccess());
            InventoryGuiTest.fx(() -> { ((Button)pane[0].lookup("#capability-refresh")).fire(); return true; }); await(pane[0]);
            InventoryGuiTest.fx(() -> {
                assertTrue(text(pane[0],"coverage").contains("20–70 mm")); assertTrue(text(pane[0],"aperture").contains("f/4"));
                CalculatorGuiTest.snapshot(shell[0],"f06-wide");
                ((ScrollPane)shell[0].lookup(".content-scroll")).setVvalue(1);CalculatorGuiTest.snapshot(shell[0],"f06-wide-results");
                app.localization().useLanguage("zh-CN"); stage[0].setWidth(880);stage[0].setHeight(600); return true;
            });
            Thread.sleep(200);
            InventoryGuiTest.fx(() -> {
                shell[0].applyCss(); shell[0].layout(); assertTrue(text(pane[0],"coverage").contains("覆盖区间"));
                for(String section:List.of("overview","coverage","aperture","compatibility","summary")) {
                    var label=pane[0].lookup("#capability-"+section);var bounds=label.localToScene(label.getBoundsInLocal());
                    assertTrue(bounds.getMaxX()<=shell[0].getScene().getWidth(),section+" clipped horizontally");
                }
                CalculatorGuiTest.snapshot(shell[0],"f06-zh-narrow-results");
                ((ScrollPane)shell[0].lookup(".content-scroll")).setVvalue(0);CalculatorGuiTest.snapshot(shell[0],"f06-zh-narrow");
                ((ToggleButton)shell[0].lookup("#nav-F05")).fire();assertFalse(pane[0].isVisible()); return true;
            });
            assertTrue(app.facade().inventory().remove(lens.id(),false).isSuccess());
            InventoryGuiTest.fx(() -> { ((ToggleButton)shell[0].lookup("#nav-F06")).fire();return true; });await(pane[0]);
            InventoryGuiTest.fx(() -> { assertFalse(text(pane[0],"coverage").contains("20–70"));return true; });
            java.nio.file.Files.writeString(app.storageScope().inventoryFile(),"malformed");
            InventoryGuiTest.fx(() -> { ((Button)pane[0].lookup("#capability-refresh")).fire();return true; });await(pane[0]);
            InventoryGuiTest.fx(() -> { assertNull(pane[0].lookup("#capability-coverage")); assertFalse(pane[0].feedbackText().isBlank()); return true; });
        } finally {
            InventoryGuiTest.fx(() -> {if(pane[0]!=null)pane[0].dispose();if(stage[0]!=null)stage[0].close();return true;});executor.shutdownNow();
        }
    }
    private static String text(CapabilityPane pane,String section) { return ((Label)pane.lookup("#capability-"+section)).getText(); }
    private static void await(CapabilityPane pane) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(InventoryGuiTest.fx(() -> ((Button)pane.lookup("#capability-refresh")).isDisabled())) {
            if(System.nanoTime()>deadline) fail("Capability analysis timed out");Thread.sleep(20);
        }
    }
}
