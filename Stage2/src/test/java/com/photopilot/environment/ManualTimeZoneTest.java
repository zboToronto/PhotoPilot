package com.photopilot.environment;

import com.photopilot.application.EnvironmentForm;
import com.photopilot.common.Result;
import com.photopilot.config.ApplicationBootstrap;
import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.*;
import com.photopilot.requirement.FakeGemini;
import com.photopilot.ui.cli.PhotoPilotCLI;
import com.photopilot.ui.privacy.ImagePrivacyBoundary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 C16: "env assess ... latitude=43.65 longitude=-79.38" was refused because the local time zone could not
 * be verified, while the same place chosen from the lookup worked (the lookup supplies its zone as a fallback). The
 * refusal is right when nothing confirms the zone; the user can now confirm a regional IANA zone explicitly. It is
 * checked against the coordinates' solar time, stated as user-entered, and a provider-verified zone still wins. The
 * computer's own zone or UTC is never substituted. Loopback providers and Gemini fake only.
 */
class ManualTimeZoneTest {
    @TempDir Path root;
    FakeGemini gemini; FakeEnvironmentProviders providers; ApplicationBootstrap app;

    @BeforeEach void start() throws Exception {
        gemini = new FakeGemini(); providers = new FakeEnvironmentProviders();
        app = new ApplicationBootstrap(providers.environment(gemini.environment(root)));
    }
    @AfterEach void stop() { if (app != null) app.close(); gemini.close(); providers.close(); }
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    UUID airshow() {
        var day = EnvironmentFixtures.day();
        var id = ok(app.facade().sessions().create("Toronto airshow", day)).id();
        ok(app.facade().planning().confirm(id, EnvironmentFixtures.airshowRequirement(day).asDraft()));
        return id;
    }
    /** Manual coordinates of the audit (no lookup), the fixture's date and availability, and an optional typed zone. */
    Map<String, String> manual(String zone) {
        var values = new LinkedHashMap<>(EnvironmentFixtures.form(EnvironmentFixtures.day()));
        values.put("location", "Toronto"); values.put("latitude", "43.65"); values.put("longitude", "-79.38"); values.put("zone", zone);
        return values;
    }
    /** The weather provider answers with an error, so it confirms no zone (the sun service still answers). */
    void weatherDown() { providers.forecast = q -> new FakeEnvironmentProviders.Reply(503, ""); }
    static boolean has(List<LocalizedMessage> messages, String key) { return messages.stream().anyMatch(m -> m.key().equals(key)); }

    @Test void manualCoordinatesWithoutAnyConfirmedZoneAreStillRefused() {
        var id = airshow(); weatherDown();
        var request = ok(EnvironmentForm.parse(manual(""), app.facade().planning().location(), Optional.empty()));
        assertTrue(request.zoneHint().isEmpty() && !request.zoneEntered());
        var refused = app.facade().assessEnvironment(id, request);
        assertTrue(refused.hasError("env.error.zone"), "the C16 refusal stays when nothing confirms the zone: " + refused.errors());
        assertTrue(app.localization().text("env.error.zone").contains("America/Toronto"), "the refusal names the manual alternative");
    }

    @Test void anEnteredRegionalZoneIsUsedStatedAndNeverReplacedByTheComputersZone() {
        var id = airshow(); weatherDown();
        gemini.reply(EnvironmentFixtures.airshowInterpretation(EnvironmentFixtures.day()));
        var request = ok(EnvironmentForm.parse(manual("America/Toronto"), app.facade().planning().location(), Optional.empty()));
        assertEquals(Optional.of(ZoneId.of("America/Toronto")), request.zoneHint()); assertTrue(request.zoneEntered());
        var a = ok(app.facade().assessEnvironment(id, request));
        assertEquals(ZoneId.of("America/Toronto"), a.availability().orElseThrow().start().getZone(), "local times in the entered zone");
        assertTrue(has(a.notices(), "env.notice.zoneFromUser"), a.notices().toString());
        assertFalse(has(a.notices(), "env.notice.zoneFromLookup"), "the source is the user, not a lookup");
        assertEquals("America/Toronto", EnvironmentForm.format(request).get("zone"), "env again keeps the entered zone");
    }

