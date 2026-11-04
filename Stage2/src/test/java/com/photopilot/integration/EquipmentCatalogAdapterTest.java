package com.photopilot.integration;

import com.photopilot.gearimport.ImportFixtures;
import com.photopilot.model.EquipmentType;
import com.photopilot.model.SpecificationEvidence;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Offline: loopback stand-ins for the Wikipedia API and Lensfun data files; no live provider request. */
class EquipmentCatalogAdapterTest {
    private ImportFixtures.FakeCatalog catalog;
    private HttpClient http;
    private EquipmentCatalogAdapter adapter;

    @BeforeEach void start() throws Exception {
        catalog = new ImportFixtures.FakeCatalog().standard();
        http = HttpClient.newHttpClient();
        adapter = new EquipmentCatalogAdapter(URI.create(catalog.wiki()), URI.create(catalog.lensfun()), http, Duration.ofSeconds(5),
            Clock.fixed(Instant.parse("2026-11-03T15:00:00Z"), ZoneOffset.UTC));
    }
    @AfterEach void stop() { catalog.close(); http.close(); }

    private static SpecificationEvidence from(List<SpecificationEvidence> records, String source) {
        return records.stream().filter(record -> record.source().equals(source)).findFirst().orElseThrow();
    }

    @Test void cameraSpecificationsAreParsedFromBothSourcesWithProvenance() {
        var result = adapter.search(EquipmentType.CAMERA, "Sony", "α7 IV", "ILCE-7M4");
        assertTrue(result.isSuccess(), result.errors().toString());
        var records = result.value().orElseThrow();
        assertEquals(2, records.size());
        var wiki = from(records, "Wikipedia");
        assertEquals("Sony α7 IV", wiki.model());
        assertEquals(Map.of("mount", "Sony E-mount", "sensorWidthMm", "35.9", "sensorHeightMm", "23.9", "resolutionMP", "33", "burstFps", "10",
            "batteryTypes", "NP-FZ100", "storageFormats", "CFexpress Type A,SD,SDHC,SDXC", "weightGrams", "658"), wiki.values());
        assertTrue(wiki.url().toString().endsWith("/w/index.php?oldid=123456"), "evidence links to the exact revision");
        assertEquals(Instant.parse("2026-11-03T15:00:00Z"), wiki.retrievedAt());
        var lensfun = from(records, "Lensfun");
        assertEquals("Alpha 7 IV", lensfun.model());
        assertEquals(Map.of("mount", "Sony E"), lensfun.values());
        assertTrue(lensfun.url().toString().endsWith("/db/mil-sony.xml"));
        assertTrue(catalog.agents.stream().allMatch(agent -> agent.startsWith("PhotoPilot/")), "requests identify the application");
        assertTrue(catalog.requests.stream().noneMatch(request -> request.contains("key")), "no credentials are sent to catalogues");
    }

    @Test void lensVariantsAreNotConfusedAndInfoboxValuesAreParsed() {
        var records = adapter.search(EquipmentType.LENS, "Sony", "FE 24-70mm F2.8 GM", "").value().orElseThrow();
        var wiki = from(records, "Wikipedia");
        assertEquals("Sony FE 24-70mm F2.8 GM", wiki.model(), "the GM II article listed first must not match");
        assertEquals(Map.of("mount", "Sony E-mount", "minFocalMm", "24", "maxFocalMm", "70", "maxAperture", "2.8", "filterDiameterMm", "82",
            "stabilization", "false", "autofocus", "true", "weightGrams", "886"), wiki.values());
        assertEquals("FE 24-70mm f/2.8 GM", from(records, "Lensfun").model());
        var variable = EquipmentCatalogAdapter.lensValues(EquipmentCatalogAdapter.infobox("""
            {{Infobox photographic lens
            |flength = 18–135 mm
            |aperture = {{f/}}3.5–5.6 to {{f/}}22
            |filter = 67&nbsp;mm<ref>maker</ref>
            }}""", EquipmentType.LENS));
        assertEquals("18", variable.get("minFocalMm")); assertEquals("135", variable.get("maxFocalMm"));
        assertEquals("3.5", variable.get("maxAperture")); assertEquals("5.6", variable.get("maxApertureAtMaxFocal"));
        assertEquals("67", variable.get("filterDiameterMm"));
    }

    @Test void unknownProductsAndPartialOutagesStayExplicit() {
        var none = adapter.search(EquipmentType.CAMERA, "Sony", "α99 XII", "");
        assertTrue(none.isSuccess());
        assertTrue(none.value().orElseThrow().isEmpty(), "no match means no evidence, never a guess");
        catalog.wikiStatus = 500;
        var partial = adapter.search(EquipmentType.CAMERA, "Sony", "α7 IV", "ILCE-7M4");
        assertTrue(partial.isSuccess());
        assertEquals(List.of("Wikipedia"), partial.warnings());
        assertEquals(List.of("Lensfun"), partial.value().orElseThrow().stream().map(SpecificationEvidence::source).toList());
        var uncached = new EquipmentCatalogAdapter(URI.create(catalog.wiki()), URI.create(catalog.lensfun()), http, Duration.ofSeconds(5), Clock.systemUTC());
        catalog.lensfunStatus = 503;
        var down = uncached.search(EquipmentType.LENS, "Sony", "FE 24-70mm F2.8 GM", "");
        assertFalse(down.isSuccess());
        assertTrue(down.hasError("import.error.catalogHttp"));
        assertTrue(adapter.search(EquipmentType.FILTER, "Hoya", "ND1000", "").value().orElseThrow().isEmpty());
        assertTrue(adapter.search(EquipmentType.CAMERA, "Sony", " ", "").hasError("import.error.identityRequired"));
    }
}
