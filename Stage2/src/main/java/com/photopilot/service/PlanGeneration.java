package com.photopilot.service;

import java.util.List;

/**
 * F10 structured-output DTO for a generated plan: the exact JSON shape requested from the model (inside the agent's
 * tool envelope). Every value is untrusted. Equipment is referenced only by the application's candidate and gear
 * labels; {@link PlanAssembly} maps them back to verified loadouts and PlanValidator checks the result.
 */
public record PlanGeneration(String summary, String loadout, List<Mount> mounts, List<String> equipmentNotes, List<Setting> settings,
        Strategy strategy, List<Window> schedule, List<String> preparation, List<String> checklist, List<Priority> shotPriorities,
        List<String> limitations, List<String> questions) {
    public record Mount(String camera, String lens, String role) { }
    public record Setting(String setting, String value, String camera) { }
    public record Strategy(String approach, String technical, String autofocus, String exposure, String composition) { }
    public record Window(String label, String start, String end, String arrival, String light, String conditions, List<String> evidence) { }
    public record Priority(String priority, String description, String tradeOff) { }

    static final String SETTING_NAMES = "\"exposureMode\",\"shutterSpeed\",\"aperture\",\"iso\",\"exposureCompensation\",\"focalLength\","
        + "\"autofocusMode\",\"afArea\",\"driveMode\",\"stabilization\",\"meteringMode\",\"whiteBalance\",\"fileFormat\"";
    static final String MOUNT = "{\"type\":\"object\",\"properties\":{\"camera\":{\"type\":\"string\",\"description\":\"Camera gear label, e.g. G1\"},"
        + "\"lens\":{\"type\":\"string\",\"description\":\"Lens gear label mounted on that camera\"},\"role\":{\"type\":\"string\"}},\"required\":[\"camera\",\"lens\",\"role\"]}";
    static final String SETTING = "{\"type\":\"object\",\"properties\":{\"setting\":{\"type\":\"string\",\"enum\":[" + SETTING_NAMES + "]},"
        + "\"value\":{\"type\":\"string\",\"description\":\"Starting value or range with units, e.g. 1/1000-1/2000 s, f/5.6-f/8, 100-3200\"},"
        + "\"camera\":{\"type\":\"string\",\"description\":\"Camera gear label, or all\"}},\"required\":[\"setting\",\"value\",\"camera\"]}";
    static final String STRATEGY = "{\"type\":\"object\",\"properties\":{\"approach\":{\"type\":\"string\"},\"technical\":{\"type\":\"string\"},"
        + "\"autofocus\":{\"type\":\"string\"},\"exposure\":{\"type\":\"string\"},\"composition\":{\"type\":\"string\"}},"
        + "\"required\":[\"approach\",\"technical\",\"autofocus\",\"exposure\",\"composition\"]}";
    static final String WINDOW = "{\"type\":\"object\",\"properties\":{\"label\":{\"type\":\"string\"},"
        + "\"start\":{\"type\":\"string\",\"description\":\"Local time YYYY-MM-DDTHH:MM in the timing zone\"},\"end\":{\"type\":\"string\"},"
        + "\"arrival\":{\"type\":\"string\",\"description\":\"Suggested arrival (local time), not before availability start; empty if not supportable\"},"
        + "\"light\":{\"type\":\"string\",\"enum\":[\"daylight\",\"goldenHour\",\"blueHour\",\"twilight\",\"night\",\"mixed\"]},"
        + "\"conditions\":{\"type\":\"string\"},\"evidence\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Environment evidence ids\"}},"
        + "\"required\":[\"label\",\"start\",\"end\",\"arrival\",\"light\",\"conditions\",\"evidence\"]}";
    static final String PRIORITY = "{\"type\":\"object\",\"properties\":{\"priority\":{\"type\":\"string\",\"enum\":[\"primary\",\"secondary\"]},"
        + "\"description\":{\"type\":\"string\"},\"tradeOff\":{\"type\":\"string\"}},\"required\":[\"priority\",\"description\",\"tradeOff\"]}";
    static final String TEXTS = "{\"type\":\"array\",\"items\":{\"type\":\"string\"}}";

    /** Provider-neutral JSON Schema of the final result. */
    public static final String SCHEMA = "{\"type\":\"object\",\"properties\":{"
        + "\"summary\":{\"type\":\"string\",\"description\":\"Two or three sentences explaining the plan\"},"
        + "\"loadout\":{\"type\":\"string\",\"description\":\"Chosen candidate label (L1, L2, ...) or none\"},"
        + "\"mounts\":{\"type\":\"array\",\"items\":" + MOUNT + "},"
        + "\"equipmentNotes\":" + TEXTS + ",\"settings\":{\"type\":\"array\",\"items\":" + SETTING + "},\"strategy\":" + STRATEGY + ","
        + "\"schedule\":{\"type\":\"array\",\"items\":" + WINDOW + "},\"preparation\":" + TEXTS + ","
        + "\"checklist\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Non-equipment items only\"},"
        + "\"shotPriorities\":{\"type\":\"array\",\"items\":" + PRIORITY + "},\"limitations\":" + TEXTS + ",\"questions\":" + TEXTS + "},"
        + "\"required\":[\"summary\",\"loadout\",\"mounts\",\"equipmentNotes\",\"settings\",\"strategy\",\"schedule\",\"preparation\",\"checklist\","
        + "\"shotPriorities\",\"limitations\",\"questions\"]}";
}
