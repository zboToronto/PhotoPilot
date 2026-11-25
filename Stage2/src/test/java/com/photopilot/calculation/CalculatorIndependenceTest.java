package com.photopilot.calculation;

import com.photopilot.model.CalculationInput;
import com.photopilot.model.CalculationKind;
import com.photopilot.service.TechnicalCalculator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The six calculators are local Java: no model, key, network, browser engine, script engine or external process.
 * Checked two ways: the calculation service works with nothing configured at all, and no calculator source refers to
 * anything that could reach outside the process. (That using the workspace sends no AI request is checked against a
 * recording endpoint in CalculatorGuiTest.)
 */
class CalculatorIndependenceTest {
    private static final Path MAIN = Path.of("src/main/java/com/photopilot");
    private static final List<String> FORBIDDEN = List.of("java.net", "HttpClient", "LLMClient", "GeminiAdapter", "PhotoPilotAgent", "AgentContext", "MiniAiService",
        "javafx.scene.web", "WebView", "WebEngine", "ScriptEngine", "ProcessBuilder", "Runtime.getRuntime", "System.getenv", ".send(", "URI.create", "Socket");

    private static List<Path> calculatorSources() throws Exception {
        List<Path> sources = new ArrayList<>(List.of(MAIN.resolve("service/PhotoFormulas.java"), MAIN.resolve("service/TechnicalCalculator.java"),
            MAIN.resolve("service/CalculationValidation.java"), MAIN.resolve("application/CalculationForm.java"), MAIN.resolve("application/CalculatorPresentation.java"),
            MAIN.resolve("application/MotionBlurPreview.java"), MAIN.resolve("model/CameraSetup.java"), MAIN.resolve("model/CalculationInput.java"),
            MAIN.resolve("model/CalculationResult.java"), MAIN.resolve("ui/cli/CalculationCommands.java"),
            MAIN.resolve("application/ExposureStops.java"), MAIN.resolve("application/ExposureSolver.java"), MAIN.resolve("application/ExposurePresentation.java")));
        try (var gui = Files.list(MAIN.resolve("ui/gui"))) {
            gui.filter(path -> path.getFileName().toString().matches("(Calculator.*|.*Mode|.*Diagram|CameraSetupEditor)\\.java")).forEach(sources::add);
        }
        return sources;
    }

    @Test void calculatorSourcesReachNothingOutsideTheProcess() throws Exception {
        var sources = calculatorSources();
        // The five pages (depth of field and hyperfocal distance share one), their five diagrams and the shared pieces
        // are all found, so the audit is not empty.
        Set<String> names = new java.util.TreeSet<>();
        for (Path source : sources) names.add(source.getFileName().toString());
        assertFalse(names.contains("HyperfocalMode.java"), "no separate hyperfocal page");
        assertTrue(names.containsAll(List.of("ExposureMode.java", "NdFilterMode.java", "DepthOfFieldMode.java", "FieldOfViewMode.java", "MotionBlurMode.java",
            "ExposureDiagram.java", "NdFilterDiagram.java", "FocusDiagram.java", "FieldOfViewDiagram.java", "MotionBlurDiagram.java",
            "CalculatorPane.java", "CalculatorMode.java", "CalculatorField.java", "CalculatorDiagram.java", "CameraSetupEditor.java")), names.toString());
        // The facade also fronts AI features; a calculator may use only its local calculation and inventory read.
        Pattern facadeCall = Pattern.compile("\\bapp\\.(\\w+)\\(");
        for (Path source : sources) {
            String code = Files.readString(source);
            for (String forbidden : FORBIDDEN) assertFalse(code.contains(forbidden), source.getFileName() + " refers to " + forbidden);
            var calls = facadeCall.matcher(code);
            while (calls.find()) assertTrue(Set.of("calculate", "inventory").contains(calls.group(1)), source.getFileName() + " calls app." + calls.group(1));
        }
        // The formula core and the display conventions do not depend on JavaFX either: they are plain Java.
        for (String pure : List.of("service/PhotoFormulas.java", "service/TechnicalCalculator.java", "service/CalculationValidation.java",
            "application/CalculatorPresentation.java", "application/MotionBlurPreview.java", "application/CalculationForm.java", "model/CameraSetup.java",
            "application/ExposureStops.java", "application/ExposureSolver.java", "application/ExposurePresentation.java"))
            assertFalse(Files.readString(MAIN.resolve(pure)).contains("javafx"), pure + " must not need a display");
    }

