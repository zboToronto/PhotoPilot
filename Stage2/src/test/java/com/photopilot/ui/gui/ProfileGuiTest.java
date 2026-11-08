package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.model.LocalProfile;
import com.photopilot.repository.StorageScope;
import com.photopilot.ui.cli.PhotoPilotCLI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Node;
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
        action(() -> { stage=new Stage(); controller=new GuiProfileController(stage,manager,initial,context -> {}); }); ready();
        assertEquals("user-selection", fx(() -> controller.picker().getId())); assertTrue(fx(() -> controller.pickerWindow().isShowing()));
        click("profile-enter");
    }
    @AfterEach void close() throws Exception { action(() -> { if(controller!=null)controller.close(); if(stage!=null)stage.close(); }); if(manager!=null)manager.close(); }
    void ready() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(fx(() -> controller.busy())) { if(System.nanoTime()>deadline)fail("Profile GUI timed out"); Thread.sleep(20); }
        action(() -> { controller.workspace().applyCss(); controller.workspace().layout(); });
    }
    Stage dialog() { return controller.pickerWindow(); }
    UserSelectionPane panel() { return controller.picker(); }
    List<LocalProfile> users() { return controller.picker() != null ? panel().users() : settings().users(); }
    UserSettingsPane settings() { return controller.workspace().userSettings(); }
    /** The chooser has no in-session menu entry any more (it opens at startup); tests that cover it open it directly. */
    void profiles() throws Exception { action(controller::openProfiles); ready(); }
    void manage() throws Exception { nav("user-settings"); assertTrue(fx(() -> controller.workspace().atUserSettings())); }
    /** The in-session switch: the user's Switch button in User Settings. */
    Button switchOn(UUID id) { return (Button)settings().lookup("#user-switch-"+id); }
    Button switchButton(UUID id) throws Exception { return fx(() -> switchOn(id)); }
    void switchTo(UUID id) throws Exception { manage(); var button=switchButton(id); action(button::fire); ready(); }
    void manageSelect(UUID id) throws Exception { action(() -> settings().select(id)); }
    void click(String id) throws Exception { action(() -> ((Button)panel().lookup("#"+id)).fire()); ready(); if (id.equals("profile-enter") && fx(() -> controller.picker() == null)) { assertTrue(fx(() -> controller.workspace().atHome())); nav("F01"); } }
    DialogPane open(String id) { return (DialogPane)Window.getWindows().stream().filter(Window::isShowing).map(w -> w.getScene().getRoot().lookup(id)).filter(Objects::nonNull).findFirst().orElseThrow(); }
    Button dialogButton(DialogPane pane,ButtonBar.ButtonData kind) { return (Button)pane.lookupButton(pane.getButtonTypes().stream().filter(b -> b.getButtonData()==kind).findFirst().orElseThrow()); }
    void named(String trigger,String value) throws Exception {
        action(() -> {
            Platform.runLater(() -> { var pane=open("#profile-name-dialog"); ((TextField)pane.lookup("#profile-name")).setText(value); dialogButton(pane,ButtonBar.ButtonData.OK_DONE).fire(); });
            ((Button)settings().lookup("#"+trigger)).fire();
        }); ready();
    }
    void refreshList() throws Exception { action(() -> { if (controller.picker() != null) panel().refresh(); else settings().refresh(); }); ready(); }
    UUID create(String name) throws Exception { manage(); named("profile-create",name); return fx(() -> settings().selectedUser().id()); }
    void select(UUID id) throws Exception { action(() -> panel().select(id)); }
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
        UUID defaultId=active(); manage(); assertEquals(1,fx(() -> users().size()));
        UUID a=create("A 夜景"), b=create("B Portrait"); assertNotEquals(a,b);
        switchTo(a); assertEquals(a,active());
        assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("A 夜景")));
        nav("F05"); mainClick("gear-add"); field("gear-field-brand","GUI"); field("gear-field-model","A Camera"); field("gear-field-mount","E"); mainClick("gear-save");
        nav("F01"); mainClick("session-create"); field("session-field-title","A Session"); field("session-field-date","2026-11-01"); mainClick("session-save");
        var record=manager.activeContext().facade().sessions().search("").value().orElseThrow().getFirst();
        String cli=process("--profile="+a,"profile list","gear list","session list"); assertTrue(cli.contains("A Camera")); assertTrue(cli.contains("A Session")); assertTrue(cli.contains(b.toString()));
        process("--profile="+a,"session edit " + record.id() + " title=\"CLI Edited\"");
        mainClick("session-refresh"); assertEquals("CLI Edited",fx(() -> ((com.photopilot.model.PhotographySession)((TableView<?>)controller.workspace().lookup("#session-table")).getItems().getFirst()).title()));
        switchTo(b); assertEquals(b,active()); nav("F01");
        assertTrue(manager.activeContext().facade().inventory().list("").value().orElseThrow().items().isEmpty());
        assertEquals(0,fx(() -> ((TableView<?>)controller.workspace().lookup("#session-table")).getItems().size()));
        mainClick("session-create"); field("session-field-title","B Session"); field("session-field-date","2026-11-02"); mainClick("session-save");
        Path bFile=manager.activeContext().storageScope().sessionsDirectory().resolve("sessions.json"); byte[] bData=Files.readAllBytes(bFile);
        manage(); named("profile-rename","B Renamed"); assertEquals(b,active()); assertArrayEquals(bData,Files.readAllBytes(bFile));
        assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("B Renamed")));
        switchTo(a); assertEquals("CLI Edited",manager.activeContext().facade().sessions().search("").value().orElseThrow().getFirst().title());
        nav("F05"); assertEquals(1,fx(() -> ((TableView<?>)controller.workspace().lookup("#gear-table")).getItems().size()));
        manage(); manageSelect(b); confirm(() -> ((Button)settings().lookup("#profile-delete")).fire(),false); assertTrue(manager.getProfile(b).isSuccess());
        confirm(() -> ((Button)settings().lookup("#profile-delete")).fire(),true); assertFalse(manager.getProfile(b).isSuccess()); assertArrayEquals(bData,Files.readAllBytes(bFile));
        action(() -> {controller.close();stage.close();}); manager.close(); manager=new ProfileManager(env()); assertEquals(defaultId,active()); startWindow();
        profiles(); select(a); click("profile-enter"); assertEquals("CLI Edited",manager.activeContext().facade().sessions().search("").value().orElseThrow().getFirst().title());
        assertEquals(1,manager.activeContext().facade().inventory().list("").value().orElseThrow().items().size());
    }
    @Test void avatarPreviewHomeNavigationAndBilingualLayoutsUseRealSelectedUser() throws Exception {
        UUID original = active(); UUID selected = create("Alex · Photography");
        Path source = root.resolve("avatar.png");
        var image = new java.awt.image.BufferedImage(320, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(new java.awt.Color(62, 103, 142)); graphics.fillRect(0, 0, 320, 240);
        graphics.setColor(new java.awt.Color(218, 232, 241)); graphics.fillOval(110, 40, 100, 100); graphics.fillOval(60, 145, 200, 150); graphics.dispose();
        javax.imageio.ImageIO.write(image, "png", source.toFile());
        action(() -> settings().previewAvatar(selected, source));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(() -> Window.getWindows().stream().anyMatch(w -> w.isShowing() && w.getScene().getRoot().lookup("#avatar-preview") != null))) {
            if (System.nanoTime() > deadline) fail("Avatar preview did not appear"); Thread.sleep(20);
        }
        action(() -> {
            DialogPane preview = (DialogPane)Window.getWindows().stream().filter(Window::isShowing)
                .map(w -> w.getScene().getRoot().lookup("#avatar-preview")).filter(Objects::nonNull).findFirst().orElseThrow();
            var use = preview.getButtonTypes().stream().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
            ((Button)preview.lookupButton(use)).fire();
        }); ready();
        byte[] avatar = manager.avatar(selected).value().orElseThrow().orElseThrow();
        assertTrue(manager.avatar(original).value().orElseThrow().isEmpty());
        named("profile-rename","Alex Renamed"); assertArrayEquals(avatar, manager.avatar(selected).value().orElseThrow().orElseThrow());
        snapshot(fx(() -> controller.workspace()), "user-settings-en");
        profiles(); select(selected); snapshot(panelOnFx(), "user-selection-en");
        action(() -> ((Button)panel().lookup("#profile-enter")).fire()); ready();
        assertEquals(selected, active()); assertTrue(fx(() -> controller.workspace().atHome()));
        snapshot(fx(() -> controller.workspace()), "home-dashboard-en");
        for (String feature : List.of("F05", "F01", "F08", "F10", "F02")) {
            mainClick("home-" + feature); assertFalse(fx(() -> controller.workspace().atHome()));
            action(() -> ((ToggleButton)controller.workspace().lookup("#nav-home")).fire()); ready();
            assertTrue(fx(() -> controller.workspace().atHome()));
        }
        action(() -> { manager.activeContext().localization().selectLanguage("zh-CN"); stage.setWidth(880); stage.setHeight(600); });
        resized(stage, 880, 600); snapshot(fx(() -> controller.workspace()), "home-dashboard-narrow-zh");
        profiles(); snapshot(panelOnFx(), "user-selection-narrow-zh"); click("profile-enter");
        manage(); manageSelect(selected); action(() -> ((Button)settings().lookup("#avatar-remove")).fire()); ready(); assertTrue(manager.avatar(selected).value().orElseThrow().isEmpty());
        var splash = fx(() -> { var window = new Stage(); window.setScene(new javafx.scene.Scene(new SplashScreen(), 880, 600));
            window.getScene().getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); window.show(); return window; });
        try { snapshot(fx(() -> splash.getScene().getRoot()), "startup-splash"); } finally { action(splash::close); }
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
        nav("F01"); assertEquals(0,fx(() -> ((TableView<?>)controller.workspace().lookup("#session-table")).getItems().size()));
    }
    @Test void inventoryCalculatorDraftsAndWindowCloseRequireResolution() throws Exception {
        UUID a=active(),b=manager.createProfile("B").value().orElseThrow().id(); nav("F05"); mainClick("gear-add"); field("gear-field-model","Unsaved camera");
        nav("F05"); nav("F01"); nav("F05");
        assertTrue(fx(() -> controller.dirty()));
        assertEquals("Unsaved camera", fx(() -> ((TextField)controller.workspace().lookup("#gear-field-model")).getText()));
        profiles(); select(b); confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),false); assertEquals(a,active());
        confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),true); assertEquals(b,active());
        nav("F08"); action(() -> ((TextField)controller.workspace().lookupAll(".text-field").iterator().next()).setText("42")); assertTrue(fx(() -> controller.dirty()));
        confirm(() -> stage.fireEvent(new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST)),false); assertTrue(fx(() -> stage.isShowing()));
        profiles(); select(a); confirm(() -> ((Button)panel().lookup("#profile-enter")).fire(),true); assertFalse(fx(() -> controller.dirty())); assertEquals(a,active());
    }
    @Test void startupRecoveryOffersHealthyProfilesWithoutResettingCorruptDefault() throws Exception {
        UUID a = active(), b = manager.createProfile("Healthy B").value().orElseThrow().id();
        Path broken = manager.activeContext().storageScope().inventoryFile();
        action(() -> { controller.close(); stage.close(); }); manager.close();
        Files.writeString(broken, "broken default");
        byte[] registry = Files.readAllBytes(root.resolve("data/profiles.json"));
        var failure = assertThrows(IllegalArgumentException.class, () -> new ProfileManager(env()));
        var recovery = fx(() -> { var dialog = new ProfileRecoveryDialog(env(), failure); dialog.show(); return dialog; });
        action(() -> {
            @SuppressWarnings("unchecked") var choices = (ComboBox<LocalProfile>)recovery.getDialogPane().lookup("#profile-recovery-list");
            choices.getSelectionModel().select(choices.getItems().stream().filter(p -> p.id().equals(a)).findFirst().orElseThrow());
            ((Button)recovery.getDialogPane().lookup("#profile-recovery-enter")).fire();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(() -> recovery.getDialogPane().lookup("#profile-recovery-list").isDisabled())) {
            if (System.nanoTime() > deadline) fail("Recovery failure feedback timed out"); Thread.sleep(20);
        }
        assertTrue(fx(recovery::isShowing)); assertNull(fx(recovery::getResult));
        snapshot(recovery.getDialogPane(), "profile-startup-recovery");
        action(() -> {
            @SuppressWarnings("unchecked") var choices = (ComboBox<LocalProfile>)recovery.getDialogPane().lookup("#profile-recovery-list");
            choices.getSelectionModel().select(choices.getItems().stream().filter(p -> p.id().equals(b)).findFirst().orElseThrow());
            ((Button)recovery.getDialogPane().lookup("#profile-recovery-enter")).fire();
        });
        while (fx(recovery::isShowing)) {
            if (System.nanoTime() > deadline) fail("Recovery selection timed out"); Thread.sleep(20);
        }
        try (var recovered = fx(recovery::getResult)) {
            assertNotNull(recovered); assertEquals(b, recovered.activeProfile().value().orElseThrow().id());
        }
        action(() -> { var cancelled = new ProfileRecoveryDialog(env(), failure); cancelled.show(); cancelled.close(); assertNull(cancelled.getResult()); });
        assertEquals("broken default", Files.readString(broken));
        assertArrayEquals(registry, Files.readAllBytes(root.resolve("data/profiles.json")));
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
        UUID defaultId=active(); manage();
        var blankRejected=new java.util.concurrent.atomic.AtomicBoolean();
        action(() -> {
            Platform.runLater(() -> { var pane=open("#profile-name-dialog"); ((TextField)pane.lookup("#profile-name")).setText(" "); blankRejected.set(dialogButton(pane,ButtonBar.ButtonData.OK_DONE).isDisabled()); dialogButton(pane,ButtonBar.ButtonData.CANCEL_CLOSE).fire(); });
            ((Button)settings().lookup("#profile-create")).fire();
        }); ready(); assertTrue(blankRejected.get()); assertEquals(1,fx(() -> users().size()));
        named("profile-create","x".repeat(81)); assertEquals(1,fx(() -> users().size()));
        assertTrue(fx(() -> { var pane=open("#profile-error"); pane.applyCss(); var fill=pane.getBackground().getFills().getFirst().getFill();
            return pane.getStyleClass().contains("app-dialog") && ((javafx.scene.paint.Color)fill).getOpacity()>.9; }),"Error dialog must be an opaque chooser-styled panel, not see-through"); snapshot(fx(() -> open("#profile-error")),"chooser-error-dialog");
        assertTrue(fx(() -> ((Label)settings().lookup("#profile-feedback")).getText().contains("failed")));
        UUID a=create("摄影 档案"), b=create("摄影 档案"); assertNotEquals(a,b); assertEquals(3,fx(() -> users().size()));
        manageSelect(defaultId); assertTrue(fx(() -> settings().lookup("#profile-delete").isDisabled()),"Active user cannot be offered for deletion");
        profiles(); select(a); click("profile-enter"); manage(); manageSelect(defaultId);
        confirm(() -> ((Button)settings().lookup("#profile-delete")).fire(),true); assertTrue(manager.getProfile(defaultId).isSuccess());
        profiles();
        action(() -> { manager.activeContext().localization().selectLanguage("zh-CN"); dialog().setWidth(880); dialog().setHeight(600); });
        resized(fx(this::dialog),880,600);

        snapshot(panelOnFx(),"phase4-profiles-narrow-zh");
        click("profile-enter"); manage(); named("profile-rename","x".repeat(81));
        assertTrue(fx(() -> settings().lookup("#profile-feedback").isVisible()),"Operation feedback must be visible");
        snapshot(fx(() -> controller.workspace()),"phase4-profiles-feedback-zh");
        try(var other=new ProfileManager(env())) { assertTrue(other.renameProfile(a,"外部重命名").isSuccess()); }
        refreshList(); assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("外部重命名")));
        action(() -> { controller.requestSwitch(active()); stage.setWidth(880); stage.setHeight(600); }); ready(); resized(stage,880,600); snapshot(fx(() -> controller.workspace()),"phase4-main-narrow-zh");
        profiles(); try(var other=new ProfileManager(env())) { assertTrue(other.deleteProfile(a,true).isSuccess()); }
        refreshList(); assertTrue(fx(() -> ((Label)controller.workspace().lookup("#profile-active")).getText().contains("不可用")));
        select(b); click("profile-enter"); assertEquals(b,active());
        profiles(); Path registry=root.resolve("data/profiles.json"); String valid=Files.readString(registry); Files.writeString(registry,"broken");
        refreshList(); assertTrue(fx(() -> ((Label)panel().lookup("#profile-feedback")).getText().contains("失败"))); assertEquals("broken",Files.readString(registry)); Files.writeString(registry,valid);
    }
    @Test void chooserKeepsSelectedUserCentredForEveryCountWithoutShowingIdentifiers() throws Exception {
        UUID first=active(); profiles(); assertEquals(1,tiles()); assertEquals("Default",centreName()); chooserChecks(first,"chooser-1-user");
        assertFalse(fx(() -> panel().lookup("#profile-create").isVisible()),"First-run action stays hidden while users exist");
        for (String removed : List.of("#profile-rename","#profile-delete","#avatar-import","#avatar-remove","#profile-refresh")) assertNull(fx(() -> panel().lookup(removed)),removed);
        assertTrue(fx(() -> panel().lookupAll(".label").stream().noneMatch(n -> ((Label)n).getText().contains("photo libraries"))),"Startup sentence must be gone");
        click("profile-enter"); UUID b=create("Studio"); profiles(); assertEquals(2,tiles()); assertFalse(fx(() -> panel().lookup("#profile-next").isVisible())); chooserChecks(first,"chooser-2-users");
        click("profile-enter"); UUID c=create("Travel"); profiles(); assertEquals(3,tiles()); chooserChecks(first,"chooser-3-users");
        select(b); assertEquals("Studio",centreName());
        action(() -> panel().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.RIGHT,false,false,false,false)));
        assertNotEquals(b,fx(() -> panel().selectedUser().id()));
        select(first); click("profile-enter"); UUID d=create("Weddings"), e=create("A very long display name that must never push neighbouring users out of slots"); profiles(); select(e); assertEquals(5,fx(() -> users().size())); assertEquals(3,tiles());
        assertTrue(fx(() -> panel().lookup("#profile-next").isVisible())); chooserChecks(e,"chooser-5-users");
        action(() -> ((Button)panel().lookup("#profile-next")).fire()); assertNotEquals(e,fx(() -> panel().selectedUser().id()));
        action(() -> ((Button)panel().lookup("#profile-previous")).fire()); assertEquals(e,fx(() -> panel().selectedUser().id()));
        action(() -> { dialog().setWidth(880); dialog().setHeight(600); }); resized(fx(this::dialog),880,600); chooserChecks(e,"chooser-minimum-size");
        action(() -> controller.pickerWindow().toggleMaximized()); assertTrue(fx(() -> controller.pickerWindow().maximizedToScreen()));
        action(() -> controller.pickerWindow().toggleMaximized()); assertFalse(fx(() -> controller.pickerWindow().maximizedToScreen()));
        Path broken=manager.activeContext().storageScope().inventoryFile().getParent().getParent().resolve(d.toString()).resolve("avatar.png"); Files.writeString(broken,"not an image");
        select(d); refreshList(); assertEquals("Weddings",centreName());
    }
    @Test void userManagementStaysInsideTheWorkspaceUntilSwitchIsRequested() throws Exception {
        UUID current=active(); var view=fx(() -> controller.workspace()); var context=manager.activeContext();
        manage(); assertNull(fx(() -> controller.picker())); assertTrue(fx(() -> stage.isShowing()));
        named("current-rename","Renamed Here");
        assertTrue(fx(() -> ((Label)view.lookup("#profile-active")).getText().contains("Renamed Here"))); assertEquals("Renamed Here",manager.activeProfile().value().orElseThrow().displayName());
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        action(() -> { Platform.runLater(() -> { var pane=open("#profile-name-dialog"); ((TextField)pane.lookup("#profile-name")).setText("Discarded"); cancelled.set(true); dialogButton(pane,ButtonBar.ButtonData.CANCEL_CLOSE).fire(); }); ((Button)settings().lookup("#current-rename")).fire(); }); ready();
        assertTrue(cancelled.get()); assertEquals("Renamed Here",manager.activeProfile().value().orElseThrow().displayName());
        UUID other=create("Other User"); assertEquals(current,active()); assertEquals(other,fx(() -> settings().selectedUser().id()));
        assertTrue(fx(() -> ((Label)view.lookup("#profile-active")).getText().contains("Renamed Here")),"Creating a user must not replace the session");
        named("profile-rename","Other Renamed"); assertEquals(current,active()); assertEquals("Other Renamed",manager.getProfile(other).value().orElseThrow().displayName());
        confirm(() -> ((Button)settings().lookup("#profile-delete")).fire(),true); assertFalse(manager.getProfile(other).isSuccess()); assertEquals(current,active());
        assertSame(view,fx(() -> controller.workspace())); assertSame(context,manager.activeContext()); assertNull(fx(() -> controller.picker())); assertTrue(fx(() -> view.atUserSettings()));
        action(() -> ((ToggleButton)view.lookup("#nav-home")).fire()); assertTrue(fx(() -> view.atHome()));
        nav("app-settings"); assertTrue(fx(() -> view.atApplicationSettings())); assertNull(fx(() -> controller.picker()));
        assertSame(view,fx(() -> controller.workspace())); assertEquals(current,active());
    }
    @Test void headerShowsTheCurrentUserReadOnlyAndUserSettingsSwitchesThroughTheExistingMechanism() throws Exception {
        UUID a=active(); UUID b=manager.createProfile("B Studio").value().orElseThrow().id();
        var oldView=fx(() -> controller.workspace());
        // Header: avatar and name only. No button, arrow or menu, nothing focusable or clickable.
        assertNull(fx(() -> oldView.lookup("#user-menu"))); assertTrue(fx(() -> oldView.lookupAll(".user-menu-chevron").isEmpty()));
        var indicator=fx(() -> oldView.lookup("#user-indicator"));
        assertFalse(indicator instanceof ButtonBase); assertTrue(fx(indicator::isVisible));
        assertTrue(fx(() -> indicator.lookupAll("*").stream().noneMatch(n -> n.isFocusTraversable() || n instanceof ButtonBase || n.getOnMouseClicked()!=null)));
        assertTrue(fx(() -> indicator.lookup(".user-portrait") != null || indicator.lookup(".user-avatar") != null),"the avatar is shown");
        assertEquals("Default",fx(() -> ((Label)oldView.lookup("#profile-active")).getText()));
        assertTrue(fx(() -> indicator.getAccessibleText()).contains("Default"));

        manage();
        assertNull(switchButton(a),"the current user offers no Switch"); assertNotNull(switchButton(b));
        assertEquals("Current",fx(() -> ((Label)settings().lookup("#user-current-badge")).getText()));
        assertTrue(fx(() -> settings().lookup("#user-row-"+a).lookup("#user-current-badge") != null),"Current marks the active user's row");
        assertEquals("Switch",fx(() -> switchOn(b).getText()));
        snapshot(fx(() -> controller.workspace()),"user-settings-switch-en");

        // Unsaved input keeps the existing confirmation: cancelling keeps the user, the view and the draft.
        nav("F01"); mainClick("session-create"); field("session-field-title","Unsaved A"); manage();
        var change=switchButton(b);
        confirm(change::fire,false);
        assertEquals(a,active()); assertSame(oldView,fx(() -> controller.workspace())); assertTrue(fx(() -> oldView.atUserSettings()));
        assertTrue(fx(() -> controller.dirty()));
        nav("F01"); assertEquals("Unsaved A",fx(() -> ((TextField)oldView.lookup("#session-field-title")).getText()));
        manage(); change=switchButton(b);
        confirm(change::fire,true);
        assertEquals(b,active()); var view=fx(() -> controller.workspace()); assertNotSame(oldView,view);
        assertNull(fx(() -> controller.picker()),"no chooser window opens"); assertTrue(fx(() -> view.atUserSettings()),"User Settings stays open");
        assertEquals("B Studio",fx(() -> ((Label)view.lookup("#profile-active")).getText()),"the header follows the switch");
        assertNull(switchButton(b)); assertNotNull(switchButton(a));
        assertTrue(fx(() -> settings().lookup("#user-row-"+b).lookup("#user-current-badge") != null),"the new user is marked Current");
        assertTrue(fx(() -> ((Label)settings().lookup("#profile-feedback")).getText().contains("User selected")));
        assertTrue(fx(() -> ((Label)settings().lookup(".user-summary-name")).getText().equals("B Studio")),"the profile card shows the new user");
        nav("F01"); assertEquals(0,fx(() -> ((TableView<?>)controller.workspace().lookup("#session-table")).getItems().size()),"B's own data is shown");

        // A failed switch keeps the current user and reports in the page; nothing is reset.
        Path file=StorageScope.forProfile(root.resolve("data"),a).inventoryFile(); boolean existed=Files.exists(file);
        byte[] original=existed ? Files.readAllBytes(file) : null; Files.createDirectories(file.getParent()); Files.writeString(file,"broken");
        switchTo(a);
        assertEquals(b,active()); assertSame(view,fx(() -> controller.workspace())); assertTrue(fx(() -> view.atUserSettings()));
        assertTrue(fx(() -> ((Label)settings().lookup("#profile-feedback")).getText().contains("failed")));
        assertEquals("broken",Files.readString(file));
        if (existed) Files.write(file,original); else Files.delete(file);
        switchTo(a); assertEquals(a,active()); assertTrue(fx(() -> controller.workspace().atUserSettings()));

        // Live language change keeps the page and its state, and the new strings are localized.
        UUID selectedBefore=fx(() -> settings().selectedUser().id());
        var shell=fx(() -> controller.workspace());
        action(() -> manager.activeContext().localization().selectLanguage("zh-CN")); ready();
        assertSame(shell,fx(() -> controller.workspace())); assertTrue(fx(() -> shell.atUserSettings()));
        assertEquals(selectedBefore,fx(() -> settings().selectedUser().id()));
        assertEquals("切换",fx(() -> switchOn(b).getText())); assertEquals("当前",fx(() -> ((Label)settings().lookup("#user-current-badge")).getText()));
        assertEquals("切换到 B Studio",fx(() -> switchOn(b).getAccessibleText()));
        snapshot(fx(() -> controller.workspace()),"user-settings-switch-zh");
        action(() -> manager.activeContext().localization().selectLanguage("en")); ready();
    }
    @Test void closingTheChooserUsesTheSameGuardAsTheMainWindow() throws Exception {
        mainClick("session-create"); field("session-field-title","Unsaved"); profiles();
        confirm(() -> ((Button)controller.pickerWindow().getScene().getRoot().lookup("#window-close")).fire(),false);
        assertTrue(fx(() -> controller.pickerWindow().isShowing())); assertFalse(fx(() -> stage.isShowing()));
        action(() -> panel().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.ENTER,false,false,false,false))); ready();
        assertTrue(fx(() -> stage.isShowing())); assertNull(fx(() -> controller.pickerWindow())); nav("F01");
        assertEquals("Unsaved",fx(() -> ((TextField)controller.workspace().lookup("#session-field-title")).getText()));
    }
    int tiles() throws Exception { return fx(() -> (int)panel().lookupAll(".profile-tile").stream().filter(Node::isVisible).count()); }
    String centreName() throws Exception { return fx(() -> panel().selectedUser().displayName()); }
    /** Selected avatar sits on the window's vertical axis; no identifier, overlap or clipped control appears. */
    void chooserChecks(UUID current,String image) throws Exception {
        Parent root=fx(() -> controller.pickerWindow().getScene().getRoot());
        action(() -> { root.applyCss(); root.layout(); });
        assertEquals(current,fx(() -> panel().selectedUser().id()));
        assertTrue(fx(() -> { double width=root.getLayoutBounds().getWidth(); var ring=root.lookupAll(".profile-tile").stream().filter(n -> n.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("current"))).findFirst().orElseThrow();
            var bounds=ring.localToScene(ring.getBoundsInLocal()); return users().size()==2 || Math.abs((bounds.getMinX()+bounds.getMaxX())/2-width/2)<2; }),"Selected avatar must stay on the centre axis");
        assertTrue(fx(() -> root.lookupAll(".label").stream().map(n -> ((Label)n).getText()).filter(Objects::nonNull).noneMatch(text -> users().stream().anyMatch(u -> text.contains(u.id().toString())))),"Identifiers must stay hidden");
        assertTrue(fx(() -> { double top=0; for(String id:List.of("#selection-logo","#profile-strip","#profile-enter")) { var node=root.lookup(id); var bounds=node.localToScene(node.getLayoutBounds());
            if(bounds.getMinY()<top-.5 || bounds.getMaxY()>root.getLayoutBounds().getHeight() || bounds.getMinX()<0 || bounds.getMaxX()>root.getLayoutBounds().getWidth()) return false; top=bounds.getMaxY(); } return true; }),"Regions must stay ordered and inside the window");
        snapshot(root,image);
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
