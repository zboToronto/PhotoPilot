package com.photopilot.ui.gui;

import com.photopilot.application.ProfileManager;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. The procedural wave background in the real application window (workspace chooser,
 * then the workspace): every theme in both modes, Follow System, Transparency, a live hue drag, resizing and the
 * display scale. Each check compares the wave layer on screen with {@link WaveRenderer}'s field for the current theme,
 * and the screenshots are real screen captures of the window (target/backdrop).
 */
class BackdropGuiTest {
    @TempDir Path root;
    Stage stage;
    GuiProfileController controller;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { AppearanceGuiTest.action(action); }
    Map<String, String> env() { return Map.of("PHOTOPILOT_DATA_DIR", root.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", root.resolve("config").toString()); }

    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void restoreDefaults() throws Exception {
        action(() -> { var theme = ThemeManager.get(); theme.systemReported(null); theme.previewCustomHue(Theme.DEFAULT_CUSTOM_HUE);
            theme.select(Theme.Accent.BLUE); theme.select(Theme.Mode.SYSTEM); theme.previewOpacity(Theme.DEFAULT_OPACITY); theme.save();
            if (controller != null) controller.close(); if (stage != null) stage.close(); });
    }

    /** Opens the application window as a user does: the chooser, then Enter into the workspace, at the given size. */
    PhotoPilotWorkspace open(ProfileManager profiles, double width, double height) throws Exception {
        var initial = profiles.activeProfile().value().orElseThrow();
        action(() -> { stage = new Stage(); controller = new GuiProfileController(stage, profiles, initial, context -> { }); });
        AppearanceGuiTest.idle(controller);
        var picker = fx(() -> controller.picker());
        action(() -> picker.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        AppearanceGuiTest.idle(controller);
        action(() -> stage.setAlwaysOnTop(true));   // kept in front for its screen captures
        resize(width, height);
        return fx(() -> controller.workspace());
    }
    void resize(double width, double height) throws Exception {
        action(() -> {
            var screen = Screen.getPrimary().getVisualBounds();
            stage.setMaximized(false);
            stage.setWidth(Math.min(width, screen.getWidth())); stage.setHeight(Math.min(height, screen.getHeight()));
            stage.setX(screen.getMinX() + (screen.getWidth() - stage.getWidth()) / 2); stage.setY(screen.getMinY() + (screen.getHeight() - stage.getHeight()) / 2);
            stage.toFront();
        });
    }
    ThemeBackdrop backdrop() throws Exception { return fx(() -> (ThemeBackdrop) stage.getScene().getRoot().lookup("#theme-backdrop")); }
    static void appearance(Theme.Accent accent, int customHue, boolean dark, int opacity) throws Exception {
        action(() -> {
            var theme = ThemeManager.get();
            if (customHue >= 0) theme.previewCustomHue(customHue); else theme.select(accent);
            theme.select(dark ? Theme.Mode.DARK : Theme.Mode.LIGHT); theme.previewOpacity(opacity);
        });
    }
    /** Waits until the full-quality render of the current theme and size is on screen (it is computed off the FX thread). */
    static void await(ThemeBackdrop backdrop) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AppearanceGuiTest.settle();
        while (!fx(backdrop::settled)) { if (System.nanoTime() > deadline) fail("the background render did not settle"); Thread.sleep(20); }
        AppearanceGuiTest.settle();
    }

    /**
     * The wave layer as drawn equals the procedural field of the current theme at the window's size, everywhere
     * including the corners (no blank or clipped region): within the smooth upscaling of the reduced-size render and
     * the sub-step dither. Returns { mean, worst } difference in 8-bit levels.
     */
    static double[] matchesProceduralField(ThemeBackdrop backdrop, String name) throws Exception {
        var shot = fx(() -> backdrop.wavesView().snapshot(null, null));
        int width = (int) shot.getWidth(), height = (int) shot.getHeight();
        double sceneWidth = fx(() -> backdrop.getScene().getWidth()), sceneHeight = fx(() -> backdrop.getScene().getHeight());
        assertEquals(sceneWidth, width, 1, name + ": the background covers the window width");
        assertEquals(sceneHeight, height, 1, name + ": the background covers the window height");
        float[] field = WaveRenderer.field(ThemeManager.get().theme().lighting(), width, height);
        double total = 0, worst = 0; int count = 0;
        List<int[]> points = new ArrayList<>();
        for (int gx = 0; gx < 32; gx++) for (int gy = 0; gy < 20; gy++) points.add(new int[] { (int) ((gx + .5) * width / 32), (int) ((gy + .5) * height / 20) });
        for (int[] corner : new int[][] { { 1, 1 }, { width - 2, 1 }, { 1, height - 2 }, { width - 2, height - 2 } }) points.add(corner);
        for (int[] p : points) {
            Color drawn = shot.getPixelReader().getColor(p[0], p[1]);
            int i = (p[1] * width + p[0]) * 3;
            double diff = 255 * Math.max(Math.abs(drawn.getRed() - field[i]), Math.max(Math.abs(drawn.getGreen() - field[i + 1]), Math.abs(drawn.getBlue() - field[i + 2])));
            assertEquals(1.0, drawn.getOpacity(), 1e-9, name + ": no transparent hole at " + Arrays.toString(p));
            total += diff; worst = Math.max(worst, diff); count++;
        }
        double mean = total / count;
        assertTrue(mean <= 2 && worst <= 8, name + ": the drawn background is not the procedural field (mean " + mean + ", worst " + worst + ")");
        // Down a column on the right, both Bézier crests show as lit ridges at their computed rows.
        int x = (int) (width * .82);
        double[] column = new double[height];
        for (int y = 0; y < height; y++) column[y] = ThemeTest.luminance(shot.getPixelReader().getColor(x, y));
        for (WaveRenderer.Surface surface : List.of(WaveRenderer.PRIMARY, WaveRenderer.SECONDARY)) {
            // Proportions of the window height: the lit rim lies within about 1% above to 2.5% below the curve, and
            // the tones it is compared with are 6% away on either side.
            int crest = (int) Math.round(WaveRenderer.crest(surface.crest(), width, height)[x]), reach = Math.max(3, (int) Math.round(height * .012));
            int peak = crest - reach;
            for (int y = crest - reach; y <= Math.min(height - 1, crest + 2 * reach); y++) if (column[y] > column[peak]) peak = y;
            double ridge = (column[peak] - Math.max(column[Math.max(0, peak - 5 * reach)], column[Math.min(height - 1, peak + 5 * reach)])) / column[peak];
            assertTrue(ridge > (ThemeManager.get().theme().dark() ? .15 : .025), name + ": no visible crest near row " + crest + " (" + ridge + ")");
        }
        return new double[] { mean, worst };
    }

    /**
     * A real screen capture of the window's content area, at the display's own pixel density. It is kept only when it
     * shows this window: it must match the scene's own rendering of the same frame (otherwise another window was in
     * front, and the capture is retried and never written).
     */
    void capture(String name) throws Exception {
        for (int attempt = 1; ; attempt++) {
            action(() -> { stage.setAlwaysOnTop(true); stage.toFront(); });
            AppearanceGuiTest.settle();
            WritableImage shot = fx(() -> {
                var scene = stage.getScene();
                var screen = new javafx.scene.robot.Robot().getScreenCapture(null, stage.getX() + scene.getX(), stage.getY() + scene.getY(), scene.getWidth(), scene.getHeight(), false);
                var own = scene.snapshot(null);
                double sx = screen.getWidth() / own.getWidth(), sy = screen.getHeight() / own.getHeight();
                List<Double> differences = new ArrayList<>();
                for (int gx = 0; gx < 24; gx++) for (int gy = 0; gy < 16; gy++) {
                    int x = (int) ((gx + .5) * own.getWidth() / 24), y = (int) ((gy + .5) * own.getHeight() / 16);
                    differences.add(AppearanceGuiTest.levelsApart(own.getPixelReader().getColor(x, y), screen.getPixelReader().getColor((int) (x * sx), (int) (y * sy))));
                }
                Collections.sort(differences);
                // Text edges differ where the screen has more pixels than the scene's 1x rendering (a 150% display),
                // so most points, not all, must agree; another window in front differs almost everywhere.
                boolean same = differences.get(differences.size() / 2) <= 4 && differences.get(differences.size() * 3 / 4) <= 12;
                if (!same) {
                    // Where they differ (rows of the 24 x 16 grid; '#' more than 8 levels apart), without keeping the image.
                    StringBuilder map = new StringBuilder();
                    for (int gy = 0; gy < 16; gy++) {
                        map.append("\n  ");
                        for (int gx = 0; gx < 24; gx++) {
                            int x = (int) ((gx + .5) * own.getWidth() / 24), y = (int) ((gy + .5) * own.getHeight() / 16);
                            map.append(AppearanceGuiTest.levelsApart(own.getPixelReader().getColor(x, y), screen.getPixelReader().getColor((int) (x * sx), (int) (y * sy))) > 8 ? '#' : '.');
                        }
                    }
                    System.out.printf(Locale.ROOT, "capture %s rejected: median %.0f, p75 %.0f; focused=%s windows=%d theme=%s%s%n", name,
                        differences.get(differences.size() / 2), differences.get(differences.size() * 3 / 4), stage.isFocused(), Window.getWindows().size(),
                        ThemeManager.get().theme(), map);
                }
                return same ? screen : null;
            });
            if (shot == null) {
                // A screenshot is evidence, not behaviour (the checks above use the scene's own rendering): when the
                // desktop does not present this window, the capture is reported as skipped instead of saving a wrong image.
                if (attempt >= 6) { System.out.println("capture " + name + " skipped: the window was not presented on screen"); return; }
                Thread.sleep(500); continue;
            }
            var image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            Files.createDirectories(Path.of("target", "backdrop"));
            javax.imageio.ImageIO.write(image, "png", Path.of("target", "backdrop", name + ".png").toFile());
            return;
        }
    }

    @Test void everyThemeInBothModesShowsTheProceduralWavesInTheRealWorkspace() throws Exception {
        try (var profiles = new ProfileManager(env())) {
            open(profiles, 1280, 800);
            var backdrop = backdrop();
            StringBuilder report = new StringBuilder();
            Set<Color> corners = new HashSet<>();
            for (boolean dark : new boolean[] { false, true }) {
                for (Theme.Accent accent : Theme.Accent.values()) {
                    appearance(accent, -1, dark, Theme.DEFAULT_OPACITY); await(backdrop);
                    String name = accent.id + "-" + (dark ? "dark" : "light");
                    double[] match = matchesProceduralField(backdrop, name);
                    report.append(String.format(Locale.ROOT, "%s: drawn vs procedural mean %.2f, worst %.1f levels%n", name, match[0], match[1]));
                    corners.add(fx(() -> { var shot = backdrop.wavesView().snapshot(null, null);
                        return shot.getPixelReader().getColor((int) shot.getWidth() - 40, (int) shot.getHeight() - 40); }));
                    capture("home-" + name);
                }
                for (int hue : new int[] { 280, 40 }) {
                    appearance(null, hue, dark, Theme.DEFAULT_OPACITY); await(backdrop);
                    String name = "custom" + hue + "-" + (dark ? "dark" : "light");
                    double[] match = matchesProceduralField(backdrop, name);
                    report.append(String.format(Locale.ROOT, "%s: drawn vs procedural mean %.2f, worst %.1f levels%n", name, match[0], match[1]));
                    capture("home-" + name);
                }
            }
            assertEquals(10, corners.size(), "every preset relights the background in both modes");
            System.out.print(report);
        }
    }

    @Test void followSystemTransparencyAndALiveHueDragKeepTheBackgroundInStep() throws Exception {
        try (var profiles = new ProfileManager(env())) {
            var workspace = open(profiles, 1280, 800);
            var backdrop = backdrop();
            // Follow System: the operating system switching appearance relights the waves with that mode's palette.
            action(() -> { ThemeManager.get().select(Theme.Accent.TEAL); ThemeManager.get().select(Theme.Mode.SYSTEM); ThemeManager.get().systemReported(false); });
            await(backdrop);
            assertFalse(ThemeManager.get().theme().dark()); matchesProceduralField(backdrop, "system light");
            capture("follow-system-light");
            action(() -> ThemeManager.get().systemReported(true)); await(backdrop);
            assertTrue(ThemeManager.get().theme().dark()); assertEquals(Theme.Mode.SYSTEM, ThemeManager.get().mode());
            matchesProceduralField(backdrop, "system dark");
            capture("follow-system-dark");

            // Transparency changes the panels only: the waves stay the same and show through, frosted, below 100%.
            action(workspace::showApplicationSettings);
            for (boolean dark : new boolean[] { false, true }) {
                int[] before = null;
                for (int opacity : new int[] { 0, 50, 100 }) {
                    appearance(Theme.Accent.BLUE, -1, dark, opacity); await(backdrop);
                    if (opacity < 100) assertTrue(fx(backdrop::frostedRegions) >= 3, "frosted waves behind the panels at " + opacity + "%");
                    else assertEquals(0, (int) fx(backdrop::frostedRegions), "nothing frosted behind opaque panels");
                    var shot = fx(() -> backdrop.wavesView().snapshot(null, null));
                    int[] waves = new int[] { shot.getPixelReader().getArgb(900, 600), shot.getPixelReader().getArgb(300, 200) };
                    if (before != null) assertArrayEquals(before, waves, "Transparency never re-renders or changes the waves");
                    before = waves;
                    capture("transparency-" + (dark ? "dark" : "light") + "-" + opacity);
                }
            }

            // A live drag of the Custom hue slider: the interface stays responsive and the waves end on the final hue.
            appearance(Theme.Accent.BLUE, -1, false, Theme.DEFAULT_OPACITY); await(backdrop);
            action(() -> ((ToggleButton) workspace.lookup("#settings-theme-custom")).fire()); AppearanceGuiTest.settle();
            var slider = fx(() -> (Slider) workspace.lookup("#settings-custom-hue"));
            action(() -> slider.setValueChanging(true));
            long slowest = 0;
            for (int hue = 0; hue <= 350; hue += 10) {
                int value = hue;
                long start = System.nanoTime();
                action(() -> slider.setValue(value)); action(() -> { });   // the change, then the next queued FX work
                slowest = Math.max(slowest, System.nanoTime() - start);
            }
            action(() -> slider.setValueChanging(false));
            await(backdrop);
            assertEquals(350, ThemeManager.get().customHue());
            matchesProceduralField(backdrop, "after hue drag");
            capture("custom-hue-after-drag");
            System.out.printf(Locale.ROOT, "slowest FX round trip during the hue drag: %.1f ms%n", slowest / 1e6);
            assertTrue(slowest < TimeUnit.MILLISECONDS.toNanos(250), "the hue drag kept the interface responsive");
        }
    }

    @Test void resizingAndTheDisplayScaleKeepTheWavesProportionalAndCovering() throws Exception {
        try (var profiles = new ProfileManager(env())) {
            open(profiles, 880, 600);
            var backdrop = backdrop();
            double display = fx(() -> stage.getRenderScaleX());
            StringBuilder report = new StringBuilder(String.format(Locale.ROOT, "display scale %.2f%n", display));
            for (boolean dark : new boolean[] { false, true }) {
                appearance(Theme.Accent.BLUE, -1, dark, Theme.DEFAULT_OPACITY);
                for (double[] size : new double[][] { { 880, 600 }, { 1280, 800 }, { 1600, 1000 }, { 0, 0 } }) {
                    if (size[0] == 0) action(() -> stage.setMaximized(true)); else resize(size[0], size[1]);
                    await(backdrop);
                    double width = fx(() -> stage.getScene().getWidth()), height = fx(() -> stage.getScene().getHeight());
                    String name = (dark ? "dark" : "light") + "-" + (int) width + "x" + (int) height + "-scale" + Math.round(display * 100);
                    // Rendered for the display's pixels: half the physical size, at most 1600 px wide.
                    double scale = Math.min(.5 * Math.max(1, display), 1600 / width);
                    double[] field = fx(backdrop::fieldSize);
                    assertEquals(Math.ceil(width * scale), field[0], 1, name + " field width");
                    assertEquals(Math.ceil(height * scale), field[1], 1, name + " field height");
                    double[] match = matchesProceduralField(backdrop, name);
                    report.append(String.format(Locale.ROOT, "%s: field %.0fx%.0f, drawn vs procedural mean %.2f, worst %.1f levels%n", name, field[0], field[1], match[0], match[1]));
                    capture("size-" + name);
                }
            }
            System.out.print(report);
        }
    }
}
