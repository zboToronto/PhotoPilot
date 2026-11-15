package com.photopilot.service;

import com.photopilot.model.CameraModel;
import com.photopilot.model.CameraModel.Level;
import com.photopilot.model.CameraModel.Series;
import com.photopilot.model.CounterKind;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.photopilot.model.CameraModel.Brand.NIKON;
import static com.photopilot.model.CameraModel.Brand.SONY;
import static org.junit.jupiter.api.Assertions.*;

class CameraModelCatalogTest {
    private final CameraModelCatalog catalog = CameraModelCatalog.bundled();
    private CameraModel model(CameraModel.Brand brand, String id) { return catalog.resolve(brand, id).orElseThrow(() -> new AssertionError("missing " + id)); }

    @Test void catalogComesFromTheOfficialTestedCameraList() {
        var source = catalog.source();
        assertEquals("https://exiftool.org/models.html", source.url());
        assertEquals("ExifTool-tested Camera Models", source.title());
        assertEquals("13.62", source.exiftoolVersion());
        assertEquals("2026-11-09", source.retrieved());
        assertEquals(7580, source.sourceModelCount());
        assertEquals(796, source.listed().get(SONY));
        assertEquals(307, source.listed().get(NIKON));
        for (var brand : CameraModel.Brand.values())
            assertEquals(source.listed().get(brand), catalog.models(brand).size() + source.excluded().get(brand), brand + ": every listed entry is a camera or excluded");
        System.out.printf("Camera catalog: Sony %d cameras (%d excluded), Nikon %d cameras (%d excluded)%n",
            catalog.models(SONY).size(), source.excluded().get(SONY), catalog.models(NIKON).size(), source.excluded().get(NIKON));
        for (var brand : CameraModel.Brand.values()) {
            Map<Level, Long> levels = new EnumMap<>(Level.class);
            catalog.models(brand).forEach(model -> levels.merge(model.support().level(), 1L, Long::sum));
            System.out.println("  " + brand + " support levels: " + levels);
        }
    }

    @Test void onlyPhotographicCamerasAreKept() {
        for (String id : List.of("C5303", "D6603", "XQ-AT51", "Xperia M2", "CLIE PEG-TH55", "DCR-HC30", "HDR-AS10", "HDR-CX12", "FDR-X3000", "DSR-PD150",
            "HVR-Z5E", "PMW-EX1R", "PSP-300", "UPX", "MODEL-NAME", "DVgate Still", "NEX-VG20E", "MHS-CM5", "DPP-FP60", "HANDYCAM", "GV-D1000"))
            assertTrue(catalog.resolve(SONY, id).isEmpty(), id + " is not a photographic camera");
        for (String id : List.of("CaptureNX-D", "NXStudio1", "Coolscan V ED", "Coolscan 9000 ED"))
            assertTrue(catalog.resolve(NIKON, id).isEmpty(), id + " is software or a scanner");
        assertTrue(catalog.models(SONY).stream().noneMatch(model -> model.brand() != SONY));
        assertTrue(catalog.search(NIKON, Optional.empty(), "").stream().allMatch(model -> model.brand() == NIKON), "options follow the brand");
    }

