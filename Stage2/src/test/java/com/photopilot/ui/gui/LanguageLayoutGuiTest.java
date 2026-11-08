package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Switching language live, in every supported language, at the default and at the
 * minimum window size: navigation keeps its geometry, settings controls keep their place, nothing overlaps, overflows
 * or is shortened without a way to read it, and the current page and its state stay as they were.
 */
class LanguageLayoutGuiTest {
    private static final String[] TAGS = { "en", "zh-CN" };
    private static final List<String> STABLE = List.of("navigation-scroll", "settings-language", "settings-theme");
    @TempDir Path root;
    Stage stage; GuiProfileController controller;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void close() throws Exception { action(() -> { if (controller != null) controller.close(); if (stage != null) stage.close(); }); }

    @Test void switchingLanguageKeepsNavigationAndSettingsInPlaceWithoutClippingOrOverlap() throws Exception {
        var env = Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString());
        try (var manager = new ProfileManager(env)) {
            manager.createProfile("Alexandra Photography Studio for Weddings and Long Travel Stories");
            manager.createProfile("摄影 档案 夜景 星空");
            var initial = manager.activeProfile().value().orElseThrow();
            action(() -> { stage = new Stage(); controller = new GuiProfileController(stage, manager, initial, context -> { }); });
            idle();
            action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle();
            var localization = manager.activeContext().localization();
            var view = fx(() -> controller.workspace());
            for (double[] size : new double[][] { { 1120, 760 }, { 880, 600 } }) for (String page : List.of("home", "app", "user")) {
                action(() -> { localization.useLanguage("en"); stage.setWidth(size[0]); stage.setHeight(size[1]); open(view, page); }); idle();
                Map<String, Map<String, Bounds>> geometry = new LinkedHashMap<>();
                Object state = fx(() -> state(view, page));
                for (String tag : TAGS) {
                    action(() -> localization.selectLanguage(tag)); idle();
                    String where = page + " " + (int) size[0] + "x" + (int) size[1] + " " + tag;
                    assertSame(view, fx(() -> controller.workspace()), where + ": the workspace is updated in place, not rebuilt");
                    assertTrue(fx(() -> shown(view, page)), where + ": the current page stays open");
                    assertEquals(state, fx(() -> state(view, page)), where + ": the page keeps its state");
                    geometry.put(tag, fx(() -> measure(view)));
                    fx(() -> { check(view, where); return true; });
                    AppearanceGuiTest.snapshot(stage.getScene(), "language-" + page + "-" + tag + "-" + (int) size[0]);
                }
                // Navigation rows and the sidebar keep one geometry in every language (also when the list overflows).
                var en = geometry.get("en"); var zh = geometry.get("zh-CN");
                assertEquals(en.keySet(), zh.keySet());
                for (String id : en.keySet()) {
                    Bounds a = en.get(id), b = zh.get(id);
                    String where = page + " " + (int) size[0] + " " + id;
                    assertEquals(a.getMinX(), b.getMinX(), .5, where + " x"); assertEquals(a.getWidth(), b.getWidth(), .5, where + " width");
                    if (id.startsWith("nav-") || id.equals("navigation-scroll")) {
                        assertEquals(a.getMinY(), b.getMinY(), .5, where + " y"); assertEquals(a.getHeight(), b.getHeight(), .5, where + " height");
                    }
                }
            }
            action(() -> localization.selectLanguage("en")); idle();
        }
    }

    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    static void open(PhotoPilotWorkspace view, String page) {
        switch (page) { case "home" -> view.showHome(); case "app" -> view.showApplicationSettings(); default -> view.showUserSettings(); }
    }
    static boolean shown(PhotoPilotWorkspace view, String page) {
        return switch (page) { case "home" -> view.atHome(); case "app" -> view.atApplicationSettings(); default -> view.atUserSettings(); };
    }
    /** What the page shows that a language change must not reset: slider and choices, or the selected user. */
    static Object state(PhotoPilotWorkspace view, String page) {
        return switch (page) {
            case "app" -> List.of(((Slider) view.lookup("#settings-transparency")).getValue(), ((ToggleButton) view.lookup("#settings-mode-system")).isSelected(),
                view.lookupAll(".theme-swatch").stream().filter(n -> ((ToggleButton) n).isSelected()).map(Node::getId).toList());
            case "user" -> view.userSettings().selectedUser().id();
            default -> "home";
        };
    }
    /** Scene geometry of the navigation and of the settings controls that must not move with the language. */
    static Map<String, Bounds> measure(Parent view) {
        Map<String, Bounds> found = new TreeMap<>();
        for (Node node : view.lookupAll(".navigation-item")) found.put(node.getId(), scene(node));
        for (String id : STABLE) { var node = view.lookup("#" + id); if (node != null && node.isVisible()) found.put(id, scene(node)); }
        return found;
    }
    static Bounds scene(Node node) { return node.localToScene(node.getLayoutBounds()); }
    /** No overlap between neighbouring controls, nothing past the window edge, no hidden clipping, no missing text. */
    static void check(Parent node, String where) {
        double width = node.getScene().getWidth(), height = node.getScene().getHeight();
        for (Node child : node.getChildrenUnmodifiable()) {
            if (!child.isVisible()) continue;
            Bounds b = scene(child);
            boolean onScreen = b.getMaxY() > 0 && b.getMinY() < height;
            if (onScreen && child instanceof Region && b.getWidth() > 0)
                assertTrue(b.getMaxX() <= width + .5, where + ": " + describe(child) + " passes the window edge at " + b.getMaxX());
            if (child instanceof Labeled labeled && labeled.getText() != null && onScreen) {
                assertFalse(labeled.getText().startsWith("[!"), where + ": missing translation " + labeled.getText());
                for (Node part : labeled.getChildrenUnmodifiable())
                    if (part instanceof Text shown && !Objects.equals(shown.getText(), labeled.getText()))
                        assertTrue(labeled.getTooltip() != null || labeled.getAccessibleText() != null,
                            where + ": '" + labeled.getText() + "' is shortened with no way to read it");
            }
            boolean userLine = child.getStyleClass().contains("user-line");
            if (child instanceof HBox || child instanceof FlowPane || userLine) {
                var kids = ((Pane) child).getChildren().stream().filter(Node::isVisible).filter(Node::isManaged).toList();
                for (int i = 0; i < kids.size(); i++) for (int j = i + 1; j < kids.size(); j++) {
                    Bounds x = local(kids.get(i)), y = local(kids.get(j));
                    if (userLine) x = textArea(kids.get(i), child);   // a user row under its Switch: the name must end before it
                    if (x != null && x.getWidth() > 0 && y.getWidth() > 0 && x.getMinX() < y.getMaxX() - 1 && y.getMinX() < x.getMaxX() - 1
                        && x.getMinY() < y.getMaxY() - 1 && y.getMinY() < x.getMaxY() - 1)
                        fail(where + ": " + describe(kids.get(i)) + " overlaps " + describe(kids.get(j)));
                }
            }
            if (child instanceof Parent parent) check(parent, where);
        }
    }
    static Bounds local(Node n) { var l = n.getLayoutBounds(); return new BoundingBox(n.getLayoutX() + l.getMinX(), n.getLayoutY() + l.getMinY(), l.getWidth(), l.getHeight()); }
    /** The rendered name of a user row, in the row's coordinates. */
    static Bounds textArea(Node row, Node line) {
        var name = row.lookup(".user-row-name");
        if (!(name instanceof Labeled labeled)) return null;
        for (Node part : labeled.getChildrenUnmodifiable()) if (part instanceof Text text) return line.sceneToLocal(text.localToScene(text.getLayoutBounds()));
        return null;
    }
    static String describe(Node n) { return n.getClass().getSimpleName() + (n.getId() == null ? "" : "#" + n.getId()) + n.getStyleClass(); }
}
