package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.Language;
import com.photopilot.repository.JsonLocalePreferenceRepository;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import com.photopilot.ui.privacy.ImageReview;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Requires a graphical desktop. Appearance through the real Preferences controls, the chooser and the F04 photo preview. */
class AppearanceGuiTest {
    private static final String CSS = PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm();
    @TempDir Path root;
    Stage stage;
    PhotoPilotWorkspace workspace;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    /** Lets real pulses run, so CSS, layout and the backdrop's post-layout tracking happen exactly as in the application. */
    static void settle() throws Exception { for (int i = 0; i < 4; i++) { action(() -> { }); Thread.sleep(50); } }
    Map<String, String> env() { return Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }
    static Color fill(Region region) { return (Color) region.getBackground().getFills().getFirst().getFill(); }
    Stage show(Parent content) {
        stage = new Stage(); var scene = new Scene(content, 1120, 760); scene.getStylesheets().add(CSS); stage.setScene(scene); stage.show(); return stage;
    }
    void appearance(Theme.Accent accent, Theme.Mode mode, int opacity) throws Exception {
        action(() -> { var theme = ThemeManager.get(); theme.select(accent); theme.select(mode); theme.previewOpacity(opacity); });
        settle();
    }

    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void restoreDefaults() throws Exception {
        action(() -> { var theme = ThemeManager.get(); theme.previewCustomHue(Theme.DEFAULT_CUSTOM_HUE); theme.select(Theme.Accent.BLUE);
            theme.select(Theme.Mode.SYSTEM); theme.previewOpacity(Theme.DEFAULT_OPACITY); theme.save();
            if (stage != null) stage.close(); });
    }