    @Test void modelsAreGroupedBySeriesAndFamily() {
        Map<String, Series> sony = Map.ofEntries(Map.entry("ILCE-1M2", Series.SONY_ALPHA_1), Map.entry("ILCE-9M3", Series.SONY_ALPHA_9),
            Map.entry("ILCE-7M4", Series.SONY_ALPHA_7), Map.entry("ILCE-7RM5", Series.SONY_ALPHA_7R), Map.entry("ILCE-7RM4", Series.SONY_ALPHA_7R),
            Map.entry("ILCE-7SM3", Series.SONY_ALPHA_7S), Map.entry("ILCE-7CR", Series.SONY_ALPHA_7C), Map.entry("ILCE-6700", Series.SONY_ALPHA_6000),
            Map.entry("ILCE-5100", Series.SONY_ALPHA_5000_3000), Map.entry("ILCE-3000", Series.SONY_ALPHA_5000_3000), Map.entry("NEX-5N", Series.SONY_NEX),
            Map.entry("ILCE-QX1", Series.SONY_QX), Map.entry("DSC-QX100", Series.SONY_QX), Map.entry("SLT-A99V", Series.SONY_A_MOUNT_SLT_ILCA),
            Map.entry("ILCA-99M2", Series.SONY_A_MOUNT_SLT_ILCA), Map.entry("DSLR-A900", Series.SONY_A_MOUNT_DSLR), Map.entry("ILME-FX3", Series.SONY_CINEMA_LINE),
            Map.entry("ZV-E10M2", Series.SONY_ZV), Map.entry("ZV-1", Series.SONY_ZV), Map.entry("DSC-RX100M7", Series.SONY_RX), Map.entry("DSC-RX1RM3", Series.SONY_RX),
            Map.entry("DSC-W800", Series.SONY_CYBER_SHOT), Map.entry("MVC-FD7", Series.SONY_MAVICA), Map.entry("NSC-GC1", Series.SONY_OTHER));
        sony.forEach((id, series) -> assertEquals(series, model(SONY, id).series(), id));
        Map<String, Series> nikon = Map.ofEntries(Map.entry("Z 9", Series.NIKON_Z_FULL_FRAME), Map.entry("Z6_3", Series.NIKON_Z_FULL_FRAME),
            Map.entry("Z f", Series.NIKON_Z_FULL_FRAME), Map.entry("ZR", Series.NIKON_Z_FULL_FRAME), Map.entry("Z 50", Series.NIKON_Z_DX), Map.entry("Z50_2", Series.NIKON_Z_DX),
            Map.entry("Z fc", Series.NIKON_Z_DX), Map.entry("D6", Series.NIKON_D_SINGLE_DIGIT), Map.entry("D2Xs", Series.NIKON_D_SINGLE_DIGIT),
            Map.entry("D90", Series.NIKON_D_TWO_DIGIT), Map.entry("D850", Series.NIKON_D_THREE_DIGIT), Map.entry("D800E", Series.NIKON_D_THREE_DIGIT),
            Map.entry("D7500", Series.NIKON_D_FOUR_DIGIT), Map.entry("Df", Series.NIKON_DF), Map.entry("1 J5", Series.NIKON_1),
            Map.entry("Coolpix P1000", Series.NIKON_COOLPIX), Map.entry("KeyMission 360", Series.NIKON_KEYMISSION), Map.entry("SQ", Series.NIKON_OTHER));
        nikon.forEach((id, series) -> assertEquals(series, model(NIKON, id).series(), id));
        assertEquals(CameraModel.Family.SONY_ALPHA_E_MOUNT, model(SONY, "ILCE-7RM5").series().family());
        assertEquals(CameraModel.Family.NIKON_DSLR, model(NIKON, "D850").series().family());
        var order = catalog.models(SONY).stream().map(CameraModel::series).toList();
        for (int i = 1; i < order.size(); i++) assertTrue(order.get(i - 1).compareTo(order.get(i)) <= 0, "models are ordered by series");
    }

    @Test void productNamesAndCommonNamingVariantsResolveToTheRightModel() {
        assertEquals("α7R V", model(SONY, "ILCE-7RM5").displayName());
        assertEquals("α7R V (ILCE-7RM5)", model(SONY, "ILCE-7RM5").label());
        Map<String, String> sonyNames = Map.of("ILCE-7M4", "α7 IV", "ILCE-1M2", "α1 II", "ILCE-7CM2", "α7C II", "ILCE-7CR", "α7CR", "DSC-RX100M5A", "RX100 VA",
            "DSC-RX100M7", "RX100 VII", "ZV-E10M2", "ZV-E10 II", "ILCA-99M2", "α99 II", "DSLR-A900", "α900");
        sonyNames.forEach((id, name) -> assertEquals(name, model(SONY, id).displayName(), id));
        assertEquals("Cyber-shot W800", model(SONY, "DSC-W800").displayName());
        assertEquals("FX3", model(SONY, "ILME-FX3").displayName());
        Map<String, String> nikonNames = Map.of("Z 6_2", "Z 6II", "Z 7_2", "Z 7II", "Z6_3", "Z6III", "Z50_2", "Z50II", "Coolpix P1000", "COOLPIX P1000", "1 J5", "Nikon 1 J5", "D850", "D850");
        nikonNames.forEach((id, name) -> assertEquals(name, model(NIKON, id).displayName(), id));

        for (String name : List.of("Sony Alpha 7 IV", "ILCE-7M4", "A7 IV", "a7iv", "α7 IV", "A7M4", "Alpha 7 IV", "sony a7 iv"))
            assertEquals("ILCE-7M4", model(SONY, name).id(), name);
        for (String name : List.of("Sony Alpha 7R V", "ILCE-7RM5", "A7R V", "A7RM5", "A7R5", "α7R V"))
            assertEquals("ILCE-7RM5", model(SONY, name).id(), name);
        for (String name : List.of("Nikon Z6 II", "Z 6II", "Z 6_2", "Z6_2", "NIKON Z 6_2", "Z6II"))
            assertEquals("Z 6_2", model(NIKON, name).id(), name);
        for (String name : List.of("Z6III", "Z6_3", "NIKON Z6_3")) assertEquals("Z6_3", model(NIKON, name).id(), name);
        assertEquals("D850", model(NIKON, "Nikon D850").id());
        assertEquals("Coolpix P1000", model(NIKON, "COOLPIX P1000").id());

        // Generations and variants stay distinct.
        assertNotEquals(model(SONY, "ILCE-7M4"), model(SONY, "ILCE-7M3"));
        assertNotEquals(model(SONY, "ILCE-7RM4"), model(SONY, "ILCE-7RM5"));
        assertNotEquals(model(SONY, "ILCE-6400"), model(SONY, "ILCE-6400A"));
        assertNotEquals(model(SONY, "SLT-A55"), model(SONY, "SLT-A55V"));
        assertNotEquals(model(SONY, "DSC-RX100M5"), model(SONY, "DSC-RX100M5A"));
        assertEquals(3, Set.of(model(NIKON, "Z 6"), model(NIKON, "Z 6_2"), model(NIKON, "Z6_3")).size());
        assertTrue(catalog.resolve(SONY, "A7 VI").isEmpty(), "no invented models");
    }

