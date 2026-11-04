package com.photopilot.service;

import java.util.List;

/**
 * F04 structured-output DTO: the exact JSON shape requested from the model for one approved image. Every value is
 * untrusted; {@link GearRecognitionService} bounds and converts it, and visible specifications are only proposals.
 */
public record GearRecognition(Boolean readable, String imageKind, List<String> visibleText, List<Candidate> candidates,
        Boolean ambiguous, String uncertainty) {
    public record Candidate(String category, String brand, String model, String modelCode, List<String> visualClues,
            String distinguishingDetails, Visible visibleSpecifications) { }
    /** Values printed or engraved on the product as seen in the image; null = not visible. */
    public record Visible(String mount, Double minFocalMm, Double maxFocalMm, Double maxAperture, Double maxApertureAtMaxFocal,
            Double filterDiameterMm, Boolean autofocus, Boolean stabilization, Double resolutionMP) { }

    /** Provider-neutral JSON Schema sent with every F04 recognition request. */
    public static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "readable": {"type": "boolean", "description": "false if the image is unreadable or shows no camera or lens."},
            "imageKind": {"type": "string", "enum": ["equipment_photo", "product_label", "purchase_screenshot", "other"]},
            "visibleText": {"type": "array", "items": {"type": "string"}, "description": "Product-relevant text read from the image; never personal information."},
            "candidates": {"type": "array", "items": {"type": "object", "properties": {
              "category": {"type": "string", "enum": ["CAMERA", "LENS", "OTHER"]},
              "brand": {"type": "string"},
              "model": {"type": "string", "description": "Model name as the manufacturer writes it."},
              "modelCode": {"type": ["string", "null"], "description": "Model number only if visible in the image."},
              "visualClues": {"type": "array", "items": {"type": "string"}},
              "distinguishingDetails": {"type": ["string", "null"], "description": "What would distinguish this candidate from the alternatives."},
              "visibleSpecifications": {"type": "object", "properties": {
                "mount": {"type": ["string", "null"]},
                "minFocalMm": {"type": ["number", "null"]},
                "maxFocalMm": {"type": ["number", "null"]},
                "maxAperture": {"type": ["number", "null"], "description": "Smallest f-number printed, e.g. 2.8 for 1:2.8."},
                "maxApertureAtMaxFocal": {"type": ["number", "null"]},
                "filterDiameterMm": {"type": ["number", "null"]},
                "autofocus": {"type": ["boolean", "null"]},
                "stabilization": {"type": ["boolean", "null"]},
                "resolutionMP": {"type": ["number", "null"]}
              }, "required": ["mount", "minFocalMm", "maxFocalMm", "maxAperture", "maxApertureAtMaxFocal", "filterDiameterMm",
                "autofocus", "stabilization", "resolutionMP"]}
            }, "required": ["category", "brand", "model", "modelCode", "visualClues", "distinguishingDetails", "visibleSpecifications"]}},
            "ambiguous": {"type": "boolean"},
            "uncertainty": {"type": ["string", "null"]}
          },
          "required": ["readable", "imageKind", "visibleText", "candidates", "ambiguous", "uncertainty"]
        }
        """;
}