    @Test void appearanceSectionOrdersItsControlsAndRestylesTheOpenWorkspaceInPlace() throws Exception {
        try (var app = new ApplicationBootstrap(env())) {
            action(() -> { workspace = new PhotoPilotWorkspace(app.localization()); show(workspace); workspace.showApplicationSettings(); });
            settle();
            assertEquals(List.of("Theme Color", "Appearance Mode", "Transparency", "Language"), rowLabels());
            assertEquals("Appearance", text("#settings-appearance"));
            assertNull(fx(() -> workspace.lookup("#settings-retry")), "no Save Preferences button: everything saves automatically");
            assertTrue(fx(() -> workspace.lookupAll(".button").stream().noneMatch(n -> n instanceof Button b && b.getText() != null && b.getText().contains("Save"))));
            var sheets = fx(() -> List.copyOf(stage.getScene().getStylesheets()));
            assertEquals(CSS, sheets.getFirst()); assertTrue(sheets.get(1).startsWith("data:text/css;base64,"), "token sheet follows photopilot.css");
            Node page = fx(() -> workspace.lookup("#application-settings"));
            Region panel = fx(() -> (Region) ((Parent) page).getChildrenUnmodifiable().getFirst());
            appearance(Theme.Accent.BLUE, Theme.Mode.LIGHT, Theme.DEFAULT_OPACITY);
            snapshot(stage.getScene(), "appearance-settings-blue-light");
            Color light = fx(() -> (Color) ((Label) workspace.lookup("#settings-appearance")).getTextFill());

            action(() -> ((ToggleButton) workspace.lookup("#settings-mode-dark")).fire()); settle();
            assertTrue(ThemeManager.get().theme().dark()); assertEquals(Theme.Mode.DARK, ThemeManager.get().mode());
            Color dark = fx(() -> (Color) ((Label) workspace.lookup("#settings-appearance")).getTextFill());
            assertTrue(ThemeTest.luminance(light) < .05 && ThemeTest.luminance(dark) > .6, "text follows the mode");
            assertSame(page, fx(() -> workspace.lookup("#application-settings")), "the page is restyled, not rebuilt");
            assertTrue(fx(() -> workspace.atApplicationSettings()));
            for (Theme.Accent accent : Theme.Accent.values()) {
                action(() -> ((ToggleButton) workspace.lookup("#settings-theme-" + accent.id)).fire()); settle();
                assertEquals(accent, ThemeManager.get().accent());
                assertEquals(1, fx(() -> workspace.lookupAll(".theme-swatch").stream().filter(n -> ((ToggleButton) n).isSelected()).count()));
                assertEquals(accent.darkFill, fx(() -> fill((Region) workspace.lookup("#nav-app-settings"))), "selection uses the theme colour");
            }
            snapshot(stage.getScene(), "appearance-settings-rose-dark");
            action(() -> ((ToggleButton) workspace.lookup("#settings-theme-rose")).fire());
            assertTrue(fx(() -> ((ToggleButton) workspace.lookup("#settings-theme-rose")).isSelected()), "re-selecting keeps the choice");

            var slider = fx(() -> (Slider) workspace.lookup("#settings-transparency"));
            var backdrop = fx(() -> (ThemeBackdrop) workspace.lookup("#theme-backdrop"));
            action(() -> slider.setValue(0)); settle();
            assertEquals(0, ThemeManager.get().opacity()); assertEquals("0%", text("#settings-transparency-value"));
            double clear = fx(() -> fill(panel).getOpacity());
            assertTrue(clear < .6, "translucent at 0%");
            assertTrue(fx(backdrop::frostRendered)); assertTrue(fx(backdrop::frostedRegions) >= 3, "blurred waves sit behind the header, sidebar and panel");
            action(() -> slider.setValue(50)); settle();
            assertTrue(fx(() -> fill(panel).getOpacity()) > clear);
            action(() -> slider.setValue(100)); settle();
            assertEquals(1.0, fx(() -> fill(panel).getOpacity()), 1e-3, "opaque at 100%");
            assertEquals(0, (int) fx(backdrop::frostedRegions), "nothing is blurred behind opaque surfaces");

            // The existing language setting keeps working from its new position and leaves appearance alone.
            @SuppressWarnings("unchecked") var languages = fx(() -> (ComboBox<Language>) workspace.lookup("#settings-language"));
            action(() -> languages.setValue(Language.SIMPLIFIED_CHINESE)); settle();
            assertEquals(Language.SIMPLIFIED_CHINESE, app.localization().language());
            assertEquals(List.of("主题颜色", "外观模式", "透明度", "语言"), rowLabels());
            assertEquals("外观", text("#settings-appearance")); assertEquals("深色", text("#settings-mode-dark"));
            assertEquals(Theme.Accent.ROSE, ThemeManager.get().accent()); assertTrue(ThemeManager.get().theme().dark());
            assertEquals("zh-CN", new JsonLocalePreferenceRepository(root.resolve("config/preferences.json"), root.resolve("config/settings.properties")).load());
            // Saved silently: no "Language preference saved." message after the change.
            assertFalse(fx(() -> workspace.lookup("#settings-feedback").isVisible()), "no success message after an automatic save");
            assertEquals("", text("#settings-feedback"));
            snapshot(stage.getScene(), "appearance-settings-zh-dark");
            action(() -> languages.setValue(Language.ENGLISH)); settle();
            assertEquals("Theme Color", rowLabels().getFirst());
            assertFalse(fx(() -> workspace.lookup("#settings-feedback").isVisible()));
            assertEquals("en", new JsonLocalePreferenceRepository(root.resolve("config/preferences.json"), root.resolve("config/settings.properties")).load());
        }
    }

