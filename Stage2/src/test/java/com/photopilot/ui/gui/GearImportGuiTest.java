package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.gearimport.ImportFixtures;
import com.photopilot.model.GearImportDraft;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.ui.privacy.ImageReview;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Requires a graphical desktop. F04 through real JavaFX controls, the shared services and offline fakes. */
class GearImportGuiTest {
    @TempDir Path directory;
    GearImportPane pane; InventoryPane inventory; PhotoPilotWorkspace workspace; Stage stage;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> pane.busy() || inventory.busy())) { if (System.nanoTime() > deadline) fail("GUI operation timed out"); Thread.sleep(20); }
    }
    void click(String id) throws Exception { action(() -> ((ButtonBase) workspace.lookup("#" + id)).fire()); }

    @Test void screenshotImportFromInventoryThroughPrivacyReviewToSavedInventoryAndCancellation() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { }
        var executor = Executors.newSingleThreadExecutor();
        try (var gemini = new FakeGemini(); var catalog = new ImportFixtures.FakeCatalog().standard()) {
            var environment = ImportFixtures.environment(directory.resolve("data"), gemini, catalog);
            Path screenshot = ImportFixtures.png(directory, "order.png", 800, 500);
            byte[] original = Files.readAllBytes(screenshot);
            try (var app = new ApplicationBootstrap(environment)) {
                action(() -> {
                    workspace = new PhotoPilotWorkspace(app.localization());
                    inventory = new InventoryPane(app.facade(), executor, app.localization());
                    pane = new GearImportPane(app.facade(), executor, app.localization(), new ImagePrivacyBoundary(), workspace::selectInventory);
                    inventory.onImport(workspace::selectImport);
                    workspace.connectInventory(inventory); workspace.connectImport(pane);
                    stage = new Stage(); var scene = new Scene(workspace, 1120, 760);
                    scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); stage.setScene(scene); stage.show();
                    ((ToggleButton) workspace.lookup("#nav-F05")).fire(); workspace.applyCss(); workspace.layout();
                });
                ready();
                click("gear-import");
                assertTrue(fx(() -> pane.isVisible()), "the inventory action opens the F04 import");
                action(() -> {
                    ((TextField) workspace.lookup("#import-path")).setText(screenshot.toString());
                    @SuppressWarnings("unchecked") var kind = (ComboBox<ImageReview.Kind>) workspace.lookup("#import-kind");
                    kind.setValue(ImageReview.Kind.SCREENSHOT);
                });
                click("import-load"); ready();
                assertTrue(fx(() -> pane.dirty()));
                assertTrue(fx(() -> pane.addRegion(true, 10, 10, 60, 20)));
                click("import-sanitize"); ready();
                Path copy = fx(() -> pane.sanitizedCopy());
                assertNotNull(copy); assertTrue(Files.exists(copy));
                assertTrue(fx(() -> ((Button) workspace.lookup("#import-recognize")).isDisabled()), "recognition requires explicit approval");
                assertTrue(gemini.requests.isEmpty());
                action(() -> ((CheckBox) workspace.lookup("#import-approve-check")).setSelected(true));
                gemini.reply(ImportFixtures.CAMERA);
                click("import-recognize"); ready();
                assertEquals(1, gemini.requests.size());
                assertEquals(0x000000, ImportFixtures.sentImage(gemini.requests.getFirst()).getRGB(40, 20) & 0xFFFFFF);
                assertFalse(Files.exists(copy), "temporary copy released");
                assertArrayEquals(original, Files.readAllBytes(screenshot));
                assertEquals(GearImportDraft.Identity.VERIFIED, fx(() -> pane.draft().identity()));
                assertTrue(fx(() -> ((Label) workspace.lookup("#import-status-mount")).getText()).startsWith("Externally verified"));
                assertTrue(fx(() -> ((Label) workspace.lookup("#import-evidence")).getText()).contains("oldid=123456"));
                assertTrue(app.facade().inventory().list("").value().orElseThrow().items().isEmpty(), "nothing saved before confirmation");
                action(() -> ((TextField) workspace.lookup("#import-field-quantity")).setText("2"));
                click("import-confirm"); ready();
                assertTrue(fx(() -> ((Label) workspace.lookup("#import-result")).getText()).contains("Imported Sony α7 IV (quantity 2)"));
                assertEquals(2, app.facade().inventory().list("").value().orElseThrow().items().getFirst().quantity());
                click("import-open-inventory"); ready();
                assertTrue(fx(() -> inventory.isVisible()));
                assertEquals(1, fx(() -> inventory.table().getItems().size()));

                // Cancellation releases the review without touching the original or the inventory.
                click("gear-import");
                action(() -> ((TextField) workspace.lookup("#import-path")).setText(screenshot.toString()));
                click("import-load"); ready();
                click("import-sanitize"); ready();
                Path second = fx(() -> pane.sanitizedCopy());
                click("import-cancel");
                assertTrue(fx(() -> pane.feedbackText()).contains("Import cancelled"));
                assertFalse(Files.exists(second)); assertTrue(Files.exists(screenshot));
                assertFalse(fx(() -> pane.dirty()));
                assertEquals(1, app.facade().inventory().list("").value().orElseThrow().items().size());
                action(() -> { stage.setWidth(880); stage.setHeight(600); workspace.applyCss(); workspace.layout(); workspace.verifyNavigation(); });
            }
            // A separate application instance (CLI) reads the GUI's import from the same storage.
            try (var other = new ApplicationBootstrap(environment)) {
                var out = new ByteArrayOutputStream();
                var cli = new PhotoPilotCLI(other.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(out, true, StandardCharsets.UTF_8));
                assertEquals(0, cli.execute("gear list"));
                assertTrue(out.toString(StandardCharsets.UTF_8).contains("Sony α7 IV | qty 2"));
            }
        } finally {
            action(() -> { if (stage != null) stage.close(); }); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