    @Test void everyCalculatorWorksWithNothingConfigured() {
        // No environment, no storage, no key, no localization service, no application bootstrap: only the calculator.
        var calculator = new TechnicalCalculator();
        Map<CalculationKind, CalculationInput> inputs = Map.of(
            CalculationKind.EXPOSURE_ND, new CalculationInput(CalculationKind.EXPOSURE_ND, Map.of("aperture", 8.0, "shutter", 0.008, "iso", 200.0, "ndStops", 10.0),
                Map.of("aperture", "f-number", "shutter", "s", "iso", "ISO", "ndStops", "stops")),
            CalculationKind.DEPTH_OF_FIELD, new CalculationInput(CalculationKind.DEPTH_OF_FIELD, Map.of("focalLength", 50.0, "aperture", 8.0, "coc", 0.03, "distance", 3.0),
                Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm", "distance", "m")),
            CalculationKind.HYPERFOCAL, new CalculationInput(CalculationKind.HYPERFOCAL, Map.of("focalLength", 50.0, "aperture", 8.0, "coc", 0.03),
                Map.of("focalLength", "mm", "aperture", "f-number", "coc", "mm")),
            CalculationKind.FIELD_OF_VIEW, new CalculationInput(CalculationKind.FIELD_OF_VIEW, Map.of("focalLength", 50.0, "sensorWidth", 36.0, "sensorHeight", 24.0, "distance", 20.0),
                Map.of("focalLength", "mm", "sensorWidth", "mm", "sensorHeight", "mm", "distance", "m")),
            CalculationKind.MOTION_BLUR, new CalculationInput(CalculationKind.MOTION_BLUR, Map.of("shutter", 0.001, "focalLength", 50.0, "distance", 20.0, "speed", 10.0, "sensorWidth", 36.0, "imageWidth", 6000.0),
                Map.of("shutter", "s", "focalLength", "mm", "distance", "m", "speed", "m/s", "sensorWidth", "mm", "imageWidth", "px")));
        Map<CalculationKind, Map<String, Double>> expected = Map.of(
            CalculationKind.EXPOSURE_ND, Map.of(TechnicalCalculator.SETTING_EV, 12.9657842847, TechnicalCalculator.EV100, 11.9657842847, TechnicalCalculator.ADJUSTED_SHUTTER, 8.192),
            CalculationKind.DEPTH_OF_FIELD, Map.of(TechnicalCalculator.NEAR_LIMIT, 2.3379052369, TechnicalCalculator.FAR_LIMIT, 4.1852678571),
            CalculationKind.HYPERFOCAL, Map.of(TechnicalCalculator.HYPERFOCAL_DISTANCE, 10.4666666667),
            CalculationKind.FIELD_OF_VIEW, Map.of(TechnicalCalculator.HORIZONTAL_FOV, 39.597752709, TechnicalCalculator.SCENE_WIDTH, 14.4),
            CalculationKind.MOTION_BLUR, Map.of(TechnicalCalculator.SENSOR_BLUR, 0.025, TechnicalCalculator.IMAGE_BLUR, 4.1666666667));
        inputs.forEach((kind, input) -> {
            var result = calculator.calculate(input);
            assertTrue(result.isSuccess(), kind + ": " + result.errors());
            expected.get(kind).forEach((name, value) -> assertEquals(value, result.value().orElseThrow().values().get(name), 1e-9, kind + " " + name));
        });
    }
}
