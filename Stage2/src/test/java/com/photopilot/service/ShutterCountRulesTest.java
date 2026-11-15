package com.photopilot.service;

import com.photopilot.i18n.LocalizedMessage;
import com.photopilot.model.CameraMetadata;
import com.photopilot.model.CameraModel;
import com.photopilot.model.CounterKind;
import com.photopilot.model.ShutterCountResult.CounterReading;
import com.photopilot.model.ShutterCountResult.Relation;
import com.photopilot.model.ShutterCountResult.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Counter selection with controlled metadata shaped like ExifTool's -G5 output. The Nikon cases are synthetic: they
 * check the documented rules, not the accuracy of any Nikon camera (no Nikon photograph was available).
 */
class ShutterCountRulesTest {
    private static final String IFD0 = "TIFF-IFD0", MN = "TIFF-IFD0-ExifIFD-MakerNotes", JPEG_MN = "JPEG-APP1-IFD0-ExifIFD-MakerNotes";
    private final CameraModelCatalog catalog = CameraModelCatalog.bundled();

    private static CameraMetadata.Field f(String directory, String tag, Object value) { return new CameraMetadata.Field(directory, tag, String.valueOf(value)); }
    private static CameraMetadata sony(String model, String type, CameraMetadata.Field... more) {
        List<CameraMetadata.Field> fields = new ArrayList<>(List.of(f(IFD0, "Make", "SONY"), f(IFD0, "Model", model), f("TIFF", "FileType", type)));
        fields.addAll(List.of(more));
        return new CameraMetadata(fields, Optional.empty(), List.of());
    }
    private static CameraMetadata nikon(String model, CameraMetadata.Field... more) {
        List<CameraMetadata.Field> fields = new ArrayList<>(List.of(f(IFD0, "Make", "NIKON CORPORATION"), f(IFD0, "Model", model), f("TIFF", "FileType", "NEF"),
            f(MN, "MakerNoteVersion", "0211")));
        fields.addAll(List.of(more));
        return new CameraMetadata(fields, Optional.empty(), List.of());
    }
    private ShutterCountRules.Interpretation interpret(CameraMetadata metadata) {
        return ShutterCountRules.interpret(metadata, catalog.detect(metadata.make().orElse(""), metadata.model().orElse(null)));
    }
    private static List<String> keys(List<LocalizedMessage> notes) { return notes.stream().map(LocalizedMessage::key).toList(); }
    private static CounterReading other(ShutterCountRules.Interpretation result, String tag) {
        return result.others().stream().filter(reading -> reading.tag().equals(tag)).findFirst().orElseThrow();
    }

    @Test void sonyTag9050cShutterCountIsSelectedAndShutterCount2CrossChecksIt() {
        var result = interpret(sony("ILCE-7RM5", "ARW", f(MN, "SonyModelID", 390), f(MN + "-Tag9050c", "ShutterCount", 54147),
            f(MN + "-Tag9050c", "ShutterCount2", 54147), f(MN + "-Tag9050c", "Shutter", "2726 5152 6482")));
        assertEquals(Status.SUCCESS, result.status());
        var counter = result.counter().orElseThrow();
        assertEquals(54147, counter.value());
        assertEquals("ShutterCount", counter.tag());
        assertEquals("Tag9050c", counter.table());
        assertEquals("MakerNotes › Tag9050c › ShutterCount", counter.source());
        assertEquals("54147", counter.original());
        assertEquals(CounterKind.TOTAL_EXPOSURES, counter.kind());
        assertEquals(Relation.MATCHES_SELECTED, other(result, "ShutterCount2").relation());
        assertTrue(keys(result.notes()).contains("shutter.note.silent"));
        assertFalse(keys(result.notes()).contains("shutter.note.photoElectronic"), "mechanical shutter values in this photo");
    }

    @Test void aDifferingRelatedCounterIsReportedNotChosen() {
        var result = interpret(sony("ILCE-7M4", "ARW", f(MN + "-Tag9050c", "ShutterCount", 1200), f(MN + "-Tag9050c", "ShutterCount2", 1100)));
        assertEquals(1200, result.counter().orElseThrow().value(), "the documented field wins");
        assertEquals(Relation.DIFFERS_FROM_SELECTED, other(result, "ShutterCount2").relation());
        assertTrue(keys(result.notes()).contains("shutter.note.relatedDiffers"));
    }