    @Test void exifMakeAndModelIdentifyTheCamera() {
        assertEquals("ILCE-7RM5", catalog.detect("SONY", "ILCE-7RM5").orElseThrow().id());
        assertEquals("Z 6_2", catalog.detect("NIKON CORPORATION", "NIKON Z 6_2").orElseThrow().id());
        assertEquals("D850", catalog.detect("NIKON CORPORATION", "NIKON D850").orElseThrow().id());
        assertEquals("Coolpix P1000", catalog.detect("NIKON", "COOLPIX P1000").orElseThrow().id());
        assertEquals("1 J5", catalog.detect("NIKON", "NIKON 1 J5").orElseThrow().id());
        assertTrue(catalog.detect("Canon", "Canon EOS R5").isEmpty());
        assertTrue(catalog.detect("SONY", "ILCE-99").isEmpty());
        assertTrue(catalog.detect("SONY", null).isEmpty());
    }

    @Test void searchCoversBrandFamilySeriesNameAndId() {
        var all = catalog.search(SONY, Optional.empty(), "");
        assertEquals(catalog.models(SONY), all, "a blank query lists every model");
        assertTrue(ids(catalog.search(SONY, Optional.empty(), "alpha 7 iv")).contains("ILCE-7M4"));
        assertTrue(ids(catalog.search(SONY, Optional.empty(), "7rv")).contains("ILCE-7RM5"));
        assertTrue(ids(catalog.search(SONY, Optional.empty(), "ILCE-7RM5")).contains("ILCE-7RM5"));
        assertTrue(catalog.search(SONY, Optional.of(Series.SONY_ALPHA_7R), "").stream().allMatch(model -> model.series() == Series.SONY_ALPHA_7R));
        assertEquals(List.of("ILCE-7R", "ILCE-7RM2", "ILCE-7RM3", "ILCE-7RM4", "ILCE-7RM5"), ids(catalog.search(SONY, Optional.of(Series.SONY_ALPHA_7R), "")), "as listed by ExifTool");
        assertTrue(ids(catalog.search(SONY, Optional.empty(), "cyber-shot")).containsAll(List.of("DSC-W800", "DSC-RX100M7")), "family keyword");
        assertTrue(ids(catalog.search(NIKON, Optional.empty(), "z6")).containsAll(List.of("Z 6", "Z 6_2", "Z6_3")));
        assertTrue(catalog.search(NIKON, Optional.empty(), "coolpix p").stream().allMatch(model -> model.id().startsWith("Coolpix P")));
        assertTrue(ids(catalog.search(NIKON, Optional.empty(), "dslr")).contains("D850"), "family keyword");
        assertTrue(catalog.search(SONY, Optional.empty(), "qwertyuiop").isEmpty());
        assertTrue(catalog.search(NIKON, Optional.empty(), "ILCE").isEmpty(), "searches stay within the brand");
    }
    private static List<String> ids(List<CameraModel> models) { return models.stream().map(CameraModel::id).toList(); }

