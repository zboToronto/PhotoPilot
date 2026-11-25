package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.repository.JsonLocalePreferenceRepository;
import com.photopilot.ui.gui.DropdownContrast.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static com.photopilot.ui.gui.DropdownGuiTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Final GUI stabilization (2026-11-24): the open dropdown's text measured on the pixels
 * JavaFX actually renders (the popup scene: frost and list as painted), in the configurations that were below 4.5:1, over
 * an unusually dark or bright area under an almost clear list, while the appearance changes with the list open, after
 * reopening, in Chinese, with keyboard focus, for the closed and disabled control, and across a profile switch (in a
 * fresh JVM with a real preference store, as the application starts). The rendered background is also checked against
 * the range DropdownContrast computed for it, so the model and the screen agree.
 */
class DropdownContrastGuiTest {
    @TempDir Path root;
    Stage stage; GuiProfileController controller; ProfileManager manager;

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
        idle(controller);
        action(() -> ((Button) controller.picker().lookup("#profile-enter")).fire()); idle(controller);
        action(() -> { stage.setAlwaysOnTop(true); stage.toFront(); });
        AppearanceGuiTest.settle();
        // Nothing in the window is hovered unless a test points at it (DropdownGuiTest QA-01).
        var robot = fx(javafx.scene.robot.Robot::new);
        var outside = fx(() -> DropdownGuiTest.outside(stage));
        action(() -> robot.mouseMove(outside));
    }
    static void idle(GuiProfileController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }
    static void nav(GuiProfileController controller, String id) throws Exception {
        action(() -> ((ToggleButton) controller.workspace().lookup("#nav-" + id)).fire()); idle(controller);
    }
    @SuppressWarnings("unchecked") static ComboBox<Object> combo(GuiProfileController controller, String id) throws Exception {
        return fx(() -> (ComboBox<Object>) controller.workspace().lookup("#" + id));
    }

    // ---- measurement ---------------------------------------------------------------------------------------------------
    /** One rendered option: its state, the painted text colour, the pixel behind it, their contrast and the model's range. */
    record Measured(String text, State state, Color fill, Color expected, Color behind, double contrast, double[] range) {
        boolean inModel() { double l = ThemeTest.luminance(behind); return range == null || l >= range[0] - .012 && l <= range[1] + .012; }
        @Override public String toString() {
            return String.format(Locale.ROOT, "'%s' %s fill %s on %s = %.2f%s", text, state, Theme.css(fill), Theme.css(behind), contrast,
                range == null ? "" : String.format(Locale.ROOT, " (background L %.3f, model %.3f-%.3f)", ThemeTest.luminance(behind), range[0], range[1]));
        }
    }
    /** The colour the open list paints for the role: the theme's token, or the readable one the skin applied. */
    static Color expected(DropdownSkin<?> skin, Role role) {
        var applied = skin.contrast();
        return applied != null ? applied.text(role) : ThemeTest.color(ThemeManager.get().theme().tokens().get(role.token));
    }
    /** Waits until the skin has measured the open list for the current appearance and its cells are restyled. */
    static void readable(ComboBox<?> box, ListView<?> list) throws Exception {
        var skin = (DropdownSkin<?>) fx(box::getSkin);
        boolean translucent = fx(() -> ThemeManager.get().theme().opacity() < 99);
        assertTrue(waitFx(() -> {
            var applied = skin.contrast();
            boolean measured = applied != null && applied.palette().equals(Palette.of(ThemeManager.get().theme()));
            if (!measured || skin.frosted() != translucent) return false;
            // The CSS pass after the restyle: every shown option paints its role's colour.
            return list.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(c -> !c.isEmpty() && DropdownGuiTest.shown(c))
                .allMatch(c -> expected(skin, c.isSelected() ? Role.ACCENT : Role.TEXT).equals(c.getTextFill()));
        }, 5000), "the open list is measured and restyled for " + fx(() -> ThemeManager.get().theme()));
    }
    /**
     * Renders the open list's popup scene (the frost and the list, exactly as painted inside the list) and measures every
     * shown option at text-free points: in its left padding, and right of its text when there is room.
     */
    static List<Measured> measure(ComboBox<?> box, ListView<?> list) throws Exception {
        return fx(() -> {
            var skin = (DropdownSkin<?>) box.getSkin();
            var shot = list.getScene().snapshot(null);
            List<Measured> out = new ArrayList<>();
            for (var node : list.lookupAll(".list-cell")) {
                var cell = (ListCell<?>) node;
                if (cell.isEmpty() || !DropdownGuiTest.shown(cell)) continue;
                Bounds b = cell.localToScene(cell.getLayoutBounds());
                if (b.getMinY() < 0 || b.getMaxY() > shot.getHeight()) continue;
                double textRight = cell.lookupAll(".text").stream().filter(Node::isVisible).mapToDouble(t -> t.localToScene(t.getLayoutBounds()).getMaxX()).max().orElse(b.getMinX() + 10);
                List<Double> xs = new ArrayList<>(List.of(b.getMinX() + 4));
                if (b.getMaxX() - 6 > textRight + 3) xs.add(b.getMaxX() - 6);
                State state = cell.isSelected() ? State.SELECTED : cell.isHover() ? State.HOVER : State.PLAIN;
                Color fill = (Color) cell.getTextFill(), expected = expected(skin, state == State.SELECTED ? Role.ACCENT : Role.TEXT);
                for (double x : xs) {
                    Color behind = shot.getPixelReader().getColor((int) x, (int) b.getCenterY());
                    out.add(new Measured(cell.getText(), state, fill, expected, behind, ThemeTest.contrast(fill, behind), skin.contrast() == null ? null : skin.contrast().range(state)));
                }
            }
            return out;
        });
    }
    static void assertReadable(List<Measured> measured, String where) {
        assertFalse(measured.isEmpty(), where + ": options are rendered");
        for (var m : measured) {
            assertEquals(m.expected(), m.fill(), where + ": " + m + " paints its role's colour");
            assertTrue(m.contrast() >= DropdownContrast.TARGET, where + ": " + m);
            assertTrue(m.inModel(), where + ": the rendered background lies in the range the check used: " + m);
        }
    }
    static double weakest(List<Measured> measured) { return measured.stream().mapToDouble(Measured::contrast).min().orElse(0); }
    /** The open list as rendered (popup scene), for the record; DropdownGuiTest.composite captures the screen. */
    static void save(ListView<?> list, String name) throws Exception {
        fx(() -> {
            var shot = list.getScene().snapshot(null);
            var out = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < out.getHeight(); y++) for (int x = 0; x < out.getWidth(); x++) out.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Files.createDirectories(Path.of("target", "dropdown-contrast"));
            javax.imageio.ImageIO.write(out, "png", Path.of("target", "dropdown-contrast", name + ".png").toFile());
            return true;
        });
    }
    static String style(ListView<?> list) throws Exception { return fx(list::getStyle); }
    /** The list carries the control's font (the Chinese UI font, Bold Text) and, after it, exactly the readable overrides. */
    static void assertFonted(ComboBox<?> box, ListView<?> list) throws Exception {
        String style = style(list), overrides = fx(() -> ((DropdownSkin<?>) box.getSkin()).contrast() == null ? "" : ((DropdownSkin<?>) box.getSkin()).contrast().style().strip());
        assertTrue(style.startsWith("-fx-font-family: \"" + fx(() -> ((Labeled) box.lookup(".list-cell")).getFont().getFamily()) + "\"; -fx-font-weight: "), "the control's font: " + style);
        assertTrue(style.endsWith(overrides), "followed by the readable overrides '" + overrides + "': " + style);
    }

    // ---- tests -----------------------------------------------------------------------------------------------------------
    record Case(String name, Theme.Accent accent, Integer hue, boolean dark, int opacity) { }

    @Test void optionTextKeeps45OnTheRenderedPixelsInTheConfigurationsThatWereBelowIt() throws Exception {
        start(); nav(controller, "F01");
        var box = combo(controller, "session-status-filter");
        Object before = fx(box::getValue);
        var report = new StringBuilder();
        for (var c : List.of(new Case("cool-blue-light-0", Theme.Accent.BLUE, null, false, 0), new Case("cool-blue-dark-0", Theme.Accent.BLUE, null, true, 0),
                new Case("custom60-dark-40", Theme.Accent.BLUE, 60, true, 40), new Case("custom240-dark-40", Theme.Accent.BLUE, 240, true, 40),
                new Case("peach-dark-0", Theme.Accent.PEACH, null, true, 0), new Case("sage-dark-50", Theme.Accent.SAGE, null, true, 50),
                new Case("teal-light-25", Theme.Accent.TEAL, null, false, 25), new Case("rose-light-10", Theme.Accent.ROSE, null, false, 10),
                new Case("cool-blue-light-62-default", Theme.Accent.BLUE, null, false, 62), new Case("cool-blue-dark-62", Theme.Accent.BLUE, null, true, 62),
                new Case("sage-light-100", Theme.Accent.SAGE, null, false, 100))) {
            appearance(c.accent(), c.hue(), c.dark(), c.opacity());
            var list = open(box);
            action(() -> list.getSelectionModel().select(2)); AppearanceGuiTest.settle();
            readable(box, list);
            var measured = measure(box, list);
            assertReadable(measured, c.name());
            var skin = (DropdownSkin<?>) fx(box::getSkin);
            String applied = fx(() -> skin.contrast().style());
            // An opaque list and the default appearance paint the theme's own option, secondary and selected text.
            if (c.opacity() >= DropdownSkin.OPAQUE || c.name().endsWith("default")) {
                var palette = Palette.of(fx(() -> ThemeManager.get().theme()));
                for (Role role : List.of(Role.TEXT, Role.MUTED, Role.ACCENT)) assertEquals(palette.text().get(role), fx(() -> skin.contrast().text(role)), c.name() + ": theme's " + role);
            }
            report.append(String.format(Locale.ROOT, "%-28s weakest %.2f over %d samples; overrides: %s%n", c.name(), weakest(measured), measured.size(), applied.isEmpty() ? "none" : applied));
            save(list, "open-" + c.name()); composite(box, list, "contrast-" + c.name());
            action(box::hide); action(() -> box.setValue(before)); AppearanceGuiTest.settle();
        }
        System.out.print(report);
    }

    @Test void aDarkOrBrightAreaUnderAnAlmostClearListIsCoveredOnThatListOnly() throws Exception {
        start(); nav(controller, "F01");
        // A dark (and then a bright) photograph-like area right under where the probe's list opens.
        var probe = new ComboBox<String>(); probe.setId("contrast-probe");
        for (int i = 1; i <= 6; i++) probe.getItems().add("Probe option " + i);
        var photo = new Region(); photo.setMinSize(420, 260); photo.setMaxSize(420, 260); photo.setStyle("-fx-background-color: black;");
        var column = new VBox(4, probe, photo); column.setMaxSize(440, 320); column.setPickOnBounds(false);
        action(() -> { var host = (StackPane) wrap(controller.workspace()); StackPane.setAlignment(column, Pos.TOP_LEFT); StackPane.setMargin(column, new Insets(40, 0, 0, 40)); host.getChildren().add(column); });
        AppearanceGuiTest.settle();
        var report = new StringBuilder();
        // A mid-grey area: the theme's own selected text falls below 4.5:1 on the rendered pixels, and moving the text colour
        // alone is enough (no protection).
        appearance(Theme.Accent.BLUE, null, false, 0);
        action(() -> photo.setStyle("-fx-background-color: #999999;"));
        var grey = open(probe);
        // A lower option, whose frost is all grey (the blur reaches about 40 px past the area's top edge).
        action(() -> grey.getSelectionModel().select(4)); AppearanceGuiTest.settle();
        readable(probe, grey);
        var greySkin = (DropdownSkin<?>) fx(probe::getSkin);
        assertFalse(fx(() -> greySkin.contrast().protectedSurface()), "the text colour alone reaches 4.5:1 over grey");
        Color themeAccent = ThemeTest.color(fx(() -> ThemeManager.get().theme().tokens().get("-pp-accent-text")));
        var greyMeasured = measure(probe, grey);
        assertReadable(greyMeasured, "grey-under-blue-light-0");
        var selectedSample = greyMeasured.stream().filter(m -> m.state() == State.SELECTED).findFirst().orElseThrow();
        double withoutFix = ThemeTest.contrast(themeAccent, selectedSample.behind());
        assertTrue(withoutFix < DropdownContrast.TARGET && !selectedSample.fill().equals(themeAccent),
            "on these rendered pixels the theme's accent text would read " + withoutFix + "; painted " + selectedSample);
        report.append(String.format(Locale.ROOT, "%-26s selected option: theme colour %.2f -> painted %.2f; weakest %.2f; overrides: %s%n", "grey-under-blue-light-0",
            withoutFix, selectedSample.contrast(), weakest(greyMeasured), fx(() -> greySkin.contrast().style())));
        save(grey, "open-grey-under-blue-light-0"); composite(probe, grey, "contrast-grey-under-blue-light-0");
        action(probe::hide); AppearanceGuiTest.settle();
        for (var c : List.of(new Case("black-under-blue-light-0", Theme.Accent.BLUE, null, false, 0), new Case("white-under-sage-dark-0", Theme.Accent.SAGE, null, true, 0))) {
            action(() -> photo.setStyle("-fx-background-color: " + (c.dark() ? "white" : "black") + ";"));
            appearance(c.accent(), c.hue(), c.dark(), c.opacity());
            String tokens = fx(() -> ThemeManager.get().theme().stylesheet());
            var list = open(probe);
            action(() -> list.getSelectionModel().select(1)); AppearanceGuiTest.settle();
            readable(probe, list);
            var skin = (DropdownSkin<?>) fx(probe::getSkin);
            var applied = fx(skin::contrast);
            double token = ThemeTest.color(fx(() -> ThemeManager.get().theme().tokens().get("-pp-surface-2"))).getOpacity();
            assertTrue(applied.protectedSurface(), c.name() + ": the list's own surface is made more opaque");
            double painted = fx(() -> ((Color) list.getBackground().getFills().getFirst().getFill()).getOpacity());
            assertEquals(applied.surfaceAlpha(), painted, .002, c.name() + ": this list paints the protected surface");
            assertTrue(painted > token + .05, c.name() + ": more opaque than the theme's " + token);
            var measured = measure(probe, list);
            assertReadable(measured, c.name());
            assertFonted(probe, list);
            assertEquals(tokens, fx(() -> ThemeManager.get().theme().stylesheet()), c.name() + ": the theme and its Transparency are unchanged");
            report.append(String.format(Locale.ROOT, "%-26s surface %.3f (theme %.3f), weakest %.2f; overrides: %s%n", c.name(), painted, token, weakest(measured), applied.style()));
            save(list, "open-" + c.name()); composite(probe, list, "contrast-" + c.name());
            action(probe::hide); AppearanceGuiTest.settle();
        }
        // Without that area under it the same list opens with the theme's surface again.
        action(() -> column.getChildren().remove(photo)); AppearanceGuiTest.settle();
        var list = open(probe); readable(probe, list);
        var skin = (DropdownSkin<?>) fx(probe::getSkin);
        assertFalse(fx(() -> skin.contrast().protectedSurface()), "no protection over the background");
        assertEquals(ThemeTest.color(fx(() -> ThemeManager.get().theme().tokens().get("-pp-surface-2"))).getOpacity(),
            fx(() -> ((Color) list.getBackground().getFills().getFirst().getFill()).getOpacity()), .002, "the theme's surface");
        action(probe::hide);
        System.out.print(report);
    }

    @Test void anEstimatedPlaceCandidatesAmberLineIsReadableInTheOpenList() throws Exception {
        start(); nav(controller, "F01");
        // The F09 location list's cell (EnvironmentPane.CandidateCell): a name and, for an AI estimate (:estimated), an amber
        // second line (-env-warn, defined by the F09 page). Built here inside an .env-page container, as on that page.
        var probe = new ComboBox<String>(); probe.setId("estimate-probe");
        for (int i = 1; i <= 5; i++) probe.getItems().add("Estimated place " + i);
        var estimated = javafx.css.PseudoClass.getPseudoClass("estimated");
        probe.setCellFactory(view -> new ListCell<>() {
            private final Label name = new Label(), detail = new Label();
            private final VBox box = new VBox(2, name, detail);
            { getStyleClass().add("env-candidate-cell"); name.getStyleClass().add("env-candidate-name"); detail.getStyleClass().add("env-candidate-detail"); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty); setText(null);
                pseudoClassStateChanged(estimated, !empty && item != null);
                if (empty || item == null) { setGraphic(null); return; }
                name.setText(item); detail.setText("Ontario, Canada · AI estimate"); setGraphic(box);
            }
        });
        var page = new VBox(probe); page.getStyleClass().add("env-page"); page.setMaxSize(360, 60); page.setPickOnBounds(false);
        action(() -> { var host = (StackPane) wrap(controller.workspace()); StackPane.setAlignment(page, Pos.TOP_LEFT); StackPane.setMargin(page, new Insets(40, 0, 0, 40)); host.getChildren().add(page); });
        AppearanceGuiTest.settle();
        var report = new StringBuilder();
        for (var c : List.of(new Case("estimate-blue-light-0", Theme.Accent.BLUE, null, false, 0), new Case("estimate-blue-light-62", Theme.Accent.BLUE, null, false, 62),
                new Case("estimate-blue-light-100", Theme.Accent.BLUE, null, false, 100), new Case("estimate-teal-dark-0", Theme.Accent.TEAL, null, true, 0),
                new Case("estimate-custom60-dark-40", Theme.Accent.BLUE, 60, true, 40))) {
            appearance(c.accent(), c.hue(), c.dark(), c.opacity());
            var list = open(probe);
            action(() -> list.getSelectionModel().select(2)); AppearanceGuiTest.settle();
            readable(probe, list);
            var skin = (DropdownSkin<?>) fx(probe::getSkin);
            Color warn = fx(() -> skin.contrast().text(Role.WARN)), themeWarn = fx(() -> skin.contrast().palette().text().get(Role.WARN));
            assertTrue(waitFx(() -> list.lookupAll(".env-candidate-detail").stream().filter(Node::isVisible).allMatch(n -> warn.equals(((Label) n).getTextFill())), 5000),
                c.name() + ": the estimate lines paint " + warn);
            double[] weakest = fx(() -> {
                var shot = list.getScene().snapshot(null);
                double painted = Double.MAX_VALUE, original = Double.MAX_VALUE;
                for (var node : list.lookupAll(".list-cell")) {
                    var cell = (ListCell<?>) node;
                    if (cell.isEmpty() || !DropdownGuiTest.shown(cell)) continue;
                    Bounds b = cell.localToScene(cell.getLayoutBounds());
                    if (b.getMinY() < 0 || b.getMaxY() > shot.getHeight()) continue;
                    var detail = (Label) cell.lookup(".env-candidate-detail");
                    Bounds d = detail.localToScene(detail.getLayoutBounds());
                    Color behind = shot.getPixelReader().getColor((int) (b.getMinX() + 4), (int) d.getCenterY());
                    State state = cell.isSelected() ? State.SELECTED : cell.isHover() ? State.HOVER : State.PLAIN;
                    double[] range = skin.contrast().range(state), l = { ThemeTest.luminance(behind) };
                    assertTrue(l[0] >= range[0] - .012 && l[0] <= range[1] + .012, c.name() + ": background " + l[0] + " in the checked range " + range[0] + "-" + range[1]);
                    painted = Math.min(painted, ThemeTest.contrast((Color) detail.getTextFill(), behind));
                    original = Math.min(original, ThemeTest.contrast(themeWarn, behind));
                }
                return new double[] { painted, original };
            });
            assertTrue(weakest[0] >= DropdownContrast.TARGET, c.name() + ": estimate line " + weakest[0]);
            report.append(String.format(Locale.ROOT, "%-26s estimate line: F09 amber %.2f -> painted %.2f (%s)%n", c.name(), weakest[1], weakest[0], Theme.css(warn)));
            save(list, "open-" + c.name()); composite(probe, list, "contrast-" + c.name());
            action(probe::hide); AppearanceGuiTest.settle();
        }
        System.out.print(report);
    }

    @Test void theOpenListStaysReadableWhileTheAppearanceChangesAfterReopeningInChineseWithKeyboardFocusAndWhenClosedOrDisabled() throws Exception {
        start(); nav(controller, "F01");
        var box = combo(controller, "session-status-filter");
        Object before = fx(box::getValue);
        var report = new StringBuilder();
        appearance(Theme.Accent.BLUE, null, false, 0);
        var list = open(box);
        action(() -> list.getSelectionModel().select(2)); AppearanceGuiTest.settle();
        // While the list stays open: mode and custom hue, accent, Transparency, opaque, and back.
        for (var c : List.of(new Case("open-custom60-dark-40", Theme.Accent.BLUE, 60, true, 40), new Case("open-sage-dark-40", Theme.Accent.SAGE, null, true, 40),
                new Case("open-sage-dark-10", Theme.Accent.SAGE, null, true, 10), new Case("open-sage-dark-100", Theme.Accent.SAGE, null, true, 100),
                new Case("open-blue-light-0", Theme.Accent.BLUE, null, false, 0))) {
            appearance(c.accent(), c.hue(), c.dark(), c.opacity());
            assertTrue(fx(box::isShowing), c.name() + ": still open");
            readable(box, list);
            var measured = measure(box, list); assertReadable(measured, c.name());
            report.append(String.format(Locale.ROOT, "%-24s weakest %.2f%n", c.name(), weakest(measured)));
        }
        action(box::hide); AppearanceGuiTest.settle();
        // Reopened after the appearance changed while it was closed.
        appearance(Theme.Accent.PEACH, null, true, 0);
        var reopened = open(box); readable(box, reopened);
        var measured = measure(box, reopened); assertReadable(measured, "reopened peach dark 0");
        report.append(String.format(Locale.ROOT, "%-24s weakest %.2f%n", "reopened-peach-dark-0", weakest(measured)));
        // Keyboard: arrows move focus and selection in the open list; the focused option is the selected one.
        key(reopened, KeyCode.DOWN); key(reopened, KeyCode.DOWN); readable(box, reopened);
        var focused = fx(() -> reopened.lookupAll(".list-cell").stream().map(n -> (ListCell<?>) n).filter(c -> c.isFocused() || c.getIndex() == reopened.getFocusModel().getFocusedIndex())
            .filter(c -> !c.isEmpty()).findFirst().orElseThrow());
        assertTrue(fx(focused::isSelected), "the keyboard-focused option is the selected one");
        measured = measure(box, reopened); assertReadable(measured, "keyboard focus");
        report.append(String.format(Locale.ROOT, "%-24s weakest %.2f%n", "keyboard-focus", weakest(measured)));
        action(box::hide); action(() -> box.setValue(before)); AppearanceGuiTest.settle();
        // Simplified Chinese: the list keeps the control's Chinese font beside the readable colours.
        action(() -> manager.activeContext().localization().selectLanguage("zh-CN")); idle(controller);
        appearance(Theme.Accent.BLUE, null, true, 0);
        var chinese = combo(controller, "session-status-filter");
        var zh = open(chinese); action(() -> zh.getSelectionModel().select(2)); AppearanceGuiTest.settle(); readable(chinese, zh);
        measured = measure(chinese, zh); assertReadable(measured, "zh-CN");
        assertTrue(measured.stream().anyMatch(m -> m.text() != null && m.text().codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)), "Chinese options");
        assertFonted(chinese, zh);
        report.append(String.format(Locale.ROOT, "%-24s weakest %.2f; list style: %s%n", "zh-CN", weakest(measured), style(zh)));
        save(zh, "open-zh-cool-blue-dark-0"); composite(chinese, zh, "contrast-zh-cool-blue-dark-0");
        action(chinese::hide); action(() -> chinese.setValue(before)); AppearanceGuiTest.settle();
        action(() -> manager.activeContext().localization().selectLanguage("en")); idle(controller);
        // Closed control: its text on the control surface, as rendered in the window; disabled keeps JavaFX's 40 % look.
        var closed = combo(controller, "session-status-filter");
        for (boolean disabled : new boolean[] { false, true }) {
            action(() -> closed.setDisable(disabled)); AppearanceGuiTest.settle();
            double[] ratio = fx(() -> {
                var shot = closed.getScene().snapshot(null);
                var cell = (ListCell<?>) closed.lookup(".list-cell");
                Bounds b = cell.localToScene(cell.getLayoutBounds());
                Color behind = shot.getPixelReader().getColor((int) (b.getMinX() + 3), (int) b.getCenterY());
                Color text = (Color) cell.getTextFill();
                Color painted = disabled ? ThemeTest.over(new Color(text.getRed(), text.getGreen(), text.getBlue(), closed.getOpacity()), behind) : text;
                return new double[] { ThemeTest.contrast(painted, behind), closed.getOpacity() };
            });
            if (!disabled) assertTrue(ratio[0] >= DropdownContrast.TARGET, "closed dropdown text " + ratio[0]);
            else { assertEquals(.4, ratio[1], 1e-6, "disabled keeps JavaFX's 40 % opacity"); assertTrue(ratio[0] < DropdownContrast.TARGET, "disabled is visibly fainter: " + ratio[0]); }
            report.append(String.format(Locale.ROOT, "%-24s %.2f (opacity %.1f)%n", disabled ? "closed-disabled" : "closed-enabled", ratio[0], ratio[1]));
        }
        action(() -> closed.setDisable(false));
        System.out.print(report);
    }

    // ---- profile switch in a fresh JVM ----------------------------------------------------------------------------------
    /** Child-JVM entry: real startup order of the appearance (ThemeManager.initialize with a store), two users, switches. */
    public static void main(String[] args) {
        var env = Map.of("PHOTOPILOT_DATA_DIR", args[0], "PHOTOPILOT_CONFIG_DIR", args[1]);
        int exit = 0;
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
            var profiles = new ProfileManager(env);
            var first = profiles.activeProfile().value().orElseThrow();
            var second = profiles.createProfile("Night").value().orElseThrow();
            var store = new JsonLocalePreferenceRepository(Path.of(args[1]).resolve("preferences.json"), Path.of(args[1]).resolve("settings.properties"));
            store.forProfile(first.id(), true).merge(Map.of("appearance.accent", "blue", "appearance.mode", "light", "appearance.opacity", "0"));
            store.forProfile(second.id(), true).merge(Map.of("appearance.accent", "custom", "appearance.customHue", "60", "appearance.mode", "dark", "appearance.opacity", "40"));
            ThemeManager.get().initialize(env); ThemeManager.get().track();
            Stage[] window = new Stage[1]; GuiProfileController[] controller = new GuiProfileController[1];
            action(() -> { window[0] = new Stage(); controller[0] = new GuiProfileController(window[0], profiles, first, context -> { }); });
            idle(controller[0]);
            action(() -> ((Button) controller[0].picker().lookup("#profile-enter")).fire()); idle(controller[0]);
            for (var step : List.of(Map.entry("first", first.id()), Map.entry("second", second.id()), Map.entry("first-again", first.id()))) {
                if (!step.getKey().equals("first")) { action(() -> controller[0].requestSwitch(step.getValue())); idle(controller[0]); }
                nav(controller[0], "F01");
                var box = combo(controller[0], "session-status-filter");
                var list = open(box);
                action(() -> list.getSelectionModel().select(2)); AppearanceGuiTest.settle();
                readable(box, list);
                var measured = measure(box, list);
                assertReadable(measured, step.getKey());
                var theme = fx(() -> ThemeManager.get().theme());
                System.out.printf(Locale.ROOT, "DROPDOWN_PROFILE %s custom=%s hue=%d accent=%s dark=%s opacity=%d weakest=%.2f overrides=%s%n", step.getKey(), theme.custom(), theme.customHue(),
                    theme.accent().id, theme.dark(), theme.opacity(), weakest(measured), fx(() -> ((DropdownSkin<?>) box.getSkin()).contrast().style()));
                action(box::hide); AppearanceGuiTest.settle();
            }
            action(() -> { controller[0].close(); window[0].close(); });
            profiles.close();
            System.out.println("DROPDOWN_PROFILE_OK");
        } catch (Throwable failure) {
            System.out.println("DROPDOWN_PROFILE_FAILED");
            failure.printStackTrace(System.out);
            exit = 1;
        }
        System.out.flush();
        Runtime.getRuntime().halt(exit);
    }

    @Test void eachUsersSavedAppearanceGetsReadableDropdownsAcrossAProfileSwitch() throws Exception {
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-cp", System.getProperty("java.class.path"), DropdownContrastGuiTest.class.getName(), root.resolve("data").toString(), root.resolve("config").toString());
        builder.environment().remove("LLM_API_KEY"); builder.environment().remove("GEMINI_API_KEY"); builder.environment().put("PHOTOPILOT_LIVE_GEMINI", "0");
        Path log = root.resolve("profiles.log");
        var process = builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean exited = process.waitFor(180, TimeUnit.SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            System.out.println("[profile switch child]\n" + output.strip());
            assertTrue(exited, "the child finished:\n" + output);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("DROPDOWN_PROFILE first custom=false hue=" + Theme.PRESET + " accent=blue dark=false opacity=0 "), output);
            assertTrue(output.contains("DROPDOWN_PROFILE second custom=true hue=60 accent=blue dark=true opacity=40 "), output);
            assertTrue(output.contains("DROPDOWN_PROFILE first-again custom=false hue=" + Theme.PRESET + " accent=blue dark=false opacity=0 "), output);
            assertTrue(output.contains("DROPDOWN_PROFILE_OK"), output);
        } finally { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }
}