    @Test void conflictingPrimaryTablesUseTheDocumentedPriorityAndSaySo() {
        var result = interpret(sony("ILCE-7M4", "ARW", f(MN + "-Tag9050b", "ShutterCount", 90), f(MN + "-Tag9050c", "ShutterCount", 100)));
        assertEquals(100, result.counter().orElseThrow().value());
        assertEquals("Tag9050c", result.counter().orElseThrow().table());
        assertTrue(keys(result.notes()).contains("shutter.note.conflict"));
    }

    @Test void sonyTablesCarryTheirDocumentedMeaning() {
        var mechanical = interpret(sony("ILCE-6700", "ARW", f(MN + "-Tag9050d", "ShutterCount", 3210)));
        assertEquals(CounterKind.MECHANICAL_ACTUATIONS, mechanical.counter().orElseThrow().kind());
        var wrapping = interpret(sony("ILCE-7R", "ARW", f(MN + "-Tag9050a", "ShutterCount", 30000)));
        assertEquals(CounterKind.TOTAL_EXPOSURES, wrapping.counter().orElseThrow().kind());
        assertTrue(keys(wrapping.notes()).contains("shutter.note.modulo"));
        var dslr = interpret(sony("DSLR-A900", "ARW", f(MN + "-FocusInfo", "ShutterCount", 4500)));
        assertEquals(CounterKind.SHUTTER_COUNT, dslr.counter().orElseThrow().kind());
        assertTrue(keys(dslr.notes()).contains("shutter.note.equalsImageCount"));
        var moreInfo = interpret(sony("DSLR-A500", "ARW", f(MN + "-MoreInfo0201", "ShutterCount", 4000), f(MN + "-MoreInfo0201", "ImageCount", 3900)));
        assertEquals(4000, moreInfo.counter().orElseThrow().value());
        assertEquals(CounterKind.IMAGE_COUNT, other(moreInfo, "ImageCount").kind());
        assertEquals(Relation.SEPARATE_COUNTER, other(moreInfo, "ImageCount").relation());
    }

    @Test void imageCountIsNeverUsedAsAShutterCount() {
        var result = interpret(sony("DSLR-A500", "ARW", f(MN + "-MoreInfo0201", "ImageCount", 3900)));
        assertEquals(Status.NO_COUNTER, result.status());
        assertTrue(result.counter().isEmpty());
        assertEquals(3900, other(result, "ImageCount").value());
        assertTrue(keys(result.notes()).contains("shutter.note.imageCountOnly"));
    }

    @Test void missingCountersAreExplainedNeverZero() {
        var mechanicalOnly = interpret(sony("ILCE-7M5", "ARW", f(MN, "SonyModelID", 400), f(MN + "-Tag9050d", "SonyExposureTime", 0)));
        assertEquals(Status.NO_COUNTER, mechanicalOnly.status());
        assertEquals("shutter.reason.mechanicalOnly", mechanicalOnly.reason().orElseThrow().key());
        var compact = interpret(sony("DSC-RX100M7", "ARW", f(MN, "SonyModelID", 300)));
        assertEquals("shutter.reason.unavailableModel", compact.reason().orElseThrow().key());
        var unknown = interpret(sony("ILCE-7M4", "ARW", f(MN, "SonyModelID", 380)));
        assertEquals("shutter.reason.noCounter", unknown.reason().orElseThrow().key());
        for (var result : List.of(mechanicalOnly, compact, unknown)) assertTrue(result.counter().isEmpty());
    }

    @Test void filesWithoutMakerNotesSayWhy() {
        assertEquals("shutter.reason.noMakerNotesJpeg", interpret(sony("ILCE-7RM5", "JPEG")).reason().orElseThrow().key());
        assertEquals("shutter.reason.noMakerNotesDng", interpret(sony("ILCE-7RM5", "DNG")).reason().orElseThrow().key());
        assertEquals("shutter.reason.noMakerNotes", interpret(sony("ILCE-7RM5", "ARW")).reason().orElseThrow().key());
        assertEquals(Status.MAKERNOTES_MISSING, interpret(sony("ILCE-7RM5", "JPEG")).status());
        // A JPEG straight from the camera keeps them.
        assertEquals(Status.SUCCESS, interpret(sony("ILCE-7RM5", "JPEG", f(JPEG_MN + "-Tag9050c", "ShutterCount", 10))).status());
    }

