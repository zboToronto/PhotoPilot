package com.photopilot.service;

import java.util.List;

/**
 * F09 structured-output DTO: the exact JSON shape requested from Gemini. Every value is untrusted;
 * {@link EnvironmentService} converts it and {@code FeasibilityValidator.checkTiming} decides what is shown.
 */
public record EnvironmentInterpretation(List<Window> windows, List<String> considerations, List<String> alternatives, String summary) {
    public record Window(String label, String start, String end, String light, String conditions, String rationale, List<String> evidence) { }

    /** Provider-neutral JSON Schema sent with every F09 request. */
    public static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "windows": {"type": "array", "description": "Zero to four proposed shooting windows inside the availability period.",
              "items": {"type": "object", "properties": {
                "label": {"type": "string", "description": "Short name of the window."},
                "start": {"type": "string", "description": "Local start as YYYY-MM-DDTHH:MM with the UTC offset used in the evidence, e.g. 2026-11-07T15:30-05:00."},
                "end": {"type": "string", "description": "Local end in the same format."},
                "light": {"type": "string", "enum": ["daylight", "goldenHour", "blueHour", "twilight", "night", "mixed"]},
                "conditions": {"type": "string", "description": "Expected conditions, quoting only evidence values."},
                "rationale": {"type": "string", "description": "Why this window suits the subject and goals."},
                "evidence": {"type": "array", "items": {"type": "string"}, "description": "Evidence ids this window relies on."}},
                "required": ["label", "start", "end", "light", "conditions", "rationale", "evidence"]}},
            "considerations": {"type": "array", "items": {"type": "string"}, "description": "Practical lighting and weather considerations."},
            "alternatives": {"type": "array", "items": {"type": "string"}, "description": "Alternatives within the availability period when conditions are unfavourable."},
            "summary": {"type": "string", "description": "One or two sentences; say so plainly if no window is defensible."}
          },
          "required": ["windows", "considerations", "alternatives", "summary"]
        }
        """;
}