    @Test void customHueAddsALivePastelThemeBesideThePresetsAndSavesWithoutAButton() throws Exception {
        try (var app = new ApplicationBootstrap(env())) {
            action(() -> { workspace = new PhotoPilotWorkspace(app.localization()); show(workspace); workspace.showApplicationSettings(); });
            appearance(Theme.Accent.BLUE, Theme.Mode.LIGHT, Theme.DEFAULT_OPACITY);
            var hueRow = fx(() -> workspace.lookup("#settings-custom-row"));
            assertFalse(fx(hueRow::isVisible), "the hue slider stays hidden while a preset is selected");
            // Five presets, then the Custom option (set apart, not a sixth preset).
            assertEquals(List.of("blue", "sage", "peach", "rose", "teal", "custom"), fx(() -> workspace.lookupAll(".theme-swatch").stream()
                .map(n -> n.getId().substring("settings-theme-".length())).toList()));
            var custom = fx(() -> (ToggleButton) workspace.lookup("#settings-theme-custom"));
            assertEquals("Custom Color", fx(custom::getAccessibleText)); assertEquals("Custom Color", fx(() -> custom.getTooltip().getText()));
            action(custom::fire); settle();
            assertTrue(ThemeManager.get().custom()); assertTrue(fx(hueRow::isVisible), "Custom shows its hue slider beneath the swatches");
            assertEquals(1, fx(() -> workspace.lookupAll(".theme-swatch").stream().filter(n -> ((ToggleButton) n).isSelected()).count()));
            assertTrue(fx(custom::isSelected));
            var slider = fx(() -> (Slider) workspace.lookup("#settings-custom-hue"));
            assertEquals("Custom color hue", fx(slider::getAccessibleText));
            var backdrop = fx(() -> (ThemeBackdrop) workspace.lookup("#theme-backdrop"));
            Color previous = null;
            for (int hue : new int[] { 0, 60, 120, 190, 280 }) {
                action(() -> slider.setValue(hue)); settle();
                assertEquals(hue, ThemeManager.get().customHue()); assertTrue(ThemeManager.get().theme().custom());
                var family = Theme.Family.custom(hue);
                // Controls, the swatch preview and the background all follow the hue together, live.
                assertClose(family.lightFill(), fx(() -> fill((Region) workspace.lookup("#nav-app-settings"))), "selection colour at hue " + hue);
                assertEquals(family.swatch(), fx(() -> ((javafx.scene.shape.Circle) custom.lookup(".custom-swatch-centre")).getFill()));
                assertTrue(fx(backdrop::frostRendered));
                var shot = fx(() -> stage.getScene().snapshot(null));
                Color background = shot.getPixelReader().getColor((int) shot.getWidth() - 14, (int) shot.getHeight() - 14);
                if (previous != null) assertTrue(levelsApart(previous, background) >= 2, "the background is relit for hue " + hue);
                previous = background;
                snapshot(stage.getScene(), "custom-hue-" + hue + "-light");
            }
            // Dark mode keeps the hue and derives its own palette for it (not an inverted Light palette).
            action(() -> ((ToggleButton) workspace.lookup("#settings-mode-dark")).fire()); settle();
            assertEquals(280, ThemeManager.get().customHue()); assertTrue(ThemeManager.get().custom());
            assertClose(Theme.Family.custom(280).darkFill(), fx(() -> fill((Region) workspace.lookup("#nav-app-settings"))), "dark selection colour");
            snapshot(stage.getScene(), "custom-hue-280-dark");
            // A preset hides the slider; Custom brings the same hue back.
            action(() -> ((ToggleButton) workspace.lookup("#settings-theme-sage")).fire()); settle();
            assertFalse(ThemeManager.get().custom()); assertFalse(fx(hueRow::isVisible)); assertFalse(fx(custom::isSelected));
            action(custom::fire); settle();
            assertEquals(280, ThemeManager.get().customHue()); assertEquals(280, fx(slider::getValue), 1e-9);

            // Automatic saving: keyboard or scroll steps are saved once they settle; a drag only when released.
            for (Slider each : List.of(slider, fx(() -> (Slider) workspace.lookup("#settings-transparency")))) {
                action(() -> each.setValue(each.getValue() - 10));
                assertTrue(ThemeManager.get().pending(), "not written on every step");
                Thread.sleep(700); settle();
                assertFalse(ThemeManager.get().pending(), "written once the value settles");
                action(() -> { each.setValueChanging(true); each.setValue(each.getValue() + 5); });
                Thread.sleep(700); settle();
                assertTrue(ThemeManager.get().pending(), "nothing is written while dragging");
                action(() -> each.setValueChanging(false));
                assertFalse(ThemeManager.get().pending(), "written when the drag ends");
            }
            assertFalse(fx(() -> workspace.lookup("#settings-appearance-feedback").isVisible()), "automatic saves show no message");
        }
    }

