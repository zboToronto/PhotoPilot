package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm;
import com.photopilot.config.ApplicationBootstrap;
import java.util.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorGuiTest {
    @Test void realControlsCalculateValidateAndResize() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch(IllegalStateException alreadyStarted) { }
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            InventoryGuiTest.fx(() -> {
                var pane=new CalculatorPane(bootstrap.facade(),executor);var workspace=new PhotoPilotWorkspace();workspace.connectCalculator(pane);
                var stage=new Stage();var scene=new Scene(workspace,1120,760);scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());stage.setScene(scene);stage.show();
                try {
                    ((ToggleButton)workspace.lookup("#nav-F08")).fire();workspace.applyCss();workspace.layout();
                    ((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains("required"));
                    fill(pane,"aperture","8");fill(pane,"shutter","1/128");fill(pane,"iso","400");
                    ((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains("11 EV100"));
                    ((ComboBox<?>)pane.lookup("#calc-tool")).getSelectionModel().select(1);
                    fill(pane,"shutter","1/125");fill(pane,"ndStops","10");((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains("8.192 s"));
                    ((ComboBox<?>)pane.lookup("#calc-nd-unit")).getSelectionModel().select(1);
                    fill(pane,"shutter","1/125");fill(pane,"ndFactor","8");((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains("0.064 s"));
                    ((ComboBox<?>)pane.lookup("#calc-nd-unit")).getSelectionModel().select(2);
                    fill(pane,"shutter","1/125");fill(pane,"opticalDensity","3");((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains("8 s"));
                    check(pane,2,Map.of("focalLength","50","aperture","8","coc",".03","distance","20"),"Infinity m");
                    snapshot(workspace,"f08-dof-wide");
                    check(pane,3,Map.of("focalLength","50","aperture","8","coc",".03"),"10.466667 m");
                    check(pane,4,Map.of("focalLength","50","sensorWidth","36","sensorHeight","24"),"39.597753 degrees");
                    check(pane,5,Map.of("shotCount","1000","fileSize","25","cardCapacity","16"),"25 GB");
                    check(pane,6,Map.of("shotCount","1000","ratedShots","600"),"2 batteries");
                    stage.setWidth(880);stage.setHeight(600);workspace.applyCss();workspace.layout();
                    assertTrue(pane.getBoundsInParent().getWidth()<=880);assertTrue(pane.isVisible());

                } finally {stage.close();}
                return true;
            });
        } finally { executor.shutdownNow(); }
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void inventoryPrefillAndActualNarrowViewport() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch(IllegalStateException alreadyStarted) { }
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        Stage[] stages=new Stage[1];CalculatorPane[] panes=new CalculatorPane[1];PhotoPilotWorkspace[] workspaces=new PhotoPilotWorkspace[1];
        try(var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            var filter=com.photopilot.application.EquipmentForm.parse(com.photopilot.model.EquipmentType.FILTER,UUID.randomUUID(),Map.of("brand","Test","model","ND","diameterMm","77","ndStops","10"),null).value().orElseThrow();
            assertTrue(bootstrap.facade().inventory().add(filter,false).isSuccess());
            InventoryGuiTest.fx(() -> {
                var pane=new CalculatorPane(bootstrap.facade(),executor);panes[0]=pane;
                var workspace=new PhotoPilotWorkspace();workspaces[0]=workspace;workspace.connectCalculator(pane);
                var stage=new Stage();stages[0]=stage;var scene=new Scene(workspace,864,561);scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());stage.setScene(scene);stage.show();
                ((ToggleButton)workspace.lookup("#nav-F08")).fire();
                ((ComboBox<?>)pane.lookup("#calc-tool")).getSelectionModel().select(1);
                ((Button)pane.lookup("#calc-load-gear")).fire();return true;
            });
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(InventoryGuiTest.fx(() -> ((Button)panes[0].lookup("#calc-load-gear")).isDisabled())) {
                if(System.nanoTime()>deadline) fail("Inventory load timed out");Thread.sleep(20);
            }
            InventoryGuiTest.fx(() -> {
                var pane=panes[0];var workspace=workspaces[0];
                ((ComboBox<?>)pane.lookup("#calc-gear")).getSelectionModel().selectFirst();
                ((Button)pane.lookup("#calc-use-gear")).fire();assertEquals("10.0",((TextField)pane.lookup("#calc-field-ndStops")).getText());
                fill(pane,"shutter","1/125");((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains("8.192 s"));
                workspace.applyCss();workspace.layout();assertTrue(workspace.getWidth()<=880);
                var shutter=pane.lookup("#calc-field-shutter");var nd=pane.lookup("#calc-field-ndStops");
                assertTrue(nd.localToScene(nd.getBoundsInLocal()).getMinY()>shutter.localToScene(shutter.getBoundsInLocal()).getMinY(),"Fields must stack at minimum width");
                snapshot(workspace,"f08-nd-narrow");
                ((ScrollPane)workspace.lookup(".content-scroll")).setVvalue(1);snapshot(workspace,"f08-nd-narrow-results");
                ((ToggleButton)workspace.lookup("#nav-F01")).fire();assertFalse(pane.isVisible());
                ((ToggleButton)workspace.lookup("#nav-F08")).fire();assertTrue(((ComboBox<?>)pane.lookup("#calc-gear")).getItems().isEmpty());return true;
            });
        } finally {InventoryGuiTest.fx(() -> {if(stages[0]!=null) stages[0].close();return true;});executor.shutdownNow();}
    }
    static void check(CalculatorPane pane,int index,Map<String,String> fields,String expected) {
        ((ComboBox<?>)pane.lookup("#calc-tool")).getSelectionModel().select(index);fields.forEach((key,value) -> fill(pane,key,value));
        ((Button)pane.lookup("#calc-run")).fire();assertTrue(pane.feedbackText().contains(expected),pane.feedbackText());
    }
    static void snapshot(PhotoPilotWorkspace workspace,String name) throws java.io.IOException {
        workspace.applyCss();workspace.layout();
        var image=workspace.snapshot(null,null);var pixels=image.getPixelReader();
        var buffered=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) buffered.setRGB(x,y,pixels.getArgb(x,y));
        javax.imageio.ImageIO.write(buffered,"png",new java.io.File("target/"+name+".png"));
    }
    static void fill(CalculatorPane pane,String key,String value) { ((TextField)pane.lookup("#calc-field-"+key)).setText(value); }
}
