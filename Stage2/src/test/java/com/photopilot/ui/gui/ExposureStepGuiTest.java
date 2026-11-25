package com.photopilot.ui.gui;

import com.photopilot.application.CalculationForm.Tool;
import com.photopilot.application.CalculatorPresentation;
import com.photopilot.application.ExposurePresentation;
import com.photopilot.application.ExposureSolver;
import com.photopilot.application.ExposureSolver.Request;
import com.photopilot.application.ExposureStops;
import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Step;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Requires a graphical desktop. Exposure steps on the exposure value page through its real controls in a real window:
 * the 1/3 EV default, sliders and arrow keys moving one stop, typed values matched to a stop when committed, solved
 * settings on stops, a target that does not drift, Exact, 1/2 and 1 EV, equivalent pairs, details, reset, and the same
 * result as the command line.
 */
class ExposureStepGuiTest {
    @TempDir Path directory;
    ExecutorService executor;
    ApplicationBootstrap bootstrap;
    CalculatorPane pane; PhotoPilotWorkspace workspace; Stage stage;

    static void run(CalculatorGuiTest.ThrowingRunnable action) throws Exception { CalculatorGuiTest.run(action); }

    void open(double width, double height) throws Exception {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { }
        executor = Executors.newSingleThreadExecutor();
        bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()));
        run(() -> {
            pane = new CalculatorPane(bootstrap.facade(), executor, bootstrap.localization());
            workspace = new PhotoPilotWorkspace(bootstrap.localization()); workspace.connectCalculator(pane);
            stage = new Stage(); var scene = new Scene(workspace, width, height);
            scene.getStylesheets().add(PhotoPilotGUI.class.getResource("photopilot.css").toExternalForm()); ThemeManager.get().style(scene.getStylesheets());
            stage.setScene(scene); stage.show();
            ((ToggleButton) workspace.lookup("#nav-F08")).fire(); settle();
        });
    }
    @AfterEach void close() throws Exception {
        if (stage != null) run(() -> stage.close());
        if (bootstrap != null) bootstrap.close();
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    // ---- Helpers on real controls (FX thread) ------------------------------------------------------------------------
    void settle() { workspace.applyCss(); workspace.layout(); }
    ExposureMode mode() { return (ExposureMode) pane.mode(Tool.EXPOSURE); }
    TextField input(String key) { return Objects.requireNonNull((TextField) pane.lookup("#calc-" + key), key); }
    void type(String key, String text) { input(key).setText(text); settle(); }
    /** Enter in the field (the same commit as leaving it). */
    void commit(String key) { input(key).fireEvent(new ActionEvent()); settle(); }
    Slider slider(String key) { return (Slider) pane.lookup("#calc-" + key + "-slider"); }
    String hint(String key) { return ((Label) pane.lookup("#calc-" + key + "-hint")).getText(); }
    String result(String id) { return ((Label) pane.lookup("#calc-result-" + id)).getText(); }
    String detail(String id) { return ((Label) pane.lookup("#calc-detail-" + id)).getText(); }
    void click(String id) { ((ButtonBase) pane.lookup("#" + id)).fire(); settle(); }
    ToggleButton step(Step step) { return (ToggleButton) pane.lookup("#calc-step-" + step.name()); }
    void key(Slider slider, KeyCode code) { Event.fireEvent(slider, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)); settle(); }
    void picture(String name) throws java.io.IOException { settle(); CalculatorGuiTest.write(pane.snapshot(null, null), name); }
    static String key(Parameter parameter) { return parameter.key; }
    /** The field text of a stop: f-number and ISO as numbers, times as the familiar fraction. */
    static String fieldText(Parameter parameter, double stop) { return parameter == Parameter.SHUTTER ? CalculatorPresentation.shutterField(stop) : CalculatorPresentation.fieldNumber(stop); }
    double value(Parameter parameter) { return mode().solution().value(parameter); }

    @Test void standardStepsAreAppliedBySlidersKeysTypingAndSolving() throws Exception {
        open(1280, 900);
        run(() -> {
            var mode = mode();
            // 1/3 EV by default, chosen with a segmented control above the settings.
            assertEquals(Step.THIRD, mode.step()); assertTrue(step(Step.THIRD).isSelected());
            assertEquals(List.of("1/3 EV", "1/2 EV", "1 EV", "Exact"), List.of(step(Step.THIRD).getText(), step(Step.HALF).getText(), step(Step.FULL).getText(), step(Step.EXACT).getText()));
            assertEquals("Exposure step", ((Label) ((Parent) pane.lookup("#calc-step").getParent()).getChildrenUnmodifiable().getFirst()).getText());
            assertSame(pane.lookup("#calc-step").getParent(), ((Parent) pane.lookup(".calc-controls")).getChildrenUnmodifiable().getFirst(), "the step is the first control");
            assertEquals("Equivalent exposures (approx.)", ((Label) pane.lookup("#calc-equivalents-title")).getText());

            // Sliders: one position per stop, and every position shows the stop as marked.
            for (Parameter parameter : Parameter.values()) {
                var stops = ExposureStops.sequence(parameter, Step.THIRD); Slider slider = slider(key(parameter));
                assertEquals(stops.size() - 1, slider.getMax(), 0.0); assertTrue(slider.isSnapToTicks()); assertEquals(1, slider.getBlockIncrement(), 0.0);
                for (int i = 0; i < stops.size(); i++) {
                    slider.setValue(i + 0.4); settle();
                    assertEquals(fieldText(parameter, stops.get(i)), input(key(parameter)).getText(), parameter + " position " + i);
                    assertEquals(stops.get(i), value(parameter), 0.0);
                    if (parameter == Parameter.SHUTTER) assertTrue(input("shutter").getText().matches("1/\\d+|\\d+(\\.\\d)?"), input("shutter").getText());
                    if (parameter == Parameter.ISO) assertTrue(input("iso").getText().matches("\\d+"), input("iso").getText());
                }
            }
            click("calc-reset");
            // Arrow keys move one stop, like a camera dial.
            Slider aperture = slider("aperture");
            type("aperture", "5.6"); key(aperture, KeyCode.RIGHT); assertEquals("6.3", input("aperture").getText());
            key(aperture, KeyCode.RIGHT); assertEquals("7.1", input("aperture").getText());
            key(aperture, KeyCode.LEFT); key(aperture, KeyCode.LEFT); key(aperture, KeyCode.LEFT); assertEquals("5", input("aperture").getText());
            key(slider("shutter"), KeyCode.RIGHT); assertEquals("1/100", input("shutter").getText());
            click("calc-reset");

            // Typed values: left as typed until committed, applied as the nearest stop at once, rewritten on Enter.
            type("aperture", "17.8885");
            assertEquals("17.8885", input("aperture").getText(), "not rewritten while typing"); assertEquals("Applied standard value: f/18", hint("aperture"));
            assertEquals(ExposureStops.sequence(Parameter.APERTURE, Step.THIRD).indexOf(18.0), aperture.getValue(), 1e-9);
            assertEquals("f/18 at 1/125 s", detail("settingsEv")); assertTrue(mode.diagram().getAccessibleText().contains("f/18"), "the scale shows what is applied");
            commit("aperture"); assertEquals("18", input("aperture").getText()); assertEquals("", hint("aperture"));
            type("shutter", "0.00630125"); assertEquals("Applied standard value: 1/160 s", hint("shutter")); commit("shutter"); assertEquals("1/160", input("shutter").getText());
            type("iso", "198.373"); assertEquals("Applied standard value: ISO 200", hint("iso")); commit("iso"); assertEquals("200", input("iso").getText());
            assertEquals("f/18 at 1/160 s", detail("settingsEv")); assertEquals("At ISO 200", detail("ev100"));
            // A stop typed as a stop needs no note; unusable text is explained as before and never rewritten.
            type("aperture", "8"); assertEquals("", hint("aperture"));
            type("aperture", "8/2"); assertFalse(((Label) pane.lookup("#calc-aperture-error")).getText().isEmpty()); commit("aperture"); assertEquals("8/2", input("aperture").getText());
            type("aperture", "500"); assertEquals("Enter a value from 0.5 to 128.", ((Label) pane.lookup("#calc-aperture-error")).getText());
            click("calc-reset");
            picture("f08-exposure-third");

            // Each solved setting is a stop, called the nearest standard stop; the target stays as captured.
            for (ExposureMode.Setting solved : ExposureMode.Setting.values()) {
                click("calc-hold-" + solved.name());
                double target = mode.solution().target();
                assertEquals("Target EV100 12.97", ((Label) pane.lookup("#calc-hold-note")).getText());
                for (ExposureMode.Setting free : ExposureMode.Setting.values()) {
                    if (free == solved) continue;
                    Slider moved = slider(free.parameter().key);
                    for (double position : new double[] { moved.getValue() + 2, moved.getValue() - 1, moved.getValue() + 1 }) {
                        moved.setValue(position); settle();
                        var solution = mode.solution();
                        assertEquals(target, solution.target(), 0.0, "the target is not replaced by the result");
                        assertTrue(ExposureStops.sequence(solved.parameter(), Step.THIRD).contains(solution.value(solved.parameter())), solved + " " + solution);
                        assertEquals(fieldText(solved.parameter(), solution.value(solved.parameter())), input(solved.parameter().key).getText());
                        assertEquals("Nearest standard stop", hint(solved.parameter().key));
                        assertTrue(pane.lookup("#calc-tile-deviation").isVisible());
                        assertEquals(ExposurePresentation.signedEv(solution.deviation()), result("deviation"));
                        assertEquals(ExposurePresentation.direction(solution.deviation(), bootstrap.localization()), detail("deviation"));
                        assertEquals(CalculatorPresentation.decimals(solution.ev100(), 2), result("ev100"), "the actual EV100 of the applied settings");
                    }
                }
                if (solved == ExposureMode.Setting.ISO) picture("f08-exposure-third-hold-iso");
                click("calc-hold-OFF"); assertFalse(pane.lookup("#calc-tile-deviation").isVisible()); assertEquals("", hint(solved.parameter().key));
                click("calc-reset");
            }
        });
    }

    @Test void theTargetDoesNotDriftAndEveryStepAppliesItsOwnStops() throws Exception {
        open(1280, 900);
        run(() -> {
            var mode = mode();
            click("calc-hold-ISO");
            double target = mode.solution().target();
            assertEquals(Math.log(8000) / Math.log(2), target, 1e-12, "f/8, 1/125 s, ISO 100");
            // Repeated changes back and forth: each solution against the original target, the same settings give the same result.
            Slider aperture = slider("aperture"); double start = aperture.getValue();
            var seen = new HashMap<Double, String>();
            for (int round = 0; round < 3; round++) for (double offset : new double[] { 1, 2, 3, -2, -4, 5, 0, 7, -1, 0 }) {
                aperture.setValue(start + offset); settle();
                assertEquals(target, mode.solution().target(), 0.0);
                String shown = input("iso").getText() + " " + result("ev100") + " " + result("deviation");
                var earlier = seen.putIfAbsent(offset, shown);
                if (earlier != null) assertEquals(earlier, shown, "position " + offset);
            }
            aperture.setValue(start); settle();
            assertEquals("100", input("iso").getText()); assertEquals("0 EV", result("deviation")); assertEquals("On target", detail("deviation"));
            // Switching steps while solving keeps the target; each step's result is stable.
            var byStep = new HashMap<Step, String>();
            for (int round = 0; round < 3; round++) for (Step step : List.of(Step.HALF, Step.FULL, Step.EXACT, Step.THIRD, Step.FULL, Step.HALF)) {
                click("calc-step-" + step.name());
                assertEquals(step, mode.step()); assertEquals(target, mode.solution().target(), 0.0);
                assertEquals("Target EV100 12.97", ((Label) pane.lookup("#calc-hold-note")).getText());
                for (Parameter parameter : Parameter.values()) if (step.standard()) assertTrue(ExposureStops.sequence(parameter, step).contains(value(parameter)), step + " " + parameter);
                String shown = input("aperture").getText() + " " + input("shutter").getText() + " " + input("iso").getText() + " " + result("ev100");
                var earlier = byStep.putIfAbsent(step, shown);
                if (earlier != null) assertEquals(earlier, shown, step.name());
            }

            // Switching the solved setting keeps the target, also while the applied stops miss it (f/10: ISO 156 → 160).
            click("calc-step-THIRD"); aperture.setValue(start + 2); settle();
            assertNotEquals(0, mode.solution().deviation(), 1e-6, "a deviation a re-captured target would hide");
            for (String solved : List.of("APERTURE", "SHUTTER", "ISO")) {
                click("calc-hold-" + solved);
                assertEquals(target, mode.solution().target(), 0.0, solved); assertEquals("Target EV100 12.97", ((Label) pane.lookup("#calc-hold-note")).getText());
            }
            // Exact: continuous sliders, the precise solution applied, no rounding said in the details.
            click("calc-step-EXACT"); aperture.setValue(start + 0.5); settle();
            assertFalse(aperture.isSnapToTicks());
            var exact = mode.solution();
            assertEquals(exact.iso().exact(), exact.value(Parameter.ISO), 0.0); assertEquals(0, exact.deviation(), 1e-12);
            assertFalse(input("iso").getText().matches("\\d+"), "a precise ISO: " + input("iso").getText());
            assertEquals("", hint("iso"), "no stop is claimed in Exact");
            assertEquals("Equivalent exposures", ((Label) pane.lookup("#calc-equivalents-title")).getText());
            assertTrue(mode.detailTexts().stream().anyMatch(line -> line.endsWith("with no standard-stop rounding.")), mode.detailTexts().toString());
            assertTrue(mode.detailTexts().contains("Values are used as entered (f/5.6 is N = 5.6). No scene is metered."));
            picture("f08-exposure-exact-hold-iso");
            // Back to standard stops: the aperture moves to its nearest stop and ISO to a whole number again.
            click("calc-step-THIRD");
            assertTrue(ExposureStops.sequence(Parameter.APERTURE, Step.THIRD).contains(value(Parameter.APERTURE)));
            assertTrue(input("iso").getText().matches("\\d+"), input("iso").getText());

            // 1/2 EV and 1 EV: the sliders, the scales and the solved setting follow the step.
            for (Step step : List.of(Step.HALF, Step.FULL)) {
                click("calc-step-" + step.name());
                var diagram = (ExposureDiagram) mode.diagram();
                for (Parameter parameter : Parameter.values()) {
                    assertEquals(ExposureStops.sequence(parameter, step).size() - 1, slider(parameter.key).getMax(), 0.0, step + " " + parameter);
                    assertEquals(ExposureStops.sequence(parameter, step), diagram.markings(ExposureMode.Setting.valueOf(parameter.name())));
                }
                assertEquals(step, diagram.step());
                picture("f08-exposure-" + step.name().toLowerCase(java.util.Locale.ROOT) + "-hold-iso");
            }
            // Details while solving: the exact solution, the applied value and the deviation, with more digits.
            click("calc-step-THIRD"); aperture.setValue(start + 2); settle();
            var lines = mode.detailTexts();
            assertTrue(lines.size() <= 4, lines.toString());
            assertTrue(lines.get(1).startsWith("1/3 EV: settings are generic standard stops"), lines.toString());
            assertTrue(lines.get(2).startsWith("ISO solved: exact ISO "), lines.toString());
            assertTrue(lines.get(3).startsWith("Target EV100 12.9658, actual "), lines.toString());
            mode.detailsSection().setExpanded(true); picture("f08-exposure-details"); mode.detailsSection().setExpanded(false);

            // Reset: 1/3 EV, nothing held, the starting settings.
            click("calc-step-FULL"); click("calc-reset");
            assertEquals(Step.THIRD, mode.step()); assertTrue(step(Step.THIRD).isSelected()); assertNull(mode.held());
            assertEquals("8", input("aperture").getText()); assertEquals("1/125", input("shutter").getText()); assertEquals("100", input("iso").getText());
            assertTrue(slider("aperture").isSnapToTicks()); assertFalse(mode.changed());
            click("calc-step-HALF"); assertTrue(mode.changed(), "another step counts as changed input");
            click("calc-reset");
        });
    }

    @Test void equivalentPairsAreStandardStopsAndApplyLikeAnyOtherChange() throws Exception {
        open(1280, 900);
        run(() -> {
            var mode = mode();
            var times = new ArrayList<String>();
            for (double stop : ExposureStops.sequence(Parameter.SHUTTER, Step.THIRD)) times.add(CalculatorPresentation.exposureTime(stop, bootstrap.localization()));
            // f/8, 1/125 s, ISO 100: f/2.8 at 1/1000 s ... f/22 at 1/15 s, every time a 1/3 EV stop.
            var expected = List.of("f/2.8 at 1/1000 s", "f/4 at 1/500 s", "f/5.6 at 1/250 s", "Current: f/8 at 1/125 s", "f/11 at 1/60 s", "f/16 at 1/30 s", "f/22 at 1/15 s");
            for (int i = 0; i < 7; i++) {
                var chip = (Button) pane.lookup("#calc-equivalent-" + i);
                assertEquals(expected.get(i), chip.getText());
                assertTrue(times.contains(chip.getText().substring(chip.getText().indexOf(" at ") + 4)));
                assertTrue(chip.getTooltip().getText().startsWith("Apply this aperture and time\nExact time "), chip.getTooltip().getText());
                assertFalse(chip.isDisabled());
            }
            assertEquals(ExposureSolver.equivalents(mode.solution(), 3), mode.pairsShown(), "the pairs are the shared solver's");
            click("calc-equivalent-4");
            assertEquals("11", input("aperture").getText()); assertEquals("1/60", input("shutter").getText());
            // While the time is solved, applying a pair solves it again from the target.
            click("calc-reset"); click("calc-hold-SHUTTER");
            click("calc-equivalent-1");
            assertEquals("4", input("aperture").getText()); assertEquals("1/500", input("shutter").getText()); assertEquals("0 EV", result("deviation"));
            // Exact: the precise reciprocity times, as before.
            click("calc-hold-OFF"); click("calc-reset"); click("calc-step-EXACT");
            assertEquals("f/11 at 1/66.1 s", ((Button) pane.lookup("#calc-equivalent-4")).getText());
            picture("f08-exposure-exact-equivalents");
            click("calc-reset");
        });
    }

    @Test void thePageAndTheCommandLineGiveTheSameResult() throws Exception {
        open(1280, 900);
        double target = CalculatorGuiTest.fx(() -> {
            click("calc-hold-ISO");
            double captured = mode().solution().target();
            type("aperture", "10"); commit("aperture"); type("shutter", "1/160"); commit("shutter");
            return captured;
        });
        var gui = CalculatorGuiTest.fx(() -> mode().solution());
        var guiPairs = CalculatorGuiTest.fx(() -> mode().pairsShown());
        assertEquals(10.0, gui.value(Parameter.APERTURE)); assertEquals(Parameter.ISO, gui.solved());
        // The command line with the same settings, step and target.
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), bootstrap.localization());
        assertEquals(0, cli.execute("calc exposure aperture=10 shutter=1/160 solve=iso target=" + target + " step=third"));
        var cliSolution = ExposureSolver.solve(Request.of(Step.THIRD, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, target), bootstrap.facade()::calculate).value().orElseThrow();
        assertEquals(gui, cliSolution, "same exact solution, applied values, EV, EV100 and deviation");
        assertEquals(ExposureSolver.equivalents(cliSolution, 3).stream().filter(ExposureSolver.Equivalent::supported).toList(), guiPairs, "same equivalent pairs");
        assertEquals(ExposurePresentation.report(gui, ExposureSolver.equivalents(gui, 3), bootstrap.localization()) + System.lineSeparator(), bytes.toString(StandardCharsets.UTF_8));
        CalculatorGuiTest.run(() -> {
            assertEquals(CalculatorPresentation.decimals(gui.ev100(), 2), result("ev100"));
            assertEquals(ExposurePresentation.signedEv(gui.deviation()), result("deviation"));
            assertEquals(CalculatorPresentation.fieldNumber(gui.value(Parameter.ISO)), input("iso").getText());
        });
    }

    @Test void chineseDarkModeAndTheMinimumWindow() throws Exception {
        open(1120, 760);
        var appearance = ThemeManager.get(); var original = appearance.mode();
        try {
            run(() -> {
                bootstrap.localization().useLanguage("zh-CN"); settle();
                assertEquals("曝光档位", ((Label) ((Parent) pane.lookup("#calc-step").getParent()).getChildrenUnmodifiable().getFirst()).getText());
                assertEquals("精确计算", step(Step.EXACT).getText()); assertEquals("1/3 EV", step(Step.THIRD).getText());
                assertEquals("近似等效曝光", ((Label) pane.lookup("#calc-equivalents-title")).getText());
                type("aperture", "17.8885"); assertEquals("实际应用档位：f/18", hint("aperture")); commit("aperture");
                click("calc-hold-ISO"); slider("aperture").setValue(slider("aperture").getValue() + 2); settle();
                assertEquals("目标 EV100 15.31", ((Label) pane.lookup("#calc-hold-note")).getText(), "f/18, 1/125 s, ISO 100: log2(18² × 125)");
                assertEquals("最接近的标准档位", hint("iso"));
                assertEquals("曝光偏差", ((Label) pane.lookup("#calc-tile-deviation").lookup(".calc-tile-caption")).getText());
                CalculatorGuiTest.noMissing(pane);
                for (String line : mode().detailTexts()) assertTrue(line.length() <= 90, line);
                picture("f08-zh-exposure-third-hold");
                bootstrap.localization().useLanguage("en"); settle();
                assertEquals("Nearest standard stop", hint("iso"), "the note follows the language");
                appearance.select(Theme.Mode.DARK);
            });
            run(() -> { settle(); CalculatorGuiTest.snapshot(workspace, "f08-dark-exposure-third-hold"); });
        } finally { run(() -> appearance.select(original)); }
        // The minimum window: the step selector and the solved field stay inside the window, captions are not cut short.
        double frame = CalculatorGuiTest.fx(() -> stage.getWidth() - stage.getScene().getWidth());
        run(() -> { stage.setWidth(880); stage.setHeight(600); });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (CalculatorGuiTest.fx(() -> Math.abs(stage.getScene().getWidth() - (880 - frame)) > 1.5)) { if (System.nanoTime() > deadline) fail("resize"); Thread.sleep(20); }
        run(() -> {
            settle();
            double limit = workspace.getScene().getWidth() + 0.5;
            for (var node : pane.lookupAll(".segment")) {
                if (!CalculatorGuiTest.visible(node)) continue;
                var bounds = node.localToScene(node.getBoundsInLocal()); assertTrue(bounds.getMaxX() <= limit, node.getId());
                var button = (ButtonBase) node;
                for (var child : button.getChildrenUnmodifiable()) if (child instanceof javafx.scene.text.Text shown) assertEquals(button.getText(), shown.getText(), node.getId() + " is cut short");
            }
            CalculatorGuiTest.snapshot(workspace, "f08-min-exposure-third-hold");
        });
    }
}