    @Test void customColourAndSliderValuesSurviveRestartAndOlderFilesStillOpen() throws Exception {
        Path config = Files.createDirectories(root.resolve("config")), json = config.resolve("preferences.json");
        var language = new JsonLocalePreferenceRepository(json, config.resolve("settings.properties"));
        language.save("zh-CN");
        var first = new ThemeManager(); first.open(config);
        assertFalse(first.custom()); assertEquals(Theme.DEFAULT_CUSTOM_HUE, first.customHue());
        assertTrue(first.selectCustom()); first.previewCustomHue(190); first.previewOpacity(33);
        assertTrue(first.pending(), "a live slider value waits for its save");
        first.shutdown();   // normal shutdown writes what is still pending
        var second = new ThemeManager(); second.open(config);
        assertTrue(second.custom()); assertEquals(190, second.customHue()); assertEquals(33, second.opacity());
        assertEquals(190, second.theme().customHue()); assertEquals("zh-CN", language.load(), "appearance never overwrites the language");
        assertTrue(second.select(Theme.Accent.SAGE)); assertTrue(second.select(Theme.Mode.DARK));
        var third = new ThemeManager(); third.open(config);
        assertFalse(third.custom()); assertEquals(Theme.Accent.SAGE, third.accent()); assertEquals(Theme.Mode.DARK, third.mode());
        assertEquals(190, third.customHue(), "the custom hue is remembered while a preset is selected");
        assertTrue(third.selectCustom()); assertEquals(190, third.theme().customHue());
        language.save("en");
        var fourth = new ThemeManager(); fourth.open(config);
        assertTrue(fourth.custom()); assertEquals(190, fourth.customHue()); assertEquals(Theme.Mode.DARK, fourth.mode()); assertEquals("en", language.load());

        // A file written before custom colours existed opens exactly as before and is not rewritten by reading it.
        String older = "{\"schemaVersion\":1,\"values\":{\"language\":\"en\",\"appearance.accent\":\"rose\",\"appearance.mode\":\"light\",\"appearance.opacity\":\"40\"}}";
        Files.writeString(json, older);
        var legacy = new ThemeManager(); legacy.open(config);
        assertEquals(Theme.Accent.ROSE, legacy.accent()); assertFalse(legacy.custom()); assertEquals(40, legacy.opacity());
        assertEquals(Theme.DEFAULT_CUSTOM_HUE, legacy.customHue()); assertEquals(older, Files.readString(json));
        // Invalid or out-of-range hues fall back safely.
        Files.writeString(json, "{\"schemaVersion\":1,\"values\":{\"appearance.accent\":\"custom\",\"appearance.customHue\":\"teal-ish\"}}");
        var invalid = new ThemeManager(); invalid.open(config);
        assertTrue(invalid.custom()); assertEquals(Theme.DEFAULT_CUSTOM_HUE, invalid.customHue());
        Files.writeString(json, "{\"schemaVersion\":1,\"values\":{\"appearance.accent\":\"custom\",\"appearance.customHue\":\"400\"}}");
        var wrapped = new ThemeManager(); wrapped.open(config);
        assertEquals(40, wrapped.customHue());
        // A failed automatic save is reported and never claims success; the hue still applies for the session.
        Files.writeString(json, "{");
        var broken = new ThemeManager(); broken.open(config);
        broken.previewCustomHue(10);
        assertFalse(broken.save()); assertTrue(broken.pending(), "still unsaved after a failure"); assertTrue(broken.saveFailure());
        assertEquals(10, broken.theme().customHue()); assertEquals("{", Files.readString(json));
    }
    static void assertClose(Color expected, Color actual, String message) {
        assertTrue(levelsApart(expected, actual) <= 1.01, message + ": expected " + expected + " but was " + actual);
    }

