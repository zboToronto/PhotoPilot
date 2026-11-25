package com.photopilot.capability;

import com.photopilot.model.GearCapabilityAnalysis.Relation;
import com.photopilot.model.MountSystem;
import com.photopilot.service.MountNormalization;
import com.photopilot.validation.CompatibilityValidator;
import com.photopilot.model.GearSnapshot;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The report's mount reading is conservative and isolated: explicit aliases only, originals kept, F07 unchanged. */
class MountNormalizationTest {
    static Relation relation(String cameraMount, String cameraBrand, String lensMount, String lensBrand) {
        return MountNormalization.compare(MountNormalization.read(cameraMount, cameraBrand), MountNormalization.read(lensMount, lensBrand)).relation();
    }

    @Test void caseSpacingAndNamingVariantsAreInsignificant() {
        assertEquals(Relation.SAME_LABEL, relation("E", "Fixture", " e ", "Fixture"));
        assertEquals(Relation.SAME_LABEL, relation("Sony  E", "Sony", "sony e", "Sony"));
        assertEquals(Relation.EQUIVALENT_LABELS, relation("E", "Fixture", "E-mount", "Fixture"));
        assertEquals(Relation.EQUIVALENT_LABELS, relation("Z mount", "Nikon", "Z", "Viltrox"));
        assertEquals("e", MountNormalization.key("E-Mount"));
        assertEquals("ef m", MountNormalization.key("EF-M"));
    }

    @Test void explicitAliasesNeedManufacturerContext() {
        // Bare "E" on a Sony body and "Sony E" both establish Sony E.
        assertEquals(Relation.EQUIVALENT_LABELS, relation("E", "Sony", "Sony E", "Sony"));
        assertEquals(Optional.of(MountSystem.SONY_E), MountNormalization.read("E", "Sony").system());
        assertEquals(Optional.of(MountSystem.SONY_E), MountNormalization.read("Sony FE", "Tamron").system());
        assertEquals(Optional.of(MountSystem.MICRO_FOUR_THIRDS), MountNormalization.read("MFT", "Sigma").system(), "self-describing");
        assertEquals(Relation.EQUIVALENT_LABELS, relation("Micro Four Thirds", "OM System", "M4/3", "Sigma"));
        // A bare designation on a third-party item is not promoted to a system.
        assertTrue(MountNormalization.read("E", "Tamron").system().isEmpty());
        assertEquals(Relation.POSSIBLE_ALIAS, relation("Sony E", "Sony", "E", "Tamron"));
        assertEquals(Relation.POSSIBLE_ALIAS, relation("Fujifilm X", "Fujifilm", "X", "Hasselblad"), "a bare X is never merged with Fujifilm X");
    }

    @Test void distinctSystemsAndAmbiguousLabelsAreNeverMerged() {
        assertEquals(Relation.DIFFERENT_SYSTEMS, relation("RF", "Canon", "E", "Sony"));
        assertEquals(Relation.DIFFERENT_SYSTEMS, relation("Nikon Z", "Nikon", "Nikon F", "Nikon"));
        assertEquals(Relation.DIFFERENT_LABELS, relation("EF", "Canon", "EF-S", "Canon"), "EF-S lenses do not fit EF bodies; not merged");
        assertEquals(Relation.DIFFERENT_LABELS, relation("RF", "Fixture", "E", "Fixture"), "unrecognized labels that differ conclude nothing");
        assertEquals(Relation.DIFFERENT_LABELS, relation("Sony A", "Sony", "Sony E", "Sony"), "Sony A is deliberately not in the table");
        assertEquals(Relation.UNKNOWN, relation("n/a", "Sony", "E", "Sony"));
        assertEquals(Relation.UNKNOWN, relation("E", "Sony", "  ", "Sony"));
        assertEquals(Relation.UNKNOWN, relation("未知", "Sony", "未知", "Sony"), "two placeholders never match");
    }

    @Test void readingKeepsTheRecordedTextAndSessionMatchingKeepsItsExactRule() {
        var reading = MountNormalization.read("Sony E-mount", "Sony");
        assertEquals("Sony E-mount", reading.recorded());
        assertEquals(Optional.of(MountSystem.SONY_E), reading.system());
        // F07 (CompatibilityValidator) is not affected by the report's normalization: different labels stay a mismatch there.
        var camera = new GearSnapshot(UUID.randomUUID(), "CAMERA", "Sony", "A7 V", 1, Instant.EPOCH, Map.of("mount", "Sony E"), Set.of(), Map.of());
        var lens = new GearSnapshot(UUID.randomUUID(), "LENS", "Sony", "FE 20-70", 1, Instant.EPOCH, Map.of("mount", "E"), Set.of(), Map.of());
        assertEquals(CompatibilityValidator.Status.INCOMPATIBLE, new CompatibilityValidator().check(camera, lens).orElseThrow().status());
    }
}
