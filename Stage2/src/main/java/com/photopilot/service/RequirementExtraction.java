package com.photopilot.service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import com.photopilot.model.ShootRequirement;

/**
 * F03 structured-output DTO: the exact JSON shape requested from the model. Every value is untrusted and
 * optional (null = not stated); {@link RequirementService} converts it into a draft and the domain validates it.
 */
public record RequirementExtraction(String subject, List<String> goals, String date, String startTime, String timeWindow,
        String location, Integer durationMinutes, Integer cameraCount, Boolean lensChangesAllowed, Double maxWeightGrams,
        String portability, List<String> gearConstraints, List<Priority> priorities, List<Constraint> fixedConstraints,
        List<String> clarificationQuestions, List<String> flaggedFields) {
    public record Priority(String name, Integer rank) { }
    public record Constraint(String name, String value) { }

    /** Provider-neutral JSON Schema sent with every F03 request. */
    public static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "subject": {"type": ["string", "null"], "description": "Photography subject or genre as the user stated it."},
            "goals": {"type": "array", "items": {"type": "string"}, "description": "Intended images or shooting goals."},
            "date": {"type": ["string", "null"], "description": "Shooting date as YYYY-MM-DD, only if stated or unambiguous."},
            "startTime": {"type": ["string", "null"], "description": "Clock start time as HH:MM (24-hour), only if a clock time was stated."},
            "timeWindow": {"type": ["string", "null"], "description": "Descriptive timing such as 'around sunset' or 'early morning'."},
            "location": {"type": ["string", "null"], "description": "Place as the user stated it."},
            "durationMinutes": {"type": ["integer", "null"], "description": "Expected shooting duration in minutes, only if stated."},
            "cameraCount": {"type": ["integer", "null"], "description": "Number of camera bodies the user wants to carry or use, only if stated."},
            "lensChangesAllowed": {"type": ["boolean", "null"], "description": "false if the user wants to avoid lens changes, true if explicitly allowed, otherwise null."},
            "maxWeightGrams": {"type": ["number", "null"], "description": "Maximum total gear weight in grams, only if a numeric limit was stated."},
            "portability": {"type": ["string", "null"], "description": "Portability or weight preference in the user's words."},
            "gearConstraints": {"type": "array", "items": {"type": "string"}, "description": "Equipment restrictions or requirements the user stated."},
            "priorities": {"type": "array", "items": {"type": "object", "properties": {"name": {"type": "string"}, "rank": {"type": "integer", "description": "1 = most important"}}, "required": ["name", "rank"]}},
            "fixedConstraints": {"type": "array", "items": {"type": "object", "properties": {"name": {"type": "string"}, "value": {"type": "string"}}, "required": ["name", "value"]}},
            "clarificationQuestions": {"type": "array", "items": {"type": "string"}},
            "flaggedFields": {"type": "array", "items": {"type": "string"}, "description": "Names of fields above that are ambiguous or contradictory."}
          },
          "required": ["subject", "goals", "date", "startTime", "timeWindow", "location", "durationMinutes", "cameraCount",
            "lensChangesAllowed", "maxWeightGrams", "portability", "gearConstraints", "priorities", "fixedConstraints",
            "clarificationQuestions", "flaggedFields"]
        }
        """;

    /** Current draft in the same shape, sent as evidence when the user answers clarification questions. */
    static RequirementExtraction from(ShootRequirement draft) {
        return new RequirementExtraction(draft.subject(), draft.goals(), draft.date().map(Object::toString).orElse(null),
            draft.time().map(time -> time.format(DateTimeFormatter.ofPattern("HH:mm"))).orElse(null), blankToNull(draft.timeWindow()),
            draft.location().map(place -> place.label()).orElse(null), draft.duration().map(value -> (int) value.toMinutes()).orElse(null),
            draft.cameraCount().orElse(null), draft.lensChangesAllowed().orElse(null), draft.maxWeightGrams().orElse(null),
            blankToNull(draft.portability()), draft.gearConstraints(),
            draft.priorities().entrySet().stream().map(entry -> new Priority(entry.getKey(), entry.getValue())).toList(),
            draft.fixedConstraints().entrySet().stream().map(entry -> new Constraint(entry.getKey(), entry.getValue())).toList(),
            draft.clarificationQuestions(), draft.reviewFields().stream().map(field -> SCHEMA_NAMES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(field)).map(java.util.Map.Entry::getKey).findFirst().orElse(field)).sorted().toList());
    }
    /** Schema property -> RequirementForm field, for the names that differ. */
    static final java.util.Map<String, String> SCHEMA_NAMES = java.util.Map.of("startTime", "time", "durationMinutes", "duration",
        "lensChangesAllowed", "lensChanges", "maxWeightGrams", "maxWeight", "fixedConstraints", "constraints");
    private static String blankToNull(String text) { return Objects.toString(text, "").isBlank() ? null : text; }
}