    @Test void transparencyVisiblyChangesOnlyThePanelBackgroundAtEveryStepInBothModesAndThemes() throws Exception {
        int[] levels = { 0, 25, 50, 75, 100 };
        try (var app = new ApplicationBootstrap(env())) {
            action(() -> { workspace = new PhotoPilotWorkspace(app.localization()); show(workspace); workspace.showApplicationSettings(); });
            settle();
            Region panel = fx(() -> (Region) ((Parent) workspace.lookup("#application-settings")).getChildrenUnmodifiable().getFirst());
            Label heading = fx(() -> (Label) workspace.lookup("#settings-appearance"));
            StringBuilder report = new StringBuilder();
            for (boolean dark : new boolean[] { false, true }) for (Theme.Accent accent : Theme.Accent.values()) {
                Color[][] seen = new Color[levels.length][];
                for (int i = 0; i < levels.length; i++) {
                    appearance(accent, dark ? Theme.Mode.DARK : Theme.Mode.LIGHT, levels[i]);
                    // The slider changes the panel's background only: the panel and its text stay fully opaque nodes.
                    assertEquals(1.0, fx(panel::getOpacity)); assertEquals(1.0, fx(heading::getOpacity));
                    assertEquals(1.0, fx(() -> ((Color) heading.getTextFill()).getOpacity()));
                    var shot = fx(() -> stage.getScene().snapshot(null));
                    var bounds = fx(() -> panel.localToScene(panel.getBoundsInLocal()));
                    // A 12 x 6 grid across the whole rendered panel (text pixels are identical at every level).
                    seen[i] = new Color[72];
                    for (int gx = 0; gx < 12; gx++) for (int gy = 0; gy < 6; gy++)
                        seen[i][gx * 6 + gy] = shot.getPixelReader().getColor((int) (bounds.getMinX() + 10 + (bounds.getWidth() - 20) * (gx + .5) / 12),
                            (int) (bounds.getMinY() + 10 + (bounds.getHeight() - 20) * (gy + .5) / 6));
                    snapshot(stage.getScene(), "transparency-" + accent.id + "-" + (dark ? "dark" : "light") + "-" + levels[i]);
                }
                double[] distance = new double[levels.length];
                for (int i = 0; i < levels.length; i++) {
                    for (int p = 0; p < 72; p++) distance[i] += levelsApart(seen[i][p], seen[levels.length - 1][p]);
                    distance[i] /= 72;
                }
                report.append(String.format(Locale.ROOT, "%s %s: 0%%->100%% differs by %.0f levels; steps %.0f/%.0f/%.0f/%.0f%n",
                    accent, dark ? "dark" : "light", distance[0], distance[0], distance[1], distance[2], distance[3]));
                for (int i = 1; i < levels.length; i++) assertTrue(distance[i] < distance[i - 1], accent + " dark=" + dark + " not monotonic at " + levels[i] + "%");
                assertTrue(distance[0] >= (dark ? 8 : 24), accent + " dark=" + dark + ": 0% and 100% look alike (" + distance[0] + " levels)");
            }
            System.out.print(report);
        }
    }
    /** Largest per-channel difference in 8-bit levels. */
    static double levelsApart(Color a, Color b) {
        return 255 * Math.max(Math.abs(a.getRed() - b.getRed()), Math.max(Math.abs(a.getGreen() - b.getGreen()), Math.abs(a.getBlue() - b.getBlue())));
    }

