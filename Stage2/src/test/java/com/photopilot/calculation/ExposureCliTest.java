package com.photopilot.calculation;

import com.photopilot.application.CalculationForm;
import com.photopilot.application.ExposurePresentation;
import com.photopilot.application.ExposureSolver;
import com.photopilot.application.ExposureSolver.Request;
import com.photopilot.application.ExposureStops.Parameter;
import com.photopilot.application.ExposureStops.Step;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizationService;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The exposure value calculator on the command line: the existing exact command is unchanged, and step=, solve= and
 * target= give the report built from the same {@link ExposureSolver} as the GUI page.
 */
class ExposureCliTest {
    @TempDir Path directory;

    interface Session { void run(ApplicationBootstrap bootstrap, PhotoPilotCLI cli, ByteArrayOutputStream bytes) throws Exception; }
    void session(Session session) throws Exception {
        try (var bootstrap = new ApplicationBootstrap(Map.of("PHOTOPILOT_DATA_DIR", directory.resolve("data").toString(), "PHOTOPILOT_CONFIG_DIR", directory.resolve("config").toString()))) {
            var bytes = new ByteArrayOutputStream();
            var cli = new PhotoPilotCLI(bootstrap.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), bootstrap.localization());
            session.run(bootstrap, cli, bytes);
        }
    }
    static String text(ByteArrayOutputStream bytes) { String out = bytes.toString(StandardCharsets.UTF_8); bytes.reset(); return out.replace("\r\n", "\n"); }

    @Test void theExistingExactCommandIsUnchanged() throws Exception {
        session((bootstrap, cli, bytes) -> {
            assertEquals(0, cli.execute("calc exposure aperture=8 shutter=1/128 iso=400"));
            var expected = CalculationForm.format(bootstrap.facade().calculate(CalculationForm.parse(CalculationForm.Tool.EXPOSURE,
                Map.of("aperture", "8", "shutter", "1/128", "iso", "400")).value().orElseThrow()).value().orElseThrow(), bootstrap.localization());
            String out = text(bytes);
            assertEquals(expected + "\n", out, "same text, byte for byte, as the shared formatter of every calculator");
            assertTrue(out.contains("13 EV") && out.contains("11 EV100"), "1/128 s is used as typed: no stop is applied");
            assertFalse(out.contains("Exposure step"));
        });
    }

    @Test void stepAppliesStandardStopsAndPrintsWhatWasMatched() throws Exception {
        session((bootstrap, cli, bytes) -> {
            assertEquals(0, cli.execute("calc exposure aperture=17.8885 shutter=0.00630125 iso=198.373 step=third"));
            String out = text(bytes);
            assertTrue(out.startsWith("PHOTOPILOT — EXPOSURE VALUE CALCULATOR"), out);
            assertTrue(out.contains("Exposure step:") && out.contains("1/3 EV"), out);
            assertTrue(out.matches("(?s).*Aperture:\\s+f/18\\n.*"), out);
            assertTrue(out.matches("(?s).*Exposure time:\\s+1/160 s\\n.*"), out);
            assertTrue(out.matches("(?s).*ISO:\\s+200\\n.*"), out);
            assertTrue(out.contains("Matched to standard stops") && out.contains("f/17.8885 → f/18") && out.contains("ISO 198.373 → ISO 200"), out);
            assertTrue(out.contains("Equivalent exposures (approx.)"), out);
            assertTrue(out.contains("a particular camera or lens may not offer every value"), "not presented as what every camera offers");
            // Equivalent pairs are standard stops: f/5.6 at 1/1600 s ... f/45 at 1/25 s for f/18, 1/160 s, ISO 200.
            assertTrue(out.contains("f/5.6     1/1600 s") && out.contains("f/45      1/25 s"), out);
            var solution = ExposureSolver.solve(Request.of(Step.THIRD, 17.8885, 0.00630125, 198.373), bootstrap.facade()::calculate).value().orElseThrow();
            assertEquals(ExposurePresentation.report(solution, ExposureSolver.equivalents(solution, 3), bootstrap.localization()) + "\n", out, "the shared report of the shared solution");
            for (String step : new String[] { "half", "full", "1/2" }) { assertEquals(0, cli.execute("calc exposure aperture=8 shutter=0.00630125 iso=100 step=" + step)); assertTrue(text(bytes).contains("EV"), step); }
            assertEquals(0, cli.execute("calc exposure aperture=8 shutter=0.00630125 iso=100 step=half"));
            assertTrue(text(bytes).matches("(?s).*Exposure time:\\s+1/180 s\\n.*"));
            assertEquals(0, cli.execute("calc exposure aperture=8 shutter=0.00630125 iso=100 step=full"));
            assertTrue(text(bytes).matches("(?s).*Exposure time:\\s+1/125 s\\n.*"));
            assertEquals(0, cli.execute("calc exposure aperture=17.8885 shutter=0.00630125 iso=198.373 step=exact"));
            out = text(bytes);
            assertTrue(out.contains("Exact") && out.contains("Equivalent exposures\n") && !out.contains("Matched to standard stops"), out);
            assertTrue(out.contains("Exact: no standard-stop rounding"), out);
        });
    }

    @Test void solvingHoldsTheGivenTargetAndShowsTheExactAndAppliedValues() throws Exception {
        session((bootstrap, cli, bytes) -> {
            assertEquals(0, cli.execute("calc exposure aperture=10 shutter=1/160 solve=iso target=12.97"));
            String out = text(bytes);
            assertTrue(out.contains("1/3 EV"), "1/3 EV unless another step is asked for");
            assertTrue(out.matches("(?s).*Auto solve:\\s+ISO\\n.*"), out);
            assertTrue(out.matches("(?s).*ISO:\\s+200\\n.*"), out);
            assertTrue(out.matches("(?s).*Target EV100:\\s+12\\.97\\n.*"), out);
            assertTrue(out.matches("(?s).*Actual EV100:\\s+12\\.97\\n.*"), out);
            assertTrue(out.contains("Exact solution:") && out.contains("ISO 199.416"), out);
            assertTrue(out.contains("Applied standard value:") && out.contains("Nearest standard stop"), out);
            // 12.965784 − 12.97: more exposure than the target, too small for two decimals but not shown as zero.
            assertTrue(out.matches("(?s).*Exposure deviation:\\s+−0\\.0042 EV \\(More exposure than target\\)\\n.*"), out);
            var solution = ExposureSolver.solve(Request.of(Step.THIRD, 10, 1 / 160.0, Double.NaN).solving(Parameter.ISO, 12.97), bootstrap.facade()::calculate).value().orElseThrow();
            assertTrue(out.contains(ExposurePresentation.signedEv(solution.deviation())), "the deviation of the shared solution");
            // The other settings, the other steps, and a limit.
            assertEquals(0, cli.execute("calc exposure shutter=1/250 iso=200 solve=aperture target=13 step=half"));
            assertTrue(text(bytes).matches("(?s).*Aperture:\\s+f/8\\n.*"));
            assertEquals(0, cli.execute("calc exposure aperture=5.6 iso=100 solve=shutter target=12.965784 step=full"));
            assertTrue(text(bytes).matches("(?s).*Exposure time:\\s+1/250 s\\n.*"));
            assertEquals(0, cli.execute("calc exposure shutter=3600 iso=400 solve=aperture target=12.965784"));
            out = text(bytes);
            assertTrue(out.matches("(?s).*Aperture:\\s+f/128\\n.*") && out.contains("Parameter limit reached"), out);
            // The limit keeps the direction words: far more exposure than the target, not "−12.87" alone.
            assertTrue(out.matches("(?s).*Exposure deviation:\\s+−12\\.87 EV \\(Parameter limit reached · More exposure than target\\)\\n.*"), out);
            // A deviation too small for two decimals keeps two significant digits in plain notation.
            assertTrue(out.contains("+0.00000028 EV") && !out.contains("E-"), out);
            // Exact refuses what it cannot reach, as the GUI does, in the command line's own words.
            assertEquals(2, cli.execute("calc exposure shutter=3600 iso=400 solve=aperture target=12.965784 step=exact"));
            out = text(bytes);
            assertEquals("The value of Aperture needed for target EV100 12.965784 is outside the supported range. Change target= or another setting.", out.strip());
            // In Exact the precise solution is applied: no "applied standard value" line under it.
            assertEquals(0, cli.execute("calc exposure shutter=1/250 iso=200 solve=aperture target=12.6 step=exact"));
            out = text(bytes);
            assertTrue(out.contains("Exact solution:") && out.contains("f/7.04748") && !out.contains("Applied standard value"), out);
            assertTrue(out.contains("Exact (no standard-stop rounding)"), out);
            // An absurd target whose solution is a subnormal number: still the end stop and a report, not an exception.
            assertEquals(0, cli.execute("calc exposure aperture=1 shutter=1 solve=iso target=1031"));
            out = text(bytes);
            assertTrue(out.matches("(?s).*ISO:\\s+3\\n.*") && out.contains("Parameter limit reached"), out);
        });
    }

    @Test void unusableArgumentsAreRefusedWithExitStatusTwo() throws Exception {
        session((bootstrap, cli, bytes) -> {
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 iso=100 step=quarter")); assertTrue(text(bytes).contains("Unknown exposure step: quarter"));
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 solve=iso")); assertTrue(text(bytes).contains("target=<EV100>"));
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 target=12")); text(bytes);
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 iso=100 solve=iso target=12")); assertTrue(text(bytes).contains("leave the solved setting out"));
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 solve=speed target=12")); text(bytes);
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 solve=iso target=abc")); text(bytes);
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 solve=iso target=NaN")); text(bytes);
            assertEquals(2, cli.execute("calc exposure aperture=500 shutter=1/125 iso=100 step=third")); assertTrue(text(bytes).contains("Enter a value from 0.5 to 128."));
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/0 iso=100 step=third")); text(bytes);
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=-1 iso=100 step=third")); text(bytes);
            assertEquals(2, cli.execute("calc exposure aperture=8 iso=100 step=third")); assertTrue(text(bytes).contains("Exposure time"));
            assertEquals(2, cli.execute("calc exposure aperture=8 shutter=1/125 iso=100 focalLength=50 step=third")); assertTrue(text(bytes).contains("focalLength"));
            assertEquals(0, cli.execute("calc help"));
            String help = text(bytes);
            assertTrue(help.contains("calc exposure aperture=<f-number> shutter=<s> iso=<ISO> [step=third|half|full|exact] [solve=aperture|shutter|iso target=<EV100>]"), help);
            assertTrue(help.contains("Without these arguments the exact result is printed as before."));
        });
    }

    @Test void theReportIsLocalized() throws Exception {
        session((bootstrap, cli, bytes) -> {
            bootstrap.localization().useLanguage("zh-CN");
            assertEquals(0, cli.execute("calc exposure aperture=10 shutter=1/160 solve=iso target=12.97 step=third"));
            String out = text(bytes);
            assertTrue(out.startsWith("PHOTOPILOT — 曝光值计算器"), out);
            for (String word : new String[] { "曝光档位", "1/3 EV", "自动求解", "目标 EV100", "实际 EV100", "曝光偏差", "数学精确解", "实际应用档位", "最接近的标准档位", "近似等效曝光" })
                assertTrue(out.contains(word), word + " in " + out);
            assertEquals(0, cli.execute("calc exposure aperture=10 shutter=1/160 iso=200 step=exact")); assertTrue(text(bytes).contains("精确计算"));
            assertFalse(LocalizationService.english().text("calc.exposure.step.EXACT").isBlank());
        });
    }
}