    @Test void aZoneThatCannotBeTheLocalTimeThereIsRefused() {
        var id = airshow(); weatherDown();
        var tokyo = ok(EnvironmentForm.parse(manual("Asia/Tokyo"), app.facade().planning().location(), Optional.empty()));
        var refused = app.facade().assessEnvironment(id, tokyo);
        assertTrue(refused.hasError("env.error.zoneImplausible"), refused.errors().toString());
        assertEquals(0, gemini.requests.size(), "nothing is interpreted for an implausible zone");
        // Plausible zones within the solar-time margin are accepted (China's single zone, Spain, across the date line),
        // in summer and winter; clearly wrong ones are not.
        for (var date : List.of(java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 12, 1))) {
            assertTrue(implausible("Asia/Shanghai", 76.0, date).isEmpty(), "Kashgar under China time " + date);
            assertTrue(implausible("Europe/Madrid", -8.7, date).isEmpty(), "Galicia under Spanish time " + date);
            assertTrue(implausible("Pacific/Kiritimati", -157.4, date).isEmpty(), "UTC+14 at 157 W " + date);
            assertTrue(implausible("America/Toronto", -79.38, date).isEmpty());
            // UTC+9 against solar UTC-5.3 is 14.3 h one way, 9.7 h the other way round the clock: about 10 h.
            assertEquals(Optional.of("10"), implausible("Asia/Tokyo", -79.38, date), "Tokyo for Toronto " + date);
            assertFalse(implausible("Europe/London", -79.38, date).isEmpty(), "London for Toronto " + date);
        }
    }
    static Optional<String> implausible(String zone, double longitude, java.time.LocalDate date) {
        return com.photopilot.service.EnvironmentService.implausibleZone(ZoneId.of(zone), longitude, date);
    }

    @Test void offsetsUtcAndUnknownIdsAreNotAcceptedAsALocationZone() {
        for (String zone : List.of("UTC", "GMT", "+05:00", "UTC-5", "Etc/GMT+5", "EST", "Toronto", "America/Nowhere", "SystemV/EST5")) {
            var parsed = EnvironmentForm.parse(manual(zone), app.facade().planning().location(), Optional.empty());
            assertTrue(parsed.hasError("env.error.zoneId"), zone + ": " + parsed.errors());
        }
    }

    @Test void aProviderVerifiedZoneTakesPrecedenceOverAnEnteredOne() {
        var id = airshow();   // the weather provider answers with America/Toronto
        gemini.reply(EnvironmentFixtures.airshowInterpretation(EnvironmentFixtures.day()));
        var request = ok(EnvironmentForm.parse(manual("America/Chicago"), app.facade().planning().location(), Optional.empty()));
        var a = ok(app.facade().assessEnvironment(id, request));
        assertEquals(FakeEnvironmentProviders.TORONTO, a.availability().orElseThrow().start().getZone());
        assertTrue(has(a.notices(), "env.notice.zoneMismatchEntered"), a.notices().toString());
    }

    @Test void theCliOffersTheSameEntryAndTheSameRefusal() {
        var id = airshow(); weatherDown();
        var bytes = new ByteArrayOutputStream();
        var cli = new PhotoPilotCLI(app.facade(), new ImagePrivacyBoundary(), new Scanner(""), new PrintStream(bytes, true, StandardCharsets.UTF_8), app.localization());
        var form = EnvironmentFixtures.form(EnvironmentFixtures.day());
        String base = "env assess " + id + " location=Toronto latitude=43.65 longitude=-79.38 date=" + form.get("date") + " from=" + form.get("from") + " until=" + form.get("until");
        assertEquals(2, cli.execute(base));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("enter the location's time zone (IANA, for example America/Toronto)"), bytes.toString(StandardCharsets.UTF_8));
        bytes.reset();
        assertEquals(2, cli.execute(base + " zone=UTC"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("is not a regional IANA time zone"), bytes.toString(StandardCharsets.UTF_8));
        bytes.reset();
        gemini.reply(EnvironmentFixtures.airshowInterpretation(EnvironmentFixtures.day()));
        assertEquals(0, cli.execute(base + " zone=America/Toronto"), bytes.toString(StandardCharsets.UTF_8));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Local times use the time zone you entered (America/Toronto)"), bytes.toString(StandardCharsets.UTF_8));
        bytes.reset();
        assertEquals(0, cli.execute("env help"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("[zone=<IANA zone, e.g. America/Toronto>]"));
    }
}