    @Test void workspaceChooserKeepsItsBehaviourAndFollowsEveryThemeLive() throws Exception {
        try (var manager = new ProfileManager(env())) {
            var initial = manager.activeProfile().value().orElseThrow();
            GuiProfileController[] controller = new GuiProfileController[1];
            action(() -> { stage = new Stage(); controller[0] = new GuiProfileController(stage, manager, initial, context -> { }); });
            idle(controller[0]);
            var picker = fx(() -> controller[0].picker());
            var window = fx(() -> controller[0].pickerWindow());
            assertNotNull(fx(() -> window.getScene().getRoot().lookup("#theme-backdrop")), "the wave background replaces the wallpaper");
            for (boolean dark : new boolean[] { false, true }) for (Theme.Accent accent : Theme.Accent.values()) {
                appearance(accent, dark ? Theme.Mode.DARK : Theme.Mode.LIGHT, Theme.DEFAULT_OPACITY);
                assertSame(picker, fx(() -> controller[0].picker()), "the chooser is restyled, not recreated");
                var enter = fx(() -> (Button) picker.lookup("#profile-enter"));
                assertEquals(dark ? accent.darkOnFill : accent.lightOnFill, fx(enter::getTextFill), "Continue follows the theme colour");
                assertEquals(1, fx(() -> picker.lookupAll(".profile-tile").stream().filter(Node::isVisible).count()));
                assertEquals(ThemeManager.get().theme().backdropBase(), fx(() -> window.getScene().getFill()));
                snapshot(window.getScene(), "appearance-chooser-" + accent.id + "-" + (dark ? "dark" : "light"));
            }
            appearance(Theme.Accent.BLUE, Theme.Mode.LIGHT, 0);
            snapshot(window.getScene(), "appearance-chooser-blue-light-transparent");
            appearance(Theme.Accent.BLUE, Theme.Mode.LIGHT, Theme.DEFAULT_OPACITY);
            action(() -> picker.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false)));
            idle(controller[0]);
            assertNull(fx(() -> controller[0].picker()), "Enter still continues into the workspace");
            assertTrue(fx(() -> controller[0].workspace().atHome()) && fx(stage::isShowing));
            settle(); snapshot(stage.getScene(), "appearance-home-blue-light");
            appearance(Theme.Accent.TEAL, Theme.Mode.DARK, 35);
            snapshot(stage.getScene(), "appearance-home-teal-dark-35");
            action(() -> ((ToggleButton) controller[0].workspace().lookup("#nav-F05")).fire()); settle();
            snapshot(stage.getScene(), "appearance-inventory-teal-dark-35");
            appearance(Theme.Accent.SAGE, Theme.Mode.LIGHT, 0);
            snapshot(stage.getScene(), "appearance-inventory-sage-light-0");
            var shell = fx(() -> controller[0].workspace());
            appearance(Theme.Accent.BLUE, Theme.Mode.DARK, Theme.DEFAULT_OPACITY);
            for (String feature : List.of("F01", "F03", "F08", "F09")) {
                action(() -> ((ToggleButton) shell.lookup("#nav-" + feature)).fire()); settle();
                snapshot(stage.getScene(), "appearance-" + feature + "-blue-dark");
            }
            action(shell::showUserSettings); idle(controller[0]);
            snapshot(stage.getScene(), "appearance-user-settings-blue-dark");
            assertSame(shell, fx(() -> controller[0].workspace()), "appearance changes never rebuild the workspace");
            action(() -> controller[0].close());
        }
    }

    @Test void photographPreviewsKeepTheirExactPixelsInEveryAppearance() throws Exception {
        var image = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 300; y++) for (int x = 0; x < 600; x++) image.setRGB(x, y, x < 300 ? 0xC82828 : 0x1E965A);
        Path photo = root.resolve("photo.png"); javax.imageio.ImageIO.write(image, "png", photo.toFile());
        var executor = Executors.newSingleThreadExecutor();
        try (var app = new ApplicationBootstrap(env())) {
            GearImportPane[] pane = new GearImportPane[1];
            action(() -> {
                workspace = new PhotoPilotWorkspace(app.localization());
                pane[0] = new GearImportPane(app.facade(), executor, app.localization(), new ImagePrivacyBoundary(), workspace::selectInventory);
                workspace.connectImport(pane[0]); show(workspace); ((ToggleButton) workspace.lookup("#nav-F04")).fire();
                ((TextField) workspace.lookup("#import-path")).setText(photo.toString());
                @SuppressWarnings("unchecked") var kind = (ComboBox<ImageReview.Kind>) workspace.lookup("#import-kind");
                kind.setValue(ImageReview.Kind.SCREENSHOT);
                ((Button) workspace.lookup("#import-load")).fire();
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (fx(() -> pane[0].busy())) { if (System.nanoTime() > deadline) fail("preview timed out"); Thread.sleep(20); }
            var view = fx(() -> (ImageView) workspace.lookup("#import-original"));
            assertNotNull(fx(view::getImage), "the photo preview is shown");
            int checked = 0;
            for (boolean dark : new boolean[] { false, true }) for (int opacity : new int[] { 0, 50, 100 }) for (Theme.Accent accent : Theme.Accent.values()) {
                appearance(accent, dark ? Theme.Mode.DARK : Theme.Mode.LIGHT, opacity);
                assertTrue(fx(() -> {
                    for (Node node = view; node != null; node = node.getParent())
                        if (node.getEffect() != null || node.getOpacity() != 1 || node.getBlendMode() != null) return false;
                    return true;
                }), "no effect, fade or blend applies between the photo and the window");
                action(() -> reveal(view)); settle();
                var shot = fx(() -> stage.getScene().snapshot(null));
                var bounds = fx(() -> view.localToScene(view.getBoundsInLocal()));
                assertTrue(bounds.getMinY() >= 0 && bounds.getMaxY() <= shot.getHeight(), "preview is on screen");
                for (double[] sample : new double[][] { { .25, 0xC8, 0x28, 0x28 }, { .75, 0x1E, 0x96, 0x5A } }) {
                    var pixel = shot.getPixelReader().getColor((int) (bounds.getMinX() + bounds.getWidth() * sample[0]), (int) bounds.getCenterY());
                    assertEquals(sample[1] / 255, pixel.getRed(), 2.5 / 255, accent + " " + dark + " " + opacity);
                    assertEquals(sample[2] / 255, pixel.getGreen(), 2.5 / 255); assertEquals(sample[3] / 255, pixel.getBlue(), 2.5 / 255);
                }
                checked++;
            }
            assertEquals(30, checked);
            appearance(Theme.Accent.PEACH, Theme.Mode.DARK, 0);
            snapshot(stage.getScene(), "appearance-photo-preview-peach-dark-0");
        } finally { executor.shutdownNow(); }
    }

    @Test void appearanceSurvivesRestartBesideTheLanguageWhileFailuresNeverOverwrite() throws Exception {
        Path config = Files.createDirectories(root.resolve("config")), json = config.resolve("preferences.json");
        Files.writeString(config.resolve("settings.properties"), "language=zh-CN\ncustom=keep\n");
        var first = new ThemeManager(); first.open(config);
        assertEquals(Theme.Accent.BLUE, first.accent()); assertEquals(Theme.Mode.SYSTEM, first.mode()); assertFalse(Files.exists(json), "reading never writes");
        assertTrue(first.select(Theme.Accent.ROSE)); assertTrue(first.select(Theme.Mode.DARK)); first.previewOpacity(27); assertTrue(first.save());
        var restarted = new ThemeManager(); restarted.open(config);
        assertEquals(Theme.Accent.ROSE, restarted.accent()); assertEquals(Theme.Mode.DARK, restarted.mode()); assertEquals(27, restarted.opacity());
        assertTrue(restarted.theme().dark());
        var language = new JsonLocalePreferenceRepository(json, config.resolve("settings.properties"));
        assertEquals("zh-CN", language.load()); assertEquals("keep", language.values().get("custom"));
        Files.writeString(json, "{\"schemaVersion\":1,\"values\":{\"language\":\"en\",\"appearance.accent\":\"lavender\"}}");
        var merged = new ThemeManager(); merged.open(config);
        assertEquals(Theme.Accent.BLUE, merged.accent(), "a saved Lavender choice opens as Cool Blue");
        Files.writeString(json, "{\"schemaVersion\":1,\"values\":{\"language\":\"en\",\"appearance.accent\":\"neon\",\"appearance.mode\":\"sideways\",\"appearance.opacity\":\"lots\"}}");
        var invalid = new ThemeManager(); invalid.open(config);
        assertEquals(Theme.Accent.BLUE, invalid.accent()); assertEquals(Theme.Mode.SYSTEM, invalid.mode()); assertEquals(Theme.DEFAULT_OPACITY, invalid.opacity());
        assertFalse(invalid.theme().dark(), "Follow System without detection falls back to Light");
        Files.writeString(json, "{");
        var broken = new ThemeManager(); broken.open(config);
        assertTrue(broken.readFailure());
        assertFalse(broken.select(Theme.Accent.SAGE), "a failed save is reported");
        assertEquals(Theme.Accent.SAGE, broken.theme().accent(), "but the appearance still applies for this session");
        assertTrue(broken.saveFailure()); assertEquals("{", Files.readString(json));
    }

    List<String> rowLabels() throws Exception {
        return fx(() -> workspace.lookupAll(".settings-row-label").stream().map(node -> ((Label) node).getText()).toList());
    }
    String text(String id) throws Exception { return fx(() -> ((Labeled) workspace.lookup(id)).getText()); }
    static void idle(GuiProfileController controller) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fx(controller::busy)) { if (System.nanoTime() > deadline) fail("GUI timed out"); Thread.sleep(20); }
        settle();
    }
    /** Scrolls the feature content so the node is inside the visible viewport. */
    void reveal(Node node) {
        var scroll = (ScrollPane) workspace.lookup(".content-scroll");
        var content = scroll.getContent();
        double y = content.sceneToLocal(node.localToScene(0, 0)).getY();
        double range = content.getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
        scroll.setVvalue(range <= 0 ? 0 : Math.max(0, Math.min(1, (y - 24) / range)));
    }
    static void snapshot(Scene scene, String name) throws Exception {
        fx(() -> {
            var image = scene.snapshot(null);
            var buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            Files.createDirectories(Path.of("target", "appearance"));
            javax.imageio.ImageIO.write(buffered, "png", Path.of("target", "appearance", name + ".png").toFile());
            return true;
        });
    }
}