    @Test void shutterCounterSupportIsKeptApartFromBeingCatalogued() {
        record Expect(String id, Level level, String field, CounterKind kind) { }
        var sony = List.of(new Expect("ILCE-7RM5", Level.VERIFIED, "Sony Tag9050c ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("ILCE-7M4", Level.DOCUMENTED, "Sony Tag9050c ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("ILME-FX3", Level.DOCUMENTED, "Sony Tag9050c ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("ILCE-7M3", Level.DOCUMENTED, "Sony Tag9050b ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("ZV-E10", Level.DOCUMENTED, "Sony Tag9050b ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("ILCE-6700", Level.DOCUMENTED, "Sony Tag9050d ShutterCount", CounterKind.MECHANICAL_ACTUATIONS),
            new Expect("ILCE-7M5", Level.DOCUMENTED, "Sony Tag9050d ShutterCount", CounterKind.MECHANICAL_ACTUATIONS),
            new Expect("ILCE-7R", Level.DOCUMENTED, "Sony Tag9050a ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("SLT-A99V", Level.DOCUMENTED, "Sony Tag9050a ShutterCount", CounterKind.TOTAL_EXPOSURES),
            new Expect("DSLR-A500", Level.DOCUMENTED, "Sony MoreInfo0201 ShutterCount", CounterKind.SHUTTER_COUNT),
            new Expect("NEX-5", Level.DOCUMENTED, "Sony MoreInfo0201 ShutterCount", CounterKind.SHUTTER_COUNT),
            new Expect("DSLR-A900", Level.DOCUMENTED, "Sony FocusInfo ShutterCount", CounterKind.SHUTTER_COUNT));
        for (var expect : sony) {
            var support = model(SONY, expect.id()).support();
            assertEquals(expect.level(), support.level(), expect.id());
            assertEquals(Optional.of(expect.field()), support.field(), expect.id());
            assertEquals(Optional.of(expect.kind()), support.kind(), expect.id());
        }
        assertEquals(Optional.of("shutter.condition.mechanicalOnly"), model(SONY, "ILCE-7M5").support().condition());
        assertEquals(Optional.of("shutter.condition.modulo"), model(SONY, "ILCE-7R").support().condition());
        for (String id : List.of("ZV-E1", "ZV-E10M2", "ZV-1", "DSC-RX100M7", "DSC-RX1RM2", "ILCE-9M3", "DSLR-A700", "DSLR-A100", "MVC-FD7"))
            assertEquals(Level.UNAVAILABLE, model(SONY, id).support().level(), id);
        for (String id : List.of("D850", "D7500", "D3", "D5000", "D2Hs", "D200", "Z 6_2", "Z 9", "Z f", "Z6_3", "Z50_2"))
            assertEquals(Level.DOCUMENTED, model(NIKON, id).support().level(), id);
        assertEquals(Optional.of(CounterKind.SHOTS_INCLUDING_ELECTRONIC), model(NIKON, "Z 9").support().kind());
        for (String id : List.of("D750", "D3500", "Df", "ZR", "Coolpix P1000", "1 J5", "KeyMission 360"))
            assertEquals(Level.UNVERIFIED, model(NIKON, id).support().level(), id);
        long verified = catalog.models(SONY).stream().filter(model -> model.support().level() == Level.VERIFIED).count()
            + catalog.models(NIKON).stream().filter(model -> model.support().level() == Level.VERIFIED).count();
        assertEquals(1, verified, "only the model checked with a real photograph is verified");
        assertTrue(catalog.models(NIKON).stream().noneMatch(model -> model.support().level() == Level.VERIFIED), "no Nikon accuracy is claimed");
    }

    @Test void everyNameUsedByTheCatalogIsTranslated() throws Exception {
        for (String bundle : List.of("messages", "messages_zh_CN")) {
            var entries = new Properties();
            try (var in = getClass().getResourceAsStream("/i18n/" + bundle + ".properties")) { entries.load(new InputStreamReader(in, StandardCharsets.UTF_8)); }
            Set<String> keys = new TreeSet<>();
            for (Series series : Series.values()) keys.add("shutter.series." + series.name());
            for (CameraModel.Family family : CameraModel.Family.values()) keys.add("shutter.family." + family.name());
            for (Level level : Level.values()) keys.add("shutter.level." + level.name());
            for (CounterKind kind : CounterKind.values()) { keys.add("shutter.kind." + kind.name()); keys.add("shutter.meaning." + kind.name()); }
            for (var status : com.photopilot.model.ShutterCountResult.Status.values()) keys.add("shutter.status." + status.name());
            for (var relation : com.photopilot.model.ShutterCountResult.Relation.values()) keys.add("shutter.relation." + relation.name());
            for (var brand : CameraModel.Brand.values()) for (CameraModel model : catalog.models(brand)) {
                keys.add(model.support().evidence()); model.support().condition().ifPresent(keys::add);
            }
            keys.add("shutter.evidence.verified");
            for (String key : keys) assertTrue(entries.containsKey(key), bundle + ": missing " + key);
        }
    }
}
