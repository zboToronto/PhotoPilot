package com.photopilot.service;

import java.util.List;

/**
 * F10/UC11 structured-output DTO: Gemini's interpretation of a natural-language change request. {@code status} is
 * change, clarify (ambiguous; questions asked) or conflict (cannot be met without breaking a fixed constraint).
 * {@code changes} names the plan sections to replace; only those are turned into PlanCommands, so unrelated
 * decisions are preserved. Every value is untrusted and validated by PlanEditor/PlanValidator.
 */
public record PlanChangeInterpretation(String status, String summary, List<String> questions, String conflict, List<String> changes,
        String loadout, List<PlanGeneration.Mount> mounts, List<String> equipmentNotes, List<PlanGeneration.Window> schedule,
        List<PlanGeneration.Setting> settings, PlanGeneration.Strategy strategy, List<PlanGeneration.Priority> shotPriorities,
        List<String> preparation, List<String> checklist, List<String> limitations) {

    /** Section names the model may list in {@code changes}. */
    public static final List<String> SECTIONS = List.of("loadout", "schedule", "settings", "strategy", "shotPriorities", "preparation", "checklist");

    public static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
        + "\"status\":{\"type\":\"string\",\"enum\":[\"change\",\"clarify\",\"conflict\"]},"
        + "\"summary\":{\"type\":\"string\",\"description\":\"What will change and why, in one or two sentences\"},"
        + "\"questions\":" + PlanGeneration.TEXTS + ",\"conflict\":{\"type\":\"string\",\"description\":\"Which fixed constraint the request conflicts with\"},"
        + "\"changes\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"loadout\",\"schedule\",\"settings\",\"strategy\",\"shotPriorities\",\"preparation\",\"checklist\"]}},"
        + "\"loadout\":{\"type\":\"string\",\"description\":\"New candidate label when changes include loadout\"},"
        + "\"mounts\":{\"type\":\"array\",\"items\":" + PlanGeneration.MOUNT + "},\"equipmentNotes\":" + PlanGeneration.TEXTS + ","
        + "\"schedule\":{\"type\":\"array\",\"items\":" + PlanGeneration.WINDOW + "},"
        + "\"settings\":{\"type\":\"array\",\"items\":" + PlanGeneration.SETTING + "},\"strategy\":" + PlanGeneration.STRATEGY + ","
        + "\"shotPriorities\":{\"type\":\"array\",\"items\":" + PlanGeneration.PRIORITY + "},"
        + "\"preparation\":" + PlanGeneration.TEXTS + ",\"checklist\":" + PlanGeneration.TEXTS + ",\"limitations\":" + PlanGeneration.TEXTS + "},"
        + "\"required\":[\"status\",\"summary\",\"questions\",\"conflict\",\"changes\"]}";
}
