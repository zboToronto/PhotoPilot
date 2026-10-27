package com.photopilot.config;

import java.util.List;

/** Bootstrap display data from Stage 1 section 2; not a workflow dispatcher. */
public final class FeatureCatalog {
    private FeatureCatalog() { }

    public static List<String> lines() {
        return List.of(
            "F01 Photography Session and History Management",
            "F02 AI Photography Inspiration and Activity Recommendation",
            "F03 Natural-Language Photography Requirement Parsing",
            "F04 AI Gear Recognition and Automatic Import",
            "F05 Photography Gear Inventory Management",
            "F06 AI Photography Equipment Capability Requirement Analysis",
            "F07 Owned Gear Matching and Compatibility Validation",
            "F08 Photography Technical Calculation Tool Suite",
            "F09 Location, Time, and Environment-Aware Photography Planning",
            "F10 AI Shoot Plan Generation and Interactive Modification",
            "F11 AI Post-Shoot Analysis and Review"
        ).stream().map(name -> name + (name.startsWith("F05 ") ? " - IMPLEMENTED" : name.startsWith("F08 ") ? " - IMPLEMENTED" : " - SCAFFOLDED / NOT IMPLEMENTED")).toList();
    }

    public static String unavailable(String feature) {
        return "Not implemented: " + feature + ". Stage 2 scaffolding build; no operation performed.";
    }
}
