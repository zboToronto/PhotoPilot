package com.photopilot.environment;

import com.photopilot.application.EnvironmentForm;
import com.photopilot.common.Result;
import com.photopilot.integration.DeviceLocationProvider;
import com.photopilot.model.GeoLocation;
import com.photopilot.model.PlaceCandidate;
import com.photopilot.service.LocationService;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Manual, consented-device and looked-up locations; strict shared form parsing. Fakes only, no device or network. */
class LocationServiceTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC);
    static <T> T ok(Result<T> result) { assertTrue(result.isSuccess(), () -> result.errors().toString()); return result.value().orElseThrow(); }
    static LocationService manualOnly() { return new LocationService(Optional.empty(), Optional.empty(), CLOCK); }

    @Test void validManualLocationKeepsTheUsersLabelAndNeverClaimsDeviceAccess() {
        var place = ok(manualOnly().manual("  Billy Bishop Airport  ", 43.6275, -79.3961));
        assertEquals("Billy Bishop Airport", place.label());
        assertEquals(Optional.of(43.6275), place.latitude());
        assertFalse(place.fromDevice()); assertTrue(place.consentGrantedAt().isEmpty());
        assertTrue(ok(manualOnly().manual("Pole", -90, 180)).validate().isSuccess(), "range limits are inclusive");
    }

    @Test void invalidMissingAndMalformedCoordinatesAreRejected() {
        var service = manualOnly();
        assertTrue(service.manual("Airport", 90.5, -79).hasError("env.location.error.latitude"));
        assertTrue(service.manual("Airport", 43.6, -180.01).hasError("env.location.error.longitude"));
        assertTrue(service.manual("Airport", Double.NaN, -79).hasError("env.location.error.latitude"));
        assertTrue(service.manual(" ", 43.6, -79.4).hasError("env.location.error.label"));
        assertTrue(service.manual("x".repeat(201), 43.6, -79.4).hasError("env.location.error.labelLength"));
        var values = form("Toronto", "", "", "2026-11-07", "13:00", "19:00", "");
        assertTrue(EnvironmentForm.parse(values, service, Optional.empty()).hasError("env.location.error.coordinates"), "a vague name alone is never a location");
        values.put("latitude", "43,6"); values.put("longitude", "-79.4");
        assertTrue(EnvironmentForm.parse(values, service, Optional.empty()).hasError("env.location.error.number"));
        var everything = EnvironmentForm.parse(form("", "", "", "07/11/2026", "1pm", "25:00", "abc"), service, Optional.empty());
        assertTrue(everything.hasError("env.location.error.missing") && everything.hasError("env.error.date")
            && everything.hasError("env.error.time") && everything.hasError("env.error.lead"), "all problems reported at once");
        var parsed = ok(EnvironmentForm.parse(form("Airport", "43.6275", "-79.3961", "2026-11-07", "21:00", "02:00", "30"), service, Optional.empty()));
        assertTrue(parsed.crossesMidnight()); assertEquals(LocalDate.of(2026, 11, 8), parsed.endDate());
        assertEquals(Duration.ofMinutes(30), parsed.leadTime().orElseThrow());
        assertTrue(EnvironmentForm.parse(form("Airport", "43.6", "-79.4", "2026-11-07", "13:00", "13:00", ""), service, Optional.empty())
            .hasError("env.error.emptyAvailability"));
        assertTrue(EnvironmentForm.parse(form("Airport", "43.6", "-79.4", "2026-11-07", "13:00", "14:00", "400"), service, Optional.empty())
            .hasError("env.error.lead"));
    }

    @Test void deniedOrMissingConsentNeverCallsTheDeviceAndDenialIsRemembered() {
        var calls = new AtomicInteger();
        DeviceLocationProvider device = () -> { calls.incrementAndGet(); return Result.success(new GeoLocation("Here", 43.6, -79.4, false, false, Optional.empty())); };
        var service = new LocationService(Optional.of(device), Optional.empty(), CLOCK);
        assertTrue(service.current(false).hasError("env.location.error.consent"));
        service.recordDenial();
        assertTrue(service.consentDenied());
        assertTrue(service.current(true).hasError("env.location.error.denied"), "not requested again after a denial");
        assertEquals(0, calls.get(), "the device provider was never contacted");
        assertTrue(manualOnly().current(true).hasError("env.location.error.deviceUnsupported"));
        assertFalse(manualOnly().deviceSupported());
    }

    @Test void consentedDeviceLocationIsVerifiedAndStampedWithTheConsentTime() {
        DeviceLocationProvider device = () -> Result.success(new GeoLocation("", 43.6532, -79.3832, true, false, Optional.empty()));
        var place = ok(new LocationService(Optional.of(device), Optional.empty(), CLOCK).current(true));
        assertTrue(place.fromDevice());
        assertEquals(Optional.of(CLOCK.instant()), place.consentGrantedAt());
        assertEquals("Device location", place.label());
        assertTrue(place.validate().isSuccess());
        DeviceLocationProvider broken = () -> Result.success(GeoLocation.manual("no coordinates"));
        assertTrue(new LocationService(Optional.of(broken), Optional.empty(), CLOCK).current(true).hasError("env.location.error.deviceFailed"));
        DeviceLocationProvider failing = () -> { throw new IllegalStateException("platform error"); };
        assertTrue(new LocationService(Optional.of(failing), Optional.empty(), CLOCK).current(true).hasError("env.location.error.deviceFailed"));
    }

    @Test void placeLookupReturnsAllMatchesForTheUserToChoose() {
        var candidates = List.of(new PlaceCandidate("Toronto", "Ontario", "Canada", 43.70643, -79.39864, Optional.of(ZoneId.of("America/Toronto"))),
            new PlaceCandidate("Toronto", "Ohio", "United States", 40.46423, -80.60091, Optional.of(ZoneId.of("America/New_York"))));
        var service = new LocationService(Optional.empty(), Optional.of(name -> Result.success(candidates)), CLOCK);
        assertEquals(2, ok(service.search("Toronto")).size());
        assertTrue(service.search("").hasError("env.location.error.label"));
        assertTrue(manualOnly().search("Toronto").hasError("env.location.error.lookupUnavailable"));
    }

    static Map<String, String> form(String location, String lat, String lon, String date, String from, String until, String lead) {
        var values = new LinkedHashMap<String, String>();
        values.put("location", location); values.put("latitude", lat); values.put("longitude", lon); values.put("date", date);
        values.put("from", from); values.put("until", until); values.put("lead", lead);
        return values;
    }
}
