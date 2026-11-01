package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.model.LocalProfile;
import com.photopilot.repository.StorageScope;
import com.photopilot.ui.cli.PhotoPilotCLI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real desktop controls and child CLI JVMs; every record lives in isolated temporary storage. */
class ProfileGuiTest {
    @TempDir Path root;
    ProfileManager manager; GuiProfileController controller; Stage stage;
    Map<String,String> env() { return Map.of("PHOTOPILOT_DATA_DIR",root.resolve("data").toString(),"PHOTOPILOT_CONFIG_DIR",root.resolve("config").toString()); }
    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeEach void launch() throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch(IllegalStateException started) { }
        manager=new ProfileManager(env()); startWindow();
    }
    void startWindow() throws Exception {
        LocalProfile initial=manager.activeProfile().value().orElseThrow();
        action(() -> { stage=new Stage(); controller=new GuiProfileController(stage,manager,initial,context -> {}); stage.show(); }); ready();
    }
    @AfterEach void close() throws Exception { action(() -> { if(controller!=null)controller.close(); if(stage!=null)stage.close(); }); if(manager!=null)manager.close(); }
    void ready() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(fx(() -> controller.busy())) { if(System.nanoTime()>deadline)fail("Profile GUI timed out"); Thread.sleep(20); }
        action(() -> { controller.workspace().applyCss(); controller.workspace().layout(); });
    }
    ProfileDialog dialog() { return Window.getWindows().stream().filter(w -> w instanceof ProfileDialog && w.isShowing()).map(w -> (ProfileDialog)w).findFirst().orElseThrow(); }
    Parent panel() { return dialog().getScene().getRoot(); }
    @SuppressWarnings("unchecked") ListView<LocalProfile> list() { return (ListView<LocalProfile>)panel().lookup("#profile-list"); }
    void profiles() throws Exception { action(() -> ((Button)controller.workspace().lookup("#profiles-open")).fire()); ready(); }
    void click(String id) throws Exception { action(() -> ((Button)panel().lookup("#"+id)).fire()); ready(); }
    void name(String value) throws Exception { action(() -> ((TextField)panel().lookup("#profile-name")).setText(value)); }
    UUID create(String name) throws Exception { name(name); click("profile-create"); return fx(() -> list().getSelectionModel().getSelectedItem().id()); }
    void select(UUID id) throws Exception { action(() -> list().getItems().stream().filter(p -> p.id().equals(id)).findFirst().ifPresent(list().getSelectionModel()::select)); }
    void nav(String feature) throws Exception { action(() -> ((ToggleButton)controller.workspace().lookup("#nav-"+feature)).fire()); ready(); }
    void mainClick(String id) throws Exception { action(() -> ((Button)controller.workspace().lookup("#"+id)).fire()); ready(); }
    void field(String id,String text) throws Exception { action(() -> ((TextInputControl)controller.workspace().lookup("#"+id)).setText(text)); }
    UUID active() { return manager.activeProfile().value().orElseThrow().id(); }
    void confirm(Runnable trigger,boolean yes) throws Exception {
        action(() -> {
            Platform.runLater(() -> {
                DialogPane pane=(DialogPane)Window.getWindows().stream().filter(Window::isShowing).map(w -> w.getScene().getRoot().lookup("#profile-confirmation")).filter(Objects::nonNull).findFirst().orElseThrow();
                var type=pane.getButtonTypes().stream().filter(b -> yes ? b.getButtonData()==ButtonBar.ButtonData.OK_DONE : b.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
                ((Button)pane.lookupButton(type)).fire();
            }); trigger.run();
        }); ready();
    }
    @Test void controlsCreateSelectCrossProcessRecordsRenameDeleteAndRestart() throws Exception {
        UUID defaultId=active(); profiles(); assertEquals(1,fx(() -> list().getItems().size()));
        UUID a=create("A 夜景"), b=create("B Portrait"); assertNotEquals(a,b);
        select(a); click("profile-enter"); assertEquals(a,active());
        assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("A 夜景")));
        nav("F05"); mainClick("gear-add"); field("gear-field-brand","GUI"); field("gear-field-model","A Camera"); field("gear-field-mount","E"); mainClick("gear-save");
        nav("F01"); mainClick("session-create"); field("session-field-title","A Session"); field("session-field-date","2026-11-01"); mainClick("session-save");
        var record=manager.activeContext().facade().sessions().search("").value().orElseThrow().getFirst();
        String cli=process("--profile="+a,"profile list","gear list","session list"); assertTrue(cli.contains("A Camera")); assertTrue(cli.contains("A Session")); assertTrue(cli.contains(b.toString()));
        process("--profile="+a,"session edit " + record.id() + " title=\"CLI Edited\"");
        mainClick("session-refresh"); assertEquals("CLI Edited",fx(() -> ((com.photopilot.model.PhotographySession)((TableView<?>)controller.workspace().lookup("#session-table")).getItems().getFirst()).title()));
        profiles(); select(b); click("profile-enter"); assertEquals(b,active());
        assertTrue(manager.activeContext().facade().inventory().list("").value().orElseThrow().items().isEmpty());
        assertEquals(0,fx(() -> ((TableView<?>)controller.workspace().lookup("#session-table")).getItems().size()));
        mainClick("session-create"); field("session-field-title","B Session"); field("session-field-date","2026-11-02"); mainClick("session-save");
        Path bFile=manager.activeContext().storageScope().sessionsDirectory().resolve("sessions.json"); byte[] bData=Files.readAllBytes(bFile);
        profiles(); name("B Renamed"); click("profile-rename"); assertEquals(b,active()); assertArrayEquals(bData,Files.readAllBytes(bFile));
        assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("B Renamed")));
        select(a); click("profile-enter"); assertEquals("CLI Edited",manager.activeContext().facade().sessions().search("").value().orElseThrow().getFirst().title());
        nav("F05"); assertEquals(1,fx(() -> ((TableView<?>)controller.workspace().lookup("#gear-table")).getItems().size()));
        profiles(); select(b); confirm(() -> ((Button)panel().lookup("#profile-delete")).fire(),false); assertTrue(manager.getProfile(b).isSuccess());
        confirm(() -> ((Button)panel().lookup("#profile-delete")).fire(),true); assertFalse(manager.getProfile(b).isSuccess()); assertArrayEquals(bData,Files.readAllBytes(bFile));
        action(() -> {controller.close();stage.close();}); manager.close(); manager=new ProfileManager(env()); assertEquals(defaultId,active()); startWindow();
        profiles(); select(a); click("profile-enter"); assertEquals("CLI Edited",manager.activeContext().facade().sessions().search("").value().orElseThrow().getFirst().title());
        assertEquals(1,manager.activeContext().facade().inventory().list("").value().orElseThrow().items().size());
    }
    @Test void failedSwitchAndCancelRetainDraftThenSuccessRebuildsAndRevokesOldContext() throws Exception {
        UUID a=active(), b=manager.createProfile("B").value().orElseThrow().id();
        mainClick("session-create"); field("session-field-title","Unsaved A"); assertTrue(fx(() -> controller.dirty()));
        var old=manager.activeContext(); var oldView=fx(() -> controller.workspace());
        profiles();
        Path activeFile=old.storageScope().inventoryFile(); Files.writeString(activeFile,"broken active");
        click("profile-enter"); assertSame(oldView,fx(() -> controller.workspace())); assertEquals(a,active());
        assertTrue(fx(() -> ((Label)panel().lookup("#profile-feedback")).getText().contains("failed")));
        assertEquals("broken active",Files.readString(activeFile)); Files.delete(activeFile);
        select(b); confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),false);
        assertEquals(a,active()); assertSame(oldView,fx(() -> controller.workspace()));
        Path file=StorageScope.forProfile(root.resolve("data"),b).inventoryFile(); assertFalse(Files.exists(file)); Files.writeString(file,"broken");
        confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),true);
        assertEquals(a,active()); assertSame(oldView,fx(() -> controller.workspace())); assertEquals("Unsaved A",fx(() -> ((TextField)oldView.lookup("#session-field-title")).getText()));
        assertTrue(fx(() -> ((Label)panel().lookup("#profile-feedback")).getText().contains("failed"))); assertEquals("broken",Files.readString(file));
        Files.delete(file); confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),true);
        assertEquals(b,active()); assertNotSame(oldView,fx(() -> controller.workspace())); assertFalse(fx(() -> controller.dirty()));
        assertEquals(0,old.localization().listenerCount()); assertFalse(old.facade().inventory().list("").isSuccess());
        assertEquals(0,fx(() -> ((TableView<?>)controller.workspace().lookup("#session-table")).getItems().size()));
    }
    @Test void inventoryCalculatorDraftsAndWindowCloseRequireResolution() throws Exception {
        UUID a=active(),b=manager.createProfile("B").value().orElseThrow().id(); nav("F05"); mainClick("gear-add"); field("gear-field-model","Unsaved camera");
        profiles(); select(b); confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),false); assertEquals(a,active());
        confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),true); assertEquals(b,active());
        nav("F08"); action(() -> ((TextField)controller.workspace().lookupAll(".text-field").iterator().next()).setText("42")); assertTrue(fx(() -> controller.dirty()));
        confirm(() -> stage.fireEvent(new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST)),false); assertTrue(fx(() -> stage.isShowing()));
        profiles(); select(a); confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),true); assertFalse(fx(() -> controller.dirty())); assertEquals(a,active());
    }
    @Test void pendingWorkerAndCompletionBlockSwitchUntilOriginalOperationFinishes() throws Exception {
        UUID a=active(),b=manager.createProfile("B").value().orElseThrow().id(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var old=manager.activeContext();
        action(() -> controller.operations().execute(() -> { entered.countDown(); try { release.await(10,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            assertTrue(old.facade().sessions().create("Pending A",java.time.LocalDate.of(2026,11,1)).isSuccess());
        }));
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try { action(() -> controller.requestSwitch(b)); assertEquals(a,active()); action(() -> stage.fireEvent(new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST))); assertTrue(fx(() -> stage.isShowing())); }
        finally { release.countDown(); }
        ready(); assertEquals(1,old.facade().sessions().search("").value().orElseThrow().size());
        action(() -> controller.requestSwitch(b)); ready(); assertEquals(b,active()); assertTrue(manager.activeContext().facade().sessions().search("").value().orElseThrow().isEmpty());
    }
    @Test void validationDuplicateNamesExternalRefreshProtectedDeletionAndChineseLayouts() throws Exception {
        UUID defaultId=active(); profiles(); name(" "); click("profile-create"); assertEquals(1,fx(() -> list().getItems().size()));
        assertTrue(fx(() -> ((Label)panel().lookup("#profile-feedback")).getText().contains("failed")));
        UUID a=create("摄影 档案"), b=create("摄影 档案"); assertNotEquals(a,b); assertEquals(3,fx(() -> list().getItems().size()));
        select(defaultId); confirm(() -> ((Button)panel().lookup("#profile-delete")).fire(),true); assertTrue(manager.getProfile(defaultId).isSuccess());
        select(a); click("profile-enter"); profiles();
        action(() -> { manager.activeContext().localization().selectLanguage("zh-CN"); dialog().setWidth(480); dialog().setHeight(480); });
        resized(fx(this::dialog),480,480);
        action(() -> ((ScrollPane)panel().lookup("#profiles-scroll")).setVvalue(0));
        snapshot(panelOnFx(),"phase4-profiles-narrow-zh");
        name(" "); click("profile-rename");
        assertTrue(fx(() -> { panel().applyCss(); panel().layout(); var label=panel().lookup("#profile-feedback"); var bounds=label.localToScene(label.getBoundsInLocal());
            return label.isVisible() && bounds.getMinY()>=0 && bounds.getMaxY()<=dialog().getScene().getHeight(); }),"Operation feedback must be visible in the narrow window");
        snapshot(panelOnFx(),"phase4-profiles-feedback-zh");
        try(var other=new ProfileManager(env())) { assertTrue(other.renameProfile(a,"外部重命名").isSuccess()); }
        click("profile-refresh"); assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("外部重命名")));
        action(() -> { dialog().close(); stage.setWidth(880); stage.setHeight(600); }); resized(stage,880,600); snapshot(fx(() -> controller.workspace()),"phase4-main-narrow-zh");
        profiles(); try(var other=new ProfileManager(env())) { assertTrue(other.deleteProfile(a,true).isSuccess()); }
        click("profile-refresh"); assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("不可用")));
        select(b); click("profile-enter"); assertEquals(b,active());
        profiles(); Path registry=root.resolve("data/profiles.json"); String valid=Files.readString(registry); Files.writeString(registry,"broken");
        click("profile-refresh"); assertTrue(fx(() -> ((Label)panel().lookup("#profile-feedback")).getText().contains("失败"))); assertEquals("broken",Files.readString(registry)); Files.writeString(registry,valid);
    }
    Parent panelOnFx() throws Exception { return fx(this::panel); }
    void resized(Stage window,double width,double height) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(fx(() -> window.getScene().getWidth()>width || window.getScene().getHeight()>height)) {
            if(System.nanoTime()>deadline)fail("Native resize did not reach the scene"); Thread.sleep(20);
        }
    }
    void snapshot(Parent node,String name) throws Exception {
        fx(() -> { node.applyCss(); node.layout(); var image=node.snapshot(null,null);
            var buffered=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)buffered.setRGB(x,y,image.getPixelReader().getArgb(x,y));
            javax.imageio.ImageIO.write(buffered,"png",Path.of("target",name+".png").toFile()); return true; });
    }
    String process(String... args) throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),PhotoPilotCLI.class.getName(),"--lang=en"));
        var lines=new ArrayList<String>(); for(String arg:args) { if(arg.startsWith("--"))command.add(arg); else lines.add(arg); }
        Path log=Files.createTempFile(root,"cli-",".log"); var builder=new ProcessBuilder(command); builder.environment().putAll(env()); builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process child=builder.start(); try(var writer=child.outputWriter(java.nio.charset.StandardCharsets.UTF_8)) { writer.write(String.join("\n",lines)+"\nexit\n"); }
        try { assertTrue(child.waitFor(20,TimeUnit.SECONDS)); String text=Files.readString(log); assertEquals(0,child.exitValue(),text); return text; } finally { if(child.isAlive())child.destroyForcibly(); }
    }
}
