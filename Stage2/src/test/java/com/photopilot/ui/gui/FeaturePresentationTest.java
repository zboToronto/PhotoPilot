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
        // Sidebar sections (2026-11-23 session-centric navigation): every page belongs to exactly one, in catalog order.
        assertEquals(List.of("F01", "F02"), features.stream()
            .filter(feature -> feature.group().equals("General")).map(FeaturePresentation::id).toList());
        assertEquals(List.of("F03", "F10", "F06", "F07", "F09", "F11"), features.stream()
            .filter(feature -> feature.group().equals("Active session")).map(FeaturePresentation::id).toList());
        assertEquals(List.of("F04", "F05"), features.stream()
            .filter(feature -> feature.group().equals("Equipment management")).map(FeaturePresentation::id).toList());
        assertEquals(List.of("F08"), features.stream()
            .filter(feature -> feature.group().equals("Photography tools")).map(FeaturePresentation::id).toList());
        assertEquals("AI Gear Import", features.stream().filter(feature -> feature.id().equals("F04")).findFirst().orElseThrow().title());
        for (var feature : features) assertEquals(FeaturePresentation.Status.IMPLEMENTED, feature.status(), feature.id());
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
