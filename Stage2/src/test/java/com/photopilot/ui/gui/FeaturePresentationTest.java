package com.photopilot.ui.gui;

import com.photopilot.config.FeatureCatalog;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeaturePresentationTest {
    @Test void navigationCoversEveryOriginalFeatureExactlyOnce() {
        var features = FeaturePresentation.all();
        Set<String> expected = IntStream.rangeClosed(1, 11).mapToObj("F%02d"::formatted).collect(Collectors.toSet());
        assertEquals(11, features.size());
        assertEquals(expected, features.stream().map(FeaturePresentation::id).collect(Collectors.toSet()));
        assertEquals(expected, FeatureCatalog.lines().stream().map(line -> line.substring(0, 3)).collect(Collectors.toSet()));
        assertEquals(List.of("F01", "F02", "F03", "F10"), features.stream()
            .filter(feature -> feature.group().equals("Workspace")).map(FeaturePresentation::id).toList());
        assertEquals("AI Gear Import", features.stream().filter(feature -> feature.id().equals("F04")).findFirst().orElseThrow().title());
        for (var feature : features) assertEquals(feature.id().equals("F05") ? FeaturePresentation.Status.IMPLEMENTED : FeaturePresentation.Status.SCAFFOLDED, feature.status());
    }

    @Test void missingOrAmbiguousStatusesNeverClaimImplementation() {
        var feature = FeaturePresentation.all().getFirst();
        assertEquals(FeaturePresentation.Status.UNAVAILABLE, feature.statusFrom(List.of()));
        assertEquals(FeaturePresentation.Status.UNAVAILABLE, feature.statusFrom(List.of("F01 Sessions - NOT IMPLEMENTED")));
        assertEquals(FeaturePresentation.Status.UNAVAILABLE, feature.statusFrom(List.of("F01 Sessions")));
        assertEquals(FeaturePresentation.Status.UNAVAILABLE, feature.statusFrom(List.of("F010 Sessions - IMPLEMENTED")));
        assertEquals(FeaturePresentation.Status.IN_PROGRESS, feature.statusFrom(List.of("F01 Sessions - IN PROGRESS")));
        assertEquals(FeaturePresentation.Status.IMPLEMENTED, feature.statusFrom(List.of("F01 Sessions - IMPLEMENTED")));
    }
}
