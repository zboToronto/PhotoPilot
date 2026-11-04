package com.photopilot.service;

import java.util.List;
import com.photopilot.model.CapabilityNeed;

/**
 * F02 structured-output DTO: the exact JSON shape requested from Gemini. Every value is untrusted; DiscoveryService
 * converts it and FeasibilityValidator.validate decides what is feasible. Distances, travel times and weather values
 * are deliberately absent from the schema: the application computes or retrieves them.
 */
public record DiscoveryProposal(List<Idea> suggestions, List<String> ranking, List<String> missingContext, List<String> adjustments, String summary) {
    public record Idea(String id, String subject, String genre, String setting, String placeType, String destination, String region,
            String start, Integer durationMinutes, String light, Boolean outdoor, Boolean needsClearSky, Boolean dependsOnEvent,
            List<Need> gearNeeds, List<String> usesGear, String gearNotes, String rationale, List<String> limitations) { }
    public record Need(String capability, Double minimum, Double maximum) { }

    /** Provider-neutral JSON Schema sent with every F02 request. */
    public static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "suggestions": {"type": "array", "description": "Three to six meaningfully different photography activities.",
              "items": {"type": "object", "properties": {
                "id": {"type": "string", "description": "Short unique id such as S1."},
                "subject": {"type": "string", "description": "What to photograph, as a short activity title."},
                "genre": {"type": "string", "description": "Photography genre, e.g. landscape, street, architecture, aviation."},
                "setting": {"type": "string", "description": "Kind of place or scene and what makes it worth photographing."},
                "placeType": {"type": "string", "enum": ["atLocation", "destination"]},
                "destination": {"type": ["string", "null"], "description": "For a destination: one searchable place name (town, neighbourhood, park or landmark), otherwise null."},
                "region": {"type": ["string", "null"], "description": "Province/state and country of the destination, otherwise null."},
                "start": {"type": "string", "description": "Local start as YYYY-MM-DDTHH:MM with the UTC offset shown in the evidence."},
                "durationMinutes": {"type": "integer", "description": "Time spent photographing, in minutes."},
                "light": {"type": "string", "enum": ["daylight", "goldenHour", "blueHour", "twilight", "night", "mixed", "any"]},
                "outdoor": {"type": "boolean", "description": "true when weather affects the activity."},
                "needsClearSky": {"type": "boolean", "description": "true only when a clear sky is essential."},
                "dependsOnEvent": {"type": "boolean", "description": "true when the activity only works during a scheduled event."},
                "gearNeeds": {"type": "array", "items": {"type": "object", "properties": {
                    "capability": {"type": "string", "enum": %s},
                    "minimum": {"type": ["number", "null"]}, "maximum": {"type": ["number", "null"]}},
                  "required": ["capability", "minimum", "maximum"]}, "description": "Hard technical requirements only. focalLength: minimum = widest (shortest) focal length needed, maximum = longest reach needed, in mm. autofocus and stabilization: minimum and maximum null."},
                "usesGear": {"type": "array", "items": {"type": "string"}, "description": "Inventory labels (G1, G2, ...) of owned gear to use."},
                "gearNotes": {"type": "string", "description": "How to use the owned gear."},
                "rationale": {"type": "string", "description": "Why this suits the user's circumstances."},
                "limitations": {"type": "array", "items": {"type": "string"}, "description": "Uncertainties and things to check."}},
                "required": ["id", "subject", "genre", "setting", "placeType", "destination", "region", "start", "durationMinutes", "light",
                  "outdoor", "needsClearSky", "dependsOnEvent", "gearNeeds", "usesGear", "gearNotes", "rationale", "limitations"]}},
            "ranking": {"type": "array", "items": {"type": "string"}, "description": "Suggestion ids from most to least suitable."},
            "missingContext": {"type": "array", "items": {"type": "string"}, "description": "Details that would improve the suggestions."},
            "adjustments": {"type": "array", "items": {"type": "string"}, "description": "Changes the user could make if constraints are restrictive."},
            "summary": {"type": "string", "description": "One or two sentences."}
          },
          "required": ["suggestions", "ranking", "missingContext", "adjustments", "summary"]
        }
        """.formatted(new com.google.gson.Gson().toJson(CapabilityNeed.SUPPORTED.keySet()));
}
