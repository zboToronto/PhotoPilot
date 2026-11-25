package com.photopilot.environment;

import com.photopilot.application.ProfileManager;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual CLI main class in fresh JVMs; only HTTP endpoints and the local profile are fixtures. */
class HybridLocationCliTest {
    @TempDir Path root;
    FakeGemini gemini;
    FakeEnvironmentProviders providers;
    Map<String,String> env;
    UUID session;
    String assess;
    @BeforeEach void start() throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        env = providers.environment(gemini.environment(root));
        try (var profiles = new ProfileManager(env)) {
            session = profiles.activeContext().facade().sessions().create("Location test",EnvironmentFixtures.day()).value().orElseThrow().id();
        }
        assess = "env assess " + session + " from=13:00 until=16:00 activity=landscape ";
    }
    @AfterEach void stop() { providers.close(); gemini.close(); }
    String process(int expected, String... commands) throws Exception {
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Dfile.encoding=UTF-8",
            "-cp",System.getProperty("java.class.path"),PhotoPilotCLI.class.getName(),"--lang=en"));
        args.addAll(List.of(commands));
        var log = Files.createTempFile(root, "cli-", ".log");
        var builder = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(env);
        var process = builder.start();
        try {
            process.getOutputStream().close(); assertTrue(process.waitFor(25,TimeUnit.SECONDS));
            String output = Files.readString(log); assertEquals(expected,process.exitValue(),output);
            assertFalse(output.contains(FakeGemini.KEY)); return output;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void databaseSelectionAndDirectCoordinateScriptsStillWork() throws Exception {
        String primary = process(0, "env help", "env place Toronto", assess+"place=1");
        assertTrue(primary.contains("3 matching places") && primary.contains("Open-Meteo geographic database") && primary.contains("acceptApprox=yes"),primary);
        assertTrue(primary.contains("Sunset:")); assertEquals(1,gemini.requests.size(),"only environmental interpretation");
        long lookups = providers.count("/v1/search");
        assertTrue(process(0,assess+"location=Site latitude=43.6 longitude=-79.4").contains("User-provided coordinates"));
        assertEquals(lookups,providers.count("/v1/search"));
        assertTrue(process(2, assess+"location=Site latitude=95 longitude=0").contains("Latitude must be between"));
        assertEquals(lookups,providers.count("/v1/search"));
    }
    @Test void estimatesRequireAcceptanceAndPersistOnlyOnExplicitRecord() throws Exception {
        var reply = HybridLocationTest.resolved(HybridLocationTest.PARK);
        gemini.reply(reply);
        var declined = process(2, assess+"location='Harbour Park in Westport'");
        assertTrue(declined.contains("geographically unverified") && declined.contains("not accepted"),declined);
        assertEquals(0,providers.count("/v1/forecast")); assertEquals(1,gemini.requests.size());
        gemini.reply(reply); gemini.status(503,"");
        String accepted = process(0,assess+"location='Harbour Park in Westport' acceptApprox=yes","env record "+session);
        assertTrue(accepted.contains("43.63") && accepted.contains("AI-estimated shooting location") && accepted.contains("Sunset:"),accepted);
        assertFalse(accepted.contains("approximate gazetteer point"));
        assertEquals(3,gemini.requests.size());
        assertFalse(gemini.requests.getLast().contains("43.62753871"), "environment interpretation excludes exact coordinates");
        try (var profiles = new ProfileManager(env)) {
            var check = profiles.activeContext().facade().sessions().open(session).value().orElseThrow().environmentCheck().orElseThrow();
            assertEquals("GEMINI_ESTIMATED",check.lookupSource().orElseThrow().name());
            assertEquals(43.62753871,check.place().latitude().orElseThrow());
        }
    }
    @Test void failureAndAmbiguityOfferRecoveryAndNeverStartAnUnconfirmedAssessment() throws Exception {
        gemini.reply("{\"status\":\"UNRESOLVED\",\"candidates\":[]}");
        assertTrue(process(2,assess+"location=Unlisted").contains("enter latitude and longitude manually"));
        String ambiguous = "{\"status\":\"AMBIGUOUS\",\"candidates\":[" + HybridLocationTest.PARK + ","
            + HybridLocationTest.PARK.replace("Westport","Eastport") + "]}";
        gemini.reply(ambiguous);
        assertTrue(process(2, assess+"location='Harbour Park' acceptApprox=yes").contains("pick=<n>"));
        assertEquals(0,providers.count("/v1/forecast"));
        gemini.reply(ambiguous); gemini.status(503,"");
        String selected = process(0,assess+"location='Harbour Park' pick=2 acceptApprox=yes");
        assertTrue(selected.contains("Eastport") && selected.contains("Sunset:"),selected);
    }
    @Test void timeZoneConflictUsesProviderWallTimeAndReportsBothZones() throws Exception {
        gemini.reply(HybridLocationTest.resolved(HybridLocationTest.PARK.replace("America/Toronto","America/Vancouver")));
        gemini.status(503,"");
        String result = process(0, assess+"location='Harbour Park in Westport' acceptApprox=yes");
        assertTrue(result.contains("AI-suggested time zone America/Vancouver differs from the weather provider time zone America/Toronto"),result);
        assertTrue(result.contains("13:00") && result.contains("16:00"),result);
        // An independent user hint matching the provider cannot hide the disagreement with the AI candidate.
        gemini.reply(HybridLocationTest.resolved(HybridLocationTest.PARK.replace("America/Toronto","America/Vancouver")));
        gemini.status(503,"");
        String withUserZone = process(0, assess+"location='Harbour Park in Westport' acceptApprox=yes zone=America/Toronto");
        assertTrue(withUserZone.contains("AI-suggested time zone America/Vancouver differs from the weather provider time zone America/Toronto"),withUserZone);
    }
    @Test void missingKeyAndRateLimitKeepManualRecoveryAvailable() throws Exception {
        gemini.status(429,"");
        String limited = process(2, assess+"location=Unknown");
        assertTrue(limited.toLowerCase(Locale.ROOT).contains("rate limit"),limited);
        assertEquals(1,gemini.requests.size(),"no retry on the resolution port");
        env.put("LLM_API_KEY","");
        String missing = process(2, assess+"location=Unknown");
        assertTrue(missing.contains("enter latitude and longitude manually"),missing);
        assertEquals(1,gemini.requests.size()); assertEquals(0,providers.count("/v1/forecast"));
    }
    @Test void aFailedDestinationChangeCannotReplayThePreviousAssessment() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var profiles = new ProfileManager(env)) {
            var cli = new PhotoPilotCLI(profiles, new Scanner(""), new PrintStream(out,true,StandardCharsets.UTF_8));
            String command = assess+"location='Harbour Park in Westport' acceptApprox=yes";
            gemini.reply(HybridLocationTest.resolved(HybridLocationTest.PARK)); gemini.status(503,"");
            assertEquals(0,cli.execute(command),out::toString);
            long searches = providers.count("/v1/search");
            gemini.status(503,"");
            assertEquals(0,cli.execute(command),out::toString);
            assertEquals(searches,providers.count("/v1/search"),"same confirmed input reuses the accepted point");
            gemini.reply("{\"status\":\"UNRESOLVED\",\"candidates\":[]}");
            assertEquals(2,cli.execute("env again location=Unlisted"),out::toString);
            assertEquals(2,cli.execute("env again"),"the previous place is no longer the active draft");
            assertTrue(profiles.activeContext().facade().planning().environmentFor(session).isEmpty());
        }
    }
    @Test void interactiveCommandLoopRejectsRetriesAndAcceptsManualEntry() throws Exception {
        var out = new ByteArrayOutputStream();
        gemini.reply(HybridLocationTest.resolved(HybridLocationTest.PARK));
        gemini.reply("{\"status\":\"UNRESOLVED\",\"candidates\":[]}"); gemini.status(503,"");
        var script = String.join("\n",assess+"location='Harbour Park in Westport'","n","1","Unlisted","2","43.6","-79.4","America/Toronto","exit");
        try (var profiles = new ProfileManager(env)) {
            var cli = new PhotoPilotCLI(profiles,new Scanner(script),new PrintStream(out,true,StandardCharsets.UTF_8));
            cli.run(new String[0]); assertEquals(0,cli.exitCode(),out::toString);
        }
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Use the unverified") && text.contains("User-provided coordinates") && text.contains("Sunset:"),text);
        assertEquals(1,providers.count("/v1/forecast"));
    }
}
