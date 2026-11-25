package com.photopilot.service;

import com.photopilot.service.EquipmentIdentity.Basis;
import com.photopilot.service.EquipmentIdentity.Relation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage2OptRe1 PP-03: model identity of equipment names from different sources. Before, F11 compared names by tokens
 * only, so the planned "Sony Alpha 7 IV" and the recorded EXIF "SONY ILCE-7M4" counted as two cameras although the
 * camera catalog lists both for one model. Catalog-backed aliases now match; nothing else is merged.
 */
class EquipmentIdentityTest {
    static void relation(Relation expected, Basis basis, String first, String second) {
        var c = EquipmentIdentity.cameras(first, second);
        assertEquals(expected, c.relation(), first + " / " + second);
        assertEquals(basis, c.basis(), first + " / " + second);
        assertEquals(expected, EquipmentIdentity.cameras(second, first).relation(), "symmetric: " + first + " / " + second);
    }

    @Test void catalogAliasesOfOneSonyModelAreTheSameModel() {
        relation(Relation.SAME_MODEL, Basis.CATALOG, "Sony Alpha 7 IV", "SONY ILCE-7M4");
        relation(Relation.SAME_MODEL, Basis.CATALOG, "Sony α7 IV", "ILCE-7M4");
        relation(Relation.SAME_MODEL, Basis.CATALOG, "Sony A7 IV", "SONY ILCE-7M4");
        relation(Relation.SAME_MODEL, Basis.CATALOG, "α7 IV", "Sony A7M4");
        assertTrue(EquipmentIdentity.cameras("Sony Alpha 7 IV", "SONY ILCE-7M4").alias());
        assertEquals("ILCE-7M4", EquipmentIdentity.camera("Sony Alpha 7 IV").orElseThrow().id());
        assertEquals("Sony α7 IV (ILCE-7M4)", EquipmentIdentity.catalogLabel("SONY ILCE-7M4").orElseThrow());
    }

    @Test void manufacturerCaseWhitespaceAndUnicodeAreNormalized() {
        relation(Relation.SAME_MODEL, Basis.NAME, "sony ilce-7m4", "SONY  ILCE-7M4");
        relation(Relation.SAME_MODEL, Basis.NAME, "ILCE-7M4", "SONY ILCE-7M4");
        relation(Relation.SAME_MODEL, Basis.NAME, "ＳＯＮＹ ＩＬＣＥ－７Ｍ４", "SONY ILCE-7M4");
        relation(Relation.SAME_MODEL, Basis.CATALOG, "NIKON CORPORATION NIKON Z 6_2", "Nikon Z6II");
        relation(Relation.SAME_MODEL, Basis.NAME, "NIKON CORPORATION NIKON Z 6_2", "Nikon Z 6_2");
    }

    @Test void confirmedDifferentModelsAreDifferent() {
        relation(Relation.DIFFERENT_MODEL, Basis.CATALOG, "Sony A7 IV", "SONY ILCE-7RM4");
        relation(Relation.DIFFERENT_MODEL, Basis.CATALOG, "Sony A1", "SONY ILCE-7M4");
        relation(Relation.DIFFERENT_MODEL, Basis.CATALOG, "Sony α7 IV", "Sony α7 III");
        relation(Relation.DIFFERENT_MODEL, Basis.MANUFACTURER, "Sony A1", "NIKON CORPORATION NIKON Z 8");
        relation(Relation.DIFFERENT_MODEL, Basis.MANUFACTURER, "Canon EOS R5", "SONY ILCE-7M4");
    }

    @Test void unknownSpellingsAndUncataloguedBrandsAreNeverMerged() {
        relation(Relation.UNKNOWN, Basis.NONE, "Sony A7 Mark IV", "SONY ILCE-7M4");
        relation(Relation.UNKNOWN, Basis.NONE, "Canon EOS R5", "Canon EOS R6");
        relation(Relation.UNKNOWN, Basis.NONE, "Audit Body", "SONY ILCE-7M4");
        // Unsafe fuzzy matches stay apart: one letter or a mark number is a different catalogued model.
        relation(Relation.DIFFERENT_MODEL, Basis.CATALOG, "Sony A7 IV", "Sony A7S III");
        assertTrue(EquipmentIdentity.camera("Sony A7 Mark IV").isEmpty(), "no catalog spelling, no resolution");
    }

    @Test void missingNamesAreUnknownNeverTheSameModel() {
        relation(Relation.UNKNOWN, Basis.MISSING, "", "SONY ILCE-7M4");
        relation(Relation.UNKNOWN, Basis.MISSING, "----", "SONY ILCE-7M4");
        assertEquals(Relation.UNKNOWN, EquipmentIdentity.cameras(null, null).relation());
        assertEquals(Relation.UNKNOWN, EquipmentIdentity.lenses(null, "FE 55mm F1.8 ZA").relation());
    }

    @Test void identicalModelsSayNothingAboutTheBody() {
        // Two photos of one model may come from two bodies: the relation is about models only, and no basis is a body.
        var same = EquipmentIdentity.cameras("SONY ILCE-7M4", "SONY ILCE-7M4");
        assertEquals(Relation.SAME_MODEL, same.relation()); assertEquals(Basis.NAME, same.basis());
        assertFalse(same.alias(), "not a catalog alias");
    }

    @Test void lensesDifferOnlyByWrittenFocalLengthOrAperture() {
        assertEquals(Relation.SAME_MODEL, EquipmentIdentity.lenses("Sony FE 24-70mm F2.8 GM II", "FE 24-70mm F2.8 GM II").relation());
        assertEquals(Relation.DIFFERENT_MODEL, EquipmentIdentity.lenses("FE 55mm F1.8 ZA", "FE 50mm F1.8").relation());
        assertEquals(Relation.DIFFERENT_MODEL, EquipmentIdentity.lenses("FE 24-70mm F2.8 GM", "FE 24-70mm F4 ZA OSS").relation());
        assertEquals(Relation.DIFFERENT_MODEL, EquipmentIdentity.lenses("Sony FE 200-600mm G", "Sony FE 100-400mm GM").relation());
        assertEquals(Relation.UNKNOWN, EquipmentIdentity.lenses("FE 24-70mm F2.8 GM", "FE 24-70mm F2.8 GM II").relation(), "same numbers, other version");
        assertEquals(Relation.UNKNOWN, EquipmentIdentity.lenses("Audit 50", "Sony FE 55mm F1.8 ZA").relation(), "no focal length written");
        assertEquals(Relation.UNKNOWN, EquipmentIdentity.lenses("ZEISS FE 55mm F1.8 ZA", "Sony FE 55mm F1.8 ZA").relation(), "co-branded makers are not compared");
    }
}
