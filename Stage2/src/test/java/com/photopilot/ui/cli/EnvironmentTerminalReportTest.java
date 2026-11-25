package com.photopilot.ui.cli;

import com.photopilot.application.EnvironmentPresentation;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.environment.EnvironmentFixtures;
import com.photopilot.environment.FakeEnvironmentProviders;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The F09 terminal report and its commands on the real services with loopback provider and Gemini fakes: the default
 * structured overview, every detail section, the AI-only retry, the opt-in interactive menu, narrow and Chinese output,
 * and the pre-flight date/time conflicts. Report commands never contact a provider or Gemini.
 */
class EnvironmentTerminalReportTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    ApplicationBootstrap app;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @BeforeEach void start() throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        app = new ApplicationBootstrap(providers.environment(gemini.environment(root)));
    }
    @AfterEach void stop() { app.localization().useLanguage("en"); app.close(); gemini.close(); providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    UUID airshow() {
        var day = EnvironmentFixtures.day();
        var id = ok(app.facade().sessions().create("Toronto airshow", day)).id();
        ok(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()));
        return id;
    }
    static String assess(UUID id) {
        var day = EnvironmentFixtures.day();
        return "env assess " + id + " location=\"" + EnvironmentFixtures.PLACE + "\" latitude=43.62750 longitude=-79.39610 from="
            + EnvironmentFixtures.clock(EnvironmentFixtures.from(day)) + " until=" + EnvironmentFixtures.clock(EnvironmentFixtures.until(day)) + " lead=30";
    }
    PhotoPilotCLI cli(String script) { return new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(script), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization()); }
    String take() { String text = bytes.toString(StandardCharsets.UTF_8); bytes.reset(); return text; }
    long providerRequests() { return providers.count("/v1/forecast") + providers.count("/json") + providers.count("/v1/search"); }

    @Test void defaultOutputIsTheStructuredOverviewAndEverySectionComesFromTheSharedAnalysis() {
        var id = airshow(); var cli = cli(""); var l = app.localization();
        assertEquals(2, cli.execute("env report")); assertTrue(take().contains("No environment assessment in this CLI session yet"));
        gemini.status(503, "");
        assertEquals(0, cli.execute(assess(id)));
        String overview = take();
        var assessment = app.facade().planning().environmentFor(id).orElseThrow();
        var analysis = app.facade().analyzeEnvironment(assessment);
        for (String heading : List.of("PHOTOPILOT — ENVIRONMENTAL ASSESSMENT", "SHOOTING CONTEXT", "WEATHER OVERVIEW", "NATURAL LIGHT", "SHOOTING WINDOW ANALYSIS",
                "SERVICE STATUS", "FINDINGS", "MORE DETAIL")) assertTrue(overview.contains(heading), heading);
        // Same analysis, same presentation as the GUI dashboard's cards (GUI/CLI parity).
        for (String metric : List.of("temperatureC", "cloudCoverPct", "windSpeedKmh", "visibilityM"))
            assertTrue(overview.contains(EnvironmentPresentation.windowValue(analysis, metric, l)), metric);
        assertTrue(overview.contains(EnvironmentPresentation.scope(analysis, l)));
        assertTrue(overview.contains("Golden-hour overlap:") && overview.contains(EnvironmentPresentation.minutes(analysis.goldenOverlap(), l)));
        assertTrue(overview.contains("[FAIL]") && overview.contains("Gemini interpretation: Unavailable"), overview);
        assertTrue(overview.contains("[--]") && overview.contains("Recommendation validation: Not performed"), "nothing to validate is never [OK]");
        assertTrue(overview.contains("No AI-validated shooting recommendation is currently available."));
        assertTrue(overview.contains("Overall: Environmental data available — AI analysis incomplete"));
        assertTrue(overview.contains("Sunset:") && overview.contains("(estimate)"));
        assertFalse(overview.contains("\u001b"), "no ANSI colour codes");
        long fetched = providerRequests(); int asked = gemini.requests.size();

        assertEquals(0, cli.execute("env report hourly"));
        String hourly = take();
        assertTrue(hourly.contains("HOURLY WEATHER FORECAST — LOCAL TIME") && hourly.contains("Time") && hourly.contains("* = forecast hour inside your availability"));
        assertEquals(analysis.window().samples().size(), hourly.lines().filter(line -> line.matches("\\* +\\d\\d:\\d\\d .*")).count(), "one marked row per window hour");
        assertEquals(analysis.hours().size() - analysis.window().samples().size(), hourly.lines().filter(line -> line.matches(" +\\d\\d:\\d\\d .*")).count(), "context rows kept");
        assertTrue(hourly.contains("12.0") && hourly.contains("Forecast values, not observations"));
        assertEquals(0, cli.execute("env report trend cloud"));
        String trend = take();
        assertTrue(trend.contains("TREND — Cloud cover (%)") && trend.contains("Axis 0 to 100 %") && trend.contains("#") && trend.contains("40%"), trend);
        assertEquals(0, cli.execute("env report trend")); assertTrue(take().contains("temperature | rain | precipitation | cloud | wind | gusts | visibility"));
        assertEquals(2, cli.execute("env report trend snow")); take();
        assertEquals(0, cli.execute("env report sun"));
        String sun = take();
        assertTrue(sun.contains("Sunrise") && sun.contains("Sunset") && sun.contains("sunrise-sunset.org") && sun.contains("Azimuth") && sun.contains("Legend: N night"), sun);
        assertTrue(sun.contains("Azimuth: degrees clockwise from true north") && sun.contains("buildings, trees, terrain"));
        assertEquals(0, cli.execute("env report windows"));
        String windows = take();
        assertTrue(windows.contains("Overlap with light phases") && windows.contains("Evening golden hour") && windows.contains("Validated AI recommendations")
            && windows.contains("No validated recommendation is currently available.") && windows.contains("Overlap is a time calculation, not a recommendation"), windows);
        assertEquals(0, cli.execute("env report ai"));
        String ai = take();
        assertTrue(ai.contains("Gemini interpretation unavailable") && ai.contains("Reason: Gemini service error (HTTP 503)") && ai.contains("No AI interpretation"), ai);
        assertEquals(0, cli.execute("env report status")); assertTrue(take().contains("[OK]   Weather retrieval: Completed"));
        assertEquals(0, cli.execute("env report technical"));
        String technical = take();
        for (String line : EnvironmentPresentation.report(assessment, l)) assertTrue(technical.contains(line), line);
        assertTrue(technical.contains("Coordinates:") && technical.contains("43.62750, -79.39610") && technical.contains("confirmed by the weather provider"));
        assertEquals(2, cli.execute("env report bogus")); take();
        assertEquals(fetched, providerRequests(), "report sections never contact a provider");
        assertEquals(asked, gemini.requests.size(), "or Gemini");

        gemini.status(503, "");
        assertEquals(2, cli.execute("env retry-ai"), "a retry whose interpretation fails again is not a success");
        String again = take();
        assertTrue(again.contains("did not succeed") && again.contains("Gemini interpretation unavailable") && again.contains("Weather and sunlight information remain available."), again);
        assertEquals(asked + 1, gemini.requests.size()); asked = gemini.requests.size();
        gemini.reply(EnvironmentFixtures.airshowInterpretation(EnvironmentFixtures.day()));
        assertEquals(0, cli.execute("env retry-ai"));
        String retried = take();
        assertTrue(retried.contains("AI analysis retried with the retained evidence") && retried.contains("Gemini interpretation succeeded")
            && retried.contains("Window 1: Afternoon flying display") && retried.contains("Overall: Environmental data and AI analysis available"), retried);
        assertEquals(fetched, providerRequests(), "the retry asked only Gemini");
        assertEquals(asked + 1, gemini.requests.size());
        assertEquals(2, cli.execute("env retry-ai")); assertTrue(take().contains("already succeeded"));
        assertEquals(0, cli.execute("env record " + id)); assertTrue(take().contains("added to the session"));
    }

    @Test void interactiveMenuOffersOnlyApplicableActionsAndScriptsAreNeverPrompted() {
        var id = airshow();
        gemini.status(503, "");
        gemini.reply(EnvironmentFixtures.airshowInterpretation(EnvironmentFixtures.day()));
        var cli = cli(String.join("\n", assess(id), "env menu", "6", "9", "5", "0", "exit"));
        cli.run(new String[0]);
        String text = take();
        assertEquals(0, cli.exitCode(), text);
        int first = text.indexOf("Environment assessment sections:");
        assertTrue(first > 0 && text.indexOf("6  Retry AI analysis", first) > 0, text);
        assertTrue(text.contains("AI analysis retried with the retained evidence"));
        assertTrue(text.contains("6  Retry AI analysis (not available: The AI interpretation of this assessment already succeeded.)"), "after success the retry is not offered");
        assertTrue(text.contains("Choose one of the listed numbers.") && text.contains("AI ANALYSIS"));
        // Without interactive input the menu lists the commands and asks nothing.
        var script = cli("");
        gemini.status(503, "");
        assertEquals(0, script.execute(assess(id)));
        take();
        assertEquals(0, script.execute("env menu"));
        String listed = take();
        assertTrue(listed.contains("env report [overview | hourly") && !listed.contains("Choose a number"), listed);
    }

    @Test void narrowAndChineseReportsKeepEveryValue() {
        var id = airshow();
        gemini.status(503, "");
        var cli = cli("");
        assertEquals(0, cli.execute(assess(id))); take();
        var assessment = app.facade().planning().environmentFor(id).orElseThrow();
        var analysis = app.facade().analyzeEnvironment(assessment);
        var narrow = new EnvironmentTerminalReport(analysis, app.localization(), 40);
        String hourly = String.join("\n", narrow.hourly());
        for (var hour : analysis.hours()) assertTrue(hourly.contains(EnvironmentPresentation.clock(hour.local(), analysis.date())), "no hour is dropped when narrow");
        for (String line : narrow.sun()) if (line.startsWith("Light ") || line.startsWith("Golden/blue") || line.startsWith("Available ")) assertTrue(TextTable.width(line) <= 40, line);
        for (String line : narrow.windows()) if (line.contains("|")) assertTrue(TextTable.width(line) <= 40, line);
        assertTrue(String.join("\n", new EnvironmentTerminalReport(analysis, app.localization(), 200).trend("temperatureC")).contains("12.0 °C"));
        app.localization().useLanguage("zh-CN");
        assertEquals(0, cli.execute("env report hourly"));
        String chinese = take();
        assertTrue(chinese.contains("逐小时天气预报") && chinese.contains("局部多云"), chinese);
        assertEquals(0, cli.execute("env report"));
        assertTrue(take().contains("拍摄背景"));
    }

    @Test void dateConflictsWithTheSessionAreShownBeforeAnyRequestIsMade() {
        var id = airshow();
        var day = EnvironmentFixtures.day();
        gemini.status(503, "");
        var cli = cli("");
        assertEquals(0, cli.execute(assess(id) + " date=" + day.plusDays(1)));
        String text = take();
        int conflict = text.indexOf("Inputs differ from the session"), running = text.indexOf("Retrieving weather and sun data");
        assertTrue(conflict >= 0 && conflict < running, text);
        assertTrue(text.contains("The confirmed requirement date is " + day + ", but this assessment uses " + day.plusDays(1)));
        assertTrue(text.contains("The session date is " + day + ", but this assessment uses " + day.plusDays(1)));
        assertFalse(text.contains("is outside your availability"), "a different date is not reported as a time outside the availability");
    }
}
