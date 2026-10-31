package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.repository.JsonSessionRepository;
import com.photopilot.session.SessionFixtures;
import com.photopilot.state.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JavaFX controls, worker callbacks, separate app instances and rendered shared layouts. */
class SessionGuiTest {
    @TempDir Path root;
    SessionPane pane; PhotoPilotWorkspace workspace; Stage stage;
    Map<String,String> environment() { return Map.of("PHOTOPILOT_DATA_DIR", root.toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(() -> pane.busy())) { if (System.nanoTime() > deadline) fail("Session operation timed out"); Thread.sleep(20); }
    }
    void click(String id) throws Exception { action(() -> ((Button)pane.lookup("#" + id)).fire()); }
    void field(String key, String value) throws Exception { action(() -> ((TextInputControl)pane.lookup("#session-field-" + key)).setText(value)); }
    void launch(ApplicationBootstrap app, ExecutorService executor) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { }
        action(() -> {
            pane = new SessionPane(app.facade(), executor, app.localization()); workspace = new PhotoPilotWorkspace(app.localization()); workspace.connectSessions(pane);
            workspace.connectInventory(new InventoryPane(app.facade(), executor, app.localization()));
            workspace.connectCalculator(new CalculatorPane(app.facade(), executor, app.localization()));
            stage = new Stage(); var scene = new Scene(workspace,1120,760);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
            workspace.applyCss(); workspace.layout();
        }); ready();
    }
    void snapshot(String name) throws Exception {
        fx(() -> {
            workspace.applyCss(); workspace.layout(); var image = workspace.snapshot(null, null);
            var buffered = new java.awt.image.BufferedImage((int)image.getWidth(), (int)image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x,y,image.getPixelReader().getArgb(x,y));
            javax.imageio.ImageIO.write(buffered, "png", Path.of("target", name + ".png").toFile()); return true;
        });
    }
    @Test void guiCrudDraftRetentionLocalizationRestartAndVisualLayouts() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var app = new ApplicationBootstrap(environment())) {
            launch(app, executor); assertEquals(0, fx(() -> pane.table().getItems().size()));
            click("session-create"); click("session-save"); assertTrue(fx(() -> pane.feedbackText().contains("date")));
            field("title", "GUI 夜景"); field("date", "2026-11-01"); field("location", "Harbour"); field("notes", "Long exposure\n保留文字");
            assertTrue(fx(() -> pane.dirty()));
            action(() -> ((ToggleButton)workspace.lookup("#nav-F08")).fire());
            action(() -> ((ToggleButton)workspace.lookup("#nav-F01")).fire()); ready();
            assertEquals("GUI 夜景", fx(() -> ((TextField)pane.lookup("#session-field-title")).getText()));
            click("session-create"); click("session-confirm-cancel"); assertTrue(fx(() -> pane.dirty()));
            click("session-save"); ready(); assertFalse(fx(() -> pane.dirty())); assertEquals(1, fx(() -> pane.table().getItems().size()));
            var stored = app.facade().sessions().search("").value().orElseThrow().getFirst();
            field("notes", "Edited GUI goals"); click("session-save"); ready();
            assertEquals("Edited GUI goals", app.facade().sessions().open(stored.id()).value().orElseThrow().notes());
            click("session-transition"); ready(); assertTrue(fx(() -> pane.feedbackText().contains("not allowed")));
            action(() -> ((TextField)pane.lookup("#session-search")).setText("unmatched")); click("session-refresh"); ready(); assertEquals(0, fx(() -> pane.table().getItems().size()));
            action(() -> ((TextField)pane.lookup("#session-search")).clear()); click("session-refresh"); ready();
            action(() -> ((ScrollPane)workspace.lookup(".content-scroll")).setVvalue(0)); snapshot("f01-history-wide");
            action(() -> { app.localization().useLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); });
            action(() -> ((ScrollPane)workspace.lookup(".content-scroll")).setVvalue(1));
            snapshot("f01-editor-narrow-zh");
            field("title", "Unsaved retained"); assertTrue(fx(() -> pane.preventClose(() -> { throw new AssertionError("Must not close before confirmation"); })));
            click("session-confirm-cancel"); assertTrue(fx(() -> pane.dirty()));
            action(() -> pane.table().getSelectionModel().selectFirst()); click("session-open"); click("session-confirm"); ready();
            assertEquals("GUI 夜景", fx(() -> ((TextField)pane.lookup("#session-field-title")).getText()));
            action(() -> stage.close());
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10,TimeUnit.SECONDS); }
        // A new bootstrap and Stage restore the real stored record with no retained in-memory service.
        var restartedExecutor = Executors.newSingleThreadExecutor();
        try (var restarted = new ApplicationBootstrap(environment())) {
            launch(restarted, restartedExecutor); action(() -> pane.table().getSelectionModel().selectFirst()); click("session-open"); ready();
            assertEquals("Edited GUI goals", fx(() -> ((TextArea)pane.lookup("#session-field-notes")).getText()));
            click("session-delete"); click("session-confirm-cancel"); assertEquals(1, restarted.facade().sessions().search("").value().orElseThrow().size());
            click("session-delete"); click("session-confirm"); ready(); assertEquals(0, fx(() -> pane.table().getItems().size()));
        } finally { action(() -> stage.close()); restartedExecutor.shutdownNow(); restartedExecutor.awaitTermination(10,TimeUnit.SECONDS); }
    }
    @Test void staleEditsCorruptStorageAndLifecycleKeepUserDataSafe() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var app = new ApplicationBootstrap(environment())) {
            var fixture = new JsonSessionRepository(root.resolve("sessions")).insert(SessionFixtures.linked(new PlannedState(), List.of(), false));
            assertTrue(fixture.isSuccess(), fixture.errors().toString()); var id = fixture.value().orElseThrow().id();
            launch(app, executor); action(() -> pane.open(id)); ready(); click("session-transition"); ready();
            assertEquals("COMPLETED", app.facade().sessions().open(id).value().orElseThrow().status());
            field("title", "Local draft");
            try (var other = new ApplicationBootstrap(environment())) {
                var current = other.facade().sessions().open(id).value().orElseThrow();
                assertTrue(other.facade().sessions().update(current, "Other process", current.date(), current.location(), current.notes()).isSuccess());
            }
            click("session-save"); ready(); assertTrue(fx(() -> pane.feedbackText().contains("changed")));
            assertEquals("Local draft", fx(() -> ((TextField)pane.lookup("#session-field-title")).getText()));
            Path file = root.resolve("sessions/sessions.json"); String valid = Files.readString(file); Files.writeString(file, "broken");
            click("session-save"); ready(); assertTrue(fx(() -> pane.dirty())); assertEquals("broken", Files.readString(file));
            Files.writeString(file, valid);
        } finally { action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); executor.awaitTermination(10,TimeUnit.SECONDS); }
    }
}
