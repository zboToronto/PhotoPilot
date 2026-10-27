package com.photopilot.config;

import com.photopilot.i18n.*;
import java.util.List;

/** Bootstrap display data from Stage 1 section 2; not a workflow dispatcher. */
public final class FeatureCatalog {
    private FeatureCatalog() { }

    public static List<String> lines() {
        return List.of(
            "F01 " + EnglishMessages.text("catalog.F01"),
            "F02 " + EnglishMessages.text("catalog.F02"),
            "F03 " + EnglishMessages.text("catalog.F03"),
            "F04 " + EnglishMessages.text("catalog.F04"),
            "F05 " + EnglishMessages.text("catalog.F05"),
            "F06 " + EnglishMessages.text("catalog.F06"),
            "F07 " + EnglishMessages.text("catalog.F07"),
            "F08 " + EnglishMessages.text("catalog.F08"),
            "F09 " + EnglishMessages.text("catalog.F09"),
            "F10 " + EnglishMessages.text("catalog.F10"),
            "F11 " + EnglishMessages.text("catalog.F11")
        ).stream().map(name -> name + (name.startsWith("F05 ") ? " - IMPLEMENTED" : name.startsWith("F08 ") ? " - IMPLEMENTED" : " - SCAFFOLDED / NOT IMPLEMENTED")).toList();
    }

    public static List<String> lines(LocalizationService localization) {
        return java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(number -> {
            String id = "F%02d".formatted(number);
            return id + " " + localization.text("catalog." + id) + " - "
                + localization.text(number == 5 || number == 8 ? "catalog.implemented" : "catalog.scaffolded");
        }).toList();
    }
    public static String unavailable(String feature) {
        return EnglishMessages.text("feature.unavailable", feature);
    }
}
