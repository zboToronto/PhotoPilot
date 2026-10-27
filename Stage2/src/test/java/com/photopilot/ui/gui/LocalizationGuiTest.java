package com.photopilot.ui.gui;

import com.photopilot.application.EquipmentForm;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.*;
import com.photopilot.model.EquipmentType;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalizationGuiTest {
    @TempDir Path directory;
    private ApplicationBootstrap bootstrap() { return new ApplicationBootstrap(Map.of("PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString(), "PHOTOPILOT_DATA_DIR", directory.resolve("data").toString())); }
    private void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException running) { } }
    private Stage show(PhotoPilotWorkspace workspace, double width, double height) {
        var stage = new Stage(); var scene = new Scene(workspace, width, height);
        scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
        stage.setScene(scene); stage.show(); workspace.applyCss(); workspace.layout(); return stage;
    }
    private void awaitInventory(InventoryPane pane) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (InventoryGuiTest.fx(pane::busy)) {
            if (System.nanoTime() > deadline) fail("Inventory load timed out"); Thread.sleep(20);
        }
    }
    private void noMissing(Node node) {
        if (node instanceof Labeled label && label.getText() != null) assertFalse(label.getText().contains("[!"), label.getText());
        if (node instanceof TextInputControl input) assertFalse(input.getPromptText().contains("[!"), input.getPromptText());
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(this::noMissing);
    }
    private void snapshot(Node node, String name) throws Exception {
        if (node instanceof Parent parent) { parent.applyCss(); parent.layout(); }
        var image = node.snapshot(null, null); var pixels = image.getPixelReader();
        var buffered = new java.awt.image.BufferedImage((int)image.getWidth(), (int)image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, pixels.getArgb(x,y));
        javax.imageio.ImageIO.write(buffered, "png", new java.io.File("target/" + name + ".png"));
    }
    @Test void settingsUpdatesOpenWindowsPreservesInventoryDraftSelectionAndPersists() throws Exception {
        startFx(); var executor = Executors.newSingleThreadExecutor(); Stage[] stage = new Stage[1];
        InventoryPane[] inventory = new InventoryPane[1]; PhotoPilotWorkspace[] workspace = new PhotoPilotWorkspace[1];
        try (var app = bootstrap()) {
            var item = EquipmentForm.parse(EquipmentType.CAMERA, UUID.randomUUID(), Map.of("brand","品牌", "model","Model", "mount","E"), null).value().orElseThrow();
            assertTrue(app.facade().inventory().add(item, false).isSuccess());
            InventoryGuiTest.fx(() -> {
                inventory[0] = new InventoryPane(app.facade(), executor, app.localization());
                workspace[0] = new PhotoPilotWorkspace(app.localization()); workspace[0].connectInventory(inventory[0]);
                stage[0] = show(workspace[0], 1120, 760); ((ToggleButton)workspace[0].lookup("#nav-F05")).fire(); return true;
            });
            awaitInventory(inventory[0]);
            InventoryGuiTest.fx(() -> {
                var pane = inventory[0]; var shell = workspace[0];
                pane.table().getSelectionModel().selectFirst();
                ((Button)pane.lookup("#gear-edit")).fire();
                TextField model = (TextField)pane.lookup("#gear-field-model"); model.setText("Unsaved 用户 O'Brien");
                model.selectRange(2, 7);
                ((TextField)pane.lookup("#gear-search")).setText("draft search");
                long selection = shell.selectionVersion();
                var scroll = (ScrollPane)shell.lookup(".content-scroll"); scroll.setVvalue(.4);
                ((Button)shell.lookup("#settings-open")).fire();
                var settings = Window.getWindows().stream().filter(w -> w instanceof SettingsDialog).map(w -> (SettingsDialog)w).findFirst().orElseThrow();
                @SuppressWarnings("unchecked") var languages = (ComboBox<Language>)settings.getScene().lookup("#settings-language");
                languages.setValue(Language.SIMPLIFIED_CHINESE);
                assertEquals("设置", settings.getTitle());
                assertEquals("器材清单", ((ToggleButton)shell.lookup("#nav-F05")).getText());
                assertEquals("保存器材", ((Button)pane.lookup("#gear-save")).getText());
                assertEquals("Unsaved 用户 O'Brien", model.getText()); assertEquals(2, model.getSelection().getStart()); assertEquals(7, model.getSelection().getEnd());
                assertEquals("复制", model.getContextMenu().getItems().get(4).getText());
                assertEquals("draft search", ((TextField)pane.lookup("#gear-search")).getText());
                assertEquals(item.id(), pane.table().getSelectionModel().getSelectedItem().id());
                assertEquals(selection, shell.selectionVersion()); assertEquals(.4, scroll.getVvalue());
                assertEquals("类型", pane.table().getColumns().getFirst().getText());
                var second = new SettingsDialog(stage[0], app.localization()); second.show();
                assertEquals("设置", second.getTitle());
                @SuppressWarnings("unchecked") var secondLanguages = (ComboBox<Language>)second.getScene().lookup("#settings-language");
                secondLanguages.setValue(Language.ENGLISH);
                assertEquals(Language.ENGLISH, languages.getValue()); assertEquals("Settings", settings.getTitle());
                assertEquals("Copy", model.getContextMenu().getItems().get(4).getText());
                secondLanguages.setValue(Language.SIMPLIFIED_CHINESE);
                noMissing(shell); noMissing(settings.getScene().getRoot());
                snapshot(settings.getScene().getRoot(), "i18n-settings-zh");
                CalculatorGuiTest.snapshot(shell, "i18n-inventory-zh-wide");
                assertEquals("所有类型", ((ComboBox<?>)pane.lookup("#gear-filter")).getButtonCell().getText());
                scroll.setVvalue(1); CalculatorGuiTest.snapshot(shell, "i18n-inventory-zh-editor");
                second.close(); settings.close();
                assertEquals(3, app.localization().listenerCount(), "shell, feature pane and inventory only");
                stage[0].close(); assertEquals(0, app.localization().listenerCount());
                return true;
            });
            assertEquals("Model", app.facade().inventory().list("").value().orElseThrow().items().getFirst().model());
        } finally { InventoryGuiTest.fx(() -> { if (stage[0] != null) stage[0].close(); return true; }); executor.shutdownNow(); }
        try (var restarted = bootstrap()) { assertEquals(Language.SIMPLIFIED_CHINESE, restarted.localization().language()); }
    }
    @Test void calculatorResultsAndFieldsSwitchInPlaceAndBackgroundEventsUseFxThread() throws Exception {
        startFx(); var executor = Executors.newSingleThreadExecutor(); Stage[] stage = new Stage[1];
        PhotoPilotWorkspace[] shell = new PhotoPilotWorkspace[1]; CalculatorPane[] pane = new CalculatorPane[1];
        AtomicBoolean nonFxMutation = new AtomicBoolean();
        try (var app = bootstrap()) {
            InventoryGuiTest.fx(() -> {
                shell[0] = new PhotoPilotWorkspace(app.localization()); pane[0] = new CalculatorPane(app.facade(), executor, app.localization()); shell[0].connectCalculator(pane[0]);
                stage[0] = show(shell[0], 864, 561); ((ToggleButton)shell[0].lookup("#nav-F08")).fire();
                var button = (Button)pane[0].lookup("#calc-run"); button.textProperty().addListener((o, a, b) -> { if (!Platform.isFxApplicationThread()) nonFxMutation.set(true); });
                CalculatorGuiTest.fill(pane[0], "aperture", "8"); CalculatorGuiTest.fill(pane[0], "shutter", "1/128"); CalculatorGuiTest.fill(pane[0], "iso", "400");
                button.fire(); assertTrue(pane[0].feedbackText().contains("Calculated result")); return true;
            });
            CompletableFuture.runAsync(() -> app.localization().useLanguage("zh-CN")).get(10, TimeUnit.SECONDS);
            InventoryGuiTest.fx(() -> {
                assertFalse(nonFxMutation.get());
                assertEquals("计算", ((Button)pane[0].lookup("#calc-run")).getText());
                assertEquals("可选：已有器材", ((ComboBox<?>)pane[0].lookup("#calc-gear")).getButtonCell().getText());
                assertTrue(pane[0].feedbackText().contains("计算结果")); assertFalse(pane[0].feedbackText().contains("Calculated result"));
                assertEquals("1/128", ((TextField)pane[0].lookup("#calc-field-shutter")).getText());
                var tool = (ComboBox<?>)pane[0].lookup("#calc-tool"); assertEquals(0, tool.getSelectionModel().getSelectedIndex());
                CalculatorGuiTest.check(pane[0], 1, Map.of("shutter", "1/125", "ndStops", "10"), "8.192 s");
                var ndUnit = (ComboBox<?>)pane[0].lookup("#calc-nd-unit"); ndUnit.getSelectionModel().select(1);
                CalculatorGuiTest.fill(pane[0], "shutter", "1/125"); CalculatorGuiTest.fill(pane[0], "ndFactor", "8");
                ((Button)pane[0].lookup("#calc-run")).fire(); assertTrue(pane[0].feedbackText().contains("0.064 s"));
                for (int i = 0; i < 12; i++) app.localization().useLanguage(i % 2 == 0 ? "en" : "zh-CN");
                assertEquals(1, ndUnit.getSelectionModel().getSelectedIndex());
                assertEquals("8", ((TextField)pane[0].lookup("#calc-field-ndFactor")).getText());
                CalculatorGuiTest.snapshot(shell[0], "i18n-calculator-zh-narrow");
                ((ScrollPane)shell[0].lookup(".content-scroll")).setVvalue(1);
                CalculatorGuiTest.snapshot(shell[0], "i18n-calculator-zh-results");
                for (int i = 0; i < 7; i++) { tool.getSelectionModel().select(i); noMissing(shell[0]); }
                shell[0].verifyNavigation();
                stage[0].hide(); assertEquals(0, app.localization().listenerCount());
                app.localization().useLanguage("en"); stage[0].show();
                assertEquals("Technical Calculator", ((ToggleButton)shell[0].lookup("#nav-F08")).getText());
                stage[0].close(); assertEquals(0, app.localization().listenerCount()); return true;
            });
        } finally { InventoryGuiTest.fx(() -> { if (stage[0] != null) stage[0].close(); return true; }); executor.shutdownNow(); }
    }
    @Test void switchingDuringInventoryLoadUsesNewLanguageOnCompletion() throws Exception {
        startFx(); var executor = Executors.newSingleThreadExecutor(); var gate = new CountDownLatch(1);
        executor.submit(() -> { try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        Stage[] stage = new Stage[1]; InventoryPane[] pane = new InventoryPane[1];
        try (var app = bootstrap()) {
            InventoryGuiTest.fx(() -> {
                var shell = new PhotoPilotWorkspace(app.localization()); pane[0] = new InventoryPane(app.facade(), executor, app.localization()); shell.connectInventory(pane[0]);
                stage[0] = show(shell, 1120, 760); ((ToggleButton)shell.lookup("#nav-F05")).fire();
                assertTrue(pane[0].busy()); app.localization().useLanguage("zh-CN"); return true;
            });
            gate.countDown(); awaitInventory(pane[0]);
            InventoryGuiTest.fx(() -> {
                ((Button)pane[0].lookup("#gear-add")).fire();
                ((TextField)pane[0].lookup("#gear-field-mount")).setText("E");
                ((Button)pane[0].lookup("#gear-save")).fire();
                assertTrue(pane[0].feedbackText().contains("品牌和型号"));
                for (int i = 0; i < EquipmentType.values().length; i++) {
                    ((ComboBox<?>)pane[0].lookup("#gear-type")).getSelectionModel().select(i); noMissing(pane[0]);
                }
                stage[0].close(); assertEquals(0, app.localization().listenerCount()); return true;
            });
        } finally { gate.countDown(); InventoryGuiTest.fx(() -> { if (stage[0] != null) stage[0].close(); return true; }); executor.shutdownNow(); }
    }
}
