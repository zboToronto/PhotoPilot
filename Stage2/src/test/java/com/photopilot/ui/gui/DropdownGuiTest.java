package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Every dropdown uses the shared design: rounded, theme-aware, translucent with a frosted
 * backdrop that follows Transparency, placed inside the window, readable, with standard keyboard and an empty state.
 */
class DropdownGuiTest {
    @TempDir Path root;
    Stage stage; GuiProfileController controller; ProfileManager manager;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void close() throws Exception {
        action(() -> { var theme = ThemeManager.get(); theme.previewCustomHue(Theme.DEFAULT_CUSTOM_HUE); theme.select(Theme.Accent.BLUE);
            theme.select(Theme.Mode.SYSTEM); theme.previewOpacity(Theme.DEFAULT_OPACITY); theme.save();
            if (controller != null) controller.close(); if (stage != null) stage.close(); });
        if (manager != null) manager.close();
    }
    void start() throws Exception {
        manager = new ProfileManager(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()));
        var initial = manager.activeProfile().value().orElseThrow();
        action(() -> { stage = new Stage(); controller = new GuiProfileController(stage, manager, initial, context -> { }); });
        idle();
        action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle();
    }
    void idle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    PhotoPilotWorkspace view() throws Exception { return fx(() -> controller.workspace()); }
    void nav(String id) throws Exception { action(() -> ((ToggleButton) controller.workspace().lookup("#nav-" + id)).fire()); idle(); }
    @SuppressWarnings("unchecked") ComboBox<Object> combo(String id) throws Exception { return fx(() -> (ComboBox<Object>) controller.workspace().lookup("#" + id)); }
    /** Opens the dropdown and returns its list once it is placed and frosted. */
    static ListView<?> open(ComboBox<?> box) throws Exception {
        action(box::show); AppearanceGuiTest.settle();
        return fx(() -> (ListView<?>) ((DropdownSkin<?>) box.getSkin()).getPopupContent());
    }
    static void appearance(Theme.Accent accent, Integer hue, boolean dark, int opacity) throws Exception {
        action(() -> { var theme = ThemeManager.get(); if (hue == null) theme.select(accent); else theme.previewCustomHue(hue);
            theme.select(dark ? Theme.Mode.DARK : Theme.Mode.LIGHT); theme.previewOpacity(opacity); });
        AppearanceGuiTest.settle();
    }

    @Test void statusFilterPlanningSessionAndLanguageShareOneRoundedThemedTranslucentDropdown() throws Exception {
        start();
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("session-status-filter", "F01"); pages.put("plan-session", "F10"); pages.put("settings-language", "app-settings");
        StringBuilder report = new StringBuilder();
        for (var entry : pages.entrySet()) {
            nav(entry.getValue());
            var box = combo(entry.getKey());
            assertInstanceOf(DropdownSkin.class, fx(box::getSkin), entry.getKey() + " uses the shared dropdown skin");
            for (int opacity : new int[] { 0, 50, 100 }) {
                appearance(Theme.Accent.BLUE, null, false, opacity);
                var list = open(box);
                String where = entry.getKey() + " at " + opacity + "%";
                Bounds control = fx(() -> box.localToScreen(box.getLayoutBounds())), shown = fx(() -> list.localToScreen(list.getLayoutBounds()));
                Scene scene = fx(box::getScene);
                // Rounded, aligned under the control, at least as wide, and inside the application window.
                assertEquals(12, fx(() -> list.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius()), .01, where);
                assertEquals(control.getMinX(), shown.getMinX(), 1.01, where + ": left edges align");
                assertTrue(shown.getWidth() >= control.getWidth() - 1, where + ": at least as wide as the control");
                assertTrue(Math.abs(shown.getMinY() - (control.getMaxY() + DropdownSkin.GAP)) <= 1.01 || Math.abs(shown.getMaxY() - (control.getMinY() - DropdownSkin.GAP)) <= 1.01, where + ": next to the control");
                assertTrue(fx(() -> inside(shown, scene)), where + ": inside the window " + shown);
                // The surface follows Transparency through the card tokens; the frosted backdrop shows only while translucent.
                double alpha = fx(() -> ((Color) list.getBackground().getFills().getFirst().getFill()).getOpacity());
                double expected = ThemeTest.color(ThemeManager.get().theme().tokens().get("-pp-surface-2")).getOpacity();
                assertEquals(expected, alpha, .01, where + ": popup background uses the shared surface opacity");
                assertEquals(opacity < 99, fx(((DropdownSkin<?>) fx(box::getSkin))::frosted), where + ": frosted backdrop");
                assertEquals(1.0, fx(list::getOpacity), where + ": opacity is on the background only, never on the list");
                // Text stays readable on the rendered surface, and the selection is a soft accent tint, not a solid bar.
                var cells = fx(() -> list.lookupAll(".list-cell").stream().filter(n -> n.isVisible() && !((ListCell<?>) n).isEmpty()).map(n -> (ListCell<?>) n).toList());
                if (!cells.isEmpty()) {
                    var shot = fx(() -> list.getScene().snapshot(null));
                    for (var cell : cells) {
                        Bounds b = fx(() -> cell.localToScene(cell.getLayoutBounds()));
                        Color behind = shot.getPixelReader().getColor((int) (b.getMaxX() - 6), (int) b.getCenterY());
                        Color text = fx(() -> (Color) cell.getTextFill());
                        double ratio = ThemeTest.contrast(text, behind);
                        assertTrue(ratio >= 4.5, where + ": '" + fx(cell::getText) + "' contrast " + ratio);
                        if (fx(cell::isSelected)) assertTrue(behind.getSaturation() < .35, where + ": selection is a soft tint " + behind);
                    }
                    report.append(String.format(Locale.ROOT, "%s: %d options, %.0f x %.0f px%n", where, cells.size(), shown.getWidth(), shown.getHeight()));
                } else {
                    // The planning selector has no sessions yet: a short localized message, not a large blank panel.
                    var empty = fx(() -> (Label) list.lookup(".dropdown-empty"));
                    assertNotNull(empty, where + ": empty state"); assertTrue(fx(empty::isVisible));
                    assertEquals("No available options", fx(empty::getText)); assertTrue(shown.getHeight() < 70, where + ": compact empty list " + shown.getHeight());
                    assertTrue(fx(() -> box.getItems().isEmpty()), "no option is invented");
                    report.append(where).append(": empty, ").append((int) shown.getHeight()).append(" px tall\n");
                }
                composite(box, list, entry.getKey() + "-light-" + opacity);
                action(box::hide); AppearanceGuiTest.settle();
            }
        }
        System.out.print(report);
    }

    @Test void everyThemeModeAndCustomColourRestylesTheOpenListAndTheKeyboardStillWorks() throws Exception {
        start(); nav("F01");
        var box = combo("session-status-filter");
        Object before = fx(box::getValue);
        for (boolean dark : new boolean[] { false, true }) {
            for (Theme.Accent accent : Theme.Accent.values()) {
                appearance(accent, null, dark, 40);
                var list = open(box);
                action(() -> list.getSelectionModel().select(2)); AppearanceGuiTest.settle();
                var selected = fx(() -> list.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(ListCell::isSelected).findFirst().orElseThrow());
                Color fill = fx(() -> (Color) selected.getBackground().getFills().getFirst().getFill());
                Color soft = ThemeTest.color(ThemeManager.get().theme().tokens().get("-pp-selected-soft"));
                assertEquals(soft, fill, (dark ? "dark " : "light ") + accent + ": selection uses the theme's soft tint");
                assertEquals(ThemeTest.color(ThemeManager.get().theme().tokens().get("-pp-accent-text")), fx(() -> (Color) selected.getTextFill()));
                if (accent == Theme.Accent.SAGE || accent == Theme.Accent.ROSE) composite(box, list, "theme-" + accent.id + (dark ? "-dark" : "-light"));
                action(box::hide); action(() -> box.setValue(before)); AppearanceGuiTest.settle();
            }
            appearance(Theme.Accent.BLUE, 190, dark, 40);
            var list = open(box);
            Color accentText = ThemeTest.color(ThemeManager.get().theme().tokens().get("-pp-accent-text"));
            action(() -> list.getSelectionModel().select(1)); AppearanceGuiTest.settle();
            assertEquals(accentText, fx(() -> list.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(ListCell::isSelected).findFirst().orElseThrow().getTextFill()), "custom colour");
            composite(box, list, "theme-custom190" + (dark ? "-dark" : "-light"));
            action(box::hide); action(() -> box.setValue(before)); AppearanceGuiTest.settle();
        }
        // Keyboard: arrows move the choice, Enter confirms and closes, Escape closes; the filter callback still runs.
        appearance(Theme.Accent.BLUE, null, false, 62);
        var list = open(box);
        assertTrue(fx(list::isFocused), "the open list has keyboard focus");
        key(list, KeyCode.DOWN); key(list, KeyCode.DOWN);
        Object moved = fx(box::getValue);
        assertNotEquals(before, moved, "arrow keys change the choice");
        key(list, KeyCode.ENTER); AppearanceGuiTest.settle();
        assertFalse(fx(box::isShowing), "Enter closes"); assertEquals(moved, fx(box::getValue));
        assertEquals(0, fx(() -> ((TableView<?>) controller.workspace().lookup("#session-table")).getItems().size()), "the status filter still filters");
        open(box); key(fx(() -> (Node) ((DropdownSkin<?>) box.getSkin()).getPopupContent()), KeyCode.ESCAPE); AppearanceGuiTest.settle();
        assertFalse(fx(box::isShowing), "Escape closes"); assertEquals(moved, fx(box::getValue), "Escape keeps the confirmed choice");
        // Mouse, with real input through the platform robot: clicking the control opens it, clicking an option picks it.
        var robot = fx(javafx.scene.robot.Robot::new);
        action(() -> { var at = box.localToScreen(box.getWidth() / 2, box.getHeight() / 2); robot.mouseMove(at); robot.mouseClick(MouseButton.PRIMARY); });
        AppearanceGuiTest.settle();
        assertTrue(fx(box::isShowing), "a click opens the list");
        var reopened = fx(() -> (ListView<?>) ((DropdownSkin<?>) box.getSkin()).getPopupContent());
        var first = fx(() -> reopened.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(c -> c.getIndex() == 0).findFirst().orElseThrow());
        action(() -> { var at = first.localToScreen(first.getWidth() / 2, first.getHeight() / 2); robot.mouseMove(at); });
        AppearanceGuiTest.settle();
        assertTrue(fx(first::isHover), "the pointer highlights the option");
        assertEquals(ThemeTest.color(ThemeManager.get().theme().tokens().get("-pp-hover")), fx(() -> (Color) first.getBackground().getFills().getFirst().getFill()), "hover uses the soft hover tint");
        action(() -> robot.mouseClick(MouseButton.PRIMARY)); AppearanceGuiTest.settle();
        assertEquals("", fx(box::getValue), "a click picks the option"); assertFalse(fx(box::isShowing), "and closes the list");
    }

    @Test void listsStayInsideTheWindowOpenUpwardNearTheBottomScrollWhenLongAndTranslateTheEmptyState() throws Exception {
        start();
        // A long list near the bottom of a minimum-size window opens upward, stays inside and scrolls.
        var probe = new ComboBox<String>(); probe.setId("probe");
        for (int i = 1; i <= 40; i++) probe.getItems().add("Option " + i + (i == 7 ? " with a considerably longer label that must not widen the list without limit" : ""));
        action(() -> { stage.setWidth(880); stage.setHeight(600); var host = (StackPane) wrap(controller.workspace());
            StackPane.setAlignment(probe, javafx.geometry.Pos.BOTTOM_LEFT); StackPane.setMargin(probe, new javafx.geometry.Insets(0, 0, 24, 300)); host.getChildren().add(probe); });
        AppearanceGuiTest.settle();
        var list = open(probe);
        Bounds control = fx(() -> probe.localToScreen(probe.getLayoutBounds())), shown = fx(() -> list.localToScreen(list.getLayoutBounds()));
        assertEquals(control.getMinY() - DropdownSkin.GAP, shown.getMaxY(), 1.01, "opens upward when the room below is too small");
        assertTrue(fx(() -> inside(shown, probe.getScene())), "inside the window " + shown);
        assertTrue(fx(() -> list.lookupAll(".scroll-bar").stream().anyMatch(n -> n.isVisible() && ((ScrollBar) n).getOrientation() == javafx.geometry.Orientation.VERTICAL)), "long lists scroll");
        assertTrue(fx(() -> list.lookupAll(".scroll-bar").stream().noneMatch(n -> n.isVisible() && ((ScrollBar) n).getOrientation() == javafx.geometry.Orientation.HORIZONTAL)), "no sideways scrolling: the list fits its longest option");
        var longest = fx(() -> list.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(c -> c.getText() != null && c.getText().contains("longer")).findFirst().orElseThrow());
        assertTrue(fx(() -> longest.getChildrenUnmodifiable().stream().filter(n -> n instanceof javafx.scene.text.Text).map(n -> ((javafx.scene.text.Text) n).getText()).allMatch(longest.getText()::equals)), "the long option is shown in full");
        composite(probe, list, "long-list-upward-880");
        action(probe::hide);
        // The empty state is localized and follows a live language change.
        nav("F10");
        var box = combo("plan-session");
        action(() -> manager.activeContext().localization().selectLanguage("zh-CN")); idle();
        var empty = open(box);
        assertEquals("暂无可选项", fx(() -> ((Label) empty.lookup(".dropdown-empty")).getText()));
        composite(box, empty, "plan-session-empty-zh");
        action(box::hide);
        action(() -> manager.activeContext().localization().selectLanguage("en")); idle();
    }

    @Test void everyDropdownInEveryPageUsesTheSharedDesignAndShowsItsRealOptions() throws Exception {
        manager = new ProfileManager(Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()));
        // A real session, so the session selectors have an option; nothing else is seeded.
        assertTrue(manager.activeContext().facade().sessions().create("Sunrise at the lake", java.time.LocalDate.of(2026, 11, 20)).isSuccess());
        manager.close(); manager = null;
        start();
        appearance(Theme.Accent.TEAL, null, false, 35);
        int opened = 0; Set<String> seen = new TreeSet<>();
        for (String page : List.of("F01", "F02", "F03", "F04", "F05", "F06", "F07", "F08", "F09", "F10", "F11", "app-settings")) {
            nav(page);
            var boxes = fx(() -> controller.workspace().lookupAll(".combo-box").stream().filter(DropdownGuiTest::shown).map(n -> (ComboBox<?>) n).toList());
            for (var box : boxes) {
                String where = page + " " + fx(box::getId);
                assertInstanceOf(DropdownSkin.class, fx(box::getSkin), where);
                assertEquals(8, fx(() -> box.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius()), .01, where + " control radius");
                if (fx(box::isDisabled)) continue;
                action(() -> reveal(box)); AppearanceGuiTest.settle();
                var list = open(box); opened++; seen.add(where);
                Bounds shown = fx(() -> list.localToScreen(list.getLayoutBounds()));
                assertTrue(fx(() -> inside(shown, box.getScene())), where + ": inside the window");
                assertTrue(fx(((DropdownSkin<?>) fx(box::getSkin))::frosted), where + ": frosted");
                assertTrue(fx(() -> list.lookupAll(".scroll-bar").stream().noneMatch(n -> n.isVisible() && ((ScrollBar) n).getOrientation() == javafx.geometry.Orientation.HORIZONTAL)), where + ": no sideways scrolling");
                int items = fx(() -> box.getItems().size());
                if (items == 0) assertTrue(fx(() -> list.lookup(".dropdown-empty").isVisible()), where + ": empty state");
                else {
                    var cells = fx(() -> list.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(c -> c.isVisible() && !c.isEmpty()).toList());
                    assertFalse(cells.isEmpty(), where + ": options are rendered");
                    var shot = fx(() -> list.getScene().snapshot(null));
                    for (var cell : cells) {
                        Bounds b = fx(() -> cell.localToScene(cell.getLayoutBounds()));
                        if (b.getMaxY() > shot.getHeight()) continue;
                        Color behind = shot.getPixelReader().getColor((int) (b.getMaxX() - 4), (int) b.getCenterY());
                        assertTrue(ThemeTest.contrast(fx(() -> (Color) cell.getTextFill()), behind) >= 4.5, where + ": '" + fx(cell::getText) + "' readable");
                        assertFalse(fx(cell::getText).isBlank() && fx(() -> cell.getItem() != null && !cell.getItem().toString().isEmpty()), where + ": option text is visible");
                    }
                }
                if (seen.size() <= 12) composite(box, list, "page-" + page + "-" + fx(box::getId));
                action(box::hide); AppearanceGuiTest.settle();
            }
        }
        System.out.println("Opened " + opened + " dropdowns: " + seen);
        assertTrue(seen.containsAll(List.of("F01 session-status-filter", "F10 plan-session", "app-settings settings-language")), seen.toString());
        assertTrue(opened >= 8, "dropdowns across the application were checked: " + seen);
    }

    @Test void aDropdownInADialogStyledOnItsPaneLooksTheSame() throws Exception {
        // Like the startup recovery dialog: photopilot.css on the dialog pane, not on the scene.
        var box = new ComboBox<String>(); box.getItems().addAll("Default", "Studio");
        Stage[] dialog = new Stage[1];
        action(() -> { var pane = new DialogPane(); pane.setContent(box); pane.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm());
            ThemeManager.get().style(pane.getStylesheets()); dialog[0] = new Stage(); dialog[0].setScene(new Scene(pane, 420, 260)); dialog[0].show(); });
        try {
            AppearanceGuiTest.settle();
            assertInstanceOf(DropdownSkin.class, fx(box::getSkin));
            var list = open(box);
            assertEquals(12, fx(() -> list.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius()), .01, "the shared list design");
            assertEquals(ThemeTest.color(ThemeManager.get().theme().tokens().get("-pp-surface-2")).getOpacity(),
                fx(() -> ((Color) list.getBackground().getFills().getFirst().getFill()).getOpacity()), .01, "the shared surface opacity");
            action(box::hide);
        } finally { action(() -> dialog[0].close()); }
    }

    /** Puts the workspace's centre content in a StackPane so a probe control can sit near the window's bottom edge. */
    static Parent wrap(PhotoPilotWorkspace view) {
        var centre = view.getCenter();
        if (centre instanceof StackPane stack && "probe-host".equals(stack.getId())) return stack;
        var stack = new StackPane(centre); stack.setId("probe-host"); view.setCenter(stack); return stack;
    }
    /** On the current page: the control and every ancestor are visible. */
    static boolean shown(Node node) {
        if (node.getScene() == null) return false;
        for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible()) return false;
        return true;
    }
    /** Scrolls the page so the control is in view, as a user would before opening it. */
    static void reveal(Node node) {
        for (Node n = node.getParent(); n != null; n = n.getParent()) if (n instanceof ScrollPane scroll && scroll.getContent() != null) {
            var content = scroll.getContent();
            double y = content.sceneToLocal(node.localToScene(0, 0)).getY();
            double range = content.getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
            scroll.setVvalue(range <= 0 ? 0 : Math.max(0, Math.min(1, (y - 80) / range)));
            scroll.layout();
            return;
        }
    }
    static boolean inside(Bounds screen, Scene scene) {
        var window = scene.getWindow();
        double left = window.getX() + scene.getX(), top = window.getY() + scene.getY();
        return screen.getMinX() >= left - .5 && screen.getMinY() >= top - .5 && screen.getMaxX() <= left + scene.getWidth() + .5 && screen.getMaxY() <= top + scene.getHeight() + .5;
    }
    static void key(Node target, KeyCode code) throws Exception {
        action(() -> { target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false)); });
        AppearanceGuiTest.settle();
    }
    /**
     * What is actually on screen: a capture of the window's content area with the open dropdown, which is a separate
     * transparent popup window composited by the operating system (a scene snapshot would paint its clear area white).
     */
    static void composite(ComboBox<?> box, ListView<?> list, String name) throws Exception {
        fx(() -> {
            var scene = box.getScene(); var window = scene.getWindow();
            var shot = new javafx.scene.robot.Robot().getScreenCapture(null, window.getX() + scene.getX(), window.getY() + scene.getY(), scene.getWidth(), scene.getHeight(), true);
            var out = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < out.getHeight(); y++) for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Files.createDirectories(Path.of("target", "dropdowns"));
            javax.imageio.ImageIO.write(out, "png", Path.of("target", "dropdowns", name + ".png").toFile());
            return true;
        });
    }
}
