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
        try(var bootstrap=new ApplicationBootstrap(Map.of())) {
            InventoryGuiTest.fx(() -> {
                var pane=new CalculatorPane(bootstrap.facade());var workspace=new PhotoPilotWorkspace();workspace.connectCalculator(pane);
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
                    stage.setWidth(880);stage.setHeight(600);workspace.applyCss();workspace.layout();
                    assertTrue(pane.getBoundsInParent().getWidth()<=880);assertTrue(pane.isVisible());
                } finally {stage.close();}
                return true;
            });
        }
    }
    static void fill(CalculatorPane pane,String key,String value) { ((TextField)pane.lookup("#calc-field-"+key)).setText(value); }
}
