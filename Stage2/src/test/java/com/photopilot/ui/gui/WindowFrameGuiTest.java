package com.photopilot.ui.gui;

import com.photopilot.i18n.LocalizationService;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical Windows desktop. The workspace chooser's outer corners are the system frame of the decorated
 * main window: each is shown over a magenta backdrop and its top-left corner is captured from the screen. The area the
 * rounding cuts away must match the main window's while restored, and be none while either fills the screen. The
 * captures are kept in target/window-frame.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowFrameGuiTest {
    /** Logical size of each corner capture, and of the corner square in which cut-away pixels are counted. */
    private static final int CAPTURE = 64, CORNER = 16;
    Stage backdrop, main;
    UserSelectionWindow chooser;

    static <T> T fx(Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void action(Runnable action) throws Exception { AppearanceGuiTest.action(action); }
    @BeforeAll static void startFx() { try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException started) { } }
    @AfterEach void closeWindows() throws Exception {
        action(() -> { if (chooser != null) chooser.dispose(); if (main != null) main.close(); if (backdrop != null) backdrop.close(); });
    }

    @Test void chooserCornersAreTheMainWindowFrameRestoredAndMaximized() throws Exception {
        Rectangle2D area = fx(() -> Screen.getPrimary().getVisualBounds());
        action(() -> {
            backdrop = new Stage(StageStyle.UNDECORATED);
            var magenta = new Pane(); magenta.setStyle("-fx-background-color: magenta;");   // inline, so no theme stylesheet repaints it
            backdrop.setScene(new Scene(magenta, area.getWidth(), area.getHeight(), Color.MAGENTA));
            backdrop.setX(area.getMinX()); backdrop.setY(area.getMinY()); backdrop.setAlwaysOnTop(true); backdrop.show();
            main = new Stage();   // decorated, as PhotoPilotGUI's main window
            main.setScene(new Scene(new Pane(), 880, 600, Color.WHITE));
            main.setX(area.getMinX() + 120); main.setY(area.getMinY() + 120); main.setAlwaysOnTop(true); main.show(); main.toFront();
        });
        int mainRestored = cutAway(main, area, "main-restored");
        action(() -> main.setMaximized(true));
        int mainMaximized = cutAway(main, area, "main-maximized");
        action(() -> {
            main.hide();
            chooser = new UserSelectionWindow(LocalizationService.english());
            chooser.setX(area.getMinX() + 120); chooser.setY(area.getMinY() + 120); chooser.setWidth(880); chooser.setHeight(600);
            chooser.setAlwaysOnTop(true); chooser.show(); chooser.toFront();
        });
        int chooserRestored = cutAway(chooser, area, "chooser-restored");
        boolean systemRounds = mainRestored > 0;
        assertNotEquals(WindowFrame.Result.UNAVAILABLE, fx(chooser::corners), "the chooser reaches the system frame on Windows");
        if (systemRounds) assertEquals(WindowFrame.Result.APPLIED, fx(chooser::corners), "Windows rounds the main window, so it rounds the chooser too");
        assertEquals(mainRestored, chooserRestored, Math.max(2, mainRestored / 5),
            "the chooser's corner is the main window's corner (cut-away pixels " + chooserRestored + " vs " + mainRestored + ")");

        action(() -> chooser.toggleMaximized());
        assertEquals(0, mainMaximized, "a maximized main window has square corners");
        assertEquals(0, cutAway(chooser, area, "chooser-maximized"), "a maximized chooser has square corners, as the main window");
        if (systemRounds) assertEquals(WindowFrame.Result.APPLIED, fx(chooser::corners));

        action(() -> chooser.toggleMaximized());
        assertEquals(chooserRestored, cutAway(chooser, area, "chooser-restored-again"), Math.max(2, mainRestored / 5), "restoring rounds the corners again");
    }

    /**
     * Pixels of the backdrop showing inside the window's top-left corner square, at the display's own pixel density,
     * once the window has settled (Windows animates a window as it opens or changes size). The window's edges are found
     * from a row and a column well inside it; backdrop pixels are magenta, darkened at most by the window's shadow.
     */
    int cutAway(Stage window, Rectangle2D area, String name) throws Exception {
        int previous = -1;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            AppearanceGuiTest.settle(); Thread.sleep(100);
            int count = fx(() -> {
                window.toFront();
                double x = Math.max(area.getMinX(), window.getX() - 4), y = Math.max(area.getMinY(), window.getY() - 4);
                WritableImage shot = new javafx.scene.robot.Robot().getScreenCapture(null, x, y, CAPTURE, CAPTURE, false);
                int width = (int) shot.getWidth(), height = (int) shot.getHeight(), corner = (int) Math.round(CORNER * width / (double) CAPTURE);
                var pixels = shot.getPixelReader();
                int left = 0, top = 0;
                while (left < width && backdrop(pixels.getArgb(left, height - 1))) left++;
                while (top < height && backdrop(pixels.getArgb(width - 1, top))) top++;
                assertTrue(left < width - corner && top < height - corner, name + ": the window is not on screen where expected");
                int cut = 0;
                for (int py = top; py < top + corner; py++) for (int px = left; px < left + corner; px++) if (backdrop(pixels.getArgb(px, py))) cut++;
                var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) image.setRGB(px, py, pixels.getArgb(px, py));
                Files.createDirectories(Path.of("target", "window-frame"));
                javax.imageio.ImageIO.write(image, "png", Path.of("target", "window-frame", name + ".png").toFile());
                return cut;
            });
            if (count == previous) return count;
            if (System.nanoTime() > deadline) fail(name + ": the corner did not settle");
            previous = count;
        }
    }

    /** The magenta backdrop, alone or under the window's shadow or the anti-aliased edge's outer half. */
    static boolean backdrop(int argb) {
        int red = argb >> 16 & 0xff, green = argb >> 8 & 0xff, blue = argb & 0xff;
        return red > 2 * green + 40 && blue > 2 * green + 40 && Math.abs(red - blue) < 48;
    }
}