    @Test void invalidValuesAreRejected() {
        for (Object value : List.of(0, "abc", 0x1000000, -5, "")) {
            var result = interpret(sony("ILCE-7RM5", "ARW", f(MN + "-Tag9050c", "ShutterCount", value)));
            assertEquals(Status.INVALID_COUNTER, result.status(), "value " + value);
            assertEquals("shutter.reason.invalid", result.reason().orElseThrow().key());
            assertTrue(result.counter().isEmpty());
        }
        var notAvailable = interpret(nikon("NIKON D850", f(MN, "ShutterCount", 4294965247L)));
        assertEquals(Status.INVALID_COUNTER, notAvailable.status(), "Nikon n/a value");
    }

    @Test void nikonUsesTheDocumentedMainCounterAndShowsTheOthersSeparately() {
        var result = interpret(nikon("NIKON Z 6_2", f(MN, "ShutterCount", 12345), f(MN, "MechanicalShutterCount", 10000), f(MN, "ImageCount", 9000),
            f(MN + "-ShotInfoZ7II", "ShutterCount", 12345), f(MN, "ShutterMode", 0)));
        assertEquals(Status.SUCCESS, result.status());
        var counter = result.counter().orElseThrow();
        assertEquals(12345, counter.value());
        assertEquals(CounterKind.SHOTS_INCLUDING_ELECTRONIC, counter.kind());
        assertEquals("MakerNotes", counter.table());
        assertEquals(Relation.MATCHES_SELECTED, result.others().stream().filter(reading -> reading.table().equals("ShotInfoZ7II")).findFirst().orElseThrow().relation());
        assertEquals(CounterKind.MECHANICAL_BY_NAME, other(result, "MechanicalShutterCount").kind());
        assertEquals(Relation.SEPARATE_COUNTER, other(result, "MechanicalShutterCount").relation());
        assertEquals(CounterKind.IMAGE_COUNT, other(result, "ImageCount").kind());
        assertTrue(result.notes().isEmpty(), result.notes().toString());
    }

    @Test void nikonInconsistenciesAndFallbacksAreVisible() {
        var above = interpret(nikon("NIKON Z 9", f(MN, "ShutterCount", 500), f(MN, "MechanicalShutterCount", 600)));
        assertTrue(keys(above.notes()).contains("shutter.note.mechanicalAboveTotal"));
        var shotInfoOnly = interpret(nikon("NIKON D850", f(MN + "-ShotInfoD850", "ShutterCount", 777)));
        assertEquals(777, shotInfoOnly.counter().orElseThrow().value());
        assertEquals(CounterKind.SHUTTER_COUNT, shotInfoOnly.counter().orElseThrow().kind());
        assertTrue(keys(shotInfoOnly.notes()).contains("shutter.note.nikonShotInfoOnly"));
        var differs = interpret(nikon("NIKON D850", f(MN, "ShutterCount", 800), f(MN + "-ShotInfoD850", "ShutterCount", 799)));
        assertTrue(keys(differs.notes()).contains("shutter.note.relatedDiffers"));
        var none = interpret(nikon("COOLPIX P1000"));
        assertEquals(Status.NO_COUNTER, none.status());
    }

    @Test void electronicShutterPhotosAreNoted() {
        assertTrue(keys(interpret(sony("ILCE-7RM5", "ARW", f(MN + "-Tag9050c", "ShutterCount", 9), f(MN + "-Tag9050c", "Shutter", "0 0 0"))).notes())
            .contains("shutter.note.photoElectronic"));
        assertTrue(keys(interpret(nikon("NIKON Z 9", f(MN, "ShutterCount", 9), f(MN, "ShutterMode", 16))).notes()).contains("shutter.note.photoElectronic"));
    }

    @Test void otherBrandsAndMissingCameraDataAreNotGuessed() {
        var canon = ShutterCountRules.interpret(new CameraMetadata(List.of(f(IFD0, "Make", "Canon"), f(IFD0, "Model", "Canon EOS R5"), f(MN, "ShutterCount", 5)),
            Optional.empty(), List.of()), Optional.empty());
        assertEquals(Status.UNSUPPORTED_BRAND, canon.status());
        assertTrue(canon.counter().isEmpty(), "another brand's field is never read as a Sony/Nikon counter");
        var nothing = ShutterCountRules.interpret(new CameraMetadata(List.of(f("JPEG", "FileType", "JPEG")), Optional.empty(), List.of()), Optional.empty());
        assertEquals(Status.NO_CAMERA, nothing.status());
        assertEquals(CameraModel.Brand.SONY, CameraModel.Brand.fromMake(" sony ").orElseThrow());
        assertEquals(CameraModel.Brand.NIKON, CameraModel.Brand.fromMake("NIKON CORPORATION").orElseThrow());
    }
}
