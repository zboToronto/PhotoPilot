package com.photopilot.service;

import java.util.List;

/**
 * F11 structured-output DTO: the exact JSON shape requested from Gemini for a post-shoot review (inside the agent's
 * reply envelope). Every value is untrusted; ReviewEvidence removes statements that cite figures absent from the
 * evidence or claim image content, quality or scores before anything becomes a PostShootReview draft.
 */
public record ReviewInterpretation(String summary, List<String> technicalObservations, List<String> equipmentObservations,
        List<String> exposureObservations, List<String> lessons, List<String> improvements, List<String> limitations) {
    static final String TEXTS = "{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"maxItems\":6}";
    /** Provider-neutral JSON Schema of the final result. */
    public static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
        + "\"summary\":{\"type\":\"string\",\"description\":\"Two to four sentences grounded in the evidence\"},"
        + "\"technicalObservations\":" + TEXTS + ",\"equipmentObservations\":" + TEXTS + ",\"exposureObservations\":" + TEXTS + ","
        + "\"lessons\":" + TEXTS + ",\"improvements\":" + TEXTS + ",\"limitations\":" + TEXTS + "},"
        + "\"required\":[\"summary\",\"technicalObservations\",\"equipmentObservations\",\"exposureObservations\",\"lessons\",\"improvements\",\"limitations\"]}";
}
