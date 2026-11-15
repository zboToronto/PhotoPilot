package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm.Tool;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.CalculatorPresentation.DistanceAxis;
import com.photopilot.application.CalculatorPresentation.LengthUnit;
import com.photopilot.application.CalculatorPresentation.SpeedUnit;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.model.CameraSetup;
import com.photopilot.requirement.FakeGemini;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. The calculator workspace through its real controls in a real window: live results from
 * typing and from sliders, independent calculators, the draggable focus subject, inline validation, resizing, themes,
 * languages, and no AI or network request.
 */
class CalculatorGuiTest {
    @TempDir Path directory;
    ExecutorService executor;
    ApplicationBootstrap bootstrap;
    CalculatorPane pane; PhotoPilotWorkspace workspace; Stage stage;

    static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception { return InventoryGuiTest.fx(action); }
    static void run(ThrowingRunnable action) throws Exception { fx(() -> { action.run(); return true; }); }
    interface ThrowingRunnable { void run() throws Exception; }

    void open(Map<String, String> environment, double width, double height) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { }
        executor = Executors.newSingleThreadExecutor();
        bootstrap = new ApplicationBootstrap(environment);
        run(() -> {
            pane = new CalculatorPane(bootstrap.facade(), executor, bootstrap.localization());
            workspace = new PhotoPilotWorkspace(bootstrap.localization()); workspace.connectCalculator(pane);
            stage = new Stage(); var scene = new Scene(workspace, width, height);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); ThemeManager.get().style(scene.getStylesheets());
            stage.setScene(scene); stage.show();
            ((ToggleButton) workspace.lookup("#nav-F08")).fire(); settle();
        });
    }
    void open(double width, double height) throws Exception {
        open(Map.of("PHOTOPILOT_DATA_DIR", directory.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()), width, height);
    }
    @AfterEach void close() throws Exception {
        if (stage != null) run(() -> stage.close());
        if (bootstrap != null) bootstrap.close();
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    // ---- Helpers on real controls (FX thread) ------------------------------------------------------------------------
    /** Lays the page out in its final state: a running rescale of the focus diagram is ended, as if its 0.24 s had passed. */
    void settle() {
        workspace.applyCss(); workspace.layout();
        if (pane.mode() != null && pane.mode().diagram() instanceof FocusDiagram focus && focus.rescaling()) { focus.finishRescale(); workspace.layout(); }
    }
    void select(Tool tool) { ((ToggleButton) pane.lookup("#calc-mode-" + tool.name())).fire(); settle(); }
    TextField input(String key) { return Objects.requireNonNull((TextField) pane.lookup("#calc-" + key), key); }
    void type(String key, String text) { input(key).setText(text); settle(); }
    Slider slider(String key) { return (Slider) pane.lookup("#calc-" + key + "-slider"); }
    String result(String id) { return ((Label) pane.lookup("#calc-result-" + id)).getText(); }
    String detail(String id) { return ((Label) pane.lookup("#calc-detail-" + id)).getText(); }
    String error(String key) { return ((Label) pane.lookup("#calc-" + key + "-error")).getText(); }
    String status() { return pane.mode().statusText(); }
    void click(String id) { ((ButtonBase) pane.lookup("#" + id)).fire(); settle(); }

    static void snapshot(PhotoPilotWorkspace workspace, String name) throws java.io.IOException {
        workspace.applyCss(); workspace.layout();
        write(workspace.snapshot(null, null), name);
    }
    static void write(javafx.scene.image.WritableImage image, String name) throws java.io.IOException {
        var pixels = image.getPixelReader();
        var buffered = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) buffered.setRGB(x, y, pixels.getArgb(x, y));
        javax.imageio.ImageIO.write(buffered, "png", new java.io.File("target/" + name + ".png"));
    }
    /** The calculator page alone at its full height, as a picture for review. */
    void picture(String name) throws java.io.IOException { settle(); write(pane.snapshot(null, null), name); }
    static void noMissing(Node node) {
        if (node instanceof Labeled label && label.getText() != null) assertFalse(label.getText().contains("[!"), label.getText());
        if (node instanceof TextInputControl field && field.getPromptText() != null) assertFalse(field.getPromptText().contains("[!"), field.getPromptText());
        if (node.getAccessibleText() != null) assertFalse(node.getAccessibleText().contains("[!"), node.getAccessibleText());
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(CalculatorGuiTest::noMissing);
    }
    /** A mouse event at a point given in the node's own coordinates. */
    static void mouse(Node node, javafx.event.EventType<MouseEvent> type, double x, double y) {
        var onScene = node.localToScene(x, y);
        boolean down = type != MouseEvent.MOUSE_RELEASED && type != MouseEvent.MOUSE_MOVED;
        Event.fireEvent(node, new MouseEvent(type, onScene.getX(), onScene.getY(), onScene.getX(), onScene.getY(), MouseButton.PRIMARY, 1,
            false, false, false, false, down, false, false, true, false, type == MouseEvent.MOUSE_DRAGGED, null));
    }
    static double number(String text) { return Double.parseDouble(text.replaceAll("[^0-9.eE-]", "")); }
    /** The result tiles of the current page in the order shown, by id (total, near, ...). */
    List<String> tileOrder() { return pane.mode().tilesShown().stream().map(tile -> tile.getId().substring("calc-tile-".length())).toList(); }
    boolean primary(String id) { return pane.lookup("#calc-tile-" + id).getStyleClass().contains("calc-tile-primary"); }
    /** The caption above an input (its name, and why it is set automatically when it is). */
    String caption(String key) { return ((Label) ((Parent) input(key).getParent().getParent()).lookup(".section-title")).getText(); }
    /** The hyperfocal distance in metres for the shared camera, from the application's own calculation (expected value of the lock). */
    double hyperfocal(double focalMm, double fNumber) {
        var input = new com.photopilot.model.CalculationInput(com.photopilot.model.CalculationKind.HYPERFOCAL,
            Map.of("focalLength", focalMm, "aperture", fNumber, "coc", pane.camera().cocMm()), Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm"));
        return bootstrap.facade().calculate(input).value().orElseThrow().values().get(com.photopilot.service.TechnicalCalculator.HYPERFOCAL_DISTANCE);
    }

    // ---- Tests ---------------------------------------------------------------------------------------------------

    @Test void sixCalculationsOnFiveIndependentPagesUpdateLiveFromTypingAndSliders() throws Exception {
        open(1280, 900);
        run(() -> {
            // Six calculations, five pages: depth of field and hyperfocal distance share one.
            assertEquals(6, Tool.values().length);
            assertEquals(List.of(Tool.EXPOSURE, Tool.ND, Tool.DOF, Tool.FOV, Tool.MOTION), CalculatorPane.PAGES);
            assertEquals(5, pane.lookup("#calc-modes").lookupAll(".segment").size());
            for (Tool tool : CalculatorPane.PAGES) assertNotNull(pane.lookup("#calc-mode-" + tool.name()), tool.name());
            assertNull(pane.lookup("#calc-mode-HYPERFOCAL"), "no separate hyperfocal page");
            assertEquals("Depth of field & hyperfocal distance", ((ToggleButton) pane.lookup("#calc-mode-DOF")).getText());
            assertSame(pane.mode(Tool.DOF), pane.mode(Tool.HYPERFOCAL), "the hyperfocal calculation is on the depth of field page");
            assertNull(pane.lookup("#calc-run"), "no Calculate button: results follow the inputs");

            // A. Exposure: settings EV and EV100 are separate quantities.
            assertSame(pane.mode(Tool.EXPOSURE), pane.mode());
            assertEquals("12.97", result("settingsEv")); assertEquals("12.97", result("ev100"));
            type("iso", "200");
            assertEquals("12.97", result("settingsEv"), "ISO is not part of the settings EV"); assertEquals("11.97", result("ev100"));
            // Only what exposure uses is on screen: no sensor, no distance unit, no gear values.
            assertNull(pane.lookup("#calc-camera-format")); assertFalse(pane.lookup("#calc-units").getParent().isVisible()); assertFalse(pane.lookup("#calc-gear-row").isVisible());
            // A slider reports while it is being dragged, not only on release, and keeps the field in step.
            Slider aperture = slider("aperture");
            aperture.setValueChanging(true);
            aperture.setValue(CalculatorPresentation.APERTURES.indexOf(5.6) + 0.3);
            assertTrue(aperture.isValueChanging()); assertEquals("5.6", input("aperture").getText(), "nominal marking while dragging");
            assertEquals("11.94", result("settingsEv")); assertEquals("10.94", result("ev100"));
            aperture.setValue(CalculatorPresentation.APERTURES.indexOf(8.0)); aperture.setValueChanging(false);
            assertEquals("8", input("aperture").getText()); assertEquals("12.97", result("settingsEv"));
            // Typing moves the slider; a value between two markings sits between them.
            type("aperture", "6");
            assertTrue(aperture.getValue() > CalculatorPresentation.APERTURES.indexOf(5.6) && aperture.getValue() < CalculatorPresentation.APERTURES.indexOf(6.3));
            type("aperture", "8"); type("shutter", "0.008");
            assertEquals("12.97", result("settingsEv"), "decimal seconds and the fraction are the same exposure time");
            type("shutter", "1/125");
            picture("f08-exposure");

            // B. ND filter.
            select(Tool.ND);
            assertEquals("8.19 s", result("adjusted")); assertTrue(detail("adjusted").contains("8.192"), detail("adjusted"));
            assertEquals("×1024", result("longer")); assertEquals("1024", input("ndFactor").getText()); assertEquals("3.0103", input("opticalDensity").getText());
            assertTrue(((Label) pane.lookup("#calc-nd-commercial")).getText().contains("ND1000"), "rounded commercial name is explained");
            assertTrue(((Label) pane.lookup("#calc-nd-commercial")).getText().contains("9.97"));
            assertNull(pane.lookup("#calc-camera-format"), "no sensor in the ND calculator");
            picture("f08-nd");
            type("ndStops", "0");
            assertEquals("1/125 s", result("adjusted"), "zero stops keeps the original time"); assertEquals("1", input("ndFactor").getText());
            type("ndFactor", "8");
            assertEquals("3", input("ndStops").getText()); assertEquals("8", input("ndFactor").getText(), "the field being typed in is left alone");
            assertEquals("1/15.6 s", result("adjusted")); assertEquals(3, slider("ndStops").getValue(), 1e-9);
            type("opticalDensity", "3");
            assertEquals("9.96578", input("ndStops").getText()); assertEquals("8 s", result("adjusted"));
            click("calc-nd-pick-6");
            assertEquals("6", input("ndStops").getText()); assertEquals("64", input("ndFactor").getText()); assertEquals("0.512 s", result("adjusted"));

            // C. Depth of field with an explicit circle of confusion of 0.03 mm.
            select(Tool.DOF);
            assertNotNull(pane.lookup("#calc-camera-format")); assertTrue(pane.lookup("#calc-units").getParent().isVisible());
            assertTrue(((Label) pane.lookup("#calc-camera-coc-in-use")).getText().contains("0.0288"), "the default policy is visible");
            @SuppressWarnings("unchecked") var policy = (ComboBox<CameraSetup.CocPolicy>) pane.lookup("#calc-camera-coc-policy");
            policy.setValue(CameraSetup.CocPolicy.CUSTOM); settle();
            type("camera-coc", "0.03");
            assertEquals(0.03, pane.camera().cocMm(), 0.0);
            assertEquals("50", input("focalLength").getText()); assertEquals("8", input("aperture").getText()); assertEquals("3", input("distance").getText());
            assertEquals("2.34 m", result("near")); assertEquals("4.19 m", result("far")); assertEquals("1.85 m", result("total")); assertEquals("10.5 m", result("hyperfocal"));
            assertEquals("0.03", input("camera-coc").getText(), "the circle of confusion in use stays on screen, with the camera");
            assertFalse(pane.lookup("#calc-camera-coc-in-use").isVisible(), "a custom value is not repeated under its own field");
            picture("f08-dof");
            assertNull(pane.lookup("#calc-dof-hyperfocal"), "the one-time button is replaced by the focus mode");

            // D. Hyperfocal distance on the same page: hyperfocal focus locks the focus there, with the same inputs and camera.
            click("calc-focus-HYPERFOCAL");
            assertEquals("10.4667", input("distance").getText());
            assertEquals(CalculatorPresentation.INFINITY, result("far")); assertEquals("Unbounded", detail("far"));
            assertEquals("5.23 m", result("near")); assertEquals("10.5 m", result("hyperfocal"));
            assertEquals(List.of("hyperfocal", "near", "far", "total"), tileOrder());
            type("focalLength", "24");
            assertEquals("2.424", input("distance").getText(), "24 mm f/8 c = 0.03 mm: H = 24² / (8 × 0.03) + 24 mm");
            assertEquals("1.21 m", result("near")); assertEquals(CalculatorPresentation.INFINITY, result("far"));
            type("focalLength", "50");
            assertEquals("10.4667", input("distance").getText());
            picture("f08-dof-hyperfocal");

            // E. Field of view.
            select(Tool.FOV);
            assertNull(pane.lookup("#calc-camera-coc-policy"), "field of view has no circle of confusion");
            assertEquals("39.6°", result("horizontal")); assertEquals("27°", result("vertical")); assertEquals("46.8°", result("diagonal"));
            assertEquals("7.2 m × 4.8 m", result("scene")); assertEquals("50 mm", result("equivalent"));
            picture("f08-fov");
            ((CheckBox) pane.lookup("#calc-fov-coverage")).setSelected(false); settle();
            assertEquals(CalculatorPresentation.NONE, result("scene")); assertEquals("39.6°", result("horizontal")); assertTrue(input("distance").getParent().getParent().isDisabled());
            @SuppressWarnings("unchecked") var format = (ComboBox<String>) pane.lookup("#calc-camera-format");
            format.setValue("apsc"); settle();
            assertEquals("23.5", input("camera-width").getText()); assertEquals("15.6", input("camera-height").getText());
            assertEquals("26.4°", result("horizontal")); assertEquals("76.7 mm", result("equivalent"), "additional information; 50 mm is still what is calculated with");
            assertEquals("50", input("focalLength").getText());
            type("focalLength", "85"); assertTrue(number(result("horizontal")) < 26.4, "a longer lens narrows the view");

            // F. Motion blur: the camera is shared, so the sensor chosen above applies here too.
            select(Tool.MOTION);
            assertEquals("23.5", input("camera-width").getText());
            assertEquals("25 µm", result("sensorBlur")); assertEquals("0.11%", result("share")); assertEquals("1 cm", result("travel"));
            assertEquals(CalculatorPresentation.NONE, result("imageBlur"), "no pixel value without the image width"); assertTrue(detail("imageBlur").contains("image width"));
            click("calc-camera-reset");
            assertEquals(CameraSetup.DEFAULT, pane.camera()); assertEquals("36", input("camera-width").getText()); assertEquals("0.069%", result("share"));
            assertEquals("1/2500 s", result("longest"), "10 um on the sensor");
            type("camera-image-width", "6000"); type("camera-image-height", "4000");
            assertEquals("4.17 px", result("imageBlur")); assertEquals("25 µm", result("sensorBlur"), "resolution does not change the physical blur");
            @SuppressWarnings("unchecked") var limitUnit = (ComboBox<MotionBlurMode.LimitUnit>) pane.lookup("#calc-motion-limit-unit");
            limitUnit.setValue(MotionBlurMode.LimitUnit.PIXELS); type("blurLimit", "2");
            assertEquals("1/2083 s", result("longest"));
            picture("f08-motion");
            @SuppressWarnings("unchecked") var speedUnit = (ComboBox<SpeedUnit>) pane.lookup("#calc-speed-unit");
            speedUnit.setValue(SpeedUnit.KILOMETRES_PER_HOUR); settle();
            assertEquals("36", input("speed").getText()); assertEquals("25 µm", result("sensorBlur"), "a unit change is not a value change");
            type("shutter", "1/500"); assertEquals("50 µm", result("sensorBlur"), "twice the exposure, twice the blur");
            type("speed", "0");
            assertEquals("0 mm", result("sensorBlur")); assertEquals("0 px", result("imageBlur")); assertEquals("No limit from motion", result("longest"));
            assertFalse(result("longest").contains(CalculatorPresentation.INFINITY));

            // Switching calculators keeps each one's inputs; reset is scoped to the calculator it is pressed in.
            select(Tool.EXPOSURE); assertEquals("200", input("iso").getText()); assertEquals("11.97", result("ev100"));
            select(Tool.ND); assertEquals("6", input("ndStops").getText());
            // Still locked, and following the camera: it was reset to the default (circle of confusion 0.0288 mm) meanwhile.
            select(Tool.DOF);
            assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, ((DepthOfFieldMode) pane.mode()).focusMode());
            assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 8)), input("distance").getText()); assertEquals("10.9 m", result("hyperfocal"));
            select(Tool.MOTION); assertEquals("0", input("speed").getText()); assertEquals("6000", input("camera-image-width").getText());
            assertTrue(pane.dirty());
            select(Tool.EXPOSURE); click("calc-reset");
            assertEquals("100", input("iso").getText()); assertEquals("12.97", result("ev100")); assertFalse(pane.mode().changed());
            select(Tool.ND); assertEquals("6", input("ndStops").getText(), "another calculator is not reset");
            select(Tool.MOTION); click("calc-reset");
            assertEquals("10", input("speed").getText()); assertEquals("6000", input("camera-image-width").getText(), "the shared camera has its own reset");
            for (Tool tool : CalculatorPane.PAGES) { select(tool); click("calc-reset"); }
            assertEquals(DepthOfFieldMode.FocusMode.MANUAL, ((DepthOfFieldMode) pane.mode(Tool.DOF)).focusMode(), "reset returns manual focus");
            select(Tool.MOTION); click("calc-camera-reset");
            assertFalse(pane.dirty());
        });
    }

    @Test void exposureHoldSolvesOnlyTheNamedSettingAndReferenceComparesInStops() throws Exception {
        open(1280, 900);
        run(() -> {
            var mode = (ExposureMode) pane.mode();
            assertEquals(CalculatorPresentation.NONE, result("relative")); assertTrue(detail("relative").contains("No reference"));
            click("calc-reference-capture");
            assertEquals("0.0 stops", result("relative")); assertTrue(detail("relative").contains("Same exposure"));
            assertTrue(((Label) pane.lookup("#calc-reference-note")).getText().contains("f/8, 1/125 s, ISO 100"));
            // One stop less light through the aperture: nothing else moves, and the comparison says one stop less.
            type("aperture", "11");
            assertEquals("1/125", input("shutter").getText()); assertEquals("100", input("iso").getText());
            assertEquals("−0.9 stops", result("relative"), "f/8 to f/11 is 0.92 stops as marked"); assertTrue(detail("relative").contains("Less exposure"));
            picture("f08-exposure-reference");
            type("aperture", "8");
            // Hold by solving the exposure time: only the time changes when the aperture does.
            click("calc-hold-SHUTTER");
            assertEquals(ExposureMode.Setting.SHUTTER, mode.held());
            assertEquals("Holding EV100 12.97", ((Label) pane.lookup("#calc-hold-note")).getText()); assertEquals("Exposure time (solved)", caption("shutter"));
            assertFalse(input("shutter").isEditable()); assertTrue(slider("shutter").isDisabled()); assertTrue(input("aperture").isEditable());
            String ev100 = result("ev100");
            type("aperture", "4");
            assertEquals(ev100, result("ev100"), "the exposure is held"); assertEquals("100", input("iso").getText());
            assertEquals("1/500", input("shutter").getText(), "two stops wider needs a quarter of the time");
            assertEquals("0.0 stops", result("relative"), "still the reference exposure");
            picture("f08-exposure-hold");
            // Solve ISO instead: the time is released and stays where it is.
            click("calc-hold-ISO");
            assertTrue(input("shutter").isEditable()); assertFalse(input("iso").isEditable());
            type("aperture", "8");
            assertEquals("1/500", input("shutter").getText()); assertEquals("400", input("iso").getText()); assertEquals(ev100, result("ev100"));
            // A value that cannot hold the exposure is reported, not silently clamped: an hour would need about f/10000.
            click("calc-hold-APERTURE");
            type("shutter", "3600");
            assertTrue(status().contains("outside the supported range"), status()); assertEquals(CalculatorPresentation.NONE, result("ev100"));
            assertEquals("8", input("aperture").getText(), "the solved setting keeps its last valid value"); assertEquals("400", input("iso").getText());
            type("shutter", "1/500"); assertEquals("", status()); assertEquals(ev100, result("ev100"));
            click("calc-hold-OFF");
            assertNull(mode.held()); assertTrue(input("aperture").isEditable()); assertTrue(input("iso").isEditable());
            // Equivalent exposures: seven pairs at the same settings EV; applying one changes aperture and time together.
            type("iso", "100"); type("shutter", "1/125");
            String settingsEv = result("settingsEv");
            Button current = null;
            for (int i = 0; i < 7; i++) { var chip = (Button) pane.lookup("#calc-equivalent-" + i); assertTrue(chip.isVisible()); if (chip.getText().startsWith("Current")) current = chip; }
            assertNotNull(current); assertTrue(current.getText().contains("f/8") && current.getText().contains("1/125 s"), current.getText());
            click("calc-equivalent-1");
            assertEquals("4", input("aperture").getText()); assertEquals("1/500", input("shutter").getText()); assertEquals(settingsEv, result("settingsEv"));
            click("calc-reference-clear"); assertEquals(CalculatorPresentation.NONE, result("relative"));
            // Dragging a scale of the slide rule changes that setting only, in third-stop markings.
            var diagram = (ExposureDiagram) mode.diagram();
            double y = 24 + 2 * 50 + 26, x = diagram.getWidth() * 0.7;
            mouse(diagram, MouseEvent.MOUSE_PRESSED, x, y); mouse(diagram, MouseEvent.MOUSE_DRAGGED, x - 120, y); mouse(diagram, MouseEvent.MOUSE_RELEASED, x - 120, y); settle();
            assertTrue(number(input("iso").getText()) > 100, "pulling the ISO scale left brings higher ISO to the centre");
            assertTrue(CalculatorPresentation.ISOS.contains(number(input("iso").getText()))); assertEquals("4", input("aperture").getText());
        });
    }

    @Test void focusSubjectCanBeDraggedAndClippedLimitsAreNeverShownAsInfinity() throws Exception {
        open(1280, 900);
        run(() -> {
            select(Tool.DOF);
            var mode = (DepthOfFieldMode) pane.mode(); var diagram = mode.focusDiagram();
            assertEquals(6, diagram.axis().max(), 0.0, "3 m focus on a 0 to 6 m axis");
            assertEquals(DistanceAxis.Extent.WITHIN, diagram.farExtent(), "far limit 4.19 m drawn inside the axis");
            double y = diagram.axisY() - 30, start = diagram.subjectX();
            // Taking hold of the subject a little off its centre does not move it: only dragging does.
            mouse(diagram, MouseEvent.MOUSE_PRESSED, start + 8, y); mouse(diagram, MouseEvent.MOUSE_RELEASED, start + 8, y); settle();
            assertEquals("3", input("distance").getText(), "a press alone changes nothing");
            mouse(diagram, MouseEvent.MOUSE_MOVED, start, y);
            assertEquals(javafx.scene.Cursor.H_RESIZE, diagram.getCursor(), "the subject shows it can be moved");
            mouse(diagram, MouseEvent.MOUSE_PRESSED, start, y);
            mouse(diagram, MouseEvent.MOUSE_DRAGGED, start + 90, y); settle();
            double dragged = number(input("distance").getText());
            assertTrue(dragged > 3.2, "the focus distance follows the subject: " + dragged);
            assertEquals(6, diagram.axis().max(), 0.0, "the axis holds still during the gesture");
            assertTrue(number(result("near")) > 2.4, "results follow: " + result("near"));
            assertEquals(CalculatorPresentation.Scale.logarithmic(0.1, 1000).position(dragged), slider("distance").getValue(), 1e-6, "the slider follows too");
            assertEquals(start + 90, diagram.subjectX(), 6, "the subject is under the pointer");
            // Dragging to the camera stops at the nearest distance the model allows, never at or behind the lens.
            mouse(diagram, MouseEvent.MOUSE_DRAGGED, 2, y); settle();
            assertEquals(0.1, number(input("distance").getText()), 1e-9); assertTrue(status().isEmpty(), status());
            mouse(diagram, MouseEvent.MOUSE_DRAGGED, start + 40, y); mouse(diagram, MouseEvent.MOUSE_RELEASED, start + 40, y); settle();
            double released = number(input("distance").getText());
            assertTrue(diagram.axis().fraction(released) <= 0.56, "the axis is refitted after the gesture");
            // Keyboard: the same handle, moved with the arrow keys.
            Event.fireEvent(diagram, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, false)); settle();
            assertTrue(number(input("distance").getText()) > released);
            // A press away from the subject does not move it.
            double before = number(input("distance").getText());
            mouse(diagram, MouseEvent.MOUSE_PRESSED, diagram.subjectX() + 120, y); mouse(diagram, MouseEvent.MOUSE_DRAGGED, diagram.subjectX() + 160, y); mouse(diagram, MouseEvent.MOUSE_RELEASED, diagram.subjectX() + 160, y);
            assertEquals(before, number(input("distance").getText()), 0.0);
            // A drag released outside the diagram leaves no hover behind.
            double held = diagram.subjectX();
            mouse(diagram, MouseEvent.MOUSE_PRESSED, held, y); mouse(diagram, MouseEvent.MOUSE_DRAGGED, held + 30, y); mouse(diagram, MouseEvent.MOUSE_RELEASED, held + 30, -40); settle();
            assertNotEquals(javafx.scene.Cursor.H_RESIZE, diagram.getCursor());

            // Finite but outside the view: 50 mm f/8 focused at 10 m with the default circle of confusion.
            type("distance", "10");
            assertEquals("123 m", result("far"), "a finite far limit keeps its value");
            assertEquals(DistanceAxis.Extent.BEYOND_VIEW, diagram.farExtent(), "drawn broken off at the edge, with its value"); assertTrue(diagram.axis().max() < 122);
            assertTrue(diagram.getAccessibleText().contains("123 m")); assertFalse(diagram.getAccessibleText().contains("infinity"));
            picture("f08-dof-beyond-view");
            // Unbounded: at and beyond the hyperfocal distance (10.9 m).
            type("distance", "11");
            assertEquals(CalculatorPresentation.INFINITY, result("far")); assertEquals(CalculatorPresentation.INFINITY, result("total"));
            assertEquals(DistanceAxis.Extent.UNBOUNDED, diagram.farExtent(), "drawn open-ended with the infinity sign"); assertTrue(diagram.getAccessibleText().contains("infinity"));
            picture("f08-dof-unbounded");
            // Feet: the same distances in another unit, converted where they are shown and typed.
            click("calc-unit-FEET");
            assertEquals(LengthUnit.FEET, pane.lengthUnit()); assertEquals("36.0892", input("distance").getText()); assertEquals(CalculatorPresentation.INFINITY, result("far"));
            assertTrue(result("near").endsWith(" ft"), result("near")); assertTrue(diagram.axis().max() > 36);
            type("distance", "10");
            assertEquals("7.83 ft", result("near"), "10 ft is 3.048 m; near limit 2.387 m");
            assertEquals("13.8 ft", result("far"));
            // Dragged all the way to the camera in feet: rounding to what is shown never goes below the 0.1 m minimum.
            double feetY = diagram.axisY() - 30, subject = diagram.subjectX();
            mouse(diagram, MouseEvent.MOUSE_PRESSED, subject, feetY); mouse(diagram, MouseEvent.MOUSE_DRAGGED, 2, feetY); mouse(diagram, MouseEvent.MOUSE_RELEASED, 2, feetY); settle();
            double nearest = mode.manualDistance().orElseThrow();
            assertTrue(nearest >= 0.1 && nearest < 0.102, "nearest focus in feet: " + nearest + " m"); assertEquals("", error("distance")); assertEquals("", status());
            type("distance", "10");
            click("calc-unit-METRES");
            assertEquals("3.048", input("distance").getText());
        });
    }

    @Test void hyperfocalFocusIsAPersistentLockAndManualFocusComesBackUnchanged() throws Exception {
        open(1280, 900);
        run(() -> {
            select(Tool.DOF);
            var mode = (DepthOfFieldMode) pane.mode(); var diagram = mode.focusDiagram();
            var manual = (ToggleButton) pane.lookup("#calc-focus-MANUAL"); var locked = (ToggleButton) pane.lookup("#calc-focus-HYPERFOCAL");
            var automatic = javafx.css.PseudoClass.getPseudoClass("automatic");
            Label note = (Label) pane.lookup("#calc-focus-note");
            assertNull(pane.lookup("#calc-focus-hint"), "how to operate the diagram is not written on the page");

            // 1. Manual focus is the default and the focus distance is the user's: by number, by slider and by dragging.
            assertEquals(DepthOfFieldMode.FocusMode.MANUAL, mode.focusMode()); assertTrue(manual.isSelected()); assertFalse(locked.isSelected());
            assertEquals(List.of("total", "near", "far", "hyperfocal"), tileOrder()); assertTrue(primary("total")); assertFalse(primary("hyperfocal"));
            assertTrue(input("distance").isEditable()); assertFalse(slider("distance").isDisabled()); assertFalse(diagram.locked());
            assertEquals("Focus distance", caption("distance")); assertTrue(diagram.getAccessibleHelp().startsWith("Drag the subject"), "kept for assistive technology");
            for (Node node : mode.lookupAll(".label")) if (node instanceof Label label && visible(label)) assertNotEquals(diagram.getAccessibleHelp(), label.getText(), "but not written on the page");
            assertFalse(note.isVisible(), "nothing to say about manual focus");
            type("distance", "4");
            assertEquals(CalculatorPresentation.Scale.logarithmic(0.1, 1000).position(4), slider("distance").getValue(), 1e-9, "the number moves the slider");
            assertTrue(diagram.getAccessibleText().contains("focus at 4 m"), diagram.getAccessibleText());
            String nearAt4 = result("near");
            slider("distance").setValueChanging(true); slider("distance").setValue(CalculatorPresentation.Scale.logarithmic(0.1, 1000).position(5)); settle();
            assertEquals("5", input("distance").getText(), "the slider moves the number"); assertNotEquals(nearAt4, result("near"));
            assertTrue(diagram.getAccessibleText().contains("focus at 5 m"), "and the diagram, while the slider is dragged");
            slider("distance").setValueChanging(false);
            assertNotEquals(CalculatorPresentation.INFINITY, result("far"), "5 m is short of the hyperfocal distance: a finite far limit");
            assertFalse(diagram.getAccessibleText().contains("infinity"));
            String nearAt5 = result("near"), farAt5 = result("far");

            // 2. Hyperfocal focus: the focus distance becomes the hyperfocal distance of the current lens and camera.
            click("calc-focus-HYPERFOCAL");
            double h = hyperfocal(50, 8);
            assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, mode.focusMode()); assertTrue(locked.isSelected()); assertFalse(manual.isSelected());
            assertEquals(CalculatorPresentation.fieldNumber(h), input("distance").getText(), "the effective focus distance stays visible");
            assertEquals("Focus distance (locked)", caption("distance"));
            assertFalse(input("distance").isEditable()); assertTrue(slider("distance").isDisabled(), "no second source of the focus distance while locked");
            assertTrue(input("distance").getParent().getParent().getPseudoClassStates().contains(automatic));
            assertEquals(CalculatorPresentation.distance(h, LengthUnit.METRES), result("hyperfocal"));
            assertEquals(CalculatorPresentation.distance(h / 2, LengthUnit.METRES), result("near"), "near limit H/2"); assertTrue(detail("near").startsWith("Half the hyperfocal"));
            assertEquals(CalculatorPresentation.INFINITY, result("far")); assertEquals(CalculatorPresentation.INFINITY, result("total"));
            assertEquals(List.of("hyperfocal", "near", "far", "total"), tileOrder()); assertTrue(primary("hyperfocal")); assertFalse(primary("total"));
            assertEquals("Locked to hyperfocal. Manual distance kept: 5 m.", note.getText()); assertTrue(note.isVisible());
            assertTrue(note.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("locked")));
            assertTrue(diagram.getAccessibleHelp().contains("locked"), diagram.getAccessibleHelp());
            assertTrue(diagram.locked()); assertTrue(diagram.getAccessibleText().contains("hyperfocal focus") && diagram.getAccessibleText().contains("infinity"), diagram.getAccessibleText());
            assertEquals(DistanceAxis.Extent.UNBOUNDED, diagram.farExtent()); assertTrue(diagram.axis().max() >= h);
            assertEquals("", status());
            picture("f08-dof-locked");

            // 3. Locked means locked: the subject cannot be dragged or moved by keyboard.
            double y = diagram.axisY() - 30, x = diagram.subjectX();
            mouse(diagram, MouseEvent.MOUSE_MOVED, x, y); assertNotEquals(javafx.scene.Cursor.H_RESIZE, diagram.getCursor());
            mouse(diagram, MouseEvent.MOUSE_PRESSED, x, y); mouse(diagram, MouseEvent.MOUSE_DRAGGED, x - 150, y); mouse(diagram, MouseEvent.MOUSE_RELEASED, x - 150, y); settle();
            Event.fireEvent(diagram, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT, false, false, false, false)); settle();
            assertEquals(CalculatorPresentation.fieldNumber(h), input("distance").getText());
            assertEquals(5.0, mode.manualDistance().orElseThrow(), 0.0, "the manual distance is set aside, untouched");

            // 4. The lock follows every input it depends on: focal length, aperture, circle of confusion and sensor.
            type("focalLength", "35"); assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(35, 8)), input("distance").getText());
            type("focalLength", "50"); assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 8)), input("distance").getText(), "35 mm to 50 mm moves the focus with it");
            type("aperture", "11"); assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 11)), input("distance").getText());
            assertTrue(hyperfocal(50, 11) < h, "stopping down brings H closer");
            @SuppressWarnings("unchecked") var policy = (ComboBox<CameraSetup.CocPolicy>) pane.lookup("#calc-camera-coc-policy");
            policy.setValue(CameraSetup.CocPolicy.DIAGONAL_1730); settle();
            assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 11)), input("distance").getText()); assertEquals(43.2666 / 1730, pane.camera().cocMm(), 1e-6);
            policy.setValue(CameraSetup.CocPolicy.CUSTOM); settle(); type("camera-coc", "0.02");
            assertEquals(CalculatorPresentation.fieldNumber(50.0 * 50 / (11 * 0.02) / 1000 + 0.05), input("distance").getText(), "50² / (11 × 0.02) + 50 mm");
            policy.setValue(CameraSetup.CocPolicy.DIAGONAL_1500); settle();
            @SuppressWarnings("unchecked") var format = (ComboBox<String>) pane.lookup("#calc-camera-format");
            format.setValue("apsc"); settle();
            assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 11)), input("distance").getText()); assertEquals(23.5, pane.camera().sensorWidthMm(), 0.0);
            type("camera-width", "22"); assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 11)), input("distance").getText(), "custom sensor dimensions too");
            // Camera reset: the camera's defaults, the lock stays.
            click("calc-camera-reset");
            assertEquals(CameraSetup.DEFAULT, pane.camera()); assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, mode.focusMode());
            assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 11)), input("distance").getText());
            assertEquals(CalculatorPresentation.INFINITY, result("far"));

            // 5. Beyond the slider and the diagram's usual range: used as calculated, never limited. 800 mm f/1.4: H ≈ 15.8 km.
            type("focalLength", "800"); type("aperture", "1.4");
            double far = hyperfocal(800, 1.4);
            assertTrue(far > 1000, "beyond the slider's 1000 m");
            assertEquals(CalculatorPresentation.fieldNumber(far), input("distance").getText()); assertEquals("", status()); assertEquals(1000, slider("distance").getValue(), 1e-9, "the slider at its end, the value not clamped");
            assertEquals(CalculatorPresentation.distance(far, LengthUnit.METRES), result("hyperfocal")); assertEquals(CalculatorPresentation.distance(far / 2, LengthUnit.METRES), result("near"));
            assertTrue(diagram.axis().max() >= far, "the axis widens to show it"); assertEquals(CalculatorPresentation.INFINITY, result("far"));
            picture("f08-dof-locked-far");
            type("focalLength", "50"); type("aperture", "8");

            // 6. Units: the locked distance and the manual one keep their physical values; only the text changes.
            click("calc-unit-FEET");
            assertEquals(CalculatorPresentation.fieldNumber(h / 0.3048), input("distance").getText()); assertTrue(result("near").endsWith(" ft"));
            assertTrue(note.getText().contains("16.4 ft"), "the manual distance shown in feet: " + note.getText());
            click("calc-unit-METRES"); click("calc-unit-FEET"); click("calc-unit-METRES");
            assertEquals(CalculatorPresentation.fieldNumber(h), input("distance").getText()); assertEquals(5.0, mode.manualDistance().orElseThrow(), 0.0);

            // 7. Another page and back: the mode, the locked value and the manual distance are as they were.
            select(Tool.FOV); select(Tool.EXPOSURE); select(Tool.DOF);
            assertSame(mode, pane.mode()); assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, mode.focusMode()); assertTrue(locked.isSelected());
            assertEquals(CalculatorPresentation.fieldNumber(h), input("distance").getText());

            // 8. Manual focus again: the manual distance comes back exactly, with everything that depends on it.
            click("calc-focus-MANUAL");
            assertEquals("5", input("distance").getText()); assertEquals(nearAt5, result("near")); assertEquals(farAt5, result("far"));
            assertTrue(input("distance").isEditable()); assertFalse(slider("distance").isDisabled()); assertFalse(diagram.locked());
            assertEquals("Focus distance", caption("distance")); assertEquals(List.of("total", "near", "far", "hyperfocal"), tileOrder());
            assertFalse(input("distance").getParent().getParent().getPseudoClassStates().contains(automatic));
            // Choosing the mode already in use changes nothing; locking and unlocking again still keeps the manual distance.
            click("calc-focus-MANUAL"); assertEquals("5", input("distance").getText()); assertTrue(manual.isSelected());
            click("calc-focus-HYPERFOCAL"); click("calc-focus-HYPERFOCAL"); assertTrue(locked.isSelected()); type("focalLength", "24"); type("focalLength", "50");
            click("calc-focus-MANUAL"); assertEquals("5", input("distance").getText());
            // A manual distance kept through a unit change comes back in the new unit, as the same distance.
            type("distance", "4"); click("calc-focus-HYPERFOCAL"); click("calc-unit-FEET"); click("calc-focus-MANUAL");
            assertEquals("13.1234", input("distance").getText()); assertEquals(4.0, mode.manualDistance().orElseThrow(), 0.0, "4 m, not a rounded 13.1234 ft");
            click("calc-unit-METRES"); assertEquals("4", input("distance").getText());

            // 9. Unusable manual text is kept as typed, not replaced by a calculated distance.
            type("distance", "abc"); assertFalse(error("distance").isEmpty()); assertTrue(status().contains("Focus distance"), status());
            click("calc-focus-HYPERFOCAL");
            assertEquals("", status(), "the manual distance is not needed while locked"); assertEquals("", error("distance"));
            assertEquals("Locked to hyperfocal. Manual entry kept.", note.getText()); assertTrue(mode.manualDistance().isEmpty());
            click("calc-focus-MANUAL");
            assertEquals("abc", input("distance").getText()); assertFalse(error("distance").isEmpty()); assertEquals(CalculatorPresentation.NONE, result("near"));
            // Underneath the unusable text is the user's last distance (4 m), not the locked one: the slider sits there and a
            // unit change falls back to it.
            assertEquals(CalculatorPresentation.Scale.logarithmic(0.1, 1000).position(4), slider("distance").getValue(), 1e-9, "the slider is not left at H");
            click("calc-unit-FEET"); assertEquals("13.1234", input("distance").getText()); assertEquals(4.0, mode.manualDistance().orElseThrow(), 0.0);
            click("calc-unit-METRES"); assertEquals("4", input("distance").getText());
            // Text is read again only in the unit it was typed in: 2000 m was refused, so it must not become 2000 ft.
            type("distance", "2000"); assertFalse(error("distance").isEmpty());
            click("calc-focus-HYPERFOCAL"); click("calc-unit-FEET"); click("calc-focus-MANUAL");
            assertEquals("13.1234", input("distance").getText(), "dropped like any unreadable text on a unit change"); assertEquals(4.0, mode.manualDistance().orElseThrow(), 0.0);
            assertEquals("", error("distance")); assertEquals("", status());
            click("calc-unit-METRES");
            // Locked without a current hyperfocal distance (focal length missing): no focus distance is shown, and the empty
            // manual entry still comes back as an empty, explained field.
            type("distance", ""); type("focalLength", "");
            click("calc-focus-HYPERFOCAL");
            assertEquals("", input("distance").getText()); assertTrue(status().contains("Focal length"), status()); assertTrue(diagram.stale());
            click("calc-focus-MANUAL");
            assertEquals("", input("distance").getText()); assertEquals("Enter a value.", error("distance"));
            type("focalLength", "50"); assertTrue(status().contains("Focus distance"), status());
            // A unit change now falls back to the last usable distance; nothing is left without a value.
            click("calc-unit-FEET"); assertEquals("13.1234", input("distance").getText()); assertEquals("", status()); assertNotEquals(CalculatorPresentation.NONE, result("near"));
            click("calc-unit-METRES");
            type("distance", "6"); assertEquals("", status());

            // 10. Reset: this calculator's inputs and manual focus, whatever mode it was in. The camera has its own reset.
            click("calc-focus-HYPERFOCAL"); format.setValue("apsc"); settle();
            click("calc-reset");
            assertEquals(DepthOfFieldMode.FocusMode.MANUAL, mode.focusMode()); assertTrue(manual.isSelected());
            assertEquals("3", input("distance").getText()); assertEquals("50", input("focalLength").getText()); assertEquals("8", input("aperture").getText());
            assertTrue(input("distance").isEditable()); assertFalse(mode.changed()); assertEquals("apsc", pane.camera().formatId(), "the camera is not reset with the calculator");
            click("calc-camera-reset"); assertEquals(DepthOfFieldMode.FocusMode.MANUAL, mode.focusMode()); assertEquals("3", input("distance").getText());
            assertFalse(pane.dirty());

            // 11. Opening the hyperfocal calculation by name opens this page with the focus locked.
            select(Tool.EXPOSURE); pane.select(Tool.HYPERFOCAL); settle();
            assertSame(mode, pane.mode()); assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, mode.focusMode()); assertTrue(((ToggleButton) pane.lookup("#calc-mode-DOF")).isSelected());
            assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 8)), input("distance").getText());
            click("calc-focus-MANUAL"); assertEquals("3", input("distance").getText());
        });
        // The scale of the diagram eases between ranges instead of jumping, and the transition ends by itself.
        run(() -> {
            var diagram = ((DepthOfFieldMode) pane.mode()).focusDiagram();
            double before = diagram.axis().max();
            ((ButtonBase) pane.lookup("#calc-focus-HYPERFOCAL")).fire();
            assertTrue(diagram.axis().max() > before); assertTrue(diagram.rescaling(), "from 6 m to the hyperfocal range");
        });
        Thread.sleep(700);
        run(() -> {
            var diagram = ((DepthOfFieldMode) pane.mode()).focusDiagram();
            assertFalse(diagram.rescaling(), "nothing runs once the scale has arrived");
            click("calc-focus-MANUAL"); assertEquals(6, diagram.axis().max(), 0.0);
        });
    }

    String tileCaption(String id) { return ((Label) pane.lookup("#calc-tile-" + id).lookup(".calc-tile-caption")).getText(); }
    static boolean inside(Node node, Node ancestor) { for (Node n = node; n != null; n = n.getParent()) if (n == ancestor) return true; return false; }

    @Test void workingViewShowsLabelsAndValuesWhileFormulasWaitInCollapsedDetails() throws Exception {
        open(1280, 900);
        run(() -> {
            for (Tool tool : CalculatorPane.PAGES) {
                select(tool);
                var mode = pane.mode(); var details = mode.detailsSection();
                // No description paragraph and no permanent assumptions block: formula and assumptions are a few short
                // lines in a section that is closed by default and reachable by keyboard.
                assertTrue(mode.lookupAll(".description").isEmpty(), tool + " has no description line");
                assertSame(details, pane.lookup("#calc-details")); assertEquals("Calculation details", details.getText());
                assertFalse(details.isExpanded(), tool + ": details are closed by default"); assertTrue(details.isFocusTraversable());
                assertEquals(javafx.scene.AccessibleRole.TITLED_PANE, details.getAccessibleRole());
                var lines = mode.detailTexts();
                assertTrue(lines.size() >= 2 && lines.size() <= 4, tool + " details: " + lines);
                for (String line : lines) assertTrue(line.length() <= 140, tool + ": a detail line is short: " + line);
                assertTrue(mode.cautionTexts().isEmpty(), tool + " has no caution at its starting values"); assertFalse(pane.lookup("#calc-cautions").isVisible());
                // Nothing in the working view is a paragraph: every visible text outside the details is a short label.
                for (Node node : mode.lookupAll(".label")) {
                    if (!(node instanceof Label label) || !visible(label) || inside(label, details) || label.getText() == null) continue;
                    assertTrue(label.getText().length() <= 80, tool + " shows a long text: " + label.getText());
                }
            }

            // Exposure: the settings EV and EV100 stay two separately named results.
            select(Tool.EXPOSURE);
            assertEquals("Settings EV (aperture and time)", tileCaption("settingsEv")); assertEquals("EV100 (ISO-normalized)", tileCaption("ev100"));
            assertEquals("f/8 at 1/125 s", detail("settingsEv")); assertEquals("At ISO 100", detail("ev100")); assertEquals("No reference set", detail("relative"));
            assertFalse(pane.lookup("#calc-hold-note").isVisible(), "no note while nothing is held"); assertFalse(pane.lookup("#calc-reference-note").isVisible());
            assertEquals("EV = log2(N²/t) for f-number N and time t in seconds. EV100 = EV − log2(ISO/100).", pane.mode().detailTexts().getFirst());
            assertNotNull(((Button) pane.lookup("#calc-equivalent-0")).getTooltip(), "what a pair does is a tooltip");

            // ND: stops, filter factor and optical density keep their names; a filter name only when there is one.
            select(Tool.ND);
            assertEquals("ND-adjusted exposure time", tileCaption("adjusted")); assertEquals("from 1/125 s", detail("longer"));
            assertEquals(List.of("adjusted", "longer"), tileOrder(), "the strength is the three inputs, not a result tile that repeats them");
            assertEquals("Filter strength", caption("ndStops")); assertEquals("Filter factor", caption("ndFactor")); assertEquals("Optical density", caption("opticalDensity"));
            assertEquals("10", input("ndStops").getText()); assertEquals("1024", input("ndFactor").getText()); assertEquals("3.0103", input("opticalDensity").getText());
            assertEquals("ND1000 is nominal: factor 1000 ≈ 9.97 stops.", ((Label) pane.lookup("#calc-nd-commercial")).getText());
            // A rounded name never reads as an equality with the advertised whole number: ND32000 is 14.97 stops, not 15.
            click("calc-nd-pick-15"); assertEquals("ND32000 is nominal: factor 32000 ≈ 14.97 stops.", ((Label) pane.lookup("#calc-nd-commercial")).getText());
            assertEquals("32768", input("ndFactor").getText(), "15 stops itself is a factor of 32768");
            type("ndStops", "7.5"); assertFalse(pane.lookup("#calc-nd-commercial").isVisible(), "no line when no filter name applies");
            click("calc-nd-pick-3"); assertEquals("Sold as ND8.", ((Label) pane.lookup("#calc-nd-commercial")).getText());
            assertEquals("1/15.6 s", result("adjusted")); assertEquals("0.064 s", detail("adjusted"), "the unrounded time");
            type("baseShutter", "1"); assertEquals("8 s", result("adjusted")); assertEquals("", detail("adjusted"), "not repeated when it would say the same");

            // Depth of field: the circle of confusion in use, and finite against unbounded.
            select(Tool.DOF);
            assertEquals("CoC in use: 0.0288 mm", ((Label) pane.lookup("#calc-camera-coc-in-use")).getText());
            assertTrue(detail("near").endsWith(" in front") && detail("far").endsWith(" behind"), detail("near") + " / " + detail("far"));
            assertEquals("Focus here or beyond: far limit ∞", detail("hyperfocal"));
            type("distance", "20");
            assertEquals(CalculatorPresentation.INFINITY, result("far")); assertEquals("Unbounded", detail("far")); assertTrue(detail("total").endsWith(" to infinity"), detail("total"));
            assertTrue(pane.mode().detailTexts().contains("Sharpness depends on the selected CoC."));
            assertTrue(pane.mode().detailTexts().contains("f = focal length, N = f-number, c = CoC, s = focus distance, H = hyperfocal distance."), "the formula's letters are named");
            assertNotNull(((ComboBox<?>) pane.lookup("#calc-camera-format")).getTooltip(), "the note on presets is a tooltip");

            // Field of view: the actual focal length is the input; the 35 mm equivalent is labelled as a comparison.
            select(Tool.FOV);
            assertEquals("Focal length (actual)", caption("focalLength")); assertEquals("35 mm equivalent focal length", tileCaption("equivalent"));
            assertEquals("For comparison only; crop factor 1", detail("equivalent")); assertEquals("36 mm sensor width", detail("horizontal"));
            assertEquals(4, pane.mode().detailTexts().size());
            ((CheckBox) pane.lookup("#calc-fov-coverage")).setSelected(false); settle();
            assertEquals("Coverage off", detail("scene")); assertEquals(3, pane.mode().detailTexts().size(), "the line about the figure goes with the figure");

            // Motion blur: blur on the sensor and blur in image pixels are different results, and the one warning that
            // changes how to read them stays with the results.
            select(Tool.MOTION);
            assertEquals("Blur on the sensor", tileCaption("sensorBlur")); assertEquals("Blur in the image", tileCaption("imageBlur"));
            assertEquals("", detail("sensorBlur")); assertEquals("Needs image width (Camera)", detail("imageBlur")); assertEquals("", detail("share")); assertEquals("", detail("travel"));
            assertEquals("Blur ≤ 10 µm", detail("longest"));
            type("camera-image-width", "6000"); assertEquals("At 6000 px image width", detail("imageBlur"));
            type("distance", "0.3");
            assertEquals(List.of("Subject closer than 10 focal lengths: blur is underestimated."), pane.mode().cautionTexts()); assertTrue(pane.lookup("#calc-cautions").isVisible());
            picture("f08-motion-caution");
            type("speed", ""); assertTrue(pane.mode().cautionTexts().isEmpty(), "no caution without a result"); assertFalse(status().isEmpty(), "errors are still reported");
            type("speed", "10"); assertEquals(1, pane.mode().cautionTexts().size());
            type("distance", "20"); assertTrue(pane.mode().cautionTexts().isEmpty()); assertFalse(pane.lookup("#calc-cautions").isVisible());

            // The details open on request (keyboard: Space or Enter), and opening them changes no result.
            var details = pane.mode().detailsSection(); String blur = result("sensorBlur"); double closed = details.getHeight();
            details.requestFocus();
            // The standard control toggles on Space only while it has the keyboard focus, which needs the test window to be
            // the active one on this desktop; otherwise the section is opened directly and the keyboard path is not claimed.
            keyboardToggled = details.isFocused();
            if (keyboardToggled) Event.fireEvent(details, new KeyEvent(KeyEvent.KEY_PRESSED, " ", " ", KeyCode.SPACE, false, false, false, false));
            else details.setExpanded(true);
            settle();
            assertTrue(details.isExpanded(), "the section opens"); assertTrue(details.getHeight() > closed + 20, "and it takes room only now");
            assertEquals(blur, result("sensorBlur")); assertEquals(3, pane.mode().detailTexts().size());
            Bounds shown = details.localToScene(details.getBoundsInLocal()); assertTrue(shown.getMaxX() <= workspace.getScene().getWidth() + 0.5, "inside the window");
            for (Node line : ((Parent) pane.lookup("#calc-notes")).getChildrenUnmodifiable()) assertTrue(((Label) line).getHeight() > 0 && visible(line));
            picture("f08-motion-details");
            if (keyboardToggled) {
                // Enter does what Space does.
                Event.fireEvent(details, new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "\r", KeyCode.ENTER, false, false, false, false)); settle();
                assertFalse(details.isExpanded(), "Enter closes the section");
                Event.fireEvent(details, new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "\r", KeyCode.ENTER, false, false, false, false)); settle();
                assertTrue(details.isExpanded(), "and opens it again");
            }
            details.setExpanded(false); settle(); assertEquals(closed, details.getHeight(), 1.0);

            // Chinese: the same structure, translated.
            bootstrap.localization().useLanguage("zh-CN"); settle();
            assertEquals("计算详情", details.getText()); assertEquals("传感器上的模糊", tileCaption("sensorBlur")); assertEquals("图像中的模糊", tileCaption("imageBlur"));
            for (Tool tool : CalculatorPane.PAGES) {
                select(tool); var section = pane.mode().detailsSection(); assertFalse(section.isExpanded());
                section.setExpanded(true); settle(); noMissing(pane);
                for (String line : pane.mode().detailTexts()) assertTrue(line.length() <= 90, tool + ": " + line);
                picture("f08-zh-details-" + tool.name().toLowerCase(Locale.ROOT)); section.setExpanded(false); settle();
            }
            bootstrap.localization().useLanguage("en"); settle();
        });
        System.out.println("CALC_DETAILS_KEYBOARD_TOGGLE_EXERCISED=" + keyboardToggled);
    }
    boolean keyboardToggled;

    @Test void invalidOrIncompleteInputIsExplainedInlineAndResultsAreNotLeftLookingCurrent() throws Exception {
        open(1280, 900);
        run(() -> {
            int windows = Window.getWindows().size();
            // Incomplete: an emptied field.
            type("aperture", "");
            assertEquals("Enter a value.", error("aperture")); assertTrue(status().startsWith("No result. Aperture: Enter a value."), status());
            assertEquals(CalculatorPresentation.NONE, result("settingsEv")); assertEquals(CalculatorPresentation.NONE, result("ev100")); assertTrue(pane.mode().diagram().stale());
            assertTrue(((Button) pane.lookup("#calc-equivalent-3")).isDisabled(), "pairs of an exposure that is not current cannot be applied");
            // Partly typed values are left exactly as typed and never corrected.
            type("aperture", "5."); assertEquals("5.", input("aperture").getText()); assertEquals("", error("aperture")); assertEquals("11.61", result("settingsEv"));
            type("shutter", "1/"); assertEquals("1/", input("shutter").getText()); assertFalse(error("shutter").isEmpty()); assertEquals(CalculatorPresentation.NONE, result("settingsEv"));
            type("shutter", "1/250"); assertEquals("", error("shutter")); assertEquals("", status()); assertFalse(pane.mode().diagram().stale());
            for (String bad : List.of("abc", "NaN", "Infinity", "-8", "0", "8/2", "1e999")) {
                type("aperture", bad);
                assertFalse(error("aperture").isEmpty(), bad); assertEquals(CalculatorPresentation.NONE, result("settingsEv"), bad); assertEquals(bad, input("aperture").getText());
            }
            type("aperture", "500"); assertEquals("Enter a value from 0.5 to 128.", error("aperture"));
            type("aperture", "8"); assertEquals("", status());
            assertEquals(windows, Window.getWindows().size(), "no dialog is ever opened for a typing mistake");

            // A condition of the optical model, reported by the shared calculation: focus inside the focal length.
            select(Tool.DOF); type("focalLength", "200"); type("distance", "0.15");
            assertTrue(status().contains("must exceed focal length"), status()); assertEquals(CalculatorPresentation.NONE, result("near")); assertEquals("", error("distance"));
            // Hyperfocal focus does not need the manual distance, so it has a result; manual focus brings the problem back.
            click("calc-focus-HYPERFOCAL"); assertEquals("", status()); assertEquals(CalculatorPresentation.INFINITY, result("far"));
            click("calc-focus-MANUAL"); assertEquals("0.15", input("distance").getText()); assertTrue(status().contains("must exceed focal length"), status());
            type("distance", "5"); assertEquals("", status());
            // The shared camera: unusable text stops the calculator it was typed in. The camera itself keeps its last
            // valid value, so the other calculators go on working with that.
            type("camera-width", "x"); assertTrue(status().contains("Camera"), status()); assertEquals(CalculatorPresentation.NONE, result("near"));
            assertEquals(36, pane.camera().sensorWidthMm(), 0.0);
            select(Tool.FOV); assertEquals("", status()); assertEquals("39.6°", result("horizontal")); assertEquals("36", input("camera-width").getText());
            select(Tool.EXPOSURE); assertEquals("", status()); assertEquals("13.97", result("settingsEv"), "f/8 at 1/250 s, untouched by the camera");
            select(Tool.DOF); assertEquals("x", input("camera-width").getText()); assertTrue(status().contains("Camera"));
            type("camera-width", "36"); assertEquals("", status()); assertNotEquals(CalculatorPresentation.NONE, result("near"));
            // Pixel dimensions: whole positive numbers, or empty for unknown; never inferred.
            select(Tool.MOTION);
            type("camera-image-width", "6000.5"); assertEquals("Enter a whole number.", error("camera-image-width")); assertTrue(status().contains("Camera"));
            type("camera-image-width", "0"); assertFalse(error("camera-image-width").isEmpty());
            type("camera-image-width", ""); assertEquals("", error("camera-image-width")); assertEquals("", status()); assertEquals(CalculatorPresentation.NONE, result("imageBlur"));
            // A tolerance in pixels without the image width: that one result is unavailable and says why; the rest stand.
            @SuppressWarnings("unchecked") var limitUnit = (ComboBox<MotionBlurMode.LimitUnit>) pane.lookup("#calc-motion-limit-unit");
            limitUnit.setValue(MotionBlurMode.LimitUnit.PIXELS); settle();
            assertEquals(CalculatorPresentation.NONE, result("longest")); assertTrue(detail("longest").contains("image width")); assertEquals("25 µm", result("sensorBlur")); assertEquals("", status());
            type("speed", "-1"); assertFalse(error("speed").isEmpty()); assertEquals(CalculatorPresentation.NONE, result("sensorBlur"));
            type("speed", "10");
            // Stops cannot be negative; a filter factor cannot be below 1.
            select(Tool.ND); type("ndStops", "-1"); assertFalse(error("ndStops").isEmpty()); assertEquals(CalculatorPresentation.NONE, result("adjusted"));
            type("ndStops", "10"); type("ndFactor", "0.5"); assertFalse(error("ndFactor").isEmpty()); assertEquals(CalculatorPresentation.NONE, result("adjusted"));
            type("ndFactor", "1024"); assertEquals("8.19 s", result("adjusted"));
            picture("f08-nd-valid-again");
            select(Tool.EXPOSURE); type("iso", ""); picture("f08-exposure-unavailable");
        });
    }

    @Test void workspaceFitsNarrowWindowsFollowsThemeAndLanguageAndNeverCallsTheModel() throws Exception {
        // A reachable fake model endpoint with a key: any AI request from the calculators would be recorded here.
        try (var gemini = new FakeGemini()) {
            open(gemini.environment(directory), 1120, 760);
            run(() -> { for (Tool tool : CalculatorPane.PAGES) { select(tool); noMissing(pane); assertEquals("", status(), tool.name()); assertFalse(pane.mode().diagram().stale()); } });
            // Minimum window: every control stays inside the window and no field collapses, in both focus modes.
            resize(880, 600);
            run(() -> {
                assertTrue(workspace.getScene().getWidth() <= 880);
                for (String page : List.of("EXPOSURE", "ND", "DOF", "DOF-HYPERFOCAL", "FOV", "MOTION")) {
                    Tool tool = Tool.valueOf(page.replace("-HYPERFOCAL", ""));
                    select(tool);
                    if (page.endsWith("HYPERFOCAL")) click("calc-focus-HYPERFOCAL");
                    double limit = workspace.getScene().getWidth() + 0.5;
                    for (Node node : pane.lookupAll(".text-field")) { if (!visible(node)) continue; Bounds b = node.localToScene(node.getBoundsInLocal()); assertTrue(b.getMaxX() <= limit && b.getWidth() >= 60, tool + " field " + node.getId() + " " + b); }
                    for (Node node : pane.lookupAll(".slider")) { if (!visible(node)) continue; Bounds b = node.localToScene(node.getBoundsInLocal()); assertTrue(b.getMaxX() <= limit && b.getWidth() >= 80, tool + " slider " + node.getId() + " " + b); }
                    for (Node node : pane.lookupAll(".segment")) { if (!visible(node)) continue; Bounds b = node.localToScene(node.getBoundsInLocal()); assertTrue(b.getMaxX() <= limit, tool + " segment " + node.getId()); }
                    Bounds diagram = pane.mode().diagram().localToScene(pane.mode().diagram().getBoundsInLocal());
                    assertTrue(diagram.getMaxX() <= limit && diagram.getWidth() >= 240, tool + " diagram " + diagram);
                    snapshot(workspace, "f08-min-" + page.toLowerCase(Locale.ROOT));
                    if (page.endsWith("HYPERFOCAL")) { picture("f08-min-dof-hyperfocal-page"); click("calc-focus-MANUAL"); }
                }
                select(Tool.DOF);
                Bounds controls = pane.lookup(".calc-controls").localToScene(pane.lookup(".calc-controls").getBoundsInLocal()), diagram = pane.mode().diagram().localToScene(pane.mode().diagram().getBoundsInLocal());
                assertTrue(controls.getMaxX() <= diagram.getMinX() + 1, "inputs beside the diagram at the minimum window size");
            });
            // Narrower than the two-column view needs (a window narrower than the application allows): it stacks instead of squeezing.
            resize(700, 600);
            run(() -> {
                select(Tool.DOF);
                Bounds controls = pane.lookup(".calc-controls").localToScene(pane.lookup(".calc-controls").getBoundsInLocal()), diagram = pane.mode().diagram().localToScene(pane.mode().diagram().getBoundsInLocal());
                assertTrue(diagram.getMaxY() <= controls.getMinY() + 1, "stacked: the diagram above the inputs");
                snapshot(workspace, "f08-stacked-dof");
            });
            resize(1120, 760);

            // Dark mode: the same views relit from the theme tokens, including the canvases. The stylesheet swap is
            // applied on the pulse after the change, so each step is its own turn on the JavaFX thread.
            var appearance = ThemeManager.get(); var originalMode = appearance.mode();
            try {
                run(() -> appearance.select(Theme.Mode.LIGHT));
                run(() -> { select(Tool.DOF); lightLuminance = luminance(pane.lookup(".calc-diagram-box")); });
                run(() -> appearance.select(Theme.Mode.DARK));
                run(() -> {
                    assertTrue(CalculatorDiagram.Palette.of(appearance.theme()).text().getBrightness() > 0.8, "light text on dark");
                    for (Tool tool : CalculatorPane.PAGES) { select(tool); snapshot(workspace, "f08-dark-" + tool.name().toLowerCase(Locale.ROOT)); }
                    select(Tool.DOF); darkLuminance = luminance(pane.lookup(".calc-diagram-box"));
                    click("calc-focus-HYPERFOCAL"); snapshot(workspace, "f08-dark-dof-hyperfocal"); click("calc-focus-MANUAL");
                });
            } finally { run(() -> appearance.select(originalMode)); }

            // Font Size, Bold Text and Transparency: the page follows them like the rest of the interface, canvas text included.
            int originalSize = appearance.fontSize(), originalOpacity = appearance.opacity(); boolean originalBold = appearance.boldText();
            try {
                double regular = fx(() -> { select(Tool.DOF); return ((Label) pane.lookup("#calc-result-near")).getFont().getSize(); });
                run(() -> { appearance.previewFontSize(125); appearance.selectBoldText(true); appearance.previewOpacity(0); });
                run(() -> {
                    select(Tool.DOF);
                    assertEquals(regular * 1.25, ((Label) pane.lookup("#calc-result-near")).getFont().getSize(), 0.5, "result text follows Font Size");
                    for (Node node : pane.lookupAll(".text-field")) { if (!visible(node)) continue; Bounds b = node.localToScene(node.getBoundsInLocal()); assertTrue(b.getMaxX() <= workspace.getScene().getWidth() + 0.5, "large text keeps " + node.getId() + " inside the window"); }
                    snapshot(workspace, "f08-large-dof");
                    // No button or segment caption is shortened with an ellipsis: long captions wrap instead.
                    for (Tool tool : CalculatorPane.PAGES) {
                        select(tool);
                        var captions = new ArrayList<Node>(pane.lookupAll(".button")); captions.addAll(pane.lookupAll(".segment"));
                        for (Node node : captions) {
                            if (!(node instanceof ButtonBase button) || !visible(button)) continue;
                            for (Node child : button.getChildrenUnmodifiable()) if (child instanceof javafx.scene.text.Text shown)
                                assertEquals(button.getText().replace("\n", ""), shown.getText().replace("\n", ""), tool + ": " + button.getId() + " is cut short");
                        }
                    }
                    select(Tool.DOF); click("calc-focus-HYPERFOCAL"); picture("f08-large-dof-hyperfocal"); click("calc-focus-MANUAL");
                    select(Tool.EXPOSURE); picture("f08-large-exposure");
                    select(Tool.MOTION); picture("f08-large-motion");
                });
            } finally { run(() -> { appearance.previewFontSize(originalSize); appearance.selectBoldText(originalBold); appearance.previewOpacity(originalOpacity); }); }

            run(() -> {
                // Chinese: every text of every calculator, results and inputs unchanged.
                select(Tool.DOF); type("distance", "4.5"); String near = result("near");
                bootstrap.localization().useLanguage("zh-CN"); settle();
                assertEquals("景深与超焦距", ((ToggleButton) pane.lookup("#calc-mode-DOF")).getText());
                assertEquals("手动对焦", ((ToggleButton) pane.lookup("#calc-focus-MANUAL")).getText()); assertEquals("超焦距对焦", ((ToggleButton) pane.lookup("#calc-focus-HYPERFOCAL")).getText());
                assertEquals("4.5", input("distance").getText()); assertEquals(near, result("near")); assertSame(pane.mode(Tool.DOF), pane.mode());
                assertEquals("重置此计算器", ((Button) pane.lookup("#calc-reset")).getText());
                for (Tool tool : CalculatorPane.PAGES) { select(tool); noMissing(pane); assertEquals("", status()); picture("f08-zh-" + tool.name().toLowerCase(Locale.ROOT)); }
                // Locked in Chinese: the caption, the note and the diagram say so; switching language keeps the lock.
                select(Tool.DOF); click("calc-focus-HYPERFOCAL");
                assertEquals("对焦距离（已锁定）", caption("distance")); assertTrue(((Label) pane.lookup("#calc-focus-note")).getText().contains("4.5 m"));
                noMissing(pane); picture("f08-zh-dof-hyperfocal");
                bootstrap.localization().useLanguage("en"); settle();
                assertEquals("Focus distance (locked)", caption("distance")); assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, ((DepthOfFieldMode) pane.mode()).focusMode());
                bootstrap.localization().useLanguage("zh-CN"); settle();
                click("calc-focus-MANUAL"); assertEquals("4.5", input("distance").getText()); assertEquals("对焦距离", caption("distance"));
                select(Tool.EXPOSURE); type("aperture", "");
                assertTrue(status().contains("光圈"), status());
                type("aperture", "8");
                bootstrap.localization().useLanguage("en"); settle();
                assertEquals("Depth of field & hyperfocal distance", ((ToggleButton) pane.lookup("#calc-mode-DOF")).getText());
            });
            assertTrue(lightLuminance > 0.6 && darkLuminance < 0.4, "diagram frame light " + lightLuminance + " dark " + darkLuminance);
            assertTrue(gemini.requests.isEmpty(), "opening and using the calculators sends no AI request");
        }
    }
    double lightLuminance, darkLuminance;
    /** Resizes the window and waits until the scene has the new size and has been laid out. */
    void resize(double width, double height) throws Exception {
        // The window frame's own width is whatever separates the stage from its scene before the change.
        double frame = fx(() -> stage.getWidth() - stage.getScene().getWidth());
        run(() -> { stage.setWidth(width); stage.setHeight(height); });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fx(() -> Math.abs(stage.getScene().getWidth() - (width - frame)) > 1.5 || Math.abs(workspace.getWidth() - stage.getScene().getWidth()) > 0.5)) {
            if (System.nanoTime() > deadline) fail("Resize timed out"); Thread.sleep(20);
        }
        run(this::settle);
    }
    static boolean visible(Node node) { for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible()) return false; return node.getScene() != null; }
    static double luminance(Node node) {
        var image = node.snapshot(null, null); var reader = image.getPixelReader(); double sum = 0; int count = 0;
        for (int y = 2; y < image.getHeight() - 2; y += 3) for (int x = 2; x < image.getWidth() - 2; x += 3) { var c = reader.getColor(x, y); sum += .2126 * c.getRed() + .7152 * c.getGreen() + .0722 * c.getBlue(); count++; }
        return sum / count;
    }

    @Test void inventoryValuesAreCopiedOnlyOnRequestAndNeverInferred() throws Exception {
        open(864, 561);
        var filter = com.photopilot.application.EquipmentForm.parse(com.photopilot.model.EquipmentType.FILTER, UUID.randomUUID(), Map.of("brand", "Test", "model", "ND", "diameterMm", "77", "ndStops", "6"), null).value().orElseThrow();
        var camera = com.photopilot.application.EquipmentForm.parse(com.photopilot.model.EquipmentType.CAMERA, UUID.randomUUID(), Map.of("brand", "Test", "model", "Body", "mount", "E", "sensorWidthMm", "23.6", "sensorHeightMm", "15.7", "resolutionMP", "26"), null).value().orElseThrow();
        var zoom = com.photopilot.application.EquipmentForm.parse(com.photopilot.model.EquipmentType.LENS, UUID.randomUUID(), Map.of("brand", "Test", "model", "Zoom", "mount", "E", "minFocalMm", "24", "maxFocalMm", "70", "maxAperture", "2.8"), null).value().orElseThrow();
        for (var item : List.of(filter, camera, zoom)) assertTrue(bootstrap.facade().inventory().add(item, false).isSuccess());
        byte[] stored = java.nio.file.Files.readAllBytes(bootstrap.storageScope().inventoryFile());
        run(() -> { select(Tool.ND); assertTrue(pane.lookup("#calc-gear-row").isVisible()); click("calc-load-gear"); });
        awaitInventory();
        run(() -> {
            @SuppressWarnings("unchecked") var gear = (ComboBox<com.photopilot.model.Equipment>) pane.lookup("#calc-gear");
            assertEquals(3, gear.getItems().size());
            gear.setValue(filter); click("calc-use-gear");
            assertEquals("6", input("ndStops").getText()); assertEquals("0.512 s", result("adjusted")); assertTrue(pane.gearFeedback().contains("copied"));
            // A zoom lens has no single focal length and a filter has nothing for depth of field: nothing is guessed.
            select(Tool.DOF);
            gear.setValue(zoom); click("calc-use-gear");
            assertEquals("50", input("focalLength").getText()); assertTrue(pane.gearFeedback().contains("No usable values"), pane.gearFeedback());
            // A camera body gives its sensor dimensions to the shared camera; megapixels never become pixel dimensions.
            // With the focus locked at the hyperfocal distance, the lock follows the copied sensor.
            click("calc-focus-HYPERFOCAL"); String fullFrame = input("distance").getText();
            gear.setValue(camera); click("calc-use-gear");
            assertEquals(23.6, pane.camera().sensorWidthMm(), 0.0); assertEquals(15.7, pane.camera().sensorHeightMm(), 0.0); assertEquals(CameraSetup.CUSTOM, pane.camera().formatId());
            assertTrue(pane.camera().imageWidthPx().isEmpty()); assertEquals("23.6", input("camera-width").getText());
            assertEquals(CalculatorPresentation.fieldNumber(hyperfocal(50, 8)), input("distance").getText()); assertNotEquals(fullFrame, input("distance").getText());
            // Minimum window: inputs stay usable and inside it.
            settle(); assertTrue(workspace.getWidth() <= 880);
            for (String key : List.of("focalLength", "aperture", "distance")) { Bounds b = input(key).localToScene(input(key).getBoundsInLocal()); assertTrue(b.getWidth() >= 60 && b.getMaxX() <= workspace.getScene().getWidth(), key + " " + b); }
            snapshot(workspace, "f08-dof-narrow");
            // Leaving and reopening the page drops the loaded list (it may have changed); the inputs stay.
            ((ToggleButton) workspace.lookup("#nav-F01")).fire(); assertFalse(pane.isVisible());
            ((ToggleButton) workspace.lookup("#nav-F08")).fire(); settle();
            assertTrue(gear.getItems().isEmpty()); assertSame(pane.mode(Tool.DOF), pane.mode()); assertEquals("23.6", input("camera-width").getText());
            assertEquals(DepthOfFieldMode.FocusMode.HYPERFOCAL, ((DepthOfFieldMode) pane.mode()).focusMode(), "leaving the page keeps the focus mode");
        });
        assertArrayEquals(stored, java.nio.file.Files.readAllBytes(bootstrap.storageScope().inventoryFile()), "the inventory is only read");
    }
    void awaitInventory() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(() -> ((Button) pane.lookup("#calc-load-gear")).isDisabled())) { if (System.nanoTime() > deadline) fail("Inventory load timed out"); Thread.sleep(20); }
    }
}
