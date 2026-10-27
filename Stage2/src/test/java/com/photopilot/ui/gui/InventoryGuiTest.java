package com.photopilot.ui.gui;

import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Requires a graphical desktop. Exercises real JavaFX controls and worker callbacks. */
class InventoryGuiTest {
    @TempDir Path directory;
    InventoryPane pane; PhotoPilotWorkspace workspace; Stage stage;
    static <T> T fx(Callable<T> action) throws Exception {
        var result=new CompletableFuture<T>();
        Platform.runLater(() -> { try { result.complete(action.call()); } catch(Throwable e) { result.completeExceptionally(e); } });
        return result.get(10,TimeUnit.SECONDS);
    }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    void ready() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(fx(() -> pane.busy())) { if(System.nanoTime()>deadline) fail("GUI operation timed out"); Thread.sleep(20); }
    }
    void click(String id) throws Exception { action(() -> ((Button)pane.lookup("#"+id)).fire()); }
    void field(String id,String value) throws Exception { action(() -> ((TextField)pane.lookup("#gear-field-"+id)).setText(value)); }
    @Test void desktopCrudSearchDuplicateReviewCancellationAndCliRefresh() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch(IllegalStateException alreadyStarted) { }
        var executor=Executors.newSingleThreadExecutor();
        try(var bootstrap=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString()))) {
            action(() -> {
                pane=new InventoryPane(bootstrap.facade(),executor); workspace=new PhotoPilotWorkspace();workspace.connectInventory(pane);
                stage=new Stage();var scene=new Scene(workspace,1120,760);scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());stage.setScene(scene);stage.show();
                ((ToggleButton)workspace.lookup("#nav-F05")).fire();workspace.applyCss();workspace.layout();
            });
            ready(); assertEquals(0,fx(() -> pane.table().getItems().size()));
            click("gear-add");click("gear-save");assertTrue(fx(() -> pane.feedbackText().contains("required")));
            field("brand","GUI Brand");field("model","GUI Camera");field("mount","E");click("gear-save");ready();
            assertEquals(1,fx(() -> pane.table().getItems().size()));
            action(() -> pane.table().getSelectionModel().selectFirst());click("gear-edit");field("model","Edited Camera");click("gear-save");ready();
            assertEquals("Edited Camera",bootstrap.facade().inventory().list("").value().orElseThrow().items().getFirst().model());
            action(() -> ((TextField)pane.lookup("#gear-search")).setText("no match"));click("gear-refresh");ready();assertEquals(0,fx(() -> pane.table().getItems().size()));
            action(() -> ((TextField)pane.lookup("#gear-search")).clear());click("gear-refresh");ready();
            click("gear-add");field("brand","GUI Brand");field("model","Edited Camera");field("mount","E");click("gear-save");ready();
            assertTrue(fx(() -> pane.feedbackText().contains("Possible duplicate")));
            action(() -> ((CheckBox)pane.lookup("#gear-duplicate-confirm")).setSelected(true));click("gear-save");ready();assertEquals(2,fx(() -> pane.table().getItems().size()));
            action(() -> pane.table().getSelectionModel().selectFirst());click("gear-remove");ready();click("gear-cancel-remove");assertEquals(2,bootstrap.facade().inventory().list("").value().orElseThrow().items().size());
            click("gear-remove");ready();click("gear-confirm-remove");ready();assertEquals(1,fx(() -> pane.table().getItems().size()));
            // Independent application instance simulates the other interface/process.
            try(var other=new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR",directory.toString()))) {
                var cli=new PhotoPilotCLI(other.facade(),new ImagePrivacyBoundary(),new Scanner(""),new PrintStream(new ByteArrayOutputStream()));
                assertEquals(0,cli.execute("gear add lens brand=CLI model=Zoom mount=E minFocalMm=24 maxFocalMm=70 maxAperture=2.8"));
            }
            click("gear-refresh");ready();assertEquals(2,fx(() -> pane.table().getItems().size()));
            action(() -> ((ComboBox<?>)pane.lookup("#gear-filter")).getSelectionModel().select(2));ready();assertEquals(1,fx(() -> pane.table().getItems().size()));
            action(() -> {stage.setWidth(880);stage.setHeight(600);workspace.applyCss();workspace.layout();workspace.verifyNavigation();});
            assertTrue(fx(() -> stage.isShowing()));
        } finally {
            action(() -> { if(stage!=null)stage.close(); });executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));
        }
    }
}
